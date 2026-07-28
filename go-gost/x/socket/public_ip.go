package socket

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

type publicIPRequest struct {
	Family string `json:"family"`
}

type publicIPResponse struct {
	Family  string `json:"family"`
	Address string `json:"address"`
	Source  string `json:"source"`
}

func (w *WebSocketReporter) handlePublicIPQuery(data interface{}) (publicIPResponse, error) {
	req := publicIPRequest{Family: "ipv4"}
	if payload, ok := data.(map[string]interface{}); ok {
		if value, exists := payload["family"].(string); exists {
			req.Family = strings.ToLower(strings.TrimSpace(value))
		}
	}
	if req.Family != "ipv4" && req.Family != "ipv6" {
		return publicIPResponse{}, errors.New("family must be ipv4 or ipv6")
	}
	endpoints := []string{"https://api4.ipify.org", "https://ipv4.icanhazip.com"}
	if req.Family == "ipv6" {
		endpoints = []string{"https://api6.ipify.org", "https://ipv6.icanhazip.com"}
	}
	var failures []string
	for _, endpoint := range endpoints {
		address, err := fetchPublicIP(endpoint, req.Family)
		if err == nil {
			return publicIPResponse{Family: req.Family, Address: address, Source: endpoint}, nil
		}
		failures = append(failures, err.Error())
	}
	if req.Family == "ipv6" {
		address, source, err := discoverLocalPublicIPv6()
		if err == nil {
			return publicIPResponse{Family: req.Family, Address: address, Source: source}, nil
		}
		failures = append(failures, err.Error())
	}
	return publicIPResponse{}, fmt.Errorf("public IP lookup failed: %s", strings.Join(failures, "; "))
}

func discoverLocalPublicIPv6() (string, string, error) {
	// A UDP dial to a literal IPv6 address asks the kernel which source address
	// it would use without depending on DNS or sending application traffic.
	for _, target := range []string{"[2606:4700:4700::1111]:53", "[2001:4860:4860::8888]:53"} {
		conn, err := net.DialTimeout("udp6", target, 2*time.Second)
		if err != nil {
			continue
		}
		address := conn.LocalAddr()
		_ = conn.Close()
		if udpAddress, ok := address.(*net.UDPAddr); ok && isUsablePublicIPv6(udpAddress.IP) {
			return udpAddress.IP.String(), "local-ipv6-route", nil
		}
	}

	interfaces, err := net.Interfaces()
	if err != nil {
		return "", "", fmt.Errorf("local IPv6 interface lookup failed: %w", err)
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			var ip net.IP
			switch value := address.(type) {
			case *net.IPNet:
				ip = value.IP
			case *net.IPAddr:
				ip = value.IP
			}
			if isUsablePublicIPv6(ip) {
				return ip.String(), "local-interface:" + iface.Name, nil
			}
		}
	}
	return "", "", errors.New("no globally routable IPv6 address found on local interfaces")
}

func isUsablePublicIPv6(ip net.IP) bool {
	return ip != nil && ip.To4() == nil && ip.IsGlobalUnicast() && !ip.IsPrivate()
}

func fetchPublicIP(endpoint, family string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	dialer := &net.Dialer{Timeout: 4 * time.Second}
	network := "tcp4"
	if family == "ipv6" {
		network = "tcp6"
	}
	client := &http.Client{
		Timeout: 6 * time.Second,
		Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment,
			DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
				return dialer.DialContext(ctx, network, address)
			},
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return "", err
	}
	response, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("%s returned HTTP %d", endpoint, response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 256))
	if err != nil {
		return "", err
	}
	value := strings.TrimSpace(string(body))
	ip := net.ParseIP(value)
	if ip == nil || (family == "ipv4" && ip.To4() == nil) || (family == "ipv6" && ip.To4() != nil) {
		return "", fmt.Errorf("%s returned an invalid %s address", endpoint, family)
	}
	return ip.String(), nil
}
