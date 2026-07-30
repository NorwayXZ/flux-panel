package socket

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	psnet "github.com/shirou/gopsutil/v3/net"
	"github.com/shirou/gopsutil/v3/process"
)

type nodeServiceDiscoveryRequest struct {
	TimeoutMs   int `json:"timeoutMs"`
	MaxServices int `json:"maxServices"`
}

type nodeDiscoveredService struct {
	Host           string `json:"host"`
	ProbeHost      string `json:"probeHost"`
	Port           int    `json:"port"`
	Protocol       string `json:"protocol"`
	ServiceName    string `json:"serviceName"`
	ProcessName    string `json:"processName,omitempty"`
	ProcessID      int32  `json:"processId,omitempty"`
	Executable     string `json:"executable,omitempty"`
	Product        string `json:"product,omitempty"`
	Title          string `json:"title,omitempty"`
	HTTPStatus     int    `json:"httpStatus,omitempty"`
	LatencyMs      int64  `json:"latencyMs,omitempty"`
	ContainerID    string `json:"containerId,omitempty"`
	ContainerName  string `json:"containerName,omitempty"`
	ContainerImage string `json:"containerImage,omitempty"`
	Sensitive      bool   `json:"sensitive"`
}

type nodeServiceDiscoveryResponse struct {
	Services        []nodeDiscoveredService `json:"services"`
	ListenerCount   int                     `json:"listenerCount"`
	WebServiceCount int                     `json:"webServiceCount"`
	DockerAvailable bool                    `json:"dockerAvailable"`
	ScannedAt       int64                   `json:"scannedAt"`
	DurationMs      int64                   `json:"durationMs"`
}

type dockerPortOwner struct {
	ID    string
	Name  string
	Image string
}

func (w *WebSocketReporter) handleNodeServiceDiscovery(data interface{}) (nodeServiceDiscoveryResponse, error) {
	started := time.Now()
	request := nodeServiceDiscoveryRequest{TimeoutMs: 700, MaxServices: 200}
	payload, err := json.Marshal(data)
	if err != nil {
		return nodeServiceDiscoveryResponse{}, fmt.Errorf("serialize service discovery request: %w", err)
	}
	if err := json.Unmarshal(payload, &request); err != nil {
		return nodeServiceDiscoveryResponse{}, fmt.Errorf("parse service discovery request: %w", err)
	}
	if request.TimeoutMs < 200 || request.TimeoutMs > 3000 {
		return nodeServiceDiscoveryResponse{}, errors.New("service probe timeout must be between 200 and 3000 ms")
	}
	if request.MaxServices < 1 || request.MaxServices > 500 {
		return nodeServiceDiscoveryResponse{}, errors.New("service discovery limit must be between 1 and 500")
	}

	connections, err := psnet.Connections("tcp")
	if err != nil {
		return nodeServiceDiscoveryResponse{}, fmt.Errorf("list TCP listeners: %w", err)
	}
	dockerPorts, dockerAvailable := discoverDockerPortOwners(time.Duration(request.TimeoutMs) * time.Millisecond)
	services := collectNodeListeners(connections, dockerPorts, request.MaxServices)
	probeNodeServices(services, time.Duration(request.TimeoutMs)*time.Millisecond)
	sort.Slice(services, func(i, j int) bool {
		if services[i].Port != services[j].Port {
			return services[i].Port < services[j].Port
		}
		return services[i].Host < services[j].Host
	})
	webCount := 0
	for i := range services {
		refineNodeService(&services[i])
		if services[i].Protocol == "http" || services[i].Protocol == "https" {
			webCount++
		}
	}
	return nodeServiceDiscoveryResponse{
		Services: services, ListenerCount: len(services), WebServiceCount: webCount,
		DockerAvailable: dockerAvailable, ScannedAt: time.Now().UnixMilli(), DurationMs: time.Since(started).Milliseconds(),
	}, nil
}

func collectNodeListeners(connections []psnet.ConnectionStat, dockerPorts map[int]dockerPortOwner, limit int) []nodeDiscoveredService {
	services := make([]nodeDiscoveredService, 0)
	seen := make(map[string]struct{})
	for _, connection := range connections {
		if !strings.EqualFold(connection.Status, "LISTEN") || connection.Laddr.Port == 0 {
			continue
		}
		host := normalizeListenerHost(connection.Laddr.IP)
		key := net.JoinHostPort(host, strconv.Itoa(int(connection.Laddr.Port)))
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		service := nodeDiscoveredService{
			Host: host, ProbeHost: listenerProbeHost(host), Port: int(connection.Laddr.Port),
			Protocol: "tcp", ServiceName: "TCP 服务", ProcessID: connection.Pid,
		}
		if connection.Pid > 0 {
			if proc, procErr := process.NewProcess(connection.Pid); procErr == nil {
				service.ProcessName, _ = proc.Name()
				service.Executable, _ = proc.Exe()
			}
		}
		if owner, ok := dockerPorts[service.Port]; ok {
			service.ContainerID = owner.ID
			service.ContainerName = owner.Name
			service.ContainerImage = owner.Image
		}
		services = append(services, service)
		if len(services) >= limit {
			break
		}
	}
	return services
}

func probeNodeServices(services []nodeDiscoveredService, timeout time.Duration) {
	if len(services) == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout*3)
	defer cancel()
	jobs := make(chan int)
	var workers sync.WaitGroup
	workerCount := 24
	if len(services) < workerCount {
		workerCount = len(services)
	}
	for worker := 0; worker < workerCount; worker++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for index := range jobs {
				probeNodeWebService(ctx, &services[index], timeout)
			}
		}()
	}
	for index := range services {
		select {
		case jobs <- index:
		case <-ctx.Done():
			close(jobs)
			workers.Wait()
			return
		}
	}
	close(jobs)
	workers.Wait()
}

func probeNodeWebService(ctx context.Context, service *nodeDiscoveredService, timeout time.Duration) {
	for _, scheme := range []string{"https", "http"} {
		started := time.Now()
		transport := &http.Transport{
			Proxy:               nil,
			DialContext:         (&net.Dialer{Timeout: timeout}).DialContext,
			TLSClientConfig:     &tls.Config{InsecureSkipVerify: true}, // #nosec G402 -- local metadata probe only.
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   true,
		}
		client := &http.Client{
			Transport:     transport,
			Timeout:       timeout,
			CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
		}
		host := net.JoinHostPort(service.ProbeHost, strconv.Itoa(service.Port))
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, scheme+"://"+host+"/", nil)
		if err != nil {
			transport.CloseIdleConnections()
			continue
		}
		request.Header.Set("User-Agent", "CloudNest-Node-Discovery")
		response, err := client.Do(request)
		if err != nil {
			transport.CloseIdleConnections()
			continue
		}
		if response.ProtoMajor < 1 {
			response.Body.Close()
			transport.CloseIdleConnections()
			continue
		}
		body := make([]byte, 8192)
		count, _ := response.Body.Read(body)
		response.Body.Close()
		transport.CloseIdleConnections()
		service.Protocol = scheme
		service.HTTPStatus = response.StatusCode
		service.LatencyMs = time.Since(started).Milliseconds()
		service.Product = cleanDiscoveryText(response.Header.Get("Server"), 160)
		if match := htmlTitlePattern.FindStringSubmatch(string(body[:count])); len(match) > 1 {
			service.Title = cleanDiscoveryText(match[1], 160)
		}
		return
	}
}

func refineNodeService(service *nodeDiscoveredService) {
	text := strings.ToLower(strings.Join([]string{service.ProcessName, service.ContainerName, service.ContainerImage, service.Product, service.Title}, " "))
	service.Sensitive = true
	switch {
	case strings.Contains(text, "x-ui") || strings.Contains(text, "xui"):
		service.ServiceName = "XUI 管理面板"
	case strings.Contains(text, "grafana"):
		service.ServiceName, service.Sensitive = "Grafana", false
	case strings.Contains(text, "portainer"):
		service.ServiceName = "Portainer"
	case strings.Contains(text, "nginx"):
		service.ServiceName, service.Sensitive = "Nginx Web 服务", false
	case strings.Contains(text, "apache") || strings.Contains(text, "httpd"):
		service.ServiceName, service.Sensitive = "Apache Web 服务", false
	case strings.Contains(text, "openwrt") || strings.Contains(text, "luci"):
		service.ServiceName = "OpenWrt 管理页面"
	case strings.Contains(text, "synology") || strings.Contains(text, "diskstation"):
		service.ServiceName = "群晖 NAS"
	case strings.Contains(text, "home assistant"):
		service.ServiceName = "Home Assistant"
	case service.Protocol == "http":
		service.ServiceName, service.Sensitive = "HTTP Web 服务", false
	case service.Protocol == "https":
		service.ServiceName, service.Sensitive = "HTTPS Web 服务", false
	default:
		service.ServiceName = "TCP 服务"
	}
}

func normalizeListenerHost(host string) string {
	host = strings.TrimSpace(strings.Trim(host, "[]"))
	if host == "" {
		return "0.0.0.0"
	}
	return host
}

func listenerProbeHost(host string) string {
	switch host {
	case "0.0.0.0", "*":
		return "127.0.0.1"
	case "::":
		return "::1"
	default:
		return host
	}
}

func discoverDockerPortOwners(timeout time.Duration) (map[int]dockerPortOwner, bool) {
	if _, err := os.Stat("/var/run/docker.sock"); err != nil {
		return map[int]dockerPortOwner{}, false
	}
	transport := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{Timeout: timeout}).DialContext(ctx, "unix", "/var/run/docker.sock")
		},
		DisableKeepAlives: true,
	}
	client := &http.Client{Transport: transport, Timeout: timeout}
	response, err := client.Get("http://docker/v1.24/containers/json")
	if err != nil {
		transport.CloseIdleConnections()
		return map[int]dockerPortOwner{}, false
	}
	defer response.Body.Close()
	if response.StatusCode/100 != 2 {
		return map[int]dockerPortOwner{}, false
	}
	var containers []struct {
		ID    string   `json:"Id"`
		Names []string `json:"Names"`
		Image string   `json:"Image"`
		Ports []struct {
			PublicPort int    `json:"PublicPort"`
			Type       string `json:"Type"`
		} `json:"Ports"`
	}
	if err := json.NewDecoder(response.Body).Decode(&containers); err != nil {
		return map[int]dockerPortOwner{}, false
	}
	owners := make(map[int]dockerPortOwner)
	for _, container := range containers {
		name := ""
		if len(container.Names) > 0 {
			name = strings.TrimPrefix(container.Names[0], "/")
		}
		id := container.ID
		if len(id) > 12 {
			id = id[:12]
		}
		for _, port := range container.Ports {
			if port.PublicPort > 0 && strings.EqualFold(port.Type, "tcp") {
				owners[port.PublicPort] = dockerPortOwner{ID: id, Name: name, Image: container.Image}
			}
		}
	}
	return owners, true
}
