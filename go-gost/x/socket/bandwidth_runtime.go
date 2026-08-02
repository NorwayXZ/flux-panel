package socket

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/mem"
)

const (
	bandwidthMaximumDuration = 30 * time.Second
	bandwidthMaximumBytes    = int64(2 * 1024 * 1024 * 1024)
	bandwidthMaximumStreams  = 8
)

type bandwidthPrepareRequest struct {
	SessionID      string `json:"sessionId"`
	Protocol       string `json:"protocol"`
	ListenPort     int    `json:"listenPort"`
	TTLSeconds     int    `json:"ttlSeconds"`
	MaximumBytes   int64  `json:"maximumBytes"`
	MaximumStreams int    `json:"maximumStreams"`
}

type bandwidthPrepareResponse struct {
	SessionID string `json:"sessionId"`
	Protocol  string `json:"protocol"`
	Port      int    `json:"port"`
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expiresAt"`
}

type bandwidthRunRequest struct {
	TargetHost      string `json:"targetHost"`
	Protocol        string `json:"protocol"`
	Port            int    `json:"port"`
	Token           string `json:"token"`
	Direction       string `json:"direction"`
	Streams         int    `json:"streams"`
	DurationSeconds int    `json:"durationSeconds"`
	MaximumBytes    int64  `json:"maximumBytes"`
}

type bandwidthRunResponse struct {
	Protocol      string  `json:"protocol"`
	Direction     string  `json:"direction"`
	Streams       int     `json:"streams"`
	DurationMs    int64   `json:"durationMs"`
	UploadBytes   int64   `json:"uploadBytes"`
	DownloadBytes int64   `json:"downloadBytes"`
	UploadMbps    float64 `json:"uploadMbps"`
	DownloadMbps  float64 `json:"downloadMbps"`
	TotalMbps     float64 `json:"totalMbps"`
	CPUPercent    float64 `json:"cpuPercent"`
	MemoryUsed    uint64  `json:"memoryUsed"`
	MemoryPercent float64 `json:"memoryPercent"`
	Successful    int     `json:"successfulStreams"`
	Failed        int     `json:"failedStreams"`
	RTTMs         float64 `json:"rttMs"`
	Retransmits   uint64  `json:"retransmits"`
	PacketsSent   uint64  `json:"packetsSent"`
	PacketsRecv   uint64  `json:"packetsReceived"`
	PacketsLost   uint64  `json:"packetsLost"`
	OutOfOrder    uint64  `json:"outOfOrderPackets"`
	JitterMs      float64 `json:"jitterMs"`
}

type bandwidthNetworkMetrics struct {
	RTTMs       float64 `json:"rttMs"`
	Retransmits uint64  `json:"retransmits"`
	PacketsSent uint64  `json:"packetsSent"`
	PacketsRecv uint64  `json:"packetsReceived"`
	PacketsLost uint64  `json:"packetsLost"`
	OutOfOrder  uint64  `json:"outOfOrderPackets"`
	JitterMs    float64 `json:"jitterMs"`
}

type bandwidthStopResponse struct {
	Protocol string `json:"protocol"`
	bandwidthNetworkMetrics
}

type bandwidthStreamHeader struct {
	Token        string `json:"token"`
	Mode         string `json:"mode"`
	DurationMs   int64  `json:"durationMs"`
	MaximumBytes int64  `json:"maximumBytes"`
}

type bandwidthServer struct {
	id             string
	protocol       string
	token          string
	listener       net.Listener
	udpConnection  *net.UDPConn
	expiresAt      time.Time
	maximumBytes   int64
	maximumStreams int
	active         atomic.Int32
	metricsMu      sync.Mutex
	metrics        bandwidthNetworkMetrics
	metricSamples  uint64
	udpStreams     map[string]*udpBandwidthStream
	closed         chan struct{}
	closeOnce      sync.Once
}

type bandwidthTestManager struct {
	mu       sync.Mutex
	sessions map[string]*bandwidthServer
}

func newBandwidthTestManager() *bandwidthTestManager {
	return &bandwidthTestManager{sessions: make(map[string]*bandwidthServer)}
}

func (m *bandwidthTestManager) prepare(request bandwidthPrepareRequest) (bandwidthPrepareResponse, error) {
	request.SessionID = strings.TrimSpace(request.SessionID)
	if !runtimeNamePattern.MatchString(request.SessionID) {
		return bandwidthPrepareResponse{}, errors.New("invalid bandwidth session ID")
	}
	if request.ListenPort < 1 || request.ListenPort > 65535 {
		return bandwidthPrepareResponse{}, errors.New("invalid bandwidth test port")
	}
	request.Protocol = strings.ToLower(strings.TrimSpace(request.Protocol))
	if request.Protocol == "" {
		request.Protocol = "tcp"
	}
	if request.Protocol != "tcp" && request.Protocol != "udp" {
		return bandwidthPrepareResponse{}, errors.New("invalid bandwidth test protocol")
	}
	if request.TTLSeconds < 10 || request.TTLSeconds > 120 {
		request.TTLSeconds = 60
	}
	if request.MaximumBytes < 1 || request.MaximumBytes > bandwidthMaximumBytes {
		request.MaximumBytes = bandwidthMaximumBytes
	}
	if request.MaximumStreams < 1 || request.MaximumStreams > bandwidthMaximumStreams {
		request.MaximumStreams = bandwidthMaximumStreams
	}
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		return bandwidthPrepareResponse{}, err
	}
	server := &bandwidthServer{
		id: request.SessionID, protocol: request.Protocol, token: base64.RawURLEncoding.EncodeToString(tokenBytes),
		expiresAt: time.Now().Add(time.Duration(request.TTLSeconds) * time.Second), maximumBytes: request.MaximumBytes,
		maximumStreams: request.MaximumStreams, closed: make(chan struct{}), udpStreams: make(map[string]*udpBandwidthStream),
	}
	var err error
	if request.Protocol == "udp" {
		server.udpConnection, err = net.ListenUDP("udp", &net.UDPAddr{Port: request.ListenPort})
		if err == nil {
			_ = server.udpConnection.SetReadBuffer(4 * 1024 * 1024)
			_ = server.udpConnection.SetWriteBuffer(4 * 1024 * 1024)
		}
	} else {
		server.listener, err = net.Listen("tcp", fmt.Sprintf(":%d", request.ListenPort))
	}
	if err != nil {
		return bandwidthPrepareResponse{}, fmt.Errorf("open %s bandwidth test port %d: %w", request.Protocol, request.ListenPort, err)
	}
	m.mu.Lock()
	if existing := m.sessions[request.SessionID]; existing != nil {
		m.mu.Unlock()
		server.closeListener()
		return bandwidthPrepareResponse{}, errors.New("bandwidth session already exists")
	}
	m.sessions[request.SessionID] = server
	m.mu.Unlock()
	if request.Protocol == "udp" {
		go serveUDPBandwidth(server)
	} else {
		go m.serve(server)
	}
	go func() {
		timer := time.NewTimer(time.Until(server.expiresAt))
		defer timer.Stop()
		select {
		case <-timer.C:
			m.stop(server.id)
		case <-server.closed:
		}
	}()
	return bandwidthPrepareResponse{SessionID: server.id, Protocol: server.protocol, Port: request.ListenPort, Token: server.token, ExpiresAt: server.expiresAt.UnixMilli()}, nil
}

func (m *bandwidthTestManager) serve(server *bandwidthServer) {
	for {
		connection, err := server.listener.Accept()
		if err != nil {
			return
		}
		if server.active.Add(1) > int32(server.maximumStreams) {
			server.active.Add(-1)
			connection.Close()
			continue
		}
		go func() {
			defer server.active.Add(-1)
			defer connection.Close()
			metrics, _ := serveBandwidthStream(connection, server)
			server.addMetrics(metrics)
		}()
	}
}

func serveBandwidthStream(connection net.Conn, server *bandwidthServer) (bandwidthNetworkMetrics, error) {
	startedMetrics := readTCPMetrics(connection)
	_ = connection.SetDeadline(time.Now().Add(bandwidthMaximumDuration + 5*time.Second))
	reader := bufio.NewReaderSize(connection, 4096)
	headerLine, err := reader.ReadBytes('\n')
	if err != nil || len(headerLine) > 2048 {
		return bandwidthNetworkMetrics{}, errors.New("invalid bandwidth stream header")
	}
	var header bandwidthStreamHeader
	if json.Unmarshal(headerLine, &header) != nil || header.Token != server.token {
		return bandwidthNetworkMetrics{}, errors.New("invalid bandwidth stream token")
	}
	duration := time.Duration(header.DurationMs) * time.Millisecond
	if duration < time.Second || duration > bandwidthMaximumDuration {
		return bandwidthNetworkMetrics{}, errors.New("invalid bandwidth stream duration")
	}
	limit := header.MaximumBytes
	if limit < 1 || limit > server.maximumBytes {
		limit = server.maximumBytes
	}
	deadline := time.Now().Add(duration)
	if _, err := connection.Write([]byte{1}); err != nil {
		return bandwidthNetworkMetrics{}, err
	}
	buffer := make([]byte, 64*1024)
	var transferError error
	switch header.Mode {
	case "upload":
		transferError = transferUntil(reader, io.Discard, buffer, limit, deadline)
	case "download":
		transferError = transferUntil(nil, connection, buffer, limit, deadline)
	default:
		return bandwidthNetworkMetrics{}, errors.New("invalid bandwidth stream mode")
	}
	return tcpMetricsDelta(startedMetrics, readTCPMetrics(connection)), transferError
}

func transferUntil(reader io.Reader, writer io.Writer, buffer []byte, maximum int64, deadline time.Time) error {
	var transferred int64
	for transferred < maximum && time.Now().Before(deadline) {
		chunk := int64(len(buffer))
		if maximum-transferred < chunk {
			chunk = maximum - transferred
		}
		var count int
		var err error
		if reader != nil {
			count, err = reader.Read(buffer[:chunk])
			if count > 0 && writer != nil {
				_, _ = writer.Write(buffer[:count])
			}
		} else {
			count, err = writer.Write(buffer[:chunk])
		}
		transferred += int64(count)
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
	}
	return nil
}

func (m *bandwidthTestManager) run(request bandwidthRunRequest) (bandwidthRunResponse, error) {
	request.TargetHost = strings.Trim(strings.TrimSpace(request.TargetHost), "[]")
	request.Protocol = strings.ToLower(strings.TrimSpace(request.Protocol))
	if request.Protocol == "" {
		request.Protocol = "tcp"
	}
	request.Direction = strings.ToLower(strings.TrimSpace(request.Direction))
	if !validDiagnosticTarget(request.TargetHost) {
		return bandwidthRunResponse{}, errors.New("invalid bandwidth target host")
	}
	if request.Port < 1 || request.Port > 65535 || len(request.Token) < 32 {
		return bandwidthRunResponse{}, errors.New("invalid bandwidth target settings")
	}
	if request.Protocol != "tcp" && request.Protocol != "udp" {
		return bandwidthRunResponse{}, errors.New("invalid bandwidth protocol")
	}
	if request.Direction != "upload" && request.Direction != "download" && request.Direction != "bidirectional" {
		return bandwidthRunResponse{}, errors.New("invalid bandwidth direction")
	}
	if request.Streams < 1 || request.Streams > bandwidthMaximumStreams {
		return bandwidthRunResponse{}, errors.New("bandwidth streams must be between 1 and 8")
	}
	if request.Direction == "bidirectional" && request.Streams < 2 {
		return bandwidthRunResponse{}, errors.New("bidirectional bandwidth tests need at least 2 streams")
	}
	if request.DurationSeconds < 1 || request.DurationSeconds > int(bandwidthMaximumDuration/time.Second) {
		return bandwidthRunResponse{}, errors.New("bandwidth duration must be between 1 and 30 seconds")
	}
	if request.MaximumBytes < 1 || request.MaximumBytes > bandwidthMaximumBytes {
		request.MaximumBytes = bandwidthMaximumBytes
	}
	if request.Protocol == "udp" {
		return runUDPBandwidth(request)
	}
	beforeCPU, _ := cpu.Percent(0, false)
	started := time.Now()
	type outcome struct {
		mode    string
		bytes   int64
		metrics bandwidthNetworkMetrics
		err     error
	}
	outcomes := make(chan outcome, request.Streams)
	for index := 0; index < request.Streams; index++ {
		mode := request.Direction
		if mode == "bidirectional" {
			if index%2 == 0 {
				mode = "upload"
			} else {
				mode = "download"
			}
		}
		go func(streamMode string) {
			streamResult, err := runBandwidthStream(request, streamMode)
			outcomes <- outcome{mode: streamMode, bytes: streamResult.bytes, metrics: streamResult.metrics, err: err}
		}(mode)
	}
	result := bandwidthRunResponse{Protocol: request.Protocol, Direction: request.Direction, Streams: request.Streams}
	for index := 0; index < request.Streams; index++ {
		outcome := <-outcomes
		if outcome.err != nil {
			result.Failed++
			continue
		}
		result.Successful++
		result.RTTMs += outcome.metrics.RTTMs
		result.Retransmits += outcome.metrics.Retransmits
		if outcome.mode == "upload" {
			result.UploadBytes += outcome.bytes
		} else {
			result.DownloadBytes += outcome.bytes
		}
	}
	if result.Successful > 0 {
		result.RTTMs /= float64(result.Successful)
	}
	result.DurationMs = maxInt64(time.Since(started).Milliseconds())
	seconds := float64(result.DurationMs) / 1000
	result.UploadMbps = float64(result.UploadBytes) * 8 / seconds / 1_000_000
	result.DownloadMbps = float64(result.DownloadBytes) * 8 / seconds / 1_000_000
	result.TotalMbps = result.UploadMbps + result.DownloadMbps
	afterCPU, _ := cpu.Percent(0, false)
	if len(afterCPU) > 0 {
		result.CPUPercent = afterCPU[0]
	} else if len(beforeCPU) > 0 {
		result.CPUPercent = beforeCPU[0]
	}
	if memory, err := mem.VirtualMemory(); err == nil {
		result.MemoryUsed = memory.Used
		result.MemoryPercent = memory.UsedPercent
	}
	if result.Successful == 0 {
		return result, errors.New("all bandwidth test streams failed; check the target port and firewall")
	}
	runtime.GC()
	return result, nil
}

type bandwidthTCPStreamResult struct {
	bytes   int64
	metrics bandwidthNetworkMetrics
}

func runBandwidthStream(request bandwidthRunRequest, mode string) (bandwidthTCPStreamResult, error) {
	connection, err := net.DialTimeout("tcp", net.JoinHostPort(request.TargetHost, fmt.Sprint(request.Port)), 8*time.Second)
	if err != nil {
		return bandwidthTCPStreamResult{}, err
	}
	defer connection.Close()
	startedMetrics := readTCPMetrics(connection)
	_ = connection.SetDeadline(time.Now().Add(time.Duration(request.DurationSeconds+10) * time.Second))
	header, _ := json.Marshal(bandwidthStreamHeader{Token: request.Token, Mode: mode, DurationMs: int64(request.DurationSeconds) * 1000, MaximumBytes: request.MaximumBytes})
	if _, err := connection.Write(append(header, '\n')); err != nil {
		return bandwidthTCPStreamResult{}, err
	}
	ack := []byte{0}
	if _, err := io.ReadFull(connection, ack); err != nil || ack[0] != 1 {
		if err == nil {
			err = errors.New("bandwidth server rejected the stream")
		}
		return bandwidthTCPStreamResult{}, err
	}
	deadline := time.Now().Add(time.Duration(request.DurationSeconds) * time.Second)
	buffer := make([]byte, 64*1024)
	var total int64
	for total < request.MaximumBytes && time.Now().Before(deadline) {
		chunk := int64(len(buffer))
		if request.MaximumBytes-total < chunk {
			chunk = request.MaximumBytes - total
		}
		var count int
		if mode == "upload" {
			count, err = connection.Write(buffer[:chunk])
		} else {
			count, err = connection.Read(buffer[:chunk])
		}
		total += int64(count)
		if err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return bandwidthTCPStreamResult{bytes: total, metrics: tcpMetricsDelta(startedMetrics, readTCPMetrics(connection))}, err
		}
	}
	return bandwidthTCPStreamResult{bytes: total, metrics: tcpMetricsDelta(startedMetrics, readTCPMetrics(connection))}, nil
}

func (m *bandwidthTestManager) stop(id string) bandwidthStopResponse {
	m.mu.Lock()
	server := m.sessions[strings.TrimSpace(id)]
	delete(m.sessions, strings.TrimSpace(id))
	m.mu.Unlock()
	if server != nil {
		server.closeOnce.Do(func() { close(server.closed); server.closeListener() })
		deadline := time.Now().Add(750 * time.Millisecond)
		for server.active.Load() > 0 && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
		return bandwidthStopResponse{Protocol: server.protocol, bandwidthNetworkMetrics: server.snapshotMetrics()}
	}
	return bandwidthStopResponse{}
}

func (m *bandwidthTestManager) stopAll() {
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

func (server *bandwidthServer) closeListener() {
	if server.listener != nil {
		_ = server.listener.Close()
	}
	if server.udpConnection != nil {
		_ = server.udpConnection.Close()
	}
}

func (server *bandwidthServer) addMetrics(metrics bandwidthNetworkMetrics) {
	server.metricsMu.Lock()
	defer server.metricsMu.Unlock()
	server.metrics.RTTMs += metrics.RTTMs
	if metrics.RTTMs > 0 {
		server.metricSamples++
	}
	server.metrics.Retransmits += metrics.Retransmits
	server.metrics.PacketsSent += metrics.PacketsSent
	server.metrics.PacketsRecv += metrics.PacketsRecv
	server.metrics.PacketsLost += metrics.PacketsLost
	server.metrics.OutOfOrder += metrics.OutOfOrder
	if metrics.JitterMs > server.metrics.JitterMs {
		server.metrics.JitterMs = metrics.JitterMs
	}
}

func (server *bandwidthServer) snapshotMetrics() bandwidthNetworkMetrics {
	if server.protocol == "udp" {
		server.collectUDPMetrics()
	}
	server.metricsMu.Lock()
	defer server.metricsMu.Unlock()
	metrics := server.metrics
	if server.protocol == "tcp" && server.metricSamples > 0 {
		metrics.RTTMs /= float64(server.metricSamples)
	}
	return metrics
}
