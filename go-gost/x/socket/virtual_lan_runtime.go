package socket

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

type virtualLanPeer struct {
	PublicKey           string   `json:"publicKey"`
	AllowedIPs          []string `json:"allowedIps"`
	Endpoint            string   `json:"endpoint,omitempty"`
	PersistentKeepalive int      `json:"persistentKeepalive,omitempty"`
}

type virtualLanApplyRequest struct {
	Name             string           `json:"name"`
	InterfaceAddress string           `json:"interfaceAddress"`
	ListenPort       int              `json:"listenPort"`
	Hub              bool             `json:"hub"`
	Peers            []virtualLanPeer `json:"peers"`
}

type virtualLanState struct {
	Name             string           `json:"name"`
	PrivateKey       string           `json:"privateKey"`
	PublicKey        string           `json:"publicKey"`
	InterfaceAddress string           `json:"interfaceAddress,omitempty"`
	ListenPort       int              `json:"listenPort,omitempty"`
	Hub              bool             `json:"hub"`
	Peers            []virtualLanPeer `json:"peers,omitempty"`
	Active           bool             `json:"active"`
	UpdatedAt        int64            `json:"updatedAt"`
}

type virtualLanKeyResponse struct {
	Name      string `json:"name"`
	PublicKey string `json:"publicKey"`
	Platform  string `json:"platform"`
}

type virtualLanStatusResponse struct {
	Name            string `json:"name"`
	Active          bool   `json:"active"`
	InterfaceName   string `json:"interfaceName,omitempty"`
	PublicKey       string `json:"publicKey"`
	ReceiveBytes    int64  `json:"receiveBytes"`
	TransmitBytes   int64  `json:"transmitBytes"`
	LatestHandshake int64  `json:"latestHandshake,omitempty"`
	PeerCount       int    `json:"peerCount"`
}

type virtualLanRuntimeManager struct {
	mu        sync.Mutex
	directory string
	runtimes  map[string]*virtualLanPlatformRuntime
}

func newVirtualLanRuntimeManager() *virtualLanRuntimeManager {
	executable, err := os.Executable()
	base := "."
	if err == nil {
		base = filepath.Dir(executable)
	}
	return &virtualLanRuntimeManager{directory: filepath.Join(base, "virtual-lan-runtime"), runtimes: make(map[string]*virtualLanPlatformRuntime)}
}

func (m *virtualLanRuntimeManager) prepareKey(name string) (virtualLanKeyResponse, error) {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return virtualLanKeyResponse{}, errors.New("invalid virtual LAN name")
	}
	if runtime.GOOS != "linux" {
		return virtualLanKeyResponse{}, fmt.Errorf("virtual LAN is not supported on %s connectors", runtime.GOOS)
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return virtualLanKeyResponse{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	state, err := m.readState(name)
	if err == nil {
		return virtualLanKeyResponse{Name: name, PublicKey: state.PublicKey, Platform: runtime.GOOS}, nil
	}
	privateKey, publicKey, err := newVirtualLanKeypair()
	if err != nil {
		return virtualLanKeyResponse{}, err
	}
	state = virtualLanState{Name: name, PrivateKey: privateKey, PublicKey: publicKey, UpdatedAt: time.Now().UnixMilli()}
	if err := m.writeState(state); err != nil {
		return virtualLanKeyResponse{}, err
	}
	return virtualLanKeyResponse{Name: name, PublicKey: publicKey, Platform: runtime.GOOS}, nil
}

func (m *virtualLanRuntimeManager) apply(request virtualLanApplyRequest) (virtualLanStatusResponse, error) {
	if err := validateVirtualLanRequest(&request); err != nil {
		return virtualLanStatusResponse{}, err
	}
	if _, err := m.prepareKey(request.Name); err != nil {
		return virtualLanStatusResponse{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	previous, err := m.readState(request.Name)
	if err != nil {
		return virtualLanStatusResponse{}, err
	}
	next := previous
	next.InterfaceAddress = request.InterfaceAddress
	next.ListenPort = request.ListenPort
	next.Hub = request.Hub
	next.Peers = request.Peers
	next.Active = true
	next.UpdatedAt = time.Now().UnixMilli()
	m.stopLocked(request.Name)
	runtimeState, startErr := startVirtualLanPlatform(next)
	if startErr != nil {
		if previous.Active {
			if restored, restoreErr := startVirtualLanPlatform(previous); restoreErr == nil {
				m.runtimes[request.Name] = restored
			}
		}
		return virtualLanStatusResponse{}, fmt.Errorf("apply virtual LAN config: %w; previous configuration restored", startErr)
	}
	m.runtimes[request.Name] = runtimeState
	if err := m.writeState(next); err != nil {
		m.stopLocked(request.Name)
		if previous.Active {
			if restored, restoreErr := startVirtualLanPlatform(previous); restoreErr == nil {
				m.runtimes[request.Name] = restored
			}
		}
		return virtualLanStatusResponse{}, fmt.Errorf("save virtual LAN config: %w", err)
	}
	return virtualLanPlatformStatus(runtimeState, next)
}

func (m *virtualLanRuntimeManager) pause(name string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	state, err := m.readState(strings.TrimSpace(name))
	if err != nil {
		return err
	}
	m.stopLocked(state.Name)
	state.Active = false
	state.UpdatedAt = time.Now().UnixMilli()
	return m.writeState(state)
}

func (m *virtualLanRuntimeManager) resume(name string) (virtualLanStatusResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	state, err := m.readState(strings.TrimSpace(name))
	if err != nil {
		return virtualLanStatusResponse{}, err
	}
	if state.InterfaceAddress == "" {
		return virtualLanStatusResponse{}, errors.New("virtual LAN has no applied configuration")
	}
	if current := m.runtimes[state.Name]; current != nil {
		return virtualLanPlatformStatus(current, state)
	}
	current, err := startVirtualLanPlatform(state)
	if err != nil {
		return virtualLanStatusResponse{}, err
	}
	state.Active = true
	state.UpdatedAt = time.Now().UnixMilli()
	m.runtimes[state.Name] = current
	if err := m.writeState(state); err != nil {
		m.stopLocked(state.Name)
		return virtualLanStatusResponse{}, err
	}
	return virtualLanPlatformStatus(current, state)
}

func (m *virtualLanRuntimeManager) status(name string) (virtualLanStatusResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	state, err := m.readState(strings.TrimSpace(name))
	if err != nil {
		return virtualLanStatusResponse{}, err
	}
	if !state.Active {
		return virtualLanStatusResponse{Name: state.Name, PublicKey: state.PublicKey, PeerCount: len(state.Peers)}, nil
	}
	current := m.runtimes[state.Name]
	if current == nil {
		return virtualLanStatusResponse{}, errors.New("virtual LAN runtime is not running")
	}
	return virtualLanPlatformStatus(current, state)
}

func (m *virtualLanRuntimeManager) delete(name string) error {
	name = strings.TrimSpace(name)
	if !runtimeNamePattern.MatchString(name) {
		return errors.New("invalid virtual LAN name")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stopLocked(name)
	err := os.Remove(m.statePath(name))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (m *virtualLanRuntimeManager) restore() {
	entries, err := os.ReadDir(m.directory)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		name := strings.TrimSuffix(entry.Name(), ".json")
		m.mu.Lock()
		state, readErr := m.readState(name)
		if readErr == nil && state.Active {
			if current, startErr := startVirtualLanPlatform(state); startErr == nil {
				m.runtimes[name] = current
			} else {
				fmt.Printf("virtual LAN restore %s failed: %v\n", name, startErr)
			}
		}
		m.mu.Unlock()
	}
}

func (m *virtualLanRuntimeManager) stopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for name := range m.runtimes {
		m.stopLocked(name)
	}
}

func (m *virtualLanRuntimeManager) stopLocked(name string) {
	if current := m.runtimes[name]; current != nil {
		stopVirtualLanPlatform(current)
		delete(m.runtimes, name)
	}
}

func (m *virtualLanRuntimeManager) readState(name string) (virtualLanState, error) {
	data, err := os.ReadFile(m.statePath(name))
	if err != nil {
		return virtualLanState{}, err
	}
	var state virtualLanState
	if err := json.Unmarshal(data, &state); err != nil {
		return virtualLanState{}, err
	}
	if state.Name != name || state.PrivateKey == "" || state.PublicKey == "" {
		return virtualLanState{}, errors.New("invalid virtual LAN state")
	}
	return state, nil
}

func (m *virtualLanRuntimeManager) writeState(state virtualLanState) error {
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	temporary := m.statePath(state.Name) + ".tmp"
	if err := os.WriteFile(temporary, data, 0600); err != nil {
		return err
	}
	return os.Rename(temporary, m.statePath(state.Name))
}

func (m *virtualLanRuntimeManager) statePath(name string) string {
	return filepath.Join(m.directory, name+".json")
}

func validateVirtualLanRequest(request *virtualLanApplyRequest) error {
	request.Name = strings.TrimSpace(request.Name)
	request.InterfaceAddress = strings.TrimSpace(request.InterfaceAddress)
	if !runtimeNamePattern.MatchString(request.Name) {
		return errors.New("invalid virtual LAN name")
	}
	ip, network, err := net.ParseCIDR(request.InterfaceAddress)
	if err != nil || ip == nil || ip.To4() == nil {
		return errors.New("virtual LAN address must be an IPv4 CIDR")
	}
	if request.ListenPort < 0 || request.ListenPort > 65535 || (request.Hub && request.ListenPort < 1) {
		return errors.New("invalid virtual LAN listen port")
	}
	if len(request.Peers) < 1 || len(request.Peers) > 253 {
		return errors.New("virtual LAN needs between 1 and 253 peers")
	}
	for index := range request.Peers {
		peer := &request.Peers[index]
		peer.PublicKey = strings.TrimSpace(peer.PublicKey)
		peer.Endpoint = strings.TrimSpace(peer.Endpoint)
		decoded, err := base64.StdEncoding.DecodeString(peer.PublicKey)
		if err != nil || len(decoded) != 32 {
			return errors.New("invalid virtual LAN peer public key")
		}
		if len(peer.AllowedIPs) == 0 {
			return errors.New("virtual LAN peer has no allowed IPs")
		}
		for _, allowed := range peer.AllowedIPs {
			allowedIP, _, err := net.ParseCIDR(allowed)
			if err != nil || !network.Contains(allowedIP) {
				return errors.New("virtual LAN peer route is outside the network CIDR")
			}
		}
		if peer.Endpoint != "" {
			if _, _, err := net.SplitHostPort(peer.Endpoint); err != nil {
				return errors.New("invalid virtual LAN peer endpoint")
			}
		}
		if peer.PersistentKeepalive < 0 || peer.PersistentKeepalive > 120 {
			return errors.New("invalid virtual LAN keepalive")
		}
	}
	return nil
}

func newVirtualLanKeypair() (string, string, error) {
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
