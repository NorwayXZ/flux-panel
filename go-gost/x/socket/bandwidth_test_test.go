package socket

import (
	"fmt"
	"net"
	"testing"
	"time"
)

func TestBandwidthPrepareRunAndStop(t *testing.T) {
	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := probe.Addr().(*net.TCPAddr).Port
	probe.Close()
	manager := newBandwidthTestManager()
	prepared, err := manager.prepare(bandwidthPrepareRequest{SessionID: "bw-test-1", ListenPort: port, TTLSeconds: 30, MaximumBytes: 4 * 1024 * 1024, MaximumStreams: 2})
	if err != nil {
		t.Fatalf("prepare: %v", err)
	}
	result, err := manager.run(bandwidthRunRequest{TargetHost: "127.0.0.1", Port: prepared.Port, Token: prepared.Token, Direction: "bidirectional", Streams: 2, DurationSeconds: 1, MaximumBytes: 2 * 1024 * 1024})
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if result.Successful != 2 || result.UploadBytes == 0 || result.DownloadBytes == 0 || result.TotalMbps <= 0 {
		t.Fatalf("unexpected result: %+v", result)
	}
	manager.stop("bw-test-1")
	if connection, err := net.Dial("tcp", preparedAddress(port)); err == nil {
		connection.Close()
		t.Fatal("listener remains open after stop")
	}
}

func TestBandwidthRejectsBadToken(t *testing.T) {
	probe, _ := net.Listen("tcp", "127.0.0.1:0")
	port := probe.Addr().(*net.TCPAddr).Port
	probe.Close()
	manager := newBandwidthTestManager()
	_, err := manager.prepare(bandwidthPrepareRequest{SessionID: "bw-test-2", ListenPort: port, TTLSeconds: 30, MaximumBytes: 1024 * 1024, MaximumStreams: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.stopAll()
	if _, err := manager.run(bandwidthRunRequest{TargetHost: "127.0.0.1", Port: port, Token: "this-token-is-long-enough-but-invalid-123", Direction: "download", Streams: 1, DurationSeconds: 1, MaximumBytes: 1024 * 1024}); err == nil {
		t.Fatal("expected invalid token test to fail")
	}
}

func TestUDPBandwidthPrepareRunAndStop(t *testing.T) {
	probe, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	port := probe.LocalAddr().(*net.UDPAddr).Port
	probe.Close()
	manager := newBandwidthTestManager()
	prepared, err := manager.prepare(bandwidthPrepareRequest{SessionID: "bw-udp-1", Protocol: "udp", ListenPort: port, TTLSeconds: 30, MaximumBytes: 8 * 1024 * 1024, MaximumStreams: 2})
	if err != nil {
		t.Fatalf("prepare UDP: %v", err)
	}
	result, err := manager.run(bandwidthRunRequest{TargetHost: "127.0.0.1", Protocol: "udp", Port: prepared.Port, Token: prepared.Token, Direction: "bidirectional", Streams: 2, DurationSeconds: 1, MaximumBytes: 2 * 1024 * 1024})
	if err != nil {
		t.Fatalf("run UDP: %v", err)
	}
	serverMetrics := manager.stop("bw-udp-1")
	if result.Successful != 2 || result.UploadBytes == 0 || result.DownloadBytes == 0 || result.TotalMbps <= 0 {
		t.Fatalf("unexpected UDP result: %+v", result)
	}
	if result.PacketsSent == 0 || result.PacketsRecv == 0 || serverMetrics.PacketsSent == 0 || serverMetrics.PacketsRecv == 0 {
		t.Fatalf("missing UDP packet metrics: source=%+v target=%+v", result, serverMetrics)
	}
	connection, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: port})
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
	_, _ = connection.Write([]byte("closed"))
	if _, err := connection.Read(make([]byte, 16)); err == nil {
		t.Fatal("UDP listener remains active after stop")
	}
}

func TestUDPBandwidthRejectsBadToken(t *testing.T) {
	probe, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	port := probe.LocalAddr().(*net.UDPAddr).Port
	probe.Close()
	manager := newBandwidthTestManager()
	_, err := manager.prepare(bandwidthPrepareRequest{SessionID: "bw-udp-2", Protocol: "udp", ListenPort: port, TTLSeconds: 30, MaximumBytes: 1024 * 1024, MaximumStreams: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.stopAll()
	if _, err := manager.run(bandwidthRunRequest{TargetHost: "127.0.0.1", Protocol: "udp", Port: port, Token: "this-token-is-long-enough-but-invalid-123", Direction: "download", Streams: 1, DurationSeconds: 1, MaximumBytes: 1024 * 1024}); err == nil {
		t.Fatal("expected invalid UDP token test to fail")
	}
}

func TestUDPPacketTrackerCountsLossDuplicatesAndReordering(t *testing.T) {
	tracker := udpPacketTracker{expected: 4}
	now := time.Now().UnixNano()
	tracker.record(0, now, 100)
	tracker.record(2, now+int64(time.Millisecond), 100)
	tracker.record(1, now+2*int64(time.Millisecond), 100)
	tracker.record(1, now+2*int64(time.Millisecond), 100)
	metrics := tracker.metrics()
	if metrics.PacketsRecv != 3 || metrics.PacketsLost != 1 || metrics.OutOfOrder != 1 {
		t.Fatalf("unexpected tracker metrics: %+v", metrics)
	}
}

func preparedAddress(port int) string { return net.JoinHostPort("127.0.0.1", fmt.Sprint(port)) }
