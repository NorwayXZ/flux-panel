package socket

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/x/internal/net/dialer"
)

var testExitCA = newTestExitCertificate()

func newTestExitCertificate() string {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		panic(err)
	}
	template := x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "exit test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		panic(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
}

type fakeExitRunner struct {
	mu         sync.Mutex
	prepareErr error
	sessions   map[string]*fakeExitSession
	connectErr map[string]error
	exitIPs    map[string]string
	closeDelay time.Duration
	inUse      map[string]bool
	overlaps   int
}

type fakeExitSession struct {
	mu         sync.Mutex
	ip         string
	checkErr   error
	exited     chan struct{}
	closed     bool
	closeDelay time.Duration
	runner     *fakeExitRunner
	device     string
}

func newFakeExitRunner() *fakeExitRunner {
	return &fakeExitRunner{sessions: map[string]*fakeExitSession{}, connectErr: map[string]error{}, exitIPs: map[string]string{},
		inUse: map[string]bool{}}
}

func (r *fakeExitRunner) prepare() (string, error) { return "2.6.14", r.prepareErr }
func (r *fakeExitRunner) cleanup(string)           {}

func (r *fakeExitRunner) connect(_ context.Context, _, device string, spec exitMemberSpec) (exitSession, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if err := r.connectErr[spec.ID]; err != nil {
		return nil, err
	}
	if r.inUse[device] {
		r.overlaps++
	}
	r.inUse[device] = true
	session := &fakeExitSession{ip: r.exitIPs[spec.ID], exited: make(chan struct{}), closeDelay: r.closeDelay, runner: r, device: device}
	r.sessions[spec.ID] = session
	return session, nil
}

func (r *fakeExitRunner) session(id string) *fakeExitSession {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.sessions[id]
}

func (s *fakeExitSession) exitIP(context.Context) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ip == "" {
		return "", errors.New("no reply")
	}
	return s.ip, nil
}

func (s *fakeExitSession) check(context.Context) (time.Duration, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return 20 * time.Millisecond, s.checkErr
}

func (s *fakeExitSession) done() <-chan struct{}   { return s.exited }
func (s *fakeExitSession) traffic() (int64, int64) { return 10, 20 }
func (s *fakeExitSession) close() {
	time.Sleep(s.closeDelay)
	s.mu.Lock()
	s.closed = true
	s.mu.Unlock()
	s.runner.mu.Lock()
	s.runner.inUse[s.device] = false
	s.runner.mu.Unlock()
}

func testExitSpec(id, host string) exitMemberSpec {
	return exitMemberSpec{ID: id, Host: host, Port: 443, Proto: "tcp", Cipher: "AES-128-CBC", Auth: "SHA1", CA: testExitCA}
}

func newTestExitManager(t *testing.T, runner exitRunner) *exitPoolManager {
	t.Helper()
	manager := &exitPoolManager{directory: t.TempDir(), runner: runner, groups: make(map[string]*exitGroup),
		closing: make(map[string]chan struct{}), now: time.Now}
	t.Cleanup(manager.stopAll)
	return manager
}

func waitForExit(t *testing.T, description string, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", description)
}

func memberStatus(t *testing.T, manager *exitPoolManager, id string) exitMemberStatus {
	t.Helper()
	status, err := manager.status("jp")
	if err != nil {
		t.Fatal(err)
	}
	for _, member := range status.Members {
		if member.ID == id {
			return member
		}
	}
	return exitMemberStatus{}
}

func TestExitGroupUsesOnlyApprovedExits(t *testing.T) {
	runner := newFakeExitRunner()
	runner.exitIPs["a"] = "126.0.0.1"
	manager := newTestExitManager(t, runner)

	if _, err := manager.sync(exitPoolSyncRequest{Group: "jp", Members: []exitMemberSpec{testExitSpec("a", "60.1.1.1")}}); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "member a up", func() bool { return memberStatus(t, manager, "a").State == exitStateUp })
	if _, err := manager.activeDevice("jp"); !errors.Is(err, dialer.ErrExitUnavailable) {
		t.Fatalf("an exit the panel has not approved must not carry traffic, got %v", err)
	}

	status, err := manager.sync(exitPoolSyncRequest{Group: "jp", Members: []exitMemberSpec{testExitSpec("a", "60.1.1.1")}, ApprovedIPs: []string{"126.0.0.1"}})
	if err != nil {
		t.Fatal(err)
	}
	if status.ActiveID != "a" || status.ActiveExitIP != "126.0.0.1" {
		t.Fatalf("expected a to become active, got %+v", status)
	}
	device, err := manager.activeDevice("jp")
	if err != nil || device != exitDeviceName("jp", "a") {
		t.Fatalf("unexpected active device %q, %v", device, err)
	}
}

func TestExitGroupFailsOverAndStaysSticky(t *testing.T) {
	runner := newFakeExitRunner()
	runner.exitIPs["a"] = "126.0.0.1"
	runner.exitIPs["b"] = "126.0.0.2"
	manager := newTestExitManager(t, runner)
	request := exitPoolSyncRequest{Group: "jp", ApprovedIPs: []string{"126.0.0.1", "126.0.0.2"},
		Members: []exitMemberSpec{testExitSpec("a", "60.1.1.1"), testExitSpec("b", "60.1.1.2")}}
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "both members up", func() bool {
		return memberStatus(t, manager, "a").State == exitStateUp && memberStatus(t, manager, "b").State == exitStateUp
	})
	first, _ := manager.status("jp")
	if first.ActiveID == "" {
		t.Fatal("expected an active exit")
	}
	other := "a"
	if first.ActiveID == "a" {
		other = "b"
	}

	close(runner.session(first.ActiveID).exited)
	waitForExit(t, "failover", func() bool { status, _ := manager.status("jp"); return status.ActiveID == other })
	if failed := memberStatus(t, manager, first.ActiveID); failed.State != exitStateFailed || failed.Error == "" {
		t.Fatalf("expected the lost exit to be reported as failed, got %+v", failed)
	}

	// Re-sending the same list must not move traffic off a working exit.
	if status, err := manager.sync(request); err != nil || status.ActiveID != other {
		t.Fatalf("active exit changed without a failure: %+v, %v", status, err)
	}

	close(runner.session(other).exited)
	waitForExit(t, "no exit left", func() bool { status, _ := manager.status("jp"); return status.ActiveID == "" })
	if _, err := manager.activeDevice("jp"); !errors.Is(err, dialer.ErrExitUnavailable) {
		t.Fatalf("with no exit left connections must fail, got %v", err)
	}
}

func TestExitGroupReportsConnectFailureAndRemoval(t *testing.T) {
	runner := newFakeExitRunner()
	runner.connectErr["a"] = errors.New("connection timed out")
	runner.exitIPs["b"] = "126.0.0.2"
	manager := newTestExitManager(t, runner)
	request := exitPoolSyncRequest{Group: "jp", ApprovedIPs: []string{"126.0.0.2"},
		Members: []exitMemberSpec{testExitSpec("a", "60.1.1.1"), testExitSpec("b", "60.1.1.2")}}
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "a failed and b active", func() bool {
		status, _ := manager.status("jp")
		return status.ActiveID == "b" && memberStatus(t, manager, "a").State == exitStateFailed
	})
	if message := memberStatus(t, manager, "a").Error; !strings.Contains(message, "timed out") {
		t.Fatalf("expected the failure reason to be reported, got %q", message)
	}

	request.Members = request.Members[:1]
	status, err := manager.sync(request)
	if err != nil {
		t.Fatal(err)
	}
	if len(status.Members) != 1 || status.ActiveID != "" {
		t.Fatalf("removing the active member must leave no active exit, got %+v", status)
	}
	waitForExit(t, "removed session closed", func() bool {
		session := runner.session("b")
		session.mu.Lock()
		defer session.mu.Unlock()
		return session.closed
	})
	if err := manager.delete("jp"); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.activeDevice("jp"); !errors.Is(err, dialer.ErrExitUnavailable) {
		t.Fatalf("a deleted group must not resolve, got %v", err)
	}
}

func TestExitGroupRetriesAFailedMemberOnANewAttempt(t *testing.T) {
	runner := newFakeExitRunner()
	runner.connectErr["a"] = errors.New("OpenVPN is not ready")
	runner.exitIPs["a"] = "126.0.0.1"
	manager := newTestExitManager(t, runner)
	spec := testExitSpec("a", "60.1.1.1")
	spec.Attempt = 1
	request := exitPoolSyncRequest{Group: "jp", ApprovedIPs: []string{"126.0.0.1"}, Members: []exitMemberSpec{spec}}
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "first attempt failed", func() bool { return memberStatus(t, manager, "a").State == exitStateFailed })

	runner.mu.Lock()
	delete(runner.connectErr, "a")
	runner.mu.Unlock()
	// The same request again must not restart anything: the panel has not asked for a retry.
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	time.Sleep(50 * time.Millisecond)
	if state := memberStatus(t, manager, "a").State; state != exitStateFailed {
		t.Fatalf("a failed member must stay failed until a new attempt is requested, got %s", state)
	}

	request.Members[0].Attempt = 2
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "second attempt active", func() bool { status, _ := manager.status("jp"); return status.ActiveID == "a" })
}

func TestExitGroupWaitsForTheOldTunnelBeforeReusingItsDevice(t *testing.T) {
	runner := newFakeExitRunner()
	runner.closeDelay = 150 * time.Millisecond
	runner.exitIPs["a"] = "126.0.0.1"
	manager := newTestExitManager(t, runner)
	spec := testExitSpec("a", "60.1.1.1")
	spec.Attempt = 1
	request := exitPoolSyncRequest{Group: "jp", ApprovedIPs: []string{"126.0.0.1"}, Members: []exitMemberSpec{spec}}
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "first attempt active", func() bool { status, _ := manager.status("jp"); return status.ActiveID == "a" })
	first := runner.session("a")

	// A new attempt reuses the device name while the old tunnel is still shutting down.
	request.Members[0].Attempt = 2
	if _, err := manager.sync(request); err != nil {
		t.Fatal(err)
	}
	waitForExit(t, "second attempt active", func() bool {
		status, _ := manager.status("jp")
		return status.ActiveID == "a" && runner.session("a") != first
	})
	runner.mu.Lock()
	overlaps := runner.overlaps
	runner.mu.Unlock()
	if overlaps != 0 {
		t.Fatalf("a new tunnel started on a device that was still in use %d time(s)", overlaps)
	}
}

func TestExitGroupRestoresSavedMembers(t *testing.T) {
	runner := newFakeExitRunner()
	runner.exitIPs["a"] = "126.0.0.1"
	manager := newTestExitManager(t, runner)
	if _, err := manager.sync(exitPoolSyncRequest{Group: "jp", ApprovedIPs: []string{"126.0.0.1"},
		Members: []exitMemberSpec{testExitSpec("a", "60.1.1.1")}}); err != nil {
		t.Fatal(err)
	}

	restored := &exitPoolManager{directory: manager.directory, runner: runner, groups: make(map[string]*exitGroup),
		closing: make(map[string]chan struct{}), now: time.Now}
	t.Cleanup(restored.stopAll)
	restored.restore()
	waitForExit(t, "restored exit active", func() bool { status, _ := restored.status("jp"); return status.ActiveID == "a" })
}

func TestNormalizeExitMemberRejectsUnsafeInput(t *testing.T) {
	valid := testExitSpec("a", "60.1.1.1")
	if _, err := normalizeExitMember(valid); err != nil {
		t.Fatalf("valid member rejected: %v", err)
	}
	cases := map[string]func(*exitMemberSpec){
		"host name":         func(s *exitMemberSpec) { s.Host = "example.com" },
		"private host":      func(s *exitMemberSpec) { s.Host = "10.0.0.1" },
		"host with newline": func(s *exitMemberSpec) { s.Host = "60.1.1.1\nup /bin/sh" },
		"protocol":          func(s *exitMemberSpec) { s.Proto = "tcp\nscript-security 3" },
		"cipher":            func(s *exitMemberSpec) { s.Cipher = "AES-128-CBC\nup /tmp/x" },
		"auth":              func(s *exitMemberSpec) { s.Auth = "none" },
		"port":              func(s *exitMemberSpec) { s.Port = 70000 },
		"id":                func(s *exitMemberSpec) { s.ID = "../a" },
		"directive in CA":   func(s *exitMemberSpec) { s.CA = testExitCA + "\nscript-security 3\nup /bin/sh" },
		"cert without key":  func(s *exitMemberSpec) { s.Cert = testExitCA },
		"missing CA":        func(s *exitMemberSpec) { s.CA = "" },
	}
	for name, mutate := range cases {
		spec := valid
		mutate(&spec)
		if _, err := normalizeExitMember(spec); err == nil {
			t.Errorf("%s: expected the member to be rejected", name)
		}
	}
	if _, err := normalizeExitPoolRequest(exitPoolSyncRequest{Group: "JP/../x"}); err == nil {
		t.Error("expected an invalid group name to be rejected")
	}
	if _, err := normalizeExitPoolRequest(exitPoolSyncRequest{Group: "jp", ApprovedIPs: []string{"not-an-ip"}}); err == nil {
		t.Error("expected an invalid approved address to be rejected")
	}
}

func TestExitTunnelConfigNeverExecutesPushedSettings(t *testing.T) {
	spec, err := normalizeExitMember(testExitSpec("a", "60.1.1.1"))
	if err != nil {
		t.Fatal(err)
	}
	config := exitTunnelConfig(spec, "cnx0123456789", "/etc/gost/exit-runtime/cnx0123456789.sock", [2]int{2, 6})
	for _, expected := range []string{"route-nopull", "route-noexec", "ifconfig-noexec", "script-security 1", "remap-usr1 SIGTERM",
		"proto tcp4-client", "remote 60.1.1.1 443", "data-ciphers AES-256-GCM:AES-128-GCM:AES-128-CBC",
		"data-ciphers-fallback AES-128-CBC", "disable-dco", `pull-filter ignore "redirect-gateway"`} {
		if !strings.Contains(config, expected+"\n") {
			t.Errorf("configuration is missing %q", expected)
		}
	}
	for _, forbidden := range []string{"\nup ", "\ndown ", "route-up", "plugin", "script-security 2", "redirect-gateway def1"} {
		if strings.Contains(config, forbidden) {
			t.Errorf("configuration must not contain %q", forbidden)
		}
	}
	if older := exitTunnelConfig(spec, "cnx0123456789", "x.sock", [2]int{2, 5}); strings.Contains(older, "disable-dco") {
		t.Error("disable-dco is not understood by OpenVPN 2.5")
	}
}

func TestParseExitManagementState(t *testing.T) {
	state, local, ok := parseExitManagementState(">STATE:1791862683,CONNECTED,SUCCESS,10.211.1.69,60.137.217.108,1648,,")
	if !ok || state != "CONNECTED" || local != "10.211.1.69" {
		t.Fatalf("unexpected result %q %q %v", state, local, ok)
	}
	state, local, ok = parseExitManagementState("1791862683,CONNECTED,SUCCESS,10.211.1.69,60.137.217.108,1648,,\r")
	if !ok || state != "CONNECTED" || local != "10.211.1.69" {
		t.Fatalf("unexpected result for a command reply %q %q %v", state, local, ok)
	}
	if state, _, ok := parseExitManagementState(">STATE:1791862683,EXITING,SIGTERM,,,,,"); !ok || state != "EXITING" {
		t.Fatalf("expected EXITING, got %q %v", state, ok)
	}
	for _, line := range []string{">INFO:OpenVPN Management Interface Version 5", "SUCCESS: real-time state notification set to ON", "END", ""} {
		if _, _, ok := parseExitManagementState(line); ok {
			t.Errorf("%q is not a state line", line)
		}
	}
}

func TestValidExitTunnelAddress(t *testing.T) {
	_, lan, _ := net.ParseCIDR("10.0.2.0/24")
	if ip, err := validExitTunnelAddress("10.211.1.69", []*net.IPNet{lan}); err != nil || ip.String() != "10.211.1.69" {
		t.Fatalf("expected a private tunnel address to be accepted, got %v %v", ip, err)
	}
	for _, value := range []string{"8.8.8.8", "10.0.2.99", "", "fe80::1", "0.0.0.0"} {
		if _, err := validExitTunnelAddress(value, []*net.IPNet{lan}); err == nil {
			t.Errorf("expected %q to be rejected", value)
		}
	}
}

func TestParseOpenVPNVersion(t *testing.T) {
	version, text, err := parseOpenVPNVersion("OpenVPN 2.6.14 aarch64-unknown-linux-gnu [SSL (OpenSSL)] [LZO]")
	if err != nil || version != [2]int{2, 6} || text != "2.6.14" {
		t.Fatalf("unexpected result %v %q %v", version, text, err)
	}
	if _, _, err := parseOpenVPNVersion("command not found"); err == nil {
		t.Error("expected an error for unrelated output")
	}
}

func TestExitDeviceNameFitsInterfaceLimit(t *testing.T) {
	name := exitDeviceName("jp", strings.Repeat("x", 64))
	if len(name) != 13 || !strings.HasPrefix(name, "cnx") {
		t.Fatalf("unexpected device name %q", name)
	}
	if name == exitDeviceName("kr", strings.Repeat("x", 64)) {
		t.Error("device names must differ between groups")
	}
}
