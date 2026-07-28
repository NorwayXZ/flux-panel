package socket

import (
	"net"
	"testing"
)

func TestPublicIPRejectsUnknownFamily(t *testing.T) {
	reporter := &WebSocketReporter{}
	if _, err := reporter.handlePublicIPQuery(map[string]interface{}{"family": "auto"}); err == nil {
		t.Fatal("expected unsupported family error")
	}
}

func TestUsablePublicIPv6Validation(t *testing.T) {
	tests := []struct {
		address string
		usable  bool
	}{
		{address: "2408:8207:1234::10", usable: true},
		{address: "2001:4860:4860::8888", usable: true},
		{address: "fe80::1", usable: false},
		{address: "fd12:3456::1", usable: false},
		{address: "::1", usable: false},
		{address: "::", usable: false},
		{address: "192.0.2.1", usable: false},
	}
	for _, test := range tests {
		t.Run(test.address, func(t *testing.T) {
			if got := isUsablePublicIPv6(net.ParseIP(test.address)); got != test.usable {
				t.Fatalf("isUsablePublicIPv6(%q) = %v, want %v", test.address, got, test.usable)
			}
		})
	}
}
