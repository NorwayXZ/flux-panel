package socket

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	psnet "github.com/shirou/gopsutil/v3/net"
)

type systemSelfCheckRequest struct {
	Domains []string                     `json:"domains"`
	Ports   []systemSelfCheckPortRequest `json:"ports"`
	Targets []systemSelfCheckTarget      `json:"targets"`
}

type systemSelfCheckPortRequest struct {
	Network string `json:"network"`
	Port    int    `json:"port"`
}

type systemSelfCheckPortResult struct {
	Network   string `json:"network"`
	Port      int    `json:"port"`
	Listening bool   `json:"listening"`
}

type systemSelfCheckTarget struct {
	Name    string `json:"name"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
	Network string `json:"network"`
}

type systemSelfCheckTargetResult struct {
	Name       string `json:"name"`
	Host       string `json:"host"`
	Port       int    `json:"port"`
	Network    string `json:"network"`
	Reachable  bool   `json:"reachable"`
	DurationMs int64  `json:"durationMs"`
	Error      string `json:"error,omitempty"`
}

type systemSelfCheckFamily struct {
	Available bool     `json:"available"`
	Outbound  bool     `json:"outbound"`
	Addresses []string `json:"addresses"`
	Error     string   `json:"error,omitempty"`
}

type systemSelfCheckDNS struct {
	Domain      string   `json:"domain"`
	SystemA     []string `json:"systemA"`
	SystemAAAA  []string `json:"systemAAAA"`
	PublicA     []string `json:"publicA"`
	PublicAAAA  []string `json:"publicAAAA"`
	SystemError string   `json:"systemError,omitempty"`
	PublicError string   `json:"publicError,omitempty"`
}

type systemSelfCheckResponse struct {
	Version             string                        `json:"version"`
	Role                string                        `json:"role"`
	OS                  string                        `json:"os"`
	Arch                string                        `json:"arch"`
	Hostname            string                        `json:"hostname"`
	ConfiguredAddress   string                        `json:"configuredAddress"`
	IdentityFingerprint string                        `json:"identityFingerprint"`
	MachineFingerprint  string                        `json:"machineFingerprint"`
	DNSResolvers        []string                      `json:"dnsResolvers"`
	IPv4                systemSelfCheckFamily         `json:"ipv4"`
	IPv6                systemSelfCheckFamily         `json:"ipv6"`
	DNS                 []systemSelfCheckDNS          `json:"dns"`
	Ports               []systemSelfCheckPortResult   `json:"ports"`
	DefaultRoutes       []string                      `json:"defaultRoutes"`
	RouteError          string                        `json:"routeError,omitempty"`
	Reachability        []systemSelfCheckTargetResult `json:"reachability"`
	CheckedAt           int64                         `json:"checkedAt"`
}

func (w *WebSocketReporter) handleSystemSelfCheck(data interface{}) (systemSelfCheckResponse, error) {
	request := systemSelfCheckRequest{}
	payload, err := json.Marshal(data)
	if err != nil {
		return systemSelfCheckResponse{}, fmt.Errorf("serialize self-check request: %w", err)
	}
	if err := json.Unmarshal(payload, &request); err != nil {
		return systemSelfCheckResponse{}, fmt.Errorf("parse self-check request: %w", err)
	}
	request.Domains = normalizeSelfCheckDomains(request.Domains)
	if len(request.Domains) > 40 {
		return systemSelfCheckResponse{}, errors.New("self-check accepts at most 40 domains")
	}
	if len(request.Ports) > 300 {
		return systemSelfCheckResponse{}, errors.New("self-check accepts at most 300 ports")
	}
	if len(request.Targets) > 300 {
		return systemSelfCheckResponse{}, errors.New("self-check accepts at most 300 targets")
	}

	hostname, _ := os.Hostname()
	response := systemSelfCheckResponse{
		Version: w.version, Role: w.role, OS: runtime.GOOS, Arch: runtime.GOARCH,
		Hostname: hostname, ConfiguredAddress: w.addr,
		IdentityFingerprint: shortFingerprint(w.secret),
		MachineFingerprint:  machineFingerprint(hostname),
		DNSResolvers:        readDNSResolvers(),
		IPv4:                inspectIPFamily(false), IPv6: inspectIPFamily(true),
		Ports: inspectRequestedPorts(request.Ports), CheckedAt: time.Now().UnixMilli(),
	}
	response.DefaultRoutes, response.RouteError = inspectDefaultRoutes()
	response.Reachability = inspectSelfCheckTargets(request.Targets, w.addr)
	response.DNS = inspectDomains(request.Domains)
	return response, nil
}

func inspectDefaultRoutes() ([]string, string) {
	commands := [][]string{}
	switch runtime.GOOS {
	case "windows":
		commands = append(commands, []string{"route", "print"})
	default:
		commands = append(commands, []string{"ip", "route", "show", "default"}, []string{"ip", "-6", "route", "show", "default"})
		commands = append(commands, []string{"netstat", "-rn"})
	}
	var lastError error
	for _, command := range commands {
		output, err := execCommandOutput(command[0], command[1:]...)
		if err != nil {
			lastError = err
			continue
		}
		routes := make([]string, 0, 2)
		for _, line := range strings.Split(output, "\n") {
			line = strings.TrimSpace(line)
			if line == "" || !isDefaultRouteLine(line) {
				continue
			}
			routes = append(routes, line)
		}
		if len(routes) > 0 {
			return uniqueSorted(routes), ""
		}
	}
	if lastError != nil {
		return []string{}, lastError.Error()
	}
	return []string{}, "未发现默认路由"
}

func isDefaultRouteLine(line string) bool {
	fields := strings.Fields(line)
	if len(fields) == 0 {
		return false
	}
	first := strings.ToLower(fields[0])
	if first == "default" || first == "0.0.0.0" || first == "::/0" || first == "::" {
		return true
	}
	return strings.HasPrefix(first, "default")
}

func execCommandOutput(name string, args ...string) (string, error) {
	command := exec.Command(name, args...)
	output, err := command.Output()
	if err != nil {
		return "", err
	}
	return string(output), nil
}

func inspectSelfCheckTargets(requested []systemSelfCheckTarget, panelAddress string) []systemSelfCheckTargetResult {
	targets := make([]systemSelfCheckTarget, 0, len(requested)+1)
	if host, port, ok := splitSelfCheckEndpoint(panelAddress); ok {
		targets = append(targets, systemSelfCheckTarget{Name: "CloudNest 面板", Host: host, Port: port, Network: "tcp"})
	}
	targets = append(targets, requested...)
	validTargets := make([]systemSelfCheckTarget, 0, len(targets))
	seen := make(map[string]struct{})
	for _, target := range targets {
		host := strings.TrimSpace(target.Host)
		network := strings.ToLower(strings.TrimSpace(target.Network))
		if network == "" {
			network = "tcp"
		}
		if !isValidSelfCheckHost(host) || network != "tcp" || target.Port < 1 || target.Port > 65535 {
			continue
		}
		key := host + ":" + strconv.Itoa(target.Port)
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		target.Host = host
		target.Network = network
		validTargets = append(validTargets, target)
	}
	results := make([]systemSelfCheckTargetResult, len(validTargets))
	jobs := make(chan int)
	workers := 16
	if len(validTargets) < workers {
		workers = len(validTargets)
	}
	var wait sync.WaitGroup
	for worker := 0; worker < workers; worker++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			for index := range jobs {
				target := validTargets[index]
				started := time.Now()
				connection, err := net.DialTimeout("tcp", net.JoinHostPort(target.Host, strconv.Itoa(target.Port)), 3*time.Second)
				item := systemSelfCheckTargetResult{Name: strings.TrimSpace(target.Name), Host: target.Host, Port: target.Port,
					Network: target.Network, Reachable: err == nil, DurationMs: time.Since(started).Milliseconds()}
				if item.Name == "" {
					item.Name = target.Host + ":" + strconv.Itoa(target.Port)
				}
				if err != nil {
					item.Error = err.Error()
				} else {
					_ = connection.Close()
				}
				results[index] = item
			}
		}()
	}
	for index := range validTargets {
		jobs <- index
	}
	close(jobs)
	wait.Wait()
	return results
}

func splitSelfCheckEndpoint(address string) (string, int, bool) {
	address = strings.TrimSpace(address)
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return "", 0, false
	}
	parsed, err := strconv.Atoi(port)
	if err != nil || parsed < 1 || parsed > 65535 || !isValidSelfCheckHost(host) {
		return "", 0, false
	}
	return host, parsed, true
}

func isValidSelfCheckHost(host string) bool {
	if net.ParseIP(host) != nil {
		return true
	}
	if host == "" || len(host) > 253 || strings.ContainsAny(host, " /\\:@[]\t\r\n") {
		return false
	}
	return isValidHostname(host)
}

func normalizeSelfCheckDomains(domains []string) []string {
	seen := make(map[string]struct{})
	result := make([]string, 0, len(domains))
	for _, value := range domains {
		value = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
		if value == "" || len(value) > 253 || net.ParseIP(value) != nil {
			continue
		}
		if _, exists := seen[value]; exists {
			continue
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}

func inspectDomains(domains []string) []systemSelfCheckDNS {
	results := make([]systemSelfCheckDNS, len(domains))
	jobs := make(chan int)
	var workers sync.WaitGroup
	count := 10
	if len(domains) < count {
		count = len(domains)
	}
	for i := 0; i < count; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for index := range jobs {
				results[index] = inspectDomain(domains[index])
			}
		}()
	}
	for index := range domains {
		jobs <- index
	}
	close(jobs)
	workers.Wait()
	return results
}

func inspectDomain(domain string) systemSelfCheckDNS {
	result := systemSelfCheckDNS{Domain: domain, SystemA: []string{}, SystemAAAA: []string{}, PublicA: []string{}, PublicAAAA: []string{}}
	var systemError, publicAError, publicAAAAError error
	var checks sync.WaitGroup
	checks.Add(3)
	go func() {
		defer checks.Done()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		addresses, err := net.DefaultResolver.LookupIPAddr(ctx, domain)
		if err != nil {
			systemError = err
			return
		}
		for _, address := range addresses {
			if address.IP.To4() != nil {
				result.SystemA = append(result.SystemA, address.IP.String())
			} else {
				result.SystemAAAA = append(result.SystemAAAA, address.IP.String())
			}
		}
	}()
	go func() {
		defer checks.Done()
		result.PublicA, publicAError = queryPublicDNS(domain, "A")
	}()
	go func() {
		defer checks.Done()
		result.PublicAAAA, publicAAAAError = queryPublicDNS(domain, "AAAA")
	}()
	checks.Wait()
	if systemError != nil {
		result.SystemError = systemError.Error()
	}
	if publicAError != nil {
		result.PublicError = "A: " + publicAError.Error()
	}
	if publicAAAAError != nil {
		if result.PublicError != "" {
			result.PublicError += "; "
		}
		result.PublicError += "AAAA: " + publicAAAAError.Error()
	}
	result.SystemA = uniqueSorted(result.SystemA)
	result.SystemAAAA = uniqueSorted(result.SystemAAAA)
	result.PublicA = uniqueSorted(result.PublicA)
	result.PublicAAAA = uniqueSorted(result.PublicAAAA)
	return result
}

func queryPublicDNS(domain, recordType string) ([]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	endpoint := "https://1.1.1.1/dns-query?name=" + url.QueryEscape(domain) + "&type=" + recordType
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/dns-json")
	request.Header.Set("User-Agent", "CloudNest-System-Self-Check")
	client := &http.Client{Timeout: 5 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode/100 != 2 {
		return nil, fmt.Errorf("DoH returned HTTP %d", response.StatusCode)
	}
	var body struct {
		Status int `json:"Status"`
		Answer []struct {
			Type int    `json:"type"`
			Data string `json:"data"`
		} `json:"Answer"`
	}
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		return nil, err
	}
	if body.Status != 0 {
		return []string{}, nil
	}
	wanted := 1
	if recordType == "AAAA" {
		wanted = 28
	}
	values := make([]string, 0)
	for _, answer := range body.Answer {
		if answer.Type == wanted && net.ParseIP(strings.TrimSpace(answer.Data)) != nil {
			values = append(values, strings.TrimSpace(answer.Data))
		}
	}
	return values, nil
}

func inspectIPFamily(ipv6 bool) systemSelfCheckFamily {
	result := systemSelfCheckFamily{Addresses: []string{}}
	interfaces, err := net.Interfaces()
	if err != nil {
		result.Error = err.Error()
		return result
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, _ := iface.Addrs()
		for _, address := range addresses {
			ip, _, parseError := net.ParseCIDR(address.String())
			if parseError != nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() {
				continue
			}
			if ipv6 == (ip.To4() == nil) {
				result.Addresses = append(result.Addresses, ip.String())
			}
		}
	}
	result.Addresses = uniqueSorted(result.Addresses)
	result.Available = len(result.Addresses) > 0
	if !result.Available {
		return result
	}
	network, target := "udp4", "1.1.1.1:53"
	if ipv6 {
		network, target = "udp6", "[2606:4700:4700::1111]:53"
	}
	connection, dialError := net.DialTimeout(network, target, 3*time.Second)
	if dialError != nil {
		result.Error = dialError.Error()
		return result
	}
	result.Outbound = true
	_ = connection.Close()
	return result
}

func inspectRequestedPorts(requests []systemSelfCheckPortRequest) []systemSelfCheckPortResult {
	listening := make(map[string]bool)
	if connections, err := psnet.Connections("all"); err == nil {
		for _, connection := range connections {
			network := "udp"
			if connection.Type == 1 {
				network = "tcp"
				if !strings.EqualFold(connection.Status, "LISTEN") {
					continue
				}
			}
			listening[fmt.Sprintf("%s:%d", network, connection.Laddr.Port)] = true
		}
	}
	results := make([]systemSelfCheckPortResult, 0, len(requests))
	seen := make(map[string]struct{})
	for _, request := range requests {
		network := strings.ToLower(strings.TrimSpace(request.Network))
		if (network != "tcp" && network != "udp") || request.Port < 1 || request.Port > 65535 {
			continue
		}
		key := fmt.Sprintf("%s:%d", network, request.Port)
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		results = append(results, systemSelfCheckPortResult{Network: network, Port: request.Port, Listening: listening[key]})
	}
	sort.Slice(results, func(i, j int) bool {
		if results[i].Port == results[j].Port {
			return results[i].Network < results[j].Network
		}
		return results[i].Port < results[j].Port
	})
	return results
}

func readDNSResolvers() []string {
	content, err := os.ReadFile("/etc/resolv.conf")
	if err != nil {
		return []string{}
	}
	values := make([]string, 0)
	for _, line := range strings.Split(string(content), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 2 && fields[0] == "nameserver" {
			values = append(values, fields[1])
		}
	}
	return uniqueSorted(values)
}

func machineFingerprint(hostname string) string {
	identity := hostname
	for _, path := range []string{"/etc/machine-id", "/var/lib/dbus/machine-id"} {
		if content, err := os.ReadFile(path); err == nil && strings.TrimSpace(string(content)) != "" {
			identity = strings.TrimSpace(string(content)) + "|" + hostname
			break
		}
	}
	return shortFingerprint(identity)
}

func shortFingerprint(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])[:16]
}

func uniqueSorted(values []string) []string {
	seen := make(map[string]struct{})
	result := make([]string, 0, len(values))
	for _, value := range values {
		if _, exists := seen[value]; exists {
			continue
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	sort.Strings(result)
	return result
}
