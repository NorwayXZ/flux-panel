//go:build linux && exitlive

package socket

// Live check of the exit pool against real VPN Gate servers. It needs root,
// OpenVPN and internet access, so it is excluded from ordinary test runs:
//
//	GOOS=linux go test -c -tags exitlive -o exit-live.test ./socket
//	sudo ./exit-live.test -test.run TestExitPoolLive -test.v

import (
	"context"
	"encoding/base64"
	"encoding/csv"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/go-gost/x/internal/net/dialer"
	xlogger "github.com/go-gost/x/logger"
)

func liveExitCandidates(t *testing.T, country string) []exitMemberSpec {
	t.Helper()
	response, err := http.Get("http://www.vpngate.net/api/iphone/")
	if err != nil {
		t.Fatalf("fetch exit list: %v", err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 16<<20))
	if err != nil {
		t.Fatal(err)
	}
	var lines []string
	for _, line := range strings.Split(string(body), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "*") {
			continue
		}
		lines = append(lines, strings.TrimPrefix(line, "#"))
	}
	reader := csv.NewReader(strings.NewReader(strings.Join(lines, "\n")))
	reader.FieldsPerRecord = -1
	reader.LazyQuotes = true
	rows, err := reader.ReadAll()
	if err != nil || len(rows) < 2 {
		t.Fatalf("parse exit list: %v", err)
	}
	column := map[string]int{}
	for index, name := range rows[0] {
		column[name] = index
	}
	block := func(config, tag string) string {
		match := regexp.MustCompile(`(?s)<` + tag + `>(.*?)</` + tag + `>`).FindStringSubmatch(config)
		if match == nil {
			return ""
		}
		return strings.TrimSpace(match[1])
	}
	directive := func(config, name string) []string {
		match := regexp.MustCompile(`(?m)^` + name + `\s+(.+)$`).FindStringSubmatch(config)
		if match == nil {
			return nil
		}
		return strings.Fields(match[1])
	}
	var specs []exitMemberSpec
	for index, row := range rows[1:] {
		if len(row) <= column["OpenVPN_ConfigData_Base64"] || row[column["CountryShort"]] != country {
			continue
		}
		if os.Getenv("EXIT_LIVE_INCLUDE_PROJECT") == "" && strings.HasPrefix(row[column["IP"]], "219.100.37.") {
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(row[column["OpenVPN_ConfigData_Base64"]])
		if err != nil {
			continue
		}
		config := strings.ReplaceAll(string(raw), "\r", "")
		remote, proto, cipher, auth := directive(config, "remote"), directive(config, "proto"), directive(config, "cipher"), directive(config, "auth")
		if len(remote) < 2 || len(proto) < 1 || len(cipher) < 1 || len(auth) < 1 {
			continue
		}
		port, _ := strconv.Atoi(remote[1])
		spec, err := normalizeExitMember(exitMemberSpec{ID: fmt.Sprintf("c%d", index), Host: remote[0], Port: port, Proto: proto[0],
			Cipher: cipher[0], Auth: auth[0], CA: block(config, "ca"), Cert: block(config, "cert"), Key: block(config, "key")})
		if err != nil {
			t.Logf("skip %s: %v", remote[0], err)
			continue
		}
		specs = append(specs, spec)
	}
	return specs
}

func liveCommand(t *testing.T, name string, arguments ...string) string {
	t.Helper()
	output, err := exec.Command(name, arguments...).CombinedOutput()
	if err != nil {
		t.Fatalf("%s %v: %v %s", name, arguments, err, output)
	}
	return string(output)
}

func livePublicIP(t *testing.T, ifce string) (string, error) {
	t.Helper()
	netd := &dialer.Dialer{Interface: ifce, Logger: xlogger.NewLogger(xlogger.OutputOption(io.Discard))}
	client := &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{DisableKeepAlives: true,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return netd.Dial(ctx, network, addr)
		}}}
	response, err := client.Get("https://api.ipify.org")
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(response.Body, 64))
	return strings.TrimSpace(string(body)), nil
}

func TestExitPoolLive(t *testing.T) {
	country := os.Getenv("EXIT_LIVE_COUNTRY")
	if country == "" {
		country = "JP"
	}
	candidates := liveExitCandidates(t, country)
	t.Logf("%d candidates for %s", len(candidates), country)
	if len(candidates) == 0 {
		t.Skip("no candidates listed")
	}
	mainBefore := liveCommand(t, "ip", "route", "show", "table", "main")
	rulesBefore := liveCommand(t, "ip", "rule")
	nodeIP, err := livePublicIP(t, "")
	if err != nil {
		t.Fatalf("node address: %v", err)
	}
	t.Logf("node address %s", nodeIP)

	manager := &exitPoolManager{directory: t.TempDir(), runner: newExitRunner(), groups: make(map[string]*exitGroup),
		closing: make(map[string]chan struct{}), now: time.Now}
	dialer.ExitInterfaceResolver = manager.activeDevice
	defer manager.stopAll()

	// Keep trying candidates until two exits are up, as the panel does.
	next, wanted := 0, 2
	var members []exitMemberSpec
	up := map[string]string{}
	deadline := time.Now().Add(6 * time.Minute)
	for time.Now().Before(deadline) && len(up) < wanted {
		for len(members) < 4 && next < len(candidates) {
			members = append(members, candidates[next])
			next++
		}
		status, err := manager.sync(exitPoolSyncRequest{Group: "jp", Members: members})
		if err != nil {
			t.Fatal(err)
		}
		if status.SetupError != "" {
			t.Fatalf("runtime setup: %s", status.SetupError)
		}
		var keep []exitMemberSpec
		for _, member := range status.Members {
			switch member.State {
			case exitStateFailed:
				t.Logf("candidate %s failed: %s", member.ID, member.Error)
				delete(up, member.ID)
				continue
			case exitStateUp:
				if up[member.ID] == "" {
					t.Logf("candidate %s up on %s, exit address %s", member.ID, member.Interface, member.ExitIP)
				}
				up[member.ID] = member.ExitIP
			}
			for _, spec := range members {
				if spec.ID == member.ID {
					keep = append(keep, spec)
				}
			}
		}
		members = keep
		if next >= len(candidates) && len(members) == len(up) {
			break
		}
		time.Sleep(2 * time.Second)
	}
	if len(up) == 0 {
		t.Fatal("no candidate became usable")
	}

	if _, err := livePublicIP(t, "cnexit:jp"); err == nil {
		t.Fatal("an exit that is not approved yet must not carry traffic")
	}

	var approved []string
	var kept []exitMemberSpec
	for _, spec := range members {
		if ip := up[spec.ID]; ip != "" {
			approved = append(approved, ip)
			kept = append(kept, spec)
		}
	}
	status, err := manager.sync(exitPoolSyncRequest{Group: "jp", Members: kept, ApprovedIPs: approved})
	if err != nil || status.ActiveID == "" {
		t.Fatalf("expected an active exit after approval: %+v %v", status, err)
	}
	seen, err := livePublicIP(t, "cnexit:jp")
	if err != nil {
		t.Fatalf("request through the exit failed: %v", err)
	}
	t.Logf("active %s, sites see %s", status.ActiveID, seen)
	if seen != status.ActiveExitIP || seen == nodeIP {
		t.Fatalf("expected the exit address %s, sites saw %s (node is %s)", status.ActiveExitIP, seen, nodeIP)
	}
	if direct, _ := livePublicIP(t, ""); direct != nodeIP {
		t.Fatalf("the node's own traffic moved from %s to %s", nodeIP, direct)
	}
	if now := liveCommand(t, "ip", "route", "show", "table", "main"); now != mainBefore {
		t.Fatalf("main routing table changed:\nbefore:\n%s\nafter:\n%s", mainBefore, now)
	}
	if now := liveCommand(t, "ip", "rule"); now != rulesBefore {
		t.Fatalf("routing rules changed:\nbefore:\n%s\nafter:\n%s", rulesBefore, now)
	}

	// A UDP association must also leave through the exit device.
	netd := &dialer.Dialer{Interface: "cnexit:jp", Logger: xlogger.NewLogger(xlogger.OutputOption(io.Discard))}
	packet, err := netd.Dial(context.Background(), "udp", "")
	if err != nil {
		t.Fatalf("UDP association: %v", err)
	}
	query := []byte{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}
	udpConn := packet.(*net.UDPConn)
	_ = udpConn.SetDeadline(time.Now().Add(8 * time.Second))
	if _, err := udpConn.WriteToUDP(query, &net.UDPAddr{IP: net.IPv4(8, 8, 8, 8), Port: 53}); err != nil {
		t.Fatalf("UDP send through the exit: %v", err)
	}
	reply := make([]byte, 512)
	if n, _, err := udpConn.ReadFromUDP(reply); err != nil || n < 12 || reply[0] != 0x12 || reply[1] != 0x34 {
		t.Errorf("UDP reply through the exit: n=%d err=%v", n, err)
	} else {
		t.Logf("UDP through the exit works (local %s)", udpConn.LocalAddr())
	}
	udpConn.Close()

	if len(kept) > 1 {
		first := status.ActiveID
		pid, err := os.ReadFile(manager.directory + "/" + exitDeviceName("jp", first) + ".pid")
		if err != nil {
			t.Fatal(err)
		}
		started := time.Now()
		liveCommand(t, "kill", "-9", strings.TrimSpace(string(pid)))
		for time.Since(started) < 30*time.Second {
			if status, _ = manager.status("jp"); status.ActiveID != "" && status.ActiveID != first {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		if status.ActiveID == "" || status.ActiveID == first {
			t.Fatalf("no failover after the active exit died: %+v", status)
		}
		t.Logf("failover from %s to %s in %s", first, status.ActiveID, time.Since(started).Round(10*time.Millisecond))
		seen, err = livePublicIP(t, "cnexit:jp")
		if err != nil || seen != status.ActiveExitIP {
			t.Fatalf("after failover sites saw %q (%v), expected %s", seen, err, status.ActiveExitIP)
		}
	} else {
		t.Log("only one exit was usable; failover between exits not exercised")
	}

	if _, err := manager.sync(exitPoolSyncRequest{Group: "jp"}); err != nil {
		t.Fatal(err)
	}
	if ip, err := livePublicIP(t, "cnexit:jp"); err == nil {
		t.Fatalf("with no exit left the request must fail, but sites saw %s", ip)
	}
	time.Sleep(4 * time.Second)
	if links := liveCommand(t, "ip", "-o", "link"); strings.Contains(links, exitDevicePrefix) {
		t.Errorf("exit devices were left behind:\n%s", links)
	}
	if now := liveCommand(t, "ip", "route", "show", "table", "main"); now != mainBefore {
		t.Fatalf("main routing table changed after cleanup")
	}
}
