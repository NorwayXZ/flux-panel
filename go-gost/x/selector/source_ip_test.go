package selector

import (
	"context"
	"net"
	"testing"
	"time"

	coreselector "github.com/go-gost/core/selector"
	ctxvalue "github.com/go-gost/x/ctx"
)

func TestSourceIPSelectorUsesLongestPrefixAndFallback(t *testing.T) {
	_, telecom, _ := net.ParseCIDR("203.0.113.0/24")
	_, narrow, _ := net.ParseCIDR("203.0.113.128/25")
	selector := NewSourceIPSelector([]SourceIPRoute[string]{
		{Network: telecom, Target: "telecom"},
		{Network: narrow, Target: "unicom"},
	}, "default", []string{"default", "telecom", "unicom"}, 1, time.Second)

	ctx := ctxvalue.ContextWithHash(context.Background(), &ctxvalue.Hash{Source: "203.0.113.200"})
	if got := selector.Select(ctx); got != "unicom" {
		t.Fatalf("expected narrow source route, got %q", got)
	}

	ctx = ctxvalue.ContextWithHash(context.Background(), &ctxvalue.Hash{Source: "198.51.100.8"})
	if got := selector.Select(ctx); got != "default" {
		t.Fatalf("expected default fallback, got %q", got)
	}
}

func TestSourceIPSelectorFallsBackWhenMatchedChainFails(t *testing.T) {
	_, network, _ := net.ParseCIDR("198.51.100.0/24")
	matched := &testMarkable{value: "telecom", marker: coreselector.NewFailMarker()}
	fallback := &testMarkable{value: "default", marker: coreselector.NewFailMarker()}
	matched.marker.Mark()
	selector := NewSourceIPSelector([]SourceIPRoute[*testMarkable]{
		{Network: network, Target: matched},
	}, fallback, []*testMarkable{fallback, matched}, 1, time.Hour)
	ctx := ctxvalue.ContextWithHash(context.Background(), &ctxvalue.Hash{Source: "198.51.100.7"})
	if got := selector.Select(ctx); got != fallback {
		t.Fatalf("expected failed matched route to use fallback, got %v", got)
	}
}

func TestSourceIPSelectorReadsClientAddressAndSupportsIPv6(t *testing.T) {
	_, network, err := net.ParseCIDR("2408:8000::/20")
	if err != nil {
		t.Fatal(err)
	}
	selector := NewSourceIPSelector([]SourceIPRoute[string]{
		{Network: network, Target: "mobile"},
	}, "default", []string{"default", "mobile"}, 1, time.Second)

	ctx := ctxvalue.ContextWithClientAddr(context.Background(), ctxvalue.ClientAddr("[2408:8256:3500::10]:443"))
	if got := selector.Select(ctx); got != "mobile" {
		t.Fatalf("expected IPv6 client address route, got %q", got)
	}

	ctx = ctxvalue.ContextWithClientAddr(context.Background(), ctxvalue.ClientAddr("198.51.100.5:443"))
	if got := selector.Select(ctx); got != "default" {
		t.Fatalf("expected unmatched client address to use default, got %q", got)
	}
}

type testMarkable struct {
	value  string
	marker coreselector.Marker
}

func (m *testMarkable) Marker() coreselector.Marker {
	return m.marker
}
