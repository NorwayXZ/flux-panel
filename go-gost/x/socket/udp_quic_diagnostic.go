package socket

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/quic-go/quic-go"
)

const (
	udpDiagnosticHeaderSize = 40
	udpDiagnosticMinSize    = 64
	udpDiagnosticMaxSize    = 1400
)

var udpDiagnosticMagic = [4]byte{'U', 'Q', 'D', '1'}

type udpQuicPrepareRequest struct {
	SessionID  string `json:"sessionId"`
	ListenPort int    `json:"listenPort"`
	TTLSeconds int    `json:"ttlSeconds"`
	PacketSize int    `json:"packetSize"`
}

type udpQuicPrepareResponse struct {
	SessionID string `json:"sessionId"`
	Port      int    `json:"port"`
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expiresAt"`
}

type udpQuicRunRequest struct {
	Mode               string `json:"mode"`
	TargetHost         string `json:"targetHost"`
	Port               int    `json:"port"`
	Token              string `json:"token,omitempty"`
	ServerName         string `json:"serverName,omitempty"`
	IPFamily           string `json:"ipFamily"`
	Count              int    `json:"count"`
	TimeoutMs          int    `json:"timeoutMs"`
	PacketSize         int    `json:"packetSize"`
	IdleTimeoutSeconds int    `json:"idleTimeoutSeconds"`
	ALPN               string `json:"alpn,omitempty"`
	VerifyCertificate  bool   `json:"verifyCertificate"`
}

type udpQuicSample struct {
	Index   int     `json:"index"`
	Success bool    `json:"success"`
	RTTMs   float64 `json:"rttMs,omitempty"`
	Error   string  `json:"error,omitempty"`
}

type udpQuicRunResponse struct {
	Mode               string          `json:"mode"`
	TargetHost         string          `json:"targetHost"`
	ResolvedAddress    string          `json:"resolvedAddress,omitempty"`
	IPFamily           string          `json:"ipFamily"`
	Port               int             `json:"port"`
	PacketSize         int             `json:"packetSize,omitempty"`
	SuccessCount       int             `json:"successCount"`
	FailureRate        float64         `json:"failureRate"`
	PacketLossPercent  float64         `json:"packetLossPercent,omitempty"`
	RTTMinMs           float64         `json:"rttMinMs,omitempty"`
	RTTAvgMs           float64         `json:"rttAvgMs,omitempty"`
	RTTMaxMs           float64         `json:"rttMaxMs,omitempty"`
	JitterMs           float64         `json:"jitterMs,omitempty"`
	NATIdleSeconds     int             `json:"natIdleSeconds,omitempty"`
	NATIdleAlive       *bool           `json:"natIdleAlive,omitempty"`
	QUICHandshakeAvgMs float64         `json:"quicHandshakeAvgMs,omitempty"`
	ALPN               string          `json:"alpn,omitempty"`
	Samples            []udpQuicSample `json:"samples"`
	Error              string          `json:"error,omitempty"`
}

type udpQuicServer struct {
	id         string
	token      string
	packetSize int
	connection *net.UDPConn
	expiresAt  time.Time
	closed     chan struct{}
	closeOnce  sync.Once
}

type udpQuicDiagnosticManager struct {
	mu       sync.Mutex
	sessions map[string]*udpQuicServer
}

func newUDPQuicDiagnosticManager() *udpQuicDiagnosticManager {
	return &udpQuicDiagnosticManager{sessions: make(map[string]*udpQuicServer)}
}

func (m *udpQuicDiagnosticManager) prepare(request udpQuicPrepareRequest) (udpQuicPrepareResponse, error) {
	request.SessionID = strings.TrimSpace(request.SessionID)
	if !runtimeNamePattern.MatchString(request.SessionID) {
		return udpQuicPrepareResponse{}, errors.New("invalid UDP diagnostic session ID")
	}
	if request.ListenPort < 1 || request.ListenPort > 65535 {
		return udpQuicPrepareResponse{}, errors.New("invalid UDP diagnostic port")
	}
	if request.TTLSeconds < 10 || request.TTLSeconds > 180 {
		request.TTLSeconds = 90
	}
	request.PacketSize = normalizeUDPPacketSize(request.PacketSize)
	token, err := randomURLToken(32)
	if err != nil {
		return udpQuicPrepareResponse{}, err
	}
	connection, err := net.ListenUDP("udp", &net.UDPAddr{Port: request.ListenPort})
	if err != nil {
		return udpQuicPrepareResponse{}, fmt.Errorf("open UDP diagnostic port %d: %w", request.ListenPort, err)
	}
	server := &udpQuicServer{
		id: request.SessionID, token: token, packetSize: request.PacketSize, connection: connection,
		expiresAt: time.Now().Add(time.Duration(request.TTLSeconds) * time.Second), closed: make(chan struct{}),
	}
	m.mu.Lock()
	if existing := m.sessions[request.SessionID]; existing != nil {
		m.mu.Unlock()
		_ = connection.Close()
		return udpQuicPrepareResponse{}, errors.New("UDP diagnostic session already exists")
	}
	m.sessions[request.SessionID] = server
	m.mu.Unlock()
	go m.serve(server)
	go func() {
		timer := time.NewTimer(time.Until(server.expiresAt))
		defer timer.Stop()
		select {
		case <-timer.C:
			m.stop(server.id)
		case <-server.closed:
		}
	}()
	return udpQuicPrepareResponse{SessionID: server.id, Port: request.ListenPort, Token: server.token, ExpiresAt: server.expiresAt.UnixMilli()}, nil
}

func (m *udpQuicDiagnosticManager) serve(server *udpQuicServer) {
	buffer := make([]byte, udpDiagnosticMaxSize+64)
	digest := tokenDigest(server.token)
	for {
		count, remote, err := server.connection.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		sequence, sentAt, ok := decodeUDPDiagnosticPacket(buffer[:count], digest)
		if !ok {
			continue
		}
		replySize := normalizeUDPPacketSize(count)
		_, _ = server.connection.WriteToUDP(encodeUDPDiagnosticPacket(sequence, sentAt, digest, replySize), remote)
	}
}

func (m *udpQuicDiagnosticManager) stop(sessionID string) {
	m.mu.Lock()
	server := m.sessions[sessionID]
	if server != nil {
		delete(m.sessions, sessionID)
	}
	m.mu.Unlock()
	if server == nil {
		return
	}
	server.closeOnce.Do(func() {
		close(server.closed)
		_ = server.connection.Close()
	})
}

func (m *udpQuicDiagnosticManager) stopAll() {
	m.mu.Lock()
	ids := make([]string, 0, len(m.sessions))
	for id := range m.sessions {
		ids = append(ids, id)
	}
	m.mu.Unlock()
	for _, id := range ids {
		m.stop(id)
	}
}

func (m *udpQuicDiagnosticManager) run(request udpQuicRunRequest) (udpQuicRunResponse, error) {
	if err := normalizeUDPQuicRunRequest(&request); err != nil {
		return udpQuicRunResponse{}, err
	}
	if request.Mode == "udp_echo" {
		return runUDPEchoDiagnostic(request)
	}
	return runQUICDiagnostic(request)
}

func normalizeUDPQuicRunRequest(request *udpQuicRunRequest) error {
	request.Mode = strings.ToLower(strings.TrimSpace(request.Mode))
	request.TargetHost = strings.Trim(strings.TrimSpace(request.TargetHost), "[]")
	request.ServerName = strings.TrimSpace(request.ServerName)
	request.IPFamily = strings.ToLower(strings.TrimSpace(request.IPFamily))
	request.ALPN = strings.TrimSpace(request.ALPN)
	if request.Mode == "" {
		request.Mode = "udp_echo"
	}
	if request.Mode != "udp_echo" && request.Mode != "quic" {
		return errors.New("mode must be udp_echo or quic")
	}
	if !validDiagnosticTarget(request.TargetHost) {
		return errors.New("target must be a valid IP address or hostname")
	}
	if request.ServerName != "" && !validDiagnosticTarget(request.ServerName) {
		return errors.New("server name must be a valid IP address or hostname")
	}
	if request.IPFamily == "" {
		request.IPFamily = "auto"
	}
	if request.IPFamily != "auto" && request.IPFamily != "ipv4" && request.IPFamily != "ipv6" {
		return errors.New("IP family must be auto, ipv4, or ipv6")
	}
	if request.Port < 1 || request.Port > 65535 {
		return errors.New("port must be between 1 and 65535")
	}
	if request.Count < 1 || request.Count > 20 {
		request.Count = 5
	}
	if request.TimeoutMs < 300 || request.TimeoutMs > 20000 {
		request.TimeoutMs = 3000
	}
	request.PacketSize = normalizeUDPPacketSize(request.PacketSize)
	if request.IdleTimeoutSeconds < 0 || request.IdleTimeoutSeconds > 60 {
		request.IdleTimeoutSeconds = 0
	}
	if request.Mode == "udp_echo" && len(request.Token) < 32 {
		return errors.New("UDP echo diagnostics require an authenticated Agent target")
	}
	return nil
}

func runUDPEchoDiagnostic(request udpQuicRunRequest) (udpQuicRunResponse, error) {
	started := time.Now()
	resolved, family, resolveErr := resolveQualityTarget(request.TargetHost, request.IPFamily, time.Duration(request.TimeoutMs)*time.Millisecond)
	response := udpQuicRunResponse{
		Mode: request.Mode, TargetHost: request.TargetHost, ResolvedAddress: resolved, IPFamily: family,
		Port: request.Port, PacketSize: request.PacketSize, Samples: make([]udpQuicSample, 0, request.Count+1),
	}
	if resolveErr != nil {
		response.Error = conciseProbeError(resolveErr)
		response.FailureRate = 100
		response.PacketLossPercent = 100
		return response, nil
	}
	address := net.JoinHostPort(resolved, strconv.Itoa(request.Port))
	network := "udp4"
	if family == "ipv6" {
		network = "udp6"
	}
	remote, err := net.ResolveUDPAddr(network, address)
	if err != nil {
		response.Error = conciseProbeError(err)
		response.FailureRate = 100
		response.PacketLossPercent = 100
		return response, nil
	}
	connection, err := net.DialUDP(network, nil, remote)
	if err != nil {
		response.Error = conciseProbeError(err)
		response.FailureRate = 100
		response.PacketLossPercent = 100
		return response, nil
	}
	defer connection.Close()
	digest := tokenDigest(request.Token)
	latencies := make([]float64, 0, request.Count)
	for index := 1; index <= request.Count; index++ {
		sample := runUDPEchoSample(connection, digest, uint64(index), request.PacketSize, time.Duration(request.TimeoutMs)*time.Millisecond)
		sample.Index = index
		response.Samples = append(response.Samples, sample)
		if sample.Success {
			response.SuccessCount++
			latencies = append(latencies, sample.RTTMs)
		}
		if index < request.Count {
			time.Sleep(80 * time.Millisecond)
		}
	}
	if request.IdleTimeoutSeconds > 0 && response.SuccessCount > 0 {
		time.Sleep(time.Duration(request.IdleTimeoutSeconds) * time.Second)
		sample := runUDPEchoSample(connection, digest, uint64(request.Count+1), request.PacketSize, time.Duration(request.TimeoutMs)*time.Millisecond)
		alive := sample.Success
		response.NATIdleSeconds = request.IdleTimeoutSeconds
		response.NATIdleAlive = &alive
	}
	fillUDPQuicStats(&response, latencies, request.Count)
	if response.SuccessCount == 0 {
		response.Error = firstUDPSampleError(response.Samples)
	}
	_ = started
	return response, nil
}

func runUDPEchoSample(connection *net.UDPConn, digest [16]byte, sequence uint64, packetSize int, timeout time.Duration) udpQuicSample {
	sentAt := time.Now().UnixNano()
	packet := encodeUDPDiagnosticPacket(sequence, sentAt, digest, packetSize)
	started := time.Now()
	if _, err := connection.Write(packet); err != nil {
		return udpQuicSample{Error: conciseProbeError(err)}
	}
	buffer := make([]byte, udpDiagnosticMaxSize+64)
	_ = connection.SetReadDeadline(time.Now().Add(timeout))
	for {
		count, err := connection.Read(buffer)
		if err != nil {
			return udpQuicSample{Error: conciseProbeError(err)}
		}
		replySequence, replySentAt, ok := decodeUDPDiagnosticPacket(buffer[:count], digest)
		if ok && replySequence == sequence && replySentAt == sentAt {
			return udpQuicSample{Success: true, RTTMs: milliseconds(time.Since(started))}
		}
	}
}

func runQUICDiagnostic(request udpQuicRunRequest) (udpQuicRunResponse, error) {
	resolved, family, resolveErr := resolveQualityTarget(request.TargetHost, request.IPFamily, time.Duration(request.TimeoutMs)*time.Millisecond)
	response := udpQuicRunResponse{
		Mode: request.Mode, TargetHost: request.TargetHost, ResolvedAddress: resolved, IPFamily: family,
		Port: request.Port, ALPN: normalizeALPNText(request.ALPN), Samples: make([]udpQuicSample, 0, request.Count),
	}
	if resolveErr != nil {
		response.Error = conciseProbeError(resolveErr)
		response.FailureRate = 100
		return response, nil
	}
	latencies := make([]float64, 0, request.Count)
	for index := 1; index <= request.Count; index++ {
		sample := runQUICHandshakeSample(request, resolved, index)
		response.Samples = append(response.Samples, sample)
		if sample.Success {
			response.SuccessCount++
			latencies = append(latencies, sample.RTTMs)
		}
		if index < request.Count {
			time.Sleep(120 * time.Millisecond)
		}
	}
	fillUDPQuicStats(&response, latencies, request.Count)
	response.QUICHandshakeAvgMs = response.RTTAvgMs
	if response.SuccessCount == 0 {
		response.Error = firstUDPSampleError(response.Samples)
	}
	return response, nil
}

func runQUICHandshakeSample(request udpQuicRunRequest, resolved string, index int) udpQuicSample {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(request.TimeoutMs)*time.Millisecond)
	defer cancel()
	serverName := request.ServerName
	if serverName == "" {
		serverName = request.TargetHost
	}
	tlsConfig := &tls.Config{
		ServerName:         serverName,
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: !request.VerifyCertificate,
		NextProtos:         normalizeALPN(request.ALPN),
	}
	started := time.Now()
	connection, err := quic.DialAddr(ctx, net.JoinHostPort(resolved, strconv.Itoa(request.Port)), tlsConfig, &quic.Config{
		HandshakeIdleTimeout: time.Duration(request.TimeoutMs) * time.Millisecond,
		MaxIdleTimeout:       time.Duration(request.TimeoutMs) * time.Millisecond,
	})
	if err != nil {
		return udpQuicSample{Index: index, Error: conciseProbeError(err)}
	}
	_ = connection.CloseWithError(0, "diagnostic complete")
	return udpQuicSample{Index: index, Success: true, RTTMs: milliseconds(time.Since(started))}
}

func encodeUDPDiagnosticPacket(sequence uint64, sentAt int64, digest [16]byte, packetSize int) []byte {
	size := normalizeUDPPacketSize(packetSize)
	packet := make([]byte, size)
	copy(packet[:4], udpDiagnosticMagic[:])
	binary.BigEndian.PutUint64(packet[4:12], sequence)
	binary.BigEndian.PutUint64(packet[12:20], uint64(sentAt))
	copy(packet[20:36], digest[:])
	return packet
}

func decodeUDPDiagnosticPacket(data []byte, expectedDigest [16]byte) (uint64, int64, bool) {
	if len(data) < udpDiagnosticHeaderSize || subtle.ConstantTimeCompare(data[:4], udpDiagnosticMagic[:]) != 1 || subtle.ConstantTimeCompare(data[20:36], expectedDigest[:]) != 1 {
		return 0, 0, false
	}
	return binary.BigEndian.Uint64(data[4:12]), int64(binary.BigEndian.Uint64(data[12:20])), true
}

func normalizeUDPPacketSize(size int) int {
	if size <= 0 {
		return 1200
	}
	if size < udpDiagnosticMinSize {
		return udpDiagnosticMinSize
	}
	if size > udpDiagnosticMaxSize {
		return udpDiagnosticMaxSize
	}
	return size
}

func fillUDPQuicStats(response *udpQuicRunResponse, latencies []float64, count int) {
	if count <= 0 {
		count = len(response.Samples)
	}
	response.FailureRate = float64(count-response.SuccessCount) * 100 / float64(count)
	response.PacketLossPercent = response.FailureRate
	if len(latencies) == 0 {
		return
	}
	minimum := latencies[0]
	maximum := latencies[0]
	total := 0.0
	for _, value := range latencies {
		if value < minimum {
			minimum = value
		}
		if value > maximum {
			maximum = value
		}
		total += value
	}
	response.RTTMinMs = minimum
	response.RTTAvgMs = total / float64(len(latencies))
	response.RTTMaxMs = maximum
	response.JitterMs = successiveJitter(latencies)
}

func normalizeALPN(raw string) []string {
	parts := strings.Split(raw, ",")
	values := make([]string, 0, len(parts))
	for _, part := range parts {
		value := strings.TrimSpace(part)
		if value != "" {
			values = append(values, value)
		}
	}
	if len(values) == 0 {
		return []string{"h3"}
	}
	return values
}

func normalizeALPNText(raw string) string {
	return strings.Join(normalizeALPN(raw), ",")
}

func firstUDPSampleError(samples []udpQuicSample) string {
	for _, sample := range samples {
		if sample.Error != "" {
			return sample.Error
		}
	}
	return "all diagnostic samples failed"
}

func randomURLToken(size int) (string, error) {
	raw := make([]byte, size)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func (w *WebSocketReporter) handleUDPQuicPrepare(data interface{}) (udpQuicPrepareResponse, error) {
	var request udpQuicPrepareRequest
	if err := decodeCommandData(data, &request); err != nil {
		return udpQuicPrepareResponse{}, err
	}
	if w.udpQuicManager == nil {
		return udpQuicPrepareResponse{}, errors.New("UDP / QUIC diagnostic manager is unavailable")
	}
	return w.udpQuicManager.prepare(request)
}

func (w *WebSocketReporter) handleUDPQuicRun(data interface{}) (udpQuicRunResponse, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return udpQuicRunResponse{}, err
	}
	var request udpQuicRunRequest
	if err := json.Unmarshal(raw, &request); err != nil {
		return udpQuicRunResponse{}, err
	}
	if w.udpQuicManager == nil {
		return udpQuicRunResponse{}, errors.New("UDP / QUIC diagnostic manager is unavailable")
	}
	return w.udpQuicManager.run(request)
}
