package tls

import (
	"time"

	mdata "github.com/go-gost/core/metadata"
	mdutil "github.com/go-gost/x/metadata/util"
)

type metadata struct {
	handshakeTimeout time.Duration
	poolSize         int
}

func (d *tlsDialer) parseMetadata(md mdata.Metadata) (err error) {
	const (
		handshakeTimeout = "handshakeTimeout"
	)

	d.md.handshakeTimeout = mdutil.GetDuration(md, handshakeTimeout)
	// Pre-handshaken connections kept ready while the tunnel carries traffic (0 = off).
	d.md.poolSize = min(max(mdutil.GetInt(md, "pool.size"), 0), 8)

	return
}
