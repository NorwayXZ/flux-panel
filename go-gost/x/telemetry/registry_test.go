package telemetry

import (
	"fmt"
	"testing"
)

func TestRegistryBoundsAndNormalizesSamples(t *testing.T) {
	ResetForTest()
	for i := 0; i < 25; i++ {
		ObserveSource("svc", fmt.Sprintf("192.0.2.%d:1234", i), "previous_hop")
	}
	ObserveHost("svc", "Example.COM:443")
	sources, hosts := Snapshot("svc")
	if len(sources) != maxSamples {
		t.Fatalf("expected %d bounded source samples, got %d", maxSamples, len(sources))
	}
	if len(hosts) != 1 || hosts[0].Value != "example.com" {
		t.Fatalf("unexpected host samples: %#v", hosts)
	}
}
