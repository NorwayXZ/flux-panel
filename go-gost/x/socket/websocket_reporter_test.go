package socket

import (
	"encoding/json"
	"net/url"
	"testing"
	"time"

	"github.com/go-gost/x/config"
)

func TestReconnectDelayUsesFastStartupWindow(t *testing.T) {
	now := time.Unix(100, 0)
	fast := reconnectDelay(now, now.Add(time.Second), 750*time.Millisecond)
	if fast < time.Second || fast >= 1500*time.Millisecond {
		t.Fatalf("fast reconnect delay is outside the expected range: %v", fast)
	}
	normal := reconnectDelay(now, now.Add(-time.Second), 1500*time.Millisecond)
	if normal < 5*time.Second || normal >= 6*time.Second {
		t.Fatalf("normal reconnect delay is outside the expected range: %v", normal)
	}
}

func TestBuildReporterURLPreservesEncodedIdentity(t *testing.T) {
	raw := buildReporterURL("panel.example:6366", "secret + & value", "2.42.0", "1", 18080, 18443, 11080)
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatal(err)
	}
	query := parsed.Query()
	if parsed.Host != "panel.example:6366" || parsed.Path != "/system-info" {
		t.Fatalf("unexpected reporter endpoint: %s", raw)
	}
	if query.Get("secret") != "secret + & value" || query.Get("type") != "1" || query.Get("version") != "2.42.0" {
		t.Fatalf("reporter identity was not preserved: %#v", query)
	}
	if query.Get("http") != "18080" || query.Get("tls") != "18443" || query.Get("socks") != "11080" {
		t.Fatalf("reporter ports were not preserved: %#v", query)
	}
	if len(query.Get("machine")) != 16 {
		t.Fatalf("machine fingerprint is missing or malformed: %q", query.Get("machine"))
	}
}

func TestPreprocessDurationFieldsAcceptsStringTimeoutsInChains(t *testing.T) {
	reporter := &WebSocketReporter{}
	processed, err := reporter.preprocessDurationFields([]byte(`{
		"name":"route_chains",
		"hops":[{"name":"hop-1","selector":{"strategy":"fifo","maxFails":1,"failTimeout":"30s"}}]
	}`))
	if err != nil {
		t.Fatal(err)
	}
	var chain config.ChainConfig
	if err := json.Unmarshal(processed, &chain); err != nil {
		t.Fatal(err)
	}
	if len(chain.Hops) != 1 || chain.Hops[0].Selector == nil || chain.Hops[0].Selector.FailTimeout != 30*time.Second {
		t.Fatalf("unexpected chain selector timeout: %#v", chain.Hops)
	}
}
