//go:build linux

package socket

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func repairStartupService(role string) {
	if os.Geteuid() != 0 {
		return
	}
	executable, err := os.Executable()
	if err != nil {
		return
	}
	executable, err = filepath.EvalSymlinks(executable)
	if err != nil {
		return
	}

	serviceName := "gost"
	if role == "connector" {
		serviceName = "flux-connector"
	}
	if _, err := os.Stat("/run/systemd/system"); err == nil {
		repairSystemdService(serviceName, executable)
		return
	}
	repairOpenRCService(serviceName, executable)
}

func repairSystemdService(serviceName, executable string) {
	path := filepath.Join("/etc/systemd/system", serviceName+".service")
	content, mode, ok := readManagedService(path, "ExecStart="+executable)
	if !ok {
		return
	}
	updated, changed := optimizeSystemdUnit(content)
	if !changed || writeAtomic(path, []byte(updated), mode) != nil {
		return
	}
	runServiceCommand("systemctl", "daemon-reload")
	runServiceCommand("systemctl", "enable", serviceName)
}

func repairOpenRCService(serviceName, executable string) {
	path := filepath.Join("/etc/init.d", serviceName)
	content, mode, ok := readManagedService(path, "command=\""+executable+"\"")
	if !ok {
		return
	}
	updated, changed := optimizeOpenRCScript(content)
	if !changed || writeAtomic(path, []byte(updated), mode) != nil {
		return
	}
	runServiceCommand("rc-update", "add", serviceName, "default")
}

func readManagedService(path, executableMarker string) (string, os.FileMode, bool) {
	info, err := os.Stat(path)
	if err != nil {
		return "", 0, false
	}
	data, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(data), executableMarker) {
		return "", 0, false
	}
	return string(data), info.Mode(), true
}

func optimizeSystemdUnit(content string) (string, bool) {
	lines := strings.Split(content, "\n")
	updated := make([]string, 0, len(lines)+1)
	hasStartLimit := false
	changed := false

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "StartLimitIntervalSec=0" {
			hasStartLimit = true
		}
		if trimmed == "Wants=network-online.target" {
			changed = true
			continue
		}
		if strings.HasPrefix(trimmed, "After=") && strings.Contains(trimmed, "network-online.target") {
			line = strings.Replace(line, "network-online.target", "network.target", 1)
			changed = true
		}
		if trimmed == "Restart=on-failure" {
			line = strings.Replace(line, "Restart=on-failure", "Restart=always", 1)
			changed = true
		}
		if trimmed == "RestartSec=3" || trimmed == "RestartSec=3s" {
			line = strings.Replace(line, trimmed, "RestartSec=1", 1)
			changed = true
		}
		if trimmed == "[Service]" && !hasStartLimit {
			updated = append(updated, "StartLimitIntervalSec=0")
			hasStartLimit = true
			changed = true
		}
		updated = append(updated, line)
	}
	return strings.Join(updated, "\n"), changed
}

func optimizeOpenRCScript(content string) (string, bool) {
	lines := strings.Split(content, "\n")
	changed := false
	for index, line := range lines {
		if strings.TrimSpace(line) != "need net" {
			continue
		}
		indent := line[:len(line)-len(strings.TrimLeft(line, " \t"))]
		lines[index] = indent + "use net"
		changed = true
	}
	return strings.Join(lines, "\n"), changed
}

func writeAtomic(path string, data []byte, mode os.FileMode) error {
	temporary, err := os.CreateTemp(filepath.Dir(path), ".flux-service-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(mode.Perm()); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

func runServiceCommand(name string, args ...string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if output, err := exec.CommandContext(ctx, name, args...).CombinedOutput(); err != nil {
		fmt.Printf("startup service optimization failed: %s: %s\n", err, strings.TrimSpace(string(output)))
	}
}
