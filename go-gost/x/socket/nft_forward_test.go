package socket

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type fakeNFTForwardSystem struct {
	preflightResult nftForwardPreflightResponse
	table           bool
	applied         []string
	counters        map[string]nftForwardCounter
	applyError      error
	forwardEnabled  bool
}

func (s *fakeNFTForwardSystem) supported() bool { return s.preflightResult.Supported }

func (s *fakeNFTForwardSystem) preflight([]nftForwardCheck) (nftForwardPreflightResponse, error) {
	return s.preflightResult, nil
}

func (s *fakeNFTForwardSystem) tableExists() (bool, error) { return s.table, nil }

func (s *fakeNFTForwardSystem) apply(script string) error {
	if s.applyError != nil {
		return s.applyError
	}
	s.applied = append(s.applied, script)
	s.table = !strings.Contains(script, "delete table ip "+nftForwardTable) || strings.Contains(script, "add table ip "+nftForwardTable)
	return nil
}

func (s *fakeNFTForwardSystem) readCounters() (bool, map[string]nftForwardCounter, error) {
	return s.table, s.counters, nil
}

func (s *fakeNFTForwardSystem) enableIPv4Forwarding() error {
	s.forwardEnabled = true
	return nil
}

func TestNFTForwardApplyPersistsAnIsolatedAtomicRuleset(t *testing.T) {
	system := &fakeNFTForwardSystem{
		preflightResult: nftForwardPreflightResponse{Supported: true, Available: true},
		counters: map[string]nftForwardCounter{
			nftForwardComment(42, "tcp", "traffic"): {Packets: 9, Bytes: 1024},
		},
	}
	stateFile := filepath.Join(t.TempDir(), "state.json")
	manager := newNFTForwardManagerWithSystem(system, stateFile)
	status, err := manager.apply(nftForwardApplyRequest{
		Generation: 100,
		Rules: []nftForwardRule{{
			ID: 42, Name: "web", ListenAddress: "0.0.0.0", ListenPort: 443,
			Protocol: "tcp", TargetAddress: "10.0.0.8", TargetPort: 8443,
			NATMode: "masquerade", SourceCIDRs: []string{"192.0.2.0/24"}, Enabled: true,
		}},
	})
	if err != nil {
		t.Fatalf("apply failed: %v", err)
	}
	if !system.forwardEnabled {
		t.Fatal("IPv4 forwarding was not enabled")
	}
	if len(system.applied) != 1 {
		t.Fatalf("expected one transaction, got %d", len(system.applied))
	}
	script := system.applied[0]
	for _, expected := range []string{
		"add table ip cloudnest_nat",
		"tcp dport 443",
		"ip saddr { 192.0.2.0/24 }",
		"dnat to 10.0.0.8:8443",
		"masquerade",
		"cloudnest:nft-forward:42:tcp:traffic",
	} {
		if !strings.Contains(script, expected) {
			t.Fatalf("script is missing %q:\n%s", expected, script)
		}
	}
	if strings.Contains(script, "flush ruleset") {
		t.Fatal("managed transaction must never flush the complete ruleset")
	}
	if status.Generation != 100 || len(status.Rules) != 1 || !status.Rules[0].Applied || status.Rules[0].Bytes != 1024 {
		t.Fatalf("unexpected status: %+v", status)
	}
	if _, err := os.Stat(stateFile); err != nil {
		t.Fatalf("state was not persisted: %v", err)
	}
}

func TestNFTForwardApplyFailureLeavesPreviousState(t *testing.T) {
	system := &fakeNFTForwardSystem{preflightResult: nftForwardPreflightResponse{Supported: true, Available: true}}
	manager := newNFTForwardManagerWithSystem(system, filepath.Join(t.TempDir(), "state.json"))
	_, err := manager.apply(nftForwardApplyRequest{Generation: 1, Rules: []nftForwardRule{{
		ID: 1, Name: "first", ListenPort: 10001, Protocol: "tcp", TargetAddress: "10.0.0.2", TargetPort: 80,
		NATMode: "masquerade", Enabled: true,
	}}})
	if err != nil {
		t.Fatalf("initial apply failed: %v", err)
	}
	system.applyError = errors.New("candidate rejected")
	_, err = manager.apply(nftForwardApplyRequest{Generation: 2, Rules: []nftForwardRule{{
		ID: 2, Name: "second", ListenPort: 10002, Protocol: "udp", TargetAddress: "10.0.0.3", TargetPort: 53,
		NATMode: "preserve_source", Enabled: true,
	}}})
	if err == nil {
		t.Fatal("expected apply failure")
	}
	if manager.state.Generation != 1 || manager.state.Rules[0].ID != 1 {
		t.Fatalf("previous state was replaced: %+v", manager.state)
	}
}

func TestNFTForwardPersistenceFailureRestoresPreviousRules(t *testing.T) {
	system := &fakeNFTForwardSystem{preflightResult: nftForwardPreflightResponse{Supported: true, Available: true}}
	stateFile := filepath.Join(t.TempDir(), "state.json")
	manager := newNFTForwardManagerWithSystem(system, stateFile)
	_, err := manager.apply(nftForwardApplyRequest{Generation: 1, Rules: []nftForwardRule{{
		ID: 1, Name: "first", ListenPort: 10001, Protocol: "tcp", TargetAddress: "10.0.0.2", TargetPort: 80,
		NATMode: "masquerade", Enabled: true,
	}}})
	if err != nil {
		t.Fatalf("initial apply failed: %v", err)
	}
	manager.stateFile = filepath.Dir(stateFile)
	_, err = manager.apply(nftForwardApplyRequest{Generation: 2, Rules: []nftForwardRule{{
		ID: 2, Name: "second", ListenPort: 10002, Protocol: "udp", TargetAddress: "10.0.0.3", TargetPort: 53,
		NATMode: "masquerade", Enabled: true,
	}}})
	if err == nil || !strings.Contains(err.Error(), "persist") {
		t.Fatalf("expected persistence failure, got %v", err)
	}
	if manager.state.Generation != 1 || len(system.applied) != 3 {
		t.Fatalf("previous state was not restored: state=%+v transactions=%d", manager.state, len(system.applied))
	}
	if !strings.Contains(system.applied[2], "tcp dport 10001") {
		t.Fatalf("rollback transaction did not restore the previous rule:\n%s", system.applied[2])
	}
}

func TestNFTForwardValidationRejectsOverlappingAndUnsafeRules(t *testing.T) {
	_, err := normalizeNFTForwardRules([]nftForwardRule{
		{ID: 1, Name: "wildcard", ListenAddress: "0.0.0.0", ListenPort: 443, Protocol: "tcp", TargetAddress: "10.0.0.2", TargetPort: 443, Enabled: true},
		{ID: 2, Name: "specific", ListenAddress: "192.0.2.10", ListenPort: 443, Protocol: "tcp_udp", TargetAddress: "10.0.0.3", TargetPort: 443, Enabled: true},
	})
	if err == nil || !strings.Contains(err.Error(), "overlapping") {
		t.Fatalf("expected listener overlap error, got %v", err)
	}

	_, err = normalizeNFTForwardRules([]nftForwardRule{{
		ID: 3, Name: "loop", ListenPort: 80, Protocol: "tcp", TargetAddress: "127.0.0.1", TargetPort: 8080, Enabled: true,
	}})
	if err == nil || !strings.Contains(err.Error(), "target") {
		t.Fatalf("expected target validation error, got %v", err)
	}
}

func TestNFTForwardHashAndScriptAreStable(t *testing.T) {
	left, err := normalizeNFTForwardRules([]nftForwardRule{
		{ID: 9, Name: "udp", ListenPort: 53, Protocol: "udp", TargetAddress: "10.0.0.9", TargetPort: 53, Enabled: true},
		{ID: 4, Name: "tcp", ListenPort: 80, Protocol: "tcp", TargetAddress: "10.0.0.4", TargetPort: 8080, Enabled: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	right, err := normalizeNFTForwardRules([]nftForwardRule{left[1], left[0]})
	if err != nil {
		t.Fatal(err)
	}
	if hashNFTForwardRules(left) != hashNFTForwardRules(right) {
		t.Fatal("hash changed when input order changed")
	}
	if buildNFTForwardScript(left, false) != buildNFTForwardScript(right, false) {
		t.Fatal("script changed when input order changed")
	}
}

func TestNFTForwardStatusReportsSystemSupport(t *testing.T) {
	system := &fakeNFTForwardSystem{
		preflightResult: nftForwardPreflightResponse{Supported: false},
		counters:        map[string]nftForwardCounter{},
	}
	manager := newNFTForwardManagerWithSystem(system, filepath.Join(t.TempDir(), "state.json"))
	status, err := manager.status()
	if err != nil {
		t.Fatal(err)
	}
	if status.Supported {
		t.Fatal("unsupported systems must not report nftables support")
	}
}
