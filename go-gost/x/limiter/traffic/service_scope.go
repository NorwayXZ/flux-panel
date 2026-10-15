package traffic

import (
	"context"
	"github.com/go-gost/core/limiter"
	coretraffic "github.com/go-gost/core/limiter/traffic"
	"sync"
)

// PerService copies the configured rate, not the token bucket, for a shared
// tunnel's business listener. Reusing the same authorized rule must not make A
// consume B's rate allowance. The live parent still supplies updated limits.
func PerService(parent coretraffic.TrafficLimiter) coretraffic.TrafficLimiter {
	if parent == nil {
		return nil
	}
	return &serviceScopeLimiter{parent: parent}
}

type serviceScopeLimiter struct {
	mu      sync.Mutex
	parent  coretraffic.TrafficLimiter
	in, out coretraffic.Limiter
}

func (l *serviceScopeLimiter) In(ctx context.Context, key string, opts ...limiter.Option) coretraffic.Limiter {
	return l.copy(ctx, key, true, opts...)
}
func (l *serviceScopeLimiter) Out(ctx context.Context, key string, opts ...limiter.Option) coretraffic.Limiter {
	return l.copy(ctx, key, false, opts...)
}
func (l *serviceScopeLimiter) copy(ctx context.Context, key string, input bool, opts ...limiter.Option) coretraffic.Limiter {
	var options limiter.Options
	for _, option := range opts {
		option(&options)
	}
	var source coretraffic.Limiter
	if input {
		source = l.parent.In(ctx, key, opts...)
	} else {
		source = l.parent.Out(ctx, key, opts...)
	}
	if options.Scope != limiter.ScopeService {
		return source
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	value := &l.out
	if input {
		value = &l.in
	}
	if source == nil || source.Limit() <= 0 {
		*value = nil
		return nil
	}
	if *value == nil {
		*value = NewLimiter(source.Limit())
	} else if (*value).Limit() != source.Limit() {
		(*value).Set(source.Limit())
	}
	return *value
}
