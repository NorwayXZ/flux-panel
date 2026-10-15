package local_test

import (
	"fmt"
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
	_ "github.com/go-gost/x/dialer/tls"
	_ "github.com/go-gost/x/handler/relay"
	_ "github.com/go-gost/x/listener/tls"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

// Consecutive and concurrent connections through a relay/TLS tunnel whose entry keeps a pool of
// pre-handshaken connections all reach the landing intact.
func TestTCPForwardThroughPooledTLSRelayTunnel(t *testing.T) {
	corelogger.SetDefault(xlogger.Nop())
	tlsCfg, err := parsing.BuildDefaultTLSConfig(nil)
	if err != nil {
		t.Fatal(err)
	}
	parsing.SetDefaultTLSConfig(tlsCfg)
	echo := startEcho(t)
	defer echo.Close()

	exit, err := service_parser.ParseService(&config.ServiceConfig{
		Name:     "pool-exit",
		Addr:     "127.0.0.1:0",
		Handler:  &config.HandlerConfig{Type: "relay"},
		Listener: &config.ListenerConfig{Type: "tls"},
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
		Name: "pool-test_chains",
		Hops: []*config.HopConfig{{
			Name: "pool-test_hop",
			Nodes: []*config.NodeConfig{{
				Name:      "exit",
				Addr:      exit.Addr().String(),
				Connector: &config.ConnectorConfig{Type: "relay"},
				Dialer:    &config.DialerConfig{Type: "tls", Metadata: map[string]any{"pool.size": 2}},
			}},
		}},
	}, xlogger.Nop())
	if err != nil {
		t.Fatal(err)
	}
	if err := registry.ChainRegistry().Register("pool-test_chains", chain); err != nil {
		t.Fatal(err)
	}
	defer registry.ChainRegistry().Unregister("pool-test_chains")

	entry, err := service_parser.ParseService(&config.ServiceConfig{
		Name:     "pool-entry",
		Addr:     "127.0.0.1:0",
		Handler:  &config.HandlerConfig{Type: "tcp", Chain: "pool-test_chains"},
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

	send := func(payload string) error {
		conn, err := net.DialTimeout("tcp", entry.Addr().String(), 2*time.Second)
		if err != nil {
			return err
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := conn.Write([]byte(payload)); err != nil {
			return err
		}
		buf := make([]byte, len(payload))
		if _, err := io.ReadFull(conn, buf); err != nil {
			return err
		}
		if string(buf) != payload {
			return fmt.Errorf("got %q", buf)
		}
		return nil
	}
	for i := 0; i < 6; i++ {
		if err := send(fmt.Sprintf("sequential-%d", i)); err != nil {
			t.Fatalf("connection %d: %v", i, err)
		}
		time.Sleep(50 * time.Millisecond)
	}
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		go func(i int) { errs <- send(fmt.Sprintf("concurrent-%d", i)) }(i)
	}
	for i := 0; i < 8; i++ {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
}
