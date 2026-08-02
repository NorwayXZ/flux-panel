//go:build linux

package socket

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

type linuxNFTForwardSystem struct{}

func (s *linuxNFTForwardSystem) supported() bool {
	_, err := exec.LookPath("nft")
	return err == nil
}

func newNFTForwardSystem() nftForwardSystem {
	return &linuxNFTForwardSystem{}
}

func (s *linuxNFTForwardSystem) preflight(checks []nftForwardCheck) (nftForwardPreflightResponse, error) {
	result := nftForwardPreflightResponse{Supported: true, Available: true}
	if _, err := exec.LookPath("nft"); err != nil {
		result.Supported = false
		result.Available = false
		return result, errors.New("服务器未安装 nftables（Debian/Ubuntu: apt install nftables；Alpine: apk add nftables）")
	}
	version, err := runNFTForwardCommand(nil, "nft", "--version")
	if err != nil {
		return result, fmt.Errorf("read nftables version: %w", err)
	}
	result.NftVersion = strings.TrimSpace(string(version))
	forwarding, _ := os.ReadFile("/proc/sys/net/ipv4/ip_forward")
	result.IPv4Forwarding = strings.TrimSpace(string(forwarding)) == "1"

	ruleset, err := runNFTForwardCommand(nil, "nft", "-j", "list", "ruleset")
	if err != nil {
		return result, fmt.Errorf("read nftables ruleset (Agent needs CAP_NET_ADMIN): %w", err)
	}
	result.Conflicts = findExternalNFTForwardConflicts(ruleset, checks)
	result.Available = len(result.Conflicts) == 0
	result.FirewallManager, result.Warnings = detectNFTForwardFirewall()
	return result, nil
}

func (s *linuxNFTForwardSystem) tableExists() (bool, error) {
	_, err := runNFTForwardCommand(nil, "nft", "list", "table", "ip", nftForwardTable)
	if err == nil {
		return true, nil
	}
	var exitError *exec.ExitError
	if errors.As(err, &exitError) {
		return false, nil
	}
	return false, err
}

func (s *linuxNFTForwardSystem) apply(script string) error {
	if _, err := runNFTForwardCommand([]byte(script), "nft", "--check", "-f", "-"); err != nil {
		return fmt.Errorf("nftables validation failed: %w", err)
	}
	if _, err := runNFTForwardCommand([]byte(script), "nft", "-f", "-"); err != nil {
		return fmt.Errorf("nftables transaction failed: %w", err)
	}
	return nil
}

func (s *linuxNFTForwardSystem) readCounters() (bool, map[string]nftForwardCounter, error) {
	content, err := runNFTForwardCommand(nil, "nft", "-j", "list", "table", "ip", nftForwardTable)
	if err != nil {
		var exitError *exec.ExitError
		if errors.As(err, &exitError) {
			return false, map[string]nftForwardCounter{}, nil
		}
		return false, nil, err
	}
	return true, parseNFTForwardCounters(content), nil
}

func (s *linuxNFTForwardSystem) enableIPv4Forwarding() error {
	content, err := os.ReadFile("/proc/sys/net/ipv4/ip_forward")
	if err != nil {
		return err
	}
	if strings.TrimSpace(string(content)) == "1" {
		return nil
	}
	return os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1\n"), 0644)
}

func runNFTForwardCommand(input []byte, name string, arguments ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, name, arguments...)
	if input != nil {
		command.Stdin = bytes.NewReader(input)
	}
	output, err := command.CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return nil, errors.New("command timed out")
	}
	if err != nil {
		detail := strings.TrimSpace(string(output))
		if detail == "" {
			detail = err.Error()
		}
		return nil, errors.New(detail)
	}
	return output, nil
}

func detectNFTForwardFirewall() (string, []string) {
	var managers []string
	var warnings []string
	if _, err := exec.LookPath("firewall-cmd"); err == nil {
		if output, commandError := runNFTForwardCommand(nil, "firewall-cmd", "--state"); commandError == nil && strings.TrimSpace(string(output)) == "running" {
			managers = append(managers, "firewalld")
			warnings = append(warnings, "firewalld is active; its forward policy or a reload may block the managed NAT path")
		}
	}
	if _, err := exec.LookPath("ufw"); err == nil {
		if output, commandError := runNFTForwardCommand(nil, "ufw", "status"); commandError == nil && strings.Contains(strings.ToLower(string(output)), "status: active") {
			managers = append(managers, "ufw")
			warnings = append(warnings, "UFW is active; routed traffic may require an explicit UFW route allow rule")
		}
	}
	if _, err := exec.LookPath("iptables"); err == nil {
		if output, commandError := runNFTForwardCommand(nil, "iptables", "-S", "FORWARD"); commandError == nil && strings.Contains(string(output), "-P FORWARD DROP") {
			warnings = append(warnings, "the current FORWARD policy is DROP; external reachability must be verified")
		}
	}
	return strings.Join(managers, ","), warnings
}

func findExternalNFTForwardConflicts(content []byte, checks []nftForwardCheck) []nftForwardConflict {
	if len(checks) == 0 {
		return nil
	}
	var document map[string]interface{}
	if json.Unmarshal(content, &document) != nil {
		return nil
	}
	items, _ := document["nftables"].([]interface{})
	var conflicts []nftForwardConflict
	for _, item := range items {
		wrapper, _ := item.(map[string]interface{})
		rule, _ := wrapper["rule"].(map[string]interface{})
		if rule == nil || fmt.Sprint(rule["table"]) == nftForwardTable {
			continue
		}
		expressions, _ := rule["expr"].([]interface{})
		protocol, ports, hasDNAT := nftForwardRuleMatch(expressions)
		if !hasDNAT || protocol == "" || len(ports) == 0 {
			continue
		}
		for _, check := range checks {
			if check.Protocol != protocol || !ports[check.ListenPort] {
				continue
			}
			conflicts = append(conflicts, nftForwardConflict{
				Protocol: protocol,
				Port:     check.ListenPort,
				Table:    fmt.Sprint(rule["table"]),
				Chain:    fmt.Sprint(rule["chain"]),
				Detail:   "服务器已有其他 nftables DNAT 规则使用此端口",
			})
		}
	}
	return conflicts
}

func nftForwardRuleMatch(expressions []interface{}) (string, map[int]bool, bool) {
	protocol := ""
	ports := map[int]bool{}
	hasDNAT := false
	for _, expression := range expressions {
		entry, _ := expression.(map[string]interface{})
		if entry == nil {
			continue
		}
		if _, exists := entry["dnat"]; exists {
			hasDNAT = true
		}
		match, _ := entry["match"].(map[string]interface{})
		if match == nil {
			continue
		}
		left, _ := match["left"].(map[string]interface{})
		payload, _ := left["payload"].(map[string]interface{})
		if fmt.Sprint(payload["field"]) == "dport" {
			candidate := strings.ToLower(fmt.Sprint(payload["protocol"]))
			if candidate == "tcp" || candidate == "udp" {
				protocol = candidate
				collectNFTForwardPorts(match["right"], ports)
			}
		}
		meta, _ := left["meta"].(map[string]interface{})
		if fmt.Sprint(meta["key"]) == "l4proto" {
			candidate := strings.ToLower(fmt.Sprint(match["right"]))
			if candidate == "tcp" || candidate == "udp" {
				protocol = candidate
			}
		}
	}
	return protocol, ports, hasDNAT
}

func collectNFTForwardPorts(value interface{}, ports map[int]bool) {
	switch typed := value.(type) {
	case float64:
		ports[int(typed)] = true
	case json.Number:
		if number, err := strconv.Atoi(typed.String()); err == nil {
			ports[number] = true
		}
	case []interface{}:
		for _, item := range typed {
			collectNFTForwardPorts(item, ports)
		}
	case map[string]interface{}:
		if set, exists := typed["set"]; exists {
			collectNFTForwardPorts(set, ports)
		}
		if rangeValue, exists := typed["range"].([]interface{}); exists && len(rangeValue) == 2 {
			start, startOK := nftForwardInteger(rangeValue[0])
			end, endOK := nftForwardInteger(rangeValue[1])
			if startOK && endOK && end-start <= 4096 {
				for port := start; port <= end; port++ {
					ports[port] = true
				}
			}
		}
	}
}

func nftForwardInteger(value interface{}) (int, bool) {
	switch typed := value.(type) {
	case float64:
		return int(typed), true
	case json.Number:
		number, err := strconv.Atoi(typed.String())
		return number, err == nil
	default:
		return 0, false
	}
}

func parseNFTForwardCounters(content []byte) map[string]nftForwardCounter {
	result := map[string]nftForwardCounter{}
	var document map[string]interface{}
	if json.Unmarshal(content, &document) != nil {
		return result
	}
	items, _ := document["nftables"].([]interface{})
	for _, item := range items {
		wrapper, _ := item.(map[string]interface{})
		rule, _ := wrapper["rule"].(map[string]interface{})
		if rule == nil {
			continue
		}
		comment := fmt.Sprint(rule["comment"])
		if !strings.HasPrefix(comment, "cloudnest:nft-forward:") {
			continue
		}
		expressions, _ := rule["expr"].([]interface{})
		for _, expression := range expressions {
			entry, _ := expression.(map[string]interface{})
			counter, _ := entry["counter"].(map[string]interface{})
			if counter == nil {
				continue
			}
			result[comment] = nftForwardCounter{
				Packets: uint64(nftForwardFloat(counter["packets"])),
				Bytes:   uint64(nftForwardFloat(counter["bytes"])),
			}
		}
	}
	return result
}

func nftForwardFloat(value interface{}) float64 {
	if number, ok := value.(float64); ok {
		return number
	}
	return 0
}
