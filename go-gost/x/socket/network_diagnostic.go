package socket

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const diagnosticOutputLimit = 32 * 1024

type networkDiagnosticRequest struct {
	Mode       string `json:"mode"`
	Target     string `json:"target"`
	RecordType string `json:"recordType"`
	Port       int    `json:"port"`
	Count      int    `json:"count"`
	TimeoutMs  int    `json:"timeoutMs"`
}

type networkDiagnosticResponse struct {
	Mode       string   `json:"mode"`
	Target     string   `json:"target"`
	Success    bool     `json:"success"`
	Summary    string   `json:"summary"`
	Output     string   `json:"output"`
	Addresses  []string `json:"addresses,omitempty"`
	DurationMs int64    `json:"durationMs"`
}

func (w *WebSocketReporter) handleNetworkDiagnostic(data interface{}) (networkDiagnosticResponse, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return networkDiagnosticResponse{}, fmt.Errorf("serialize diagnostic request: %w", err)
	}
	var req networkDiagnosticRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		return networkDiagnosticResponse{}, fmt.Errorf("parse diagnostic request: %w", err)
	}
	req.Mode = strings.ToLower(strings.TrimSpace(req.Mode))
	req.Target = strings.TrimSpace(req.Target)
	if !validDiagnosticTarget(req.Target) {
		return networkDiagnosticResponse{}, errors.New("target must be a valid IP address or hostname")
	}
	if req.Count < 1 || req.Count > 10 {
		req.Count = 4
	}
	if req.TimeoutMs < 200 || req.TimeoutMs > 30000 {
		req.TimeoutMs = 5000
	}
	started := time.Now()
	result := networkDiagnosticResponse{Mode: req.Mode, Target: req.Target}

	switch req.Mode {
	case "tcp":
		if req.Port < 1 || req.Port > 65535 {
			return result, errors.New("TCP port must be between 1 and 65535")
		}
		ping, pingErr := w.handleTcpPing(map[string]interface{}{
			"ip": req.Target, "port": req.Port, "count": req.Count, "timeout": req.TimeoutMs,
		})
		encoded, _ := json.MarshalIndent(ping, "", "  ")
		result.Success = pingErr == nil && ping.Success
		result.Output = string(encoded)
		result.Summary = fmt.Sprintf("TCP %s:%d %s", req.Target, req.Port, map[bool]string{true: "reachable", false: "unreachable"}[result.Success])
		if pingErr != nil {
			result.Summary = pingErr.Error()
		}
	case "dns":
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(req.TimeoutMs)*time.Millisecond)
		defer cancel()
		addresses, lookupErr := lookupDNS(ctx, req.Target, req.RecordType)
		result.Addresses = addresses
		result.Success = lookupErr == nil && len(addresses) > 0
		result.Output = strings.Join(addresses, "\n")
		result.Summary = fmt.Sprintf("resolved %d record(s)", len(addresses))
		if lookupErr != nil {
			result.Summary = lookupErr.Error()
		}
	case "ping", "trace":
		output, runErr := runNetworkCommand(req)
		result.Success = runErr == nil
		result.Output = output
		result.Summary = map[bool]string{true: "diagnostic completed", false: "diagnostic failed"}[result.Success]
		if runErr != nil {
			result.Summary = runErr.Error()
		}
	default:
		return result, errors.New("mode must be ping, tcp, dns, or trace")
	}
	result.DurationMs = time.Since(started).Milliseconds()
	return result, nil
}

func validDiagnosticTarget(target string) bool {
	if len(target) == 0 || len(target) > 253 {
		return false
	}
	if net.ParseIP(target) != nil {
		return true
	}
	if strings.ContainsAny(target, " /\\:@[]\t\r\n") {
		return false
	}
	for _, label := range strings.Split(target, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != '-' {
				return false
			}
		}
	}
	return true
}

func lookupDNS(ctx context.Context, target, recordType string) ([]string, error) {
	typeName := strings.ToUpper(strings.TrimSpace(recordType))
	switch typeName {
	case "", "A", "AAAA":
		values, err := net.DefaultResolver.LookupHost(ctx, target)
		if err != nil {
			return nil, err
		}
		filtered := make([]string, 0, len(values))
		for _, value := range values {
			ip := net.ParseIP(value)
			if ip == nil || (typeName == "A" && ip.To4() == nil) || (typeName == "AAAA" && ip.To4() != nil) {
				continue
			}
			filtered = append(filtered, value)
		}
		return filtered, nil
	case "CNAME":
		value, err := net.DefaultResolver.LookupCNAME(ctx, target)
		return []string{value}, err
	case "MX":
		values, err := net.DefaultResolver.LookupMX(ctx, target)
		if err != nil {
			return nil, err
		}
		result := make([]string, 0, len(values))
		for _, value := range values {
			result = append(result, fmt.Sprintf("%d %s", value.Pref, value.Host))
		}
		return result, nil
	case "TXT":
		return net.DefaultResolver.LookupTXT(ctx, target)
	default:
		return nil, errors.New("record type must be A, AAAA, CNAME, MX, or TXT")
	}
}

func runNetworkCommand(req networkDiagnosticRequest) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(req.TimeoutMs)*time.Millisecond*time.Duration(req.Count+2))
	defer cancel()
	seconds := strconv.Itoa(maxInt(1, (req.TimeoutMs+999)/1000))
	var name string
	var args []string
	if req.Mode == "ping" {
		name = "ping"
		if runtime.GOOS == "windows" {
			args = []string{"-n", strconv.Itoa(req.Count), "-w", strconv.Itoa(req.TimeoutMs), req.Target}
		} else {
			args = []string{"-c", strconv.Itoa(req.Count), "-W", seconds, req.Target}
		}
	} else if runtime.GOOS == "windows" {
		name, args = "tracert", []string{"-d", "-h", "20", "-w", strconv.Itoa(req.TimeoutMs), req.Target}
	} else {
		name, args = "traceroute", []string{"-n", "-m", "20", "-w", seconds, req.Target}
	}
	if _, err := exec.LookPath(name); err != nil {
		return "", fmt.Errorf("%s is not installed on this node", name)
	}
	output, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	if len(output) > diagnosticOutputLimit {
		output = output[:diagnosticOutputLimit]
	}
	if ctx.Err() == context.DeadlineExceeded {
		return string(output), errors.New("diagnostic timed out")
	}
	return string(output), err
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
