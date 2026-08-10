package socket

import (
	"net"
	"testing"
)

func TestUDPQuicDiagnosticPrepareRunAndStop(t *testing.T) {
	port := freeUDPDiagnosticPort(t)
	manager := newUDPQuicDiagnosticManager()
	prepare, err := manager.prepare(udpQuicPrepareRequest{
		SessionID:  "udp-quic-test",
		ListenPort: port,
		TTLSeconds: 20,
		PacketSize: 256,
	})
	if err != nil {
		t.Fatalf("prepare failed: %v", err)
	}
	result, err := manager.run(udpQuicRunRequest{
		Mode:       "udp_echo",
		TargetHost: "127.0.0.1",
		Port:       prepare.Port,
		Token:      prepare.Token,
		IPFamily:   "ipv4",
		Count:      3,
		TimeoutMs:  1000,
		PacketSize: 256,
	})
	if err != nil {
		t.Fatalf("run failed: %v", err)
	}
	if result.SuccessCount != 3 || result.PacketLossPercent != 0 || result.RTTAvgMs <= 0 {
		t.Fatalf("unexpected UDP echo result: %+v", result)
	}
	manager.stop(prepare.SessionID)
}

func freeUDPDiagnosticPort(t *testing.T) int {
	t.Helper()
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatalf("allocate UDP port: %v", err)
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).Port
}
