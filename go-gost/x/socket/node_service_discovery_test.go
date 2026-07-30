package socket

import (
	"testing"

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
