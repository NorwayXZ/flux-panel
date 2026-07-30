package service

import (
	"encoding/json"
	"testing"
)

func TestTrafficReportIncludesConnectionCounters(t *testing.T) {
	payload, err := json.Marshal(TrafficReportItem{
		N: "12_1_0",
		U: 1024,
		D: 2048,
		T: 17,
		C: 2,
		E: 3,
		A: 1700000000000,
	})
	if err != nil {
		t.Fatal(err)
	}

	var report map[string]any
	if err := json.Unmarshal(payload, &report); err != nil {
		t.Fatal(err)
	}
	if report["t"] != float64(17) || report["c"] != float64(2) {
		t.Fatalf("connection counters missing from report: %s", payload)
	}
	if report["e"] != float64(3) || report["a"] != float64(1700000000000) {
		t.Fatalf("telemetry counters missing from report: %s", payload)
	}
}
