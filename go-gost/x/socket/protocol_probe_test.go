package socket

import (
	"strings"
	"testing"
)

func TestProtocolProbeRejectsUnsupportedProtocol(t *testing.T) {
	_, err := (&WebSocketReporter{}).handleProtocolProbe(map[string]interface{}{
		"proxyType":   "trojan",
		"proxyPort":   443,
		"downloadUrl": "https://speed.cloudflare.com/__down",
		"uploadUrl":   "https://speed.cloudflare.com/__up",
	})
	if err == nil || !strings.Contains(err.Error(), "supports socks5 and http") {
		t.Fatalf("expected unsupported protocol error, got %v", err)
	}
}

func TestProtocolProbeRejectsInvalidTarget(t *testing.T) {
	_, err := (&WebSocketReporter{}).handleProtocolProbe(map[string]interface{}{
		"proxyType":   "socks5",
		"proxyPort":   1080,
		"downloadUrl": "ftp://example.com/file",
		"uploadUrl":   "https://speed.cloudflare.com/__up",
	})
	if err == nil || !strings.Contains(err.Error(), "valid http or https") {
		t.Fatalf("expected invalid target error, got %v", err)
	}
}
