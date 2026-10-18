package socket

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

var opsMutation sync.Mutex
var opsServiceName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.@-]{0,127}$`)
var opsSlots = make(chan struct{}, 2)

type opsRequest struct {
	TimeoutSeconds  int             `json:"timeoutSeconds"`
	AllowLogCleanup bool            `json:"allowLogCleanup"`
	Tool            string          `json:"tool"`
	Args            json.RawMessage `json:"args"`
	AllowRepair     bool            `json:"allowRepair"`
	Allowlist       []string        `json:"allowlist"`
	RetentionDays   int             `json:"retentionDays"`
	MaxJournalMB    int             `json:"maxJournalMb"`
}
type opsArgs struct {
	Service string `json:"service"`
}

// Continue draining output after the cap so a noisy subprocess cannot block.
type opsOutput struct{ data []byte }

func (b *opsOutput) Write(p []byte) (int, error) {
	n := len(p)
	if left := 32768 - len(b.data); left > 0 {
		if len(p) > left {
			p = p[:left]
		}
		b.data = append(b.data, p...)
	}
	return n, nil
}
func opsCommand(ctx context.Context, program string, args ...string) (string, error) {
	child, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(child, program, args...)
	cmd.WaitDelay = 2 * time.Second
	var out opsOutput
	cmd.Stdout = &out
	cmd.Stderr = &out
	err := cmd.Run()
	if child.Err() != nil {
		err = child.Err()
	}
	return string(out.data), err
}
func validateOpsService(service string, allowed []string) error {
	if !opsServiceName.MatchString(service) {
		return errors.New("invalid service name")
	}
	for _, v := range allowed {
		if service == v {
			return nil
		}
	}
	return errors.New("service is not in task allowlist")
}
func executeOpsTool(ctx context.Context, req opsRequest) (map[string]interface{}, error) {
	if runtime.GOOS != "linux" {
		return nil, errors.New("operations tools require Linux")
	}
	var args opsArgs
	if len(req.Args) > 0 {
		if err := json.Unmarshal(req.Args, &args); err != nil {
			return nil, err
		}
	}
	result := map[string]interface{}{}
	run := func(key, cmd string, argv ...string) error {
		out, err := opsCommand(ctx, cmd, argv...)
		result[key] = out
		if err != nil {
			result[key+"Error"] = err.Error()
		}
		return err
	}
	switch req.Tool {
	case "inspect_disk":
		if err := run("filesystems", "df", "-P"); err != nil {
			return result, err
		}
		run("inodes", "df", "-Pi")
		run("logSize", "du", "-x", "-d", "2", "/var/log")
		run("journal", "journalctl", "--disk-usage")
	case "inspect_service":
		if err := validateOpsService(args.Service, req.Allowlist); err != nil {
			return nil, err
		}
		if err := run("service", "systemctl", "show", "--property=ActiveState,SubState,MainPID,MemoryCurrent", "--", args.Service); err != nil {
			return result, err
		}
	case "vacuum_journal", "restart_service", "cleanup_rotated_logs":
		if !req.AllowRepair {
			return nil, errors.New("task is read-only")
		}
		if !opsMutation.TryLock() {
			return nil, errors.New("another maintenance operation is running")
		}
		defer opsMutation.Unlock()
		if req.Tool == "cleanup_rotated_logs" {
			if !req.AllowLogCleanup {
				return nil, errors.New("rotated log cleanup is not authorized")
			}
			cleanup, err := cleanupOpsLogs(ctx, "/var/log", req.RetentionDays)
			if cleanup == nil {
				return nil, err
			}
			result = cleanup
			verifyErr := run("filesystemsAfter", "df", "-P")
			if err != nil {
				return result, err
			}
			if verifyErr != nil {
				return result, verifyErr
			}
		} else if req.Tool == "vacuum_journal" {
			if req.RetentionDays < 1 || req.RetentionDays > 365 || req.MaxJournalMB < 100 || req.MaxJournalMB > 102400 {
				return nil, errors.New("invalid journal retention limits")
			}
			if err := run("rotate", "journalctl", "--rotate"); err != nil {
				return result, err
			}
			if err := run("vacuum", "journalctl", fmt.Sprintf("--vacuum-size=%dM", req.MaxJournalMB), fmt.Sprintf("--vacuum-time=%dd", req.RetentionDays)); err != nil {
				return result, err
			}
			if err := run("verification", "journalctl", "--disk-usage"); err != nil {
				return result, err
			}
			if err := run("filesystemsAfter", "df", "-P"); err != nil {
				return result, err
			}
		} else {
			if err := validateOpsService(args.Service, req.Allowlist); err != nil {
				return nil, err
			}
			if err := run("restart", "systemctl", "restart", "--", args.Service); err != nil {
				return result, err
			}
			if err := run("verification", "systemctl", "is-active", "--", args.Service); err != nil {
				return result, err
			}
			if strings.TrimSpace(result["verification"].(string)) != "active" {
				return result, errors.New("service did not become active")
			}
		}
	default:
		return nil, errors.New("unknown operations tool")
	}
	return result, nil
}
func (w *WebSocketReporter) runOpsTool(cmd CommandMessage) {
	response := CommandResponse{Type: cmd.Type + "Response", RequestId: cmd.RequestId}
	if cmd.Type == "OpsCapabilities" {
		response.Success = true
		response.Message = "OK"
		response.Data = map[string]interface{}{"version": 1}
		w.sendResponse(response)
		return
	}
	select {
	case opsSlots <- struct{}{}:
		defer func() { <-opsSlots }()
	default:
		response.Message = "operations tools busy"
		w.sendResponse(response)
		return
	}
	var req opsRequest
	err := decodeCommandData(cmd.Data, &req)
	if err == nil {
		seconds := req.TimeoutSeconds
		if seconds <= 0 || seconds > 140 {
			seconds = 140
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(seconds)*time.Second)
		defer cancel()
		response.Data, err = executeOpsTool(ctx, req)
	}
	response.Success = err == nil
	if err != nil {
		response.Message = err.Error()
	} else {
		response.Message = "OK"
	}
	w.sendResponse(response)
}

// os.Root keeps every lookup and removal inside the log tree, even if paths change.
var opsRotatedLog = regexp.MustCompile(`^(?:.+\.log|syslog|messages|debug|wtmp|btmp)(?:\.[0-9]+|[-.][0-9]{4}[-.]?[0-9]{2}[-.]?[0-9]{2})(?:\.gz)?$`)

func cleanupOpsLogs(ctx context.Context, directory string, days int) (map[string]interface{}, error) {
	if days < 1 || days > 365 {
		return nil, errors.New("invalid retention days")
	}
	info, err := os.Lstat(directory)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return nil, errors.New("log root must be a real directory")
	}
	root, err := os.OpenRoot(directory)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	removed := []string{}
	var freed int64
	scanned := 0
	cutoff := time.Now().Add(-time.Duration(days) * 24 * time.Hour)
	err = fs.WalkDir(root.FS(), ".", func(path string, entry fs.DirEntry, walkErr error) error {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		scanned++
		if scanned > 20000 {
			return errors.New("log scan exceeds 20000 entries")
		}
		if walkErr != nil {
			return walkErr
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return nil
		}
		if entry.IsDir() {
			return nil
		}
		if !opsRotatedLog.MatchString(filepath.Base(path)) {
			return nil
		}
		info, err := root.Lstat(path)
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() || !info.ModTime().Before(cutoff) {
			return nil
		}
		if len(removed) >= 500 || freed+info.Size() > 1024*1024*1024 {
			return errors.New("cleanup limit reached (500 files / 1 GiB)")
		}
		if err := root.Remove(path); err != nil {
			return err
		}
		removed = append(removed, path)
		freed += info.Size()
		return nil
	})
	return map[string]interface{}{"removed": removed, "freedBytes": freed, "retentionDays": days}, err
}
