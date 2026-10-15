package tls

import (
	"context"
	"crypto/tls"
	"sync"
	"time"

	xnet "github.com/go-gost/core/common/net"
)

// The pool keeps a few TLS connections to the next hop already handshaken, so a new client
// connection skips the TCP and TLS round trips. The exit's relay handler drops a connection that
// sends no request within its read timeout (15s by default), so idle entries are replaced
// before poolMaxIdle. The pool only refills while it is in use: once nothing has been dialed for
// poolActiveWindow, it lets its entries expire and stops connecting.
var (
	poolMaxIdle      = 10 * time.Second
	poolActiveWindow = time.Minute
)

type pooledConn struct {
	*tls.Conn
}

type poolEntry struct {
	conn    *tls.Conn
	created time.Time
}

type connPool struct {
	mu       sync.Mutex
	size     int
	addr     string
	idle     []poolEntry
	lastUsed time.Time
	filling  bool
	netd     xnet.Dialer
	config   *tls.Config
	timeout  time.Duration
	maxIdle  time.Duration
	window   time.Duration
	wake     chan struct{}
}

func newConnPool(size int, addr string, config *tls.Config, timeout time.Duration) *connPool {
	return &connPool{
		size:    size,
		addr:    addr,
		config:  config,
		timeout: timeout,
		maxIdle: poolMaxIdle,
		window:  poolActiveWindow,
		wake:    make(chan struct{}, 1),
	}
}

// take returns a fresh pooled connection, or nil when none is ready.
func (p *connPool) take() *tls.Conn {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.lastUsed = time.Now()
	for len(p.idle) > 0 {
		entry := p.idle[0]
		p.idle = p.idle[1:]
		if time.Since(entry.created) < p.maxIdle {
			return entry.conn
		}
		entry.conn.Close()
	}
	return nil
}

// refill tops the pool up in the background with the dialer of the latest Dial.
func (p *connPool) refill(netd xnet.Dialer) {
	p.mu.Lock()
	if netd != nil {
		p.netd = netd
	}
	if p.filling {
		p.mu.Unlock()
		select {
		case p.wake <- struct{}{}:
		default:
		}
		return
	}
	if p.netd == nil {
		p.mu.Unlock()
		return
	}
	p.filling = true
	p.mu.Unlock()
	go p.fill()
}

func (p *connPool) fill() {
	defer func() {
		p.mu.Lock()
		p.filling = false
		p.mu.Unlock()
	}()
	for {
		p.mu.Lock()
		p.expireLocked()
		active := time.Since(p.lastUsed) < p.window
		missing := p.size - len(p.idle)
		netd := p.netd
		var wait time.Duration
		if len(p.idle) > 0 {
			wait = p.maxIdle - time.Since(p.idle[0].created)
		}
		p.mu.Unlock()
		if !active {
			p.drain()
			return
		}
		if missing <= 0 {
			// Sleep until the oldest entry expires, or until an entry is taken.
			timer := time.NewTimer(max(wait, 100*time.Millisecond))
			select {
			case <-timer.C:
			case <-p.wake:
				timer.Stop()
			}
			continue
		}
		conn, err := p.dial(netd)
		if err != nil {
			time.Sleep(time.Second)
			continue
		}
		p.mu.Lock()
		p.idle = append(p.idle, poolEntry{conn: conn, created: time.Now()})
		p.mu.Unlock()
	}
}

func (p *connPool) dial(netd xnet.Dialer) (*tls.Conn, error) {
	timeout := p.timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	raw, err := netd.Dial(ctx, "tcp", p.addr)
	if err != nil {
		return nil, err
	}
	conn := tls.Client(raw, p.config)
	if err := conn.HandshakeContext(ctx); err != nil {
		raw.Close()
		return nil, err
	}
	return conn, nil
}

// expireLocked closes entries that are too old to be accepted by the exit. Callers hold p.mu.
func (p *connPool) expireLocked() {
	kept := p.idle[:0]
	for _, entry := range p.idle {
		if time.Since(entry.created) < p.maxIdle {
			kept = append(kept, entry)
		} else {
			entry.conn.Close()
		}
	}
	p.idle = kept
}

func (p *connPool) drain() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, entry := range p.idle {
		entry.conn.Close()
	}
	p.idle = nil
}

func (p *connPool) idleCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.idle)
}

func (p *connPool) running() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.filling
}
