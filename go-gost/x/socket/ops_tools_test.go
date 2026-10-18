package socket

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestOpsServicePolicy(t *testing.T) {
	for _, name := range []string{"nginx.service", "app@worker.service", "gost"} {
		if err := validateOpsService(name, []string{name}); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"", "../nginx", "--help", "nginx;id", "nginx service", "/etc/passwd"} {
		if err := validateOpsService(name, []string{name}); err == nil {
			t.Fatalf("accepted %q", name)
		}
	}
	if validateOpsService("sshd", []string{"nginx"}) == nil {
		t.Fatal("accepted service outside allowlist")
	}
}
func TestOpsReadOnlyAndUnknownTool(t *testing.T) {
	for _, name := range []string{"vacuum_journal", "restart_service", "cleanup_rotated_logs", "arbitrary_shell"} {
		_, err := executeOpsTool(context.Background(), opsRequest{Tool: name, Args: json.RawMessage(`{}`)})
		if err == nil {
			t.Fatalf("accepted %s", name)
		}
	}
}
func TestOpsOutputBound(t *testing.T) {
	var b opsOutput
	payload := []byte(strings.Repeat("x", 100000))
	n, err := b.Write(payload)
	if err != nil || n != len(payload) || len(b.data) != 32768 {
		t.Fatal("output cap failed")
	}
}
func TestOpsCleanupBoundaries(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	write := func(dir, name string, old bool) {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte("test"), 0600); err != nil {
			t.Fatal(err)
		}
		if old {
			past := time.Now().Add(-30 * 24 * time.Hour)
			os.Chtimes(p, past, past)
		}
	}
	write(root, "app.log.1.gz", true)
	write(root, "app.log", true)
	write(root, "app.log.2.gz", false)
	write(root, "database.gz", true)
	write(outside, "secret.log.1", true)
	if err := os.Symlink(outside, filepath.Join(root, "linked")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(outside, "secret.log.1"), filepath.Join(root, "symlink.log.1")); err != nil {
		t.Fatal(err)
	}
	result, err := cleanupOpsLogs(context.Background(), root, 7)
	if err != nil {
		t.Fatal(err)
	}
	if len(result["removed"].([]string)) != 1 {
		t.Fatalf("removed %v", result)
	}
	for _, p := range []string{filepath.Join(root, "app.log"), filepath.Join(root, "app.log.2.gz"), filepath.Join(root, "database.gz"), filepath.Join(outside, "secret.log.1"), filepath.Join(root, "symlink.log.1")} {
		if _, err := os.Lstat(p); err != nil {
			t.Fatalf("protected path changed: %s", p)
		}
	}
	if _, err := cleanupOpsLogs(context.Background(), filepath.Join(root, "linked"), 7); err == nil {
		t.Fatal("accepted symlink root")
	}
	if _, err := cleanupOpsLogs(context.Background(), root, 0); err == nil {
		t.Fatal("accepted zero retention")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := cleanupOpsLogs(ctx, root, 7); err == nil {
		t.Fatal("ignored cancellation")
	}
}
