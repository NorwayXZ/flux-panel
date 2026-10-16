package socket

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func sampleRouterRequest() proxyRouterRequest {
	var request proxyRouterRequest
	_ = json.Unmarshal([]byte(`{
		"name": "private-proxy-7-router",
		"outbounds": [
			{"type": "socks", "tag": "res-us", "version": "5", "server": "127.0.0.1", "server_port": 45001, "username": "exit", "password": "x"},
			{"type": "vless", "tag": "custom-1", "server": "example.com", "server_port": 443, "uuid": "11111111-2222-3333-4444-555555555555", "flow": "xtls-rprx-vision",
			 "tls": {"enabled": true, "server_name": "www.microsoft.com", "utls": {"enabled": true, "fingerprint": "chrome"},
			 "reality": {"enabled": true, "public_key": "uPvv8YQ8X2Zx3H9yjUfmdz8nmLNZmV9pWwF0W2V6HXs", "short_id": "ab12"}}},
			{"type": "hysteria2", "tag": "custom-2", "server": "1.2.3.4", "server_port": 8443, "password": "p", "tls": {"enabled": true, "insecure": true}},
			{"type": "shadowsocks", "tag": "custom-3", "server": "1.2.3.4", "server_port": 8388, "method": "2022-blake3-aes-128-gcm", "password": "AAAAAAAAAAAAAAAAAAAAAA=="},
			{"type": "vmess", "tag": "custom-4", "server": "1.2.3.4", "server_port": 80, "uuid": "11111111-2222-3333-4444-555555555555", "security": "auto",
			 "transport": {"type": "ws", "path": "/ws", "headers": {"Host": "a.example.com"}}},
			{"type": "trojan", "tag": "custom-5", "server": "1.2.3.4", "server_port": 443, "password": "p", "tls": {"enabled": true, "server_name": "a.example.com"}},
			{"type": "tuic", "tag": "custom-6", "server": "1.2.3.4", "server_port": 443, "uuid": "11111111-2222-3333-4444-555555555555", "password": "p", "congestion_control": "bbr", "tls": {"enabled": true, "alpn": ["h3"]}},
			{"type": "anytls", "tag": "custom-7", "server": "1.2.3.4", "server_port": 443, "password": "p", "tls": {"enabled": true, "server_name": "a.example.com"}},
			{"type": "urltest", "tag": "custom-8", "outbounds": ["custom-1", "custom-2", "direct"], "url": "https://www.gstatic.com/generate_204", "interval": "3m", "tolerance": 50}
		],
		"rules": [
			{"domain_suffix": ["openai.com", "chatgpt.com"], "outbound": "res-us"},
			{"domain_keyword": ["netflix"], "outbound": "custom-1"},
			{"ip_cidr": ["10.0.0.0/8"], "action": "reject"},
			{"domain": ["example.org"], "outbound": "direct"},
			{"domain_regex": ["^chatgpt-[a-z0-9]+\\.example\\.com$"], "outbound": "custom-8"},
			{"port": [443, 8443], "outbound": "custom-8"},
			{"port_range": ["8000:9000"], "outbound": "direct"},
			{"network": ["udp"], "action": "reject"},
			{"ip_is_private": true, "outbound": "direct"},
			{"action": "resolve"},
			{"ip_cidr": ["1.0.1.0/24"], "outbound": "custom-8"}
		],
		"final": "custom-2"
	}`), &request)
	return request
}

func TestRouterConfigSniffsFirstAndRoutesByRule(t *testing.T) {
	config, err := buildRouterConfig(sampleRouterRequest(), 40000, "router", "secret")
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	route := config["route"].(map[string]interface{})
	rules := route["rules"].([]interface{})
	if rules[0].(map[string]interface{})["action"] != "sniff" {
		t.Fatalf("first rule must sniff, got %v", rules[0])
	}
	first := rules[1].(map[string]interface{})
	if first["action"] != "route" || first["outbound"] != "res-us" {
		t.Fatalf("rule not converted to a route action: %v", first)
	}
	if rules[3].(map[string]interface{})["action"] != "reject" {
		t.Fatalf("reject rule lost: %v", rules[3])
	}
	if route["final"] != "custom-2" {
		t.Fatalf("final = %v", route["final"])
	}
	// direct is added when the panel does not send it.
	found := false
	for _, outbound := range config["outbounds"].([]interface{}) {
		if outbound.(map[string]interface{})["tag"] == "direct" {
			found = true
		}
	}
	if !found {
		t.Fatal("direct outbound missing")
	}
}

func TestRouterConfigRejectsUnsafeInput(t *testing.T) {
	cases := map[string]func(*proxyRouterRequest){
		"unknown type":     func(r *proxyRouterRequest) { r.Outbounds[0]["type"] = "selector" },
		"chained outbound": func(r *proxyRouterRequest) { r.Outbounds[1]["detour"] = "res-us" },
		"bad tag":          func(r *proxyRouterRequest) { r.Outbounds[0]["tag"] = "a b" },
		"duplicate tag":    func(r *proxyRouterRequest) { r.Outbounds[1]["tag"] = "res-us" },
		"unknown outbound": func(r *proxyRouterRequest) { r.Rules[0]["outbound"] = "missing" },
		"unknown field":    func(r *proxyRouterRequest) { r.Rules[0]["process_name"] = []interface{}{"x"} },
		"no match":         func(r *proxyRouterRequest) { r.Rules = append(r.Rules, map[string]interface{}{"outbound": "direct"}) },
		"unknown final":    func(r *proxyRouterRequest) { r.Final = "nope" },
		"group in group": func(r *proxyRouterRequest) {
			r.Outbounds = append(r.Outbounds, map[string]interface{}{"type": "urltest", "tag": "custom-9", "outbounds": []interface{}{"custom-8"}})
		},
		"group unknown member": func(r *proxyRouterRequest) { r.Outbounds[8]["outbounds"] = []interface{}{"missing"} },
		"group extra field":    func(r *proxyRouterRequest) { r.Outbounds[8]["default"] = "custom-1" },
		"group bad interval":   func(r *proxyRouterRequest) { r.Outbounds[8]["interval"] = "soon" },
		"group bad url":        func(r *proxyRouterRequest) { r.Outbounds[8]["url"] = "file:///etc/passwd" },
		"bad regex":            func(r *proxyRouterRequest) { r.Rules[4]["domain_regex"] = []interface{}{"(("} },
		"bad port":             func(r *proxyRouterRequest) { r.Rules[5]["port"] = []interface{}{float64(70000)} },
		"bad port range":       func(r *proxyRouterRequest) { r.Rules[6]["port_range"] = []interface{}{"1-2"} },
		"bad network":          func(r *proxyRouterRequest) { r.Rules[7]["network"] = []interface{}{"icmp"} },
		"resolve with match":   func(r *proxyRouterRequest) { r.Rules[9]["domain"] = []interface{}{"a.com"} },
	}
	for name, mutate := range cases {
		request := sampleRouterRequest()
		mutate(&request)
		if _, err := buildRouterConfig(request, 40000, "router", "secret"); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

// With SINGBOX_BIN pointing at sing-box, the generated config must pass `sing-box check`.
func TestRouterConfigPassesSingBoxCheck(t *testing.T) {
	binary := os.Getenv("SINGBOX_BIN")
	if binary == "" {
		t.Skip("SINGBOX_BIN not set")
	}
	config, err := buildRouterConfig(sampleRouterRequest(), 40000, "router", "secret")
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	path := filepath.Join(t.TempDir(), "router.json")
	content, _ := json.Marshal(config)
	if err := os.WriteFile(path, content, 0600); err != nil {
		t.Fatal(err)
	}
	if output, err := exec.Command(binary, "check", "-c", path).CombinedOutput(); err != nil {
		t.Fatalf("sing-box check failed: %v\n%s", err, strings.TrimSpace(string(output)))
	}
}
