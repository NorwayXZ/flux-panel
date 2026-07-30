package socket

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"testing"
)

func TestQualityProbeHTTP(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	parsed, err := url.Parse(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	host, portText, err := net.SplitHostPort(parsed.Host)
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	result, err := (&WebSocketReporter{}).handleQualityProbe(map[string]interface{}{
		"target": host, "port": port, "protocol": "http", "path": "/",
		"ipFamily": "ipv4", "count": 3, "timeoutMs": 2000,
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.SuccessCount != 3 || result.FailureRate != 0 || len(result.Samples) != 3 {
		t.Fatalf("unexpected result: %+v", result)
	}
	for _, sample := range result.Samples {
		if !sample.Success || sample.HTTPStatus != http.StatusNoContent || sample.TCPMs <= 0 || sample.TTFBMs <= 0 {
			t.Fatalf("unexpected sample: %+v", sample)
		}
	}
}

func TestQualityProbeRejectsUnsafeInput(t *testing.T) {
	_, err := (&WebSocketReporter{}).handleQualityProbe(map[string]interface{}{
		"target": "example.com", "port": 443, "protocol": "https", "path": "/\r\nInjected: true",
		"ipFamily": "auto", "count": 3, "timeoutMs": 2000,
	})
	if err == nil {
		t.Fatal("expected unsafe path to be rejected")
	}
}

func TestSuccessiveJitter(t *testing.T) {
	if value := successiveJitter([]float64{10, 14, 11}); value != 3.5 {
		t.Fatalf("unexpected jitter %.2f", value)
	}
}
