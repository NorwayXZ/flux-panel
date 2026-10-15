package relay

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/connector"
	"github.com/go-gost/core/observer/stats"
	wire "github.com/go-gost/relay"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	serviceparser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/http"
	relayconnector "github.com/go-gost/x/connector/relay"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/tls"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/tls"
	xlogger "github.com/go-gost/x/logger"
	xmetadata "github.com/go-gost/x/metadata"
	"github.com/go-gost/x/registry"
	xservice "github.com/go-gost/x/service"
)

// Runs production configuration loading, relay handlers/connectors, TLS, chains,
// listeners, stats and limiters in independent processes. Only final target dials
// map test public addresses to loopback echo fixtures; CNIX mapping is a real TCP
// transparent proxy with a port different from the shared listener.
func TestSharedRuntimeWorker(t *testing.T) {
	path := os.Getenv("CNIX_RUNTIME_CONFIG")
	if path == "" {
		return
	}
	var mappings map[string]string
	if err := json.Unmarshal([]byte(os.Getenv("CNIX_RUNTIME_TARGETS")), &mappings); err != nil {
		panic(err)
	}
	sharedResolveTarget = func(ctx context.Context, host string) ([]net.IP, error) {
		if host == "landing.test" {
			return []net.IP{net.ParseIP("203.0.113.3")}, nil
		}
		return []net.IP{net.ParseIP(host)}, nil
	}
	sharedDialTarget = func(ctx context.Context, address string) (net.Conn, error) {
		fixture, ok := mappings[address]
		if !ok {
			return nil, fmt.Errorf("unexpected target %s", address)
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", fixture)
	}
	content, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	var cfg config.Config
	if err = json.Unmarshal(content, &cfg); err != nil {
		panic(err)
	}
	if err = loader.Load(&cfg); err != nil {
		panic(err)
	}
	config.Set(&cfg)
	for _, service := range registry.ServiceRegistry().GetAll() {
		go service.Serve()
	}
	fmt.Println("CNIX:ready")
	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		var command struct {
			Action string
			Name   string
			Grants []SharedGrant
		}
		if err := json.Unmarshal(scanner.Bytes(), &command); err != nil {
			panic(err)
		}
		response := map[string]any{"ok": true}
		switch command.Action {
		case "grants":
			if err := SharedGrants(command.Name, command.Grants); err != nil {
				response["error"] = err.Error()
			} else {
				config.OnUpdate(func(c *config.Config) error {
					for _, s := range c.Services {
						if s.Name == command.Name {
							s.Handler.Metadata["cnix.grants"] = command.Grants
						}
					}
					return nil
				})
			}
		case "delete", "pause":
			if service := registry.ServiceRegistry().Get(command.Name); service != nil {
				service.Close()
				registry.ServiceRegistry().Unregister(command.Name)
			}
			if command.Action == "delete" {
				config.OnUpdate(func(c *config.Config) error {
					var keep []*config.ServiceConfig
					for _, s := range c.Services {
						if s.Name != command.Name {
							keep = append(keep, s)
						}
					}
					c.Services = keep
					return nil
				})
			}
		case "resume":
			for _, s := range config.Global().Services {
				if s.Name == command.Name {
					service, err := serviceparser.ParseService(s)
					if err != nil {
						response["error"] = err.Error()
						break
					}
					registry.ServiceRegistry().Register(s.Name, service)
					go service.Serve()
				}
			}
		case "stats":
			values := map[string]any{}
			for name, service := range registry.ServiceRegistry().GetAll() {
				if status, ok := service.(interface{ Status() *xservice.Status }); ok {
					st := status.Status().Stats()
					values[name] = map[string]uint64{"in": st.Get(stats.KindInputBytes), "out": st.Get(stats.KindOutputBytes)}
				}
			}
			response["stats"] = values
		}
		var saved bytes.Buffer
		config.Global().Write(&saved, "json")
		if err := os.WriteFile(path, saved.Bytes(), 0600); err != nil {
			panic(err)
		}
		data, _ := json.Marshal(response)
		fmt.Println("CNIX:" + string(data))
	}
	os.Exit(0)
}

type sharedWorker struct {
	cmd *exec.Cmd
	in  io.WriteCloser
	out *bufio.Scanner
	log *os.File
}

func startSharedWorker(t *testing.T, path string, mapping map[string]string) *sharedWorker {
	t.Helper()
	encoded, _ := json.Marshal(mapping)
	cmd := exec.Command(os.Args[0], "-test.run=^TestSharedRuntimeWorker$")
	cmd.Env = append(os.Environ(), "CNIX_RUNTIME_CONFIG="+path, "CNIX_RUNTIME_TARGETS="+string(encoded))
	cmd.Dir = filepath.Dir(path)
	input, _ := cmd.StdinPipe()
	output, _ := cmd.StdoutPipe()
	logs, err := os.Create(path + ".log")
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = logs
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	worker := &sharedWorker{cmd: cmd, in: input, out: bufio.NewScanner(output), log: logs}
	if line := worker.read(t); line != "ready" {
		t.Fatal(line)
	}
	return worker
}
func (w *sharedWorker) read(t *testing.T) string {
	t.Helper()
	for w.out.Scan() {
		if strings.HasPrefix(w.out.Text(), "CNIX:") {
			return strings.TrimPrefix(w.out.Text(), "CNIX:")
		}
	}
	content, _ := os.ReadFile(w.log.Name())
	t.Fatalf("worker stopped: %s", content)
	return ""
}
func (w *sharedWorker) call(t *testing.T, command any) map[string]any {
	t.Helper()
	data, _ := json.Marshal(command)
	w.in.Write(append(data, '\n'))
	var result map[string]any
	if err := json.Unmarshal([]byte(w.read(t)), &result); err != nil {
		t.Fatal(err)
	}
	if result["error"] != nil {
		t.Fatal(result)
	}
	return result
}
func (w *sharedWorker) stop() { w.cmd.Process.Kill(); w.cmd.Wait(); w.log.Close() }
func freeSharedAddr(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().String()
}
func sharedCert(t *testing.T) (string, string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "cnix-test.invalid"}, DNSNames: []string{"cnix-test.invalid"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	private, _ := x509.MarshalPKCS8PrivateKey(key)
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private}))
}

func TestCNIXSharedThreeTargetsLifecycle(t *testing.T) {
	directory := t.TempDir()
	cert, key := sharedCert(t)
	exitAddress := freeSharedAddr(t)
	var fixtures []net.Listener
	mapping := map[string]string{}
	var grants []SharedGrant
	var targetAddresses []string
	for i := 0; i < 3; i++ {
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		fixtures = append(fixtures, ln)
		defer ln.Close()
		label := byte('A' + i)
		go func() {
			for {
				conn, err := ln.Accept()
				if err != nil {
					return
				}
				go func() { defer conn.Close(); conn.Write([]byte{label}); io.Copy(conn, conn) }()
			}
		}()
		target := fmt.Sprintf("203.0.113.%d:2345", i+1)
		mapping[target] = ln.Addr().String()
		if i == 2 {
			target = "landing.test:2345"
		}
		targetAddresses = append(targetAddresses, target)
		grants = append(grants, SharedGrant{Username: string(label), Password: strings.Repeat(string(label), 40), Targets: []string{target}})
	}
	exitPath := filepath.Join(directory, "exit.json")
	exitConfig := map[string]any{"log": map[string]string{"level": "error", "output": "stderr"}, "services": []any{map[string]any{"name": "cnixshared-test", "addr": exitAddress, "handler": map[string]any{"type": "relay", "auth": map[string]string{"username": "cnix-disabled-fallback", "password": strings.Repeat("z", 40)}, "metadata": map[string]any{"cnix.shared": true, "cnix.grants": grants, "nodelay": true}}, "listener": map[string]any{"type": "tls", "tls": map[string]any{"certPem": cert, "keyPem": key}}}}}
	saveSharedFixture(t, exitPath, exitConfig)
	exit := startSharedWorker(t, exitPath, mapping)
	defer func() { exit.stop() }()
	proxy, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	external := proxy.Addr().String()
	if external == exitAddress {
		t.Fatal("mapping must use different ports")
	}
	go func() {
		for {
			client, err := proxy.Accept()
			if err != nil {
				return
			}
			go func() {
				defer client.Close()
				remote, err := net.DialTimeout("tcp", exitAddress, time.Second)
				if err != nil {
					return
				}
				defer remote.Close()
				done := make(chan struct{}, 1)
				go func() { io.Copy(remote, client); done <- struct{}{} }()
				io.Copy(client, remote)
			}()
		}
	}()
	var services []any
	var chains []any
	var entries []string
	for i, grant := range grants {
		addr := freeSharedAddr(t)
		entries = append(entries, addr)
		name := "business" + grant.Username
		chains = append(chains, map[string]any{"name": name + "_chains", "hops": []any{map[string]any{"name": name + "_hop", "nodes": []any{map[string]any{"name": name + "_node", "addr": external, "connector": map[string]any{"type": "relay", "auth": map[string]string{"username": grant.Username, "password": grant.Password}, "metadata": map[string]any{"nodelay": true}}, "dialer": map[string]any{"type": "tls", "tls": map[string]any{"secure": true, "caPem": cert, "serverName": "cnix-test.invalid"}}}}}}})
		service := map[string]any{"name": name, "addr": addr, "handler": map[string]any{"type": "tcp", "chain": name + "_chains", "metadata": map[string]any{"cnix.remoteResolution": true}}, "listener": map[string]string{"type": "tcp"}, "forwarder": map[string]any{"nodes": []any{map[string]string{"name": "target", "addr": targetAddresses[i]}}}}
		if i == 1 {
			service["limiter"] = "B-limit"
		}
		services = append(services, service)
	}
	ingressPath := filepath.Join(directory, "ingress.json")
	saveSharedFixture(t, ingressPath, map[string]any{"log": map[string]string{"level": "error", "output": "stderr"}, "services": services, "chains": chains, "limiters": []any{map[string]any{"name": "B-limit", "limits": []string{"$ 32KB 32KB"}}}})
	ingress := startSharedWorker(t, ingressPath, mapping)
	defer func() { ingress.stop() }()
	var wg sync.WaitGroup
	var durations [3]time.Duration
	errorsCh := make(chan error, 3)
	for i := 0; i < 3; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			start := time.Now()
			errorsCh <- sharedExchange(entries[i], byte('A'+i), bytes.Repeat([]byte{byte(13 + i)}, 64*1024))
			durations[i] = time.Since(start)
		}(i)
	}
	wg.Wait()
	if durations[1] < time.Second || durations[0] >= durations[1] || durations[2] >= durations[1] {
		t.Fatalf("B limiter must independently throttle: %v", durations)
	}
	close(errorsCh)
	for err := range errorsCh {
		if err != nil {
			t.Fatal(err)
		}
	}
	values := ingress.call(t, map[string]any{"Action": "stats"})["stats"].(map[string]any)
	// A client can receive the last bytes before the service's Write wrapper has
	// returned and added them to its counter. Wait for that observable state;
	// keep the exact independent totals rather than weakening the assertion.
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
		complete := true
		for _, name := range []string{"businessA", "businessB", "businessC"} {
			st := values[name].(map[string]any)
			complete = complete && st["in"].(float64) == 65536 && st["out"].(float64) == 65537
		}
		if complete {
			break
		}
		time.Sleep(10 * time.Millisecond)
		values = ingress.call(t, map[string]any{"Action": "stats"})["stats"].(map[string]any)
	}
	for _, name := range []string{"businessA", "businessB", "businessC"} {
		st := values[name].(map[string]any)
		if st["in"].(float64) != 65536 || st["out"].(float64) != 65537 {
			t.Fatalf("independent counters %s: %v", name, st)
		}
	}
	t.Log("three targets delivered distinct markers and 64 KiB each direction; independent exact counters verified; mapped ports differ")
	// A live A connection must survive B credential updates and removal.
	live, err := net.Dial("tcp", entries[0])
	if err != nil {
		t.Fatal(err)
	}
	defer live.Close()
	live.SetDeadline(time.Now().Add(10 * time.Second))
	var marker [1]byte
	if _, err = io.ReadFull(live, marker[:]); err != nil {
		t.Fatal(err)
	}
	ingress.call(t, map[string]any{"Action": "pause", "Name": "businessB"})
	exit.call(t, map[string]any{"Action": "grants", "Name": "cnixshared-test", "Grants": []SharedGrant{grants[0], grants[2]}})
	if conn, err := net.DialTimeout("tcp", entries[1], 200*time.Millisecond); err == nil {
		conn.Close()
		t.Fatal("paused B accepted connection")
	}
	live.Write([]byte("A-survives"))
	buffer := make([]byte, 10)
	if _, err = io.ReadFull(live, buffer); err != nil || string(buffer) != "A-survives" {
		t.Fatal(err, string(buffer))
	}
	ingress.call(t, map[string]any{"Action": "delete", "Name": "businessB"})
	if err = sharedExchange(entries[2], 'C', []byte("C-after-B-delete")); err != nil {
		t.Fatal(err)
	}
	// Persisted state restores in separate processes, without panel assistance.
	ingress.stop()
	ingress = startSharedWorker(t, ingressPath, mapping)
	for _, i := range []int{0, 2} {
		if err = sharedExchange(entries[i], byte('A'+i), []byte("after-ingress-restart")); err != nil {
			t.Fatal(err)
		}
	}
	exit.stop()
	exit = startSharedWorker(t, exitPath, mapping)
	for _, i := range []int{0, 2} {
		if err = sharedExchange(entries[i], byte('A'+i), []byte("after-exit-restart")); err != nil {
			t.Fatal(err)
		}
	}
	roots := x509.NewCertPool()
	roots.AppendCertsFromPEM([]byte(cert))
	for _, test := range []struct{ user, password, target string }{{"", "", targetAddresses[0]}, {"A", "wrong", targetAddresses[0]}, {"B", grants[1].Password, targetAddresses[1]}, {"A", grants[0].Password, targetAddresses[2]}} {
		conn, err := tls.Dial("tcp", external, &tls.Config{RootCAs: roots, ServerName: "cnix-test.invalid"})
		if err != nil {
			t.Fatal(err)
		}
		conn.SetDeadline(time.Now().Add(time.Second))
		relayClient := relayconnector.NewConnector(connector.AuthOption(url.UserPassword(test.user, test.password)), connector.LoggerOption(xlogger.Nop()))
		relayClient.Init(xmetadata.NewMetadata(map[string]any{"nodelay": true}))
		if _, err = relayClient.Connect(context.Background(), conn, "tcp", test.target); err == nil {
			t.Fatal("unauthorized connect accepted")
		}
		conn.Close()
	}
	for _, command := range []wire.CmdType{wire.CmdBind, wire.CmdConnect | wire.FUDP, 0} {
		connection, err := tls.Dial("tcp", external, &tls.Config{RootCAs: roots, ServerName: "cnix-test.invalid"})
		if err != nil {
			t.Fatal(err)
		}
		connection.SetDeadline(time.Now().Add(time.Second))
		request := wire.Request{Version: wire.Version1, Cmd: command, Features: []wire.Feature{&wire.UserAuthFeature{Username: "A", Password: grants[0].Password}}}
		if _, err = request.WriteTo(connection); err != nil {
			t.Fatal(err)
		}
		var response wire.Response
		if _, err = response.ReadFrom(connection); err != nil {
			t.Fatal(err)
		}
		if response.Status == wire.StatusOK {
			t.Fatal("unsupported relay command accepted")
		}
		connection.Close()
	}
	if connection, err := tls.Dial("tcp", external, &tls.Config{ServerName: "cnix-test.invalid"}); err == nil {
		connection.Close()
		t.Fatal("untrusted certificate accepted")
	}
	proxy.Close()
	if err = sharedExchange(entries[0], 'A', []byte("must-not-bypass")); err == nil {
		t.Fatal("CNIX unavailable but ingress bypassed it")
	}
	t.Log("B pause/delete isolated; A live stream preserved; ingress and exit process restarts restored A/C; auth denial and fail-closed verified")
}

func saveSharedFixture(t *testing.T, path string, value any) {
	t.Helper()
	encoded, _ := json.Marshal(value)
	if err := os.WriteFile(path, encoded, 0600); err != nil {
		t.Fatal(err)
	}
}
func sharedExchange(address string, label byte, payload []byte) error {
	conn, err := net.DialTimeout("tcp", address, time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	done := make(chan error, 1)
	go func() { _, err := conn.Write(payload); done <- err }()
	reply := make([]byte, len(payload)+1)
	if _, err = io.ReadFull(conn, reply); err != nil {
		return err
	}
	if reply[0] != label || !bytes.Equal(reply[1:], payload) {
		return fmt.Errorf("target or payload mismatch")
	}
	return <-done
}
