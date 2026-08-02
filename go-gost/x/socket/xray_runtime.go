package socket

import (
	"archive/zip"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

const xrayRuntimeVersion = "v26.3.27"

var runtimeNamePattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,120}$`)
var serverNamePattern = regexp.MustCompile(`(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$`)

type realityRuntimeRequest struct {
	Name                  string `json:"name"`
	ServerName            string `json:"serverName"`
	OutboundProxyHost     string `json:"outboundProxyHost,omitempty"`
	OutboundProxyPort     int    `json:"outboundProxyPort,omitempty"`
	OutboundProxyUsername string `json:"outboundProxyUsername,omitempty"`
	OutboundProxyPassword string `json:"outboundProxyPassword,omitempty"`
}

type realityClientRuntimeRequest struct {
	Name       string `json:"name"`
	RemoteHost string `json:"remoteHost"`
	RemotePort int    `json:"remotePort"`
	ClientID   string `json:"clientId"`
	PublicKey  string `json:"publicKey"`
	ShortID    string `json:"shortId"`
	ServerName string `json:"serverName"`
}

type realityRuntimeResponse struct {
	Port       int    `json:"port"`
	ClientID   string `json:"clientId"`
	PublicKey  string `json:"publicKey"`
	ShortID    string `json:"shortId"`
	ServerName string `json:"serverName"`
	Version    string `json:"version"`
}

type realityRuntimeState struct {
	Mode                  string `json:"mode,omitempty"`
	Name                  string `json:"name"`
	Port                  int    `json:"port"`
	ClientID              string `json:"clientId"`
	PrivateKey            string `json:"privateKey"`
	PublicKey             string `json:"publicKey"`
	ShortID               string `json:"shortId"`
	ServerName            string `json:"serverName"`
	RemoteHost            string `json:"remoteHost,omitempty"`
	RemotePort            int    `json:"remotePort,omitempty"`
	OutboundProxyHost     string `json:"outboundProxyHost,omitempty"`
	OutboundProxyPort     int    `json:"outboundProxyPort,omitempty"`
	OutboundProxyUsername string `json:"outboundProxyUsername,omitempty"`
	OutboundProxyPassword string `json:"outboundProxyPassword,omitempty"`
	Version               string `json:"version"`
}

type xrayConfig struct {
	Log       map[string]interface{} `json:"log"`
	Inbounds  []interface{}          `json:"inbounds"`
	Outbounds []interface{}          `json:"outbounds"`
}

type realityRuntimeManager struct {
	mu        sync.Mutex
	directory string
	binary    string
	processes map[string]*exec.Cmd
	stopping  map[string]bool
}

func newRealityRuntimeManager() *realityRuntimeManager {
	executable, err := os.Executable()
	base := "."
	if err == nil {
		base = filepath.Dir(executable)
	}
	directory := filepath.Join(base, "xray-runtime")
	binaryName := "xray-" + xrayRuntimeVersion
	if runtime.GOOS == "windows" {
		binaryName += ".exe"
	}
	return &realityRuntimeManager{
		directory: directory,
		binary:    filepath.Join(directory, binaryName),
		processes: make(map[string]*exec.Cmd),
		stopping:  make(map[string]bool),
	}
}

func (m *realityRuntimeManager) add(request realityRuntimeRequest) (realityRuntimeResponse, error) {
	request.Name = strings.TrimSpace(request.Name)
	request.ServerName = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(request.ServerName), "."))
	request.OutboundProxyHost = strings.TrimSpace(strings.Trim(request.OutboundProxyHost, "[]"))
	if !runtimeNamePattern.MatchString(request.Name) {
		return realityRuntimeResponse{}, errors.New("invalid REALITY runtime name")
	}
	if !serverNamePattern.MatchString(request.ServerName) {
		return realityRuntimeResponse{}, errors.New("invalid REALITY server name")
	}
	if request.OutboundProxyPort > 0 {
		if request.OutboundProxyHost == "" {
			request.OutboundProxyHost = "127.0.0.1"
		}
		proxyIP := net.ParseIP(request.OutboundProxyHost)
		if proxyIP == nil || !proxyIP.IsLoopback() || request.OutboundProxyPort > 65535 ||
			request.OutboundProxyUsername == "" || request.OutboundProxyPassword == "" {
			return realityRuntimeResponse{}, errors.New("invalid REALITY outbound proxy")
		}
	} else {
		request.OutboundProxyHost = ""
		request.OutboundProxyUsername = ""
		request.OutboundProxyPassword = ""
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return realityRuntimeResponse{}, fmt.Errorf("create REALITY runtime directory: %w", err)
	}
	if state, err := m.readState(request.Name); err == nil {
		if state.Mode != "server" || state.ServerName != request.ServerName ||
			state.OutboundProxyHost != request.OutboundProxyHost || state.OutboundProxyPort != request.OutboundProxyPort ||
			state.OutboundProxyUsername != request.OutboundProxyUsername || state.OutboundProxyPassword != request.OutboundProxyPassword {
			return realityRuntimeResponse{}, errors.New("REALITY runtime already exists with different settings")
		}
		if err := m.ensureRunning(state); err != nil {
			return realityRuntimeResponse{}, err
		}
		return state.response(), nil
	}
	if err := m.ensureBinary(); err != nil {
		return realityRuntimeResponse{}, err
	}
	state, err := newRealityState(request)
	if err != nil {
		return realityRuntimeResponse{}, err
	}
	if err := m.writeStateAndConfig(state); err != nil {
		return realityRuntimeResponse{}, err
	}
	if err := m.ensureRunning(state); err != nil {
		_ = os.Remove(m.statePath(state.Name))
		_ = os.Remove(m.configPath(state.Name))
		return realityRuntimeResponse{}, err
	}
	return state.response(), nil
}

func (m *realityRuntimeManager) addClient(request realityClientRuntimeRequest) (realityRuntimeResponse, error) {
	request.Name = strings.TrimSpace(request.Name)
	request.RemoteHost = strings.TrimSpace(strings.Trim(request.RemoteHost, "[]"))
	request.ServerName = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(request.ServerName), "."))
	request.ClientID = strings.TrimSpace(request.ClientID)
	request.PublicKey = strings.TrimSpace(request.PublicKey)
	request.ShortID = strings.ToLower(strings.TrimSpace(request.ShortID))
	if !runtimeNamePattern.MatchString(request.Name) {
		return realityRuntimeResponse{}, errors.New("invalid REALITY runtime name")
	}
	if request.RemoteHost == "" || request.RemotePort < 1 || request.RemotePort > 65535 {
		return realityRuntimeResponse{}, errors.New("invalid REALITY remote endpoint")
	}
	if !serverNamePattern.MatchString(request.ServerName) {
		return realityRuntimeResponse{}, errors.New("invalid REALITY server name")
	}
	if request.ClientID == "" || request.PublicKey == "" || !regexp.MustCompile(`^[0-9a-f]{2,32}$`).MatchString(request.ShortID) {
		return realityRuntimeResponse{}, errors.New("invalid REALITY client credentials")
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return realityRuntimeResponse{}, fmt.Errorf("create REALITY runtime directory: %w", err)
	}
	if state, err := m.readState(request.Name); err == nil {
		if state.Mode != "client" || state.RemoteHost != request.RemoteHost || state.RemotePort != request.RemotePort ||
			state.ClientID != request.ClientID || state.PublicKey != request.PublicKey || state.ShortID != request.ShortID ||
			state.ServerName != request.ServerName {
			return realityRuntimeResponse{}, errors.New("REALITY runtime already exists with different settings")
		}
		if err := m.ensureRunning(state); err != nil {
			return realityRuntimeResponse{}, err
		}
		return state.response(), nil
	}
	if err := m.ensureBinary(); err != nil {
		return realityRuntimeResponse{}, err
	}
	port, err := availableLocalPort()
	if err != nil {
		return realityRuntimeResponse{}, err
	}
	state := realityRuntimeState{
		Mode: "client", Name: request.Name, Port: port, ClientID: request.ClientID,
		PublicKey: request.PublicKey, ShortID: request.ShortID, ServerName: request.ServerName,
		RemoteHost: request.RemoteHost, RemotePort: request.RemotePort, Version: xrayRuntimeVersion,
	}
	if err := m.writeStateAndConfig(state); err != nil {
		return realityRuntimeResponse{}, err
	}
	if err := m.ensureRunning(state); err != nil {
		_ = os.Remove(m.statePath(state.Name))
		_ = os.Remove(m.configPath(state.Name))
		return realityRuntimeResponse{}, err
	}
	return state.response(), nil
}

func (m *realityRuntimeManager) delete(name string) error {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return errors.New("invalid REALITY runtime name")
	}
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
	for _, path := range []string{m.statePath(name), m.configPath(name), m.logPath(name)} {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			return fmt.Errorf("remove REALITY runtime file: %w", err)
		}
	}
	return nil
}

func (m *realityRuntimeManager) restore() {
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
			fmt.Printf("REALITY restore %s failed: %v\n", name, err)
			continue
		}
		if err := m.ensureRunning(state); err != nil {
			fmt.Printf("REALITY restore %s failed: %v\n", name, err)
		}
	}
}

func (m *realityRuntimeManager) stopAll() {
	m.mu.Lock()
	commands := make(map[string]*exec.Cmd, len(m.processes))
	for name, cmd := range m.processes {
		m.stopping[name] = true
		commands[name] = cmd
	}
	m.mu.Unlock()
	for _, cmd := range commands {
		if cmd != nil && cmd.Process != nil {
			_ = cmd.Process.Signal(os.Interrupt)
			waitForProcess(cmd, 2*time.Second)
			if processAlive(cmd) {
				_ = cmd.Process.Kill()
			}
		}
	}
}

func (m *realityRuntimeManager) ensureRunning(state realityRuntimeState) error {
	m.mu.Lock()
	if cmd := m.processes[state.Name]; cmd != nil && processAlive(cmd) {
		m.mu.Unlock()
		return nil
	}
	m.mu.Unlock()
	if err := m.ensureBinary(); err != nil {
		return err
	}
	logFile, err := os.OpenFile(m.logPath(state.Name), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return fmt.Errorf("open REALITY log: %w", err)
	}
	cmd := exec.Command(m.binary, "run", "-config", m.configPath(state.Name))
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		logFile.Close()
		return fmt.Errorf("start REALITY runtime: %w", err)
	}
	logFile.Close()
	m.mu.Lock()
	m.processes[state.Name] = cmd
	delete(m.stopping, state.Name)
	m.mu.Unlock()
	go m.watch(state.Name, cmd)
	if err := waitForTCP(state.Port, 4*time.Second); err != nil {
		_ = cmd.Process.Kill()
		return fmt.Errorf("REALITY runtime did not become ready: %w", err)
	}
	return nil
}

func (m *realityRuntimeManager) watch(name string, cmd *exec.Cmd) {
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
	state, err := m.readState(name)
	if err == nil {
		_ = m.ensureRunning(state)
	}
}

func (m *realityRuntimeManager) ensureBinary() error {
	if info, err := os.Stat(m.binary); err == nil && info.Mode().IsRegular() {
		return nil
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return err
	}
	asset, err := xrayAssetName()
	if err != nil {
		return err
	}
	baseURL := "https://github.com/XTLS/Xray-core/releases/download/" + xrayRuntimeVersion + "/" + asset
	client := &http.Client{Timeout: 2 * time.Minute}
	digest, err := downloadBytes(client, baseURL+".dgst", 64*1024)
	if err != nil {
		return fmt.Errorf("download Xray checksum: %w", err)
	}
	expected := parseSHA256(string(digest))
	if expected == "" {
		return errors.New("Xray checksum response is invalid")
	}
	archivePath := filepath.Join(m.directory, ".xray-download.zip")
	if err := downloadFile(client, baseURL, archivePath, 128*1024*1024); err != nil {
		return fmt.Errorf("download Xray runtime: %w", err)
	}
	defer os.Remove(archivePath)
	actual, err := xrayFileSHA256(archivePath)
	if err != nil || !strings.EqualFold(actual, expected) {
		return errors.New("Xray runtime SHA-256 verification failed")
	}
	temporary := m.binary + ".tmp"
	if err := extractXray(archivePath, temporary); err != nil {
		return err
	}
	if err := os.Chmod(temporary, 0755); err != nil {
		return err
	}
	return os.Rename(temporary, m.binary)
}

func newRealityState(request realityRuntimeRequest) (realityRuntimeState, error) {
	port, err := availableLocalPort()
	if err != nil {
		return realityRuntimeState{}, err
	}
	privateKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return realityRuntimeState{}, err
	}
	uuidBytes := make([]byte, 16)
	shortBytes := make([]byte, 8)
	if _, err := rand.Read(uuidBytes); err != nil {
		return realityRuntimeState{}, err
	}
	if _, err := rand.Read(shortBytes); err != nil {
		return realityRuntimeState{}, err
	}
	uuidBytes[6] = (uuidBytes[6] & 0x0f) | 0x40
	uuidBytes[8] = (uuidBytes[8] & 0x3f) | 0x80
	return realityRuntimeState{
		Mode: "server", Name: request.Name, Port: port, ClientID: formatUUID(uuidBytes),
		PrivateKey: base64.RawURLEncoding.EncodeToString(privateKey.Bytes()),
		PublicKey:  base64.RawURLEncoding.EncodeToString(privateKey.PublicKey().Bytes()),
		ShortID:    hex.EncodeToString(shortBytes), ServerName: request.ServerName,
		OutboundProxyHost: request.OutboundProxyHost, OutboundProxyPort: request.OutboundProxyPort,
		OutboundProxyUsername: request.OutboundProxyUsername, OutboundProxyPassword: request.OutboundProxyPassword,
		Version: xrayRuntimeVersion,
	}, nil
}

func (m *realityRuntimeManager) writeStateAndConfig(state realityRuntimeState) error {
	if state.Mode == "" {
		state.Mode = "server"
	}
	stateBytes, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	if err := writeRuntimeFile(m.statePath(state.Name), stateBytes, 0600); err != nil {
		return err
	}
	config := m.runtimeConfig(state)
	configBytes, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}
	return writeRuntimeFile(m.configPath(state.Name), configBytes, 0600)
}

func (m *realityRuntimeManager) runtimeConfig(state realityRuntimeState) xrayConfig {
	if state.Mode == "client" {
		return xrayConfig{
			Log: map[string]interface{}{"loglevel": "warning"},
			Inbounds: []interface{}{map[string]interface{}{
				"listen": "127.0.0.1", "port": state.Port, "protocol": "socks",
				"settings": map[string]interface{}{"auth": "noauth", "udp": true},
			}},
			Outbounds: []interface{}{map[string]interface{}{
				"protocol": "vless",
				"settings": map[string]interface{}{"vnext": []interface{}{map[string]interface{}{
					"address": state.RemoteHost, "port": state.RemotePort,
					"users": []interface{}{map[string]interface{}{
						"id": state.ClientID, "encryption": "none", "flow": "xtls-rprx-vision",
					}},
				}}},
				"streamSettings": map[string]interface{}{
					"network": "raw", "security": "reality",
					"realitySettings": map[string]interface{}{
						"fingerprint": "chrome", "serverName": state.ServerName,
						"publicKey": state.PublicKey, "shortId": state.ShortID, "spiderX": "/",
					},
				},
			}},
		}
	}
	outbound := map[string]interface{}{"protocol": "freedom", "tag": "direct"}
	if state.OutboundProxyPort > 0 {
		outbound = map[string]interface{}{
			"protocol": "socks", "tag": "routed",
			"settings": map[string]interface{}{"servers": []interface{}{map[string]interface{}{
				"address": state.OutboundProxyHost, "port": state.OutboundProxyPort,
				"users": []interface{}{map[string]interface{}{
					"user": state.OutboundProxyUsername, "pass": state.OutboundProxyPassword,
				}},
			}}},
		}
	}
	return xrayConfig{
		Log: map[string]interface{}{"loglevel": "warning"},
		Inbounds: []interface{}{map[string]interface{}{
			"listen": "127.0.0.1", "port": state.Port, "protocol": "vless",
			"settings": map[string]interface{}{
				"clients":    []interface{}{map[string]interface{}{"id": state.ClientID, "flow": "xtls-rprx-vision", "email": state.Name}},
				"decryption": "none",
			},
			"streamSettings": map[string]interface{}{
				"network": "raw", "security": "reality",
				"realitySettings": map[string]interface{}{
					"show": false, "target": state.ServerName + ":443", "xver": 0,
					"serverNames": []string{state.ServerName}, "privateKey": state.PrivateKey, "shortIds": []string{state.ShortID},
				},
			},
		}},
		Outbounds: []interface{}{outbound},
	}
}

func (m *realityRuntimeManager) readState(name string) (realityRuntimeState, error) {
	data, err := os.ReadFile(m.statePath(name))
	if err != nil {
		return realityRuntimeState{}, err
	}
	var state realityRuntimeState
	if err := json.Unmarshal(data, &state); err != nil {
		return state, err
	}
	if state.Mode == "" {
		state.Mode = "server"
	}
	return state, nil
}

func (s realityRuntimeState) response() realityRuntimeResponse {
	return realityRuntimeResponse{Port: s.Port, ClientID: s.ClientID, PublicKey: s.PublicKey,
		ShortID: s.ShortID, ServerName: s.ServerName, Version: s.Version}
}

func (m *realityRuntimeManager) statePath(name string) string {
	return filepath.Join(m.directory, name+".state.json")
}
func (m *realityRuntimeManager) configPath(name string) string {
	return filepath.Join(m.directory, name+".json")
}
func (m *realityRuntimeManager) logPath(name string) string {
	return filepath.Join(m.directory, name+".log")
}

func xrayAssetName() (string, error) {
	return xrayAssetNameFor(runtime.GOOS, runtime.GOARCH)
}

func xrayAssetNameFor(platform, arch string) (string, error) {
	if platform == "linux" {
		switch arch {
		case "amd64":
			return "Xray-linux-64.zip", nil
		case "arm64":
			return "Xray-linux-arm64-v8a.zip", nil
		case "386":
			return "Xray-linux-32.zip", nil
		case "arm":
			return "Xray-linux-arm32-v7a.zip", nil
		}
	}
	if platform == "darwin" {
		if arch == "amd64" {
			return "Xray-macos-64.zip", nil
		}
		if arch == "arm64" {
			return "Xray-macos-arm64-v8a.zip", nil
		}
	}
	if platform == "windows" {
		switch arch {
		case "amd64":
			return "Xray-windows-64.zip", nil
		case "arm64":
			return "Xray-windows-arm64-v8a.zip", nil
		case "386":
			return "Xray-windows-32.zip", nil
		}
	}
	return "", fmt.Errorf("VLESS+REALITY is not supported on %s/%s", platform, arch)
}

func availableLocalPort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

func waitForTCP(port int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 200*time.Millisecond)
		if err == nil {
			conn.Close()
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return errors.New("local listener timeout")
}

func processAlive(cmd *exec.Cmd) bool {
	return cmd != nil && cmd.Process != nil && cmd.ProcessState == nil
}

func waitForProcess(cmd *exec.Cmd, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for processAlive(cmd) && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}
}

func formatUUID(value []byte) string {
	hexValue := hex.EncodeToString(value)
	return fmt.Sprintf("%s-%s-%s-%s-%s", hexValue[0:8], hexValue[8:12], hexValue[12:16], hexValue[16:20], hexValue[20:32])
}

func parseSHA256(value string) string {
	for _, line := range strings.Split(value, "\n") {
		if strings.HasPrefix(line, "SHA2-256=") {
			return strings.TrimSpace(strings.TrimPrefix(line, "SHA2-256="))
		}
	}
	return ""
}

func downloadBytes(client *http.Client, url string, limit int64) ([]byte, error) {
	response, err := client.Get(url)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", response.StatusCode)
	}
	return io.ReadAll(io.LimitReader(response.Body, limit))
}

func downloadFile(client *http.Client, url, path string, limit int64) error {
	response, err := client.Get(url)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", response.StatusCode)
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	written, copyErr := io.Copy(file, io.LimitReader(response.Body, limit+1))
	closeErr := file.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if written > limit {
		return errors.New("Xray archive exceeds size limit")
	}
	return nil
}

func xrayFileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func extractXray(archivePath, target string) error {
	archive, err := zip.OpenReader(archivePath)
	if err != nil {
		return err
	}
	defer archive.Close()
	for _, item := range archive.File {
		name := strings.ToLower(filepath.Base(item.Name))
		if name != "xray" && name != "xray.exe" {
			continue
		}
		reader, err := item.Open()
		if err != nil {
			return err
		}
		file, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0700)
		if err != nil {
			reader.Close()
			return err
		}
		_, copyErr := io.Copy(file, io.LimitReader(reader, 128*1024*1024))
		reader.Close()
		closeErr := file.Close()
		if copyErr != nil {
			return copyErr
		}
		return closeErr
	}
	return errors.New("Xray executable is missing from archive")
}

func writeRuntimeFile(path string, data []byte, mode os.FileMode) error {
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, data, mode); err != nil {
		return err
	}
	if err := os.Chmod(temporary, mode); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}
