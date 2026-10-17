package socket

import (
	"net/http"
	"os"
	"strconv"
	"testing"
	"time"
)

// With MYST_LIVE_API pointing at a running, funded myst (e.g. http://127.0.0.1:4449),
// connects a second residential exit, checks it and disconnects.
func TestMysteriumLive(t *testing.T) {
	api := os.Getenv("MYST_LIVE_API")
	if api == "" {
		t.Skip("MYST_LIVE_API not set")
	}
	country := os.Getenv("MYST_LIVE_COUNTRY")
	if country == "" {
		country = "GB"
	}
	port, _ := strconv.Atoi(os.Getenv("MYST_LIVE_PORT"))
	if port == 0 {
		port = 18901
	}
	dir := os.Getenv("MYST_LIVE_DIR")
	if dir == "" {
		dir = t.TempDir()
	}
	m := &mysteriumManager{directory: dir, api: api, client: &http.Client{Timeout: 90 * time.Second}, wanted: map[int]mysteriumConnection{}}
	m.cmd = selfProcess(t)
	started := time.Now()
	result, err := m.connect(mysteriumConnection{ProxyPort: port, Country: country})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Logf("connected in %s: %+v", time.Since(started).Round(time.Second), result)
	check, err := m.check(port)
	if err != nil {
		t.Fatalf("check: %v", err)
	}
	t.Logf("check: %v", check)
	account, err := m.status([]mysteriumConnection{{ProxyPort: port, Country: country}, {ProxyPort: 18900, Country: "US"}})
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	t.Logf("account: identity=%s registration=%s balance=%.4f", account.Identity, account.RegistrationStatus, account.Balance)
	for _, c := range account.Connections {
		t.Logf("  connection %+v", c)
	}
	if err := m.disconnect(port); err != nil {
		t.Fatalf("disconnect: %v", err)
	}
	after := m.connectionStatus(mysteriumConnection{ProxyPort: port})
	t.Logf("after disconnect: %s", after.Status)
}
