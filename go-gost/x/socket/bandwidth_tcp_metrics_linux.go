//go:build linux

package socket

import (
	"net"
	"syscall"

	"golang.org/x/sys/unix"
)

func readTCPMetrics(connection net.Conn) bandwidthNetworkMetrics {
	syscallConnection, ok := connection.(syscall.Conn)
	if !ok {
		return bandwidthNetworkMetrics{}
	}
	var metrics bandwidthNetworkMetrics
	rawConnection, err := syscallConnection.SyscallConn()
	if err != nil {
		return metrics
	}
	_ = rawConnection.Control(func(fileDescriptor uintptr) {
		info, infoError := unix.GetsockoptTCPInfo(int(fileDescriptor), unix.IPPROTO_TCP, unix.TCP_INFO)
		if infoError != nil || info == nil {
			return
		}
		metrics.RTTMs = float64(info.Rtt) / 1000
		metrics.Retransmits = uint64(info.Total_retrans)
		metrics.PacketsSent = uint64(info.Segs_out)
		metrics.PacketsRecv = uint64(info.Segs_in)
	})
	return metrics
}

func tcpMetricsDelta(before, after bandwidthNetworkMetrics) bandwidthNetworkMetrics {
	metrics := bandwidthNetworkMetrics{RTTMs: after.RTTMs}
	if after.Retransmits >= before.Retransmits {
		metrics.Retransmits = after.Retransmits - before.Retransmits
	}
	if after.PacketsSent >= before.PacketsSent {
		metrics.PacketsSent = after.PacketsSent - before.PacketsSent
	}
	if after.PacketsRecv >= before.PacketsRecv {
		metrics.PacketsRecv = after.PacketsRecv - before.PacketsRecv
	}
	return metrics
}
