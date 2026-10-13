package dialer

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
)

// Public resolvers queried through the exit device.
var exitResolvers = []string{"1.1.1.1:53", "8.8.8.8:53"}

const (
	exitDNSCacheTTL  = 60 * time.Second
	exitDNSCacheSize = 2048
	exitDNSMaxAddrs  = 3
)

type exitDNSEntry struct {
	ips     []net.IP
	expires time.Time
}

var exitDNSCache = struct {
	sync.Mutex
	entries map[string]exitDNSEntry
}{entries: make(map[string]exitDNSEntry)}

// exitDNSExchange sends one query through the device. Tests replace it.
var exitDNSExchange = func(ctx context.Context, query *dns.Msg, network, server, device string, mark int) (*dns.Msg, error) {
	conn, err := dialDeviceAddr(ctx, network, server, device, mark)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	deadline := time.Now().Add(4 * time.Second)
	if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
		deadline = limit
	}
	_ = conn.SetDeadline(deadline)
	client := &dns.Client{Net: network}
	reply, _, err := client.ExchangeWithConn(query, &dns.Conn{Conn: conn})
	return reply, err
}

// resolveThroughDevice looks up the IPv4 addresses of a host with DNS queries
// that leave through the device, never through the node's own resolver.
func resolveThroughDevice(ctx context.Context, host, device string, mark int) ([]net.IP, error) {
	name := strings.ToLower(strings.TrimSuffix(host, "."))
	key := device + "/" + name
	now := time.Now()
	exitDNSCache.Lock()
	entry, cached := exitDNSCache.entries[key]
	exitDNSCache.Unlock()
	if cached && now.Before(entry.expires) {
		return entry.ips, nil
	}

	query := new(dns.Msg)
	query.SetQuestion(dns.Fqdn(name), dns.TypeA)
	var lastErr error
	for _, server := range exitResolvers {
		reply, err := exitDNSExchange(ctx, query, "udp", server, device, mark)
		if err == nil && reply != nil && reply.Truncated {
			reply, err = exitDNSExchange(ctx, query, "tcp", server, device, mark)
		}
		if err != nil {
			lastErr = err
			if ctx.Err() != nil {
				break
			}
			continue
		}
		if reply == nil || reply.Rcode != dns.RcodeSuccess {
			lastErr = fmt.Errorf("resolve %s through the exit: %s", name, dns.RcodeToString[replyCode(reply)])
			if reply != nil && reply.Rcode == dns.RcodeNameError {
				break
			}
			continue
		}
		var ips []net.IP
		for _, answer := range reply.Answer {
			if record, ok := answer.(*dns.A); ok && len(ips) < exitDNSMaxAddrs {
				ips = append(ips, record.A)
			}
		}
		if len(ips) == 0 {
			lastErr = fmt.Errorf("resolve %s through the exit: no IPv4 address", name)
			break
		}
		exitDNSCache.Lock()
		if len(exitDNSCache.entries) >= exitDNSCacheSize {
			exitDNSCache.entries = make(map[string]exitDNSEntry)
		}
		exitDNSCache.entries[key] = exitDNSEntry{ips: ips, expires: now.Add(exitDNSCacheTTL)}
		exitDNSCache.Unlock()
		return ips, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("resolve %s through the exit failed", name)
	}
	return nil, lastErr
}

func replyCode(reply *dns.Msg) int {
	if reply == nil {
		return dns.RcodeServerFailure
	}
	return reply.Rcode
}
