package socket

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	wire "github.com/go-gost/relay"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/go-gost/x/config"
	relayhandler "github.com/go-gost/x/handler/relay"
	tlsutil "github.com/go-gost/x/internal/util/tls"
)

func TestCNIXTransportProbeVerifiesActualTLSAndRejectsAnonymousRelay(t *testing.T) {
	cert, err := cnixCertificate("cnix-67.invalid")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := tlsutil.LoadServerConfig(&config.TLSConfig{CertPEM: cert["certPem"], KeyPEM: cert["keyPem"]})
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		name      string
		status    uint8
		identity  string
		wantError bool
	}{{"protected relay", wire.StatusUnauthorized, "cnix-67.invalid", false}, {"anonymous relay", wire.StatusOK, "cnix-67.invalid", true}, {"wrong relay response", wire.StatusBadRequest, "cnix-67.invalid", true}, {"wrong TLS identity", wire.StatusUnauthorized, "cnix-wrong.invalid", true}} {
		t.Run(test.name, func(t *testing.T) {
			ln, err := tls.Listen("tcp", "127.0.0.1:0", cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			go func() {
				conn, err := ln.Accept()
				if err != nil {
					return
				}
				defer conn.Close()
				var request wire.Request
				if _, err = request.ReadFrom(conn); err == nil {
					(&wire.Response{Version: wire.Version1, Status: test.status}).WriteTo(conn)
				}
			}()
			result, err := cnixTransportProbe(map[string]interface{}{"address": ln.Addr().String(), "caPem": cert["certPem"], "serverName": test.identity})
			if (err != nil) != test.wantError {
				t.Fatalf("result=%v error=%v", result, err)
			}
			if err == nil && (result["tlsVerified"] != true || result["authenticationRequired"] != true) {
				t.Fatal(result)
			}
		})
	}
	address, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	target := address.Addr().String()
	address.Close()
	if _, err = cnixTransportProbe(map[string]interface{}{"address": target, "caPem": cert["certPem"], "serverName": "cnix-67.invalid"}); err == nil {
		t.Fatal("offline CNIX endpoint accepted")
	}
}

func TestCNIXCertificatesUseExplicitTrust(t *testing.T) {
	cert, err := cnixCertificate("cnix-17.invalid")
	if err != nil {
		t.Fatal(err)
	}
	server, err := tlsutil.LoadServerConfig(&config.TLSConfig{CertPEM: cert["certPem"], KeyPEM: cert["keyPem"]})
	if err != nil {
		t.Fatal(err)
	}
	client, err := tlsutil.LoadClientConfig(&config.TLSConfig{CAPEM: cert["certPem"], Secure: true, ServerName: cert["serverName"]})
	if err != nil {
		t.Fatal(err)
	}
	if client.InsecureSkipVerify || len(server.Certificates) != 1 {
		t.Fatal("TLS bypass or missing certificate")
	}
	leaf, err := x509.ParseCertificate(server.Certificates[0].Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	if _, err = leaf.Verify(x509.VerifyOptions{Roots: client.RootCAs, DNSName: client.ServerName}); err != nil {
		t.Fatal(err)
	}
	if _, err = leaf.Verify(x509.VerifyOptions{Roots: client.RootCAs, DNSName: "wrong.invalid"}); err == nil {
		t.Fatal("wrong identity accepted")
	}
	if _, err = cnixCertificate("../../key"); err == nil {
		t.Fatal("bad certificate identity accepted")
	}
}

func TestCNIXGrantPersistenceAndFailedWriteRestorePreviousSnapshot(t *testing.T) {
	// The commands use the same persistent GOST config as ordinary services/chains.
	original := config.Global()
	defer config.Set(original)
	directory := t.TempDir()
	t.Chdir(directory)
	grant := relayhandler.SharedGrant{Username: "f17", Password: strings.Repeat("a", 40), Targets: []string{"example.com:443"}}
	cfg := &config.Config{Services: []*config.ServiceConfig{{Name: "cnix-17", Handler: &config.HandlerConfig{Type: "relay", Metadata: map[string]any{"cnix.shared": true, "cnix.grants": []relayhandler.SharedGrant{}}}}}}
	config.Set(cfg)
	if err := cnixGrants(map[string]any{"service": "cnix-17", "grants": []relayhandler.SharedGrant{grant}}); err != nil {
		t.Fatal(err)
	}
	contents, err := os.ReadFile("gost.json")
	if err != nil {
		t.Fatal(err)
	}
	var persisted config.Config
	if err = json.Unmarshal(contents, &persisted); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat("gost.json")
	if info.Mode().Perm() != 0600 {
		t.Fatal("credential config must be private")
	}
	if !strings.Contains(string(contents), "example.com:443") {
		t.Fatal("grant not saved")
	}
	if err = os.Mkdir(filepath.Join(directory, "gost.json.tmp"), 0700); err != nil {
		t.Fatal(err)
	}
	replacement := relayhandler.SharedGrant{Username: "f18", Password: strings.Repeat("b", 40), Targets: []string{"other.example:443"}}
	if err := cnixGrants(map[string]any{"service": "cnix-17", "grants": []relayhandler.SharedGrant{replacement}}); err == nil {
		t.Fatal("failed config write acknowledged")
	}
	data, _ := json.Marshal(config.Global().Services[0].Handler.Metadata["cnix.grants"])
	if strings.Contains(string(data), "other.example") || !strings.Contains(string(data), "example.com") {
		t.Fatal("working policy not restored")
	}
	if err := cnixGrants(map[string]any{"service": "missing", "grants": []relayhandler.SharedGrant{grant}}); err == nil {
		t.Fatal("non-shared service accepted grant mutation")
	}
}
