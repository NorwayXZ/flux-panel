package traffic

import (
	"context"
	"github.com/go-gost/core/limiter"
	"github.com/go-gost/x/logger"
	"testing"
	"time"
)

func TestSharedBusinessesUsingSameRuleHaveIndependentRateBudgets(t *testing.T) {
	parent := NewTrafficLimiter(LimitsOption("$ 1KB 1KB"), LoggerOption(logger.Nop()))
	a, b := PerService(parent), PerService(parent)
	options := []limiter.Option{limiter.ScopeOption(limiter.ScopeService)}
	left, right := a.In(context.Background(), "$", options...), b.In(context.Background(), "$", options...)
	if left == right {
		t.Fatal("businesses share a token bucket")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if left.Wait(ctx, 1024) != 1024 {
		t.Fatal("A could not use its budget")
	}
	start := time.Now()
	if right.Wait(ctx, 1024) != 1024 {
		t.Fatal("B could not use its budget")
	}
	if time.Since(start) > 100*time.Millisecond {
		t.Fatal("A consumed B's initial rate budget")
	}
	source := parent.In(context.Background(), "$", options...)
	source.Set(2048)
	if b.In(context.Background(), "$", options...).Limit() != 2048 {
		t.Fatal("updated authorized rule did not propagate")
	}
}
