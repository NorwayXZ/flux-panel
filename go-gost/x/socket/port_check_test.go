package socket

import (
	"net"
	"testing"
)

func TestCheckLocalPortDetectsTcpListener(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	port := listener.Addr().(*net.TCPAddr).Port

	result := checkLocalPort(PortCheckItem{Network: "tcp", Host: "127.0.0.1", Port: port})
	if result.Available {
		t.Fatalf("expected occupied port, got %#v", result)
	}
}

func TestCheckLocalPortReleasesProbeSocket(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()

	result := checkLocalPort(PortCheckItem{Network: "tcp", Host: "127.0.0.1", Port: port})
	if !result.Available {
		t.Fatalf("expected available port, got %#v", result)
	}
	second, err := net.Listen("tcp", result.Address)
	if err != nil {
		t.Fatalf("probe did not release socket: %v", err)
	}
	second.Close()
}
