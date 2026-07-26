package local_test

import (
	"bufio"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	service_parser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/tcp"
	xlogger "github.com/go-gost/x/logger"
)

func TestTLSSniffingRoutesBySNI(t *testing.T) {
	corelogger.SetDefault(xlogger.Nop())

	backend := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, "sni route ok")
	}))
	defer backend.Close()

	backendURL, err := url.Parse(backend.URL)
	if err != nil {
		t.Fatal(err)
	}

	svc, err := service_parser.ParseService(&config.ServiceConfig{
		Name: "tls-sniff-test",
		Addr: "127.0.0.1:0",
		Handler: &config.HandlerConfig{
			Type: "forward",
			Metadata: map[string]any{
				"sniffing":         true,
				"sniffing.timeout": "2s",
				"readTimeout":      "2s",
			},
		},
		Listener: &config.ListenerConfig{Type: "tcp"},
		Forwarder: &config.ForwarderConfig{
			Nodes: []*config.ForwardNodeConfig{{
				Name: "tls-backend",
				Addr: backendURL.Host,
				Filter: &config.NodeFilterConfig{
					Host:     "test.example.com",
					Protocol: "tls",
				},
			}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Close()

	serveErr := make(chan error, 1)
	go func() {
		serveErr <- svc.Serve()
	}()

	conn, err := tls.Dial("tcp", svc.Addr().String(), &tls.Config{
		ServerName:         "test.example.com",
		InsecureSkipVerify: true, // The test backend uses httptest's self-signed certificate.
	})
	if err != nil {
		t.Fatal(err)
	}

	req, err := http.NewRequest(http.MethodGet, "https://test.example.com/", nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := req.Write(conn); err != nil {
		t.Fatal(err)
	}

	resp, err := http.ReadResponse(bufio.NewReader(conn), req)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if err := resp.Body.Close(); err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK || string(body) != "sni route ok" {
		t.Fatalf("unexpected response: status=%d body=%q", resp.StatusCode, body)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}

	if err := svc.Close(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-serveErr:
		if err != nil && !errors.Is(err, net.ErrClosed) {
			t.Fatalf("service stopped with error: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("service did not stop")
	}
}
