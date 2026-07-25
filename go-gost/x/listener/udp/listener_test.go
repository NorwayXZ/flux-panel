package udp

import (
	"fmt"
	"net"
	"syscall"
	"testing"
)

func TestShouldFallbackToIPv4(t *testing.T) {
	unsupported := fmt.Errorf("listen udp: %w", syscall.EAFNOSUPPORT)

	tests := []struct {
		name    string
		network string
		addr    *net.UDPAddr
		err     error
		want    bool
	}{
		{name: "IPv6 wildcard unsupported", network: "udp", addr: &net.UDPAddr{IP: net.IPv6unspecified, Port: 1006}, err: unsupported, want: true},
		{name: "empty wildcard unsupported", network: "udp", addr: &net.UDPAddr{Port: 1006}, err: unsupported, want: true},
		{name: "explicit IPv6 address", network: "udp", addr: &net.UDPAddr{IP: net.ParseIP("2001:db8::1"), Port: 1006}, err: unsupported, want: false},
		{name: "IPv4 listener", network: "udp4", addr: &net.UDPAddr{IP: net.IPv4zero, Port: 1006}, err: unsupported, want: false},
		{name: "other error", network: "udp", addr: &net.UDPAddr{IP: net.IPv6unspecified, Port: 1006}, err: syscall.EACCES, want: false},
		{name: "successful listen", network: "udp", addr: &net.UDPAddr{IP: net.IPv6unspecified, Port: 1006}, err: nil, want: false},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := shouldFallbackToIPv4(test.network, test.addr, test.err); got != test.want {
				t.Fatalf("shouldFallbackToIPv4() = %v, want %v", got, test.want)
			}
		})
	}
}
