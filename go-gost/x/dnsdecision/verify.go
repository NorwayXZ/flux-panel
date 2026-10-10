package dnsdecision

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
)

const dnsIntegrationProbeName = "flux-panel-dns-check.invalid"

type OpenWrtDNSCheck struct {
	Port            int      `json:"port,omitempty"`
	TCPState        string   `json:"tcpState"`
	TCPError        string   `json:"tcpError,omitempty"`
	TCPCheckedAt    int64    `json:"tcpCheckedAt,omitempty"`
	Domain          string   `json:"domain"`
	RecordType      string   `json:"recordType"`
	ExpectedAddress string   `json:"expectedAddress"`
	Answers         []string `json:"answers"`
	State           string   `json:"state"`
	Error           string   `json:"error,omitempty"`
}

func (m *OpenWrtDNSManager) snapshotPolicy() OpenWrtDNSPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	policy := m.policy
	policy.Groups = append([]OpenWrtDNSDomainPolicy{}, policy.Groups...)
	return policy
}

func (m *OpenWrtDNSManager) restoreAfterDNSControlFailure(previousPolicy OpenWrtDNSPolicy, cache []byte, includePath string, include []byte, cause error) error {
	message := "DNS 服务重新加载失败，已尝试恢复原规则：" + cause.Error()
	if err := writeOpenWrtAtomic(includePath, include, 0644); err != nil {
		message += "；规则恢复失败：" + err.Error()
	}
	if err := writeOpenWrtAtomic(m.statePath, cache, 0600); err != nil {
		message += "；缓存恢复失败：" + err.Error()
	}
	m.mu.Lock()
	m.policy = previousPolicy
	m.carrierNetworks = compileOpenWrtCarrierNetworks(previousPolicy.CarrierCIDRs)
	m.status.Revision = previousPolicy.Revision
	m.status.DNSReady = false
	m.status.DnsmasqReloaded = false
	m.status.DNSStatus = "apply-error"
	m.status.DNSChecks = nil
	m.status.LastError = message
	m.mu.Unlock()
	if _, err := m.dnsmasqControl(true); err != nil {
		message += "；原 DNS 服务恢复失败：" + err.Error()
		m.reportError(message)
	} else {
		m.ReportNow()
	}
	return errors.New(message)
}

func validConfDir(directory string) bool {
	return filepath.IsAbs(directory) && directory != "/" && !strings.Contains(directory, "..") && !strings.ContainsAny(directory, "\n\r\x00")
}

// OpenWrt displays anonymous UCI sections as @dnsmasq[0], but generated files
// use their stable cfg IDs. Prefer the effective main instance configuration.
func confDirFromConfig(data string) string {
	port := "53"
	directory := ""
	for _, line := range strings.Split(data, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "port=") {
			port = strings.TrimPrefix(line, "port=")
		}
		if strings.HasPrefix(line, "conf-dir=") {
			directory = strings.TrimSpace(strings.SplitN(strings.TrimPrefix(line, "conf-dir="), ",", 2)[0])
		}
	}
	if port != "53" || !validConfDir(directory) {
		return ""
	}
	return directory
}

func openWrtDNSMasqDirectory(statePath string) (string, error) {
	directory := statePath + ".d"
	if data, err := os.ReadFile(filepath.Join(filepath.Dir(statePath), "dnsmasq-confdir")); err == nil {
		directory = strings.TrimSpace(string(data))
	}
	if !validConfDir(directory) {
		return "", errors.New("dnsmasq 配置目录无效")
	}
	if _, err := os.Stat("/etc/openwrt_release"); err != nil {
		return directory, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	sections, err := exec.CommandContext(ctx, "uci", "-q", "-X", "show", "dhcp").Output()
	if err != nil {
		return "", errors.New("无法读取 dnsmasq 实际实例，请检查 uci")
	}
	for _, section := range enabledDNSMasqSections(string(sections)) {
		data, err := os.ReadFile("/var/etc/dnsmasq.conf." + section)
		if err == nil {
			if actual := confDirFromConfig(string(data)); actual != "" {
				return actual, nil
			}
		}
	}
	return "", errors.New("没有找到监听 53 端口的 dnsmasq 实际配置，不能确认域名规则生效")
}

func enabledDNSMasqSections(output string) []string {
	values := map[string]string{}
	var sections []string
	for _, line := range strings.Split(output, "\n") {
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		value := strings.Trim(strings.TrimSpace(parts[1]), "'\"")
		values[key] = value
		if strings.HasPrefix(key, "dhcp.") && value == "dnsmasq" {
			section := strings.TrimPrefix(key, "dhcp.")
			if regexpSafeSection(section) {
				sections = append(sections, section)
			}
		}
	}
	result := []string{}
	for _, section := range sections {
		prefix := "dhcp." + section + "."
		disabled := strings.ToLower(values[prefix+"disabled"])
		if disabled == "1" || disabled == "true" || disabled == "yes" || disabled == "on" || disabled == "enabled" {
			continue
		}
		if port := values[prefix+"port"]; port != "" && port != "53" {
			continue
		}
		result = append(result, section)
	}
	return result
}

func regexpSafeSection(section string) bool {
	if section == "" {
		return false
	}
	for _, char := range section {
		if !(char >= 'a' && char <= 'z' || char >= 'A' && char <= 'Z' || char >= '0' && char <= '9' || char == '_') {
			return false
		}
	}
	return true
}

func (m *OpenWrtDNSManager) verifyDNS(policy OpenWrtDNSPolicy, probeConnections ...bool) error {
	if len(policy.Groups) == 0 {
		m.mu.Lock()
		m.status.DNSReady = true
		m.status.DNSStatus = "idle"
		m.status.DNSChecks = nil
		m.status.DNSCheckedAt = time.Now().UnixMilli()
		m.status.LastError = ""
		m.mu.Unlock()
		return nil
	}
	request := new(dns.Msg)
	request.SetQuestion(dnsIntegrationProbeName+".", dns.TypeTXT)
	client := &dns.Client{Net: "udp", Timeout: 350 * time.Millisecond}
	deadline := time.Now().Add(5 * time.Second)
	verified := false
	for {
		reply, _, err := client.Exchange(request, m.dnsmasqServer)
		if err == nil && reply.Rcode == dns.RcodeSuccess {
			for _, answer := range reply.Answer {
				if value, ok := answer.(*dns.TXT); ok && len(value.Txt) == 1 && value.Txt[0] == m.probeToken {
					verified = true
				}
			}
		}
		if verified || time.Now().After(deadline) {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !verified {
		message := "路由器 DNS 未加载 Agent 域名规则（127.0.0.1:53 → 5354 校验失败）；检查实际 conf-dir 或代理 DNS 接管"
		m.mu.Lock()
		m.status.DNSReady = false
		m.status.DNSStatus = "integration-error"
		m.status.DNSCheckedAt = time.Now().UnixMilli()
		m.status.DNSChecks = nil
		m.status.LastError = message
		m.mu.Unlock()
		return errors.New(message)
	}
	checks := make([]OpenWrtDNSCheck, len(policy.Groups))
	previousChecks := m.snapshot().DNSChecks
	probeTCP := len(probeConnections) > 0 && probeConnections[0]
	var wg sync.WaitGroup
	limit := make(chan struct{}, 16)
	for i, group := range policy.Groups {
		wg.Add(1)
		go func(index int, group OpenWrtDNSDomainPolicy) {
			defer wg.Done()
			limit <- struct{}{}
			defer func() { <-limit }()
			m.mu.RLock()
			carrier := m.status.ActiveCarrier
			if group.RecordType == "AAAA" {
				carrier = m.status.ActiveCarrier6
			}
			m.mu.RUnlock()
			if !openWrtCarrier(carrier) || carrier == "default" {
				carrier = policy.DefaultCarrier
			}
			expected, configured := group.Addresses[carrier]
			if !configured {
				expected = group.DefaultAddress
			}
			check := OpenWrtDNSCheck{Domain: group.Domain, RecordType: group.RecordType, ExpectedAddress: expected, State: "ready", Answers: []string{}, Port: group.Port, TCPState: "not-checked"}
			if expected == "" {
				check.State = "unavailable"
				check.Error = "本运营商没有健康入口，请检查入口检测或备用顺序"
				checks[index] = check
				return
			}
			kind := uint16(dns.TypeA)
			if group.RecordType == "AAAA" {
				kind = dns.TypeAAAA
			}
			query := new(dns.Msg)
			query.SetQuestion(group.Domain+".", kind)
			reply, _, err := (&dns.Client{Net: "udp", Timeout: 500 * time.Millisecond}).Exchange(query, m.dnsmasqServer)
			if err != nil {
				check.State = "error"
				check.Error = "路由器业务域名查询超时或失败"
			} else {
				for _, answer := range reply.Answer {
					switch value := answer.(type) {
					case *dns.A:
						check.Answers = append(check.Answers, value.A.String())
					case *dns.AAAA:
						check.Answers = append(check.Answers, value.AAAA.String())
					}
				}
				matched := reply.Rcode == dns.RcodeSuccess && len(check.Answers) > 0
				for _, address := range check.Answers {
					matched = matched && net.ParseIP(expected).Equal(net.ParseIP(address))
				}
				if !matched {
					check.State = "error"
					check.Error = fmt.Sprintf("Agent 目标 %s，但路由器 DNS 返回 %s %v；可能被其他 DNS 规则接管", expected, dns.RcodeToString[reply.Rcode], check.Answers)
				}
			}
			if check.State == "ready" && group.Port > 0 {
				if probeTCP {
					check.TCPCheckedAt = time.Now().UnixMilli()
					connection, err := net.DialTimeout("tcp", net.JoinHostPort(expected, strconv.Itoa(group.Port)), 700*time.Millisecond)
					if err != nil {
						check.TCPState = "failed"
						check.TCPError = "域名解析正确，但路由器无法连接入口 " + net.JoinHostPort(expected, strconv.Itoa(group.Port)) + "：" + err.Error()
					} else {
						connection.Close()
						check.TCPState = "reachable"
					}
				} else {
					for _, old := range previousChecks {
						age := time.Now().UnixMilli() - old.TCPCheckedAt
						if old.Domain == check.Domain && old.RecordType == check.RecordType && old.ExpectedAddress == expected && old.Port == group.Port && age >= 0 && age < 300000 {
							check.TCPState = old.TCPState
							check.TCPError = old.TCPError
							check.TCPCheckedAt = old.TCPCheckedAt
						}
					}
				}
			}
			checks[index] = check
		}(i, group)
	}
	wg.Wait()
	ready := true
	lastError := ""
	connectionFailed := false
	for _, check := range checks {
		if check.State != "ready" {
			ready = false
			if lastError == "" {
				lastError = check.Domain + "：" + check.Error
			}
		}
		if check.TCPState == "failed" {
			connectionFailed = true
			if lastError == "" {
				lastError = check.Domain + "：" + check.TCPError
			}
		}
	}
	m.mu.Lock()
	m.status.DNSReady = ready
	m.status.DNSStatus = "ready"
	if !ready {
		m.status.DNSStatus = "domain-error"
	} else if connectionFailed {
		m.status.DNSStatus = "connection-error"
	}
	m.status.DNSChecks = checks
	m.status.DNSCheckedAt = time.Now().UnixMilli()
	m.status.LastError = lastError
	m.mu.Unlock()
	return nil
}
