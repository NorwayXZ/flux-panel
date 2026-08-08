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

func TestRealityServerCanRouteThroughLocalAuthenticatedSocks(t *testing.T) {
	directory := t.TempDir()
	manager := &realityRuntimeManager{directory: directory, binary: directory + "/xray", processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	state, err := newRealityState(realityRuntimeRequest{
		Name: "routed-reality", ServerName: "www.example.com",
		OutboundProxyHost: "127.0.0.1", OutboundProxyPort: 21080,
		OutboundProxyUsername: "route-user", OutboundProxyPassword: "route-password",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.writeStateAndConfig(state); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(manager.configPath(state.Name))
	if err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{`"protocol": "vless"`, `"protocol": "socks"`, `"address": "127.0.0.1"`, `"port": 21080`, `"user": "route-user"`, `"pass": "route-password"`} {
		if !strings.Contains(string(data), expected) {
			t.Fatalf("routed server config is missing %s: %s", expected, data)
		}
	}
}

func TestRealityServerRejectsNonLoopbackOutboundProxy(t *testing.T) {
	manager := &realityRuntimeManager{directory: t.TempDir(), processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	_, err := manager.add(realityRuntimeRequest{
		Name: "bad-route", ServerName: "www.example.com", OutboundProxyHost: "203.0.113.10",
		OutboundProxyPort: 1080, OutboundProxyUsername: "user", OutboundProxyPassword: "password",
	})
	if err == nil || !strings.Contains(err.Error(), "outbound proxy") {
		t.Fatalf("expected loopback validation error, got %v", err)
	}
}

func TestRealityClientConfigUsesLocalSocksAndRealityOutbound(t *testing.T) {
	directory := t.TempDir()
	manager := &realityRuntimeManager{directory: directory, binary: directory + "/xray", processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	state := realityRuntimeState{
		Mode: "client", Name: "home-client", Port: 19080,
		RemoteHost: "203.0.113.10", RemotePort: 443,
		ClientID: "00000000-0000-4000-8000-000000000001", PublicKey: "public-key",
		ShortID: "0123456789abcdef", ServerName: "www.example.com", Version: xrayRuntimeVersion,
	}
	if err := manager.writeStateAndConfig(state); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(manager.configPath(state.Name))
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, expected := range []string{`"listen": "127.0.0.1"`, `"protocol": "socks"`, `"protocol": "vless"`, `"security": "reality"`, `"address": "203.0.113.10"`} {
		if !strings.Contains(text, expected) {
			t.Fatalf("client config is missing %s: %s", expected, text)
		}
	}
}

func TestRealityLegacyStateDefaultsToServerMode(t *testing.T) {
	directory := t.TempDir()
	manager := &realityRuntimeManager{directory: directory, binary: directory + "/xray", processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	legacy := `{"name":"legacy","port":12345,"clientId":"id","serverName":"www.example.com","version":"v1"}`
	if err := os.WriteFile(manager.statePath("legacy"), []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}
	state, err := manager.readState("legacy")
	if err != nil {
		t.Fatal(err)
	}
	if state.Mode != "server" {
		t.Fatalf("legacy state mode = %q, want server", state.Mode)
	}
}

func TestXHTTPServerConfigUsesRoutedOutbound(t *testing.T) {
	directory := t.TempDir()
	manager := &realityRuntimeManager{directory: directory, binary: directory + "/xray", processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	state := realityRuntimeState{Mode: "server", Network: "xhttp", Security: "none", Name: "xhttp-server", Port: 19090,
		ClientID: "00000000-0000-4000-8000-000000000001", Path: "/aws/", XHTTPMode: "auto", XPaddingBytes: "100-1000",
		OutboundProxyHost: "127.0.0.1", OutboundProxyPort: 21080, OutboundProxyUsername: "route-user", OutboundProxyPassword: "route-password", Version: xrayRuntimeVersion}
	if err := manager.writeStateAndConfig(state); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(manager.configPath(state.Name))
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, expected := range []string{`"network": "xhttp"`, `"path": "/aws/"`, `"xPaddingBytes": "100-1000"`, `"protocol": "socks"`, `"port": 21080`} {
		if !strings.Contains(text, expected) {
			t.Fatalf("XHTTP server config is missing %s: %s", expected, text)
		}
	}
}

func TestXHTTPClientConfigUsesTLSAndLocalSocks(t *testing.T) {
	directory := t.TempDir()
	manager := &realityRuntimeManager{directory: directory, binary: directory + "/xray", processes: map[string]*exec.Cmd{}, stopping: map[string]bool{}}
	state := realityRuntimeState{Mode: "client", Network: "xhttp", Security: "tls", Name: "xhttp-client", Port: 19091,
		ClientID: "00000000-0000-4000-8000-000000000001", Path: "/aws/", XHTTPMode: "auto", XPaddingBytes: "100-1000",
		RemoteHost: "d123.cloudfront.net", RemotePort: 443, ServerName: "d123.cloudfront.net", Version: xrayRuntimeVersion}
	if err := manager.writeStateAndConfig(state); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(manager.configPath(state.Name))
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	for _, expected := range []string{`"protocol": "socks"`, `"protocol": "vless"`, `"network": "xhttp"`, `"security": "tls"`, `"serverName": "d123.cloudfront.net"`} {
		if !strings.Contains(text, expected) {
			t.Fatalf("XHTTP client config is missing %s: %s", expected, text)
		}
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
	client, err := manager.addClient(realityClientRuntimeRequest{
		Name: "integration-client", RemoteHost: "127.0.0.1", RemotePort: response.Port,
		ClientID: response.ClientID, PublicKey: response.PublicKey, ShortID: response.ShortID,
		ServerName: response.ServerName,
	})
	if err != nil {
		t.Fatalf("REALITY client runtime failed: %v", err)
	}
	clientConnection, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", fmt.Sprint(client.Port)), time.Second)
	if err != nil {
		t.Fatalf("REALITY client SOCKS listener is unavailable: %v", err)
	}
	clientConnection.Close()
	if err := manager.delete("integration-client"); err != nil {
		t.Fatal(err)
	}
	if err := manager.delete("integration"); err != nil {
		t.Fatal(err)
	}
	xhttp, err := manager.addXHTTP(xhttpRuntimeRequest{
		Name: "integration-xhttp", ClientID: "00000000-0000-4000-8000-000000000001",
		Path: "/cloudnest/", Mode: "auto", PaddingBytes: "100-1000", Security: "none",
	})
	if err != nil {
		t.Fatalf("XHTTP server runtime failed: %v", err)
	}
	xhttpClient, err := manager.addXHTTPClient(xhttpClientRuntimeRequest{
		Name: "integration-xhttp-client", RemoteHost: "127.0.0.1", RemotePort: xhttp.Port,
		ClientID: xhttp.ClientID, Path: xhttp.Path, Mode: "auto", PaddingBytes: "100-1000", Security: "none",
	})
	if err != nil {
		t.Fatalf("XHTTP client runtime failed: %v", err)
	}
	for name, port := range map[string]int{"XHTTP server": xhttp.Port, "XHTTP client": xhttpClient.Port} {
		connection, dialErr := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", fmt.Sprint(port)), time.Second)
		if dialErr != nil {
			t.Fatalf("%s listener is unavailable: %v", name, dialErr)
		}
		connection.Close()
	}
	if err := manager.delete("integration-xhttp-client"); err != nil {
		t.Fatal(err)
	}
	if err := manager.delete("integration-xhttp"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(manager.configPath("integration")); !os.IsNotExist(err) {
		t.Fatalf("REALITY config was not removed: %v", err)
	}
}
