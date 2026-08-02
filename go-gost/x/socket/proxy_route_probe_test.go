package socket

import "testing"

func TestProxyRouteProbeRejectsUnsupportedProtocol(t *testing.T) {
	_, err := (&WebSocketReporter{}).handleProxyRouteProbe(map[string]interface{}{
		"proxyType": "trojan", "proxyPort": 1080, "target": "example.com:443",
	})
	if err == nil {
		t.Fatal("expected unsupported protocol error")
	}
}
