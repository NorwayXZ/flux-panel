package socket

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRealityHandshakeChecksTLS13H2AndName(t *testing.T) {
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	host, port, _ := net.SplitHostPort(server.Listener.Addr().String())

	if _, _, h2, err := realityHandshakeAt(context.Background(), "example.com", host, port); err != nil || !h2 {
		t.Fatalf("a TLS 1.3 site with HTTP/2 and its own certificate passes, got h2=%v err=%v", h2, err)
	}
	if _, _, _, err := realityHandshakeAt(context.Background(), "other.test", host, port); err == nil {
		t.Fatal("a certificate for another name must fail")
	}

	old := httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	old.TLS = &tls.Config{MaxVersion: tls.VersionTLS12}
	old.StartTLS()
	defer old.Close()
	host, port, _ = net.SplitHostPort(old.Listener.Addr().String())
	if _, _, _, err := realityHandshakeAt(context.Background(), "example.com", host, port); err == nil || err.Error() != "does not support TLS 1.3" {
		t.Fatalf("TLS 1.2 only must fail with a clear reason, got %v", err)
	}

	noH2 := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer noH2.Close()
	host, port, _ = net.SplitHostPort(noH2.Listener.Addr().String())
	if _, _, _, err := realityHandshakeAt(context.Background(), "example.com", host, port); err == nil || err.Error() != "does not support HTTP/2" {
		t.Fatalf("a site without HTTP/2 must fail, got %v", err)
	}
}

func TestRealityTargetProbeValidatesInput(t *testing.T) {
	if _, err := probeRealityTargets(realityTargetProbeRequest{Domains: []string{"https://x.com/path"}}); err == nil {
		t.Fatal("URLs are refused")
	}
	if _, err := probeRealityTargets(realityTargetProbeRequest{}); err == nil {
		t.Fatal("an empty list is refused")
	}
}
