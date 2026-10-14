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

func TestAdvancedPrivateProxyRuntimeRoutesEverythingThroughTheLocalExit(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir()}
	state := privateProxyRuntimeState{Name: "p", ProxyType: "hysteria2", ListenPort: 24443, Password: "test-password-123"}
	direct := manager.runtimeConfig(state)["outbounds"].([]interface{})
	if len(direct) != 1 || direct[0].(map[string]interface{})["type"] != "direct" {
		t.Fatalf("without a route the runtime connects directly, got %v", direct)
	}

	state.OutboundProxyPort, state.OutboundProxyUsername, state.OutboundProxyPassword = 45001, "exit", "secret"
	routed := manager.runtimeConfig(state)["outbounds"].([]interface{})
	if len(routed) != 1 {
		t.Fatalf("the local exit must be the only outbound so traffic cannot leave directly, got %v", routed)
	}
	outbound := routed[0].(map[string]interface{})
	if outbound["type"] != "socks" || outbound["server"] != "127.0.0.1" || outbound["server_port"] != 45001 || outbound["username"] != "exit" {
		t.Fatalf("unexpected routed outbound %v", outbound)
	}

	if _, err := manager.add(privateProxyRuntimeRequest{Name: "w", ProxyType: "wireguard", ListenPort: 24444,
		Password: "test-password-123", OutboundProxyPort: 45001, OutboundProxyUsername: "exit", OutboundProxyPassword: "secret"}); err == nil && runtime.GOOS == "linux" {
		t.Fatal("WireGuard must refuse a routed exit")
	}
}

func TestNewPrivateProxyProtocolsConfig(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir()}
	key16 := "MTIzNDU2Nzg5MDEyMzQ1Ng=="
	cases := []struct {
		request privateProxyRuntimeRequest
		check   func(t *testing.T, inbounds []interface{}, inbound map[string]interface{})
	}{
		{privateProxyRuntimeRequest{ProxyType: "shadowsocks2022", Password: key16}, func(t *testing.T, _ []interface{}, in map[string]interface{}) {
			if in["type"] != "shadowsocks" || in["method"] != "2022-blake3-aes-128-gcm" || in["password"] != key16 || in["tls"] != nil {
				t.Fatalf("unexpected Shadowsocks 2022 inbound %v", in)
			}
		}},
		{privateProxyRuntimeRequest{ProxyType: "vmess", Password: "", privateProxyRuntimeOptions: privateProxyRuntimeOptions{Transport: "ws"}}, func(t *testing.T, _ []interface{}, in map[string]interface{}) {
			transport := in["transport"].(map[string]interface{})
			if transport["type"] != "ws" || !strings.HasPrefix(transport["path"].(string), "/") || in["tls"] != nil {
				t.Fatalf("unexpected VMess inbound %v", in)
			}
		}},
		{privateProxyRuntimeRequest{ProxyType: "vless", privateProxyRuntimeOptions: privateProxyRuntimeOptions{Transport: "grpc", TLS: true, Path: "svc"}}, func(t *testing.T, _ []interface{}, in map[string]interface{}) {
			transport := in["transport"].(map[string]interface{})
			if transport["type"] != "grpc" || transport["service_name"] != "svc" || in["tls"] == nil {
				t.Fatalf("unexpected VLESS inbound %v", in)
			}
		}},
		{privateProxyRuntimeRequest{ProxyType: "anytls", Password: "test-password-123"}, func(t *testing.T, _ []interface{}, in map[string]interface{}) {
			if in["tls"] == nil {
				t.Fatalf("AnyTLS needs TLS: %v", in)
			}
		}},
		{privateProxyRuntimeRequest{ProxyType: "hysteria2", Password: "test-password-123", privateProxyRuntimeOptions: privateProxyRuntimeOptions{ObfsPassword: "obfs-pass-1"}}, func(t *testing.T, _ []interface{}, in map[string]interface{}) {
			obfs := in["obfs"].(map[string]interface{})
			if obfs["type"] != "salamander" || obfs["password"] != "obfs-pass-1" {
				t.Fatalf("unexpected obfs %v", in)
			}
		}},
		{privateProxyRuntimeRequest{ProxyType: "shadowtls", Password: "test-password-123", privateProxyRuntimeOptions: privateProxyRuntimeOptions{HandshakeServer: "www.microsoft.com", InnerPassword: key16}}, func(t *testing.T, inbounds []interface{}, in map[string]interface{}) {
			if len(inbounds) != 2 || in["detour"] == nil || in["version"] != 3 {
				t.Fatalf("unexpected ShadowTLS inbounds %v", inbounds)
			}
			inner := inbounds[1].(map[string]interface{})
			if inner["listen"] != "127.0.0.1" || inner["type"] != "shadowsocks" || inner["password"] != key16 {
				t.Fatalf("inner Shadowsocks must stay on loopback: %v", inner)
			}
		}},
	}
	for _, c := range cases {
		t.Run(c.request.ProxyType, func(t *testing.T) {
			request := c.request
			request.Name, request.ListenPort = "t-"+request.ProxyType, 24443
			if err := normalizeRuntimeOptions(&request); err != nil {
				t.Fatal(err)
			}
			state, err := newPrivateProxyRuntimeState(request)
			if err != nil {
				t.Fatal(err)
			}
			if (request.ProxyType == "vmess" || request.ProxyType == "vless") && !uuidPattern.MatchString(state.ClientID) {
				t.Fatalf("missing client id: %q", state.ClientID)
			}
			inbounds := manager.runtimeConfig(state)["inbounds"].([]interface{})
			c.check(t, inbounds, inbounds[0].(map[string]interface{}))
		})
	}
}

func TestPrivateProxyRuntimeOptionValidation(t *testing.T) {
	bad := []privateProxyRuntimeRequest{
		{ProxyType: "shadowsocks2022", Password: "not-a-key"},
		{ProxyType: "shadowsocks2022", Password: "MTIzNDU2Nzg5MDEyMzQ1Ng==", privateProxyRuntimeOptions: privateProxyRuntimeOptions{Method: "2022-blake3-aes-256-gcm"}},
		{ProxyType: "vmess", privateProxyRuntimeOptions: privateProxyRuntimeOptions{Transport: "kcp"}},
		{ProxyType: "vless", privateProxyRuntimeOptions: privateProxyRuntimeOptions{Transport: "ws", Path: "no-slash"}},
		{ProxyType: "vless", privateProxyRuntimeOptions: privateProxyRuntimeOptions{ClientID: "nope"}},
		{ProxyType: "shadowtls", Password: "test-password-123", privateProxyRuntimeOptions: privateProxyRuntimeOptions{InnerPassword: "MTIzNDU2Nzg5MDEyMzQ1Ng=="}},
		{ProxyType: "trojan", Password: "short"},
		{ProxyType: "trojan", Password: "test-password-123", AllowedCIDRs: []string{"1.2.3.4"}},
		{ProxyType: "wireguard", Password: "test-password-123", AllowedCIDRs: []string{"1.2.3.0/24"}},
		{ProxyType: "hysteria2", Password: "test-password-123", privateProxyRuntimeOptions: privateProxyRuntimeOptions{ObfsPassword: "short"}},
	}
	for i, request := range bad {
		if err := normalizeRuntimeOptions(&request); err == nil {
			t.Fatalf("case %d (%s) must be refused", i, request.ProxyType)
		}
	}
	ok := privateProxyRuntimeRequest{ProxyType: "trojan", Password: "test-password-123", AllowedCIDRs: []string{" 1.2.3.4/24 ", ""}}
	if err := normalizeRuntimeOptions(&ok); err != nil || len(ok.AllowedCIDRs) != 1 || ok.AllowedCIDRs[0] != "1.2.3.0/24" {
		t.Fatalf("allowed sources are normalised, got %v %v", ok.AllowedCIDRs, err)
	}
}

func TestAllowedSourcesRejectOthersButKeepLoopback(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir()}
	state := privateProxyRuntimeState{Name: "p", ProxyType: "trojan", ListenPort: 24443, Password: "test-password-123", AllowedCIDRs: []string{"1.2.3.0/24"}}
	route, ok := manager.runtimeConfig(state)["route"].(map[string]interface{})
	if !ok {
		t.Fatal("allowed sources need a route rule")
	}
	rule := route["rules"].([]interface{})[0].(map[string]interface{})
	cidrs := rule["source_ip_cidr"].([]string)
	if rule["invert"] != true || rule["action"] != "reject" || cidrs[0] != "1.2.3.0/24" || cidrs[1] != "127.0.0.0/8" {
		t.Fatalf("unexpected rule %v", rule)
	}
	state.AllowedCIDRs = nil
	if _, ok := manager.runtimeConfig(state)["route"]; ok {
		t.Fatal("without allowed sources there is no rule")
	}
}

func TestRuntimeMatchesIgnoresChangeableSettings(t *testing.T) {
	state := privateProxyRuntimeState{ProxyType: "vless", ListenPort: 1, ClientID: "11111111-2222-4333-8444-555555555555", Transport: "ws", Path: "/a",
		OutboundProxyPort: 1, AllowedCIDRs: []string{"1.2.3.0/24"}}
	request := privateProxyRuntimeRequest{ProxyType: "vless", ListenPort: 1, privateProxyRuntimeOptions: privateProxyRuntimeOptions{Transport: "ws", Path: "/a"}}
	if !state.matches(request) {
		t.Fatal("route, sources and an omitted client id may differ")
	}
	request.Path = "/b"
	if state.matches(request) {
		t.Fatal("a different path is a different runtime")
	}
	request.Path, request.ClientID = "/a", "11111111-2222-4333-8444-666666666666"
	if state.matches(request) {
		t.Fatal("a different client id is a different runtime")
	}
}

func TestCertificatePinsMatchTheCertificate(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir()}
	state := privateProxyRuntimeState{Name: "pin", ProxyType: "trojan", ServerName: "cloudnest.local"}
	if err := manager.writeCertificate(state); err != nil {
		t.Fatal(err)
	}
	certHex, keyB64 := certificatePins(manager.certPath(state.Name))
	if len(certHex) != 64 || len(keyB64) != 44 {
		t.Fatalf("unexpected pins %q %q", certHex, keyB64)
	}
	response := manager.response(state)
	if response.CertSHA256 != certHex || response.PublicKeySHA256 != keyB64 {
		t.Fatalf("response must carry the pins: %#v", response)
	}
	if manager.response(privateProxyRuntimeState{Name: "pin", ProxyType: "shadowsocks2022"}).CertSHA256 != "" {
		t.Fatal("protocols without TLS have no pins")
	}
}

func TestTuicSelectsH3(t *testing.T) {
	manager := &privateProxyRuntimeManager{directory: t.TempDir()}
	state := privateProxyRuntimeState{Name: "q", ProxyType: "tuic", ListenPort: 1, Password: "test-password-123", ClientID: "11111111-2222-4333-8444-555555555555"}
	tls := manager.runtimeConfig(state)["inbounds"].([]interface{})[0].(map[string]interface{})["tls"].(map[string]interface{})
	if alpn, ok := tls["alpn"].([]string); !ok || len(alpn) != 1 || alpn[0] != "h3" {
		t.Fatalf("TUIC must offer h3, got %v", tls)
	}
}

func TestProbeErrorDropsColourCodes(t *testing.T) {
	err := probeError("failed", "\x1b[31mERROR\x1b[0m[0000] dial tcp: refused")
	if strings.Contains(err.Error(), "\x1b") || !strings.Contains(err.Error(), "ERROR[0000] dial tcp: refused") {
		t.Fatalf("unexpected error text %q", err.Error())
	}
}
