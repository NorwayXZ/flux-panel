package socket

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestAdvancedPrivateProxyRuntimeConfig(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir(), processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	for _, proxyType := range []string{"trojan", "hysteria2", "tuic", "wireguard"} {
		t.Run(proxyType, func(t *testing.T) {
			state, err := newPrivateProxyRuntimeState(privateProxyRuntimeRequest{
				Name: "test-" + proxyType, ProxyType: proxyType, ListenPort: 24443,
				Password: "test-password-123", ServerName: "cloudnest.local",
			})
			if err != nil {
				t.Fatal(err)
			}
			if err := manager.writeStateAndConfig(state); err != nil {
				t.Fatal(err)
			}
			if proxyType == "wireguard" {
				if state.ClientPrivateKey == "" || state.ServerPublicKey == "" || state.ClientAddress == "" {
					t.Fatalf("incomplete WireGuard credentials: %#v", state)
				}
				return
			}
			data, err := os.ReadFile(manager.configPath(state.Name))
			if err != nil {
				t.Fatal(err)
			}
			var config map[string]interface{}
			if err := json.Unmarshal(data, &config); err != nil {
				t.Fatal(err)
			}
			text := string(data)
			if !strings.Contains(text, `"type": "`+proxyType+`"`) {
				t.Fatalf("missing %s inbound: %s", proxyType, text)
			}
			if _, err := os.Stat(manager.certPath(state.Name)); err != nil {
				t.Fatalf("certificate is missing: %v", err)
			}
			if _, err := os.Stat(manager.keyPath(state.Name)); err != nil {
				t.Fatalf("key is missing: %v", err)
			}
		})
	}
}

func TestPrivateProxyRuntimeFilesArePrivate(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir(), processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	state, err := newPrivateProxyRuntimeState(privateProxyRuntimeRequest{
		Name: "test-private", ProxyType: "hysteria2", ListenPort: 24443,
		Password: "test-password-123", ServerName: "cloudnest.local",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.writeStateAndConfig(state); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{manager.statePath(state.Name), manager.configPath(state.Name), manager.certPath(state.Name), manager.keyPath(state.Name)} {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0600 {
			t.Fatalf("%s permissions are %o, want 0600", filepath.Base(path), info.Mode().Perm())
		}
	}
}

func TestAdvancedPrivateProxyRuntimeSingBoxValidation(t *testing.T) {
	if os.Getenv("FLUX_SINGBOX_INTEGRATION") != "1" {
		t.Skip("set FLUX_SINGBOX_INTEGRATION=1 to download and validate configs with sing-box")
	}
	if runtime.GOOS != "darwin" || (runtime.GOARCH != "amd64" && runtime.GOARCH != "arm64") {
		t.Skip("local sing-box integration validation is configured for macOS")
	}
	directory := t.TempDir()
	manager := &privateProxyRuntimeManager{
		directory: directory, binary: filepath.Join(directory, "sing-box"),
		processes: map[string]*exec.Cmd{}, stopping: map[string]bool{},
	}
	asset := fmt.Sprintf("sing-box-%s-darwin-%s.tar.gz", singBoxRuntimeVersion, runtime.GOARCH)
	url := "https://github.com/SagerNet/sing-box/releases/download/v" + singBoxRuntimeVersion + "/" + asset
	archive := filepath.Join(directory, "sing-box.tar.gz")
	if err := downloadFile(&http.Client{}, url, archive, 96*1024*1024); err != nil {
		t.Fatal(err)
	}
	if err := extractSingBox(archive, manager.binary); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(manager.binary, 0755); err != nil {
		t.Fatal(err)
	}
	for _, proxyType := range []string{"trojan", "hysteria2", "tuic"} {
		t.Run(proxyType, func(t *testing.T) {
			state, err := newPrivateProxyRuntimeState(privateProxyRuntimeRequest{
				Name: "live-" + proxyType, ProxyType: proxyType, ListenPort: 24443,
				Password: "test-password-123", ServerName: "cloudnest.local",
			})
			if err != nil {
				t.Fatal(err)
			}
			if err := manager.writeStateAndConfig(state); err != nil {
				t.Fatal(err)
			}
			if err := manager.validateConfig(state); err != nil {
				t.Fatal(err)
			}
		})
	}
}
