package socket

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestNewRealityStateGeneratesValidCredentials(t *testing.T) {
	state, err := newRealityState(realityRuntimeRequest{Name: "proxy-test", ServerName: "www.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	if state.Port < 1 || state.PrivateKey == "" || state.PublicKey == "" || len(state.ShortID) != 16 {
		t.Fatalf("incomplete REALITY state: %#v", state)
	}
	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(state.ClientID) {
		t.Fatalf("invalid client UUID: %s", state.ClientID)
	}
}

func TestRealityRuntimeFilesArePrivate(t *testing.T) {
	directory := t.TempDir()
	manager := &realityRuntimeManager{directory: directory, binary: directory + "/xray", processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	state, err := newRealityState(realityRuntimeRequest{Name: "proxy-test", ServerName: "www.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.writeStateAndConfig(state); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{manager.statePath(state.Name), manager.configPath(state.Name)} {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0600 {
			t.Fatalf("expected %s to use mode 0600, got %o", path, info.Mode().Perm())
		}
	}
	data, err := os.ReadFile(manager.configPath(state.Name))
	if err != nil {
		t.Fatal(err)
	}
	var config map[string]interface{}
	if err := json.Unmarshal(data, &config); err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`"security":\s*"reality"`).Match(data) || !regexp.MustCompile(`"flow":\s*"xtls-rprx-vision"`).Match(data) {
		t.Fatalf("REALITY config is incomplete: %s", data)
	}
}

func TestParseXrayChecksum(t *testing.T) {
	digest := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	if actual := parseSHA256("SHA2-256= " + digest + "\n"); actual != digest {
		t.Fatalf("unexpected checksum: %s", actual)
	}
	if parseSHA256("not-a-checksum") != "" {
		t.Fatal("invalid checksum response was accepted")
	}
}

func TestXrayAssetMapping(t *testing.T) {
	tests := map[string]string{
		"linux/amd64": "Xray-linux-64.zip", "linux/arm64": "Xray-linux-arm64-v8a.zip",
		"darwin/arm64": "Xray-macos-arm64-v8a.zip", "windows/amd64": "Xray-windows-64.zip",
		"windows/arm64": "Xray-windows-arm64-v8a.zip",
	}
	for platformArch, expected := range tests {
		parts := strings.Split(platformArch, "/")
		actual, err := xrayAssetNameFor(parts[0], parts[1])
		if err != nil || actual != expected {
			t.Fatalf("unexpected asset for %s: %s (%v)", platformArch, actual, err)
		}
	}
	if _, err := xrayAssetNameFor("plan9", "amd64"); err == nil {
		t.Fatal("unsupported platform was accepted")
	}
}

func TestRealityRuntimeIntegration(t *testing.T) {
	if os.Getenv("FLUX_XRAY_INTEGRATION") != "1" {
		t.Skip("set FLUX_XRAY_INTEGRATION=1 to download and start the official Xray runtime")
	}
	directory := t.TempDir()
	manager := &realityRuntimeManager{
		directory: directory,
		binary:    directory + "/xray-" + xrayRuntimeVersion,
		processes: map[string]*exec.Cmd{},
		stopping:  map[string]bool{},
	}
	response, err := manager.add(realityRuntimeRequest{Name: "integration", ServerName: "www.microsoft.com"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(manager.stopAll)
	connection, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", fmt.Sprint(response.Port)), time.Second)
	if err != nil {
		t.Fatalf("REALITY listener is unavailable: %v", err)
	}
	connection.Close()
	if response.ClientID == "" || response.PublicKey == "" || response.ShortID == "" {
		t.Fatalf("incomplete REALITY client response: %#v", response)
	}
	if err := manager.delete("integration"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(manager.configPath("integration")); !os.IsNotExist(err) {
		t.Fatalf("REALITY config was not removed: %v", err)
	}
}
