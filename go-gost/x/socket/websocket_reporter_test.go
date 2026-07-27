package socket

import (
	"testing"
	"time"
)

func TestReconnectDelayUsesFastStartupWindow(t *testing.T) {
	now := time.Unix(100, 0)
	fast := reconnectDelay(now, now.Add(time.Second), 750*time.Millisecond)
	if fast < time.Second || fast >= 1500*time.Millisecond {
		t.Fatalf("fast reconnect delay is outside the expected range: %v", fast)
	}
	normal := reconnectDelay(now, now.Add(-time.Second), 1500*time.Millisecond)
	if normal < 5*time.Second || normal >= 6*time.Second {
		t.Fatalf("normal reconnect delay is outside the expected range: %v", normal)
	}
}
