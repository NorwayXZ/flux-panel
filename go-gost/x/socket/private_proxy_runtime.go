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
}

type privateProxyRuntimeResponse struct {
	Version          string `json:"version"`
	ClientID         string `json:"clientId,omitempty"`
	ClientPrivateKey string `json:"clientPrivateKey,omitempty"`
	ClientAddress    string `json:"clientAddress,omitempty"`
	ServerPublicKey  string `json:"serverPublicKey,omitempty"`
	ServerAddress    string `json:"serverAddress,omitempty"`
	ServerName       string `json:"serverName,omitempty"`
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
}

type privateProxyRuntimeManager struct {
	mu         sync.Mutex
	directory  string
	binary     string
	processes  map[string]*exec.Cmd
	stopping   map[string]bool
	wireguards map[string]*wireGuardRuntime
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
	if request.ListenPort < 1 || request.ListenPort > 65535 || len(request.Password) < 8 {
		return privateProxyRuntimeResponse{}, errors.New("invalid private proxy runtime settings")
	}
	if request.BindIP != "" && net.ParseIP(request.BindIP) == nil {
		return privateProxyRuntimeResponse{}, errors.New("invalid private proxy bind IP")
	}
	if request.ServerName == "" {
		request.ServerName = "cloudnest.local"
	}
	if request.ProxyType == "wireguard" {
		return m.addWireGuard(request)
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return privateProxyRuntimeResponse{}, fmt.Errorf("create private proxy runtime directory: %w", err)
	}
	if existing, err := m.readState(request.Name); err == nil {
		if existing.ProxyType != request.ProxyType || existing.BindIP != request.BindIP || existing.ListenPort != request.ListenPort || existing.Password != request.Password {
			return privateProxyRuntimeResponse{}, errors.New("private proxy runtime already exists with different settings")
		}
		if err := m.ensureRunning(existing); err != nil {
			return privateProxyRuntimeResponse{}, err
		}
		return existing.response(), nil
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
	if err := m.writeStateAndConfig(state); err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if err := m.ensureRunning(state); err != nil {
		m.removeFiles(state.Name)
		return privateProxyRuntimeResponse{}, err
	}
	return state.response(), nil
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
	if state.ProxyType == "trojan" {
		if err := waitForTCP(state.ListenPort, 4*time.Second); err != nil {
			_ = cmd.Process.Kill()
			return fmt.Errorf("Trojan runtime did not become ready: %w", err)
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
	if info, err := os.Stat(m.binary); err == nil && info.Mode().IsRegular() {
		return nil
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
	return os.Rename(temporary, m.binary)
}

func newPrivateProxyRuntimeState(request privateProxyRuntimeRequest) (privateProxyRuntimeState, error) {
	state := privateProxyRuntimeState{
		Name: request.Name, ProxyType: request.ProxyType, BindIP: request.BindIP, ListenPort: request.ListenPort,
		Password: request.Password, ServerName: request.ServerName, Version: singBoxRuntimeVersion,
	}
	if request.ProxyType == "tuic" {
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
	tls := map[string]interface{}{"enabled": true, "certificate_path": m.certPath(state.Name), "key_path": m.keyPath(state.Name)}
	inbound := map[string]interface{}{"type": state.ProxyType, "tag": state.Name, "listen": listen, "listen_port": state.ListenPort, "tls": tls}
	switch state.ProxyType {
	case "trojan":
		inbound["users"] = []interface{}{map[string]interface{}{"password": state.Password}}
	case "hysteria2":
		inbound["users"] = []interface{}{map[string]interface{}{"password": state.Password}}
		inbound["masquerade"] = "https://www.cloudflare.com/"
	case "tuic":
		inbound["users"] = []interface{}{map[string]interface{}{"uuid": state.ClientID, "password": state.Password}}
		inbound["congestion_control"] = "bbr"
	}
	config := map[string]interface{}{
		"log":       map[string]interface{}{"level": "warn"},
		"inbounds":  []interface{}{inbound},
		"outbounds": []interface{}{map[string]interface{}{"type": "direct", "tag": "direct"}},
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

func (s privateProxyRuntimeState) response() privateProxyRuntimeResponse {
	return privateProxyRuntimeResponse{Version: s.Version, ClientID: s.ClientID, ClientPrivateKey: s.ClientPrivateKey,
		ClientAddress: s.ClientAddress, ServerPublicKey: s.ServerPublicKey, ServerAddress: s.ServerAddress, ServerName: s.ServerName}
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
	return value == "trojan" || value == "hysteria2" || value == "tuic" || value == "wireguard"
}

func singBoxAssetName() (string, error) {
	if runtime.GOOS != "linux" {
		return "", fmt.Errorf("sing-box runtime is not supported on %s", runtime.GOOS)
	}
	switch runtime.GOARCH {
	case "amd64", "arm64":
		return fmt.Sprintf("sing-box-%s-linux-%s.tar.gz", singBoxRuntimeVersion, runtime.GOARCH), nil
	case "arm":
		return fmt.Sprintf("sing-box-%s-linux-armv7.tar.gz", singBoxRuntimeVersion), nil
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
