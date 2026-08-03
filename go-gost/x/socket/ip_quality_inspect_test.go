package socket

import (
	"net"
	"os"
	"testing"
)

func TestDecodeIPQualityResponse(t *testing.T) {
	result, err := decodeIPQualityResponse(map[string]interface{}{
		"publicIpv4": "1.1.1.1",
		"services":   []map[string]interface{}{{"name": "ChatGPT", "state": "available", "httpStatus": 200}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.PublicIPv4 != "1.1.1.1" || len(result.Services) != 1 || result.Services[0].State != "available" {
		t.Fatalf("unexpected response: %+v", result)
	}
}

func TestIPQualityTargetsAreFixedAndPublic(t *testing.T) {
	if len(ipQualityServices) < 4 || len(ipQualityPorts) < 5 {
		t.Fatal("expected fixed inspection targets")
	}
	for _, target := range ipQualityPorts {
		if ip := net.ParseIP(target.host); ip != nil && (ip.IsLoopback() || ip.IsPrivate()) {
			t.Fatalf("private target: %s", target.host)
		}
		if target.port < 1 || target.port > 65535 {
			t.Fatalf("invalid port: %d", target.port)
		}
	}
}

func TestIPQualityInspectLive(t *testing.T) {
	if os.Getenv("CLOUDNEST_LIVE_NETWORK_TEST") != "1" {
		t.Skip("set CLOUDNEST_LIVE_NETWORK_TEST=1 to run external probes")
	}
	result, err := (&WebSocketReporter{}).handleIPQualityInspect(nil)
	if err != nil {
		t.Fatal(err)
	}
	if result.PublicIPv4 == "" && result.PublicIPv6 == "" {
		t.Fatal("missing public IP")
	}
	if len(result.Services) != len(ipQualityServices) || len(result.Ports) != len(ipQualityPorts) {
		t.Fatalf("incomplete probes: %+v", result)
	}
}
