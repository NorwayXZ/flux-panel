package dnsdecision

import (
	"encoding/json"
	"github.com/miekg/dns"
	"net"
	"os"
	"path/filepath"
	"testing"
)

type recorder struct{ msg *dns.Msg }

func (r *recorder) LocalAddr() net.Addr         { return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 5354} }
func (r *recorder) RemoteAddr() net.Addr        { return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 4321} }
func (r *recorder) WriteMsg(m *dns.Msg) error   { r.msg = m.Copy(); return nil }
func (r *recorder) Write(p []byte) (int, error) { return len(p), nil }
func (r *recorder) Close() error                { return nil }
func (r *recorder) TsigStatus() error           { return nil }
func (r *recorder) TsigTimersOnly(bool)         {}
func (r *recorder) Hijack()                     {}

func samplePolicy() OpenWrtDNSPolicy {
	return OpenWrtDNSPolicy{Revision: 1, DefaultCarrier: "default", InterfaceCarriers: map[string]string{}, Groups: []OpenWrtDNSDomainPolicy{
		{Domain: "access.example.test", RecordType: "A", TTL: 5, DefaultAddress: "192.0.2.1", Addresses: map[string]string{"mobile": "192.0.2.2", "unicom": "192.0.2.3"}},
		{Domain: "access.example.test", RecordType: "AAAA", TTL: 5, DefaultAddress: "2001:db8::1", Addresses: map[string]string{"mobile": "2001:db8::2", "unicom": "2001:db8::3"}},
	}}
}
func query(m *OpenWrtDNSManager, name string, kind uint16) *dns.Msg {
	request := new(dns.Msg)
	request.SetQuestion(name, kind)
	response := new(recorder)
	m.handleDNS(response, request)
	return response.msg
}

func TestNewQueriesFollowCurrentCarrierWithoutBusinessForwarding(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	if err := m.ApplyPolicy(samplePolicy()); err != nil {
		t.Fatal(err)
	}
	m.status.ActiveCarrier = "mobile"
	first := query(m, "access.example.test.", dns.TypeA)
	if len(first.Answer) != 1 || first.Answer[0].(*dns.A).A.String() != "192.0.2.2" || first.Answer[0].Header().Ttl != 5 {
		t.Fatal(first)
	}
	m.status.ActiveCarrier = "unicom"
	second := query(m, "access.example.test.", dns.TypeA)
	if second.Answer[0].(*dns.A).A.String() != "192.0.2.3" {
		t.Fatal(second)
	}
}

func TestIpv6UsesItsOwnWanCarrier(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	if err := m.ApplyPolicy(samplePolicy()); err != nil {
		t.Fatal(err)
	}
	m.status.ActiveCarrier = "mobile"
	m.status.ActiveCarrier6 = "unicom"
	response := query(m, "access.example.test.", dns.TypeAAAA)
	if response.Answer[0].(*dns.AAAA).AAAA.String() != "2001:db8::3" {
		t.Fatal(response)
	}
}

func TestMissingIpv6AndHttpsHintsCannotBypassCarrierPolicy(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups = policy.Groups[:1]
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	m.upstreams = func() []string { t.Fatal("unexpected public DNS request"); return nil }
	for _, kind := range []uint16{dns.TypeAAAA, dns.TypeHTTPS, dns.TypeSVCB} {
		response := query(m, "access.example.test.", kind)
		if response.Rcode != dns.RcodeSuccess || len(response.Answer) != 0 {
			t.Fatal(response)
		}
	}
}

func TestExplicitNoFallbackCannotSilentlyUseDefaultEntry(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups[0].Addresses["mobile"] = ""
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	m.status.ActiveCarrier = "mobile"
	if response := query(m, "access.example.test.", dns.TypeA); response.Rcode != dns.RcodeServerFailure || len(response.Answer) != 0 {
		t.Fatal(response)
	}
	m.status.ActiveCarrier = "telecom"
	if response := query(m, "access.example.test.", dns.TypeA); len(response.Answer) != 1 || response.Answer[0].(*dns.A).A.String() != "192.0.2.1" {
		t.Fatal(response)
	}
}

func TestNoHealthyAddressReturnsFailureInsteadOfAnUnhealthyEntry(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	policy.Groups[0].DefaultAddress = ""
	policy.Groups[0].Addresses = map[string]string{}
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	response := query(m, "access.example.test.", dns.TypeA)
	if response.Rcode != dns.RcodeServerFailure || len(response.Answer) != 0 {
		t.Fatal(response)
	}
}

func TestPoliciesPersistAndRemovalPreservesOtherDnsmasqFiles(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "state.json")
	confdir := filepath.Join(directory, "conf.d")
	if err := os.Mkdir(confdir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "dnsmasq-confdir"), []byte(confdir), 0600); err != nil {
		t.Fatal(err)
	}
	unrelated := filepath.Join(confdir, "existing.conf")
	_ = os.WriteFile(unrelated, []byte("existing-rule\n"), 0644)
	m := NewOpenWrtDNSManager(path, nil)
	policy := samplePolicy()
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	body, _ := os.ReadFile(path)
	var saved OpenWrtDNSPolicy
	if json.Unmarshal(body, &saved) != nil || saved.Revision != 1 {
		t.Fatal("policy not persisted")
	}
	policy.Revision = 2
	policy.Groups = nil
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	managed, _ := os.ReadFile(filepath.Join(confdir, "flux-panel-smart-entry.conf"))
	if string(managed) != "\n" {
		t.Fatal(string(managed))
	}
	preserved, _ := os.ReadFile(unrelated)
	if string(preserved) != "existing-rule\n" {
		t.Fatal("unrelated DNS configuration changed")
	}
	policy.Revision = 1
	if err := m.ApplyPolicy(policy); err == nil {
		t.Fatal("stale policy accepted")
	}
}

func TestInvalidPoliciesCannotChangeCachedState(t *testing.T) {
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	policy := samplePolicy()
	if err := m.ApplyPolicy(policy); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"test\nserver=evil", "test/127.0.0.1"} {
		invalid := samplePolicy()
		invalid.Groups[0].Domain = name
		if err := m.ApplyPolicy(invalid); err == nil {
			t.Fatal("invalid domain accepted")
		}
	}
	invalid := samplePolicy()
	invalid.Groups[0].DefaultAddress = "2001:db8::5"
	if err := m.ApplyPolicy(invalid); err == nil {
		t.Fatal("address family mismatch accepted")
	}
	body, _ := os.ReadFile(m.statePath)
	var cached OpenWrtDNSPolicy
	_ = json.Unmarshal(body, &cached)
	if cached.Groups[0].DefaultAddress != "192.0.2.1" {
		t.Fatal("valid cache changed")
	}
}

func TestCarrierDatabaseUsesLongestPrefixAndRejectsAmbiguity(t *testing.T) {
	networks := compileOpenWrtCarrierNetworks(map[string][]string{"mobile": {"192.0.2.0/24"}, "unicom": {"192.0.2.64/26"}})
	if got := matchOpenWrtCarrier(net.ParseIP("192.0.2.70"), networks); got != "unicom" {
		t.Fatal(got)
	}
	networks = compileOpenWrtCarrierNetworks(map[string][]string{"mobile": {"192.0.2.0/24"}, "unicom": {"192.0.2.0/24"}})
	for i := 0; i < 20; i++ {
		if got := matchOpenWrtCarrier(net.ParseIP("192.0.2.70"), networks); got != "" {
			t.Fatal(got)
		}
	}
}

func TestUnmanagedDomainsForwardToExistingUpstream(t *testing.T) {
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := &dns.Server{PacketConn: packet, Net: "udp", Handler: dns.HandlerFunc(func(w dns.ResponseWriter, r *dns.Msg) {
		reply := new(dns.Msg)
		reply.SetReply(r)
		reply.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: r.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60}, A: net.IPv4(192, 0, 2, 50)}}
		_ = w.WriteMsg(reply)
	})}
	go func() { _ = server.ActivateAndServe() }()
	defer server.Shutdown()
	m := NewOpenWrtDNSManager(filepath.Join(t.TempDir(), "state.json"), nil)
	m.upstreams = func() []string { return []string{packet.LocalAddr().String()} }
	response := query(m, "ordinary.example.test.", dns.TypeA)
	if len(response.Answer) != 1 || response.Answer[0].(*dns.A).A.String() != "192.0.2.50" {
		t.Fatal(response)
	}
}
