package socket

import (
	"archive/tar"
	"compress/gzip"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const singBoxRuntimeVersion = "1.13.15"

type privateProxyRuntimeRequest struct {
	Name       string `json:"name"`
	ProxyType  string `json:"proxyType"`
	BindIP     string `json:"bindIp"`
	ListenPort int    `json:"listenPort"`
	Password   string `json:"password"`
	ServerName string `json:"serverName"`
	// A local SOCKS5 service that all outbound traffic is handed to. When
	// absent the runtime connects to destinations directly.
	OutboundProxyPort     int    `json:"outboundProxyPort,omitempty"`
	OutboundProxyUsername string `json:"outboundProxyUsername,omitempty"`
	OutboundProxyPassword string `json:"outboundProxyPassword,omitempty"`

	privateProxyRuntimeOptions
	// Source addresses allowed to use the proxy. Empty allows everyone.
	AllowedCIDRs []string `json:"allowedCidrs,omitempty"`
}

// privateProxyRuntimeOptions are the protocol settings that clients must
// match. Changing any of them requires a new runtime.
type privateProxyRuntimeOptions struct {
	// Client UUID for VMess, VLESS and TUIC. Generated when empty.
	ClientID string `json:"clientId,omitempty"`
	// VMess/VLESS transport: tcp, ws, grpc or httpupgrade.
	Transport string `json:"transport,omitempty"`
	// VMess/VLESS over TLS. The other TLS protocols always use it.
	TLS bool `json:"tls,omitempty"`
	// WebSocket/HTTPUpgrade path or gRPC service name.
	Path string `json:"path,omitempty"`
	// Shadowsocks 2022 method, also used inside ShadowTLS.
	Method string `json:"method,omitempty"`
	// Hysteria2 salamander obfuscation password. Empty disables it.
	ObfsPassword string `json:"obfsPassword,omitempty"`
	// ShadowTLS handshake server, a real TLS site.
	HandshakeServer string `json:"handshakeServer,omitempty"`
	// ShadowTLS inner Shadowsocks 2022 key.
	InnerPassword string `json:"innerPassword,omitempty"`
}

type privateProxyRuntimeResponse struct {
	Version          string `json:"version"`
	ClientID         string `json:"clientId,omitempty"`
	ClientPrivateKey string `json:"clientPrivateKey,omitempty"`
	ClientAddress    string `json:"clientAddress,omitempty"`
	ServerPublicKey  string `json:"serverPublicKey,omitempty"`
	ServerAddress    string `json:"serverAddress,omitempty"`
	ServerName       string `json:"serverName,omitempty"`
	// SHA-256 of the self-signed certificate (hex) and of its public key
	// (base64), so clients can pin it instead of skipping verification.
	CertSHA256      string `json:"certSha256,omitempty"`
	PublicKeySHA256 string `json:"publicKeySha256,omitempty"`
	Transport       string `json:"transport,omitempty"`
	Path            string `json:"path,omitempty"`
	Method          string `json:"method,omitempty"`
}

type privateProxyRuntimeTraffic struct {
	InFlow  int64 `json:"inFlow"`
	OutFlow int64 `json:"outFlow"`
}

type privateProxyRuntimeState struct {
	Name             string `json:"name"`
	ProxyType        string `json:"proxyType"`
	BindIP           string `json:"bindIp"`
	ListenPort       int    `json:"listenPort"`
	Password         string `json:"password"`
	ServerName       string `json:"serverName"`
	ClientID         string `json:"clientId,omitempty"`
	ClientPrivateKey string `json:"clientPrivateKey,omitempty"`
	ClientAddress    string `json:"clientAddress,omitempty"`
	ServerPrivateKey string `json:"serverPrivateKey,omitempty"`
	ServerPublicKey  string `json:"serverPublicKey,omitempty"`
	ServerAddress    string `json:"serverAddress,omitempty"`
	ControllerPort   int    `json:"controllerPort,omitempty"`
	Version          string `json:"version"`

	OutboundProxyPort     int    `json:"outboundProxyPort,omitempty"`
	OutboundProxyUsername string `json:"outboundProxyUsername,omitempty"`
	OutboundProxyPassword string `json:"outboundProxyPassword,omitempty"`

	Transport       string   `json:"transport,omitempty"`
	TLS             bool     `json:"tls,omitempty"`
	Path            string   `json:"path,omitempty"`
	Method          string   `json:"method,omitempty"`
	ObfsPassword    string   `json:"obfsPassword,omitempty"`
	HandshakeServer string   `json:"handshakeServer,omitempty"`
	InnerPassword   string   `json:"innerPassword,omitempty"`
	InnerPort       int      `json:"innerPort,omitempty"`
	AllowedCIDRs    []string `json:"allowedCidrs,omitempty"`
}

type privateProxyRuntimeManager struct {
	mu        sync.Mutex
	directory string
	binary    string
	// binaryMu guards binaryUsable, set once the binary is known to start.
	binaryMu     sync.Mutex
	binaryUsable bool
	processes    map[string]*exec.Cmd
	stopping     map[string]bool
	wireguards   map[string]*wireGuardRuntime
}

func newPrivateProxyRuntimeManager() *privateProxyRuntimeManager {
	executable, err := os.Executable()
	base := "."
	if err == nil {
		base = filepath.Dir(executable)
	}
	directory := filepath.Join(base, "sing-box-runtime")
	binaryName := "sing-box-" + singBoxRuntimeVersion
	if runtime.GOOS == "windows" {
		binaryName += ".exe"
	}
	return &privateProxyRuntimeManager{
		directory:  directory,
		binary:     filepath.Join(directory, binaryName),
		processes:  make(map[string]*exec.Cmd),
		stopping:   make(map[string]bool),
		wireguards: make(map[string]*wireGuardRuntime),
	}
}

func (m *privateProxyRuntimeManager) add(request privateProxyRuntimeRequest) (privateProxyRuntimeResponse, error) {
	if runtime.GOOS != "linux" {
		return privateProxyRuntimeResponse{}, fmt.Errorf("%s private proxy is currently supported only on Linux nodes", request.ProxyType)
	}
	request.Name = strings.TrimSpace(request.Name)
	request.ProxyType = strings.ToLower(strings.TrimSpace(request.ProxyType))
	request.BindIP = strings.TrimSpace(strings.Trim(request.BindIP, "[]"))
	request.Password = strings.TrimSpace(request.Password)
	request.ServerName = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(request.ServerName), "."))
	if !runtimeNamePattern.MatchString(request.Name) {
		return privateProxyRuntimeResponse{}, errors.New("invalid private proxy runtime name")
	}
	if !supportedAdvancedProxyType(request.ProxyType) {
		return privateProxyRuntimeResponse{}, errors.New("unsupported private proxy runtime type")
	}
	if request.ListenPort < 1 || request.ListenPort > 65535 {
		return privateProxyRuntimeResponse{}, errors.New("invalid private proxy runtime settings")
	}
	if err := normalizeRuntimeOptions(&request); err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if request.BindIP != "" && net.ParseIP(request.BindIP) == nil {
		return privateProxyRuntimeResponse{}, errors.New("invalid private proxy bind IP")
	}
	if request.ServerName == "" {
		request.ServerName = "cloudnest.local"
	}
	if request.OutboundProxyPort != 0 {
		if request.ProxyType == "wireguard" {
			return privateProxyRuntimeResponse{}, errors.New("WireGuard does not support a routed exit")
		}
		if request.OutboundProxyPort < 1 || request.OutboundProxyPort > 65535 ||
			request.OutboundProxyUsername == "" || request.OutboundProxyPassword == "" {
			return privateProxyRuntimeResponse{}, errors.New("invalid private proxy outbound route")
		}
	} else {
		request.OutboundProxyUsername, request.OutboundProxyPassword = "", ""
	}
	if request.ProxyType == "wireguard" {
		return m.addWireGuard(request)
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return privateProxyRuntimeResponse{}, fmt.Errorf("create private proxy runtime directory: %w", err)
	}
	if existing, err := m.readState(request.Name); err == nil {
		if !existing.matches(request) {
			return privateProxyRuntimeResponse{}, errors.New("private proxy runtime already exists with different settings")
		}
		if existing.OutboundProxyPort != request.OutboundProxyPort || existing.OutboundProxyUsername != request.OutboundProxyUsername ||
			existing.OutboundProxyPassword != request.OutboundProxyPassword || !sameStrings(existing.AllowedCIDRs, request.AllowedCIDRs) {
			// Only where traffic leaves or who may connect changes;
			// certificates and client credentials are kept.
			existing.OutboundProxyPort = request.OutboundProxyPort
			existing.OutboundProxyUsername, existing.OutboundProxyPassword = request.OutboundProxyUsername, request.OutboundProxyPassword
			existing.AllowedCIDRs = request.AllowedCIDRs
			if err := m.writeStateAndConfig(existing); err != nil {
				return privateProxyRuntimeResponse{}, err
			}
			m.stop(existing.Name)
		}
		if err := m.ensureRunning(existing); err != nil {
			return privateProxyRuntimeResponse{}, err
		}
		return m.response(existing), nil
	}
	if err := m.ensureBinary(); err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	state, err := newPrivateProxyRuntimeState(request)
	if err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if state.ProxyType != "wireguard" {
		state.ControllerPort, err = m.availableControllerPort(state.Name)
		if err != nil {
			return privateProxyRuntimeResponse{}, err
		}
	}
	if state.ProxyType == "shadowtls" {
		if state.InnerPort, err = availableLocalPort(); err != nil {
			return privateProxyRuntimeResponse{}, err
		}
	}
	if err := m.writeStateAndConfig(state); err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if err := m.ensureRunning(state); err != nil {
		m.removeFiles(state.Name)
		return privateProxyRuntimeResponse{}, err
	}
	return m.response(state), nil
}

func (m *privateProxyRuntimeManager) delete(name string) error {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return errors.New("invalid private proxy runtime name")
	}
	if state, err := m.readState(name); err == nil && state.ProxyType == "wireguard" {
		m.stopWireGuard(name)
	} else {
		m.stop(name)
	}
	return m.removeFiles(name)
}

func (m *privateProxyRuntimeManager) pause(name string) error {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return errors.New("invalid private proxy runtime name")
	}
	state, err := m.readState(name)
	if err != nil {
		return fmt.Errorf("private proxy runtime not found: %w", err)
	}
	if state.ProxyType == "wireguard" {
		m.stopWireGuard(name)
		return nil
	}
	m.stop(name)
	return nil
}

func (m *privateProxyRuntimeManager) resume(name string) error {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return errors.New("invalid private proxy runtime name")
	}
	state, err := m.readState(name)
	if err != nil {
		return fmt.Errorf("private proxy runtime not found: %w", err)
	}
	if state.ProxyType == "wireguard" {
		return m.ensureWireGuardRunning(state)
	}
	return m.ensureRunning(state)
}

func (m *privateProxyRuntimeManager) traffic(name string) (privateProxyRuntimeTraffic, error) {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return privateProxyRuntimeTraffic{}, errors.New("invalid private proxy runtime name")
	}
	state, err := m.readState(name)
	if err != nil {
		return privateProxyRuntimeTraffic{}, fmt.Errorf("private proxy runtime not found: %w", err)
	}
	if state.ProxyType == "wireguard" {
		return m.wireGuardTraffic(name)
	}
	if state.ControllerPort < 1 {
		return privateProxyRuntimeTraffic{}, errors.New("private proxy runtime traffic endpoint is unavailable; restart the runtime after upgrading Agent")
	}
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/connections", state.ControllerPort))
	if err != nil {
		return privateProxyRuntimeTraffic{}, fmt.Errorf("read private proxy runtime traffic: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return privateProxyRuntimeTraffic{}, fmt.Errorf("private proxy runtime traffic returned HTTP %d", response.StatusCode)
	}
	var counters struct {
		UploadTotal   int64 `json:"uploadTotal"`
		DownloadTotal int64 `json:"downloadTotal"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 1024*1024)).Decode(&counters); err != nil {
		return privateProxyRuntimeTraffic{}, fmt.Errorf("decode private proxy runtime traffic: %w", err)
	}
	return privateProxyRuntimeTraffic{InFlow: maxInt64(counters.UploadTotal), OutFlow: maxInt64(counters.DownloadTotal)}, nil
}

func (m *privateProxyRuntimeManager) restore() {
	entries, err := os.ReadDir(m.directory)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".state.json") {
			continue
		}
		name := strings.TrimSuffix(entry.Name(), ".state.json")
		state, err := m.readState(name)
		if err != nil {
			fmt.Printf("private proxy runtime restore %s failed: %v\n", name, err)
			continue
		}
		if state.ProxyType != "wireguard" {
			// Regenerate the configuration from the saved state so runtimes
			// created by an older Agent pick up fixes; keys and certificates
			// are kept.
			if err := m.writeStateAndConfig(state); err != nil {
				fmt.Printf("private proxy runtime restore %s failed: %v\n", name, err)
				continue
			}
		}
		if err := m.ensureRunning(state); err != nil {
			fmt.Printf("private proxy runtime restore %s failed: %v\n", name, err)
		}
	}
}

func (m *privateProxyRuntimeManager) stopAll() {
	m.mu.Lock()
	names := make([]string, 0, len(m.processes))
	for name := range m.processes {
		names = append(names, name)
	}
	m.mu.Unlock()
	for _, name := range names {
		m.stop(name)
	}
	m.stopAllWireGuards()
}

func (m *privateProxyRuntimeManager) stop(name string) {
	m.mu.Lock()
	m.stopping[name] = true
	cmd := m.processes[name]
	m.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		waitForProcess(cmd, 3*time.Second)
		if processAlive(cmd) {
			_ = cmd.Process.Kill()
		}
	}
	m.mu.Lock()
	delete(m.processes, name)
	delete(m.stopping, name)
	m.mu.Unlock()
}

func (m *privateProxyRuntimeManager) ensureRunning(state privateProxyRuntimeState) error {
	if state.ProxyType == "wireguard" {
		return m.ensureWireGuardRunning(state)
	}
	m.mu.Lock()
	if cmd := m.processes[state.Name]; cmd != nil && processAlive(cmd) {
		m.mu.Unlock()
		return nil
	}
	m.mu.Unlock()
	if state.ControllerPort < 1 {
		controllerPort, err := m.availableControllerPort(state.Name)
		if err != nil {
			return err
		}
		state.ControllerPort = controllerPort
		if err := m.writeStateAndConfig(state); err != nil {
			return fmt.Errorf("upgrade private proxy runtime traffic endpoint: %w", err)
		}
	}
	if err := m.ensureBinary(); err != nil {
		return err
	}
	if err := m.validateConfig(state); err != nil {
		return err
	}
	logFile, err := os.OpenFile(m.logPath(state.Name), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return fmt.Errorf("open private proxy runtime log: %w", err)
	}
	cmd := exec.Command(m.binary, "run", "-c", m.configPath(state.Name))
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		logFile.Close()
		return fmt.Errorf("start %s runtime: %w", state.ProxyType, err)
	}
	logFile.Close()
	m.mu.Lock()
	m.processes[state.Name] = cmd
	delete(m.stopping, state.Name)
	m.mu.Unlock()
	go m.watch(state.Name, cmd)
	if !udpRuntimeType(state.ProxyType) {
		if err := waitForListener(state.BindIP, state.ListenPort, 4*time.Second); err != nil {
			_ = cmd.Process.Kill()
			return fmt.Errorf("%s runtime did not become ready: %w", state.ProxyType, err)
		}
	} else {
		time.Sleep(400 * time.Millisecond)
		if !processAlive(cmd) {
			return fmt.Errorf("%s runtime stopped during startup; check %s", state.ProxyType, m.logPath(state.Name))
		}
	}
	return nil
}

func (m *privateProxyRuntimeManager) watch(name string, cmd *exec.Cmd) {
	_ = cmd.Wait()
	m.mu.Lock()
	if m.processes[name] == cmd {
		delete(m.processes, name)
	}
	shouldRestart := !m.stopping[name]
	m.mu.Unlock()
	if !shouldRestart {
		return
	}
	time.Sleep(2 * time.Second)
	if state, err := m.readState(name); err == nil {
		_ = m.ensureRunning(state)
	}
}

func (m *privateProxyRuntimeManager) validateConfig(state privateProxyRuntimeState) error {
	command := exec.Command(m.binary, "check", "-c", m.configPath(state.Name))
	output, err := command.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s configuration check failed: %s", state.ProxyType, strings.TrimSpace(string(output)))
	}
	return nil
}

func (m *privateProxyRuntimeManager) ensureBinary() error {
	m.binaryMu.Lock()
	defer m.binaryMu.Unlock()
	if m.binaryUsable {
		return nil
	}
	if info, err := os.Stat(m.binary); err == nil && info.Mode().IsRegular() {
		// Builds before Agent 2.82.2 downloaded sing-box's default Linux build,
		// which needs glibc. On systems without it (Alpine, slim containers)
		// the file exists but cannot start, so it is replaced with the static
		// build below.
		if exec.Command(m.binary, "version").Run() == nil {
			m.binaryUsable = true
			return nil
		}
		_ = os.Remove(m.binary)
	}
	if runtime.GOOS != "linux" {
		return errors.New("advanced private proxy runtime is supported only on Linux")
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return err
	}
	asset, err := singBoxAssetName()
	if err != nil {
		return err
	}
	archivePath := filepath.Join(m.directory, ".sing-box-download.tar.gz")
	url := "https://github.com/SagerNet/sing-box/releases/download/v" + singBoxRuntimeVersion + "/" + asset
	client := &http.Client{Timeout: 2 * time.Minute}
	if err := downloadFile(client, url, archivePath, 96*1024*1024); err != nil {
		return fmt.Errorf("download sing-box runtime: %w", err)
	}
	defer os.Remove(archivePath)
	temporary := m.binary + ".tmp"
	if err := extractSingBox(archivePath, temporary); err != nil {
		return err
	}
	if err := os.Chmod(temporary, 0755); err != nil {
		return err
	}
	if err := os.Rename(temporary, m.binary); err != nil {
		return err
	}
	if output, err := exec.Command(m.binary, "version").CombinedOutput(); err != nil {
		return fmt.Errorf("sing-box runtime cannot start on this system: %v %s", err, strings.TrimSpace(string(output)))
	}
	m.binaryUsable = true
	return nil
}

func newPrivateProxyRuntimeState(request privateProxyRuntimeRequest) (privateProxyRuntimeState, error) {
	state := privateProxyRuntimeState{
		Name: request.Name, ProxyType: request.ProxyType, BindIP: request.BindIP, ListenPort: request.ListenPort,
		Password: request.Password, ServerName: request.ServerName, Version: singBoxRuntimeVersion,
		OutboundProxyPort: request.OutboundProxyPort, OutboundProxyUsername: request.OutboundProxyUsername,
		OutboundProxyPassword: request.OutboundProxyPassword,
		ClientID:              request.ClientID, Transport: request.Transport, TLS: request.TLS, Path: request.Path,
		Method: request.Method, ObfsPassword: request.ObfsPassword, HandshakeServer: request.HandshakeServer,
		InnerPassword: request.InnerPassword, AllowedCIDRs: request.AllowedCIDRs,
	}
	if (request.ProxyType == "tuic" || request.ProxyType == "vmess" || request.ProxyType == "vless") && state.ClientID == "" {
		id, err := randomRuntimeUUID()
		if err != nil {
			return state, err
		}
		state.ClientID = id
	}
	if request.ProxyType == "wireguard" {
		serverPrivate, serverPublic, err := newWireGuardKeypair()
		if err != nil {
			return state, err
		}
		clientPrivate, clientPublic, err := newWireGuardKeypair()
		if err != nil {
			return state, err
		}
		state.ServerPrivateKey = serverPrivate
		state.ServerPublicKey = serverPublic
		state.ClientPrivateKey = clientPrivate
		state.ClientID = clientPublic
		hash := sha256.Sum256([]byte(request.Name))
		subnet := int(hash[0])%254 + 1
		state.ServerAddress = fmt.Sprintf("10.66.%d.1/24", subnet)
		state.ClientAddress = fmt.Sprintf("10.66.%d.2/32", subnet)
	}
	return state, nil
}

func (m *privateProxyRuntimeManager) writeStateAndConfig(state privateProxyRuntimeState) error {
	stateBytes, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	if err := writeRuntimeFile(m.statePath(state.Name), stateBytes, 0600); err != nil {
		return err
	}
	if state.ProxyType == "wireguard" {
		return nil
	}
	if err := m.writeCertificate(state); err != nil {
		return err
	}
	configBytes, err := json.MarshalIndent(m.runtimeConfig(state), "", "  ")
	if err != nil {
		return err
	}
	return writeRuntimeFile(m.configPath(state.Name), configBytes, 0600)
}

func (m *privateProxyRuntimeManager) runtimeConfig(state privateProxyRuntimeState) map[string]interface{} {
	listen := state.BindIP
	if listen == "" {
		listen = "0.0.0.0"
	}
	inbound := map[string]interface{}{"type": state.ProxyType, "tag": state.Name, "listen": listen, "listen_port": state.ListenPort}
	if state.usesTLS() {
		tls := map[string]interface{}{"enabled": true, "certificate_path": m.certPath(state.Name), "key_path": m.keyPath(state.Name)}
		if state.ProxyType == "tuic" {
			// QUIC needs an application protocol; Clash/mihomo clients refuse
			// servers that do not select one.
			tls["alpn"] = []string{"h3"}
		}
		inbound["tls"] = tls
	}
	inbounds := []interface{}{inbound}
	switch state.ProxyType {
	case "trojan":
		inbound["users"] = []interface{}{map[string]interface{}{"password": state.Password}}
	case "hysteria2":
		inbound["users"] = []interface{}{map[string]interface{}{"password": state.Password}}
		inbound["masquerade"] = "https://www.cloudflare.com/"
		if state.ObfsPassword != "" {
			inbound["obfs"] = map[string]interface{}{"type": "salamander", "password": state.ObfsPassword}
		}
	case "tuic":
		inbound["users"] = []interface{}{map[string]interface{}{"uuid": state.ClientID, "password": state.Password}}
		inbound["congestion_control"] = "bbr"
	case "anytls":
		inbound["users"] = []interface{}{map[string]interface{}{"name": "cloudnest", "password": state.Password}}
	case "shadowsocks2022":
		inbound["type"] = "shadowsocks"
		inbound["method"] = state.Method
		inbound["password"] = state.Password
	case "vmess":
		inbound["users"] = []interface{}{map[string]interface{}{"uuid": state.ClientID, "alterId": 0}}
	case "vless":
		inbound["users"] = []interface{}{map[string]interface{}{"uuid": state.ClientID}}
	case "shadowtls":
		inner := state.Name + "-ss"
		inbound["version"] = 3
		inbound["users"] = []interface{}{map[string]interface{}{"name": "cloudnest", "password": state.Password}}
		inbound["handshake"] = map[string]interface{}{"server": state.HandshakeServer, "server_port": 443}
		inbound["strict_mode"] = true
		inbound["detour"] = inner
		inbounds = append(inbounds, map[string]interface{}{
			"type": "shadowsocks", "tag": inner, "listen": "127.0.0.1", "listen_port": state.InnerPort,
			"method": state.Method, "password": state.InnerPassword,
		})
	}
	if transport := runtimeTransport(state.Transport, state.Path); transport != nil {
		inbound["transport"] = transport
	}
	outbound := map[string]interface{}{"type": "direct", "tag": "direct"}
	if state.OutboundProxyPort > 0 {
		// The only outbound: with the local route unavailable connections
		// fail instead of leaving from the node's own address.
		outbound = map[string]interface{}{
			"type": "socks", "tag": "routed", "version": "5", "server": "127.0.0.1", "server_port": state.OutboundProxyPort,
			"username": state.OutboundProxyUsername, "password": state.OutboundProxyPassword,
		}
	}
	config := map[string]interface{}{
		"log":       map[string]interface{}{"level": "warn"},
		"inbounds":  inbounds,
		"outbounds": []interface{}{outbound},
	}
	if len(state.AllowedCIDRs) > 0 {
		// Connections from other addresses are refused once they arrive.
		// Loopback stays allowed so the node can test its own proxy.
		allowed := append(append([]string{}, state.AllowedCIDRs...), "127.0.0.0/8", "::1/128")
		config["route"] = map[string]interface{}{"rules": []interface{}{map[string]interface{}{
			"source_ip_cidr": allowed, "invert": true, "action": "reject",
		}}}
	}
	if state.ControllerPort > 0 {
		config["experimental"] = map[string]interface{}{
			"clash_api": map[string]interface{}{"external_controller": fmt.Sprintf("127.0.0.1:%d", state.ControllerPort)},
		}
	}
	return config
}

func (m *privateProxyRuntimeManager) availableControllerPort(name string) (int, error) {
	used := make(map[int]bool)
	entries, _ := os.ReadDir(m.directory)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".state.json") {
			continue
		}
		stateName := strings.TrimSuffix(entry.Name(), ".state.json")
		if stateName == name {
			continue
		}
		if state, err := m.readState(stateName); err == nil && state.ControllerPort > 0 {
			used[state.ControllerPort] = true
		}
	}
	hash := sha256.Sum256([]byte(name))
	start := 20000 + (int(hash[0]) << 4) + int(hash[1]&0x0f)
	for offset := 0; offset < 10000; offset++ {
		port := 20000 + (start-20000+offset)%10000
		if used[port] {
			continue
		}
		listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if err != nil {
			continue
		}
		_ = listener.Close()
		return port, nil
	}
	return 0, errors.New("no local port is available for private proxy traffic accounting")
}

func maxInt64(value int64) int64 {
	if value < 0 {
		return 0
	}
	return value
}

func parseWireGuardTraffic(status string) (privateProxyRuntimeTraffic, error) {
	var traffic privateProxyRuntimeTraffic
	found := false
	for _, line := range strings.Split(status, "\n") {
		parts := strings.SplitN(strings.TrimSpace(line), "=", 2)
		if len(parts) != 2 || (parts[0] != "rx_bytes" && parts[0] != "tx_bytes") {
			continue
		}
		value, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || value < 0 {
			return privateProxyRuntimeTraffic{}, errors.New("invalid WireGuard traffic counters")
		}
		found = true
		if parts[0] == "rx_bytes" {
			traffic.InFlow += value
		} else {
			traffic.OutFlow += value
		}
	}
	if !found {
		return privateProxyRuntimeTraffic{}, errors.New("WireGuard traffic counters are unavailable")
	}
	return traffic, nil
}

func (m *privateProxyRuntimeManager) writeCertificate(state privateProxyRuntimeState) error {
	if _, err := os.Stat(m.certPath(state.Name)); err == nil {
		if _, keyErr := os.Stat(m.keyPath(state.Name)); keyErr == nil {
			return nil
		}
	}
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return err
	}
	template := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: state.ServerName},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().AddDate(5, 0, 0),
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     []string{state.ServerName},
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, &template, &template, &privateKey.PublicKey, privateKey)
	if err != nil {
		return err
	}
	certificate := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificateDER})
	keyDER, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		return err
	}
	key := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	if err := writeRuntimeFile(m.certPath(state.Name), certificate, 0600); err != nil {
		return err
	}
	return writeRuntimeFile(m.keyPath(state.Name), key, 0600)
}

func (m *privateProxyRuntimeManager) readState(name string) (privateProxyRuntimeState, error) {
	data, err := os.ReadFile(m.statePath(name))
	if err != nil {
		return privateProxyRuntimeState{}, err
	}
	var state privateProxyRuntimeState
	if err := json.Unmarshal(data, &state); err != nil {
		return privateProxyRuntimeState{}, err
	}
	if !supportedAdvancedProxyType(state.ProxyType) {
		return privateProxyRuntimeState{}, errors.New("invalid private proxy runtime state")
	}
	return state, nil
}

func (m *privateProxyRuntimeManager) removeFiles(name string) error {
	var firstErr error
	for _, path := range []string{m.statePath(name), m.configPath(name), m.logPath(name), m.certPath(name), m.keyPath(name)} {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (m *privateProxyRuntimeManager) response(s privateProxyRuntimeState) privateProxyRuntimeResponse {
	response := privateProxyRuntimeResponse{Version: s.Version, ClientID: s.ClientID, ClientPrivateKey: s.ClientPrivateKey,
		ClientAddress: s.ClientAddress, ServerPublicKey: s.ServerPublicKey, ServerAddress: s.ServerAddress, ServerName: s.ServerName,
		Transport: s.Transport, Path: s.Path, Method: s.Method}
	if s.usesTLS() {
		response.CertSHA256, response.PublicKeySHA256 = certificatePins(m.certPath(s.Name))
	}
	return response
}

// matches reports whether a request describes this runtime, ignoring the
// settings that can change in place (outbound route and allowed sources).
func (s privateProxyRuntimeState) matches(r privateProxyRuntimeRequest) bool {
	if s.ProxyType != r.ProxyType || s.BindIP != r.BindIP || s.ListenPort != r.ListenPort || s.Password != r.Password {
		return false
	}
	if r.ClientID != "" && s.ClientID != r.ClientID {
		return false
	}
	return s.Transport == r.Transport && s.TLS == r.TLS && s.Path == r.Path && s.Method == r.Method &&
		s.ObfsPassword == r.ObfsPassword && s.HandshakeServer == r.HandshakeServer && s.InnerPassword == r.InnerPassword
}

func (s privateProxyRuntimeState) usesTLS() bool {
	switch s.ProxyType {
	case "trojan", "hysteria2", "tuic", "anytls":
		return true
	case "vmess", "vless":
		return s.TLS
	}
	return false
}

// certificatePins returns the certificate's SHA-256 in hex and its public
// key's SHA-256 in base64, the two forms clients use for pinning.
func certificatePins(path string) (string, string) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", ""
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return "", ""
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return "", ""
	}
	certSum := sha256.Sum256(certificate.Raw)
	keySum := sha256.Sum256(certificate.RawSubjectPublicKeyInfo)
	return hex.EncodeToString(certSum[:]), base64.StdEncoding.EncodeToString(keySum[:])
}

var grpcServiceNamePattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// normalizeRuntimeOptions checks the protocol settings and fills defaults.
func normalizeRuntimeOptions(r *privateProxyRuntimeRequest) error {
	r.ClientID = strings.ToLower(strings.TrimSpace(r.ClientID))
	r.Transport = strings.ToLower(strings.TrimSpace(r.Transport))
	r.Path = strings.TrimSpace(r.Path)
	r.Method = strings.TrimSpace(r.Method)
	r.ObfsPassword = strings.TrimSpace(r.ObfsPassword)
	r.HandshakeServer = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(r.HandshakeServer), "."))
	r.InnerPassword = strings.TrimSpace(r.InnerPassword)
	if r.ClientID != "" && !uuidPattern.MatchString(r.ClientID) {
		return errors.New("invalid private proxy client id")
	}
	switch r.ProxyType {
	case "vmess", "vless":
		if r.Transport == "" {
			r.Transport = "tcp"
		}
		switch r.Transport {
		case "tcp":
			r.Path = ""
		case "ws", "httpupgrade":
			if r.Path == "" {
				r.Path = "/" + randomRuntimeToken(8)
			}
			if !strings.HasPrefix(r.Path, "/") || len(r.Path) > 128 || strings.ContainsAny(r.Path, " \t\r\n?#") {
				return errors.New("invalid private proxy transport path")
			}
		case "grpc":
			if r.Path == "" {
				r.Path = randomRuntimeToken(8)
			}
			if !grpcServiceNamePattern.MatchString(r.Path) {
				return errors.New("invalid private proxy gRPC service name")
			}
		default:
			return errors.New("unsupported private proxy transport")
		}
	default:
		r.Transport, r.Path, r.TLS = "", "", false
		if len(r.Password) < 8 {
			return errors.New("invalid private proxy runtime settings")
		}
	}
	switch r.ProxyType {
	case "shadowsocks2022", "shadowtls":
		if r.Method == "" {
			r.Method = "2022-blake3-aes-128-gcm"
		}
		key := r.Password
		if r.ProxyType == "shadowtls" {
			key = r.InnerPassword
			if r.HandshakeServer == "" || !serverNamePattern.MatchString(r.HandshakeServer) {
				return errors.New("invalid ShadowTLS handshake server")
			}
		}
		if err := checkShadowsocks2022Key(r.Method, key); err != nil {
			return err
		}
	default:
		r.Method, r.InnerPassword, r.HandshakeServer = "", "", ""
	}
	if r.ProxyType != "hysteria2" {
		r.ObfsPassword = ""
	} else if r.ObfsPassword != "" && len(r.ObfsPassword) < 8 {
		return errors.New("Hysteria2 obfuscation password must have at least 8 characters")
	}
	cidrs := make([]string, 0, len(r.AllowedCIDRs))
	for _, value := range r.AllowedCIDRs {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		_, network, err := net.ParseCIDR(value)
		if err != nil {
			return fmt.Errorf("invalid allowed source %q", value)
		}
		cidrs = append(cidrs, network.String())
	}
	if len(cidrs) > 0 && r.ProxyType == "wireguard" {
		return errors.New("WireGuard does not support allowed sources")
	}
	r.AllowedCIDRs = cidrs
	return nil
}

func checkShadowsocks2022Key(method, key string) error {
	size := 0
	switch method {
	case "2022-blake3-aes-128-gcm":
		size = 16
	case "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305":
		size = 32
	default:
		return errors.New("unsupported Shadowsocks 2022 method")
	}
	decoded, err := base64.StdEncoding.DecodeString(key)
	if err != nil || len(decoded) != size {
		return fmt.Errorf("Shadowsocks 2022 key must be %d bytes in base64", size)
	}
	return nil
}

func runtimeTransport(transport, path string) map[string]interface{} {
	switch transport {
	case "ws", "httpupgrade":
		return map[string]interface{}{"type": transport, "path": path}
	case "grpc":
		return map[string]interface{}{"type": "grpc", "service_name": path}
	}
	return nil
}

func udpRuntimeType(proxyType string) bool {
	return proxyType == "hysteria2" || proxyType == "tuic" || proxyType == "wireguard"
}

func sameStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func randomRuntimeToken(size int) string {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return "cloudnest"
	}
	return hex.EncodeToString(value)
}

// waitForListener waits until the runtime accepts TCP connections on its
// listen address; a specific bind address is not reachable on loopback.
func waitForListener(bindIP string, port int, timeout time.Duration) error {
	host := "127.0.0.1"
	if ip := net.ParseIP(bindIP); ip != nil && !ip.IsUnspecified() {
		host = ip.String()
	}
	address := net.JoinHostPort(host, strconv.Itoa(port))
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", address, 200*time.Millisecond)
		if err == nil {
			conn.Close()
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return errors.New("local listener timeout")
}

func (m *privateProxyRuntimeManager) statePath(name string) string {
	return filepath.Join(m.directory, name+".state.json")
}
func (m *privateProxyRuntimeManager) configPath(name string) string {
	return filepath.Join(m.directory, name+".json")
}
func (m *privateProxyRuntimeManager) logPath(name string) string {
	return filepath.Join(m.directory, name+".log")
}
func (m *privateProxyRuntimeManager) certPath(name string) string {
	return filepath.Join(m.directory, name+".crt.pem")
}
func (m *privateProxyRuntimeManager) keyPath(name string) string {
	return filepath.Join(m.directory, name+".key.pem")
}

func supportedAdvancedProxyType(value string) bool {
	switch value {
	case "trojan", "hysteria2", "tuic", "wireguard", "anytls", "shadowsocks2022", "vmess", "vless", "shadowtls":
		return true
	}
	return false
}

func singBoxAssetName() (string, error) {
	if runtime.GOOS != "linux" {
		return "", fmt.Errorf("sing-box runtime is not supported on %s", runtime.GOOS)
	}
	// The musl builds are statically linked and run on any Linux; the default
	// builds need glibc.
	switch runtime.GOARCH {
	case "amd64", "arm64":
		return fmt.Sprintf("sing-box-%s-linux-%s-musl.tar.gz", singBoxRuntimeVersion, runtime.GOARCH), nil
	case "arm":
		return fmt.Sprintf("sing-box-%s-linux-armv7-musl.tar.gz", singBoxRuntimeVersion), nil
	}
	return "", fmt.Errorf("sing-box runtime is not supported on linux/%s", runtime.GOARCH)
}

func extractSingBox(archivePath, target string) error {
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gzipReader.Close()
	archive := tar.NewReader(gzipReader)
	for {
		header, err := archive.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		if filepath.Base(header.Name) != "sing-box" || !header.FileInfo().Mode().IsRegular() {
			continue
		}
		output, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0700)
		if err != nil {
			return err
		}
		written, copyErr := io.Copy(output, io.LimitReader(archive, 128*1024*1024))
		closeErr := output.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if written == 0 {
			return errors.New("sing-box executable is empty")
		}
		return nil
	}
	return errors.New("sing-box executable is missing from archive")
}

func randomRuntimeUUID() (string, error) {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	return formatUUID(value), nil
}

func newWireGuardKeypair() (string, string, error) {
	privateBytes := make([]byte, 32)
	if _, err := rand.Read(privateBytes); err != nil {
		return "", "", err
	}
	privateKey, err := ecdh.X25519().NewPrivateKey(privateBytes)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(privateKey.Bytes()), base64.StdEncoding.EncodeToString(privateKey.PublicKey().Bytes()), nil
}
