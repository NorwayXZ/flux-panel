//go:build linux

package socket

import (
	"strings"
	"testing"
)

func TestOptimizeSystemdUnit(t *testing.T) {
	input := `[Unit]
Description=Gost Proxy Service
After=network-online.target
Wants=network-online.target

[Service]
Restart=on-failure
RestartSec=3
`
	updated, changed := optimizeSystemdUnit(input)
	if !changed {
		t.Fatal("expected the legacy unit to change")
	}
	for _, expected := range []string{"After=network.target", "StartLimitIntervalSec=0", "Restart=always", "RestartSec=1"} {
		if !strings.Contains(updated, expected) {
			t.Fatalf("optimized unit is missing %q", expected)
		}
	}
	if strings.Contains(updated, "network-online.target") {
		t.Fatal("optimized unit still waits for network-online.target")
	}
	if second, changedAgain := optimizeSystemdUnit(updated); changedAgain || second != updated {
		t.Fatal("systemd optimization must be idempotent")
	}
}

func TestOptimizeOpenRCScript(t *testing.T) {
	input := "depend() {\n  need net\n  after firewall\n}\n"
	updated, changed := optimizeOpenRCScript(input)
	if !changed || !strings.Contains(updated, "  use net") || strings.Contains(updated, "need net") {
		t.Fatalf("unexpected OpenRC optimization: %q", updated)
	}
	if second, changedAgain := optimizeOpenRCScript(updated); changedAgain || second != updated {
		t.Fatal("OpenRC optimization must be idempotent")
	}
}
