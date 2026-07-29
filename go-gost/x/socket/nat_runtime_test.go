package socket

import (
	"fmt"
	"io"
	"net"
	"os"
	"testing"
	"time"

	"github.com/pion/stun/v3"
)

func TestNATDirectPathForwardsThroughICEAndQUIC(t *testing.T) {
	stunAddress := startLocalSTUNServer(t)
	backend := startEchoServer(t)
	listenAddress := unusedTCPAddress(t)
	directEstablished := make(chan struct{}, 1)

	home := newNATRuntimeManager(func(string, map[string]interface{}) {})
	source := newNATRuntimeManager(func(event string, data map[string]interface{}) {
		if event == "NatPathChanged" && data["accessPath"] == "udp_direct" {
			select {
			case directEstablished <- struct{}{}:
			default:
			}
		}
	})
	home.stateFile = t.TempDir() + "/home-state.json"
	source.stateFile = t.TempDir() + "/source-state.json"
	t.Cleanup(home.stopAll)
	t.Cleanup(source.stopAll)

	common := natPrepareRequest{
		RouteID: 41, SessionID: "local-integration", Token: "0123456789abcdef0123456789abcdef",
		STUNServers: []string{"stun:" + stunAddress}, ConnectTimeoutSeconds: 5,
	}
	homeRequest := common
	homeRequest.Role = "home"
	homeRequest.BackendAddress = backend
	homePrepared, err := home.prepare(homeRequest)
	if err != nil {
		t.Fatal(err)
	}
	sourceRequest := common
	sourceRequest.Role = "source"
	sourceRequest.ListenAddress = listenAddress
	sourceRequest.FallbackAddress = "127.0.0.1:1"
	sourcePrepared, err := source.prepare(sourceRequest)
	if err != nil {
		t.Fatal(err)
	}

	if err := home.connect(natConnectRequest{
		RouteID: common.RouteID, SessionID: common.SessionID, Token: common.Token, Role: "controlled",
		RemoteUsername: sourcePrepared.UsernameFragment, RemotePassword: sourcePrepared.Password,
		RemoteCandidates: sourcePrepared.Candidates, ConnectTimeoutSeconds: 5,
	}); err != nil {
		t.Fatal(err)
	}
	if err := source.connect(natConnectRequest{
		RouteID: common.RouteID, SessionID: common.SessionID, Token: common.Token, Role: "controlling",
		RemoteUsername: homePrepared.UsernameFragment, RemotePassword: homePrepared.Password,
		RemoteCandidates: homePrepared.Candidates, ExpectedFingerprint: homePrepared.CertificateFingerprint,
		ConnectTimeoutSeconds: 5,
	}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-directEstablished:
	case <-time.After(20 * time.Second):
		t.Fatal("ICE/QUIC direct path was not established")
	}

	client, err := net.DialTimeout("tcp", listenAddress, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := client.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, 4)
	if _, err := io.ReadFull(client, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != "ping" {
		t.Fatalf("unexpected direct response: %q", response)
	}
}

func TestNATFallbackForwardsLocalConnections(t *testing.T) {
	upstream, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	go func() {
		conn, acceptErr := upstream.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		payload, _ := io.ReadAll(io.LimitReader(conn, 4))
		_, _ = conn.Write(payload)
	}()

	probe, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	listenAddress := probe.Addr().String()
	_ = probe.Close()
	manager := newNATRuntimeManager(func(string, map[string]interface{}) {})
	manager.stateFile = t.TempDir() + "/state.json"
	if err := manager.fallback(natFallbackRequest{RouteID: 1, ListenAddress: listenAddress, FallbackAddress: upstream.Addr().String()}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(manager.stopAll)

	client, err := net.DialTimeout("tcp", listenAddress, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	if _, err := client.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, 4)
	if _, err := io.ReadFull(client, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != "ping" {
		t.Fatalf("unexpected fallback response: %q", response)
	}
}

func TestNATRuntimeStateFileIsPrivate(t *testing.T) {
	manager := newNATRuntimeManager(func(string, map[string]interface{}) {})
	manager.stateFile = t.TempDir() + "/state.json"
	manager.persistSources()
	info, err := os.Stat(manager.stateFile)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("state mode = %o, want 600", info.Mode().Perm())
	}
}

func TestNATCertificateFingerprintMatchesCertificate(t *testing.T) {
	certificate, fingerprint, err := generateNATCertificate()
	if err != nil {
		t.Fatal(err)
	}
	if len(certificate.Certificate) != 1 || len(fingerprint) != 64 {
		t.Fatalf("invalid certificate or fingerprint: %d %q", len(certificate.Certificate), fingerprint)
	}
}

func TestPrivateIPClassification(t *testing.T) {
	if !isPrivateIP(net.ParseIP("192.168.1.1")) || !isPrivateIP(net.ParseIP("fd00::1")) {
		t.Fatal("private address was not classified as private")
	}
	if isPrivateIP(net.ParseIP("1.1.1.1")) {
		t.Fatal("public address was classified as private")
	}
}

func startLocalSTUNServer(t *testing.T) string {
	t.Helper()
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = server.Close() })
	go func() {
		buffer := make([]byte, 2048)
		for {
			n, remote, readErr := server.ReadFromUDP(buffer)
			if readErr != nil {
				return
			}
			request := stun.New()
			if stun.Decode(buffer[:n], request) != nil || request.Type != stun.BindingRequest {
				continue
			}
			response := stun.MustBuild(
				stun.NewTransactionIDSetter(request.TransactionID), stun.BindingSuccess,
				&stun.XORMappedAddress{IP: remote.IP, Port: remote.Port}, stun.Fingerprint,
			)
			_, _ = server.WriteToUDP(response.Raw, remote)
		}
	}()
	return server.LocalAddr().String()
}

func startEchoServer(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			go func() {
				defer connection.Close()
				_, _ = io.Copy(connection, connection)
			}()
		}
	}()
	return listener.Addr().String()
}

func unusedTCPAddress(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatal(fmt.Errorf("release test port: %w", err))
	}
	return address
}
