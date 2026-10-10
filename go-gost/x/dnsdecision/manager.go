package dnsdecision

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
)

const openWrtDNSListen = "127.0.0.1:5354"

var safeDNSMasqName = regexp.MustCompile(`^[A-Za-z0-9.-]+$`)

type OpenWrtDNSPolicy struct {
	Revision          int64                    `json:"revision"`
	InterfaceCarriers map[string]string        `json:"interfaceCarriers"`
	DefaultCarrier    string                   `json:"defaultCarrier"`
	Groups            []OpenWrtDNSDomainPolicy `json:"groups"`
	CarrierCIDRs      map[string][]string      `json:"carrierCidrs"`
}

type OpenWrtDNSDomainPolicy struct {
	Domain         string            `json:"domain"`
	RecordType     string            `json:"recordType"`
	TTL            uint32            `json:"ttl"`
	DefaultAddress string            `json:"defaultAddress"`
	Addresses      map[string]string `json:"addresses"`
}

type OpenWrtDNSStatus struct {
	Revision         int64  `json:"revision"`
	ActiveInterface  string `json:"activeInterface,omitempty"`
	ActiveCarrier    string `json:"activeCarrier"`
	ResolvedQueries  uint64 `json:"resolvedQueries"`
	DnsmasqReloaded  bool   `json:"dnsmasqReloaded"`
	LastError        string `json:"lastError,omitempty"`
	ReportedAt       int64  `json:"reportedAt"`
	PublicIP         string `json:"publicIp,omitempty"`
	DetectionSource  string `json:"detectionSource,omitempty"`
	ActiveInterface6 string `json:"activeInterface6,omitempty"`
	ActiveCarrier6   string `json:"activeCarrier6"`
	PublicIP6        string `json:"publicIp6,omitempty"`
	DetectionSource6 string `json:"detectionSource6,omitempty"`
	DetectionError   string `json:"detectionError,omitempty"`
}

type OpenWrtDNSManager struct {
	statePath       string
	report          func(string, map[string]interface{})
	mu              sync.RWMutex
	applyMu         sync.Mutex
	policy          OpenWrtDNSPolicy
	status          OpenWrtDNSStatus
	queries         uint64
	carrierNetworks map[string][]*net.IPNet
	upstreams       func() []string
	udp             *dns.Server
	tcp             *dns.Server
	cancel          context.CancelFunc
}

func NewOpenWrtDNSManager(statePath string, report func(string, map[string]interface{})) *OpenWrtDNSManager {
	if strings.TrimSpace(statePath) == "" {
		statePath = "/etc/flux-panel-dns/state.json"
	}
	return &OpenWrtDNSManager{statePath: statePath, report: report, policy: OpenWrtDNSPolicy{DefaultCarrier: "default"}, carrierNetworks: map[string][]*net.IPNet{}, upstreams: readOpenWrtUpstreams}
}

func (m *OpenWrtDNSManager) Start(parent context.Context) error {
	ctx, cancel := context.WithCancel(parent)
	m.mu.Lock()
	m.cancel = cancel
	m.mu.Unlock()
	if state, err := os.ReadFile(m.statePath); err == nil {
		var policy OpenWrtDNSPolicy
		if err := json.Unmarshal(state, &policy); err != nil {
			return fmt.Errorf("缓存策略无法解析：%w", err)
		}
		if err := validateOpenWrtDNSPolicy(policy); err != nil {
			return err
		}
		{
			m.mu.Lock()
			m.policy = policy
			m.carrierNetworks = compileOpenWrtCarrierNetworks(policy.CarrierCIDRs)
			m.mu.Unlock()
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	packet, err := net.ListenPacket("udp", openWrtDNSListen)
	if err != nil {
		return fmt.Errorf("DNS UDP 端口被占用：%w", err)
	}
	listener, err := net.Listen("tcp", openWrtDNSListen)
	if err != nil {
		_ = packet.Close()
		return fmt.Errorf("DNS TCP 端口被占用：%w", err)
	}
	udp := &dns.Server{PacketConn: packet, Net: "udp", Handler: dns.HandlerFunc(m.handleDNS)}
	tcp := &dns.Server{Listener: listener, Net: "tcp", Handler: dns.HandlerFunc(m.handleDNS)}
	m.mu.Lock()
	m.udp = udp
	m.tcp = tcp
	m.mu.Unlock()
	go func() {
		if err := udp.ActivateAndServe(); err != nil && !errors.Is(err, net.ErrClosed) {
			m.reportError("UDP DNS 监听失败：" + err.Error())
		}
	}()
	go func() {
		if err := tcp.ActivateAndServe(); err != nil && !errors.Is(err, net.ErrClosed) {
			m.reportError("TCP DNS 监听失败：" + err.Error())
		}
	}()
	if err := writeOpenWrtDNSMasqIncludes(m.statePath, m.policy); err != nil {
		m.reportError(err.Error())
	} else {
		_, _ = reloadOpenWrtDNSMasq()
	}
	go m.monitorWAN(ctx)
	return nil
}

func (m *OpenWrtDNSManager) Stop() {
	m.mu.Lock()
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	udp, tcp := m.udp, m.tcp
	m.udp, m.tcp = nil, nil
	m.mu.Unlock()
	if udp != nil {
		_ = udp.Shutdown()
	}
	if tcp != nil {
		_ = tcp.Shutdown()
	}
	m.applyMu.Lock()
	_ = writeOpenWrtDNSMasqIncludes(m.statePath, OpenWrtDNSPolicy{})
	_, _ = reloadOpenWrtDNSMasq()
	m.applyMu.Unlock()
}

func (m *OpenWrtDNSManager) ApplyPolicy(policy OpenWrtDNSPolicy) error {
	m.applyMu.Lock()
	defer m.applyMu.Unlock()
	if err := validateOpenWrtDNSPolicy(policy); err != nil {
		return err
	}
	m.mu.RLock()
	oldRevision := m.policy.Revision
	m.mu.RUnlock()
	if policy.Revision < oldRevision {
		return errors.New("拒绝旧版本 DNS 配置")
	}
	if policy.DefaultCarrier == "" {
		policy.DefaultCarrier = "default"
	}
	for i := range policy.Groups {
		policy.Groups[i].Domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(policy.Groups[i].Domain)), ".")
		policy.Groups[i].RecordType = strings.ToUpper(policy.Groups[i].RecordType)
	}
	serialized, err := json.MarshalIndent(policy, "", "  ")
	if err != nil {
		return err
	}
	m.mu.RLock()
	previous, _ := json.MarshalIndent(m.policy, "", "  ")
	m.mu.RUnlock()
	if oldRevision > 0 && policy.Revision == oldRevision && string(previous) != string(serialized) {
		return errors.New("相同版本的策略内容不一致")
	}
	if oldRevision > 0 && policy.Revision == oldRevision {
		m.mu.RLock()
		lastError := m.status.LastError
		m.mu.RUnlock()
		if _, err := os.Stat(m.statePath); err == nil && lastError == "" {
			m.ReportNow()
			return nil
		}
	}
	if err := writeOpenWrtAtomic(m.statePath, serialized, 0600); err != nil {
		return fmt.Errorf("保存 DNS 策略失败：%w", err)
	}
	if err := writeOpenWrtDNSMasqIncludes(m.statePath, policy); err != nil {
		return fmt.Errorf("生成 dnsmasq 域名规则失败：%w", err)
	}
	m.mu.Lock()
	m.policy = policy
	m.carrierNetworks = compileOpenWrtCarrierNetworks(policy.CarrierCIDRs)
	m.status.Revision = policy.Revision
	m.mu.Unlock()
	reloaded, err := reloadOpenWrtDNSMasq()
	m.mu.Lock()
	m.status.DnsmasqReloaded = err == nil && reloaded
	if err != nil {
		m.status.LastError = err.Error()
	} else {
		m.status.LastError = ""
	}
	status := m.snapshotLocked()
	m.mu.Unlock()
	if err != nil {
		m.reportStatus(status)
		return err
	}
	m.reportStatus(status)
	return nil
}

func validateOpenWrtDNSPolicy(policy OpenWrtDNSPolicy) error {
	if policy.Revision < 0 {
		return errors.New("策略版本无效")
	}
	if policy.DefaultCarrier != "" && !openWrtCarrier(policy.DefaultCarrier) {
		return errors.New("默认运营商无效")
	}
	for iface, carrier := range policy.InterfaceCarriers {
		if len(iface) == 0 || len(iface) > 15 || !regexp.MustCompile(`^[A-Za-z0-9_.:-]+$`).MatchString(iface) {
			return fmt.Errorf("WAN 接口名称无效：%q", iface)
		}
		if !openWrtCarrier(carrier) || carrier == "default" {
			return fmt.Errorf("接口 %s 的运营商映射无效", iface)
		}
	}
	if len(policy.Groups) > 256 {
		return errors.New("最多绑定 256 个业务策略")
	}
	seen := map[string]bool{}
	for carrier, cidrs := range policy.CarrierCIDRs {
		if !openWrtCarrier(carrier) || len(cidrs) > 30000 {
			return errors.New("运营商地址库标识或大小无效")
		}
		for _, cidr := range cidrs {
			if _, _, err := net.ParseCIDR(cidr); err != nil {
				return fmt.Errorf("无效的运营商地址段：%s", cidr)
			}
		}
	}
	for _, group := range policy.Groups {
		domain := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(group.Domain)), ".")
		_, validName := dns.IsDomainName(domain + ".")
		if domain == "" || !safeDNSMasqName.MatchString(domain) || !validName {
			return fmt.Errorf("业务域名无效：%q", group.Domain)
		}
		if seen[domain+"/"+strings.ToUpper(group.RecordType)] {
			return fmt.Errorf("策略域名重复：%s", domain)
		}
		seen[domain+"/"+strings.ToUpper(group.RecordType)] = true
		typeCode := uint16(dns.TypeA)
		if strings.EqualFold(group.RecordType, "AAAA") {
			typeCode = dns.TypeAAAA
		} else if !strings.EqualFold(group.RecordType, "A") {
			return fmt.Errorf("暂不支持 %s 记录", group.RecordType)
		}
		for _, address := range append([]string{group.DefaultAddress}, mapValues(group.Addresses)...) {
			if address == "" {
				continue
			}
			ip := net.ParseIP(address)
			if ip == nil || (typeCode == dns.TypeA && ip.To4() == nil) || (typeCode == dns.TypeAAAA && ip.To4() != nil) {
				return fmt.Errorf("%s 的 %s 地址与 %s 记录类型不匹配", domain, address, group.RecordType)
			}
		}
		for carrier := range group.Addresses {
			if !openWrtCarrier(carrier) {
				return errors.New("策略入口运营商标识无效")
			}
		}
	}
	return nil
}

func (m *OpenWrtDNSManager) handleDNS(writer dns.ResponseWriter, request *dns.Msg) {
	response := new(dns.Msg)
	response.SetReply(request)
	response.Authoritative = true
	if len(request.Question) != 1 || request.Question[0].Qclass != dns.ClassINET {
		response.Rcode = dns.RcodeRefused
		_ = writer.WriteMsg(response)
		return
	}
	question := request.Question[0]
	domain := strings.TrimSuffix(strings.ToLower(question.Name), ".")
	m.mu.RLock()
	policy := m.policy
	active := m.status.ActiveCarrier
	if question.Qtype == dns.TypeAAAA {
		active = m.status.ActiveCarrier6
	}
	m.mu.RUnlock()
	matchedDomain := false
	for _, group := range policy.Groups {
		configuredDomain := strings.TrimSuffix(strings.ToLower(group.Domain), ".")
		if domain != configuredDomain {
			continue
		}
		matchedDomain = true
		wantType := uint16(dns.TypeA)
		if strings.EqualFold(group.RecordType, "AAAA") {
			wantType = dns.TypeAAAA
		}
		if question.Qtype != wantType {
			if question.Qtype == dns.TypeA || question.Qtype == dns.TypeAAAA {
				continue
			}
			if question.Qtype == dns.TypeHTTPS || question.Qtype == dns.TypeSVCB {
				_ = writer.WriteMsg(response)
				return
			}
			m.forwardUpstream(writer, request)
			return
		}
		carrier := active
		if !openWrtCarrier(carrier) || carrier == "default" {
			carrier = policy.DefaultCarrier
		}
		address, configured := group.Addresses[carrier]
		if !configured {
			address = group.DefaultAddress
		}
		if address != "" {
			ttl := group.TTL
			if ttl == 0 || ttl > 30 {
				ttl = 5
			}
			if wantType == dns.TypeA {
				response.Answer = append(response.Answer, &dns.A{Hdr: dns.RR_Header{Name: question.Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: ttl}, A: net.ParseIP(address).To4()})
			}
			if wantType == dns.TypeAAAA {
				response.Answer = append(response.Answer, &dns.AAAA{Hdr: dns.RR_Header{Name: question.Name, Rrtype: dns.TypeAAAA, Class: dns.ClassINET, Ttl: ttl}, AAAA: net.ParseIP(address)})
			}
		}
		if address == "" {
			response.Rcode = dns.RcodeServerFailure
		}
		m.mu.Lock()
		m.queries++
		m.mu.Unlock()
		_ = writer.WriteMsg(response)
		return
	}
	if matchedDomain && (question.Qtype == dns.TypeA || question.Qtype == dns.TypeAAAA) {
		_ = writer.WriteMsg(response)
		return
	}
	m.forwardUpstream(writer, request)
}

func (m *OpenWrtDNSManager) forwardUpstream(writer dns.ResponseWriter, request *dns.Msg) {
	servers := m.upstreams()
	client := &dns.Client{Net: "udp", Timeout: 2 * time.Second}
	for _, server := range servers {
		response, _, err := client.Exchange(request, server)
		if err == nil && response.Truncated {
			response, _, err = (&dns.Client{Net: "tcp", Timeout: 2 * time.Second}).Exchange(request, server)
		}
		if err == nil {
			_ = writer.WriteMsg(response)
			return
		}
	}
	failure := new(dns.Msg)
	failure.SetRcode(request, dns.RcodeServerFailure)
	_ = writer.WriteMsg(failure)
}

func readOpenWrtUpstreams() []string {
	data, err := os.ReadFile("/tmp/resolv.conf.d/resolv.conf.auto")
	if err != nil {
		data, _ = os.ReadFile("/etc/resolv.conf")
	}
	seen := map[string]bool{}
	var servers []string
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) != 2 || fields[0] != "nameserver" {
			continue
		}
		ip := net.ParseIP(fields[1])
		if ip == nil || ip.IsLoopback() {
			continue
		}
		server := net.JoinHostPort(ip.String(), "53")
		if !seen[server] {
			servers = append(servers, server)
			seen[server] = true
		}
	}
	return servers
}

func (m *OpenWrtDNSManager) monitorWAN(ctx context.Context) {
	watch := time.NewTicker(3 * time.Second)
	report := time.NewTicker(15 * time.Second)
	defer watch.Stop()
	defer report.Stop()
	lastLookup := time.Time{}
	lastLookupError := ""
	for {
		select {
		case <-ctx.Done():
			return
		case <-watch.C:
			changed := false
			var errorsFound []string
			for _, family := range []string{"4", "6"} {
				iface, sourceIP, routeErr := detectOpenWrtRoute(family)
				m.mu.RLock()
				previousInterface, previousIP := m.status.ActiveInterface, m.status.PublicIP
				if family == "6" {
					previousInterface, previousIP = m.status.ActiveInterface6, m.status.PublicIP6
				}
				explicit := m.policy.InterfaceCarriers[iface]
				m.mu.RUnlock()
				if routeErr != nil {
					if family == "4" {
						errorsFound = append(errorsFound, routeErr.Error())
					}
					iface, sourceIP = "", ""
				} else if family == "4" && explicit == "" {
					if iface != previousInterface || time.Since(lastLookup) >= 15*time.Second {
						lastLookup = time.Now()
						publicIP, lookupErr := fetchOpenWrtPublicIP(ctx)
						if lookupErr == nil {
							sourceIP = publicIP
							lastLookupError = ""
						} else {
							lastLookupError = "公网 IPv4 查询失败"
							sourceIP = ""
						}
					} else {
						sourceIP = previousIP
					}
					if lastLookupError != "" {
						errorsFound = append(errorsFound, lastLookupError)
					}
				}
				m.mu.Lock()
				carrier, detection := m.policy.InterfaceCarriers[iface], "interface"
				if carrier == "" && sourceIP != "" {
					carrier = matchOpenWrtCarrier(net.ParseIP(sourceIP), m.carrierNetworks)
					detection = "public-ip-database"
				}
				if carrier == "" {
					if sourceIP != "" {
						if len(m.carrierNetworks) == 0 {
							errorsFound = append(errorsFound, "运营商地址库暂不可用，面板将自动更新")
						} else {
							errorsFound = append(errorsFound, "IPv"+family+" 出口未命中运营商地址库")
						}
					}
					carrier = m.policy.DefaultCarrier
					detection = "default"
				}
				if family == "4" {
					changed = changed || carrier != m.status.ActiveCarrier || iface != m.status.ActiveInterface
					m.status.ActiveCarrier, m.status.ActiveInterface, m.status.PublicIP, m.status.DetectionSource = carrier, iface, sourceIP, detection
				} else {
					changed = changed || carrier != m.status.ActiveCarrier6 || iface != m.status.ActiveInterface6
					m.status.ActiveCarrier6, m.status.ActiveInterface6, m.status.PublicIP6, m.status.DetectionSource6 = carrier, iface, sourceIP, detection
				}
				m.mu.Unlock()
			}
			m.mu.Lock()
			m.status.DetectionError = strings.Join(errorsFound, "；")
			m.mu.Unlock()
			if changed {
				m.applyMu.Lock()
				reloaded, reloadErr := reloadOpenWrtDNSMasq()
				m.applyMu.Unlock()
				m.mu.Lock()
				m.status.DnsmasqReloaded = reloaded && reloadErr == nil
				if reloadErr != nil {
					m.status.LastError = reloadErr.Error()
				}
				status := m.snapshotLocked()
				m.mu.Unlock()
				m.reportStatus(status)
			}
		case <-report.C:
			m.reportStatus(m.snapshot())
		}
	}
}

func detectOpenWrtRoute(family string) (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	target := "1.1.1.1"
	if family == "6" {
		target = "2606:4700:4700::1111"
	}
	output, err := exec.CommandContext(ctx, "ip", "-"+family, "route", "get", target).Output()
	if err != nil {
		return "", "", fmt.Errorf("读取 OpenWrt 默认 IPv%s 路由失败：%w", family, err)
	}
	fields := strings.Fields(string(output))
	iface, source := "", ""
	for i := 0; i+1 < len(fields); i++ {
		if fields[i] == "dev" {
			iface = fields[i+1]
		}
		if fields[i] == "src" {
			source = fields[i+1]
		}
	}
	if iface == "" {
		return "", "", errors.New("当前没有可识别的默认出口")
	}
	return iface, source, nil
}

func compileOpenWrtCarrierNetworks(cidrs map[string][]string) map[string][]*net.IPNet {
	result := map[string][]*net.IPNet{}
	for carrier, values := range cidrs {
		for _, value := range values {
			if _, network, err := net.ParseCIDR(value); err == nil {
				result[carrier] = append(result[carrier], network)
			}
		}
	}
	return result
}

func matchOpenWrtCarrier(ip net.IP, networks map[string][]*net.IPNet) string {
	best := -1
	selected := ""
	ambiguous := false
	for carrier, values := range networks {
		for _, network := range values {
			if network.Contains(ip) {
				bits, _ := network.Mask.Size()
				if bits > best {
					best = bits
					selected = carrier
					ambiguous = false
				} else if bits == best && selected != carrier {
					ambiguous = true
				}
			}
		}
	}
	if ambiguous {
		return ""
	}
	return selected
}

func fetchOpenWrtPublicIP(parent context.Context) (string, error) {
	ctx, cancel := context.WithTimeout(parent, 4*time.Second)
	defer cancel()
	dialer := &net.Dialer{Timeout: 2 * time.Second}
	transport := &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
		return dialer.DialContext(ctx, "tcp4", address)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 4 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	request, err := http.NewRequestWithContext(ctx, "GET", "https://api4.ipify.org", nil)
	if err != nil {
		return "", err
	}
	response, err := client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return "", errors.New("公网出口查询失败")
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 128))
	if err != nil {
		return "", err
	}
	ip := net.ParseIP(strings.TrimSpace(string(body)))
	if ip == nil || ip.To4() == nil || ip.IsPrivate() || ip.IsLoopback() {
		return "", errors.New("出口返回非公网 IPv4")
	}
	return ip.String(), nil
}

func writeOpenWrtDNSMasqIncludes(statePath string, policy OpenWrtDNSPolicy) error {
	directory := statePath + ".d"
	if contents, err := os.ReadFile(filepath.Join(filepath.Dir(statePath), "dnsmasq-confdir")); err == nil {
		directory = strings.TrimSpace(string(contents))
		if !filepath.IsAbs(directory) || directory == "/" || strings.Contains(directory, "..") {
			return errors.New("dnsmasq 配置目录无效")
		}
	}
	if err := os.MkdirAll(directory, 0755); err != nil {
		return err
	}
	var lines []string
	seen := map[string]bool{}
	for _, group := range policy.Groups {
		domain := strings.TrimSuffix(strings.ToLower(group.Domain), ".")
		if seen[domain] {
			continue
		}
		seen[domain] = true
		lines = append(lines, "server=/"+domain+"/127.0.0.1#5354")
	}
	return writeOpenWrtAtomic(filepath.Join(directory, "flux-panel-smart-entry.conf"), []byte(strings.Join(lines, "\n")+"\n"), 0644)
}

func reloadOpenWrtDNSMasq() (bool, error) {
	if _, err := os.Stat("/etc/openwrt_release"); err != nil {
		return false, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, "/etc/init.d/dnsmasq", "reload").CombinedOutput()
	if err != nil {
		return false, fmt.Errorf("重载 dnsmasq 失败：%s", strings.TrimSpace(string(output)))
	}
	return true, nil
}

func writeOpenWrtAtomic(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func openWrtCarrier(carrier string) bool {
	return carrier == "default" || carrier == "telecom" || carrier == "unicom" || carrier == "mobile"
}
func mapValues(values map[string]string) []string {
	result := make([]string, 0, len(values))
	for _, value := range values {
		result = append(result, value)
	}
	return result
}
func (m *OpenWrtDNSManager) reportError(message string) {
	m.mu.Lock()
	m.status.LastError = message
	s := m.snapshotLocked()
	m.mu.Unlock()
	m.reportStatus(s)
}
func (m *OpenWrtDNSManager) snapshot() OpenWrtDNSStatus {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.snapshotLocked()
}
func (m *OpenWrtDNSManager) snapshotLocked() OpenWrtDNSStatus {
	s := m.status
	s.Revision = m.policy.Revision
	s.ResolvedQueries = m.queries
	s.ReportedAt = time.Now().UnixMilli()
	return s
}
func (m *OpenWrtDNSManager) reportStatus(status OpenWrtDNSStatus) {
	if m.report != nil {
		data, _ := json.Marshal(status)
		var fields map[string]interface{}
		_ = json.Unmarshal(data, &fields)
		m.report("OpenWrtDnsStatus", fields)
	}
}

func (m *OpenWrtDNSManager) ReportNow() { m.reportStatus(m.snapshot()) }
