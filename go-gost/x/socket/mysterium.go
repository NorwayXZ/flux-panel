package socket

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

var syscallZero = syscall.Signal(0)

// Mysterium residential exits. The Agent runs Mysterium's node software (myst)
// as a consumer in proxy mode: every connection to a residential provider is a
// user-space WireGuard tunnel behind a local HTTP proxy on 127.0.0.1, so the
// node's own routes are untouched. Private proxy routers use these local
// proxies as exits. The panel decides which countries are connected.

const (
	mysteriumVersion = "1.39.7"
	mysteriumAPIPort = 4450
	// A trial install made with scripts/mysterium-poc.sh; its funded account is taken over.
	mysteriumTrialDirectory = "/opt/cloudnest-mysterium"
)

var mysteriumTraceURL = "https://www.cloudflare.com/cdn-cgi/trace"

type mysteriumConnection struct {
	ProxyPort        int      `json:"proxyPort"`
	Country          string   `json:"country"`
	ProviderID       string   `json:"providerId,omitempty"`
	ExcludeProviders []string `json:"excludeProviders,omitempty"`
}

type mysteriumManager struct {
	mu        sync.Mutex
	directory string
	binary    string
	api       string
	client    *http.Client
	cmd       *exec.Cmd
	stopping  bool
	// desired connections by proxy port, restored after a restart
	wanted map[int]mysteriumConnection
}

func newMysteriumManager() *mysteriumManager {
	executable, err := os.Executable()
	base := "."
	if err == nil {
		base = filepath.Dir(executable)
	}
	directory := filepath.Join(base, "mysterium")
	return &mysteriumManager{
		directory: directory,
		binary:    filepath.Join(directory, "bin", "myst"),
		api:       fmt.Sprintf("http://127.0.0.1:%d", mysteriumAPIPort),
		client:    &http.Client{Timeout: 90 * time.Second},
		wanted:    map[int]mysteriumConnection{},
	}
}

func (m *mysteriumManager) statePath() string {
	return filepath.Join(m.directory, "connections.json")
}

// installed reports whether Mysterium was set up on this node before.
func (m *mysteriumManager) installed() bool {
	_, err := os.Stat(filepath.Join(m.directory, "data", "keystore"))
	return err == nil
}

// ensureRunning downloads myst when needed, takes over a trial install's
// account, and starts the daemon.
func (m *mysteriumManager) ensureRunning() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.cmd != nil && processAlive(m.cmd) && m.healthy() {
		return nil
	}
	if err := os.MkdirAll(m.directory, 0700); err != nil {
		return err
	}
	if err := m.ensureBinary(); err != nil {
		return err
	}
	m.adoptTrial()
	for _, dir := range []string{"data", "logs"} {
		if err := os.MkdirAll(filepath.Join(m.directory, dir), 0700); err != nil {
			return err
		}
	}
	logFile, err := os.OpenFile(filepath.Join(m.directory, "logs", "daemon.out"), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return err
	}
	// --proxymode keeps connections as local proxies without touching routes;
	// --proxy.bind.address keeps them on loopback (myst defaults to 0.0.0.0).
	cmd := exec.Command(m.binary,
		"--data-dir="+filepath.Join(m.directory, "data"),
		"--config-dir="+filepath.Join(m.directory, "bin", "config"),
		"--log-dir="+filepath.Join(m.directory, "logs"),
		"--proxymode", "--proxy.bind.address=127.0.0.1",
		"--tequilapi.address=127.0.0.1", "--tequilapi.port="+strconv.Itoa(mysteriumAPIPort),
		"--ui.enable=false", "daemon")
	cmd.Env = mysteriumDaemonEnv()
	cmd.Stdout, cmd.Stderr = logFile, logFile
	if err := cmd.Start(); err != nil {
		logFile.Close()
		return fmt.Errorf("start myst: %w", err)
	}
	logFile.Close()
	m.cmd = cmd
	m.stopping = false
	go m.watch(cmd)
	deadline := time.Now().Add(45 * time.Second)
	for time.Now().Before(deadline) {
		if m.healthy() {
			return nil
		}
		time.Sleep(time.Second)
	}
	return fmt.Errorf("myst did not become ready: %s", m.startupLog())
}

// systemd does not always supply HOME to system services. myst reads it while
// constructing its CLI, even when explicit data/config paths are supplied.
// Set the child process's real account home without changing the Agent's env.
func mysteriumDaemonEnv() []string {
	env := os.Environ()
	if os.Getenv("HOME") != "" {
		return env
	}
	current, err := user.Current()
	if err != nil || current.HomeDir == "" {
		return env
	}
	filtered := make([]string, 0, len(env)+1)
	for _, item := range env {
		if !strings.HasPrefix(item, "HOME=") {
			filtered = append(filtered, item)
		}
	}
	return append(filtered, "HOME="+current.HomeDir)
}

func (m *mysteriumManager) startupLog() string {
	file, err := os.Open(filepath.Join(m.directory, "logs", "daemon.out"))
	if err != nil {
		return "see mysterium/logs/daemon.out on the node"
	}
	defer file.Close()
	// Only return a small tail, even if a failed daemon produced a large log.
	if info, err := file.Stat(); err == nil && info.Size() > 2048 {
		_, _ = file.Seek(-2048, io.SeekEnd)
	}
	data, err := io.ReadAll(io.LimitReader(file, 2048))
	if err != nil || strings.TrimSpace(string(data)) == "" {
		return "see mysterium/logs/daemon.out on the node"
	}
	return strings.TrimSpace(string(data))
}

func (m *mysteriumManager) healthy() bool {
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Get(m.api + "/healthcheck")
	if err != nil {
		return false
	}
	response.Body.Close()
	return response.StatusCode == http.StatusOK
}

func (m *mysteriumManager) watch(cmd *exec.Cmd) {
	_ = cmd.Wait()
	m.mu.Lock()
	restart := m.cmd == cmd && !m.stopping
	m.mu.Unlock()
	if !restart {
		return
	}
	time.Sleep(5 * time.Second)
	if err := m.ensureRunning(); err == nil {
		m.reconnectWanted()
	}
}

func (m *mysteriumManager) stop() {
	m.mu.Lock()
	cmd := m.cmd
	m.stopping = true
	m.cmd = nil
	m.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Signal(os.Interrupt)
		done := make(chan struct{})
		go func() { _, _ = cmd.Process.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(15 * time.Second):
			_ = cmd.Process.Kill()
		}
	}
}

func mysteriumAsset() (string, error) {
	if runtime.GOOS != "linux" && runtime.GOOS != "darwin" {
		return "", fmt.Errorf("mysterium is not supported on %s", runtime.GOOS)
	}
	switch runtime.GOARCH {
	case "amd64", "arm64":
		return fmt.Sprintf("myst_%s_%s.tar.gz", runtime.GOOS, runtime.GOARCH), nil
	case "arm":
		return "myst_linux_arm.tar.gz", nil
	}
	return "", fmt.Errorf("mysterium is not supported on %s/%s", runtime.GOOS, runtime.GOARCH)
}

func (m *mysteriumManager) ensureBinary() error {
	versionFile := filepath.Join(m.directory, "bin", ".version")
	if current, err := os.ReadFile(versionFile); err == nil && strings.TrimSpace(string(current)) == mysteriumVersion {
		if info, err := os.Stat(m.binary); err == nil && info.Mode().IsRegular() {
			return nil
		}
	}
	asset, err := mysteriumAsset()
	if err != nil {
		return err
	}
	archive := filepath.Join(m.directory, ".myst-download.tar.gz")
	client := &http.Client{Timeout: 3 * time.Minute}
	link := "https://github.com/mysteriumnetwork/node/releases/download/" + mysteriumVersion + "/" + asset
	if err := downloadFile(client, link, archive, 128*1024*1024); err != nil {
		return fmt.Errorf("download myst: %w", err)
	}
	defer os.Remove(archive)
	target := filepath.Join(m.directory, "bin")
	if err := os.RemoveAll(target + ".new"); err != nil {
		return err
	}
	if err := extractTarGz(archive, target+".new"); err != nil {
		return err
	}
	if _, err := os.Stat(filepath.Join(target+".new", "myst")); err != nil {
		return errors.New("myst executable is missing from the release archive")
	}
	_ = os.RemoveAll(target)
	if err := os.Rename(target+".new", target); err != nil {
		return err
	}
	return os.WriteFile(versionFile, []byte(mysteriumVersion), 0600)
}

// extractTarGz unpacks regular files and directories, refusing paths that
// would leave the target directory.
func extractTarGz(archivePath, target string) error {
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	gz, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gz.Close()
	reader := tar.NewReader(gz)
	for {
		header, err := reader.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		name := filepath.Clean(header.Name)
		if name == "." || strings.HasPrefix(name, "..") || filepath.IsAbs(name) {
			continue
		}
		path := filepath.Join(target, name)
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(path, 0755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
				return err
			}
			out, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(header.Mode)&0755|0600)
			if err != nil {
				return err
			}
			if _, err := io.Copy(out, io.LimitReader(reader, 256*1024*1024)); err != nil {
				out.Close()
				return err
			}
			out.Close()
		}
	}
}

// adoptTrial moves the account of a trial install into the Agent's directory
// once, so its balance keeps working. The trial daemon is stopped first.
func (m *mysteriumManager) adoptTrial() {
	data := filepath.Join(m.directory, "data")
	if _, err := os.Stat(filepath.Join(data, "keystore")); err == nil {
		return
	}
	trialData := filepath.Join(mysteriumTrialDirectory, "data")
	if _, err := os.Stat(filepath.Join(trialData, "keystore")); err != nil {
		return
	}
	if pid, err := os.ReadFile(filepath.Join(mysteriumTrialDirectory, "myst.pid")); err == nil {
		if value, err := strconv.Atoi(strings.TrimSpace(string(pid))); err == nil {
			if process, err := os.FindProcess(value); err == nil {
				_ = process.Signal(os.Interrupt)
				for i := 0; i < 15 && process.Signal(syscallZero) == nil; i++ {
					time.Sleep(time.Second)
				}
				_ = process.Kill()
			}
		}
	}
	_ = os.RemoveAll(data)
	if err := exec.Command("cp", "-a", trialData, data).Run(); err != nil {
		return
	}
	_ = os.Rename(trialData, trialData+".moved-to-agent")
}

// ---- Tequilapi ----

func (m *mysteriumManager) call(method, path string, body interface{}, out interface{}) error {
	var reader io.Reader
	if body != nil {
		content, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(content)
	}
	request, err := http.NewRequest(method, m.api+path, reader)
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := m.client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	content, _ := io.ReadAll(io.LimitReader(response.Body, 32*1024*1024))
	if response.StatusCode >= 300 {
		return fmt.Errorf("myst %s %s: HTTP %d %s", method, path, response.StatusCode, mysteriumErrorMessage(content))
	}
	if out != nil && len(content) > 0 {
		return json.Unmarshal(content, out)
	}
	return nil
}

func mysteriumErrorMessage(content []byte) string {
	var parsed struct {
		Message string `json:"message"`
		Error   struct {
			Message string `json:"message"`
		} `json:"error"`
		Detail string `json:"detail"`
	}
	if json.Unmarshal(content, &parsed) == nil {
		for _, text := range []string{parsed.Error.Message, parsed.Message, parsed.Detail} {
			if text != "" {
				return text
			}
		}
	}
	text := strings.TrimSpace(string(content))
	if len(text) > 300 {
		text = text[:300]
	}
	return text
}

type mysteriumAccount struct {
	Installed          bool                     `json:"installed"`
	Identity           string                   `json:"identity,omitempty"`
	RegistrationStatus string                   `json:"registrationStatus,omitempty"`
	ChannelAddress     string                   `json:"channelAddress,omitempty"`
	Balance            float64                  `json:"balance"`
	TermsAgreed        bool                     `json:"termsAgreed"`
	Connections        []mysteriumConnectionDTO `json:"connections"`
}

type mysteriumConnectionDTO struct {
	ProxyPort     int    `json:"proxyPort"`
	Country       string `json:"country"`
	Status        string `json:"status"`
	ProviderID    string `json:"providerId,omitempty"`
	SessionID     string `json:"sessionId,omitempty"`
	BytesReceived int64  `json:"bytesReceived"`
	BytesSent     int64  `json:"bytesSent"`
}

func (m *mysteriumManager) identity() (string, error) {
	var current struct {
		ID string `json:"id"`
	}
	if err := m.call(http.MethodPut, "/identities/current", map[string]string{"passphrase": ""}, &current); err != nil {
		return "", err
	}
	if current.ID == "" {
		return "", errors.New("myst returned no identity")
	}
	return current.ID, nil
}

// setup starts myst, creates or unlocks the account and records the consumer
// terms agreement the panel's admin gave.
func (m *mysteriumManager) setup(agreeTerms bool) (mysteriumAccount, error) {
	if err := m.ensureRunning(); err != nil {
		return mysteriumAccount{}, err
	}
	if agreeTerms {
		var terms struct {
			CurrentVersion string `json:"current_version"`
		}
		if err := m.call(http.MethodGet, "/terms", nil, &terms); err == nil && terms.CurrentVersion != "" {
			_ = m.call(http.MethodPost, "/terms", map[string]interface{}{"agreed_consumer": true, "agreed_version": terms.CurrentVersion}, nil)
		}
	}
	id, err := m.identity()
	if err != nil {
		return mysteriumAccount{}, err
	}
	var status struct {
		RegistrationStatus string `json:"registration_status"`
	}
	if err := m.call(http.MethodGet, "/identities/"+id, nil, &status); err == nil && status.RegistrationStatus == "Unregistered" {
		// Registration finishes with the first top-up.
		_ = m.call(http.MethodPost, "/identities/"+id+"/register", map[string]string{}, nil)
	}
	return m.status(nil)
}

func (m *mysteriumManager) status(ports []mysteriumConnection) (mysteriumAccount, error) {
	if !m.installed() {
		return mysteriumAccount{Installed: false}, nil
	}
	if err := m.ensureRunning(); err != nil {
		return mysteriumAccount{Installed: true}, err
	}
	id, err := m.identity()
	if err != nil {
		return mysteriumAccount{Installed: true}, err
	}
	_ = m.call(http.MethodPut, "/identities/"+id+"/balance/refresh", nil, nil)
	var info struct {
		RegistrationStatus string `json:"registration_status"`
		ChannelAddress     string `json:"channel_address"`
		BalanceTokens      struct {
			Ether string `json:"ether"`
		} `json:"balance_tokens"`
	}
	if err := m.call(http.MethodGet, "/identities/"+id, nil, &info); err != nil {
		return mysteriumAccount{Installed: true}, err
	}
	balance, _ := strconv.ParseFloat(info.BalanceTokens.Ether, 64)
	var terms struct {
		AgreedConsumer bool `json:"agreed_consumer"`
	}
	_ = m.call(http.MethodGet, "/terms", nil, &terms)
	account := mysteriumAccount{Installed: true, Identity: id, RegistrationStatus: info.RegistrationStatus,
		ChannelAddress: info.ChannelAddress, Balance: balance, TermsAgreed: terms.AgreedConsumer, Connections: []mysteriumConnectionDTO{}}
	if ports == nil {
		m.mu.Lock()
		for _, wanted := range m.wanted {
			ports = append(ports, wanted)
		}
		m.mu.Unlock()
	}
	for _, port := range ports {
		account.Connections = append(account.Connections, m.connectionStatus(port))
	}
	return account, nil
}

func (m *mysteriumManager) connectionStatus(port mysteriumConnection) mysteriumConnectionDTO {
	dto := mysteriumConnectionDTO{ProxyPort: port.ProxyPort, Country: port.Country, Status: "NotConnected"}
	var status struct {
		Status    string `json:"status"`
		SessionID string `json:"session_id"`
		Proposal  struct {
			ProviderID string `json:"provider_id"`
		} `json:"proposal"`
	}
	if err := m.call(http.MethodGet, "/connection?id="+strconv.Itoa(port.ProxyPort), nil, &status); err == nil {
		dto.Status = status.Status
		dto.SessionID = status.SessionID
		dto.ProviderID = status.Proposal.ProviderID
	}
	// /connection/statistics reports zeros for proxy connections; /connection/traffic counts bytes.
	var traffic struct {
		BytesReceived int64 `json:"bytes_received"`
		BytesSent     int64 `json:"bytes_sent"`
	}
	if err := m.call(http.MethodGet, "/connection/traffic?id="+strconv.Itoa(port.ProxyPort), nil, &traffic); err == nil {
		dto.BytesReceived, dto.BytesSent = traffic.BytesReceived, traffic.BytesSent
	}
	return dto
}

type mysteriumTopupRequest struct {
	Gateway   string `json:"gateway"`
	AmountUSD string `json:"amountUsd"`
	Country   string `json:"country"`
}

func (m *mysteriumManager) topup(request mysteriumTopupRequest) (map[string]interface{}, error) {
	if request.Gateway != "paypal" && request.Gateway != "stripe" && request.Gateway != "coingate" {
		return nil, errors.New("unsupported payment gateway")
	}
	if err := m.ensureRunning(); err != nil {
		return nil, err
	}
	id, err := m.identity()
	if err != nil {
		return nil, err
	}
	currency := "USD"
	if request.Gateway == "coingate" {
		currency = "USDT"
	}
	var order struct {
		ID                string          `json:"id"`
		PayAmount         string          `json:"pay_amount"`
		PayCurrency       string          `json:"pay_currency"`
		ReceiveMyst       string          `json:"receive_myst"`
		PublicGatewayData json.RawMessage `json:"public_gateway_data"`
	}
	body := map[string]interface{}{"amount_usd": request.AmountUSD, "country": request.Country, "pay_currency": currency,
		"gateway_caller_data": map[string]interface{}{}}
	if err := m.call(http.MethodPost, "/v2/identities/"+id+"/"+request.Gateway+"/payment-order", body, &order); err != nil {
		return nil, err
	}
	var gateway map[string]interface{}
	_ = json.Unmarshal(order.PublicGatewayData, &gateway)
	link := ""
	for _, key := range []string{"checkout_url", "payment_url", "url"} {
		if text, ok := gateway[key].(string); ok && strings.HasPrefix(text, "https://") {
			link = text
			break
		}
	}
	if link == "" {
		return nil, errors.New("the payment gateway returned no payment link")
	}
	return map[string]interface{}{"orderId": order.ID, "payAmount": order.PayAmount, "payCurrency": order.PayCurrency,
		"receiveMyst": order.ReceiveMyst, "paymentUrl": link}, nil
}

type mysteriumProposal struct {
	ProviderID string `json:"provider_id"`
	Location   struct {
		Country string `json:"country"`
		City    string `json:"city"`
		ISP     string `json:"isp"`
		IPType  string `json:"ip_type"`
	} `json:"location"`
	Quality struct {
		Quality   float64 `json:"quality"`
		Latency   float64 `json:"latency"`
		Bandwidth float64 `json:"bandwidth"`
	} `json:"quality"`
}

// pickProviders returns residential providers in the country, best first,
// leaving out excluded ones.
func pickProviders(proposals []mysteriumProposal, exclude []string, limit int) []mysteriumProposal {
	skip := map[string]bool{}
	for _, id := range exclude {
		skip[strings.ToLower(id)] = true
	}
	candidates := make([]mysteriumProposal, 0, len(proposals))
	for _, proposal := range proposals {
		if skip[strings.ToLower(proposal.ProviderID)] || proposal.Location.IPType != "residential" {
			continue
		}
		candidates = append(candidates, proposal)
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		a, b := candidates[i].Quality, candidates[j].Quality
		if a.Quality != b.Quality {
			return a.Quality > b.Quality
		}
		return a.Bandwidth > b.Bandwidth
	})
	if len(candidates) > limit {
		candidates = candidates[:limit]
	}
	return candidates
}

type mysteriumConnectResult struct {
	ProxyPort  int    `json:"proxyPort"`
	Country    string `json:"country"`
	ProviderID string `json:"providerId"`
	ExitIP     string `json:"exitIp"`
	City       string `json:"city"`
	ISP        string `json:"isp"`
	LatencyMs  int64  `json:"latencyMs"`
}

// connect opens (or replaces) the connection on the proxy port to a
// residential provider in the country and checks that traffic goes through.
func (m *mysteriumManager) connect(request mysteriumConnection) (mysteriumConnectResult, error) {
	request.Country = strings.ToUpper(strings.TrimSpace(request.Country))
	if len(request.Country) != 2 || request.ProxyPort < 1024 || request.ProxyPort > 65535 {
		return mysteriumConnectResult{}, errors.New("invalid country or proxy port")
	}
	if err := m.ensureRunning(); err != nil {
		return mysteriumConnectResult{}, err
	}
	id, err := m.identity()
	if err != nil {
		return mysteriumConnectResult{}, err
	}
	var listed struct {
		Proposals []mysteriumProposal `json:"proposals"`
	}
	// Tequilapi's documentation calls this filter "country", but it reads "location_country".
	query := url.Values{"service_type": {"wireguard"}, "location_country": {request.Country}, "ip_type": {"residential"}}
	if err := m.call(http.MethodGet, "/proposals?"+query.Encode(), nil, &listed); err != nil {
		return mysteriumConnectResult{}, err
	}
	inCountry := listed.Proposals[:0]
	for _, proposal := range listed.Proposals {
		if strings.EqualFold(proposal.Location.Country, request.Country) {
			inCountry = append(inCountry, proposal)
		}
	}
	candidates := pickProviders(inCountry, request.ExcludeProviders, 4)
	if request.ProviderID != "" {
		candidates = append([]mysteriumProposal{{ProviderID: request.ProviderID}}, candidates...)
	}
	if len(candidates) == 0 {
		return mysteriumConnectResult{}, fmt.Errorf("no residential Mysterium provider available in %s", request.Country)
	}
	var lastErr error
	for _, candidate := range candidates {
		_ = m.call(http.MethodDelete, "/connection?id="+strconv.Itoa(request.ProxyPort), nil, nil)
		body := map[string]interface{}{"consumer_id": id, "provider_id": candidate.ProviderID, "service_type": "wireguard",
			"connect_options": map[string]interface{}{"proxy_port": request.ProxyPort}}
		if err := m.call(http.MethodPut, "/connection", body, nil); err != nil {
			lastErr = err
			continue
		}
		ip, latency, err := mysteriumProbe(request.ProxyPort)
		if err != nil {
			lastErr = err
			continue
		}
		m.mu.Lock()
		m.wanted[request.ProxyPort] = mysteriumConnection{ProxyPort: request.ProxyPort, Country: request.Country, ProviderID: candidate.ProviderID}
		m.mu.Unlock()
		m.saveWanted()
		return mysteriumConnectResult{ProxyPort: request.ProxyPort, Country: request.Country, ProviderID: candidate.ProviderID,
			ExitIP: ip, City: candidate.Location.City, ISP: candidate.Location.ISP, LatencyMs: latency}, nil
	}
	_ = m.call(http.MethodDelete, "/connection?id="+strconv.Itoa(request.ProxyPort), nil, nil)
	return mysteriumConnectResult{}, fmt.Errorf("could not connect to a residential provider in %s: %v", request.Country, lastErr)
}

func (m *mysteriumManager) disconnect(port int) error {
	m.mu.Lock()
	delete(m.wanted, port)
	m.mu.Unlock()
	m.saveWanted()
	if !m.healthy() {
		return nil
	}
	err := m.call(http.MethodDelete, "/connection?id="+strconv.Itoa(port), nil, nil)
	if err != nil && strings.Contains(err.Error(), "HTTP 4") {
		return nil
	}
	return err
}

// mysteriumProbe fetches Cloudflare's trace through the local proxy.
func mysteriumProbe(port int) (string, int64, error) {
	proxy := &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", port)}
	client := &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxy)}}
	started := time.Now()
	response, err := client.Get(mysteriumTraceURL)
	if err != nil {
		return "", 0, fmt.Errorf("exit check failed: %w", err)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 16*1024))
	latency := time.Since(started).Milliseconds()
	for _, line := range strings.Split(string(body), "\n") {
		if value, ok := strings.CutPrefix(strings.TrimSpace(line), "ip="); ok {
			return value, latency, nil
		}
	}
	return "", latency, errors.New("exit check returned no address")
}

func (m *mysteriumManager) saveWanted() {
	m.mu.Lock()
	list := make([]mysteriumConnection, 0, len(m.wanted))
	for _, connection := range m.wanted {
		list = append(list, connection)
	}
	m.mu.Unlock()
	content, err := json.Marshal(list)
	if err == nil {
		_ = os.WriteFile(m.statePath(), content, 0600)
	}
}

// reconnectWanted brings back the connections the panel asked for, e.g. after
// the Agent or myst restarted.
func (m *mysteriumManager) reconnectWanted() {
	m.mu.Lock()
	list := make([]mysteriumConnection, 0, len(m.wanted))
	for _, connection := range m.wanted {
		list = append(list, connection)
	}
	m.mu.Unlock()
	for _, connection := range list {
		if status := m.connectionStatus(connection); status.Status == "Connected" {
			continue
		}
		_, _ = m.connect(connection)
	}
}

// check probes one connection's exit.
func (m *mysteriumManager) check(port int) (map[string]interface{}, error) {
	ip, latency, err := mysteriumProbe(port)
	if err != nil {
		return nil, err
	}
	return map[string]interface{}{"exitIp": ip, "latencyMs": latency}, nil
}

func (m *mysteriumManager) restore() {
	content, err := os.ReadFile(m.statePath())
	if err != nil {
		return
	}
	var list []mysteriumConnection
	if json.Unmarshal(content, &list) != nil || len(list) == 0 {
		return
	}
	m.mu.Lock()
	for _, connection := range list {
		m.wanted[connection.ProxyPort] = connection
	}
	m.mu.Unlock()
	if err := m.ensureRunning(); err == nil {
		m.reconnectWanted()
	}
}
