package socket

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ipQualityHTTPResult struct {
	Name       string  `json:"name"`
	State      string  `json:"state"`
	HTTPStatus int     `json:"httpStatus,omitempty"`
	LatencyMs  float64 `json:"latencyMs"`
	Detail     string  `json:"detail,omitempty"`
}

type ipQualityPortResult struct {
	Name      string  `json:"name"`
	Host      string  `json:"host"`
	Port      int     `json:"port"`
	Reachable bool    `json:"reachable"`
	LatencyMs float64 `json:"latencyMs"`
	Error     string  `json:"error,omitempty"`
}

type ipQualityDNSResult struct {
	ConfiguredResolvers []string `json:"configuredResolvers"`
	ObservedResolvers   []string `json:"observedResolvers"`
	Error               string   `json:"error,omitempty"`
}

type ipQualityInspectResponse struct {
	PublicIPv4 string                `json:"publicIpv4,omitempty"`
	PublicIPv6 string                `json:"publicIpv6,omitempty"`
	Location   string                `json:"locationHint,omitempty"`
	DNS        ipQualityDNSResult    `json:"dns"`
	Services   []ipQualityHTTPResult `json:"services"`
	Ports      []ipQualityPortResult `json:"ports"`
	StartedAt  int64                 `json:"startedAt"`
	FinishedAt int64                 `json:"finishedAt"`
}

var ipQualityTraceURL = "https://www.cloudflare.com/cdn-cgi/trace"

var ipQualityServices = []struct {
	name string
	url  string
}{
	{"ChatGPT", "https://chatgpt.com/"},
	{"Netflix", "https://www.netflix.com/title/80018499"},
	{"Disney+", "https://www.disneyplus.com/"},
	{"YouTube Premium", "https://www.youtube.com/premium"},
}

var ipQualityPorts = []struct {
	name string
	host string
	port int
}{
	{"DNS over TCP", "1.1.1.1", 53},
	{"HTTPS", "1.1.1.1", 443},
	{"DNS over TLS", "1.1.1.1", 853},
	{"SSH", "github.com", 22},
	{"SMTP Submission", "smtp.gmail.com", 587},
	{"SMTP", "smtp.gmail.com", 25},
}

func (w *WebSocketReporter) handleIPQualityInspect(_ interface{}) (ipQualityInspectResponse, error) {
	started := time.Now()
	result := ipQualityInspectResponse{StartedAt: started.UnixMilli(), Services: []ipQualityHTTPResult{}, Ports: []ipQualityPortResult{}}
	var lock sync.Mutex
	var wait sync.WaitGroup

	wait.Add(2)
	go func() {
		defer wait.Done()
		ip, location, _ := queryIPQualityTrace("tcp4")
		lock.Lock()
		result.PublicIPv4, result.Location = ip, location
		lock.Unlock()
	}()
	go func() {
		defer wait.Done()
		ip, _, _ := queryIPQualityTrace("tcp6")
		lock.Lock()
		result.PublicIPv6 = ip
		lock.Unlock()
	}()

	services := make([]ipQualityHTTPResult, len(ipQualityServices))
	for index, target := range ipQualityServices {
		wait.Add(1)
		go func(index int, name, targetURL string) {
			defer wait.Done()
			services[index] = inspectIPQualityService(name, targetURL)
		}(index, target.name, target.url)
	}

	ports := make([]ipQualityPortResult, len(ipQualityPorts))
	for index, target := range ipQualityPorts {
		wait.Add(1)
		go func(index int, name, host string, port int) {
			defer wait.Done()
			ports[index] = inspectIPQualityPort(name, host, port)
		}(index, target.name, target.host, target.port)
	}

	wait.Add(1)
	go func() {
		defer wait.Done()
		dns := inspectIPQualityDNS()
		lock.Lock()
		result.DNS = dns
		lock.Unlock()
	}()
	wait.Wait()
	result.Services, result.Ports = services, ports
	result.FinishedAt = time.Now().UnixMilli()
	if result.PublicIPv4 == "" && result.PublicIPv6 == "" {
		return result, errors.New("unable to determine public egress IP")
	}
	return result, nil
}

func queryIPQualityTrace(network string) (string, string, error) {
	dialer := &net.Dialer{Timeout: 5 * time.Second}
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, address)
		},
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
	}
	client := &http.Client{Transport: transport, Timeout: 8 * time.Second}
	request, _ := http.NewRequest(http.MethodGet, ipQualityTraceURL, nil)
	request.Header.Set("User-Agent", "CloudNest-IPQuality/1.0")
	response, err := client.Do(request)
	if err != nil {
		return "", "", err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 32*1024))
	if err != nil {
		return "", "", err
	}
	values := map[string]string{}
	for _, line := range strings.Split(string(body), "\n") {
		parts := strings.SplitN(strings.TrimSpace(line), "=", 2)
		if len(parts) == 2 {
			values[parts[0]] = parts[1]
		}
	}
	ip := net.ParseIP(values["ip"])
	if ip == nil || (network == "tcp4" && ip.To4() == nil) || (network == "tcp6" && ip.To4() != nil) {
		return "", values["loc"], errors.New("trace returned an unexpected address family")
	}
	return ip.String(), values["loc"], nil
}

func inspectIPQualityService(name, targetURL string) ipQualityHTTPResult {
	started := time.Now()
	result := ipQualityHTTPResult{Name: name, State: "unknown"}
	client := &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(_ *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return errors.New("too many redirects")
		}
		return nil
	}}
	request, _ := http.NewRequest(http.MethodGet, targetURL, nil)
	request.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
	request.Header.Set("Accept-Language", "en-US,en;q=0.8")
	response, err := client.Do(request)
	result.LatencyMs = float64(time.Since(started).Microseconds()) / 1000
	if err != nil {
		result.State, result.Detail = "unavailable", conciseIPQualityError(err)
		return result
	}
	defer response.Body.Close()
	result.HTTPStatus = response.StatusCode
	body, _ := io.ReadAll(io.LimitReader(response.Body, 384*1024))
	lower := strings.ToLower(string(body))
	if response.StatusCode == http.StatusUnavailableForLegalReasons || strings.Contains(lower, "unsupported_country") || strings.Contains(lower, "not available in your region") {
		result.State, result.Detail = "restricted", "服务返回地区限制"
	} else if response.StatusCode >= 200 && response.StatusCode < 400 {
		result.State, result.Detail = "available", "服务页面可访问"
	} else if response.StatusCode == http.StatusForbidden {
		result.State, result.Detail = "unknown", "服务拒绝自动探测，不能据此判断解锁"
	} else {
		result.Detail = "HTTP " + strconv.Itoa(response.StatusCode)
	}
	return result
}

func inspectIPQualityPort(name, host string, port int) ipQualityPortResult {
	started := time.Now()
	result := ipQualityPortResult{Name: name, Host: host, Port: port}
	connection, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 4*time.Second)
	result.LatencyMs = float64(time.Since(started).Microseconds()) / 1000
	if err != nil {
		result.Error = conciseIPQualityError(err)
		return result
	}
	result.Reachable = true
	_ = connection.Close()
	return result
}

func inspectIPQualityDNS() ipQualityDNSResult {
	result := ipQualityDNSResult{ConfiguredResolvers: []string{}, ObservedResolvers: []string{}}
	if content, err := os.ReadFile("/etc/resolv.conf"); err == nil {
		for _, line := range strings.Split(string(content), "\n") {
			fields := strings.Fields(line)
			if len(fields) == 2 && fields[0] == "nameserver" && net.ParseIP(fields[1]) != nil {
				result.ConfiguredResolvers = append(result.ConfiguredResolvers, fields[1])
			}
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	addresses, err := net.DefaultResolver.LookupIPAddr(ctx, "whoami.akamai.net")
	if err != nil {
		result.Error = conciseIPQualityError(err)
		return result
	}
	seen := map[string]bool{}
	for _, address := range addresses {
		value := address.IP.String()
		if !seen[value] {
			seen[value] = true
			result.ObservedResolvers = append(result.ObservedResolvers, value)
		}
	}
	sort.Strings(result.ObservedResolvers)
	return result
}

func conciseIPQualityError(err error) string {
	if err == nil {
		return ""
	}
	value := strings.TrimSpace(err.Error())
	if len(value) > 180 {
		value = value[:180]
	}
	return value
}

func decodeIPQualityResponse(data interface{}) (ipQualityInspectResponse, error) {
	encoded, err := json.Marshal(data)
	if err != nil {
		return ipQualityInspectResponse{}, err
	}
	var response ipQualityInspectResponse
	err = json.Unmarshal(encoded, &response)
	return response, err
}
