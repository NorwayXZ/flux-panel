package socket

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// stopStrayMysterium ends myst processes started from binDir that the
// manager does not track. It reads /proc and does nothing where that is absent.
func stopStrayMysterium(binDir string) {
	pids := strayMysteriumPIDs("/proc", binDir)
	for _, pid := range pids {
		_ = syscall.Kill(pid, syscall.SIGTERM)
	}
	deadline := time.Now().Add(15 * time.Second)
	for _, pid := range pids {
		for time.Now().Before(deadline) && syscall.Kill(pid, 0) == nil {
			time.Sleep(200 * time.Millisecond)
		}
		if syscall.Kill(pid, 0) == nil {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
	}
}

func strayMysteriumPIDs(procRoot, binDir string) []int {
	entries, err := os.ReadDir(procRoot)
	if err != nil {
		return nil
	}
	prefix := filepath.Clean(binDir) + string(filepath.Separator)
	pids := []int{}
	for _, entry := range entries {
		pid, err := strconv.Atoi(entry.Name())
		if err != nil || pid == os.Getpid() {
			continue
		}
		exe, err := os.Readlink(filepath.Join(procRoot, entry.Name(), "exe"))
		if err != nil {
			continue
		}
		// A replaced binary shows as "<path> (deleted)".
		exe = strings.TrimSuffix(exe, " (deleted)")
		if strings.HasPrefix(exe, prefix) && strings.HasPrefix(filepath.Base(exe), "myst") {
			pids = append(pids, pid)
		}
	}
	return pids
}
