package socket

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type qualityProbeRequest struct {
	Target     string `json:"target"`
	Port       int    `json:"port"`
	Protocol   string `json:"protocol"`
	Path       string `json:"path"`
	ServerName string `json:"serverName"`
	IPFamily   string `json:"ipFamily"`
	Count      int    `json:"count"`
	TimeoutMs  int    `json:"timeoutMs"`
}

type qualityProbeSample struct {
	Index      int     `json:"index"`
	Success    bool    `json:"success"`
	TCPMs      float64 `json:"tcpMs"`
	TLSMs      float64 `json:"tlsMs,omitempty"`
	TTFBMs     float64 `json:"ttfbMs,omitempty"`
	TotalMs    float64 `json:"totalMs"`
	HTTPStatus int     `json:"httpStatus,omitempty"`
	Error      string  `json:"error,omitempty"`
}

type qualityProbeResponse struct {
	Target          string               `json:"target"`
	ResolvedAddress string               `json:"resolvedAddress,omitempty"`
	IPFamily        string               `json:"ipFamily"`
	Protocol        string               `json:"protocol"`
	DNSMs           float64              `json:"dnsMs"`
	SuccessCount    int                  `json:"successCount"`
	FailureRate     float64              `json:"failureRate"`
	JitterMs        float64              `json:"jitterMs"`
	Samples         []qualityProbeSample `json:"samples"`
	Error           string               `json:"error,omitempty"`
}

type firstReadConn struct {
	net.Conn
	once      sync.Once
	firstRead time.Time
}

func (c *firstReadConn) Read(buffer []byte) (int, error) {
	n, err := c.Conn.Read(buffer)
	if n > 0 {
		c.once.Do(func() { c.firstRead = time.Now() })
	}
	return n, err
}

func (w *WebSocketReporter) handleQualityProbe(data interface{}) (qualityProbeResponse, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return qualityProbeResponse{}, fmt.Errorf("serialize quality probe: %w", err)
	}
	var request qualityProbeRequest
	if err := json.Unmarshal(raw, &request); err != nil {
		return qualityProbeResponse{}, fmt.Errorf("parse quality probe: %w", err)
	}
	if err := normalizeQualityProbeRequest(&request); err != nil {
		return qualityProbeResponse{}, err
	}

	response := qualityProbeResponse{
		Target: request.Target, IPFamily: request.IPFamily, Protocol: request.Protocol,
		Samples: make([]qualityProbeSample, 0, request.Count),
	}
	resolveStarted := time.Now()
	resolved, family, resolveErr := resolveQualityTarget(request.Target, request.IPFamily,
		time.Duration(request.TimeoutMs)*time.Millisecond)
	response.DNSMs = milliseconds(time.Since(resolveStarted))
	response.IPFamily = family
	response.ResolvedAddress = resolved
	if resolveErr != nil {
		response.Error = conciseProbeError(resolveErr)
		response.FailureRate = 100
		for index := 1; index <= request.Count; index++ {
			response.Samples = append(response.Samples, qualityProbeSample{Index: index, Error: response.Error})
		}
		return response, nil
	}

	latencies := make([]float64, 0, request.Count)
	for index := 1; index <= request.Count; index++ {
		sample := runQualityProbeSample(request, resolved, family, index)
		response.Samples = append(response.Samples, sample)
		if sample.Success {
			response.SuccessCount++
			latencies = append(latencies, sample.TotalMs)
		}
		if index < request.Count {
			time.Sleep(100 * time.Millisecond)
		}
	}
	response.FailureRate = float64(request.Count-response.SuccessCount) * 100 / float64(request.Count)
	response.JitterMs = successiveJitter(latencies)
	if response.SuccessCount == 0 {
		for _, sample := range response.Samples {
			if sample.Error != "" {
				response.Error = sample.Error
				break
			}
		}
		if response.Error == "" {
			response.Error = "all probe attempts failed"
		}
	}
	return response, nil
}

func normalizeQualityProbeRequest(request *qualityProbeRequest) error {
	request.Target = strings.TrimSpace(request.Target)
	request.Protocol = strings.ToLower(strings.TrimSpace(request.Protocol))
	request.IPFamily = strings.ToLower(strings.TrimSpace(request.IPFamily))
	request.Path = strings.TrimSpace(request.Path)
	request.ServerName = strings.TrimSpace(request.ServerName)
	if !validDiagnosticTarget(request.Target) {
		return errors.New("target must be a valid IP address or hostname")
	}
	if request.ServerName != "" && !validDiagnosticTarget(request.ServerName) {
		return errors.New("TLS server name must be a valid IP address or hostname")
	}
	if request.Protocol != "tcp" && request.Protocol != "tls" && request.Protocol != "http" && request.Protocol != "https" {
		return errors.New("protocol must be tcp, tls, http, or https")
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
	if request.Count < 1 || request.Count > 10 {
		return errors.New("sample count must be between 1 and 10")
	}
	if request.TimeoutMs < 500 || request.TimeoutMs > 15000 {
		return errors.New("timeout must be between 500 and 15000 milliseconds")
	}
	if request.Path == "" {
		request.Path = "/"
	}
	if len(request.Path) > 512 || !strings.HasPrefix(request.Path, "/") || strings.ContainsAny(request.Path, "\r\n") {
		return errors.New("HTTP path must start with / and contain at most 512 characters")
	}
	return nil
}

func resolveQualityTarget(target, requestedFamily string, timeout time.Duration) (string, string, error) {
	if parsed := net.ParseIP(target); parsed != nil {
		family := "ipv6"
		if parsed.To4() != nil {
			family = "ipv4"
		}
		if requestedFamily != "auto" && requestedFamily != family {
			return "", requestedFamily, fmt.Errorf("target has no %s address", requestedFamily)
		}
		return parsed.String(), family, nil
	}
	network := "ip"
	if requestedFamily == "ipv4" {
		network = "ip4"
	} else if requestedFamily == "ipv6" {
		network = "ip6"
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	addresses, err := net.DefaultResolver.LookupIP(ctx, network, target)
	if err != nil || len(addresses) == 0 {
		if err == nil {
			err = errors.New("DNS returned no matching address")
		}
		return "", requestedFamily, err
	}
	sort.SliceStable(addresses, func(left, right int) bool {
		return addresses[left].To4() != nil && addresses[right].To4() == nil
	})
	selected := addresses[0]
	family := "ipv6"
	if selected.To4() != nil {
		family = "ipv4"
	}
	return selected.String(), family, nil
}

func runQualityProbeSample(request qualityProbeRequest, resolved, family string, index int) qualityProbeSample {
	started := time.Now()
	sample := qualityProbeSample{Index: index}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(request.TimeoutMs)*time.Millisecond)
	defer cancel()
	network := "tcp4"
	if family == "ipv6" {
		network = "tcp6"
	}
	address := net.JoinHostPort(resolved, strconv.Itoa(request.Port))
	dialStarted := time.Now()
	connection, err := (&net.Dialer{}).DialContext(ctx, network, address)
	sample.TCPMs = milliseconds(time.Since(dialStarted))
	if err != nil {
		sample.TotalMs = milliseconds(time.Since(started))
		sample.Error = conciseProbeError(err)
		return sample
	}
	defer connection.Close()

	activeConnection := connection
	if request.Protocol == "tls" || request.Protocol == "https" {
		serverName := request.ServerName
		if serverName == "" {
			serverName = request.Target
		}
		tlsConnection := tls.Client(connection, &tls.Config{ServerName: serverName, MinVersion: tls.VersionTLS12})
		tlsStarted := time.Now()
		if err := tlsConnection.HandshakeContext(ctx); err != nil {
			sample.TLSMs = milliseconds(time.Since(tlsStarted))
			sample.TotalMs = milliseconds(time.Since(started))
			sample.Error = conciseProbeError(err)
			return sample
		}
		sample.TLSMs = milliseconds(time.Since(tlsStarted))
		activeConnection = tlsConnection
	}

	if request.Protocol == "http" || request.Protocol == "https" {
		tracked := &firstReadConn{Conn: activeConnection}
		host := request.Target
		if parsed := net.ParseIP(host); parsed != nil && parsed.To4() == nil {
			host = "[" + host + "]"
		}
		if (request.Protocol == "http" && request.Port != 80) || (request.Protocol == "https" && request.Port != 443) {
			host = net.JoinHostPort(request.Target, strconv.Itoa(request.Port))
		}
		requestStarted := time.Now()
		_, err = fmt.Fprintf(tracked, "GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: CloudNest-QualityProbe\r\nAccept: */*\r\nConnection: close\r\n\r\n", request.Path, host)
		if err == nil {
			var response *http.Response
			response, err = http.ReadResponse(bufio.NewReader(tracked), &http.Request{Method: http.MethodGet})
			if response != nil {
				sample.HTTPStatus = response.StatusCode
				_ = response.Body.Close()
			}
		}
		if !tracked.firstRead.IsZero() {
			sample.TTFBMs = milliseconds(tracked.firstRead.Sub(requestStarted))
		}
		if err != nil {
			sample.TotalMs = milliseconds(time.Since(started))
			sample.Error = conciseProbeError(err)
			return sample
		}
	}

	sample.Success = true
	sample.TotalMs = milliseconds(time.Since(started))
	return sample
}

func successiveJitter(values []float64) float64 {
	if len(values) < 2 {
		return 0
	}
	var total float64
	for index := 1; index < len(values); index++ {
		delta := values[index] - values[index-1]
		if delta < 0 {
			delta = -delta
		}
		total += delta
	}
	return total / float64(len(values)-1)
}

func milliseconds(duration time.Duration) float64 {
	return float64(duration.Microseconds()) / 1000
}

func conciseProbeError(err error) string {
	if err == nil {
		return ""
	}
	value := strings.TrimSpace(err.Error())
	if len(value) > 240 {
		value = value[:240]
	}
	return value
}
