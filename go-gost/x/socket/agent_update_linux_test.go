//go:build linux

package socket

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLaunchDetachedAgentUpgradeHelper(t *testing.T) {
	directory := t.TempDir()
	marker := filepath.Join(directory, "started")
	helper := filepath.Join(directory, "helper.sh")
	script := "#!/bin/sh\nprintf started > " + shellQuote(marker) + "\n"
	if err := os.WriteFile(helper, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}

	if err := launchDetachedAgentUpgradeHelper(helper, directory); err != nil {
		t.Fatalf("launchDetachedAgentUpgradeHelper returned an error: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if content, err := os.ReadFile(marker); err == nil && string(content) == "started" {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("detached helper did not run; update log: %s", readUpdateLog(t, filepath.Join(directory, "agent-update.log")))
}

func readUpdateLog(t *testing.T, path string) string {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil {
		return err.Error()
	}
	return string(content)
}
