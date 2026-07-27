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
	return publicIPResponse{}, fmt.Errorf("public IP lookup failed: %s", strings.Join(failures, "; "))
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
