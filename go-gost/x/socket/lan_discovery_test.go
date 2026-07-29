package socket

import (
	"fmt"
	"net"
	"testing"
)

func TestLanDiscoveryFindsLocalHTTPService(t *testing.T) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer connection.Close()
		buffer := make([]byte, 1024)
		_, _ = connection.Read(buffer)
		_, _ = fmt.Fprint(connection, "HTTP/1.0 200 OK\r\nServer: CloudNest-Test\r\nContent-Type: text/html\r\n\r\n<title>Family NAS</title>")
	}()

	port := listener.Addr().(*net.TCPAddr).Port
	reporter := &WebSocketReporter{}
	response, err := reporter.handleLanDiscovery(map[string]interface{}{
		"cidr": "127.0.0.1/32", "ports": []int{port}, "timeoutMs": 300, "maxHosts": 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(response.Services) != 1 {
		t.Fatalf("expected one service, got %d", len(response.Services))
	}
	service := response.Services[0]
	if service.Title != "Family NAS" || service.Product != "CloudNest-Test" || service.Confidence != "high" {
		t.Fatalf("unexpected fingerprint: %+v", service)
	}
}

func TestLanDiscoveryRejectsPublicAndLargeRanges(t *testing.T) {
	for _, cidr := range []string{"8.8.8.0/24", "192.168.0.0/16"} {
		if _, err := discoveryRanges(cidr, nil); err == nil {
			t.Fatalf("expected %s to be rejected", cidr)
		}
	}
}

func TestLanDiscoveryHonorsConnectorAllowedNetworks(t *testing.T) {
	ranges, err := discoveryRanges("192.168.100.0/24", []string{"192.168.100.0/24"})
	if err != nil || len(ranges) != 1 {
		t.Fatalf("expected authorized range, got %v, %v", ranges, err)
	}
	if _, err := discoveryRanges("192.168.101.0/24", []string{"192.168.100.0/24"}); err == nil {
		t.Fatal("expected unauthorized range to be rejected")
	}
}

func TestDiscoveryHostsSkipsNetworkAndBroadcast(t *testing.T) {
	hosts, err := discoveryHosts([]string{"192.168.50.0/30"}, 4)
	if err != nil {
		t.Fatal(err)
	}
	if len(hosts) != 2 || hosts[0] != "192.168.50.1" || hosts[1] != "192.168.50.2" {
		t.Fatalf("unexpected hosts: %v", hosts)
	}
}
