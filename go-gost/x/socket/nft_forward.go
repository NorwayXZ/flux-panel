package socket

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const (
	nftForwardTable      = "cloudnest_nat"
	nftForwardMarkPrefix = uint32(0x43000000)
	nftForwardMaxRuleID  = int64(0x007ffffe)
	nftForwardMaxSources = 64
)

type nftForwardRule struct {
	ID            int64    `json:"id"`
	Name          string   `json:"name"`
	ListenAddress string   `json:"listenAddress"`
	ListenPort    int      `json:"listenPort"`
	Protocol      string   `json:"protocol"`
	TargetAddress string   `json:"targetAddress"`
	TargetPort    int      `json:"targetPort"`
	NATMode       string   `json:"natMode"`
	SourceCIDRs   []string `json:"sourceCidrs,omitempty"`
	Enabled       bool     `json:"enabled"`
}

type nftForwardCheck struct {
	Protocol      string `json:"protocol"`
	ListenAddress string `json:"listenAddress"`
	ListenPort    int    `json:"listenPort"`
}

type nftForwardApplyRequest struct {
	Generation int64            `json:"generation"`
	Rules      []nftForwardRule `json:"rules"`
}

type nftForwardPreflightRequest struct {
	Checks []nftForwardCheck `json:"checks"`
}

type nftForwardConflict struct {
	Protocol string `json:"protocol"`
	Port     int    `json:"port"`
	Table    string `json:"table,omitempty"`
	Chain    string `json:"chain,omitempty"`
	Detail   string `json:"detail"`
}

type nftForwardPreflightResponse struct {
	Supported       bool                 `json:"supported"`
	Available       bool                 `json:"available"`
	NftVersion      string               `json:"nftVersion,omitempty"`
	IPv4Forwarding  bool                 `json:"ipv4Forwarding"`
	FirewallManager string               `json:"firewallManager,omitempty"`
	Warnings        []string             `json:"warnings,omitempty"`
	Conflicts       []nftForwardConflict `json:"conflicts,omitempty"`
}

type nftForwardCounter struct {
	Packets uint64
	Bytes   uint64
}

type nftForwardRuleStatus struct {
	ID       int64  `json:"id"`
	Protocol string `json:"protocol"`
	Applied  bool   `json:"applied"`
	Packets  uint64 `json:"packets"`
	Bytes    uint64 `json:"bytes"`
}

type nftForwardStatusResponse struct {
	Supported    bool                   `json:"supported"`
	TablePresent bool                   `json:"tablePresent"`
	Generation   int64                  `json:"generation"`
	AppliedHash  string                 `json:"appliedHash,omitempty"`
	Rules        []nftForwardRuleStatus `json:"rules"`
}

type nftForwardPersistedState struct {
	Generation  int64            `json:"generation"`
	AppliedHash string           `json:"appliedHash"`
	Rules       []nftForwardRule `json:"rules"`
}

type nftForwardSystem interface {
	supported() bool
	preflight([]nftForwardCheck) (nftForwardPreflightResponse, error)
	tableExists() (bool, error)
	apply(string) error
	readCounters() (bool, map[string]nftForwardCounter, error)
	enableIPv4Forwarding() error
}

type nftForwardManager struct {
	mu        sync.Mutex
	system    nftForwardSystem
	stateFile string
	state     nftForwardPersistedState
}

func newNFTForwardManager() *nftForwardManager {
	stateFile := "nft-forward-state.json"
	if executable, err := os.Executable(); err == nil {
		stateFile = filepath.Join(filepath.Dir(executable), stateFile)
	}
	return newNFTForwardManagerWithSystem(newNFTForwardSystem(), stateFile)
}

func newNFTForwardManagerWithSystem(system nftForwardSystem, stateFile string) *nftForwardManager {
	return &nftForwardManager{system: system, stateFile: stateFile}
}

func (m *nftForwardManager) restore() {
	m.mu.Lock()
	defer m.mu.Unlock()

	content, err := os.ReadFile(m.stateFile)
	if err != nil {
		return
	}
	var state nftForwardPersistedState
	if err := json.Unmarshal(content, &state); err != nil {
		fmt.Printf("nftables forwarding state is invalid: %v\n", err)
		return
	}
	rules, err := normalizeNFTForwardRules(state.Rules)
	if err != nil {
		fmt.Printf("nftables forwarding state validation failed: %v\n", err)
		return
	}
	state.Rules = rules
	if err := m.applyState(state); err != nil {
		fmt.Printf("nftables forwarding restore failed: %v\n", err)
		return
	}
	m.state = state
}

func (m *nftForwardManager) preflight(request nftForwardPreflightRequest) (nftForwardPreflightResponse, error) {
	checks, err := normalizeNFTForwardChecks(request.Checks)
	if err != nil {
		return nftForwardPreflightResponse{}, err
	}
	return m.system.preflight(checks)
}

func (m *nftForwardManager) apply(request nftForwardApplyRequest) (nftForwardStatusResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if request.Generation <= 0 {
		return nftForwardStatusResponse{}, errors.New("nftables generation must be positive")
	}
	rules, err := normalizeNFTForwardRules(request.Rules)
	if err != nil {
		return nftForwardStatusResponse{}, err
	}
	checks := make([]nftForwardCheck, 0, len(rules)*2)
	for _, rule := range rules {
		if !rule.Enabled {
			continue
		}
		for _, protocol := range expandNFTForwardProtocols(rule.Protocol) {
			checks = append(checks, nftForwardCheck{Protocol: protocol, ListenAddress: rule.ListenAddress, ListenPort: rule.ListenPort})
		}
	}
	preflight, err := m.system.preflight(checks)
	if err != nil {
		return nftForwardStatusResponse{}, err
	}
	if !preflight.Supported {
		return nftForwardStatusResponse{}, errors.New("nftables forwarding is not supported on this Agent")
	}
	if !preflight.Available {
		return nftForwardStatusResponse{}, formatNFTForwardConflicts(preflight.Conflicts)
	}

	candidate := nftForwardPersistedState{Generation: request.Generation, Rules: rules}
	candidate.AppliedHash = hashNFTForwardRules(rules)
	previous := m.state
	if err := m.applyState(candidate); err != nil {
		return nftForwardStatusResponse{}, err
	}
	if err := writeNFTForwardState(m.stateFile, candidate); err != nil {
		_ = m.applyState(previous)
		return nftForwardStatusResponse{}, fmt.Errorf("persist nftables forwarding state: %w", err)
	}
	m.state = candidate
	return m.statusLocked()
}

func (m *nftForwardManager) status() (nftForwardStatusResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.statusLocked()
}

func (m *nftForwardManager) statusLocked() (nftForwardStatusResponse, error) {
	present, counters, err := m.system.readCounters()
	if err != nil {
		return nftForwardStatusResponse{}, err
	}
	result := nftForwardStatusResponse{
		Supported:    m.system.supported(),
		TablePresent: present,
		Generation:   m.state.Generation,
		AppliedHash:  m.state.AppliedHash,
		Rules:        []nftForwardRuleStatus{},
	}
	for _, rule := range m.state.Rules {
		if !rule.Enabled {
			continue
		}
		for _, protocol := range expandNFTForwardProtocols(rule.Protocol) {
			comment := nftForwardComment(rule.ID, protocol, "traffic")
			counter, applied := counters[comment]
			result.Rules = append(result.Rules, nftForwardRuleStatus{
				ID: rule.ID, Protocol: protocol, Applied: present && applied,
				Packets: counter.Packets, Bytes: counter.Bytes,
			})
		}
	}
	return result, nil
}

func (m *nftForwardManager) applyState(state nftForwardPersistedState) error {
	enabled := make([]nftForwardRule, 0, len(state.Rules))
	for _, rule := range state.Rules {
		if rule.Enabled {
			enabled = append(enabled, rule)
		}
	}
	if len(enabled) > 0 {
		if err := m.system.enableIPv4Forwarding(); err != nil {
			return fmt.Errorf("enable IPv4 forwarding: %w", err)
		}
	}
	exists, err := m.system.tableExists()
	if err != nil {
		return err
	}
	if !exists && len(enabled) == 0 {
		return nil
	}
	script := buildNFTForwardScript(enabled, exists)
	if strings.TrimSpace(script) == "" {
		return nil
	}
	if err := m.system.apply(script); err != nil {
		return err
	}
	return nil
}

func normalizeNFTForwardRules(input []nftForwardRule) ([]nftForwardRule, error) {
	rules := append([]nftForwardRule(nil), input...)
	seenIDs := make(map[int64]struct{}, len(rules))
	for index := range rules {
		rule := &rules[index]
		if rule.ID <= 0 || rule.ID > nftForwardMaxRuleID {
			return nil, fmt.Errorf("invalid nftables rule ID: %d", rule.ID)
		}
		if _, exists := seenIDs[rule.ID]; exists {
			return nil, fmt.Errorf("duplicate nftables rule ID: %d", rule.ID)
		}
		seenIDs[rule.ID] = struct{}{}
		rule.Name = strings.TrimSpace(rule.Name)
		if rule.Name == "" || len(rule.Name) > 100 {
			return nil, errors.New("nftables rule name must contain 1 to 100 characters")
		}
		rule.ListenAddress = strings.TrimSpace(rule.ListenAddress)
		if rule.ListenAddress == "" || rule.ListenAddress == "*" {
			rule.ListenAddress = "0.0.0.0"
		}
		listenIP := net.ParseIP(rule.ListenAddress)
		if listenIP == nil || listenIP.To4() == nil {
			return nil, fmt.Errorf("invalid IPv4 listen address: %s", rule.ListenAddress)
		}
		rule.ListenAddress = listenIP.To4().String()
		if rule.ListenPort < 1 || rule.ListenPort > 65535 || rule.TargetPort < 1 || rule.TargetPort > 65535 {
			return nil, errors.New("nftables ports must be between 1 and 65535")
		}
		rule.Protocol = normalizeNFTForwardProtocol(rule.Protocol)
		if rule.Protocol == "" {
			return nil, errors.New("nftables protocol must be tcp, udp, or tcp_udp")
		}
		targetIP := net.ParseIP(strings.TrimSpace(rule.TargetAddress))
		if targetIP == nil || targetIP.To4() == nil || targetIP.IsUnspecified() || targetIP.IsLoopback() || targetIP.IsMulticast() {
			return nil, fmt.Errorf("invalid IPv4 target address: %s", rule.TargetAddress)
		}
		rule.TargetAddress = targetIP.To4().String()
		rule.NATMode = strings.ToLower(strings.TrimSpace(rule.NATMode))
		if rule.NATMode == "" {
			rule.NATMode = "masquerade"
		}
		if rule.NATMode != "masquerade" && rule.NATMode != "preserve_source" {
			return nil, errors.New("nftables NAT mode must be masquerade or preserve_source")
		}
		if len(rule.SourceCIDRs) > nftForwardMaxSources {
			return nil, fmt.Errorf("a rule can contain at most %d source networks", nftForwardMaxSources)
		}
		sources := make([]string, 0, len(rule.SourceCIDRs))
		seenSources := map[string]struct{}{}
		for _, raw := range rule.SourceCIDRs {
			raw = strings.TrimSpace(raw)
			if raw == "" {
				continue
			}
			ip, network, err := net.ParseCIDR(raw)
			if err != nil || ip.To4() == nil {
				return nil, fmt.Errorf("invalid IPv4 source network: %s", raw)
			}
			normalized := network.String()
			if _, exists := seenSources[normalized]; !exists {
				seenSources[normalized] = struct{}{}
				sources = append(sources, normalized)
			}
		}
		sort.Strings(sources)
		rule.SourceCIDRs = sources
	}
	sort.Slice(rules, func(i, j int) bool { return rules[i].ID < rules[j].ID })
	for i := 0; i < len(rules); i++ {
		if !rules[i].Enabled {
			continue
		}
		for j := i + 1; j < len(rules); j++ {
			if !rules[j].Enabled || !nftForwardProtocolOverlap(rules[i].Protocol, rules[j].Protocol) {
				continue
			}
			if rules[i].ListenPort == rules[j].ListenPort && nftForwardAddressOverlap(rules[i].ListenAddress, rules[j].ListenAddress) {
				return nil, fmt.Errorf("nftables rules %d and %d use overlapping listeners", rules[i].ID, rules[j].ID)
			}
		}
	}
	return rules, nil
}

func normalizeNFTForwardChecks(input []nftForwardCheck) ([]nftForwardCheck, error) {
	checks := append([]nftForwardCheck(nil), input...)
	for index := range checks {
		check := &checks[index]
		check.Protocol = normalizeNFTForwardProtocol(check.Protocol)
		if check.Protocol != "tcp" && check.Protocol != "udp" {
			return nil, errors.New("preflight protocol must be tcp or udp")
		}
		if check.ListenPort < 1 || check.ListenPort > 65535 {
			return nil, errors.New("preflight port must be between 1 and 65535")
		}
		check.ListenAddress = strings.TrimSpace(check.ListenAddress)
		if check.ListenAddress == "" || check.ListenAddress == "*" {
			check.ListenAddress = "0.0.0.0"
		}
		ip := net.ParseIP(check.ListenAddress)
		if ip == nil || ip.To4() == nil {
			return nil, fmt.Errorf("invalid IPv4 listen address: %s", check.ListenAddress)
		}
		check.ListenAddress = ip.To4().String()
	}
	return checks, nil
}

func normalizeNFTForwardProtocol(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "tcp":
		return "tcp"
	case "udp":
		return "udp"
	case "both", "tcp_udp", "tcp+udp":
		return "tcp_udp"
	default:
		return ""
	}
}

func expandNFTForwardProtocols(protocol string) []string {
	if protocol == "tcp_udp" {
		return []string{"tcp", "udp"}
	}
	return []string{protocol}
}

func nftForwardProtocolOverlap(left, right string) bool {
	return left == "tcp_udp" || right == "tcp_udp" || left == right
}

func nftForwardAddressOverlap(left, right string) bool {
	return left == "0.0.0.0" || right == "0.0.0.0" || left == right
}

func nftForwardMark(id int64, protocol string) uint32 {
	value := uint32(id) << 1
	if protocol == "udp" {
		value++
	}
	return nftForwardMarkPrefix | value
}

func nftForwardComment(id int64, protocol, kind string) string {
	return fmt.Sprintf("cloudnest:nft-forward:%d:%s:%s", id, protocol, kind)
}

func buildNFTForwardScript(rules []nftForwardRule, tableExists bool) string {
	var lines []string
	if tableExists {
		lines = append(lines, "delete table ip "+nftForwardTable)
	}
	if len(rules) == 0 {
		return strings.Join(lines, "\n") + "\n"
	}
	lines = append(lines,
		"add table ip "+nftForwardTable,
		"add chain ip "+nftForwardTable+" prerouting { type nat hook prerouting priority -100; policy accept; }",
		"add chain ip "+nftForwardTable+" forward { type filter hook forward priority 0; policy accept; }",
		"add chain ip "+nftForwardTable+" postrouting { type nat hook postrouting priority 100; policy accept; }",
	)
	for _, rule := range rules {
		for _, protocol := range expandNFTForwardProtocols(rule.Protocol) {
			mark := fmt.Sprintf("0x%08x", nftForwardMark(rule.ID, protocol))
			matches := make([]string, 0, 3)
			if rule.ListenAddress != "0.0.0.0" {
				matches = append(matches, "ip daddr "+rule.ListenAddress)
			}
			if len(rule.SourceCIDRs) > 0 {
				matches = append(matches, "ip saddr { "+strings.Join(rule.SourceCIDRs, ", ")+" }")
			}
			matches = append(matches, fmt.Sprintf("%s dport %d", protocol, rule.ListenPort))
			lines = append(lines, fmt.Sprintf(
				"add rule ip %s prerouting %s ct mark set %s counter dnat to %s:%d comment \"%s\"",
				nftForwardTable, strings.Join(matches, " "), mark, rule.TargetAddress, rule.TargetPort,
				nftForwardComment(rule.ID, protocol, "dnat")))
			lines = append(lines, fmt.Sprintf(
				"add rule ip %s forward ct mark %s counter accept comment \"%s\"",
				nftForwardTable, mark, nftForwardComment(rule.ID, protocol, "traffic")))
			if rule.NATMode == "masquerade" {
				lines = append(lines, fmt.Sprintf(
					"add rule ip %s postrouting ct mark %s counter masquerade comment \"%s\"",
					nftForwardTable, mark, nftForwardComment(rule.ID, protocol, "snat")))
			}
		}
	}
	return strings.Join(lines, "\n") + "\n"
}

func hashNFTForwardRules(rules []nftForwardRule) string {
	content, _ := json.Marshal(rules)
	hash := sha256.Sum256(content)
	return hex.EncodeToString(hash[:])
}

func writeNFTForwardState(path string, state nftForwardPersistedState) error {
	content, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0750); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".nft-forward-state-*")
	if err != nil {
		return err
	}
	temporaryName := temporary.Name()
	defer os.Remove(temporaryName)
	if err := temporary.Chmod(0600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(content); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryName, path)
}

func formatNFTForwardConflicts(conflicts []nftForwardConflict) error {
	if len(conflicts) == 0 {
		return errors.New("nftables listener is unavailable")
	}
	parts := make([]string, 0, len(conflicts))
	for _, conflict := range conflicts {
		parts = append(parts, fmt.Sprintf("%s/%d: %s", conflict.Protocol, conflict.Port, conflict.Detail))
	}
	return errors.New(strings.Join(parts, "; "))
}
