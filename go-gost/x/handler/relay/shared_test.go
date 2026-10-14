package relay

import (
	"context"
	"net"
	"strings"
	"testing"
	"time"
)

func TestSharedTargetsRejectPrivateAndMetadataAfterResolution(t *testing.T) {
	original := sharedResolveTarget
	defer func() { sharedResolveTarget = original }()
	for _, address := range []string{"127.0.0.1", "10.0.0.2", "172.16.3.4", "192.168.1.2", "169.254.169.254", "100.100.100.200", "0.0.0.0", "224.0.0.1", "198.18.0.1", "::1", "2001:db8::1"} {
		sharedResolveTarget = func(context.Context, string) ([]net.IP, error) { return []net.IP{net.ParseIP(address)}, nil }
		if conn, err := sharedConnect(context.Background(), "authorized.example:443"); err == nil {
			conn.Close()
			t.Fatalf("protected resolved address allowed: %s", address)
		}
	}
	sharedResolveTarget = func(context.Context, string) ([]net.IP, error) {
		return []net.IP{net.ParseIP("8.8.8.8"), net.ParseIP("192.168.1.2")}, nil
	}
	if conn, err := sharedConnect(context.Background(), "authorized.example:443"); err == nil {
		conn.Close()
		t.Fatal("mixed public/private answer accepted")
	}
}

func TestSharedExpiryRejectsNewConnectionsAndClosesExistingOnes(t *testing.T) {
	service := "shared-expiry-test"
	defer sharedPolicies.Delete(service)
	grant := SharedGrant{Username: "expired", Password: strings.Repeat("x", 40), Targets: []string{"example.com:443"}, ExpiresAt: time.Now().Add(100 * time.Millisecond).UnixMilli()}
	if err := SharedGrants(service, []SharedGrant{grant}); err != nil {
		t.Fatal(err)
	}
	handler := &relayHandler{}
	handler.options.Service = service
	conn, peer := net.Pipe()
	defer conn.Close()
	defer peer.Close()
	finish, allowed := handler.sharedAuthorize(conn, grant.Username, grant.Password, grant.Targets[0])
	if !allowed {
		t.Fatal("unexpired grant refused")
	}
	defer finish()
	peer.SetReadDeadline(time.Now().Add(time.Second))
	var data [1]byte
	if _, err := peer.Read(data[:]); err == nil {
		t.Fatal("expiry must close connection")
	}
	if _, allowed = handler.sharedAuthorize(conn, grant.Username, grant.Password, grant.Targets[0]); allowed {
		t.Fatal("expired principal accepted")
	}
}

func TestSharedRevocationClosesOnlyAffectedSessions(t *testing.T) {
	first := SharedGrant{Username: "A", Password: strings.Repeat("a", 40), Targets: []string{"a.example:443"}}
	second := SharedGrant{Username: "B", Password: strings.Repeat("b", 40), Targets: []string{"b.example:443"}}
	service := "shared-revocation-test"
	defer sharedPolicies.Delete(service)
	if err := SharedGrants(service, []SharedGrant{first, second}); err != nil {
		t.Fatal(err)
	}
	handler := &relayHandler{}
	handler.options.Service = service
	a, ac := net.Pipe()
	b, bc := net.Pipe()
	defer a.Close()
	defer ac.Close()
	defer b.Close()
	defer bc.Close()
	finish, ok := handler.sharedAuthorize(a, first.Username, first.Password, first.Targets[0])
	if !ok {
		t.Fatal("A denied")
	}
	defer finish()
	finishB, ok := handler.sharedAuthorize(b, second.Username, second.Password, second.Targets[0])
	if !ok {
		t.Fatal("B denied")
	}
	defer finishB()
	if err := SharedGrants(service, []SharedGrant{first}); err != nil {
		t.Fatal(err)
	}
	if _, err := bc.Write([]byte("revoked")); err == nil {
		t.Fatal("B session survived revocation")
	}
	if _, allowed := handler.sharedAuthorize(b, first.Username, first.Password, second.Targets[0]); allowed {
		t.Fatal("A credentials allowed B target")
	}
	if err := SharedGrants(service, []SharedGrant{{Username: "invalid", Password: "short", Targets: []string{"invalid:0"}}}); err == nil {
		t.Fatal("bad snapshot accepted")
	}
	if _, allowed := handler.sharedAuthorize(a, first.Username, first.Password, first.Targets[0]); !allowed {
		t.Fatal("bad update replaced working A policy")
	}
}
