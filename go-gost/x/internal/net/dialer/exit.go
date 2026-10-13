package dialer

import (
	"context"
	"errors"
	"fmt"
	"net"
	"runtime"
	"strings"
	"syscall"
)

// ExitInterfacePrefix marks an interface option that names an exit group
// instead of a network device, for example "cnexit:jp".
const ExitInterfacePrefix = "cnexit:"

// ExitInterfaceResolver returns the device currently carrying an exit group.
// It is set by the Agent's exit pool manager.
var ExitInterfaceResolver func(group string) (string, error)

// ErrExitUnavailable is returned when an exit group has no usable exit.
var ErrExitUnavailable = errors.New("exit group has no usable exit")

// ExitGroup reports the exit group named by an interface option.
func ExitGroup(ifce string) (string, bool) {
	if !strings.HasPrefix(ifce, ExitInterfacePrefix) {
		return "", false
	}
	return strings.TrimPrefix(ifce, ExitInterfacePrefix), true
}

// dialExit dials through the device of an exit group. Unlike the ordinary
// interface option it never continues without the device binding: a
// connection that cannot use the exit must fail instead of leaving through
// the node's own address.
func (d *Dialer) dialExit(ctx context.Context, network, addr, group string) (net.Conn, error) {
	if runtime.GOOS != "linux" {
		return nil, fmt.Errorf("exit group %s: supported only on Linux", group)
	}
	if ExitInterfaceResolver == nil {
		return nil, fmt.Errorf("exit group %s: %w", group, ErrExitUnavailable)
	}
	device, err := ExitInterfaceResolver(group)
	if err != nil {
		return nil, fmt.Errorf("exit group %s: %w", group, err)
	}
	if device == "" {
		return nil, fmt.Errorf("exit group %s: %w", group, ErrExitUnavailable)
	}
	return DialDevice(ctx, network, addr, device, d.Mark)
}

// DialDevice opens an IPv4 connection that is bound to the given device and
// fails when the binding cannot be applied.
func DialDevice(ctx context.Context, network, addr, device string, mark int) (net.Conn, error) {
	local, err := deviceIPv4(device)
	if err != nil {
		return nil, err
	}
	var bindErr error
	control := func(_, _ string, c syscall.RawConn) error {
		if err := c.Control(func(fd uintptr) {
			bindErr = bindDevice(fd, device)
			if bindErr == nil && mark != 0 {
				bindErr = setMark(fd, mark)
			}
		}); err != nil {
			return err
		}
		if bindErr != nil {
			return fmt.Errorf("bind device %s: %w", device, bindErr)
		}
		return nil
	}

	switch network {
	case "tcp", "tcp4":
		netd := net.Dialer{LocalAddr: &net.TCPAddr{IP: local}, Control: control}
		return netd.DialContext(ctx, "tcp4", addr)
	case "udp", "udp4":
		if addr == "" {
			config := net.ListenConfig{Control: control}
			conn, err := config.ListenPacket(ctx, "udp4", net.JoinHostPort(local.String(), "0"))
			if err != nil {
				return nil, err
			}
			udpConn, ok := conn.(*net.UDPConn)
			if !ok {
				_ = conn.Close()
				return nil, errors.New("unexpected packet connection type")
			}
			return udpConn, nil
		}
		netd := net.Dialer{LocalAddr: &net.UDPAddr{IP: local}, Control: control}
		return netd.DialContext(ctx, "udp4", addr)
	default:
		return nil, fmt.Errorf("exit dial: unsupported network %s", network)
	}
}

func deviceIPv4(device string) (net.IP, error) {
	ifce, err := net.InterfaceByName(device)
	if err != nil {
		return nil, fmt.Errorf("exit device %s: %w", device, err)
	}
	addrs, err := ifce.Addrs()
	if err != nil {
		return nil, fmt.Errorf("exit device %s: %w", device, err)
	}
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok {
			if ip := ipNet.IP.To4(); ip != nil {
				return ip, nil
			}
		}
	}
	return nil, fmt.Errorf("exit device %s has no IPv4 address", device)
}
