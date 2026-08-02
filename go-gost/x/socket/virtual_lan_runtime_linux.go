//go:build linux

package socket

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type virtualLanPlatformRuntime struct {
	device        *device.Device
	tun           tun.Device
	interfaceName string
	ipCommand     string
	iptables      string
	iptablesRules [][]string
}

func startVirtualLanPlatform(state virtualLanState) (*virtualLanPlatformRuntime, error) {
	interfaceName := wireGuardInterfaceName("lan-" + state.Name)
	tunDevice, err := tun.CreateTUN(interfaceName, 1420)
	if err != nil {
		return nil, fmt.Errorf("create virtual LAN interface: %w", err)
	}
	actualName, err := tunDevice.Name()
	if err != nil {
		tunDevice.Close()
		return nil, err
	}
	wg := device.NewDevice(tunDevice, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "(cloudnest-lan) "))
	configuration := "private_key=" + virtualLanUAPIKey(state.PrivateKey) + "\n"
	if state.ListenPort > 0 {
		configuration += "listen_port=" + strconv.Itoa(state.ListenPort) + "\n"
	}
	configuration += "replace_peers=true\n"
	for _, peer := range state.Peers {
		configuration += "public_key=" + virtualLanUAPIKey(peer.PublicKey) + "\n"
		if peer.Endpoint != "" {
			configuration += "endpoint=" + peer.Endpoint + "\n"
		}
		if peer.PersistentKeepalive > 0 {
			configuration += "persistent_keepalive_interval=" + strconv.Itoa(peer.PersistentKeepalive) + "\n"
		}
		for _, allowed := range peer.AllowedIPs {
			configuration += "allowed_ip=" + allowed + "\n"
		}
	}
	if err := wg.IpcSet(configuration); err != nil {
		wg.Close()
		tunDevice.Close()
		return nil, fmt.Errorf("configure virtual LAN WireGuard: %w", err)
	}
	wg.Up()
	result := &virtualLanPlatformRuntime{device: wg, tun: tunDevice, interfaceName: actualName}
	ipCommand, err := exec.LookPath("ip")
	if err != nil {
		stopVirtualLanPlatform(result)
		return nil, errors.New("virtual LAN needs the iproute2 'ip' command")
	}
	result.ipCommand = ipCommand
	if err := runWireGuardCommand(ipCommand, "link", "set", "dev", actualName, "up"); err != nil {
		stopVirtualLanPlatform(result)
		return nil, err
	}
	if err := runWireGuardCommand(ipCommand, "addr", "replace", state.InterfaceAddress, "dev", actualName); err != nil {
		stopVirtualLanPlatform(result)
		return nil, err
	}
	if state.Hub {
		sysctl, err := exec.LookPath("sysctl")
		if err != nil {
			stopVirtualLanPlatform(result)
			return nil, errors.New("virtual LAN hub needs sysctl")
		}
		if err := runWireGuardCommand(sysctl, "-w", "net.ipv4.ip_forward=1"); err != nil {
			stopVirtualLanPlatform(result)
			return nil, err
		}
		iptables, err := exec.LookPath("iptables")
		if err != nil {
			stopVirtualLanPlatform(result)
			return nil, errors.New("virtual LAN hub needs iptables")
		}
		result.iptables = iptables
		rule := []string{"FORWARD", "-i", actualName, "-o", actualName, "-j", "ACCEPT"}
		added, err := ensureIPTablesRule(iptables, rule)
		if err != nil {
			stopVirtualLanPlatform(result)
			return nil, err
		}
		if added {
			result.iptablesRules = append(result.iptablesRules, rule)
		}
	}
	return result, nil
}

func stopVirtualLanPlatform(current *virtualLanPlatformRuntime) {
	if current == nil {
		return
	}
	for index := len(current.iptablesRules) - 1; index >= 0; index-- {
		_ = exec.Command(current.iptables, iptablesRuleArguments("-D", current.iptablesRules[index])...).Run()
	}
	if current.device != nil {
		current.device.Close()
	}
	if current.tun != nil {
		_ = current.tun.Close()
	}
	if current.ipCommand != "" && current.interfaceName != "" {
		_ = exec.Command(current.ipCommand, "link", "delete", "dev", current.interfaceName).Run()
	}
}

func virtualLanPlatformStatus(current *virtualLanPlatformRuntime, state virtualLanState) (virtualLanStatusResponse, error) {
	if current == nil || current.device == nil {
		return virtualLanStatusResponse{}, errors.New("virtual LAN is not running")
	}
	status, err := current.device.IpcGet()
	if err != nil {
		return virtualLanStatusResponse{}, err
	}
	result := virtualLanStatusResponse{Name: state.Name, Active: true, InterfaceName: current.interfaceName, PublicKey: state.PublicKey, PeerCount: len(state.Peers)}
	for _, line := range strings.Split(status, "\n") {
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		value, _ := strconv.ParseInt(parts[1], 10, 64)
		switch parts[0] {
		case "rx_bytes":
			result.ReceiveBytes += value
		case "tx_bytes":
			result.TransmitBytes += value
		case "last_handshake_time_sec":
			if value > result.LatestHandshake {
				result.LatestHandshake = value * 1000
			}
		}
	}
	return result, nil
}

func virtualLanUAPIKey(value string) string {
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return ""
	}
	return hex.EncodeToString(decoded)
}
