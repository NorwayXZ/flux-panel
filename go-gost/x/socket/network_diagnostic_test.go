package socket

import (
	"context"
	"testing"
	"time"
)

func TestValidDiagnosticTarget(t *testing.T) {
	valid := []string{"example.com", "127.0.0.1", "2001:db8::1", "node-1.example.com"}
	for _, target := range valid {
		if !validDiagnosticTarget(target) {
			t.Fatalf("expected valid target: %s", target)
		}
	}
	invalid := []string{"", "example.com;id", "https://example.com", "a b", "-bad.example"}
	for _, target := range invalid {
		if validDiagnosticTarget(target) {
			t.Fatalf("expected invalid target: %s", target)
		}
	}
}

func TestLookupDNSRejectsUnknownRecordType(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := lookupDNS(ctx, "localhost", "CAA"); err == nil {
		t.Fatal("expected unsupported record type error")
	}
}
