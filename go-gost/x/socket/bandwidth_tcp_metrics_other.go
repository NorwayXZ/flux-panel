//go:build !linux

package socket

import "net"

func readTCPMetrics(net.Conn) bandwidthNetworkMetrics {
	return bandwidthNetworkMetrics{}
}

func tcpMetricsDelta(_, _ bandwidthNetworkMetrics) bandwidthNetworkMetrics {
	return bandwidthNetworkMetrics{}
}
