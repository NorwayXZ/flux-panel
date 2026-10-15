package relay

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// SharedGrant is scoped to one business, not to the whole shared transport.
type SharedGrant struct {
	ExpiresAt int64    `json:"expiresAt,omitempty"`
	Username  string   `json:"username"`
	Password  string   `json:"password"`
	Targets   []string `json:"targets"`
}

type sharedPolicy struct {
	mu       sync.Mutex
	grants   map[string]SharedGrant
	sessions map[net.Conn]string
}

var sharedPolicies sync.Map
var sharedDialTarget = func(ctx context.Context, address string) (net.Conn, error) {
	return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp4", address)
}
var sharedResolveTarget = func(ctx context.Context, host string) ([]net.IP, error) {
	return net.DefaultResolver.LookupIP(ctx, "ip4", host)
}

func sharedConnect(ctx context.Context, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	resolveCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	ips, err := sharedResolveTarget(resolveCtx, host)
	if err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, errors.New("shared target has no IPv4 address")
	}
	// Validate all resolved addresses and dial the validated literal. DNS cannot
	// change between authorization and connect to escape the private-network guard.
	for _, ip := range ips {
		if !sharedPublicIPv4(ip) {
			return nil, errors.New("shared target resolves to a protected address")
		}
	}
	var last error
	for _, ip := range ips {
		connection, err := sharedDialTarget(ctx, net.JoinHostPort(ip.String(), port))
		if err == nil {
			return connection, nil
		}
		last = err
	}
	return nil, last
}

func sharedPublicIPv4(ip net.IP) bool {
	v := ip.To4()
	if v == nil || v[0] == 0 || v[0] >= 224 || ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() {
		return false
	}
	if v[0] == 100 && v[1] >= 64 && v[1] <= 127 {
		return false
	}
	if v[0] == 198 && (v[1] == 18 || v[1] == 19) {
		return false
	}
	return true
}

func SharedGrants(service string, grants []SharedGrant) error {
	next := make(map[string]SharedGrant)
	for _, grant := range grants {
		if grant.Username == "" || len(grant.Username) > 255 || len(grant.Password) < 32 || len(grant.Password) > 255 || len(grant.Targets) == 0 {
			return errors.New("invalid shared relay grant")
		}
		if _, exists := next[grant.Username]; exists {
			return errors.New("duplicate shared relay principal")
		}
		for _, target := range grant.Targets {
			if _, err := canonicalTarget(target); err != nil {
				return err
			}
		}
		next[grant.Username] = grant
	}
	value, _ := sharedPolicies.LoadOrStore(service, &sharedPolicy{grants: map[string]SharedGrant{}, sessions: map[net.Conn]string{}})
	policy := value.(*sharedPolicy)
	policy.mu.Lock()
	defer policy.mu.Unlock()
	for connection, user := range policy.sessions {
		before, oldOK := policy.grants[user]
		after, newOK := next[user]
		a, _ := json.Marshal(before)
		b, _ := json.Marshal(after)
		if !oldOK || !newOK || string(a) != string(b) {
			connection.Close()
			delete(policy.sessions, connection)
		}
	}
	policy.grants = next
	return nil
}

func canonicalTarget(target string) (string, error) {
	host, port, err := net.SplitHostPort(target)
	number, portErr := strconv.Atoi(port)
	if err != nil || host == "" || portErr != nil || number < 1 || number > 65535 {
		return "", errors.New("invalid shared relay target")
	}
	return net.JoinHostPort(strings.ToLower(strings.TrimSuffix(host, ".")), strconv.Itoa(number)), nil
}

func (h *relayHandler) sharedAuthorize(conn net.Conn, user, password, target string) (func(), bool) {
	value, exists := sharedPolicies.Load(h.options.Service)
	if !exists {
		return nil, false
	}
	policy := value.(*sharedPolicy)
	policy.mu.Lock()
	defer policy.mu.Unlock()
	grant, exists := policy.grants[user]
	if !exists || (grant.ExpiresAt > 0 && time.Now().UnixMilli() >= grant.ExpiresAt) || subtle.ConstantTimeCompare([]byte(password), []byte(grant.Password)) != 1 {
		return nil, false
	}
	requested, err := canonicalTarget(target)
	if err != nil {
		return nil, false
	}
	allowed := false
	for _, entry := range grant.Targets {
		candidate, _ := canonicalTarget(entry)
		allowed = allowed || candidate == requested
	}
	if !allowed {
		return nil, false
	}
	policy.sessions[conn] = user
	var expiryTimer *time.Timer
	if grant.ExpiresAt > 0 {
		expiryTimer = time.AfterFunc(time.Until(time.UnixMilli(grant.ExpiresAt)), func() { policy.mu.Lock(); defer policy.mu.Unlock(); conn.Close(); delete(policy.sessions, conn) })
	}
	return func() {
		if expiryTimer != nil {
			expiryTimer.Stop()
		}
		policy.mu.Lock()
		delete(policy.sessions, conn)
		policy.mu.Unlock()
	}, true
}
