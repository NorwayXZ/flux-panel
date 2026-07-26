package socket

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

const agentReleaseRepository = "NorwayXZ/flux-panel"

var agentUpgradeLock sync.Mutex
var agentUpgradeRunning bool

type agentUpgradeRequest struct {
	TaskID        string `json:"taskId"`
	TargetVersion string `json:"targetVersion"`
}

type agentUpgradeResponse struct {
	TaskID        string `json:"taskId"`
	TargetVersion string `json:"targetVersion"`
	State         string `json:"state"`
}

type agentUpgradeStatus struct {
	TaskID        string `json:"taskId"`
	TargetVersion string `json:"targetVersion"`
	State         string `json:"state"`
}

func (w *WebSocketReporter) handleAgentUpgrade(data interface{}) (agentUpgradeResponse, error) {
	if w.role != "node" || runtime.GOOS != "linux" {
		return agentUpgradeResponse{}, errors.New("agent self-update is only available for Linux nodes")
	}
	encoded, err := json.Marshal(data)
	if err != nil {
		return agentUpgradeResponse{}, errors.New("invalid update request")
	}
	var request agentUpgradeRequest
	if err := json.Unmarshal(encoded, &request); err != nil {
		return agentUpgradeResponse{}, errors.New("invalid update request")
	}
	if !validAgentUpdateToken(request.TaskID, 16, 64) || !validAgentVersion(request.TargetVersion) {
		return agentUpgradeResponse{}, errors.New("invalid update task")
	}
	if compareAgentVersions(w.version, request.TargetVersion) >= 0 {
		return agentUpgradeResponse{}, fmt.Errorf("agent is already version %s", w.version)
	}

	agentUpgradeLock.Lock()
	if agentUpgradeRunning {
		agentUpgradeLock.Unlock()
		return agentUpgradeResponse{}, errors.New("another agent update is already running")
	}
	agentUpgradeRunning = true
	agentUpgradeLock.Unlock()

	go w.runAgentUpgrade(request)
	return agentUpgradeResponse{TaskID: request.TaskID, TargetVersion: request.TargetVersion, State: "accepted"}, nil
}

func (w *WebSocketReporter) runAgentUpgrade(request agentUpgradeRequest) {
	succeeded := false
	defer func() {
		if !succeeded {
			agentUpgradeLock.Lock()
			agentUpgradeRunning = false
			agentUpgradeLock.Unlock()
		}
	}()

	time.Sleep(750 * time.Millisecond)
	w.sendAgentUpgradeEvent("AgentUpgradeProgress", request, "downloading", "Downloading release metadata")
	executable, err := os.Executable()
	if err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	executable, err = filepath.EvalSymlinks(executable)
	if err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	installDirectory := filepath.Dir(executable)
	binaryName, err := agentReleaseBinaryName(runtime.GOARCH)
	if err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	expectedHash, err := downloadReleaseChecksum(request.TargetVersion, binaryName)
	if err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	newBinary := filepath.Join(installDirectory, ".gost.update-"+request.TaskID)
	if err := downloadReleaseFile(request.TargetVersion, binaryName, newBinary); err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	defer func() {
		if !succeeded {
			_ = os.Remove(newBinary)
		}
	}()
	actualHash, err := fileSHA256(newBinary)
	if err != nil || !strings.EqualFold(actualHash, expectedHash) {
		w.sendAgentUpgradeFailure(request, errors.New("downloaded Agent checksum verification failed"))
		return
	}
	if err := os.Chmod(newBinary, 0755); err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	versionOutput, err := exec.Command(newBinary, "--agent-version").CombinedOutput()
	if err != nil || strings.TrimSpace(string(versionOutput)) != request.TargetVersion {
		w.sendAgentUpgradeFailure(request, errors.New("downloaded Agent version verification failed"))
		return
	}

	statusPath := filepath.Join(installDirectory, ".agent-update-status.json")
	helperPath := filepath.Join(installDirectory, ".agent-updater-"+request.TaskID+".sh")
	if err := os.WriteFile(helperPath, []byte(renderAgentUpdateHelper(request, executable, newBinary, statusPath, helperPath)), 0700); err != nil {
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	w.sendAgentUpgradeEvent("AgentUpgradeProgress", request, "verified", "Release verified")
	time.Sleep(300 * time.Millisecond)
	w.sendAgentUpgradeEvent("AgentUpgradeProgress", request, "restarting", "Restarting Agent service")
	if err := launchAgentUpgradeHelper(request.TaskID, helperPath, installDirectory); err != nil {
		_ = os.Remove(helperPath)
		w.sendAgentUpgradeFailure(request, err)
		return
	}
	succeeded = true
}

func (w *WebSocketReporter) sendAgentUpgradeFailure(request agentUpgradeRequest, err error) {
	w.sendResponse(CommandResponse{Type: "AgentUpgradeResult", Success: false, Message: err.Error(), Data: agentUpgradeStatus{TaskID: request.TaskID, TargetVersion: request.TargetVersion, State: "failed"}})
}

func (w *WebSocketReporter) sendAgentUpgradeEvent(eventType string, request agentUpgradeRequest, state, message string) {
	w.sendResponse(CommandResponse{Type: eventType, Success: true, Message: message, Data: agentUpgradeStatus{TaskID: request.TaskID, TargetVersion: request.TargetVersion, State: state}})
}

func (w *WebSocketReporter) reportPendingAgentUpgrade() {
	executable, err := os.Executable()
	if err != nil {
		return
	}
	statusPath := filepath.Join(filepath.Dir(executable), ".agent-update-status.json")
	for attempt := 0; attempt < 15; attempt++ {
		time.Sleep(2 * time.Second)
		content, err := os.ReadFile(statusPath)
		if err != nil {
			return
		}
		var status agentUpgradeStatus
		if json.Unmarshal(content, &status) != nil || !validAgentUpdateToken(status.TaskID, 16, 64) {
			return
		}
		if status.State != "success" && status.State != "rolled_back" {
			continue
		}
		success := status.State == "success"
		message := "Agent update completed"
		if !success {
			message = "Agent update failed and the previous version was restored"
		}
		w.sendResponse(CommandResponse{Type: "AgentUpgradeResult", Success: success, Message: message, Data: status})
		_ = os.Remove(statusPath)
		return
	}
}

func agentReleaseBinaryName(architecture string) (string, error) {
	switch architecture {
	case "amd64":
		return "gost-amd64", nil
	case "arm64":
		return "gost-arm64", nil
	default:
		return "", fmt.Errorf("unsupported Agent architecture: %s", architecture)
	}
}

func releaseURL(version, filename string) string {
	return fmt.Sprintf("https://github.com/%s/releases/download/%s/%s", agentReleaseRepository, version, filename)
}

func downloadReleaseChecksum(version, binaryName string) (string, error) {
	response, err := getRelease(releaseURL(version, "SHA256SUMS"))
	if err != nil {
		return "", fmt.Errorf("download release checksums: %w", err)
	}
	defer response.Close()
	scanner := bufio.NewScanner(io.LimitReader(response, 128*1024))
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) >= 2 && strings.TrimPrefix(fields[len(fields)-1], "*") == binaryName && len(fields[0]) == 64 {
			if _, err := hex.DecodeString(fields[0]); err == nil {
				return fields[0], nil
			}
		}
	}
	return "", fmt.Errorf("checksum for %s was not found", binaryName)
}

func downloadReleaseFile(version, binaryName, destination string) error {
	response, err := getRelease(releaseURL(version, binaryName))
	if err != nil {
		return fmt.Errorf("download Agent binary: %w", err)
	}
	defer response.Close()
	file, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(file, io.LimitReader(response, 128*1024*1024))
	closeErr := file.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}

func getRelease(url string) (io.ReadCloser, error) {
	client := &http.Client{Timeout: 90 * time.Second}
	var lastError error
	for _, candidate := range []string{url, "https://ghfast.top/" + url} {
		response, err := client.Get(candidate)
		if err != nil {
			lastError = err
			continue
		}
		if response.StatusCode >= 200 && response.StatusCode < 300 {
			return response.Body, nil
		}
		lastError = fmt.Errorf("HTTP %d", response.StatusCode)
		response.Body.Close()
	}
	return nil, lastError
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func validAgentVersion(version string) bool {
	parts := strings.Split(version, ".")
	if len(parts) != 3 {
		return false
	}
	for _, part := range parts {
		if part == "" {
			return false
		}
		for _, char := range part {
			if char < '0' || char > '9' {
				return false
			}
		}
	}
	return true
}

func validAgentUpdateToken(value string, minimum, maximum int) bool {
	if len(value) < minimum || len(value) > maximum {
		return false
	}
	for _, char := range value {
		if !((char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') || (char >= '0' && char <= '9') || char == '-') {
			return false
		}
	}
	return true
}

func compareAgentVersions(left, right string) int {
	parse := func(value string) [3]int {
		var parsed [3]int
		fmt.Sscanf(strings.TrimPrefix(value, "v"), "%d.%d.%d", &parsed[0], &parsed[1], &parsed[2])
		return parsed
	}
	a, b := parse(left), parse(right)
	for index := range a {
		if a[index] < b[index] {
			return -1
		}
		if a[index] > b[index] {
			return 1
		}
	}
	return 0
}

func renderAgentUpdateHelper(request agentUpgradeRequest, executable, newBinary, statusPath, helperPath string) string {
	previous := executable + ".previous"
	return fmt.Sprintf(`#!/bin/sh
set -u
EXECUTABLE=%s
NEW_BINARY=%s
PREVIOUS=%s
STATUS=%s
HELPER=%s
TASK_ID=%s
TARGET_VERSION=%s

write_status() {
  printf '{"taskId":"%%s","targetVersion":"%%s","state":"%%s"}\n' "$TASK_ID" "$TARGET_VERSION" "$1" > "$STATUS"
}
start_service() {
  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then systemctl start gost; else rc-service gost start; fi
}
stop_service() {
  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then systemctl stop gost || true; else rc-service gost stop || true; fi
}
service_active() {
  if command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then systemctl is-active --quiet gost; else rc-service gost status >/dev/null 2>&1; fi
}

write_status installing
cp -f "$EXECUTABLE" "$PREVIOUS" || { write_status rolled_back; exit 1; }
stop_service
mv -f "$NEW_BINARY" "$EXECUTABLE" && chmod 755 "$EXECUTABLE"
if start_service; then sleep 3; fi
if service_active; then
  write_status success
  rm -f "$PREVIOUS" "$HELPER"
  exit 0
fi
stop_service
if [ -f "$PREVIOUS" ]; then mv -f "$PREVIOUS" "$EXECUTABLE"; chmod 755 "$EXECUTABLE"; fi
start_service || true
write_status rolled_back
rm -f "$NEW_BINARY" "$HELPER"
exit 1
`, shellQuote(executable), shellQuote(newBinary), shellQuote(previous), shellQuote(statusPath), shellQuote(helperPath), shellQuote(request.TaskID), shellQuote(request.TargetVersion))
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\"'\"'") + "'"
}
