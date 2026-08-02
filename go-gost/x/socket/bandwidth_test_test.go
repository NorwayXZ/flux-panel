package socket

import (
	"fmt"
	"net"
	"testing"
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

func preparedAddress(port int) string { return net.JoinHostPort("127.0.0.1", fmt.Sprint(port)) }
