package local

import (
	"bufio"
	"errors"
	"io"
	"net"
	"strings"

	xnet "github.com/go-gost/x/internal/net"
)

// Panel-managed options for a TCP forward:
//   - forward.block: comma separated protocols the client may not send ("socks", "http", "tls").
//   - forward.maxConns: concurrent connections allowed on this service (0 = unlimited).
//   - forward.proxyProtocol: PROXY protocol version (1 or 2) written to the target before data.

var (
	errBlockedProtocol = errors.New("blocked protocol")
	errTooManyConns    = errors.New("too many connections")
)

type blockSet struct {
	socks, http, tls bool
}

func parseBlockSet(value string) blockSet {
	var set blockSet
	for _, item := range strings.Split(strings.ToLower(value), ",") {
		switch strings.TrimSpace(item) {
		case "socks":
			set.socks = true
		case "http":
			set.http = true
		case "tls":
			set.tls = true
		}
	}
	return set
}

func (s blockSet) any() bool { return s.socks || s.http || s.tls }

// classify names the protocol a connection opens with: "socks", "http", "tls" or "".
func classify(data []byte) string {
	if len(data) == 0 {
		return ""
	}
	switch data[0] {
	case 0x04:
		if len(data) >= 2 && (data[1] == 0x01 || data[1] == 0x02) {
			return "socks"
		}
	case 0x05:
		if len(data) >= 2 && data[1] >= 1 {
			return "socks"
		}
	case 0x16:
		if len(data) >= 2 && data[1] == 0x03 {
			return "tls"
		}
	}
	// An HTTP request line starts with an upper-case method followed by a space.
	for i := 0; i < len(data) && i < 8; i++ {
		c := data[i]
		if c == ' ' {
			if i >= 3 {
				return "http"
			}
			return ""
		}
		if c < 'A' || c > 'Z' {
			return ""
		}
	}
	return ""
}

// transport relays conn and cc, closing them when the client opens with a blocked protocol.
// The target's data flows to the client from the start, so protocols in which the server
// speaks first (SSH, SMTP, MySQL) are not held back while the client's first bytes are awaited.
func (s blockSet) transport(conn, cc net.Conn) error {
	errc := make(chan error, 2)
	go func() {
		errc <- xnet.CopyBuffer(conn, cc, 32*1024)
	}()
	go func() {
		br := bufio.NewReader(conn)
		if _, err := br.Peek(1); err != nil {
			errc <- err
			return
		}
		data, _ := br.Peek(br.Buffered())
		if s.blocks(classify(data)) {
			errc <- errBlockedProtocol
			return
		}
		errc <- xnet.CopyBuffer(cc, br, 32*1024)
	}()
	if err := <-errc; err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	return nil
}

func (s blockSet) blocks(protocol string) bool {
	switch protocol {
	case "socks":
		return s.socks
	case "http":
		return s.http
	case "tls":
		return s.tls
	}
	return false
}
