package socket

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/x/internal/net/dialer"
)

const (
	exitPoolMaxMembers    = 6
	exitConnectTimeout    = 30 * time.Second
	exitHealthInterval    = 10 * time.Second
	exitHealthRetry       = 3 * time.Second
	exitHealthFailures    = 3
	exitIPRecheckInterval = 5 * time.Minute
	exitSetupRetry        = 5 * time.Minute

	exitStateConnecting = "connecting"
	exitStateUp         = "up"
	exitStateFailed     = "failed"
)

var (
	exitGroupPattern  = regexp.MustCompile(`^[a-z0-9]{1,12}$`)
	exitMemberPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]{1,64}$`)
	exitCiphers       = map[string]bool{
		"AES-128-CBC": true, "AES-192-CBC": true, "AES-256-CBC": true,
		"AES-128-GCM": true, "AES-192-GCM": true, "AES-256-GCM": true,
		"CHACHA20-POLY1305": true,
	}
	exitAuthDigests = map[string]bool{"SHA1": true, "SHA256": true, "SHA384": true, "SHA512": true}
)

// exitMemberSpec describes one upstream exit. The Agent builds the tunnel
// configuration from these fields itself; it never runs a configuration file
// received from outside.
type exitMemberSpec struct {
	ID string `json:"id"`
	// Attempt is raised by the panel to ask for a fresh connection attempt
	// of a member that failed earlier.
	Attempt int64  `json:"attempt,omitempty"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
	Proto   string `json:"proto"`
	Cipher  string `json:"cipher"`
	Auth    string `json:"auth"`
	CA      string `json:"ca"`
	Cert    string `json:"cert,omitempty"`
	Key     string `json:"key,omitempty"`
}

type exitPoolSyncRequest struct {
	Group       string           `json:"group"`
	Members     []exitMemberSpec `json:"members"`
	ApprovedIPs []string         `json:"approvedIps"`
}

type exitMemberStatus struct {
	ID          string `json:"id"`
	State       string `json:"state"`
	Interface   string `json:"interface,omitempty"`
	ExitIP      string `json:"exitIp,omitempty"`
	Approved    bool   `json:"approved"`
	Active      bool   `json:"active"`
	LatencyMs   int64  `json:"latencyMs,omitempty"`
	ConnectedAt int64  `json:"connectedAt,omitempty"`
	LastOkAt    int64  `json:"lastOkAt,omitempty"`
	RxBytes     int64  `json:"rxBytes,omitempty"`
	TxBytes     int64  `json:"txBytes,omitempty"`
	Error       string `json:"error,omitempty"`
}

type exitPoolStatus struct {
	Group          string             `json:"group"`
	ActiveID       string             `json:"activeId,omitempty"`
	ActiveExitIP   string             `json:"activeExitIp,omitempty"`
	ActiveSince    int64              `json:"activeSince,omitempty"`
	SwitchReason   string             `json:"switchReason,omitempty"`
	RuntimeVersion string             `json:"runtimeVersion,omitempty"`
	SetupError     string             `json:"setupError,omitempty"`
	Members        []exitMemberStatus `json:"members"`
}

// exitRunner performs the platform work of carrying traffic through an exit.
type exitRunner interface {
	prepare() (string, error)
	cleanup(directory string)
	connect(ctx context.Context, directory, device string, spec exitMemberSpec) (exitSession, error)
}

type exitSession interface {
	exitIP(ctx context.Context) (string, error)
	check(ctx context.Context) (time.Duration, error)
	done() <-chan struct{}
	traffic() (int64, int64)
	close()
}

type exitMember struct {
	spec        exitMemberSpec
	state       string
	device      string
	exitIP      string
	err         string
	latency     time.Duration
	connectedAt time.Time
	lastOkAt    time.Time
	session     exitSession
	cancel      context.CancelFunc
	done        chan struct{}
}

type exitGroup struct {
	name         string
	order        []string
	approved     map[string]bool
	members      map[string]*exitMember
	activeID     string
	activeSince  time.Time
	switchReason string
}

type exitPoolManager struct {
	mu             sync.Mutex
	directory      string
	runner         exitRunner
	groups         map[string]*exitGroup
	closing        map[string]chan struct{}
	runtimeVersion string
	setupError     string
	setupAt        time.Time
	setupMu        sync.Mutex
	now            func() time.Time
}

func newExitPoolManager() *exitPoolManager {
	executable, err := os.Executable()
	base := "."
	if err == nil {
		base = filepath.Dir(executable)
	}
	manager := &exitPoolManager{
		directory: filepath.Join(base, "exit-runtime"),
		runner:    newExitRunner(),
		groups:    make(map[string]*exitGroup),
		closing:   make(map[string]chan struct{}),
		now:       time.Now,
	}
	dialer.ExitInterfaceResolver = manager.activeDevice
	return manager
}

// activeDevice is the lookup used for every outbound connection of a service
// bound to an exit group. It fails when the group has no usable exit.
func (m *exitPoolManager) activeDevice(group string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	g := m.groups[group]
	if g == nil {
		return "", dialer.ErrExitUnavailable
	}
	member := g.members[g.activeID]
	if member == nil || !g.usable(member) {
		return "", dialer.ErrExitUnavailable
	}
	return member.device, nil
}

func (m *exitPoolManager) sync(request exitPoolSyncRequest) (exitPoolStatus, error) {
	request, err := normalizeExitPoolRequest(request)
	if err != nil {
		return exitPoolStatus{}, err
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return exitPoolStatus{}, fmt.Errorf("create exit runtime directory: %w", err)
	}
	if payload, err := json.Marshal(request); err == nil {
		if err := writeRuntimeFile(m.groupPath(request.Group), payload, 0600); err != nil {
			return exitPoolStatus{}, fmt.Errorf("save exit group: %w", err)
		}
	}
	m.apply(request)
	return m.status(request.Group)
}

func (m *exitPoolManager) apply(request exitPoolSyncRequest) {
	m.mu.Lock()
	defer m.mu.Unlock()
	g := m.groups[request.Group]
	if g == nil {
		g = &exitGroup{name: request.Group, members: make(map[string]*exitMember)}
		m.groups[request.Group] = g
	}
	wanted := make(map[string]exitMemberSpec, len(request.Members))
	g.order = g.order[:0]
	for _, spec := range request.Members {
		wanted[spec.ID] = spec
		g.order = append(g.order, spec.ID)
	}
	for id, member := range g.members {
		spec, keep := wanted[id]
		if keep && spec == member.spec {
			continue
		}
		m.release(member)
		delete(g.members, id)
	}
	for _, id := range g.order {
		if g.members[id] != nil {
			continue
		}
		ctx, cancel := context.WithCancel(context.Background())
		member := &exitMember{spec: wanted[id], state: exitStateConnecting, device: exitDeviceName(g.name, id),
			cancel: cancel, done: make(chan struct{})}
		g.members[id] = member
		go m.run(ctx, g.name, member, m.closing[member.device])
	}
	g.approved = make(map[string]bool, len(request.ApprovedIPs))
	for _, ip := range request.ApprovedIPs {
		g.approved[ip] = true
	}
	m.reselect(g, "exit list updated")
}

func (m *exitPoolManager) delete(group string) error {
	if !exitGroupPattern.MatchString(group) {
		return errors.New("invalid exit group name")
	}
	m.mu.Lock()
	if g := m.groups[group]; g != nil {
		for _, member := range g.members {
			m.release(member)
		}
		delete(m.groups, group)
	}
	m.mu.Unlock()
	if err := os.Remove(m.groupPath(group)); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (m *exitPoolManager) status(group string) (exitPoolStatus, error) {
	if !exitGroupPattern.MatchString(group) {
		return exitPoolStatus{}, errors.New("invalid exit group name")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	status := exitPoolStatus{Group: group, RuntimeVersion: m.runtimeVersion, SetupError: m.setupError, Members: []exitMemberStatus{}}
	g := m.groups[group]
	if g == nil {
		return status, nil
	}
	status.SwitchReason = g.switchReason
	if !g.activeSince.IsZero() {
		status.ActiveSince = g.activeSince.UnixMilli()
	}
	for _, id := range g.order {
		member := g.members[id]
		if member == nil {
			continue
		}
		item := exitMemberStatus{ID: id, State: member.state, Interface: member.device, ExitIP: member.exitIP,
			Approved: member.exitIP != "" && g.approved[member.exitIP], Active: id == g.activeID && g.usable(member),
			LatencyMs: member.latency.Milliseconds(), Error: member.err}
		if !member.connectedAt.IsZero() {
			item.ConnectedAt = member.connectedAt.UnixMilli()
		}
		if !member.lastOkAt.IsZero() {
			item.LastOkAt = member.lastOkAt.UnixMilli()
		}
		if member.session != nil && member.state == exitStateUp {
			item.RxBytes, item.TxBytes = member.session.traffic()
		}
		if item.Active {
			status.ActiveID = id
			status.ActiveExitIP = member.exitIP
		}
		status.Members = append(status.Members, item)
	}
	return status, nil
}

func (m *exitPoolManager) restore() {
	m.runner.cleanup(m.directory)
	entries, err := os.ReadDir(m.directory)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".group.json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(m.directory, entry.Name()))
		if err != nil {
			continue
		}
		var request exitPoolSyncRequest
		if err := json.Unmarshal(data, &request); err != nil {
			fmt.Printf("exit group restore %s failed: %v\n", entry.Name(), err)
			continue
		}
		request, err = normalizeExitPoolRequest(request)
		if err != nil {
			fmt.Printf("exit group restore %s failed: %v\n", entry.Name(), err)
			continue
		}
		m.apply(request)
	}
}

func (m *exitPoolManager) stopAll() {
	m.mu.Lock()
	var pending []chan struct{}
	for _, g := range m.groups {
		for _, member := range g.members {
			m.release(member)
			pending = append(pending, member.done)
		}
		g.members = make(map[string]*exitMember)
		g.activeID = ""
	}
	m.mu.Unlock()
	deadline := time.After(5 * time.Second)
	for _, done := range pending {
		select {
		case <-done:
		case <-deadline:
		}
	}
	m.runner.cleanup(m.directory)
}

// release stops a member. Its device name may be needed again right away, so
// the next member on that device waits until this one has fully shut down.
// The caller holds m.mu.
func (m *exitPoolManager) release(member *exitMember) {
	member.cancel()
	if m.closing == nil {
		m.closing = make(map[string]chan struct{})
	}
	m.closing[member.device] = member.done
}

func (m *exitPoolManager) run(ctx context.Context, group string, member *exitMember, previous <-chan struct{}) {
	defer func() {
		close(member.done)
		m.mu.Lock()
		if m.closing[member.device] == member.done {
			delete(m.closing, member.device)
		}
		m.mu.Unlock()
	}()
	if previous != nil {
		select {
		case <-previous:
		case <-ctx.Done():
			return
		}
	}
	if err := m.ensureRuntime(); err != nil {
		m.fail(group, member, err)
		return
	}
	connectCtx, cancelConnect := context.WithTimeout(ctx, exitConnectTimeout)
	session, err := m.runner.connect(connectCtx, m.directory, member.device, member.spec)
	cancelConnect()
	if err != nil {
		m.fail(group, member, err)
		return
	}
	defer session.close()

	exitIP, err := probeExitIP(ctx, session)
	if err != nil {
		m.fail(group, member, fmt.Errorf("exit carried no traffic: %w", err))
		return
	}
	m.update(group, member, "exit connected", func() {
		member.state = exitStateUp
		member.session = session
		member.exitIP = exitIP
		member.connectedAt = m.now()
		member.lastOkAt = member.connectedAt
	})

	lastIPCheck := m.now()
	failures := 0
	timer := time.NewTimer(exitHealthInterval)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-session.done():
			m.fail(group, member, errors.New("exit tunnel closed"))
			return
		case <-timer.C:
		}
		checkCtx, cancelCheck := context.WithTimeout(ctx, 6*time.Second)
		latency, err := session.check(checkCtx)
		cancelCheck()
		if ctx.Err() != nil {
			return
		}
		if err != nil {
			failures++
			if failures >= exitHealthFailures {
				m.fail(group, member, fmt.Errorf("exit stopped responding: %w", err))
				return
			}
			timer.Reset(exitHealthRetry)
			continue
		}
		failures = 0
		recheckIP := m.now().Sub(lastIPCheck) >= exitIPRecheckInterval
		currentIP := ""
		if recheckIP {
			lastIPCheck = m.now()
			if ip, err := probeExitIP(ctx, session); err == nil {
				currentIP = ip
			}
		}
		m.update(group, member, "exit address changed", func() {
			member.latency = latency
			member.lastOkAt = m.now()
			if currentIP != "" {
				member.exitIP = currentIP
			}
		})
		timer.Reset(exitHealthInterval)
	}
}

func probeExitIP(ctx context.Context, session exitSession) (string, error) {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		probeCtx, cancel := context.WithTimeout(ctx, 12*time.Second)
		ip, err := session.exitIP(probeCtx)
		cancel()
		if err == nil {
			return ip, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			break
		}
	}
	return "", lastErr
}

func (m *exitPoolManager) ensureRuntime() error {
	m.setupMu.Lock()
	defer m.setupMu.Unlock()
	m.mu.Lock()
	version, setupError, setupAt := m.runtimeVersion, m.setupError, m.setupAt
	m.mu.Unlock()
	if version != "" {
		return nil
	}
	if setupError != "" && m.now().Sub(setupAt) < exitSetupRetry {
		return errors.New(setupError)
	}
	version, err := m.runner.prepare()
	m.mu.Lock()
	defer m.mu.Unlock()
	m.setupAt = m.now()
	if err != nil {
		m.setupError = err.Error()
		return err
	}
	m.runtimeVersion = version
	m.setupError = ""
	return nil
}

func (m *exitPoolManager) fail(group string, member *exitMember, err error) {
	m.update(group, member, "exit failed", func() {
		member.state = exitStateFailed
		member.session = nil
		member.err = conciseExitError(err)
	})
}

// update applies a change to a member and re-evaluates which exit is active.
// A member that was replaced or removed in the meantime is ignored.
func (m *exitPoolManager) update(group string, member *exitMember, reason string, change func()) {
	m.mu.Lock()
	defer m.mu.Unlock()
	g := m.groups[group]
	if g == nil || g.members[member.spec.ID] != member {
		return
	}
	change()
	m.reselect(g, reason)
}

// reselect keeps the current exit while it is usable and otherwise moves to
// the first usable one in panel order. With none usable the group has no
// active exit and its connections fail.
func (m *exitPoolManager) reselect(g *exitGroup, reason string) {
	if current := g.members[g.activeID]; current != nil && g.usable(current) {
		return
	}
	next := ""
	for _, id := range g.order {
		if member := g.members[id]; member != nil && g.usable(member) {
			next = id
			break
		}
	}
	if next == g.activeID {
		return
	}
	g.activeID = next
	g.activeSince = m.now()
	g.switchReason = reason
}

func (g *exitGroup) usable(member *exitMember) bool {
	return member.state == exitStateUp && member.exitIP != "" && g.approved[member.exitIP]
}

func (m *exitPoolManager) groupPath(group string) string {
	return filepath.Join(m.directory, group+".group.json")
}

func normalizeExitPoolRequest(request exitPoolSyncRequest) (exitPoolSyncRequest, error) {
	request.Group = strings.TrimSpace(request.Group)
	if !exitGroupPattern.MatchString(request.Group) {
		return request, errors.New("invalid exit group name")
	}
	if len(request.Members) > exitPoolMaxMembers {
		return request, fmt.Errorf("an exit group accepts at most %d members", exitPoolMaxMembers)
	}
	seen := make(map[string]bool, len(request.Members))
	for index := range request.Members {
		spec, err := normalizeExitMember(request.Members[index])
		if err != nil {
			return request, fmt.Errorf("exit member %d: %w", index+1, err)
		}
		if seen[spec.ID] {
			return request, fmt.Errorf("exit member %s is listed twice", spec.ID)
		}
		seen[spec.ID] = true
		request.Members[index] = spec
	}
	approved := make([]string, 0, len(request.ApprovedIPs))
	for _, value := range request.ApprovedIPs {
		ip := net.ParseIP(strings.TrimSpace(value)).To4()
		if ip == nil {
			return request, fmt.Errorf("invalid approved exit address %q", value)
		}
		approved = append(approved, ip.String())
	}
	sort.Strings(approved)
	request.ApprovedIPs = approved
	return request, nil
}

func normalizeExitMember(spec exitMemberSpec) (exitMemberSpec, error) {
	spec.ID = strings.TrimSpace(spec.ID)
	spec.Host = strings.TrimSpace(spec.Host)
	spec.Proto = strings.ToLower(strings.TrimSpace(spec.Proto))
	spec.Cipher = strings.ToUpper(strings.TrimSpace(spec.Cipher))
	spec.Auth = strings.ToUpper(strings.TrimSpace(spec.Auth))
	if !exitMemberPattern.MatchString(spec.ID) {
		return spec, errors.New("invalid id")
	}
	if spec.Attempt < 0 {
		return spec, errors.New("invalid attempt")
	}
	host := net.ParseIP(spec.Host).To4()
	if host == nil || host.IsLoopback() || host.IsPrivate() || host.IsUnspecified() || host.IsMulticast() || host.IsLinkLocalUnicast() {
		return spec, errors.New("host must be a public IPv4 address")
	}
	spec.Host = host.String()
	if spec.Port < 1 || spec.Port > 65535 {
		return spec, errors.New("invalid port")
	}
	if spec.Proto != "tcp" && spec.Proto != "udp" {
		return spec, errors.New("protocol must be tcp or udp")
	}
	if !exitCiphers[spec.Cipher] {
		return spec, errors.New("unsupported cipher")
	}
	if !exitAuthDigests[spec.Auth] {
		return spec, errors.New("unsupported auth digest")
	}
	var err error
	if spec.CA, err = normalizeExitPEM(spec.CA, "CERTIFICATE"); err != nil || spec.CA == "" {
		return spec, errors.New("invalid CA certificate")
	}
	if spec.Cert, err = normalizeExitPEM(spec.Cert, "CERTIFICATE"); err != nil {
		return spec, errors.New("invalid client certificate")
	}
	if spec.Key, err = normalizeExitPEM(spec.Key, "PRIVATE KEY"); err != nil {
		return spec, errors.New("invalid client key")
	}
	if (spec.Cert == "") != (spec.Key == "") {
		return spec, errors.New("client certificate and key must be given together")
	}
	return spec, nil
}

// normalizeExitPEM re-encodes PEM input so that only well-formed blocks of
// the expected kind can reach the generated configuration.
func normalizeExitPEM(value, kind string) (string, error) {
	rest := []byte(strings.TrimSpace(value))
	if len(rest) == 0 {
		return "", nil
	}
	if len(rest) > 32*1024 {
		return "", errors.New("PEM data is too large")
	}
	var output strings.Builder
	for len(rest) > 0 {
		block, remaining := pem.Decode(rest)
		if block == nil {
			if strings.TrimSpace(string(rest)) != "" {
				return "", errors.New("unexpected data around PEM block")
			}
			break
		}
		if !strings.HasSuffix(block.Type, kind) || len(block.Headers) > 0 || len(block.Bytes) == 0 {
			return "", errors.New("unexpected PEM block")
		}
		output.Write(pem.EncodeToMemory(&pem.Block{Type: block.Type, Bytes: block.Bytes}))
		rest = remaining
	}
	if output.Len() == 0 {
		return "", errors.New("no PEM block found")
	}
	return output.String(), nil
}

// exitTunnelConfig renders the OpenVPN client configuration for a member.
// Addresses and routes pushed by the remote side are never executed: the
// Agent assigns the tunnel address itself and leaves the routing table alone.
func exitTunnelConfig(spec exitMemberSpec, device, managementSocket string, version [2]int) string {
	proto := "udp4"
	if spec.Proto == "tcp" {
		proto = "tcp4-client"
	}
	lines := []string{
		"client",
		"dev " + device,
		"dev-type tun",
		"proto " + proto,
		"remote " + spec.Host + " " + strconv.Itoa(spec.Port),
		"nobind",
		"resolv-retry 0",
		"connect-retry-max 1",
		"connect-timeout 10",
		"server-poll-timeout 10",
		"remap-usr1 SIGTERM",
		"route-nopull",
		"route-noexec",
		"ifconfig-noexec",
		`pull-filter ignore "redirect-gateway"`,
		`pull-filter ignore "redirect-private"`,
		`pull-filter ignore "route"`,
		`pull-filter ignore "dhcp-option"`,
		`pull-filter ignore "dns"`,
		`pull-filter ignore "ifconfig-ipv6"`,
		`pull-filter ignore "block-"`,
		"cipher " + spec.Cipher,
		"data-ciphers " + exitDataCiphers(spec.Cipher),
		"data-ciphers-fallback " + spec.Cipher,
		"auth " + spec.Auth,
		"auth-nocache",
		"script-security 1",
		"verb 3",
		"management " + managementSocket + " unix",
	}
	if version[0] > 2 || (version[0] == 2 && version[1] >= 6) {
		lines = append(lines, "disable-dco")
	}
	lines = append(lines, "<ca>", strings.TrimSpace(spec.CA), "</ca>")
	if spec.Cert != "" {
		lines = append(lines, "<cert>", strings.TrimSpace(spec.Cert), "</cert>", "<key>", strings.TrimSpace(spec.Key), "</key>")
	}
	return strings.Join(lines, "\n") + "\n"
}

func exitDataCiphers(cipher string) string {
	ciphers := []string{"AES-256-GCM", "AES-128-GCM"}
	for _, existing := range ciphers {
		if existing == cipher {
			return strings.Join(ciphers, ":")
		}
	}
	return strings.Join(append(ciphers, cipher), ":")
}

// parseExitManagementState reads a state line of the OpenVPN management
// interface and returns the state name and, once connected, the tunnel address.
func parseExitManagementState(line string) (state, local string, ok bool) {
	line = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), ">STATE:"))
	fields := strings.Split(line, ",")
	if len(fields) < 2 {
		return "", "", false
	}
	if _, err := strconv.ParseInt(fields[0], 10, 64); err != nil {
		return "", "", false
	}
	state = fields[1]
	if len(fields) > 3 {
		local = fields[3]
	}
	return state, local, true
}

// validExitTunnelAddress accepts only a private IPv4 address that does not
// overlap a network the node is already attached to, so that an exit cannot
// make the node treat somebody else's address as its own.
func validExitTunnelAddress(value string, existing []*net.IPNet) (net.IP, error) {
	ip := net.ParseIP(strings.TrimSpace(value)).To4()
	if ip == nil {
		return nil, fmt.Errorf("exit assigned an invalid tunnel address %q", value)
	}
	_, carrierGrade, _ := net.ParseCIDR("100.64.0.0/10")
	if !ip.IsPrivate() && !carrierGrade.Contains(ip) {
		return nil, fmt.Errorf("exit assigned a public tunnel address %s", ip)
	}
	for _, network := range existing {
		if network != nil && network.Contains(ip) {
			return nil, fmt.Errorf("exit tunnel address %s overlaps local network %s", ip, network)
		}
	}
	return ip, nil
}

func parseOpenVPNVersion(output string) ([2]int, string, error) {
	match := regexp.MustCompile(`OpenVPN (\d+)\.(\d+)(\.\d+)?`).FindStringSubmatch(output)
	if match == nil {
		return [2]int{}, "", errors.New("unable to read the OpenVPN version")
	}
	major, _ := strconv.Atoi(match[1])
	minor, _ := strconv.Atoi(match[2])
	return [2]int{major, minor}, match[1] + "." + match[2] + match[3], nil
}

func exitDeviceName(group, id string) string {
	checksum := sha256.Sum256([]byte(group + "/" + id))
	return "cnx" + hex.EncodeToString(checksum[:5])
}

func conciseExitError(err error) string {
	if err == nil {
		return ""
	}
	message := strings.TrimSpace(err.Error())
	if len(message) > 300 {
		message = message[:300]
	}
	return message
}
