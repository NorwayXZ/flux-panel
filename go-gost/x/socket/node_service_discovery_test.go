package socket

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	psnet "github.com/shirou/gopsutil/v3/net"
)

func TestCollectNodeListenersMergesDockerMetadata(t *testing.T) {
	connections := []psnet.ConnectionStat{
		{Status: "LISTEN", Laddr: psnet.Addr{IP: "0.0.0.0", Port: 54321}, Pid: 0},
		{Status: "ESTABLISHED", Laddr: psnet.Addr{IP: "127.0.0.1", Port: 1234}},
	}
	services := collectNodeListeners(connections, map[int]dockerPortOwner{
		54321: {ID: "abcdef", Name: "xui", Image: "example/x-ui:latest"},
	}, 20)
	if len(services) != 1 {
		t.Fatalf("expected one listener, got %d", len(services))
	}
	if services[0].ProbeHost != "127.0.0.1" || services[0].ContainerName != "xui" {
		t.Fatalf("unexpected service: %#v", services[0])
	}
}

func TestRefineNodeServiceRecognizesXUI(t *testing.T) {
	service := nodeDiscoveredService{Protocol: "http", ProcessName: "x-ui"}
	refineNodeService(&service)
	if service.ServiceName != "XUI 管理面板" || !service.Sensitive {
		t.Fatalf("unexpected classification: %#v", service)
	}
}

func TestProbeNodeWebServiceFallsBackToHTTPS(t *testing.T) {
	tlsServer := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	tlsServer.StartTLS()
	defer tlsServer.Close()

	tlsAddress := tlsServer.Listener.Addr().(*net.TCPAddr)
	service := nodeDiscoveredService{ProbeHost: tlsAddress.IP.String(), Port: tlsAddress.Port, Protocol: "tcp"}
	probeNodeWebService(context.Background(), &service, time.Second)

	if service.Protocol != "https" || service.HTTPStatus != http.StatusOK {
		t.Fatalf("expected HTTPS 200, got %s %d", service.Protocol, service.HTTPStatus)
	}
}

func TestProbeNodeWebServiceFallsBackToHTTP(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	address := server.Listener.Addr().(*net.TCPAddr)
	service := nodeDiscoveredService{ProbeHost: address.IP.String(), Port: address.Port, Protocol: "tcp"}
	probeNodeWebService(context.Background(), &service, time.Second)

	if service.Protocol != "http" || service.HTTPStatus != http.StatusNoContent {
		t.Fatalf("expected HTTP 204, got %s %d", service.Protocol, service.HTTPStatus)
	}
}
