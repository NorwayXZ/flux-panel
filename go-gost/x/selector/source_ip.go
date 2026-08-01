package selector

import (
	"context"
	"net"
	"reflect"
	"strings"
	"time"

	"github.com/go-gost/core/selector"
	ctxvalue "github.com/go-gost/x/ctx"
)

// SourceIPRoute binds a source CIDR to a selector value. Routes are evaluated
// by longest prefix, which makes an explicit narrow override deterministic.
type SourceIPRoute[T any] struct {
	Network *net.IPNet
	Target  T
}

type sourceIPSelector[T any] struct {
	routes     []SourceIPRoute[T]
	fallback   T
	all        []T
	failFilter selector.Filter[T]
}

// NewSourceIPSelector creates a deterministic source-IP selector with a
// health-aware fallback. The first candidate is the CIDR match, followed by
// the configured default and then any remaining chains as a last resort.
func NewSourceIPSelector[T any](routes []SourceIPRoute[T], fallback T, all []T,
	maxFails int, failTimeout time.Duration) selector.Selector[T] {
	if maxFails <= 0 {
		maxFails = 1
	}
	if failTimeout <= 0 {
		failTimeout = DefaultFailTimeout
	}
	return &sourceIPSelector[T]{
		routes:     routes,
		fallback:   fallback,
		all:        all,
		failFilter: FailFilter[T](maxFails, failTimeout),
	}
}

func (s *sourceIPSelector[T]) Select(ctx context.Context, values ...T) (zero T) {
	if s == nil {
		return zero
	}
	ip := sourceIP(ctx)
	candidates := make([]T, 0, len(values)+1)
	if target, ok := s.match(ip); ok {
		candidates = appendUnique(candidates, target)
	}
	candidates = appendUnique(candidates, s.fallback)
	for _, target := range s.all {
		candidates = appendUnique(candidates, target)
	}
	if len(values) > 0 {
		available := make([]T, 0, len(candidates))
		for _, candidate := range candidates {
			for _, value := range values {
				if sameValue(candidate, value) {
					available = appendUnique(available, value)
					break
				}
			}
		}
		candidates = available
	}
	if len(candidates) == 0 {
		return zero
	}
	if healthy := s.failFilter.Filter(ctx, candidates...); len(healthy) > 0 {
		return healthy[0]
	}
	return candidates[0]
}

func (s *sourceIPSelector[T]) match(ip net.IP) (T, bool) {
	var zero T
	if ip == nil {
		return zero, false
	}
	bestBits := -1
	var best T
	found := false
	for _, route := range s.routes {
		if route.Network == nil || !route.Network.Contains(ip) {
			continue
		}
		ones, _ := route.Network.Mask.Size()
		if !found || ones > bestBits {
			bestBits = ones
			best = route.Target
			found = true
		}
	}
	return best, found
}

func sourceIP(ctx context.Context) net.IP {
	if hash := ctxvalue.HashFromContext(ctx); hash != nil {
		if ip := parseIP(hash.Source); ip != nil {
			return ip
		}
	}
	if client := ctxvalue.ClientAddrFromContext(ctx); client != "" {
		return parseIP(string(client))
	}
	return nil
}

func parseIP(value string) net.IP {
	value = strings.TrimSpace(value)
	if host, _, err := net.SplitHostPort(value); err == nil {
		value = host
	}
	value = strings.Trim(value, "[]")
	return net.ParseIP(value)
}

func appendUnique[T any](values []T, candidate T) []T {
	for _, value := range values {
		if sameValue(value, candidate) {
			return values
		}
	}
	return append(values, candidate)
}

func sameValue(a, b any) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	ta, tb := reflect.TypeOf(a), reflect.TypeOf(b)
	return ta == tb && ta.Comparable() && reflect.ValueOf(a).Interface() == reflect.ValueOf(b).Interface()
}
