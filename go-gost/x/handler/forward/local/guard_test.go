package local_test

import (
	"bufio"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	service_parser "github.com/go-gost/x/config/parsing/service"
	xlogger "github.com/go-gost/x/logger"
)

// startEcho returns a TCP server that writes back everything it reads.
func startEcho(t *testing.T) net.Listener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	return ln
}

func startForward(t *testing.T, target string, metadata map[string]any) service.Service {
	t.Helper()
	corelogger.SetDefault(xlogger.Nop())
	svc, err := service_parser.ParseService(&config.ServiceConfig{
		Name:     "guard-test-" + strings.ReplaceAll(t.Name(), "/", "-"),
		Addr:     "127.0.0.1:0",
		Handler:  &config.HandlerConfig{Type: "tcp", Metadata: metadata},
		Listener: &config.ListenerConfig{Type: "tcp"},
		Forwarder: &config.ForwarderConfig{
			Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	go svc.Serve()
	t.Cleanup(func() { svc.Close() })
	return svc
}

// roundTrip sends payload and reports what comes back, or "" when the forward closes the
// connection instead.
func roundTrip(t *testing.T, addr string, payload []byte, want int) string {
	t.Helper()
	conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		return ""
	}
	buf := make([]byte, want)
	n, _ := io.ReadFull(conn, buf)
	return string(buf[:n])
}

func TestBlockedProtocolsAreClosedAndOthersPass(t *testing.T) {
	echo := startEcho(t)
	defer echo.Close()
	svc := startForward(t, echo.Addr().String(), map[string]any{"forward.block": "socks,http"})
	addr := svc.Addr().String()

	if got := roundTrip(t, addr, []byte{0x05, 0x01, 0x00}, 3); got != "" {
		t.Fatalf("SOCKS5 greeting was forwarded: %q", got)
	}
	if got := roundTrip(t, addr, []byte("CONNECT example.com:443 HTTP/1.1\r\n\r\n"), 7); got != "" {
		t.Fatalf("HTTP CONNECT was forwarded: %q", got)
	}
	// TLS is not blocked here, and arbitrary binary data passes.
	tlsHello := []byte{0x16, 0x03, 0x01, 0x00, 0x05, 1, 2, 3, 4, 5}
	if got := roundTrip(t, addr, tlsHello, len(tlsHello)); got != string(tlsHello) {
		t.Fatalf("TLS record was blocked: %q", got)
	}
	if got := roundTrip(t, addr, []byte("hello world"), 11); got != "hello world" {
		t.Fatalf("plain data was blocked: %q", got)
	}
}

func TestServerFirstProtocolsAreNotHeldBack(t *testing.T) {
	// A target that greets first, like SSH or MySQL: the client sends nothing until it has
	// read the banner, so the forward must not wait for client bytes forever.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_, _ = conn.Write([]byte("SSH-2.0-test\r\n"))
		time.Sleep(time.Second)
	}()
	svc := startForward(t, ln.Addr().String(), map[string]any{"forward.block": "socks,http,tls"})
	conn, err := net.Dial("tcp", svc.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	// The banner must arrive at once, not after waiting for client bytes.
	conn.SetDeadline(time.Now().Add(time.Second))
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil || line != "SSH-2.0-test\r\n" {
		t.Fatalf("server banner not delivered: %q %v", line, err)
	}
}

func TestProxyProtocolHeaderReachesTheTarget(t *testing.T) {
	echo := startEcho(t)
	defer echo.Close()
	svc := startForward(t, echo.Addr().String(), map[string]any{"forward.proxyProtocol": 1})
	conn, err := net.Dial("tcp", svc.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(conn)
	header, err := reader.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	clientPort := conn.LocalAddr().(*net.TCPAddr).Port
	if !strings.HasPrefix(header, "PROXY TCP4 127.0.0.1 127.0.0.1 ") || !strings.Contains(header, " "+strconv.Itoa(clientPort)+" ") {
		t.Fatalf("unexpected PROXY header %q for client port %d", header, clientPort)
	}
	body := make([]byte, 4)
	if _, err := io.ReadFull(reader, body); err != nil || string(body) != "ping" {
		t.Fatalf("payload after header: %q %v", body, err)
	}
}

func TestMaxConnsRejectsTheExtraConnection(t *testing.T) {
	echo := startEcho(t)
	defer echo.Close()
	svc := startForward(t, echo.Addr().String(), map[string]any{"forward.maxConns": 1})
	addr := svc.Addr().String()

	first, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	first.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := first.Write([]byte("a")); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(first, make([]byte, 1)); err != nil {
		t.Fatal(err)
	}
	if got := roundTrip(t, addr, []byte("b"), 1); got != "" {
		t.Fatalf("second concurrent connection was accepted: %q", got)
	}
	first.Close()
	time.Sleep(200 * time.Millisecond)
	if got := roundTrip(t, addr, []byte("c"), 1); got != "c" {
		t.Fatalf("connection after the first closed was refused: %q", got)
	}
}
