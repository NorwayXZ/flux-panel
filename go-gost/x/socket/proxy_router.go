package socket

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// A proxy router is a sing-box process listening on loopback SOCKS5 that
// picks an outbound per connection by domain or address. A private proxy
// hands all of its traffic to its router through the ordinary outbound proxy
// settings, so the same routing works for sing-box and Xray proxies alike.

type proxyRouterRequest struct {
	Name      string                   `json:"name"`
	Outbounds []map[string]interface{} `json:"outbounds"`
	Rules     []map[string]interface{} `json:"rules"`
	Final     string                   `json:"final"`
}

type proxyRouterResponse struct {
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
}

type proxyRouterState struct {
	Name     string `json:"name"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
	Hash     string `json:"hash"`
}

type proxyExitTestRequest struct {
	Outbound map[string]interface{} `json:"outbound"`
}

type proxyExitTestResponse struct {
	IP        string `json:"ip"`
	Country   string `json:"country"`
	Colo      string `json:"colo"`
	LatencyMs int64  `json:"latencyMs"`
}

var (
	routerTagPattern     = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)
	routerOutboundTypes  = map[string]bool{"direct": true, "socks": true, "http": true, "shadowsocks": true, "vmess": true, "vless": true, "trojan": true, "hysteria2": true, "tuic": true, "anytls": true}
	routerRuleMatchKeys  = map[string]bool{"domain": true, "domain_suffix": true, "domain_keyword": true, "ip_cidr": true}
	proxyExitTraceURL    = "https://www.cloudflare.com/cdn-cgi/trace"
	proxyExitTestTimeout = 12 * time.Second
)

type proxyRouterManager struct {
	mu        sync.Mutex
	runtime   *privateProxyRuntimeManager
	directory string
	processes map[string]*exec.Cmd
	stopping  map[string]bool
}

func newProxyRouterManager(runtime *privateProxyRuntimeManager) *proxyRouterManager {
	return &proxyRouterManager{
		runtime:   runtime,
		directory: filepath.Join(runtime.directory, "routers"),
		processes: make(map[string]*exec.Cmd),
		stopping:  make(map[string]bool),
	}
}

// buildRouterConfig validates what the panel sent and assembles the sing-box
// configuration. Only plain outbound and match fields are accepted.
func buildRouterConfig(request proxyRouterRequest, port int, username, password string) (map[string]interface{}, error) {
	tags := map[string]bool{}
	outbounds := make([]interface{}, 0, len(request.Outbounds)+1)
	for _, outbound := range request.Outbounds {
		kind, _ := outbound["type"].(string)
		tag, _ := outbound["tag"].(string)
		if !routerOutboundTypes[kind] {
			return nil, fmt.Errorf("unsupported router outbound type %q", kind)
		}
		if !routerTagPattern.MatchString(tag) || tags[tag] {
			return nil, fmt.Errorf("invalid router outbound tag %q", tag)
		}
		if _, set := outbound["detour"]; set {
			return nil, errors.New("router outbounds cannot chain through other outbounds")
		}
		tags[tag] = true
		outbounds = append(outbounds, outbound)
	}
	if !tags["direct"] {
		outbounds = append(outbounds, map[string]interface{}{"type": "direct", "tag": "direct"})
		tags["direct"] = true
	}
	if request.Final == "" {
		request.Final = "direct"
	}
	if !tags[request.Final] {
		return nil, fmt.Errorf("router final outbound %q is not defined", request.Final)
	}

	// Sniffing recovers the domain from TLS, HTTP and QUIC when a client
	// connects by address, so domain rules still apply.
	rules := []interface{}{map[string]interface{}{"action": "sniff"}}
	for index, rule := range request.Rules {
		next := map[string]interface{}{}
		matched := false
		for key, value := range rule {
			switch {
			case routerRuleMatchKeys[key]:
				values, ok := stringList(value)
				if !ok || len(values) == 0 || len(values) > 2000 {
					return nil, fmt.Errorf("router rule %d: invalid %s", index+1, key)
				}
				next[key] = values
				matched = true
			case key == "outbound":
				tag, _ := value.(string)
				if !tags[tag] {
					return nil, fmt.Errorf("router rule %d: unknown outbound %q", index+1, tag)
				}
				next["action"] = "route"
				next["outbound"] = tag
			case key == "action":
				if value != "reject" {
					return nil, fmt.Errorf("router rule %d: unsupported action", index+1)
				}
				next["action"] = "reject"
			default:
				return nil, fmt.Errorf("router rule %d: unsupported field %s", index+1, key)
			}
		}
		if !matched || next["action"] == nil {
			return nil, fmt.Errorf("router rule %d needs a match and an outbound", index+1)
		}
		rules = append(rules, next)
	}

	return map[string]interface{}{
		"log": map[string]interface{}{"level": "warn"},
		"dns": map[string]interface{}{"servers": []interface{}{map[string]interface{}{"type": "local", "tag": "local"}}},
		"inbounds": []interface{}{map[string]interface{}{
			"type": "socks", "tag": "in", "listen": "127.0.0.1", "listen_port": port,
			"users": []interface{}{map[string]interface{}{"username": username, "password": password}},
		}},
		"outbounds": outbounds,
		"route":     map[string]interface{}{"rules": rules, "final": request.Final, "default_domain_resolver": "local"},
	}, nil
}

func stringList(value interface{}) ([]string, bool) {
	items, ok := value.([]interface{})
	if !ok {
		if typed, ok := value.([]string); ok {
			return typed, true
		}
		return nil, false
	}
	out := make([]string, 0, len(items))
	for _, item := range items {
		text, ok := item.(string)
		text = strings.TrimSpace(text)
		if !ok || text == "" || len(text) > 253 {
			return nil, false
		}
		out = append(out, text)
	}
	return out, true
}

func (m *proxyRouterManager) apply(request proxyRouterRequest) (proxyRouterResponse, error) {
	request.Name = strings.TrimSpace(request.Name)
	if !runtimeNamePattern.MatchString(request.Name) {
		return proxyRouterResponse{}, errors.New("invalid proxy router name")
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return proxyRouterResponse{}, fmt.Errorf("create proxy router directory: %w", err)
	}
	state, err := m.readState(request.Name)
	if err != nil {
		port, portErr := availableLocalPort()
		if portErr != nil {
			return proxyRouterResponse{}, portErr
		}
		state = proxyRouterState{Name: request.Name, Port: port, Username: "router", Password: randomToken(18)}
	}
	config, err := buildRouterConfig(request, state.Port, state.Username, state.Password)
	if err != nil {
		return proxyRouterResponse{}, err
	}
	content, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return proxyRouterResponse{}, err
	}
	sum := sha256.Sum256(content)
	hash := hex.EncodeToString(sum[:])
	if hash != state.Hash {
		if err := os.WriteFile(m.configPath(state.Name), content, 0600); err != nil {
			return proxyRouterResponse{}, fmt.Errorf("write proxy router config: %w", err)
		}
		if err := m.runtime.ensureBinary(); err != nil {
			return proxyRouterResponse{}, err
		}
		if output, err := exec.Command(m.runtime.binary, "check", "-c", m.configPath(state.Name)).CombinedOutput(); err != nil {
			return proxyRouterResponse{}, fmt.Errorf("proxy router config rejected: %s", strings.TrimSpace(string(output)))
		}
		state.Hash = hash
		if err := m.writeState(state); err != nil {
			return proxyRouterResponse{}, err
		}
		m.stop(state.Name)
	}
	if err := m.ensureRunning(state); err != nil {
		return proxyRouterResponse{}, err
	}
	return proxyRouterResponse{Port: state.Port, Username: state.Username, Password: state.Password}, nil
}

func (m *proxyRouterManager) delete(name string) error {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return errors.New("invalid proxy router name")
	}
	m.stop(name)
	for _, path := range []string{m.configPath(name), m.statePath(name), m.logPath(name)} {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}

func (m *proxyRouterManager) restore() {
	entries, err := os.ReadDir(m.directory)
	if err != nil {
		return
	}
	for _, entry := range entries {
		name, ok := strings.CutSuffix(entry.Name(), ".state.json")
		if !ok {
			continue
		}
		if state, err := m.readState(name); err == nil {
			_ = m.ensureRunning(state)
		}
	}
}

func (m *proxyRouterManager) stopAll() {
	m.mu.Lock()
	names := make([]string, 0, len(m.processes))
	for name := range m.processes {
		names = append(names, name)
	}
	m.mu.Unlock()
	for _, name := range names {
		m.stop(name)
	}
}

func (m *proxyRouterManager) stop(name string) {
	m.mu.Lock()
	cmd := m.processes[name]
	m.stopping[name] = true
	delete(m.processes, name)
	m.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}
}

func (m *proxyRouterManager) ensureRunning(state proxyRouterState) error {
	m.mu.Lock()
	if cmd := m.processes[state.Name]; cmd != nil && processAlive(cmd) {
		m.mu.Unlock()
		return nil
	}
	m.mu.Unlock()
	if err := m.runtime.ensureBinary(); err != nil {
		return err
	}
	logFile, err := os.OpenFile(m.logPath(state.Name), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return fmt.Errorf("open proxy router log: %w", err)
	}
	cmd := exec.Command(m.runtime.binary, "run", "-c", m.configPath(state.Name))
	cmd.Stdout, cmd.Stderr = logFile, logFile
	if err := cmd.Start(); err != nil {
		logFile.Close()
		return fmt.Errorf("start proxy router: %w", err)
	}
	logFile.Close()
	m.mu.Lock()
	m.processes[state.Name] = cmd
	delete(m.stopping, state.Name)
	m.mu.Unlock()
	go m.watch(state.Name, cmd)
	if err := waitForListener("127.0.0.1", state.Port, 4*time.Second); err != nil {
		_ = cmd.Process.Kill()
		return fmt.Errorf("proxy router did not become ready: %w", err)
	}
	return nil
}

func (m *proxyRouterManager) watch(name string, cmd *exec.Cmd) {
	_ = cmd.Wait()
	m.mu.Lock()
	if m.processes[name] == cmd {
		delete(m.processes, name)
	}
	restart := !m.stopping[name]
	m.mu.Unlock()
	if !restart {
		return
	}
	time.Sleep(2 * time.Second)
	if state, err := m.readState(name); err == nil {
		_ = m.ensureRunning(state)
	}
}

// testExit starts a short-lived router whose only outbound is the exit under
// test and fetches Cloudflare's trace through it.
func (m *proxyRouterManager) testExit(request proxyExitTestRequest) (proxyExitTestResponse, error) {
	if request.Outbound == nil {
		return proxyExitTestResponse{}, errors.New("missing exit outbound")
	}
	outbound := map[string]interface{}{}
	for key, value := range request.Outbound {
		outbound[key] = value
	}
	outbound["tag"] = "exit"
	name := "exit-test-" + randomToken(6)
	port, err := availableLocalPort()
	if err != nil {
		return proxyExitTestResponse{}, err
	}
	password := randomToken(18)
	config, err := buildRouterConfig(proxyRouterRequest{Name: name, Outbounds: []map[string]interface{}{outbound}, Final: "exit"}, port, "test", password)
	if err != nil {
		return proxyExitTestResponse{}, err
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return proxyExitTestResponse{}, err
	}
	content, _ := json.Marshal(config)
	path := filepath.Join(m.directory, name+".json")
	if err := os.WriteFile(path, content, 0600); err != nil {
		return proxyExitTestResponse{}, err
	}
	defer os.Remove(path)
	if err := m.runtime.ensureBinary(); err != nil {
		return proxyExitTestResponse{}, err
	}
	var output bytes.Buffer
	cmd := exec.Command(m.runtime.binary, "run", "-c", path)
	cmd.Stdout, cmd.Stderr = &output, &output
	if err := cmd.Start(); err != nil {
		return proxyExitTestResponse{}, fmt.Errorf("start exit test: %w", err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()
	if err := waitForListener("127.0.0.1", port, 4*time.Second); err != nil {
		return proxyExitTestResponse{}, fmt.Errorf("exit test did not start: %s", strings.TrimSpace(output.String()))
	}
	proxyURL := &url.URL{Scheme: "socks5", User: url.UserPassword("test", password), Host: fmt.Sprintf("127.0.0.1:%d", port)}
	client := &http.Client{Timeout: proxyExitTestTimeout, Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	started := time.Now()
	response, err := client.Get(proxyExitTraceURL)
	if err != nil {
		return proxyExitTestResponse{}, fmt.Errorf("connect through exit: %w", err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 16*1024))
	if response.StatusCode != http.StatusOK {
		return proxyExitTestResponse{}, fmt.Errorf("trace returned HTTP %d", response.StatusCode)
	}
	result := proxyExitTestResponse{LatencyMs: time.Since(started).Milliseconds()}
	for _, line := range strings.Split(string(body), "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok {
			continue
		}
		switch key {
		case "ip":
			result.IP = value
		case "loc":
			result.Country = value
		case "colo":
			result.Colo = value
		}
	}
	if result.IP == "" {
		return proxyExitTestResponse{}, errors.New("trace did not report an exit address")
	}
	return result, nil
}

func (m *proxyRouterManager) readState(name string) (proxyRouterState, error) {
	var state proxyRouterState
	content, err := os.ReadFile(m.statePath(name))
	if err != nil {
		return state, err
	}
	if err := json.Unmarshal(content, &state); err != nil {
		return state, err
	}
	if state.Name != name || state.Port < 1 {
		return state, errors.New("invalid proxy router state")
	}
	return state, nil
}

func (m *proxyRouterManager) writeState(state proxyRouterState) error {
	content, err := json.Marshal(state)
	if err != nil {
		return err
	}
	return os.WriteFile(m.statePath(state.Name), content, 0600)
}

func (m *proxyRouterManager) statePath(name string) string {
	return filepath.Join(m.directory, name+".state.json")
}

func (m *proxyRouterManager) configPath(name string) string {
	return filepath.Join(m.directory, name+".json")
}

func (m *proxyRouterManager) logPath(name string) string {
	return filepath.Join(m.directory, name+".log")
}

func randomToken(bytesLength int) string {
	buffer := make([]byte, bytesLength)
	_, _ = rand.Read(buffer)
	return hex.EncodeToString(buffer)
}
