package dialer

import (
	"context"
	"errors"
	"net"
	"testing"

	"github.com/miekg/dns"
)

func stubExitDNS(t *testing.T, handler func(network, server string, query *dns.Msg) (*dns.Msg, error)) *int {
	t.Helper()
	calls := 0
	previous := exitDNSExchange
	exitDNSExchange = func(_ context.Context, query *dns.Msg, network, server, _ string, _ int) (*dns.Msg, error) {
		calls++
		return handler(network, server, query)
	}
	exitDNSCache.Lock()
	exitDNSCache.entries = make(map[string]exitDNSEntry)
	exitDNSCache.Unlock()
	t.Cleanup(func() { exitDNSExchange = previous })
	return &calls
}

func answer(query *dns.Msg, addrs ...string) *dns.Msg {
	reply := new(dns.Msg)
	reply.SetReply(query)
	for _, addr := range addrs {
		reply.Answer = append(reply.Answer, &dns.A{
			Hdr: dns.RR_Header{Name: query.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 30},
			A:   net.ParseIP(addr),
		})
	}
	return reply
}

func TestExitNamesAreResolvedThroughTheExitAndCached(t *testing.T) {
	calls := stubExitDNS(t, func(network, server string, query *dns.Msg) (*dns.Msg, error) {
		if network != "udp" || server != exitResolvers[0] || query.Question[0].Name != "www.example.com." {
			t.Fatalf("unexpected query %s %s %v", network, server, query.Question)
		}
		return answer(query, "142.250.1.1", "142.250.1.2"), nil
	})
	ips, err := resolveThroughDevice(context.Background(), "WWW.Example.com", "cnx0000000000", 0)
	if err != nil || len(ips) != 2 || ips[0].String() != "142.250.1.1" {
		t.Fatalf("unexpected result %v %v", ips, err)
	}
	if _, err := resolveThroughDevice(context.Background(), "www.example.com", "cnx0000000000", 0); err != nil || *calls != 1 {
		t.Fatalf("expected the second lookup to come from the cache, calls=%d err=%v", *calls, err)
	}
	// Another exit may be in another country: it must ask again.
	if _, err := resolveThroughDevice(context.Background(), "www.example.com", "cnx1111111111", 0); err != nil || *calls != 2 {
		t.Fatalf("expected a separate lookup per exit, calls=%d err=%v", *calls, err)
	}
}

func TestExitResolutionFallsBackAndFailsWithoutAnAnswer(t *testing.T) {
	stubExitDNS(t, func(_, server string, query *dns.Msg) (*dns.Msg, error) {
		if server == exitResolvers[0] {
			return nil, errors.New("timeout")
		}
		return answer(query, "93.184.216.34"), nil
	})
	if ips, err := resolveThroughDevice(context.Background(), "example.org", "cnx0000000000", 0); err != nil || ips[0].String() != "93.184.216.34" {
		t.Fatalf("expected the second resolver to answer, got %v %v", ips, err)
	}

	stubExitDNS(t, func(_, _ string, query *dns.Msg) (*dns.Msg, error) {
		reply := new(dns.Msg)
		reply.SetRcode(query, dns.RcodeNameError)
		return reply, nil
	})
	if _, err := resolveThroughDevice(context.Background(), "missing.example", "cnx0000000000", 0); err == nil {
		t.Fatal("a name that does not exist must fail, not fall back to the node's resolver")
	}

	stubExitDNS(t, func(_, _ string, _ *dns.Msg) (*dns.Msg, error) { return nil, errors.New("exit is down") })
	if _, err := DialDevice(context.Background(), "tcp", "www.example.com:443", "cnxmissing0000", 0); err == nil {
		t.Fatal("dialling a host name must fail when it cannot be resolved through the exit")
	}
}
