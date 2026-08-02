package socket

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

type privateNetworkProbeRequest struct {
	Target    string `json:"target"`
	Port      int    `json:"port"`
	Count     int    `json:"count"`
	TimeoutMs int    `json:"timeoutMs"`
}

type privateNetworkProbeResponse struct {
	Success       bool    `json:"success"`
	Target        string  `json:"target"`
	Port          int     `json:"port"`
	SourceAddress string  `json:"sourceAddress,omitempty"`
	RemoteAddress string  `json:"remoteAddress,omitempty"`
	InterfaceName string  `json:"interfaceName,omitempty"`
	RouteInfo     string  `json:"routeInfo,omitempty"`
	AverageTime   float64 `json:"averageTime"`
	PacketLoss    float64 `json:"packetLoss"`
	Error         string  `json:"error,omitempty"`
}

func (w *WebSocketReporter) handlePrivateNetworkProbe(data interface{}) (privateNetworkProbeResponse, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return privateNetworkProbeResponse{}, fmt.Errorf("serialize private network probe: %w", err)
	}
	var request privateNetworkProbeRequest
	if err := json.Unmarshal(raw, &request); err != nil {
		return privateNetworkProbeResponse{}, fmt.Errorf("parse private network probe: %w", err)
	}
	request.Target = strings.TrimSpace(request.Target)
	if net.ParseIP(request.Target) == nil {
		return privateNetworkProbeResponse{}, errors.New("private network target must be an IP address")
	}
	if request.Port < 1 || request.Port > 65535 {
		return privateNetworkProbeResponse{}, errors.New("private network target port is invalid")
	}
	if request.Count < 1 || request.Count > 10 {
		request.Count = 4
	}
	if request.TimeoutMs < 200 || request.TimeoutMs > 10000 {
		request.TimeoutMs = 3000
	}

	response := privateNetworkProbeResponse{Target: request.Target, Port: request.Port}
	route, iface := inspectRouteTo(request.Target, request.TimeoutMs)
	response.RouteInfo = route
	response.InterfaceName = iface
	var total time.Duration
	var succeeded int
	var lastError error
	for attempt := 0; attempt < request.Count; attempt++ {
		started := time.Now()
		connection, dialErr := net.DialTimeout("tcp", net.JoinHostPort(request.Target, strconv.Itoa(request.Port)), time.Duration(request.TimeoutMs)*time.Millisecond)
		if dialErr != nil {
			lastError = dialErr
			continue
		}
		total += time.Since(started)
		succeeded++
		response.SourceAddress = addressHost(connection.LocalAddr())
		response.RemoteAddress = addressHost(connection.RemoteAddr())
		_ = connection.Close()
	}
	response.PacketLoss = float64(request.Count-succeeded) * 100 / float64(request.Count)
	if succeeded == 0 {
		response.Error = "private address unreachable"
		if lastError != nil {
			response.Error = lastError.Error()
		}
		return response, nil
	}
	response.Success = true
	response.AverageTime = float64(total.Microseconds()) / 1000 / float64(succeeded)
	return response, nil
}

func inspectRouteTo(target string, timeoutMs int) (string, string) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	var name string
	var args []string
	switch runtime.GOOS {
	case "linux":
		name, args = "ip", []string{"route", "get", target}
	case "darwin", "freebsd":
		name, args = "route", []string{"-n", "get", target}
	default:
		return "", ""
	}
	if _, err := exec.LookPath(name); err != nil {
		return "", ""
	}
	output, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	if err != nil {
		return strings.TrimSpace(string(output)), ""
	}
	route := strings.TrimSpace(string(output))
	if len(route) > 500 {
		route = route[:500]
	}
	return route, routeInterface(route)
}

func routeInterface(route string) string {
	fields := strings.Fields(route)
	for index, field := range fields {
		if (field == "dev" || field == "interface:") && index+1 < len(fields) {
			return strings.TrimSpace(fields[index+1])
		}
	}
	return ""
}

func addressHost(address net.Addr) string {
	if address == nil {
		return ""
	}
	host, _, err := net.SplitHostPort(address.String())
	if err != nil {
		return address.String()
	}
	return host
}
