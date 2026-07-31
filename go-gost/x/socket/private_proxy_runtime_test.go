package socket

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
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
			if proxyType != "wireguard" {
				state.ControllerPort = 23456
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
			experimental, ok := config["experimental"].(map[string]interface{})
			if !ok {
				t.Fatalf("traffic controller is missing: %s", text)
			}
			clashAPI, ok := experimental["clash_api"].(map[string]interface{})
			if !ok || clashAPI["external_controller"] != "127.0.0.1:23456" {
				t.Fatalf("traffic controller must stay on loopback: %#v", experimental)
			}
		})
	}
}

func TestPrivateProxyRuntimeReadsSingBoxTraffic(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.URL.Path != "/connections" {
			http.NotFound(response, request)
			return
		}
		_, _ = response.Write([]byte(`{"uploadTotal":1234,"downloadTotal":5678}`))
	}))
	defer server.Close()

	manager := &privateProxyRuntimeManager{directory: t.TempDir(), processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	port := server.Listener.Addr().(*net.TCPAddr).Port
	state := privateProxyRuntimeState{Name: "traffic-test", ProxyType: "trojan", ControllerPort: port}
	data, err := json.Marshal(state)
	if err != nil {
		t.Fatal(err)
	}
	if err := writeRuntimeFile(manager.statePath(state.Name), data, 0600); err != nil {
		t.Fatal(err)
	}
	traffic, err := manager.traffic(state.Name)
	if err != nil {
		t.Fatal(err)
	}
	if traffic.InFlow != 1234 || traffic.OutFlow != 5678 {
		t.Fatalf("unexpected traffic: %#v", traffic)
	}
}

func TestWireGuardTrafficParser(t *testing.T) {
	traffic, err := parseWireGuardTraffic("private_key=hidden\nrx_bytes=1024\ntx_bytes=2048\nrx_bytes=512\ntx_bytes=256\n")
	if err != nil {
		t.Fatal(err)
	}
	if traffic.InFlow != 1536 || traffic.OutFlow != 2304 {
		t.Fatalf("unexpected traffic: %#v", traffic)
	}
	if _, err := parseWireGuardTraffic("rx_bytes=invalid\n"); err == nil {
		t.Fatal("invalid WireGuard counter must fail")
	}
}

func TestControllerPortsStayUniqueWhenRuntimeIsPaused(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir(), processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	firstPort, err := manager.availableControllerPort("first-runtime")
	if err != nil {
		t.Fatal(err)
	}
	state := privateProxyRuntimeState{Name: "saved-runtime", ProxyType: "trojan", ControllerPort: firstPort}
	data, _ := json.Marshal(state)
	if err := writeRuntimeFile(manager.statePath(state.Name), data, 0600); err != nil {
		t.Fatal(err)
	}
	secondPort, err := manager.availableControllerPort("first-runtime")
	if err != nil {
		t.Fatal(err)
	}
	if secondPort == firstPort {
		t.Fatalf("paused runtime controller port %d was reused", firstPort)
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
			state.ControllerPort = 23456
			if err := manager.writeStateAndConfig(state); err != nil {
				t.Fatal(err)
			}
			if err := manager.validateConfig(state); err != nil {
				t.Fatal(err)
			}
		})
	}
}
