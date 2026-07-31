package socket

import (
	"fmt"
	"net"
	"testing"
)

func TestNormalizeSelfCheckDomains(t *testing.T) {
	result := normalizeSelfCheckDomains([]string{"Example.COM.", "example.com", "  home.example.com ", "1.1.1.1", ""})
	if len(result) != 2 || result[0] != "example.com" || result[1] != "home.example.com" {
		t.Fatalf("unexpected normalized domains: %#v", result)
	}
}

func TestInspectRequestedPortsFindsListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port
	results := inspectRequestedPorts([]systemSelfCheckPortRequest{{Network: "tcp", Port: port}, {Network: "invalid", Port: 1}})
	if len(results) != 1 || !results[0].Listening || results[0].Port != port {
		t.Fatalf("listener was not detected: %#v", results)
	}
}

func TestSystemSelfCheckRejectsTooManyDomains(t *testing.T) {
	domains := make([]string, 41)
	for index := range domains {
		domains[index] = fmt.Sprintf("host-%d.example.com", index)
	}
	_, err := (&WebSocketReporter{}).handleSystemSelfCheck(map[string]interface{}{"domains": domains})
	if err == nil {
		t.Fatal("oversized domain request was accepted")
	}
}
