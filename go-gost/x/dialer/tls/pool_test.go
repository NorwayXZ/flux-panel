package tls

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-gost/core/dialer"
	"github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
	xmetadata "github.com/go-gost/x/metadata"
)

type countingDialer struct{ dials atomic.Int64 }

func (d *countingDialer) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	d.dials.Add(1)
	return (&net.Dialer{}).DialContext(ctx, network, addr)
}

// startTLSEcho is a TLS server that echoes, like a relay exit that accepted the connection.
func startTLSEcho(t *testing.T) string {
	t.Helper()
	// Borrow httptest's self-signed certificate.
	ts := httptest.NewTLSServer(nil)
	config := ts.TLS.Clone()
	ts.Close()
	ln, err := tls.Listen("tcp", "127.0.0.1:0", config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	return ln.Addr().String()
}

func newPoolDialer(t *testing.T, size int) *tlsDialer {
	t.Helper()
	d := NewDialer(
		dialer.LoggerOption(xlogger.Nop()),
		dialer.TLSConfigOption(&tls.Config{InsecureSkipVerify: true}), // test server uses a self-signed certificate
	).(*tlsDialer)
	if err := d.Init(xmetadata.NewMetadata(map[string]any{"pool.size": size})); err != nil {
		t.Fatal(err)
	}
	return d
}

func dialAndHandshake(t *testing.T, d *tlsDialer, netd *countingDialer, addr string) net.Conn {
	t.Helper()
	ctx := context.Background()
	raw, err := d.Dial(ctx, addr, dialer.NetDialerDialOption(netd))
	if err != nil {
		t.Fatal(err)
	}
	conn, err := d.Handshake(ctx, raw)
	if err != nil {
		t.Fatal(err)
	}
	return conn
}

func echoOnce(t *testing.T, conn net.Conn, payload string) {
	t.Helper()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := conn.Write([]byte(payload)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != payload {
		t.Fatalf("echo %q: %q %v", payload, buf, err)
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestPoolServesHandshakenConnections(t *testing.T) {
	logger.SetDefault(xlogger.Nop())
	addr := startTLSEcho(t)
	d := newPoolDialer(t, 2)
	netd := &countingDialer{}

	first := dialAndHandshake(t, d, netd, addr)
	defer first.Close()
	echoOnce(t, first, "first")

	pool := d.pool(addr)
	waitFor(t, "the pool to fill", func() bool { return pool.idleCount() == 2 })

	raw, err := d.Dial(context.Background(), addr, dialer.NetDialerDialOption(netd))
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := raw.(*pooledConn); !ok {
		t.Fatalf("second connection was not served from the pool: %T", raw)
	}
	second, err := d.Handshake(context.Background(), raw)
	if err != nil {
		t.Fatal(err)
	}
	defer second.Close()
	echoOnce(t, second, "second")
	waitFor(t, "the pool to refill", func() bool { return pool.idleCount() == 2 })
}

func TestPoolStopsWhenUnusedAndReplacesStaleEntries(t *testing.T) {
	logger.SetDefault(xlogger.Nop())
	addr := startTLSEcho(t)
	d := newPoolDialer(t, 1)
	pool := d.pool(addr)
	pool.maxIdle, pool.window = 300*time.Millisecond, time.Second
	netd := &countingDialer{}
	conn := dialAndHandshake(t, d, netd, addr)
	conn.Close()

	waitFor(t, "the pool to fill", func() bool { return pool.idleCount() == 1 })
	// Entries older than poolMaxIdle are replaced while the pool is active.
	before := netd.dials.Load()
	waitFor(t, "a stale entry to be replaced", func() bool { return netd.dials.Load() > before })

	// After poolActiveWindow without dials the pool drains and stops dialing.
	waitFor(t, "the pool to stop", func() bool { return !pool.running() && pool.idleCount() == 0 })
	time.Sleep(200 * time.Millisecond)
	settled := netd.dials.Load()
	time.Sleep(800 * time.Millisecond)
	if netd.dials.Load() != settled {
		t.Fatal("an unused pool kept dialing")
	}
}

func TestPoolOffDialsEveryTime(t *testing.T) {
	logger.SetDefault(xlogger.Nop())
	addr := startTLSEcho(t)
	d := newPoolDialer(t, 0)
	netd := &countingDialer{}
	for i := 0; i < 3; i++ {
		conn := dialAndHandshake(t, d, netd, addr)
		echoOnce(t, conn, "x")
		conn.Close()
	}
	time.Sleep(200 * time.Millisecond)
	if got := netd.dials.Load(); got != 3 {
		t.Fatalf("dials without a pool = %d, want 3", got)
	}
}
