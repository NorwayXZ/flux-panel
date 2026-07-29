package socket

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var defaultDiscoveryPorts = []int{
	21, 22, 23, 53, 80, 81, 139, 443, 445, 554, 1883, 3000, 3306, 3389,
	5000, 5001, 5432, 8000, 8001, 8080, 8123, 8443, 8883, 9000, 9090, 32400,
}

var htmlTitlePattern = regexp.MustCompile(`(?is)<title[^>]*>\s*(.*?)\s*</title>`)

type lanDiscoveryRequest struct {
	CIDR         string   `json:"cidr"`
	AllowedCIDRs []string `json:"allowedCidrs"`
	Ports        []int    `json:"ports"`
	TimeoutMs    int      `json:"timeoutMs"`
	MaxHosts     int      `json:"maxHosts"`
}

type lanDiscoveredService struct {
	Host        string `json:"host"`
	Port        int    `json:"port"`
	ServiceType string `json:"serviceType"`
	ServiceName string `json:"serviceName"`
	Product     string `json:"product,omitempty"`
	Title       string `json:"title,omitempty"`
	Confidence  string `json:"confidence"`
	Sensitive   bool   `json:"sensitive"`
}

type lanDiscoveryResponse struct {
	Ranges       []string               `json:"ranges"`
	Services     []lanDiscoveredService `json:"services"`
	ScannedHosts int                    `json:"scannedHosts"`
	ScannedPorts int                    `json:"scannedPorts"`
	DurationMs   int64                  `json:"durationMs"`
}

type discoveryTarget struct {
	host string
	port int
}

func (w *WebSocketReporter) handleLanDiscovery(data interface{}) (lanDiscoveryResponse, error) {
	started := time.Now()
	request := lanDiscoveryRequest{TimeoutMs: 250, MaxHosts: 513}
	payload, err := json.Marshal(data)
	if err != nil {
		return lanDiscoveryResponse{}, fmt.Errorf("serialize discovery request: %w", err)
	}
	if err := json.Unmarshal(payload, &request); err != nil {
		return lanDiscoveryResponse{}, fmt.Errorf("parse discovery request: %w", err)
	}
	if request.TimeoutMs < 100 || request.TimeoutMs > 800 {
		return lanDiscoveryResponse{}, errors.New("discovery timeout must be between 100 and 800 ms")
	}
	if request.MaxHosts < 1 || request.MaxHosts > 513 {
		return lanDiscoveryResponse{}, errors.New("discovery host limit must be between 1 and 513")
	}
	ports, err := normalizeDiscoveryPorts(request.Ports)
	if err != nil {
		return lanDiscoveryResponse{}, err
	}
	ranges, err := discoveryRanges(request.CIDR, request.AllowedCIDRs)
	if err != nil {
		return lanDiscoveryResponse{}, err
	}
	hosts, err := discoveryHosts(ranges, request.MaxHosts)
	if err != nil {
		return lanDiscoveryResponse{}, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 22*time.Second)
	defer cancel()
	jobs := make(chan discoveryTarget)
	results := make(chan lanDiscoveredService, 128)
	workerCount := 160
	if total := len(hosts) * len(ports); total < workerCount {
		workerCount = total
	}
	var workers sync.WaitGroup
	for i := 0; i < workerCount; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			dialer := net.Dialer{Timeout: time.Duration(request.TimeoutMs) * time.Millisecond}
			for target := range jobs {
				service, found := probeLanService(ctx, &dialer, target)
				if found {
					select {
					case results <- service:
					case <-ctx.Done():
						return
					}
				}
			}
		}()
	}
	go func() {
		defer close(jobs)
		for _, host := range hosts {
			for _, port := range ports {
				select {
				case jobs <- discoveryTarget{host: host, port: port}:
				case <-ctx.Done():
					return
				}
			}
		}
	}()
	go func() {
		workers.Wait()
		close(results)
	}()

	services := make([]lanDiscoveredService, 0)
	for service := range results {
		services = append(services, service)
	}
	if ctx.Err() != nil && len(services) == 0 {
		return lanDiscoveryResponse{}, errors.New("local network discovery timed out")
	}
	sort.Slice(services, func(i, j int) bool {
		left := net.ParseIP(services[i].Host).To4()
		right := net.ParseIP(services[j].Host).To4()
		if left != nil && right != nil {
			lv := binary.BigEndian.Uint32(left)
			rv := binary.BigEndian.Uint32(right)
			if lv != rv {
				return lv < rv
			}
		}
		return services[i].Port < services[j].Port
	})

	return lanDiscoveryResponse{
		Ranges: ranges, Services: services, ScannedHosts: len(hosts), ScannedPorts: len(ports),
		DurationMs: time.Since(started).Milliseconds(),
	}, nil
}

func normalizeDiscoveryPorts(input []int) ([]int, error) {
	if len(input) == 0 {
		return append([]int(nil), defaultDiscoveryPorts...), nil
	}
	if len(input) > 32 {
		return nil, errors.New("discovery accepts at most 32 ports")
	}
	seen := make(map[int]struct{}, len(input))
	ports := make([]int, 0, len(input))
	for _, port := range input {
		if port < 1 || port > 65535 {
			return nil, errors.New("discovery port must be between 1 and 65535")
		}
		if _, ok := seen[port]; ok {
			continue
		}
		seen[port] = struct{}{}
		ports = append(ports, port)
	}
	sort.Ints(ports)
	return ports, nil
}

func discoveryRanges(requested string, allowedCIDRs []string) ([]string, error) {
	requested = strings.TrimSpace(requested)
	if requested != "" && !strings.EqualFold(requested, "auto") {
		prefix, err := netip.ParsePrefix(requested)
		if err != nil || !prefix.Addr().Is4() {
			return nil, errors.New("discovery range must be an IPv4 CIDR")
		}
		prefix = prefix.Masked()
		if !prefix.Addr().IsPrivate() && !prefix.Addr().IsLoopback() {
			return nil, errors.New("discovery can only scan private IPv4 ranges")
		}
		if prefix.Bits() < 24 {
			return nil, errors.New("discovery range cannot be larger than /24")
		}
		if !discoveryRangeAllowed(prefix, allowedCIDRs) {
			return nil, errors.New("discovery range is outside the connector's allowed networks")
		}
		return []string{prefix.String()}, nil
	}

	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, fmt.Errorf("list local interfaces: %w", err)
	}
	seen := make(map[string]struct{})
	ranges := make([]string, 0, 3)
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 || ignoredDiscoveryInterface(iface.Name) {
			continue
		}
		addresses, addressErr := iface.Addrs()
		if addressErr != nil {
			continue
		}
		for _, address := range addresses {
			value := strings.Split(address.String(), "/")[0]
			ip, parseErr := netip.ParseAddr(value)
			if parseErr != nil || !ip.Is4() || !ip.IsPrivate() {
				continue
			}
			prefix := netip.PrefixFrom(ip, 24).Masked().String()
			parsedPrefix, _ := netip.ParsePrefix(prefix)
			if _, exists := seen[prefix]; exists || !discoveryRangeAllowed(parsedPrefix, allowedCIDRs) {
				continue
			}
			seen[prefix] = struct{}{}
			ranges = append(ranges, prefix)
			if len(ranges) == 2 {
				break
			}
		}
		if len(ranges) == 2 {
			break
		}
	}
	loopback := netip.MustParsePrefix("127.0.0.1/32")
	if discoveryRangeAllowed(loopback, allowedCIDRs) {
		ranges = append(ranges, loopback.String())
	}
	if len(ranges) == 0 {
		return nil, errors.New("no active private network is authorized for discovery")
	}
	return ranges, nil
}

func discoveryRangeAllowed(requested netip.Prefix, allowedCIDRs []string) bool {
	if len(allowedCIDRs) == 0 {
		return true
	}
	requested = requested.Masked()
	last := lastDiscoveryAddress(requested)
	for _, value := range allowedCIDRs {
		allowed, err := netip.ParsePrefix(strings.TrimSpace(value))
		if err != nil || !allowed.Addr().Is4() {
			continue
		}
		allowed = allowed.Masked()
		if allowed.Contains(requested.Addr()) && allowed.Contains(last) {
			return true
		}
	}
	return false
}

func lastDiscoveryAddress(prefix netip.Prefix) netip.Addr {
	bytes := prefix.Masked().Addr().As4()
	value := binary.BigEndian.Uint32(bytes[:])
	mask := uint32(0xffffffff) << (32 - prefix.Bits())
	value |= ^mask
	binary.BigEndian.PutUint32(bytes[:], value)
	return netip.AddrFrom4(bytes)
}

func ignoredDiscoveryInterface(name string) bool {
	value := strings.ToLower(strings.TrimSpace(name))
	for _, prefix := range []string{"docker", "veth", "br-", "virbr", "vmnet", "utun", "tun", "tap", "tailscale", "wg"} {
		if strings.HasPrefix(value, prefix) {
			return true
		}
	}
	return false
}

func discoveryHosts(ranges []string, limit int) ([]string, error) {
	seen := make(map[string]struct{})
	hosts := make([]string, 0, limit)
	for _, value := range ranges {
		prefix, err := netip.ParsePrefix(value)
		if err != nil {
			return nil, err
		}
		for address := prefix.Masked().Addr(); prefix.Contains(address); address = address.Next() {
			if prefix.Bits() <= 30 {
				last := address.Next()
				if address == prefix.Masked().Addr() || !prefix.Contains(last) {
					continue
				}
			}
			host := address.String()
			if _, exists := seen[host]; exists {
				continue
			}
			seen[host] = struct{}{}
			hosts = append(hosts, host)
			if len(hosts) > limit {
				return nil, errors.New("discovery range exceeds the allowed host limit")
			}
		}
	}
	return hosts, nil
}

func probeLanService(ctx context.Context, dialer *net.Dialer, target discoveryTarget) (lanDiscoveredService, bool) {
	connection, err := dialer.DialContext(ctx, "tcp4", net.JoinHostPort(target.host, strconv.Itoa(target.port)))
	if err != nil {
		return lanDiscoveredService{}, false
	}
	defer connection.Close()
	service := classifyLanPort(target.host, target.port)
	_ = connection.SetDeadline(time.Now().Add(500 * time.Millisecond))

	if isHTTPDiscoveryPort(target.port) || service.ServiceType == "http" {
		probeConnection := connection
		if isTLSDiscoveryPort(target.port) {
			tlsConnection := tls.Client(connection, &tls.Config{InsecureSkipVerify: true, ServerName: target.host}) // #nosec G402 -- discovery reads metadata only.
			if err := tlsConnection.HandshakeContext(ctx); err == nil {
				probeConnection = tlsConnection
				service.ServiceType = "https"
				service.ServiceName = "HTTPS Web 服务"
			}
		}
		if product, title, ok := probeHTTPService(probeConnection, target.host); ok {
			service.Product = product
			service.Title = title
			service.Confidence = "high"
			refineLanService(&service)
		}
	} else if target.port == 554 {
		_, _ = io.WriteString(connection, "OPTIONS rtsp://"+target.host+"/ RTSP/1.0\r\nCSeq: 1\r\n\r\n")
		service.Product = readDiscoveryBanner(connection)
		if service.Product != "" {
			service.Confidence = "high"
		}
	} else {
		service.Product = readDiscoveryBanner(connection)
		if service.Product != "" {
			service.Confidence = "high"
		}
	}
	return service, true
}

func probeHTTPService(connection net.Conn, host string) (string, string, bool) {
	_, err := io.WriteString(connection, "GET / HTTP/1.0\r\nHost: "+host+"\r\nUser-Agent: CloudNest-Discovery\r\nConnection: close\r\n\r\n")
	if err != nil {
		return "", "", false
	}
	payload, err := io.ReadAll(io.LimitReader(connection, 8192))
	if err != nil && len(payload) == 0 {
		return "", "", false
	}
	text := string(payload)
	if !strings.HasPrefix(text, "HTTP/") {
		return "", "", false
	}
	product := ""
	scanner := bufio.NewScanner(strings.NewReader(text))
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" || line == "\r" {
			break
		}
		if strings.HasPrefix(strings.ToLower(line), "server:") {
			product = cleanDiscoveryText(strings.TrimSpace(strings.TrimPrefix(line, "Server:")), 120)
		}
	}
	title := ""
	if match := htmlTitlePattern.FindStringSubmatch(text); len(match) > 1 {
		title = cleanDiscoveryText(regexp.MustCompile(`\s+`).ReplaceAllString(match[1], " "), 120)
	}
	return product, title, true
}

func readDiscoveryBanner(connection net.Conn) string {
	buffer := make([]byte, 512)
	count, _ := connection.Read(buffer)
	if count == 0 {
		return ""
	}
	return cleanDiscoveryText(string(buffer[:count]), 160)
}

func cleanDiscoveryText(value string, limit int) string {
	value = strings.Map(func(r rune) rune {
		if r == '\r' || r == '\n' || r == '\t' {
			return ' '
		}
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, value)
	value = strings.Join(strings.Fields(value), " ")
	if len(value) > limit {
		value = value[:limit]
	}
	return value
}

func classifyLanPort(host string, port int) lanDiscoveredService {
	service := lanDiscoveredService{Host: host, Port: port, Confidence: "medium"}
	switch port {
	case 21:
		service.ServiceType, service.ServiceName, service.Sensitive = "ftp", "FTP 文件服务", true
	case 22:
		service.ServiceType, service.ServiceName, service.Sensitive = "ssh", "SSH 终端", true
	case 23:
		service.ServiceType, service.ServiceName, service.Sensitive = "telnet", "Telnet 终端", true
	case 53:
		service.ServiceType, service.ServiceName = "dns", "DNS 服务"
	case 139, 445:
		service.ServiceType, service.ServiceName, service.Sensitive = "smb", "SMB 文件共享", true
	case 443, 5001, 8443:
		service.ServiceType, service.ServiceName, service.Sensitive = "https", "HTTPS Web 服务", port == 5001
	case 554:
		service.ServiceType, service.ServiceName, service.Sensitive = "rtsp", "RTSP 摄像头/视频流", true
	case 1883, 8883:
		service.ServiceType, service.ServiceName, service.Sensitive = "mqtt", "MQTT 消息服务", true
	case 3306:
		service.ServiceType, service.ServiceName, service.Sensitive = "mysql", "MySQL 数据库", true
	case 3389:
		service.ServiceType, service.ServiceName, service.Sensitive = "rdp", "Windows 远程桌面", true
	case 5432:
		service.ServiceType, service.ServiceName, service.Sensitive = "postgresql", "PostgreSQL 数据库", true
	case 8123:
		service.ServiceType, service.ServiceName = "home-assistant", "Home Assistant"
	case 32400:
		service.ServiceType, service.ServiceName = "plex", "Plex 媒体服务"
	case 5000:
		service.ServiceType, service.ServiceName, service.Sensitive = "nas", "NAS 管理服务", true
	default:
		service.ServiceType, service.ServiceName = "http", "Web 服务"
	}
	return service
}

func refineLanService(service *lanDiscoveredService) {
	text := strings.ToLower(service.Product + " " + service.Title)
	switch {
	case strings.Contains(text, "synology") || strings.Contains(text, "diskstation"):
		service.ServiceType, service.ServiceName, service.Sensitive = "synology", "群晖 NAS", true
	case strings.Contains(text, "qnap"):
		service.ServiceType, service.ServiceName, service.Sensitive = "qnap", "QNAP NAS", true
	case strings.Contains(text, "openwrt") || strings.Contains(text, "luci"):
		service.ServiceType, service.ServiceName, service.Sensitive = "router", "OpenWrt 路由器", true
	case strings.Contains(text, "home assistant"):
		service.ServiceType, service.ServiceName = "home-assistant", "Home Assistant"
	case strings.Contains(text, "plex"):
		service.ServiceType, service.ServiceName = "plex", "Plex 媒体服务"
	case strings.Contains(text, "camera") || strings.Contains(text, "hikvision") || strings.Contains(text, "dahua"):
		service.ServiceType, service.ServiceName, service.Sensitive = "camera", "摄像头管理服务", true
	}
}

func isHTTPDiscoveryPort(port int) bool {
	switch port {
	case 80, 81, 443, 3000, 5000, 5001, 8000, 8001, 8080, 8123, 8443, 9000, 9090, 32400:
		return true
	default:
		return false
	}
}

func isTLSDiscoveryPort(port int) bool {
	return port == 443 || port == 5001 || port == 8443
}
