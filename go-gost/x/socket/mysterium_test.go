package socket

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestPickProvidersSkipsExcludedAndNonResidential(t *testing.T) {
	var proposals []mysteriumProposal
	_ = json.Unmarshal([]byte(`[
		{"provider_id":"0xA","location":{"country":"US","ip_type":"residential"},"quality":{"quality":2.1,"bandwidth":50}},
		{"provider_id":"0xB","location":{"country":"US","ip_type":"hosting"},"quality":{"quality":3,"bandwidth":900}},
		{"provider_id":"0xC","location":{"country":"US","ip_type":"residential"},"quality":{"quality":2.9,"bandwidth":10}},
		{"provider_id":"0xD","location":{"country":"US","ip_type":"residential"},"quality":{"quality":2.9,"bandwidth":80}}
	]`), &proposals)
	picked := pickProviders(proposals, []string{"0xd"}, 4)
	ids := []string{}
	for _, p := range picked {
		ids = append(ids, p.ProviderID)
	}
	if strings.Join(ids, ",") != "0xC,0xA" {
		t.Fatalf("picked %v", ids)
	}
}

// fakeTequilapi answers the calls the manager makes and serves an HTTP proxy
// that returns a Cloudflare-style trace for the exit check.
func fakeTequilapi(t *testing.T) (*mysteriumManager, *[]string) {
	var mu sync.Mutex
	calls := []string{}
	connected := map[string]bool{}
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		calls = append(calls, r.Method+" "+r.URL.Path)
		mu.Unlock()
		switch {
		case r.URL.Path == "/healthcheck":
			w.Write([]byte(`{}`))
		case r.URL.Path == "/identities/current":
			w.Write([]byte(`{"id":"0xabc"}`))
		case r.URL.Path == "/identities/0xabc" && r.Method == http.MethodGet:
			w.Write([]byte(`{"registration_status":"Registered","channel_address":"0xchan","balance_tokens":{"ether":"78.25"}}`))
		case r.URL.Path == "/proposals":
			if r.URL.Query().Get("location_country") != "US" || r.URL.Query().Get("ip_type") != "residential" {
				t.Errorf("unexpected proposal query %s", r.URL.RawQuery)
			}
			w.Write([]byte(`{"proposals":[{"provider_id":"0xbad","location":{"country":"US","ip_type":"residential","city":"X","isp":"Bad"},"quality":{"quality":3}},
				{"provider_id":"0xfr","location":{"country":"FR","ip_type":"residential"},"quality":{"quality":3.5}},
				{"provider_id":"0xgood","location":{"country":"US","ip_type":"residential","city":"Dormont","isp":"Verizon"},"quality":{"quality":2}}]}`))
		case r.URL.Path == "/connection" && r.Method == http.MethodPut:
			var body struct {
				ProviderID     string `json:"provider_id"`
				ConnectOptions struct {
					ProxyPort int `json:"proxy_port"`
				} `json:"connect_options"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body.ProviderID == "0xbad" {
				w.WriteHeader(http.StatusInternalServerError)
				w.Write([]byte(`{"error":{"message":"provider unreachable"}}`))
				return
			}
			connected[body.ProviderID] = true
			w.WriteHeader(http.StatusCreated)
			w.Write([]byte(`{"status":"Connected"}`))
		case r.URL.Path == "/connection" && r.Method == http.MethodGet:
			w.Write([]byte(`{"status":"Connected","session_id":"s1","proposal":{"provider_id":"0xgood"}}`))
		case r.URL.Path == "/connection/traffic":
			w.Write([]byte(`{"bytes_received":1048576,"bytes_sent":1024}`))
		case r.URL.Path == "/v2/identities/0xabc/paypal/payment-order":
			w.Write([]byte(`{"id":"o1","pay_amount":"8.19","pay_currency":"USD","receive_myst":"79.36","public_gateway_data":{"checkout_url":"https://www.paypal.com/checkoutnow?token=T"}}`))
		default:
			w.Write([]byte(`{}`))
		}
	}))
	t.Cleanup(api.Close)
	dir := t.TempDir()
	_ = os.MkdirAll(filepath.Join(dir, "data", "keystore"), 0700)
	m := &mysteriumManager{directory: dir, binary: filepath.Join(dir, "bin", "myst"), api: api.URL, client: api.Client(), wanted: map[int]mysteriumConnection{}}
	return m, &calls
}

func startTraceProxy(t *testing.T) int {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "fl=1\nip=173.75.12.161\nloc=US\n")
	})}
	go server.Serve(listener)
	t.Cleanup(func() { server.Close() })
	return listener.Addr().(*net.TCPAddr).Port
}

func TestMysteriumConnectFallsBackAndRemembersTheConnection(t *testing.T) {
	m, calls := fakeTequilapi(t)
	// A running fake daemon: ensureRunning only checks health.
	m.cmd = selfProcess(t)
	port := startTraceProxy(t)
	original := mysteriumTraceURL
	mysteriumTraceURL = "http://trace.example/cdn-cgi/trace"
	defer func() { mysteriumTraceURL = original }()

	result, err := m.connect(mysteriumConnection{ProxyPort: port, Country: "us"})
	if err != nil {
		t.Fatalf("connect: %v (calls %v)", err, *calls)
	}
	if result.ProviderID != "0xgood" || result.ExitIP != "173.75.12.161" || result.ISP != "Verizon" {
		t.Fatalf("result %+v", result)
	}
	if m.wanted[port].Country != "US" {
		t.Fatal("connection not remembered for restore")
	}
	if _, err := os.Stat(m.statePath()); err != nil {
		t.Fatal("state file missing")
	}

	account, err := m.status(nil)
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if account.Balance != 78.25 || account.Identity != "0xabc" || len(account.Connections) != 1 || account.Connections[0].BytesReceived != 1048576 {
		t.Fatalf("status %+v", account)
	}

	order, err := m.topup(mysteriumTopupRequest{Gateway: "paypal", AmountUSD: "6.30", Country: "CN"})
	if err != nil || order["paymentUrl"] != "https://www.paypal.com/checkoutnow?token=T" {
		t.Fatalf("topup %v %v", order, err)
	}
	if _, err := m.topup(mysteriumTopupRequest{Gateway: "bank"}); err == nil {
		t.Fatal("unknown gateway accepted")
	}

	if err := m.disconnect(port); err != nil {
		t.Fatalf("disconnect: %v", err)
	}
	if len(m.wanted) != 0 {
		t.Fatal("disconnect kept the connection")
	}
}

// selfProcess returns a started command that stays alive for the test, so the
// manager treats its daemon as running.
func selfProcess(t *testing.T) *exec.Cmd {
	cmd := exec.Command("sleep", "60")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _, _ = cmd.Process.Wait() })
	return cmd
}

func TestMysteriumDaemonHomeWhenServiceHasNoHome(t *testing.T) {
	current, err := user.Current()
	if err != nil || current.HomeDir == "" {
		t.Skip("OS account home is unavailable")
	}
	t.Setenv("HOME", "")
	env := mysteriumDaemonEnv()
	homes := []string{}
	for _, item := range env {
		if strings.HasPrefix(item, "HOME=") {
			homes = append(homes, item)
		}
	}
	if len(homes) != 1 || homes[0] != "HOME="+current.HomeDir {
		t.Fatalf("child HOME: %v", homes)
	}
	if os.Getenv("HOME") != "" {
		t.Fatal("Agent environment was changed")
	}
	cmd := exec.Command("sh", "-c", `test -n "$HOME" && test -d "$HOME"`)
	cmd.Env = env
	if err := cmd.Run(); err != nil {
		t.Fatalf("child cannot resolve its home: %v", err)
	}
}

func TestMysteriumStartupErrorIncludesBoundedLogTail(t *testing.T) {
	m := &mysteriumManager{directory: t.TempDir()}
	if err := os.MkdirAll(filepath.Join(m.directory, "logs"), 0700); err != nil {
		t.Fatal(err)
	}
	content := strings.Repeat("old log\n", 2000) + `Failed to create command: $HOME is not defined`
	if err := os.WriteFile(filepath.Join(m.directory, "logs", "daemon.out"), []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	detail := m.startupLog()
	if len(detail) > 2048 || !strings.Contains(detail, "$HOME is not defined") {
		t.Fatalf("unexpected startup detail: %q", detail)
	}
}
