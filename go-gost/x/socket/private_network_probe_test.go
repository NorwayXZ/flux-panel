package socket

import (
	"net"
	"testing"
)

func TestPrivateNetworkProbeReportsActualSocketAddresses(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			connection.Close()
		}
	}()
	port := listener.Addr().(*net.TCPAddr).Port
	result, err := (&WebSocketReporter{}).handlePrivateNetworkProbe(map[string]interface{}{
		"target": "127.0.0.1", "port": port, "count": 2, "timeoutMs": 1000,
	})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Success || result.SourceAddress != "127.0.0.1" || result.RemoteAddress != "127.0.0.1" {
		t.Fatalf("unexpected probe result: %+v", result)
	}
}

func TestPrivateNetworkProbeRejectsHostname(t *testing.T) {
	_, err := (&WebSocketReporter{}).handlePrivateNetworkProbe(map[string]interface{}{
		"target": "example.com", "port": 443,
	})
	if err == nil {
		t.Fatal("expected hostname validation error")
	}
}
