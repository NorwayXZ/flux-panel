package socket

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/ice/v4"
	"github.com/pion/stun/v3"
	quic "github.com/quic-go/quic-go"
)

const natALPN = "cloudnest-nat/1"

type natPrepareRequest struct {
	RouteID               int64    `json:"routeId"`
	SessionID             string   `json:"sessionId"`
	Role                  string   `json:"role"`
	Token                 string   `json:"token"`
	STUNServers           []string `json:"stunServers"`
	ListenAddress         string   `json:"listenAddress"`
	FallbackAddress       string   `json:"fallbackAddress"`
	BackendAddress        string   `json:"backendAddress"`
	ConnectTimeoutSeconds int      `json:"connectTimeoutSeconds"`
}

type natConnectRequest struct {
	RouteID               int64    `json:"routeId"`
	SessionID             string   `json:"sessionId"`
	Role                  string   `json:"role"`
	Token                 string   `json:"token"`
	RemoteUsername        string   `json:"remoteUsernameFragment"`
	RemotePassword        string   `json:"remotePassword"`
	RemoteCandidates      []string `json:"remoteCandidates"`
	ExpectedFingerprint   string   `json:"expectedFingerprint"`
	ConnectTimeoutSeconds int      `json:"connectTimeoutSeconds"`
}

type natFallbackRequest struct {
	RouteID         int64  `json:"routeId"`
	ListenAddress   string `json:"listenAddress"`
	FallbackAddress string `json:"fallbackAddress"`
}

type natPrepareResponse struct {
	UsernameFragment       string   `json:"usernameFragment"`
	Password               string   `json:"password"`
	Candidates             []string `json:"candidates"`
	NATType                string   `json:"natType"`
	CertificateFingerprint string   `json:"certificateFingerprint,omitempty"`
}

type natPersistedSource struct {
	RouteID         int64  `json:"routeId"`
	ListenAddress   string `json:"listenAddress"`
	FallbackAddress string `json:"fallbackAddress"`
}

type natRuntimeManager struct {
	mu        sync.Mutex
	runtimes  map[int64]*natRuntime
	emit      func(string, map[string]interface{})
	stateFile string
}

type natRuntime struct {
	mu          sync.RWMutex
	request     natPrepareRequest
	agent       *ice.Agent
	iceConn     *ice.Conn
	transport   *quic.Transport
	quicConn    quic.Connection
	listener    net.Listener
	certificate tls.Certificate
	fingerprint string
	cancel      context.CancelFunc
	direct      atomic.Bool
	directRx    atomic.Uint64
	directTx    atomic.Uint64
	relayRx     atomic.Uint64
	relayTx     atomic.Uint64
	stopping    atomic.Bool
}

func newNATRuntimeManager(emit func(string, map[string]interface{})) *natRuntimeManager {
	path := "nat-runtime.json"
	if executable, err := os.Executable(); err == nil {
		path = filepath.Join(filepath.Dir(executable), path)
	}
	return &natRuntimeManager{runtimes: make(map[int64]*natRuntime), emit: emit, stateFile: path}
}

func (m *natRuntimeManager) restore() {
	content, err := os.ReadFile(m.stateFile)
	if err != nil {
		return
	}
	var items []natPersistedSource
	if json.Unmarshal(content, &items) != nil {
		return
	}
	for _, item := range items {
		request := natPrepareRequest{RouteID: item.RouteID, Role: "source", ListenAddress: item.ListenAddress, FallbackAddress: item.FallbackAddress}
		runtime := &natRuntime{request: request}
		ctx, cancel := context.WithCancel(context.Background())
		runtime.cancel = cancel
		if err := m.startSourceListener(ctx, runtime); err != nil {
			continue
		}
		m.mu.Lock()
		m.runtimes[item.RouteID] = runtime
		m.mu.Unlock()
	}
}

func (m *natRuntimeManager) prepare(request natPrepareRequest) (natPrepareResponse, error) {
	if request.RouteID <= 0 || request.SessionID == "" || len(request.Token) < 32 {
		return natPrepareResponse{}, errors.New("invalid NAT session")
	}
	if request.Role != "source" && request.Role != "home" {
		return natPrepareResponse{}, errors.New("invalid NAT endpoint role")
	}
	m.stop(request.RouteID, false)

	urls := make([]*stun.URI, 0, len(request.STUNServers))
	for _, raw := range request.STUNServers {
		uri, err := stun.ParseURI(raw)
		if err == nil {
			urls = append(urls, uri)
		}
	}
	if len(urls) == 0 {
		return natPrepareResponse{}, errors.New("no valid STUN server")
	}
	disconnected := 5 * time.Second
	failed := 8 * time.Second
	keepalive := 2 * time.Second
	agent, err := ice.NewAgent(&ice.AgentConfig{
		Urls:                urls,
		NetworkTypes:        []ice.NetworkType{ice.NetworkTypeUDP4, ice.NetworkTypeUDP6},
		CandidateTypes:      []ice.CandidateType{ice.CandidateTypeHost, ice.CandidateTypeServerReflexive},
		MulticastDNSMode:    ice.MulticastDNSModeDisabled,
		DisconnectedTimeout: &disconnected,
		FailedTimeout:       &failed,
		KeepaliveInterval:   &keepalive,
	})
	if err != nil {
		return natPrepareResponse{}, fmt.Errorf("create ICE agent: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	runtime := &natRuntime{request: request, agent: agent, cancel: cancel}
	if request.Role == "home" {
		runtime.certificate, runtime.fingerprint, err = generateNATCertificate()
		if err != nil {
			cancel()
			_ = agent.Close()
			return natPrepareResponse{}, err
		}
	}
	if request.Role == "source" {
		if err := m.startSourceListener(ctx, runtime); err != nil {
			cancel()
			_ = agent.Close()
			return natPrepareResponse{}, err
		}
	}

	var candidates []string
	done := make(chan struct{})
	if err := agent.OnCandidate(func(candidate ice.Candidate) {
		if candidate == nil {
			close(done)
			return
		}
		candidates = append(candidates, candidate.Marshal())
	}); err != nil {
		runtime.close()
		return natPrepareResponse{}, err
	}
	if err := agent.GatherCandidates(); err != nil {
		runtime.close()
		return natPrepareResponse{}, err
	}
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		runtime.close()
		return natPrepareResponse{}, errors.New("STUN candidate gathering timed out")
	}
	if len(candidates) == 0 {
		runtime.close()
		return natPrepareResponse{}, errors.New("STUN returned no usable UDP candidate")
	}
	username, password, err := agent.GetLocalUserCredentials()
	if err != nil {
		runtime.close()
		return natPrepareResponse{}, err
	}

	m.mu.Lock()
	m.runtimes[request.RouteID] = runtime
	m.mu.Unlock()
	if request.Role == "source" {
		m.persistSources()
	}
	return natPrepareResponse{
		UsernameFragment:       username,
		Password:               password,
		Candidates:             candidates,
		NATType:                classifyNAT(candidates),
		CertificateFingerprint: runtime.fingerprint,
	}, nil
}

func (m *natRuntimeManager) connect(request natConnectRequest) error {
	m.mu.Lock()
	runtime := m.runtimes[request.RouteID]
	m.mu.Unlock()
	if runtime == nil || runtime.request.SessionID != request.SessionID || runtime.request.Token != request.Token {
		return errors.New("NAT session is no longer active")
	}
	for _, raw := range request.RemoteCandidates {
		candidate, err := ice.UnmarshalCandidate(raw)
		if err != nil {
			return fmt.Errorf("parse remote ICE candidate: %w", err)
		}
		if err := runtime.agent.AddRemoteCandidate(candidate); err != nil {
			return err
		}
	}
	go m.establish(runtime, request)
	return nil
}

func (m *natRuntimeManager) fallback(request natFallbackRequest) error {
	if request.RouteID <= 0 || request.ListenAddress == "" || request.FallbackAddress == "" {
		return errors.New("invalid NAT fallback configuration")
	}
	m.stop(request.RouteID, false)
	runtime := &natRuntime{request: natPrepareRequest{
		RouteID: request.RouteID, Role: "source", ListenAddress: request.ListenAddress,
		FallbackAddress: request.FallbackAddress,
	}}
	ctx, cancel := context.WithCancel(context.Background())
	runtime.cancel = cancel
	if err := m.startSourceListener(ctx, runtime); err != nil {
		cancel()
		return err
	}
	m.mu.Lock()
	m.runtimes[request.RouteID] = runtime
	m.mu.Unlock()
	m.persistSources()
	return nil
}

func (m *natRuntimeManager) establish(runtime *natRuntime, request natConnectRequest) {
	timeout := time.Duration(request.ConnectTimeoutSeconds) * time.Second
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var conn *ice.Conn
	var err error
	if request.Role == "controlling" {
		conn, err = runtime.agent.Dial(ctx, request.RemoteUsername, request.RemotePassword)
	} else {
		conn, err = runtime.agent.Accept(ctx, request.RemoteUsername, request.RemotePassword)
	}
	if err != nil {
		m.directFailed(runtime, "UDP 打洞失败："+err.Error())
		return
	}
	runtime.mu.Lock()
	runtime.iceConn = conn
	runtime.transport = &quic.Transport{Conn: &connectedPacketConn{Conn: conn}}
	runtime.mu.Unlock()
	if request.Role == "controlling" {
		err = m.runQUICClient(runtime, request.ExpectedFingerprint)
	} else {
		err = m.runQUICServer(runtime)
	}
	if err != nil && !runtime.stopping.Load() {
		m.directFailed(runtime, "直连通道中断："+err.Error())
	}
}

func (m *natRuntimeManager) runQUICClient(runtime *natRuntime, expectedFingerprint string) error {
	verify := strings.ToLower(strings.ReplaceAll(expectedFingerprint, ":", ""))
	if len(verify) != 64 {
		return errors.New("invalid peer certificate fingerprint")
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS13, NextProtos: []string{natALPN}, InsecureSkipVerify: true}
	tlsConfig.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) == 0 {
			return errors.New("peer certificate is missing")
		}
		sum := sha256.Sum256(rawCerts[0])
		if hex.EncodeToString(sum[:]) != verify {
			return errors.New("peer certificate fingerprint mismatch")
		}
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	connection, err := runtime.transport.Dial(ctx, runtime.iceConn.RemoteAddr(), tlsConfig,
		&quic.Config{KeepAlivePeriod: 2 * time.Second, MaxIdleTimeout: 10 * time.Second})
	if err != nil {
		return err
	}
	runtime.mu.Lock()
	runtime.quicConn = connection
	runtime.direct.Store(true)
	runtime.mu.Unlock()
	m.emitStatus(runtime, "direct", "udp_direct", "UDP 直连已建立")
	<-connection.Context().Done()
	return context.Cause(connection.Context())
}

func (m *natRuntimeManager) runQUICServer(runtime *natRuntime) error {
	listener, err := runtime.transport.Listen(&tls.Config{MinVersion: tls.VersionTLS13, NextProtos: []string{natALPN}, Certificates: []tls.Certificate{runtime.certificate}},
		&quic.Config{KeepAlivePeriod: 2 * time.Second, MaxIdleTimeout: 10 * time.Second})
	if err != nil {
		return err
	}
	connection, err := listener.Accept(context.Background())
	if err != nil {
		return err
	}
	runtime.mu.Lock()
	runtime.quicConn = connection
	runtime.direct.Store(true)
	runtime.mu.Unlock()
	for {
		stream, err := connection.AcceptStream(context.Background())
		if err != nil {
			return err
		}
		go m.handleHomeStream(runtime, stream)
	}
}

func (m *natRuntimeManager) startSourceListener(ctx context.Context, runtime *natRuntime) error {
	if runtime.request.ListenAddress == "" || runtime.request.FallbackAddress == "" {
		return errors.New("source listener or fallback address is missing")
	}
	listener, err := net.Listen("tcp", runtime.request.ListenAddress)
	if err != nil {
		return fmt.Errorf("open local SOCKS listener: %w", err)
	}
	runtime.listener = listener
	go func() {
		for {
			connection, err := listener.Accept()
			if err != nil {
				return
			}
			go m.handleSourceConnection(ctx, runtime, connection)
		}
	}()
	go m.reportTraffic(ctx, runtime)
	return nil
}

func (m *natRuntimeManager) handleSourceConnection(ctx context.Context, runtime *natRuntime, local net.Conn) {
	defer local.Close()
	runtime.mu.RLock()
	directConn := runtime.quicConn
	direct := runtime.direct.Load()
	runtime.mu.RUnlock()
	if direct && directConn != nil {
		stream, err := directConn.OpenStreamSync(ctx)
		if err == nil {
			if _, err = io.WriteString(stream, runtime.request.Token+"\n"); err == nil {
				proxyCounted(local, stream, &runtime.directTx, &runtime.directRx)
				return
			}
			_ = stream.Close()
		}
		runtime.direct.Store(false)
		m.emitStatus(runtime, "relay", "relay", "直连不可用，新连接已切换公网中继")
	}
	remote, err := net.DialTimeout("tcp", runtime.request.FallbackAddress, 5*time.Second)
	if err != nil {
		return
	}
	defer remote.Close()
	proxyCounted(local, remote, &runtime.relayTx, &runtime.relayRx)
}

func (m *natRuntimeManager) handleHomeStream(runtime *natRuntime, stream quic.Stream) {
	defer stream.Close()
	_ = stream.SetReadDeadline(time.Now().Add(5 * time.Second))
	reader := bufio.NewReader(stream)
	token, err := reader.ReadString('\n')
	if err != nil || strings.TrimSpace(token) != runtime.request.Token {
		return
	}
	_ = stream.SetReadDeadline(time.Time{})
	backend, err := net.DialTimeout("tcp", runtime.request.BackendAddress, 5*time.Second)
	if err != nil {
		return
	}
	defer backend.Close()
	proxy(&bufferedReadWriteCloser{Reader: reader, ReadWriteCloser: stream}, backend)
}

func (m *natRuntimeManager) directFailed(runtime *natRuntime, detail string) {
	if runtime.stopping.Load() {
		return
	}
	runtime.direct.Store(false)
	if runtime.request.Role == "source" {
		m.emitStatus(runtime, "relay", "relay", detail)
	}
}

func (m *natRuntimeManager) reportTraffic(ctx context.Context, runtime *natRuntime) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	var previous [4]uint64
	report := func() {
		current := [4]uint64{runtime.directRx.Load(), runtime.directTx.Load(), runtime.relayRx.Load(), runtime.relayTx.Load()}
		if current == previous {
			return
		}
		m.emit("NatTraffic", map[string]interface{}{
			"routeId":       runtime.request.RouteID,
			"directRxDelta": current[0] - previous[0], "directTxDelta": current[1] - previous[1],
			"relayRxDelta": current[2] - previous[2], "relayTxDelta": current[3] - previous[3],
		})
		previous = current
	}
	for {
		select {
		case <-ctx.Done():
			report()
			return
		case <-ticker.C:
			report()
		}
	}
}

func (m *natRuntimeManager) emitStatus(runtime *natRuntime, state, path, detail string) {
	if runtime.request.Role != "source" {
		return
	}
	m.emit("NatPathChanged", map[string]interface{}{"routeId": runtime.request.RouteID, "state": state, "accessPath": path, "detail": detail})
}

func (m *natRuntimeManager) stop(routeID int64, persist bool) {
	m.mu.Lock()
	runtime := m.runtimes[routeID]
	delete(m.runtimes, routeID)
	m.mu.Unlock()
	if runtime != nil {
		runtime.close()
	}
	if persist {
		m.persistSources()
	}
}

func (m *natRuntimeManager) stopAll() {
	m.mu.Lock()
	items := m.runtimes
	m.runtimes = make(map[int64]*natRuntime)
	m.mu.Unlock()
	for _, runtime := range items {
		runtime.close()
	}
}

func (m *natRuntimeManager) persistSources() {
	m.mu.Lock()
	items := make([]natPersistedSource, 0)
	for _, runtime := range m.runtimes {
		if runtime.request.Role == "source" {
			items = append(items, natPersistedSource{RouteID: runtime.request.RouteID, ListenAddress: runtime.request.ListenAddress, FallbackAddress: runtime.request.FallbackAddress})
		}
	}
	m.mu.Unlock()
	data, _ := json.Marshal(items)
	_ = os.WriteFile(m.stateFile+".tmp", data, 0600)
	_ = os.Rename(m.stateFile+".tmp", m.stateFile)
}

func (runtime *natRuntime) close() {
	runtime.stopping.Store(true)
	runtime.direct.Store(false)
	if runtime.cancel != nil {
		runtime.cancel()
	}
	if runtime.listener != nil {
		_ = runtime.listener.Close()
	}
	runtime.mu.Lock()
	quicConn := runtime.quicConn
	transport := runtime.transport
	iceConn := runtime.iceConn
	agent := runtime.agent
	runtime.quicConn = nil
	runtime.transport = nil
	runtime.iceConn = nil
	runtime.agent = nil
	runtime.mu.Unlock()
	if quicConn != nil {
		_ = quicConn.CloseWithError(0, "stopped")
	}
	// quic.Transport cannot interrupt a blocking read on pion/ice.Conn itself.
	// Close ICE first so Transport.Close can finish its listener goroutine.
	if iceConn != nil {
		_ = iceConn.Close()
	}
	if transport != nil {
		_ = transport.Close()
	}
	if agent != nil {
		_ = agent.Close()
	}
}

type connectedPacketConn struct{ net.Conn }

func (c *connectedPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, err := c.Read(p)
	return n, c.RemoteAddr(), err
}
func (c *connectedPacketConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }

type bufferedReadWriteCloser struct {
	*bufio.Reader
	io.ReadWriteCloser
}

func (c *bufferedReadWriteCloser) Read(p []byte) (int, error) { return c.Reader.Read(p) }

func proxy(left io.ReadWriteCloser, right io.ReadWriteCloser) {
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(left, right); done <- struct{}{} }()
	go func() { _, _ = io.Copy(right, left); done <- struct{}{} }()
	<-done
}

func proxyCounted(left io.ReadWriteCloser, right io.ReadWriteCloser, leftToRight, rightToLeft *atomic.Uint64) {
	done := make(chan struct{}, 2)
	go func() { n, _ := io.Copy(right, left); leftToRight.Add(uint64(n)); done <- struct{}{} }()
	go func() { n, _ := io.Copy(left, right); rightToLeft.Add(uint64(n)); done <- struct{}{} }()
	<-done
}

func classifyNAT(candidates []string) string {
	ports := make(map[string]struct{})
	hasPublicHost := false
	for _, raw := range candidates {
		candidate, err := ice.UnmarshalCandidate(raw)
		if err != nil {
			continue
		}
		if candidate.Type() == ice.CandidateTypeServerReflexive {
			ports[fmt.Sprintf("%s:%d", candidate.Address(), candidate.Port())] = struct{}{}
		}
		if candidate.Type() == ice.CandidateTypeHost && net.ParseIP(candidate.Address()) != nil && !isPrivateIP(net.ParseIP(candidate.Address())) {
			hasPublicHost = true
		}
	}
	if hasPublicHost {
		return "public"
	}
	if len(ports) > 1 {
		return "symmetric-likely"
	}
	if len(ports) == 1 {
		return "endpoint-independent-likely"
	}
	return "unknown"
}

func isPrivateIP(ip net.IP) bool { return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() }

func generateNATCertificate() (tls.Certificate, string, error) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	now := time.Now()
	template := x509.Certificate{SerialNumber: big.NewInt(now.UnixNano()), Subject: pkix.Name{CommonName: "CloudNest NAT"}, NotBefore: now.Add(-time.Minute), NotAfter: now.Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	certificate := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	sum := sha256.Sum256(der)
	return certificate, hex.EncodeToString(sum[:]), nil
}
