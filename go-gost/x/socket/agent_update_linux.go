//go:build linux

package socket

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
)

func launchAgentUpgradeHelper(taskID, helperPath, installDirectory string) error {
	_, systemdActive := os.Stat("/run/systemd/system")
	if systemdRun, err := exec.LookPath("systemd-run"); err == nil && systemdActive == nil {
		unit := "flux-agent-upgrade-" + taskID[:12]
		command := exec.Command(systemdRun, "--unit="+unit, "--collect", "--property=Type=oneshot", "/bin/sh", helperPath)
		if output, err := command.CombinedOutput(); err != nil {
			return fmt.Errorf("start systemd update helper: %s", string(output))
		}
		return nil
	}
	logFile, err := os.OpenFile(filepath.Join(installDirectory, "agent-update.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	command := exec.Command("/bin/sh", helperPath)
	command.Dir = "/"
	command.Stdout = logFile
	command.Stderr = logFile
	command.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := command.Start(); err != nil {
		logFile.Close()
		return fmt.Errorf("start detached update helper: %w", err)
	}
	_ = command.Process.Release()
	_ = logFile.Close()
	return nil
}
