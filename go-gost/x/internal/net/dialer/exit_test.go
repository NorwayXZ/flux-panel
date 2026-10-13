package dialer

import (
	"context"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	xlogger "github.com/go-gost/x/logger"
)

// A service bound to an exit group must never reach its target through the
// node's own address, whatever state the exit pool is in.
func TestExitGroupDialNeverFallsBackToTheNodeAddress(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()

	log := xlogger.NewLogger(xlogger.OutputOption(io.Discard))
	previous := ExitInterfaceResolver
	defer func() { ExitInterfaceResolver = previous }()
	resolvers := map[string]func(string) (string, error){
		"no exit pool":          nil,
		"no usable exit":        func(string) (string, error) { return "", ErrExitUnavailable },
		"empty device":          func(string) (string, error) { return "", nil },
		"device does not exist": func(string) (string, error) { return "cnxmissing0000", nil },
	}
	for name, resolver := range resolvers {
		ExitInterfaceResolver = resolver
		for _, network := range []string{"tcp", "udp"} {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			conn, err := (&Dialer{Interface: ExitInterfacePrefix + "jp", Logger: log}).Dial(ctx, network, listener.Addr().String())
			cancel()
			if err == nil {
				conn.Close()
				t.Errorf("%s (%s): the connection must fail instead of leaving unbound", name, network)
			}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		if conn, err := (&Dialer{Interface: ExitInterfacePrefix + "jp", Logger: log}).Dial(ctx, "udp", ""); err == nil {
			conn.Close()
			t.Errorf("%s: a UDP association must fail instead of using an unbound socket", name)
		}
		cancel()
	}

	// The ordinary interface option keeps its behaviour.
	if conn, err := (&Dialer{Logger: log}).Dial(context.Background(), "tcp", listener.Addr().String()); err != nil {
		t.Fatalf("plain dial failed: %v", err)
	} else {
		conn.Close()
	}
	if group, ok := ExitGroup("cnexit:jp"); !ok || group != "jp" {
		t.Fatalf("unexpected exit group %q %v", group, ok)
	}
	if _, ok := ExitGroup("eth0"); ok {
		t.Fatal("a device name is not an exit group")
	}
	if !errors.Is(ErrExitUnavailable, ErrExitUnavailable) {
		t.Fatal("unreachable")
	}
}
