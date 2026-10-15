package tls

import (
	"context"
	"crypto/tls"
	"net"
	"sync"
	"time"

	"github.com/go-gost/core/dialer"
	"github.com/go-gost/core/logger"
	md "github.com/go-gost/core/metadata"
	"github.com/go-gost/x/registry"
)

func init() {
	registry.DialerRegistry().Register("tls", NewDialer)
}

type tlsDialer struct {
	md      metadata
	logger  logger.Logger
	options dialer.Options

	poolsMu sync.Mutex
	pools   map[string]*connPool
}

func NewDialer(opts ...dialer.Option) dialer.Dialer {
	options := dialer.Options{}
	for _, opt := range opts {
		opt(&options)
	}

	return &tlsDialer{
		logger:  options.Logger,
		options: options,
	}
}

func (d *tlsDialer) Init(md md.Metadata) (err error) {
	return d.parseMetadata(md)
}

func (d *tlsDialer) Dial(ctx context.Context, addr string, opts ...dialer.DialOption) (net.Conn, error) {
	var options dialer.DialOptions
	for _, opt := range opts {
		opt(&options)
	}

	if d.md.poolSize > 0 && options.Dialer != nil {
		pool := d.pool(addr)
		conn := pool.take()
		pool.refill(options.Dialer)
		if conn != nil {
			return &pooledConn{Conn: conn}, nil
		}
	}

	conn, err := options.Dialer.Dial(ctx, "tcp", addr)
	if err != nil {
		d.logger.Error(err)
	}
	return conn, err
}

func (d *tlsDialer) pool(addr string) *connPool {
	d.poolsMu.Lock()
	defer d.poolsMu.Unlock()
	if d.pools == nil {
		d.pools = make(map[string]*connPool)
	}
	pool := d.pools[addr]
	if pool == nil {
		pool = newConnPool(d.md.poolSize, addr, d.options.TLSConfig, d.md.handshakeTimeout)
		d.pools[addr] = pool
	}
	return pool
}

// Handshake implements dialer.Handshaker
func (d *tlsDialer) Handshake(ctx context.Context, conn net.Conn, options ...dialer.HandshakeOption) (net.Conn, error) {
	if pooled, ok := conn.(*pooledConn); ok {
		// Already handshaken by the pool.
		return pooled.Conn, nil
	}
	if d.md.handshakeTimeout > 0 {
		conn.SetDeadline(time.Now().Add(d.md.handshakeTimeout))
		defer conn.SetDeadline(time.Time{})
	}

	tlsConn := tls.Client(conn, d.options.TLSConfig)
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		conn.Close()
		return nil, err
	}

	return tlsConn, nil
}
