package socket

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

// realityTargetProbeRequest lists sites to check as a REALITY (or ShadowTLS)
// camouflage target from this node.
type realityTargetProbeRequest struct {
	Domains []string `json:"domains"`
}

// realityTargetResult says whether a site can serve as a camouflage target
// and how long its TLS handshake takes from this node. Every REALITY
// connection repeats that handshake, so a slow target slows every connection.
type realityTargetResult struct {
	Domain    string `json:"domain"`
	OK        bool   `json:"ok"`
	LatencyMs int64  `json:"latencyMs,omitempty"`
	Address   string `json:"address,omitempty"`
	H2        bool   `json:"h2"`
	Error     string `json:"error,omitempty"`
}

const realityTargetTimeout = 5 * time.Second

func probeRealityTargets(request realityTargetProbeRequest) ([]realityTargetResult, error) {
	domains := make([]string, 0, len(request.Domains))
	seen := map[string]bool{}
	for _, domain := range request.Domains {
		domain = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(domain), "."))
		if domain == "" || seen[domain] {
			continue
		}
		if !serverNamePattern.MatchString(domain) {
			return nil, fmt.Errorf("invalid site %q", domain)
		}
		seen[domain] = true
		domains = append(domains, domain)
	}
	if len(domains) == 0 || len(domains) > 40 {
		return nil, errors.New("probe needs 1 to 40 sites")
	}
	results := make([]realityTargetResult, len(domains))
	slots := make(chan struct{}, 8)
	var wg sync.WaitGroup
	for i, domain := range domains {
		wg.Add(1)
		go func(i int, domain string) {
			defer wg.Done()
			slots <- struct{}{}
			defer func() { <-slots }()
			results[i] = probeRealityTarget(domain)
		}(i, domain)
	}
	wg.Wait()
	sort.SliceStable(results, func(a, b int) bool {
		if results[a].OK != results[b].OK {
			return results[a].OK
		}
		return results[a].OK && results[a].LatencyMs < results[b].LatencyMs
	})
	return results, nil
}

// probeRealityTarget handshakes twice and keeps the faster one, so a single
// slow DNS lookup or retransmission does not decide the ranking.
func probeRealityTarget(domain string) realityTargetResult {
	result := realityTargetResult{Domain: domain}
	for attempt := 0; attempt < 2; attempt++ {
		latency, address, h2, err := realityHandshake(domain)
		if err != nil {
			if !result.OK {
				result.Error = err.Error()
			}
			continue
		}
		if !result.OK || latency < result.LatencyMs {
			result.OK, result.LatencyMs, result.Address, result.H2, result.Error = true, latency, address, h2, ""
		}
	}
	return result
}

// realityHandshake checks what REALITY needs from a target: it answers on
// port 443 with TLS 1.3 using X25519, speaks HTTP/2, and presents a
// certificate for the name itself.
func realityHandshake(domain string) (int64, string, bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), realityTargetTimeout)
	defer cancel()
	addresses, err := net.DefaultResolver.LookupIPAddr(ctx, domain)
	if err != nil || len(addresses) == 0 {
		return 0, "", false, errors.New("DNS lookup failed")
	}
	address := addresses[0].IP.String()
	for _, candidate := range addresses {
		if candidate.IP.To4() != nil {
			address = candidate.IP.String()
			break
		}
	}
	return realityHandshakeAt(ctx, domain, address, "443")
}

func realityHandshakeAt(ctx context.Context, domain, address, port string) (int64, string, bool, error) {
	started := time.Now()
	dialer := &net.Dialer{Timeout: realityTargetTimeout}
	raw, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(address, port))
	if err != nil {
		return 0, address, false, simplifyDialError(err)
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(realityTargetTimeout))
	conn := tls.Client(raw, &tls.Config{
		ServerName:         domain,
		MinVersion:         tls.VersionTLS13,
		CurvePreferences:   []tls.CurveID{tls.X25519},
		NextProtos:         []string{"h2", "http/1.1"},
		InsecureSkipVerify: true, // the name check below is what matters here
	})
	if err := conn.HandshakeContext(ctx); err != nil {
		message := err.Error()
		switch {
		case strings.Contains(message, "protocol version"):
			return 0, address, false, errors.New("does not support TLS 1.3")
		case strings.Contains(message, "handshake failure"):
			return 0, address, false, errors.New("TLS 1.3 with X25519 refused")
		}
		return 0, address, false, fmt.Errorf("TLS handshake failed: %s", simplifyDialError(err))
	}
	latency := time.Since(started).Milliseconds()
	state := conn.ConnectionState()
	if len(state.PeerCertificates) == 0 || state.PeerCertificates[0].VerifyHostname(domain) != nil {
		return 0, address, false, errors.New("certificate does not match the name")
	}
	if state.NegotiatedProtocol != "h2" {
		return 0, address, false, errors.New("does not support HTTP/2")
	}
	return latency, address, true, nil
}

func simplifyDialError(err error) error {
	message := err.Error()
	switch {
	case strings.Contains(message, "i/o timeout"), strings.Contains(message, "deadline exceeded"):
		return errors.New("timeout")
	case strings.Contains(message, "connection refused"):
		return errors.New("connection refused")
	case strings.Contains(message, "connection reset"):
		return errors.New("connection reset")
	case strings.Contains(message, "network is unreachable"), strings.Contains(message, "no route"):
		return errors.New("unreachable")
	}
	return err
}
