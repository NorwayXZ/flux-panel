package dnsdecision

import (
	"context"
	"github.com/miekg/dns"
	"net"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestRealDNSMasqReproducesReloadFailureAndVerifiesRepair(t *testing.T) {
	binary := os.Getenv("FLUX_DNSMASQ_TEST_BINARY")
	if binary == "" {
		t.Skip("Set FLUX_DNSMASQ_TEST_BINARY to run the real dnsmasq integration test")
	}
	directory := t.TempDir()
	confdir := filepath.Join(directory, "conf.d")
	if err := os.Mkdir(confdir, 0755); err != nil {
		t.Fatal(err)
	}
	state := filepath.Join(directory, "state.json")
	if err := os.WriteFile(filepath.Join(directory, "dnsmasq-confdir"), []byte(confdir), 0600); err != nil {
		t.Fatal(err)
	}
	portListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := portListener.Addr().(*net.TCPAddr).Port
	portListener.Close()
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
	logfile, err := os.Create(filepath.Join(directory, "dnsmasq.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer logfile.Close()
	currentUser, _ := user.Current()
	var process *exec.Cmd
	stop := func() {
		if process != nil {
			_ = process.Process.Signal(syscall.SIGTERM)
			_ = process.Wait()
			process = nil
		}
	}
	defer stop()
	start := func() error {
		process = exec.Command(binary, "--keep-in-foreground", "--conf-file=/dev/null", "--conf-dir="+confdir, "--port="+strconv.Itoa(port), "--listen-address=127.0.0.1", "--bind-interfaces", "--no-resolv", "--no-hosts", "--cache-size=0", "--user="+currentUser.Username, "--pid-file="+filepath.Join(directory, "dnsmasq.pid"))
		process.Stdout = logfile
		process.Stderr = logfile
		if err := process.Start(); err != nil {
			return err
		}
		request := new(dns.Msg)
		request.SetQuestion("unmanaged.example.test.", dns.TypeA)
		for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
			if _, _, err := (&dns.Client{Timeout: 100 * time.Millisecond}).Exchange(request, addr); err == nil {
				return nil
			}
			time.Sleep(50 * time.Millisecond)
		}
		return syscall.ETIMEDOUT
	}
	m := NewOpenWrtDNSManager(state, nil)
	m.dnsmasqServer = addr
	m.dnsmasqControl = func(bool) (bool, error) { return false, nil }
	ctx, cancel := context.WithCancel(context.Background())
	if err := m.Start(ctx); err != nil {
		t.Fatal(err)
	}
	cancel()
	m.wanWorkers.Wait() // WAN detection is independent of this DNS integration scenario.
	defer m.Stop()
	if err := start(); err != nil {
		t.Fatal(err)
	}
	// This is the old behavior: writing conf-dir then only sending SIGHUP.
	m.dnsmasqControl = func(bool) (bool, error) { return true, process.Process.Signal(syscall.SIGHUP) }
	policy := samplePolicy()
	policy.Groups = policy.Groups[:1]
	if err := m.ApplyPolicy(policy); err == nil {
		t.Fatal("SIGHUP must not falsely confirm a new conf-dir rule")
	}
	if m.snapshot().DNSReady || m.snapshot().DNSStatus != "integration-error" {
		t.Fatal(m.snapshot())
	}
	request := new(dns.Msg)
	request.SetQuestion("access.example.test.", dns.TypeA)
	reply, _, err := (&dns.Client{Timeout: time.Second}).Exchange(request, addr)
	if err != nil || len(reply.Answer) > 0 {
		t.Fatal("old reload unexpectedly loaded rule", reply, err)
	}
	// New behavior restarts on an include change or a failed integration check.
	restarts := 0
	m.dnsmasqControl = func(restart bool) (bool, error) {
		if restart {
			restarts++
			stop()
			return true, start()
		}
		return true, process.Process.Signal(syscall.SIGHUP)
	}
	policy.ForceRepair = true
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	status := m.snapshot()
	if !status.DNSReady || len(status.DNSChecks) != 1 || status.DNSChecks[0].Answers[0] != "192.0.2.1" {
		t.Fatal(status)
	}
	if restarts != 1 {
		t.Fatal("repair must perform one restart", restarts)
	}
	// Repeating a good policy checks the real resolver without restarting it.
	if err := m.ApplyPolicy(m.snapshotPolicy()); err != nil {
		t.Fatal(err)
	}
	if restarts != 1 {
		t.Fatal("identical working policy restarted DNS")
	}
	// An address-only change must flush caches but not restart dnsmasq.
	policy = m.snapshotPolicy()
	policy.Revision++
	policy.Groups[0].DefaultAddress = "192.0.2.9"
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	if m.snapshot().DNSChecks[0].Answers[0] != "192.0.2.9" || restarts != 1 {
		t.Fatal(m.snapshot(), restarts)
	}
	// A second domain must be loaded without an administrator editing OpenWrt.
	policy = m.snapshotPolicy()
	policy.Revision++
	policy.Groups = append(policy.Groups, OpenWrtDNSDomainPolicy{Domain: "second.example.test", RecordType: "A", DefaultAddress: "192.0.2.8", TTL: 5})
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	if restarts != 2 || !m.snapshot().DNSReady || len(m.snapshot().DNSChecks) != 2 {
		t.Fatal(m.snapshot(), restarts)
	}
	policy = m.snapshotPolicy()
	policy.Revision++
	policy.Groups = nil
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	if restarts != 3 || m.snapshot().DNSStatus != "idle" {
		t.Fatal(m.snapshot(), restarts)
	}
	cache, _ := os.ReadFile(state)
	if !strings.Contains(string(cache), "\"groups\": []") {
		t.Fatal("removed policies must be serialized as an empty list")
	}
}

func TestEffectiveConfDirRejectsDnsDisabledProxyInstanceAndAnonymousNameFallback(t *testing.T) {
	sections := enabledDNSMasqSections("dhcp.cfgOld=dnsmasq\ndhcp.cfgOld.disabled='1'\ndhcp.cfgProxy=dnsmasq\ndhcp.cfgProxy.port='15353'\ndhcp.cfg01411c=dnsmasq\ndhcp.cfg01411c.port='53'\n")
	if len(sections) != 1 || sections[0] != "cfg01411c" {
		t.Fatal("inactive or proxy instance selected", sections)
	}
	if got := confDirFromConfig("port=53\nconf-dir=/tmp/dnsmasq.cfg01411c.d\n"); got != "/tmp/dnsmasq.cfg01411c.d" {
		t.Fatal(got)
	}
	if got := confDirFromConfig("port=0\nconf-dir=/tmp/passwall.d\n"); got != "" {
		t.Fatal(got)
	}
	if got := confDirFromConfig("conf-dir=/tmp/dnsmasq.cfg01411c.d,*.conf\n"); got != "/tmp/dnsmasq.cfg01411c.d" {
		t.Fatal(got)
	}
	if got := confDirFromConfig("conf-dir=/tmp/../etc\n"); got != "" {
		t.Fatal(got)
	}
}

func TestBusinessDnsConflictIsReportedEvenWhenIntegrationProbeWorks(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups = policy.Groups[:1]
	m.policy = policy
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &dns.Server{PacketConn: packet, Net: "udp", Handler: dns.HandlerFunc(func(writer dns.ResponseWriter, request *dns.Msg) {
		if request.Question[0].Qtype == dns.TypeTXT {
			m.handleDNS(writer, request)
			return
		}
		response := new(dns.Msg)
		response.SetReply(request)
		response.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: request.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 5}, A: net.ParseIP("192.0.2.77")}}
		_ = writer.WriteMsg(response)
	})}
	go func() { _ = server.ActivateAndServe() }()
	defer server.Shutdown()
	m.dnsmasqServer = packet.LocalAddr().String()
	if err := m.verifyDNS(policy); err != nil {
		t.Fatal(err)
	}
	status := m.snapshot()
	if status.DNSReady || status.DNSStatus != "domain-error" || status.DNSChecks[0].State != "error" || status.LastError == "" {
		t.Fatal(status)
	}
	if status.DNSChecks[0].Answers[0] != "192.0.2.77" {
		t.Fatal(status)
	}
}

func TestNoHealthyEntryReportsUnavailableWithoutRestartingDns(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups = policy.Groups[:1]
	policy.Groups[0].DefaultAddress = ""
	policy.Groups[0].Addresses = map[string]string{}
	m.policy = policy
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &dns.Server{PacketConn: packet, Net: "udp", Handler: dns.HandlerFunc(m.handleDNS)}
	go func() { _ = server.ActivateAndServe() }()
	defer server.Shutdown()
	m.dnsmasqServer = packet.LocalAddr().String()
	if err := m.verifyDNS(policy); err != nil {
		t.Fatal(err)
	}
	status := m.snapshot()
	if status.DNSReady || status.DNSChecks[0].State != "unavailable" {
		t.Fatal(status)
	}
}

func TestDNSMasqControlFailureRestoresPreviousPolicy(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups = policy.Groups[:1]
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	includePath := filepath.Join(m.statePath+".d", "flux-panel-smart-entry.conf")
	previous, _ := os.ReadFile(includePath)
	attempts := 0
	m.dnsmasqControl = func(bool) (bool, error) {
		attempts++
		if attempts == 1 {
			return false, syscall.EIO
		}
		return false, nil
	}
	policy.Revision = 2
	policy.Groups = append(policy.Groups, OpenWrtDNSDomainPolicy{Domain: "second.example.test", RecordType: "A", DefaultAddress: "192.0.2.8"})
	if err := m.ApplyPolicy(policy); err == nil {
		t.Fatal("failed restart was acknowledged")
	}
	current, _ := os.ReadFile(includePath)
	if string(current) != string(previous) || m.snapshotPolicy().Revision != 1 || attempts != 2 {
		t.Fatal("previous policy was not restored")
	}
}

func TestPanelVerificationSeparatesWorkingDnsFromFailedEntryConnection(t *testing.T) {
	entry, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer entry.Close()
	go func() {
		for {
			connection, err := entry.Accept()
			if err != nil {
				return
			}
			connection.Close()
		}
	}()
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups = policy.Groups[:1]
	policy.Groups[0].DefaultAddress = "127.0.0.1"
	policy.Groups[0].Port = entry.Addr().(*net.TCPAddr).Port
	m.policy = policy
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &dns.Server{PacketConn: packet, Net: "udp", Handler: dns.HandlerFunc(m.handleDNS)}
	go func() { _ = server.ActivateAndServe() }()
	defer server.Shutdown()
	m.dnsmasqServer = packet.LocalAddr().String()
	if err := m.verifyDNS(policy, true); err != nil {
		t.Fatal(err)
	}
	if !m.snapshot().DNSReady || m.snapshot().DNSChecks[0].TCPState != "reachable" {
		t.Fatal(m.snapshot())
	}
	entry.Close()
	if err := m.verifyDNS(policy, true); err != nil {
		t.Fatal(err)
	}
	if !m.snapshot().DNSReady || m.snapshot().DNSStatus != "connection-error" || m.snapshot().DNSChecks[0].TCPState != "failed" {
		t.Fatal(m.snapshot())
	}
	if err := m.verifyDNS(policy); err != nil {
		t.Fatal(err)
	}
	if m.snapshot().DNSChecks[0].TCPState != "failed" {
		t.Fatal("periodic DNS check erased the connection diagnostic")
	}
}
