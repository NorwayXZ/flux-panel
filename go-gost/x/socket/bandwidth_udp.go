package socket

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"net"
	"runtime"
	"sync"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/mem"
)

const (
	udpBandwidthPacketSize = 1200
	udpBandwidthHeaderSize = 48
	udpBandwidthPayload    = udpBandwidthPacketSize - udpBandwidthHeaderSize
	udpPacketStart         = byte(1)
	udpPacketData          = byte(2)
	udpPacketEnd           = byte(3)
	udpPacketAck           = byte(4)
	udpModeUpload          = byte(1)
	udpModeDownload        = byte(2)
)

var udpBandwidthMagic = [4]byte{'B', 'W', 'U', '1'}

type udpBandwidthPacket struct {
	packetType byte
	mode       byte
	streamID   uint16
	sequence   uint64
	sentAt     int64
	total      uint64
	payload    []byte
}

type udpPacketTracker struct {
	seen            []uint64
	maximumPackets  uint64
	received        uint64
	receivedBytes   uint64
	outOfOrder      uint64
	highest         uint64
	hasHighest      bool
	previousArrival int64
	previousSent    int64
	jitterNs        float64
	expected        uint64
}

type udpBandwidthStream struct {
	mu          sync.Mutex
	mode        byte
	remote      *net.UDPAddr
	streamID    uint16
	maximum     int64
	duration    time.Duration
	tracker     udpPacketTracker
	sentPackets uint64
	sentBytes   uint64
	done        bool
}

type udpStreamResult struct {
	mode    string
	bytes   int64
	metrics bandwidthNetworkMetrics
	err     error
}

func tokenDigest(token string) [16]byte {
	sum := sha256.Sum256([]byte(token))
	var digest [16]byte
	copy(digest[:], sum[:16])
	return digest
}

func encodeUDPPacket(packetType, mode byte, streamID uint16, sequence uint64, sentAt int64, total uint64, digest [16]byte, payloadSize int) []byte {
	if payloadSize < 0 {
		payloadSize = 0
	}
	packet := make([]byte, udpBandwidthHeaderSize+payloadSize)
	copy(packet[:4], udpBandwidthMagic[:])
	packet[4] = packetType
	packet[5] = mode
	binary.BigEndian.PutUint16(packet[6:8], streamID)
	binary.BigEndian.PutUint64(packet[8:16], sequence)
	binary.BigEndian.PutUint64(packet[16:24], uint64(sentAt))
	binary.BigEndian.PutUint64(packet[24:32], total)
	copy(packet[32:48], digest[:])
	return packet
}

func decodeUDPPacket(data []byte, expectedDigest [16]byte) (udpBandwidthPacket, bool) {
	if len(data) < udpBandwidthHeaderSize || subtle.ConstantTimeCompare(data[:4], udpBandwidthMagic[:]) != 1 || subtle.ConstantTimeCompare(data[32:48], expectedDigest[:]) != 1 {
		return udpBandwidthPacket{}, false
	}
	return udpBandwidthPacket{
		packetType: data[4], mode: data[5], streamID: binary.BigEndian.Uint16(data[6:8]), sequence: binary.BigEndian.Uint64(data[8:16]),
		sentAt: int64(binary.BigEndian.Uint64(data[16:24])), total: binary.BigEndian.Uint64(data[24:32]), payload: data[udpBandwidthHeaderSize:],
	}, true
}

func (tracker *udpPacketTracker) record(sequence uint64, sentAt int64, payloadBytes int) {
	if tracker.maximumPackets > 0 && sequence >= tracker.maximumPackets {
		return
	}
	word := int(sequence / 64)
	if word >= len(tracker.seen) {
		tracker.seen = append(tracker.seen, make([]uint64, word-len(tracker.seen)+1)...)
	}
	mask := uint64(1) << (sequence % 64)
	if tracker.seen[word]&mask != 0 {
		return
	}
	tracker.seen[word] |= mask
	if tracker.hasHighest && sequence < tracker.highest {
		tracker.outOfOrder++
	}
	if !tracker.hasHighest || sequence > tracker.highest {
		tracker.highest = sequence
		tracker.hasHighest = true
	}
	arrival := time.Now().UnixNano()
	if tracker.previousArrival != 0 && sentAt != 0 {
		delta := math.Abs(float64((arrival - tracker.previousArrival) - (sentAt - tracker.previousSent)))
		tracker.jitterNs += (delta - tracker.jitterNs) / 16
	}
	tracker.previousArrival = arrival
	tracker.previousSent = sentAt
	tracker.received++
	tracker.receivedBytes += uint64(payloadBytes)
}

func (tracker *udpPacketTracker) metrics() bandwidthNetworkMetrics {
	lost := uint64(0)
	if tracker.expected > tracker.received {
		lost = tracker.expected - tracker.received
	}
	return bandwidthNetworkMetrics{PacketsRecv: tracker.received, PacketsLost: lost, OutOfOrder: tracker.outOfOrder, JitterMs: tracker.jitterNs / float64(time.Millisecond)}
}

func udpStreamKey(address *net.UDPAddr, streamID uint16) string {
	return fmt.Sprintf("%s/%d", address.String(), streamID)
}

func serveUDPBandwidth(server *bandwidthServer) {
	digest := tokenDigest(server.token)
	buffer := make([]byte, udpBandwidthPacketSize+64)
	for {
		count, remote, err := server.udpConnection.ReadFromUDP(buffer)
		if err != nil {
			return
		}
		packet, valid := decodeUDPPacket(buffer[:count], digest)
		if !valid || (packet.mode != udpModeUpload && packet.mode != udpModeDownload) {
			continue
		}
		key := udpStreamKey(remote, packet.streamID)
		switch packet.packetType {
		case udpPacketStart:
			server.metricsMu.Lock()
			stream := server.udpStreams[key]
			created := false
			if stream == nil && len(server.udpStreams) < server.maximumStreams {
				maximum := int64(packet.sequence)
				if maximum < 1 || maximum > server.maximumBytes {
					maximum = server.maximumBytes
				}
				duration := time.Duration(packet.sentAt) * time.Millisecond
				if duration < time.Second || duration > bandwidthMaximumDuration {
					duration = bandwidthMaximumDuration
				}
				stream = &udpBandwidthStream{mode: packet.mode, remote: remote, streamID: packet.streamID, maximum: maximum, duration: duration}
				stream.tracker.maximumPackets = uint64(maximum/int64(udpBandwidthPayload)) + 1
				server.udpStreams[key] = stream
				server.active.Add(1)
				created = true
			}
			server.metricsMu.Unlock()
			if stream == nil {
				continue
			}
			_, _ = server.udpConnection.WriteToUDP(encodeUDPPacket(udpPacketAck, packet.mode, packet.streamID, 0, 0, 0, digest, 0), remote)
			if created && packet.mode == udpModeDownload {
				go sendUDPDownload(server, stream, digest)
			}
		case udpPacketData:
			server.metricsMu.Lock()
			stream := server.udpStreams[key]
			server.metricsMu.Unlock()
			if stream != nil && stream.mode == udpModeUpload {
				stream.mu.Lock()
				stream.tracker.record(packet.sequence, packet.sentAt, len(packet.payload))
				stream.mu.Unlock()
			}
		case udpPacketEnd:
			server.metricsMu.Lock()
			stream := server.udpStreams[key]
			server.metricsMu.Unlock()
			if stream != nil && stream.mode == udpModeUpload {
				stream.mu.Lock()
				stream.tracker.expected = packet.total
				if !stream.done {
					stream.done = true
					server.active.Add(-1)
				}
				stream.mu.Unlock()
			}
			_, _ = server.udpConnection.WriteToUDP(encodeUDPPacket(udpPacketAck, packet.mode, packet.streamID, 0, 0, packet.total, digest, 0), remote)
		}
	}
}

func sendUDPDownload(server *bandwidthServer, stream *udpBandwidthStream, digest [16]byte) {
	time.Sleep(15 * time.Millisecond)
	deadline := time.Now().Add(stream.duration)
	var sequence uint64
	var transferred int64
	for transferred < stream.maximum && time.Now().Before(deadline) {
		payloadSize := udpBandwidthPayload
		if remaining := stream.maximum - transferred; remaining < int64(payloadSize) {
			payloadSize = int(remaining)
		}
		packet := encodeUDPPacket(udpPacketData, udpModeDownload, stream.streamID, sequence, time.Now().UnixNano(), 0, digest, payloadSize)
		count, err := server.udpConnection.WriteToUDP(packet, stream.remote)
		if err != nil {
			break
		}
		transferred += int64(count - udpBandwidthHeaderSize)
		sequence++
	}
	stream.mu.Lock()
	stream.sentPackets = sequence
	stream.sentBytes = uint64(transferred)
	if !stream.done {
		stream.done = true
		server.active.Add(-1)
	}
	stream.mu.Unlock()
	endPacket := encodeUDPPacket(udpPacketEnd, udpModeDownload, stream.streamID, 0, 0, sequence, digest, 0)
	for attempt := 0; attempt < 3; attempt++ {
		_, _ = server.udpConnection.WriteToUDP(endPacket, stream.remote)
		time.Sleep(20 * time.Millisecond)
	}
}

func runUDPBandwidth(request bandwidthRunRequest) (bandwidthRunResponse, error) {
	beforeCPU, _ := cpu.Percent(0, false)
	started := time.Now()
	outcomes := make(chan udpStreamResult, request.Streams)
	for index := 0; index < request.Streams; index++ {
		mode := request.Direction
		if mode == "bidirectional" {
			if index%2 == 0 {
				mode = "upload"
			} else {
				mode = "download"
			}
		}
		go func(streamID int, streamMode string) {
			outcomes <- runUDPBandwidthStream(request, uint16(streamID+1), streamMode)
		}(index, mode)
	}
	result := bandwidthRunResponse{Protocol: "udp", Direction: request.Direction, Streams: request.Streams}
	for index := 0; index < request.Streams; index++ {
		outcome := <-outcomes
		if outcome.err != nil {
			result.Failed++
			continue
		}
		result.Successful++
		if outcome.mode == "upload" {
			result.UploadBytes += outcome.bytes
		} else {
			result.DownloadBytes += outcome.bytes
		}
		result.PacketsSent += outcome.metrics.PacketsSent
		result.PacketsRecv += outcome.metrics.PacketsRecv
		result.PacketsLost += outcome.metrics.PacketsLost
		result.OutOfOrder += outcome.metrics.OutOfOrder
		result.RTTMs += outcome.metrics.RTTMs
		if outcome.metrics.JitterMs > result.JitterMs {
			result.JitterMs = outcome.metrics.JitterMs
		}
	}
	result.DurationMs = maxInt64(time.Since(started).Milliseconds())
	seconds := float64(result.DurationMs) / 1000
	result.UploadMbps = float64(result.UploadBytes) * 8 / seconds / 1_000_000
	result.DownloadMbps = float64(result.DownloadBytes) * 8 / seconds / 1_000_000
	result.TotalMbps = result.UploadMbps + result.DownloadMbps
	if result.Successful > 0 {
		result.RTTMs /= float64(result.Successful)
	}
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
		return result, errors.New("all UDP bandwidth streams failed; check the target UDP port and firewall")
	}
	runtime.GC()
	return result, nil
}

func runUDPBandwidthStream(request bandwidthRunRequest, streamID uint16, mode string) udpStreamResult {
	remote, err := net.ResolveUDPAddr("udp", net.JoinHostPort(request.TargetHost, fmt.Sprint(request.Port)))
	if err != nil {
		return udpStreamResult{mode: mode, err: err}
	}
	connection, err := net.DialUDP("udp", nil, remote)
	if err != nil {
		return udpStreamResult{mode: mode, err: err}
	}
	defer connection.Close()
	_ = connection.SetReadBuffer(4 * 1024 * 1024)
	_ = connection.SetWriteBuffer(4 * 1024 * 1024)
	digest := tokenDigest(request.Token)
	modeCode := udpModeUpload
	if mode == "download" {
		modeCode = udpModeDownload
	}
	start := encodeUDPPacket(udpPacketStart, modeCode, streamID, uint64(request.MaximumBytes), int64(request.DurationSeconds)*1000, 0, digest, 0)
	handshakeStarted := time.Now()
	if err := waitForUDPAck(connection, start, digest, streamID, modeCode); err != nil {
		return udpStreamResult{mode: mode, err: err}
	}
	rttMs := float64(time.Since(handshakeStarted).Microseconds()) / 1000
	if mode == "upload" {
		return sendUDPUpload(connection, request, streamID, digest, rttMs)
	}
	return receiveUDPDownload(connection, request, streamID, digest, rttMs)
}

func waitForUDPAck(connection *net.UDPConn, start []byte, digest [16]byte, streamID uint16, mode byte) error {
	buffer := make([]byte, udpBandwidthPacketSize+64)
	for attempt := 0; attempt < 3; attempt++ {
		if _, err := connection.Write(start); err != nil {
			return err
		}
		_ = connection.SetReadDeadline(time.Now().Add(700 * time.Millisecond))
		count, err := connection.Read(buffer)
		if err != nil {
			continue
		}
		packet, valid := decodeUDPPacket(buffer[:count], digest)
		if valid && packet.packetType == udpPacketAck && packet.streamID == streamID && packet.mode == mode {
			return nil
		}
	}
	return errors.New("UDP bandwidth server did not acknowledge the authenticated stream")
}

func sendUDPUpload(connection *net.UDPConn, request bandwidthRunRequest, streamID uint16, digest [16]byte, rttMs float64) udpStreamResult {
	_ = connection.SetReadDeadline(time.Time{})
	deadline := time.Now().Add(time.Duration(request.DurationSeconds) * time.Second)
	var sequence uint64
	var transferred int64
	for transferred < request.MaximumBytes && time.Now().Before(deadline) {
		payloadSize := udpBandwidthPayload
		if remaining := request.MaximumBytes - transferred; remaining < int64(payloadSize) {
			payloadSize = int(remaining)
		}
		packet := encodeUDPPacket(udpPacketData, udpModeUpload, streamID, sequence, time.Now().UnixNano(), 0, digest, payloadSize)
		count, err := connection.Write(packet)
		if err != nil {
			return udpStreamResult{mode: "upload", bytes: transferred, err: err}
		}
		transferred += int64(count - udpBandwidthHeaderSize)
		sequence++
	}
	endPacket := encodeUDPPacket(udpPacketEnd, udpModeUpload, streamID, 0, 0, sequence, digest, 0)
	for attempt := 0; attempt < 3; attempt++ {
		_, _ = connection.Write(endPacket)
		time.Sleep(20 * time.Millisecond)
	}
	return udpStreamResult{mode: "upload", bytes: transferred, metrics: bandwidthNetworkMetrics{RTTMs: rttMs, PacketsSent: sequence}}
}

func receiveUDPDownload(connection *net.UDPConn, request bandwidthRunRequest, streamID uint16, digest [16]byte, rttMs float64) udpStreamResult {
	tracker := udpPacketTracker{maximumPackets: uint64(request.MaximumBytes/int64(udpBandwidthPayload)) + 1}
	buffer := make([]byte, udpBandwidthPacketSize+64)
	deadline := time.Now().Add(time.Duration(request.DurationSeconds)*time.Second + 2*time.Second)
	var endReceivedAt time.Time
	for time.Now().Before(deadline) {
		readDeadline := deadline
		if !endReceivedAt.IsZero() {
			readDeadline = endReceivedAt.Add(250 * time.Millisecond)
		}
		_ = connection.SetReadDeadline(readDeadline)
		count, err := connection.Read(buffer)
		if err != nil {
			if !endReceivedAt.IsZero() {
				break
			}
			return udpStreamResult{mode: "download", bytes: int64(tracker.receivedBytes), err: err}
		}
		packet, valid := decodeUDPPacket(buffer[:count], digest)
		if !valid || packet.streamID != streamID || packet.mode != udpModeDownload {
			continue
		}
		if packet.packetType == udpPacketData {
			tracker.record(packet.sequence, packet.sentAt, len(packet.payload))
		} else if packet.packetType == udpPacketEnd {
			tracker.expected = packet.total
			if endReceivedAt.IsZero() {
				endReceivedAt = time.Now()
			}
		}
	}
	if tracker.expected == 0 {
		return udpStreamResult{mode: "download", bytes: int64(tracker.receivedBytes), err: errors.New("UDP bandwidth stream ended without a packet summary")}
	}
	metrics := tracker.metrics()
	metrics.RTTMs = rttMs
	return udpStreamResult{mode: "download", bytes: int64(tracker.receivedBytes), metrics: metrics}
}

func (server *bandwidthServer) collectUDPMetrics() {
	server.metricsMu.Lock()
	streams := make([]*udpBandwidthStream, 0, len(server.udpStreams))
	for _, stream := range server.udpStreams {
		streams = append(streams, stream)
	}
	server.metricsMu.Unlock()
	combined := bandwidthNetworkMetrics{}
	for _, stream := range streams {
		stream.mu.Lock()
		if stream.mode == udpModeUpload {
			metrics := stream.tracker.metrics()
			combined.PacketsRecv += metrics.PacketsRecv
			combined.PacketsLost += metrics.PacketsLost
			combined.OutOfOrder += metrics.OutOfOrder
			if metrics.JitterMs > combined.JitterMs {
				combined.JitterMs = metrics.JitterMs
			}
		} else {
			combined.PacketsSent += stream.sentPackets
		}
		stream.mu.Unlock()
	}
	server.metricsMu.Lock()
	server.metrics = combined
	server.metricsMu.Unlock()
}
