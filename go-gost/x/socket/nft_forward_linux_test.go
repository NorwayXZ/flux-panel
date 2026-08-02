//go:build linux

package socket

import (
	"errors"
	"os/exec"
	"strings"
	"testing"
)

func TestRunNFTForwardCommandPreservesExitError(t *testing.T) {
	_, err := runNFTForwardCommand(nil, "sh", "-c", "printf 'missing managed table' >&2; exit 1")
	if err == nil {
		t.Fatal("expected command failure")
	}
	var exitError *exec.ExitError
	if !errors.As(err, &exitError) {
		t.Fatalf("expected wrapped exec.ExitError, got %T: %v", err, err)
	}
	if !strings.Contains(err.Error(), "missing managed table") {
		t.Fatalf("command output was lost: %v", err)
	}
}

func TestParseNFTForwardCounters(t *testing.T) {
	content := []byte(`{"nftables":[{"rule":{"family":"ip","table":"cloudnest_nat","chain":"forward","expr":[{"counter":{"packets":12,"bytes":4096}},{"accept":null}],"comment":"cloudnest:nft-forward:8:tcp:traffic"}}]}`)
	counters := parseNFTForwardCounters(content)
	value, exists := counters["cloudnest:nft-forward:8:tcp:traffic"]
	if !exists || value.Packets != 12 || value.Bytes != 4096 {
		t.Fatalf("unexpected counters: %+v", counters)
	}
}

func TestFindExternalNFTForwardConflict(t *testing.T) {
	content := []byte(`{"nftables":[{"rule":{"family":"ip","table":"external","chain":"prerouting","expr":[{"match":{"op":"==","left":{"payload":{"protocol":"tcp","field":"dport"}},"right":443}},{"dnat":{"addr":"10.0.0.2","port":8443}}]}}]}`)
	conflicts := findExternalNFTForwardConflicts(content, []nftForwardCheck{{Protocol: "tcp", ListenPort: 443}})
	if len(conflicts) != 1 || conflicts[0].Table != "external" {
		t.Fatalf("unexpected conflicts: %+v", conflicts)
	}
}
