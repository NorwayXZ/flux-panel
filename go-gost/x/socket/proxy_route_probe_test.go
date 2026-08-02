package socket

import (
	"io"
	"net"
	"testing"
)

func TestProxyRouteProbeRejectsUnsupportedProtocol(t *testing.T) {
	_, err := (&WebSocketReporter{}).handleProxyRouteProbe(map[string]interface{}{
		"proxyType": "trojan", "proxyPort": 1080, "target": "example.com:443",
	})
	if err == nil {
		t.Fatal("expected unsupported protocol error")
	}
}

func TestSocks5ConnectSupportsNoAuthentication(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		defer server.Close()
		greeting := make([]byte, 3)
		if _, err := io.ReadFull(server, greeting); err != nil {
			done <- err
			return
		}
		if greeting[2] != 0 {
			done <- io.ErrUnexpectedEOF
			return
		}
		if _, err := server.Write([]byte{5, 0}); err != nil {
			done <- err
			return
		}
		request := make([]byte, 4)
		if _, err := io.ReadFull(server, request); err != nil {
			done <- err
			return
		}
		hostLength := make([]byte, 1)
		if _, err := io.ReadFull(server, hostLength); err != nil {
			done <- err
			return
		}
		if _, err := io.CopyN(io.Discard, server, int64(hostLength[0])+2); err != nil {
			done <- err
			return
		}
		_, err := server.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 80})
		done <- err
	}()
	if err := socks5Connect(client, proxyRouteProbeRequest{Target: "example.com:443"}); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
