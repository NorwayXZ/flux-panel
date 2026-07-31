//go:build linux

package socket

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

func (m *privateProxyRuntimeManager) wireGuardTraffic(name string) (privateProxyRuntimeTraffic, error) {
	m.mu.Lock()
	runtime := m.wireguards[name]
	m.mu.Unlock()
	if runtime == nil || runtime.device == nil {
		return privateProxyRuntimeTraffic{}, errors.New("WireGuard runtime is not running")
	}
	status, err := runtime.device.IpcGet()
	if err != nil {
		return privateProxyRuntimeTraffic{}, fmt.Errorf("read WireGuard traffic: %w", err)
	}
	return parseWireGuardTraffic(status)
}

type wireGuardRuntime struct {
	device        *device.Device
	tun           tun.Device
	interfaceName string
	iptables      string
	iptablesRules [][]string
}

func (m *privateProxyRuntimeManager) addWireGuard(request privateProxyRuntimeRequest) (privateProxyRuntimeResponse, error) {
	if err := ensureRuntimeDirectory(m.directory); err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if existing, err := m.readState(request.Name); err == nil {
		if existing.ProxyType != "wireguard" || existing.BindIP != request.BindIP || existing.ListenPort != request.ListenPort {
			return privateProxyRuntimeResponse{}, errors.New("WireGuard runtime already exists with different settings")
		}
		if err := m.ensureWireGuardRunning(existing); err != nil {
			return privateProxyRuntimeResponse{}, err
		}
		return existing.response(), nil
	}
	state, err := newPrivateProxyRuntimeState(request)
	if err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if err := m.writeStateAndConfig(state); err != nil {
		return privateProxyRuntimeResponse{}, err
	}
	if err := m.ensureWireGuardRunning(state); err != nil {
		_ = m.removeFiles(state.Name)
		return privateProxyRuntimeResponse{}, err
	}
	return state.response(), nil
}

func (m *privateProxyRuntimeManager) ensureWireGuardRunning(state privateProxyRuntimeState) error {
	m.mu.Lock()
	if runtime := m.wireguards[state.Name]; runtime != nil {
		m.mu.Unlock()
		return nil
	}
	m.mu.Unlock()
	if strings.TrimSpace(state.ServerPrivateKey) == "" || strings.TrimSpace(state.ClientID) == "" || state.ListenPort < 1 {
		return errors.New("WireGuard runtime state is incomplete")
	}
	interfaceName := wireGuardInterfaceName(state.Name)
	tunDevice, err := tun.CreateTUN(interfaceName, 1420)
	if err != nil {
		return fmt.Errorf("create WireGuard interface: %w", err)
	}
	actualName, err := tunDevice.Name()
	if err != nil {
		_ = tunDevice.Close()
		return fmt.Errorf("read WireGuard interface name: %w", err)
	}
	deviceLogger := device.NewLogger(device.LogLevelError, "(cloudnest-wireguard) ")
	wireGuardDevice := device.NewDevice(tunDevice, conn.NewDefaultBind(), deviceLogger)
	configuration := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=%s\n\n",
		wireGuardUAPIKey(state.ServerPrivateKey), state.ListenPort, wireGuardUAPIKey(state.ClientID), state.ClientAddress)
	if err := wireGuardDevice.IpcSet(configuration); err != nil {
		wireGuardDevice.Close()
		return fmt.Errorf("configure WireGuard device: %w", err)
	}
	wireGuardDevice.Up()
	runtime := &wireGuardRuntime{device: wireGuardDevice, tun: tunDevice, interfaceName: actualName}
	if err := runtime.configureNetwork(state); err != nil {
		runtime.close()
		return err
	}
	m.mu.Lock()
	if m.wireguards == nil {
		m.wireguards = make(map[string]*wireGuardRuntime)
	}
	m.wireguards[state.Name] = runtime
	m.mu.Unlock()
	return nil
}

func (m *privateProxyRuntimeManager) stopWireGuard(name string) {
	m.mu.Lock()
	runtime := m.wireguards[name]
	delete(m.wireguards, name)
	m.mu.Unlock()
	if runtime != nil {
		runtime.close()
	}
}

func (m *privateProxyRuntimeManager) stopAllWireGuards() {
	m.mu.Lock()
	runtimes := make([]*wireGuardRuntime, 0, len(m.wireguards))
	for name, runtime := range m.wireguards {
		delete(m.wireguards, name)
		runtimes = append(runtimes, runtime)
	}
	m.mu.Unlock()
	for _, runtime := range runtimes {
		runtime.close()
	}
}

func (r *wireGuardRuntime) configureNetwork(state privateProxyRuntimeState) error {
	ip, err := exec.LookPath("ip")
	if err != nil {
		return errors.New("WireGuard needs the iproute2 'ip' command")
	}
	if err := runWireGuardCommand(ip, "link", "set", "dev", r.interfaceName, "up"); err != nil {
		return fmt.Errorf("enable WireGuard interface: %w", err)
	}
	if err := runWireGuardCommand(ip, "addr", "replace", state.ServerAddress, "dev", r.interfaceName); err != nil {
		return fmt.Errorf("assign WireGuard address: %w", err)
	}
	if sysctl, err := exec.LookPath("sysctl"); err == nil {
		if err := runWireGuardCommand(sysctl, "-w", "net.ipv4.ip_forward=1"); err != nil {
			return fmt.Errorf("enable IPv4 forwarding: %w", err)
		}
	} else {
		return errors.New("WireGuard needs the sysctl command to enable IPv4 forwarding")
	}
	iptables, err := exec.LookPath("iptables")
	if err != nil {
		return errors.New("WireGuard needs iptables for the private subnet NAT rule")
	}
	defaultInterface, err := defaultRouteInterface(ip)
	if err != nil {
		return err
	}
	subnet, err := wireGuardSubnet(state.ServerAddress)
	if err != nil {
		return err
	}
	r.iptables = iptables
	for _, rule := range [][]string{
		{"-t", "nat", "POSTROUTING", "-s", subnet, "-o", defaultInterface, "-j", "MASQUERADE"},
		{"FORWARD", "-i", r.interfaceName, "-o", defaultInterface, "-j", "ACCEPT"},
		{"FORWARD", "-i", defaultInterface, "-o", r.interfaceName, "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "ACCEPT"},
	} {
		added, err := ensureIPTablesRule(iptables, rule)
		if err != nil {
			return err
		}
		if added {
			r.iptablesRules = append(r.iptablesRules, rule)
		}
	}
	return nil
}

func (r *wireGuardRuntime) close() {
	for index := len(r.iptablesRules) - 1; index >= 0; index-- {
		rule := r.iptablesRules[index]
		arguments := iptablesRuleArguments("-D", rule)
		_ = exec.Command(r.iptables, arguments...).Run()
	}
	if r.device != nil {
		r.device.Close()
	}
	if r.tun != nil {
		_ = r.tun.Close()
	}
	if ip, err := exec.LookPath("ip"); err == nil && r.interfaceName != "" {
		_ = exec.Command(ip, "link", "delete", "dev", r.interfaceName).Run()
	}
}

func ensureRuntimeDirectory(directory string) error {
	if err := os.MkdirAll(directory, 0700); err != nil {
		return fmt.Errorf("create WireGuard runtime directory: %w", err)
	}
	return nil
}

func wireGuardInterfaceName(name string) string {
	checksum := sha256.Sum256([]byte(name))
	return fmt.Sprintf("cnwg%x", checksum[:5])
}

func wireGuardUAPIKey(value string) string {
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return ""
	}
	return hex.EncodeToString(decoded)
}

func defaultRouteInterface(ip string) (string, error) {
	output, err := exec.Command(ip, "route", "show", "default").Output()
	if err != nil {
		return "", fmt.Errorf("read default route: %w", err)
	}
	fields := strings.Fields(string(output))
	for index, field := range fields {
		if field == "dev" && index+1 < len(fields) {
			return fields[index+1], nil
		}
	}
	return "", errors.New("unable to determine WireGuard outbound interface")
}

func wireGuardSubnet(address string) (string, error) {
	ip, network, err := net.ParseCIDR(address)
	if err != nil || ip == nil || network == nil {
		return "", errors.New("invalid WireGuard private subnet")
	}
	return network.String(), nil
}

func ensureIPTablesRule(iptables string, rule []string) (bool, error) {
	check := iptablesRuleArguments("-C", rule)
	if err := exec.Command(iptables, check...).Run(); err == nil {
		return false, nil
	}
	appendRule := iptablesRuleArguments("-A", rule)
	if output, err := exec.Command(iptables, appendRule...).CombinedOutput(); err != nil {
		return false, fmt.Errorf("add WireGuard firewall rule: %s", strings.TrimSpace(string(output)))
	}
	return true, nil
}

func iptablesRuleArguments(operation string, rule []string) []string {
	if len(rule) >= 3 && rule[0] == "-t" {
		arguments := []string{"-w", "-t", rule[1], operation}
		return append(arguments, rule[2:]...)
	}
	arguments := []string{"-w", operation}
	return append(arguments, rule...)
}

func runWireGuardCommand(command string, arguments ...string) error {
	output, err := exec.Command(command, arguments...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s", strings.TrimSpace(string(output)))
	}
	return nil
}
