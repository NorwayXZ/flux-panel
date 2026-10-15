package local_test

import (
	"io"
	"net"
	"testing"
	"time"

	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing"
	chain_parser "github.com/go-gost/x/config/parsing/chain"
	service_parser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/relay"
	_ "github.com/go-gost/x/dialer/quic"
	_ "github.com/go-gost/x/handler/relay"
	_ "github.com/go-gost/x/listener/quic"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

// The panel builds a QUIC tunnel as: entry TCP forward -> chain (relay over quic) -> exit relay
// service listening on quic with a forwarder to the landing. This runs that exact shape.
func TestTCPForwardThroughQUICRelayTunnel(t *testing.T) {
	corelogger.SetDefault(xlogger.Nop())
	// The Agent installs a default certificate at startup; QUIC listeners use it.
	tlsCfg, err := parsing.BuildDefaultTLSConfig(nil)
	if err != nil {
		t.Fatal(err)
	}
	parsing.SetDefaultTLSConfig(tlsCfg)
	echo := startEcho(t)
	defer echo.Close()

	probe, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	exitAddr := probe.LocalAddr().String()
	probe.Close()

	exit, err := service_parser.ParseService(&config.ServiceConfig{
		Name:     "quic-exit",
		Addr:     exitAddr,
		Handler:  &config.HandlerConfig{Type: "relay"},
		Listener: &config.ListenerConfig{Type: "quic"},
		Forwarder: &config.ForwarderConfig{
			Nodes: []*config.ForwardNodeConfig{{Name: "landing", Addr: echo.Addr().String()}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	go exit.Serve()
	defer exit.Close()

	chain, err := chain_parser.ParseChain(&config.ChainConfig{
		Name: "quic-test_chains",
		Hops: []*config.HopConfig{{
			Name: "quic-test_hop",
			Nodes: []*config.NodeConfig{{
				Name:      "exit",
				Addr:      exitAddr,
				Connector: &config.ConnectorConfig{Type: "relay"},
				Dialer: &config.DialerConfig{
					Type:     "quic",
					Metadata: map[string]any{"keepAlive": true, "ttl": "10s"},
				},
			}},
		}},
	}, xlogger.Nop())
	if err != nil {
		t.Fatal(err)
	}
	if err := registry.ChainRegistry().Register("quic-test_chains", chain); err != nil {
		t.Fatal(err)
	}
	defer registry.ChainRegistry().Unregister("quic-test_chains")

	entry, err := service_parser.ParseService(&config.ServiceConfig{
		Name:     "quic-entry",
		Addr:     "127.0.0.1:0",
		Handler:  &config.HandlerConfig{Type: "tcp", Chain: "quic-test_chains"},
		Listener: &config.ListenerConfig{Type: "tcp"},
		Forwarder: &config.ForwarderConfig{
			Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: echo.Addr().String()}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	go entry.Serve()
	defer entry.Close()

	var got string
	for attempt := 0; attempt < 5 && got != "over quic"; attempt++ {
		conn, err := net.DialTimeout("tcp", entry.Addr().String(), 2*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		_, _ = conn.Write([]byte("over quic"))
		buf := make([]byte, 9)
		n, _ := io.ReadFull(conn, buf)
		got = string(buf[:n])
		conn.Close()
		if got != "over quic" {
			time.Sleep(300 * time.Millisecond)
		}
	}
	if got != "over quic" {
		t.Fatalf("data did not cross the QUIC tunnel, got %q", got)
	}
}
