//go:build linux

package socket

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/go-gost/x/internal/net/dialer"
)

const exitDevicePrefix = "cnx"

var (
	exitIPEndpoints  = []string{"https://api.ipify.org", "https://checkip.amazonaws.com", "https://ipv4.icanhazip.com"}
	exitCheckTargets = []string{"1.1.1.1:443", "8.8.8.8:443"}
)

type openVPNRunner struct {
	mu      sync.Mutex
	binary  string
	version [2]int
}

func newExitRunner() exitRunner { return &openVPNRunner{} }

func (r *openVPNRunner) prepare() (string, error) {
	if _, err := exec.LookPath("ip"); err != nil {
		return "", errors.New("residential exits need the iproute2 'ip' command")
	}
	if _, err := os.Stat("/dev/net/tun"); err != nil {
		_ = exec.Command("modprobe", "tun").Run()
		if _, err := os.Stat("/dev/net/tun"); err != nil {
			return "", errors.New("this node has no TUN device support, which residential exits require")
		}
	}
	binary := findOpenVPN()
	if binary == "" {
		if err := installOpenVPN(); err != nil {
			return "", err
		}
		if binary = findOpenVPN(); binary == "" {
			return "", errors.New("OpenVPN was installed but its executable was not found")
		}
	}
	// "openvpn --version" exits with a non-zero status on several releases.
	output, _ := exec.Command(binary, "--version").CombinedOutput()
	version, text, err := parseOpenVPNVersion(string(output))
	if err != nil {
		return "", err
	}
	if version[0] < 2 || (version[0] == 2 && version[1] < 5) {
		return "", fmt.Errorf("OpenVPN 2.5 or newer is required, this node has %s", text)
	}
	r.mu.Lock()
	r.binary, r.version = binary, version
	r.mu.Unlock()
	return text, nil
}

func findOpenVPN() string {
	if path, err := exec.LookPath("openvpn"); err == nil {
		return path
	}
	for _, path := range []string{"/usr/sbin/openvpn", "/usr/local/sbin/openvpn", "/usr/bin/openvpn"} {
		if info, err := os.Stat(path); err == nil && info.Mode().IsRegular() {
			return path
		}
	}
	return ""
}

func installOpenVPN() error {
	has := func(name string) bool { _, err := exec.LookPath(name); return err == nil }
	run := func(env []string, name string, arguments ...string) error {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
		defer cancel()
		command := exec.CommandContext(ctx, name, arguments...)
		command.Env = append(os.Environ(), env...)
		if output, err := command.CombinedOutput(); err != nil {
			text := strings.TrimSpace(string(output))
			if len(text) > 200 {
				text = text[len(text)-200:]
			}
			return fmt.Errorf("%s %s: %s", name, strings.Join(arguments, " "), text)
		}
		return nil
	}
	var err error
	switch {
	case has("apt-get"):
		env := []string{"DEBIAN_FRONTEND=noninteractive"}
		if err = run(env, "apt-get", "install", "-y", "--no-install-recommends", "openvpn"); err != nil {
			_ = run(env, "apt-get", "update")
			err = run(env, "apt-get", "install", "-y", "--no-install-recommends", "openvpn")
		}
	case has("dnf"):
		if err = run(nil, "dnf", "install", "-y", "openvpn"); err != nil {
			_ = run(nil, "dnf", "install", "-y", "epel-release")
			err = run(nil, "dnf", "install", "-y", "openvpn")
		}
	case has("yum"):
		if err = run(nil, "yum", "install", "-y", "openvpn"); err != nil {
			_ = run(nil, "yum", "install", "-y", "epel-release")
			err = run(nil, "yum", "install", "-y", "openvpn")
		}
	case has("apk"):
		err = run(nil, "apk", "add", "--no-cache", "openvpn")
	case has("zypper"):
		err = run(nil, "zypper", "--non-interactive", "install", "openvpn")
	case has("pacman"):
		err = run(nil, "pacman", "-Sy", "--noconfirm", "openvpn")
	default:
		return errors.New("OpenVPN is not installed and no supported package manager was found; install openvpn on this node")
	}
	if err != nil {
		return fmt.Errorf("automatic OpenVPN installation failed, install openvpn on this node: %w", err)
	}
	return nil
}

// cleanup stops tunnel processes and devices left behind by an earlier Agent
// process.
func (r *openVPNRunner) cleanup(directory string) {
	entries, _ := os.ReadDir(directory)
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasPrefix(name, exitDevicePrefix) {
			continue
		}
		path := filepath.Join(directory, name)
		if strings.HasSuffix(name, ".pid") {
			if data, err := os.ReadFile(path); err == nil {
				if pid, err := strconv.Atoi(strings.TrimSpace(string(data))); err == nil && pid > 1 {
					stopStaleExitProcess(pid, directory)
				}
			}
		}
		switch filepath.Ext(name) {
		case ".pid", ".conf", ".sock", ".log":
			_ = os.Remove(path)
		}
	}
	interfaces, _ := net.Interfaces()
	for _, ifce := range interfaces {
		if strings.HasPrefix(ifce.Name, exitDevicePrefix) && len(ifce.Name) == len(exitDevicePrefix)+10 {
			_ = exec.Command("ip", "link", "delete", "dev", ifce.Name).Run()
		}
	}
}

func stopStaleExitProcess(pid int, directory string) {
	commandLine, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil || !strings.Contains(string(commandLine), directory) {
		return
	}
	process, err := os.FindProcess(pid)
	if err != nil {
		return
	}
	_ = process.Signal(syscall.SIGTERM)
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(fmt.Sprintf("/proc/%d", pid)); err != nil {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	_ = process.Kill()
}

type openVPNSession struct {
	device  string
	base    string
	cmd     *exec.Cmd
	exited  chan struct{}
	closing sync.Once
}

func (r *openVPNRunner) connect(ctx context.Context, directory, device string, spec exitMemberSpec) (exitSession, error) {
	r.mu.Lock()
	binary, version := r.binary, r.version
	r.mu.Unlock()
	if binary == "" {
		return nil, errors.New("OpenVPN is not ready")
	}
	base := filepath.Join(directory, device)
	socketPath := base + ".sock"
	_ = os.Remove(socketPath)
	waitForExitDeviceGone(device, 3*time.Second)
	if err := writeRuntimeFile(base+".conf", []byte(exitTunnelConfig(spec, device, socketPath, version)), 0600); err != nil {
		return nil, fmt.Errorf("write exit configuration: %w", err)
	}
	logFile, err := os.OpenFile(base+".log", os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return nil, fmt.Errorf("open exit log: %w", err)
	}
	cmd := exec.Command(binary, "--config", base+".conf")
	cmd.Dir = directory
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		logFile.Close()
		return nil, fmt.Errorf("start OpenVPN: %w", err)
	}
	logFile.Close()
	_ = os.WriteFile(base+".pid", []byte(strconv.Itoa(cmd.Process.Pid)), 0600)
	session := &openVPNSession{device: device, base: base, cmd: cmd, exited: make(chan struct{})}
	go func() {
		_ = cmd.Wait()
		close(session.exited)
	}()

	local, err := session.waitConnected(ctx, socketPath)
	if err != nil {
		if detail := lastExitLogError(base + ".log"); detail != "" {
			err = fmt.Errorf("%w (%s)", err, detail)
		}
		session.close()
		return nil, err
	}
	if err := session.configure(local); err != nil {
		session.close()
		return nil, err
	}
	return session, nil
}

// waitConnected follows the management interface until the tunnel reports
// its address.
func (s *openVPNSession) waitConnected(ctx context.Context, socketPath string) (string, error) {
	var conn net.Conn
	for conn == nil {
		select {
		case <-ctx.Done():
			return "", errors.New("exit did not connect in time")
		case <-s.exited:
			return "", errors.New("exit connection failed")
		default:
		}
		candidate, err := net.DialTimeout("unix", socketPath, time.Second)
		if err != nil {
			time.Sleep(150 * time.Millisecond)
			continue
		}
		conn = candidate
	}
	defer conn.Close()
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
		case <-s.exited:
		case <-stop:
			return
		}
		_ = conn.SetDeadline(time.Now())
	}()
	if _, err := io.WriteString(conn, "state on\nstate\n"); err != nil {
		return "", fmt.Errorf("exit management channel: %w", err)
	}
	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		state, local, ok := parseExitManagementState(scanner.Text())
		if !ok {
			continue
		}
		switch state {
		case "CONNECTED":
			if local != "" {
				return local, nil
			}
		case "EXITING":
			return "", errors.New("exit connection failed")
		}
	}
	if ctx.Err() != nil {
		return "", errors.New("exit did not connect in time")
	}
	return "", errors.New("exit connection failed")
}

// configure assigns the tunnel address without a peer or subnet route, so the
// node's routing table stays exactly as it was. Only connections bound to the
// device use it.
func (s *openVPNSession) configure(local string) error {
	ip, err := validExitTunnelAddress(local, localNetworksExceptExits())
	if err != nil {
		return err
	}
	if err := runExitCommand("ip", "addr", "replace", ip.String()+"/32", "dev", s.device); err != nil {
		return fmt.Errorf("assign exit tunnel address: %w", err)
	}
	if err := runExitCommand("ip", "link", "set", "dev", s.device, "up"); err != nil {
		return fmt.Errorf("enable exit device: %w", err)
	}
	// Replies arrive on a device the routing table does not point at, which
	// strict reverse-path filtering would drop.
	_ = os.WriteFile("/proc/sys/net/ipv4/conf/"+s.device+"/rp_filter", []byte("2"), 0644)
	_ = os.WriteFile("/proc/sys/net/ipv6/conf/"+s.device+"/disable_ipv6", []byte("1"), 0644)
	return nil
}

func (s *openVPNSession) exitIP(ctx context.Context) (string, error) {
	client := &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
				return dialer.DialDevice(ctx, "tcp", addr, s.device, 0)
			},
		},
	}
	var lastErr error
	for _, endpoint := range exitIPEndpoints {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			return "", err
		}
		response, err := client.Do(request)
		if err != nil {
			lastErr = err
			continue
		}
		body, err := io.ReadAll(io.LimitReader(response.Body, 256))
		response.Body.Close()
		if err != nil || response.StatusCode != http.StatusOK {
			lastErr = fmt.Errorf("%s returned HTTP %d", endpoint, response.StatusCode)
			continue
		}
		if ip := net.ParseIP(strings.TrimSpace(string(body))).To4(); ip != nil {
			return ip.String(), nil
		}
		lastErr = fmt.Errorf("%s returned no address", endpoint)
	}
	return "", lastErr
}

func (s *openVPNSession) check(ctx context.Context) (time.Duration, error) {
	var lastErr error
	for _, target := range exitCheckTargets {
		attempt, cancel := context.WithTimeout(ctx, 3*time.Second)
		started := time.Now()
		conn, err := dialer.DialDevice(attempt, "tcp", target, s.device, 0)
		cancel()
		if err == nil {
			_ = conn.Close()
			return time.Since(started), nil
		}
		lastErr = err
	}
	return 0, lastErr
}

func (s *openVPNSession) done() <-chan struct{} { return s.exited }

func (s *openVPNSession) traffic() (int64, int64) {
	read := func(name string) int64 {
		data, err := os.ReadFile("/sys/class/net/" + s.device + "/statistics/" + name)
		if err != nil {
			return 0
		}
		value, _ := strconv.ParseInt(strings.TrimSpace(string(data)), 10, 64)
		return value
	}
	return read("rx_bytes"), read("tx_bytes")
}

func (s *openVPNSession) close() {
	s.closing.Do(func() {
		if s.cmd.Process != nil {
			_ = s.cmd.Process.Signal(syscall.SIGTERM)
			select {
			case <-s.exited:
			case <-time.After(3 * time.Second):
				_ = s.cmd.Process.Kill()
				<-s.exited
			}
		}
		_ = exec.Command("ip", "link", "delete", "dev", s.device).Run()
		for _, suffix := range []string{".pid", ".conf", ".sock", ".log"} {
			_ = os.Remove(s.base + suffix)
		}
	})
}

func localNetworksExceptExits() []*net.IPNet {
	var networks []*net.IPNet
	interfaces, _ := net.Interfaces()
	for _, ifce := range interfaces {
		if strings.HasPrefix(ifce.Name, exitDevicePrefix) {
			continue
		}
		addrs, _ := ifce.Addrs()
		for _, addr := range addrs {
			if network, ok := addr.(*net.IPNet); ok && network.IP.To4() != nil {
				networks = append(networks, network)
			}
		}
	}
	return networks
}

func waitForExitDeviceGone(device string, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if _, err := net.InterfaceByName(device); err != nil {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func runExitCommand(name string, arguments ...string) error {
	output, err := exec.Command(name, arguments...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s", strings.TrimSpace(string(output)))
	}
	return nil
}

// lastExitLogError returns the most recent failure line of a tunnel log
// without its timestamp.
func lastExitLogError(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	if len(data) > 8192 {
		data = data[len(data)-8192:]
	}
	lines := strings.Split(string(data), "\n")
	for index := len(lines) - 1; index >= 0; index-- {
		line := strings.TrimSpace(lines[index])
		lower := strings.ToLower(line)
		if !strings.Contains(lower, "failed") && !strings.Contains(lower, "error") && !strings.Contains(lower, "refused") {
			continue
		}
		if strings.Contains(line, "MANAGEMENT:") || strings.Contains(lower, "fatal error") || strings.Contains(lower, "received, process") {
			continue
		}
		if len(line) > 20 && line[4] == '-' && line[10] == ' ' {
			line = strings.TrimSpace(line[19:])
		}
		if len(line) > 160 {
			line = line[:160]
		}
		return line
	}
	return ""
}
