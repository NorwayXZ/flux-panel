package socket

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const defaultProbeURL = "https://www.cloudflare.com/cdn-cgi/trace"

// privateProxyProbeRequest describes a client for one of the node's own
// proxies. The node runs that client for a moment and fetches a page through
// it, which checks the whole path a real device would use: listener,
// credentials, the runtime and where the traffic leaves.
type privateProxyProbeRequest struct {
	// "sing-box" (default) or "xray", the client used for the test.
	Engine string `json:"engine,omitempty"`
	// sing-box outbounds/endpoints, or xray outbounds. The one tagged
	// "proxy" carries the test.
	Outbounds []json.RawMessage `json:"outbounds"`
	Endpoints []json.RawMessage `json:"endpoints,omitempty"`
	URL       string            `json:"url,omitempty"`
}

type privateProxyProbeResponse struct {
	IP        string `json:"ip"`
	Location  string `json:"location,omitempty"`
	LatencyMs int64  `json:"latencyMs"`
}

// At most two tests run at once so a burst of checks cannot load the node.
var probeSlots = make(chan struct{}, 2)

type probeRunner struct {
	singBox *privateProxyRuntimeManager
	xray    *realityRuntimeManager
}

func (p *probeRunner) probe(request privateProxyProbeRequest) (privateProxyProbeResponse, error) {
	if len(request.Outbounds) == 0 && len(request.Endpoints) == 0 {
		return privateProxyProbeResponse{}, errors.New("probe needs a client outbound")
	}
	target := strings.TrimSpace(request.URL)
	if target == "" {
		target = defaultProbeURL
	}
	if parsed, err := url.Parse(target); err != nil || (parsed.Scheme != "https" && parsed.Scheme != "http") || parsed.Host == "" {
		return privateProxyProbeResponse{}, errors.New("invalid probe URL")
	}
	select {
	case probeSlots <- struct{}{}:
		defer func() { <-probeSlots }()
	case <-time.After(30 * time.Second):
		return privateProxyProbeResponse{}, errors.New("too many proxy tests are running")
	}

	port, err := availableLocalPort()
	if err != nil {
		return privateProxyProbeResponse{}, err
	}
	var binary, directory string
	var config map[string]interface{}
	switch request.Engine {
	case "", "sing-box":
		if p.singBox == nil {
			return privateProxyProbeResponse{}, errors.New("sing-box runtime is unavailable")
		}
		if err := p.singBox.ensureBinary(); err != nil {
			return privateProxyProbeResponse{}, err
		}
		binary, directory = p.singBox.binary, p.singBox.directory
		config = singBoxProbeConfig(port, request)
	case "xray":
		if p.xray == nil {
			return privateProxyProbeResponse{}, errors.New("xray runtime is unavailable")
		}
		if err := p.xray.ensureBinary(); err != nil {
			return privateProxyProbeResponse{}, err
		}
		binary, directory = p.xray.binary, p.xray.directory
		config = xrayProbeConfig(port, request)
	default:
		return privateProxyProbeResponse{}, errors.New("unsupported probe engine")
	}
	data, err := json.Marshal(config)
	if err != nil {
		return privateProxyProbeResponse{}, err
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return privateProxyProbeResponse{}, err
	}
	path := filepath.Join(directory, "probe-"+randomRuntimeToken(6)+".json")
	if err := writeRuntimeFile(path, data, 0600); err != nil {
		return privateProxyProbeResponse{}, err
	}
	defer os.Remove(path)

	var output limitedBuffer
	cmd := exec.Command(binary, "run", "-c", path)
	cmd.Stdout, cmd.Stderr = &output, &output
	if err := cmd.Start(); err != nil {
		return privateProxyProbeResponse{}, fmt.Errorf("start test client: %w", err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}()
	if err := waitForTCP(port, 5*time.Second); err != nil {
		return privateProxyProbeResponse{}, probeError("test client did not start", output.String())
	}

	proxyURL, _ := url.Parse(fmt.Sprintf("socks5://127.0.0.1:%d", port))
	client := &http.Client{Timeout: 15 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
	started := time.Now()
	response, err := client.Get(target)
	if err != nil {
		return privateProxyProbeResponse{}, probeError(simplifyProbeError(err), output.String())
	}
	body, _ := io.ReadAll(io.LimitReader(response.Body, 64*1024))
	response.Body.Close()
	elapsed := time.Since(started)
	if response.StatusCode >= 400 {
		return privateProxyProbeResponse{}, fmt.Errorf("test page returned HTTP %d", response.StatusCode)
	}
	result := privateProxyProbeResponse{LatencyMs: elapsed.Milliseconds()}
	for _, line := range strings.Split(string(body), "\n") {
		if value, ok := strings.CutPrefix(strings.TrimSpace(line), "ip="); ok {
			result.IP = value
		}
		if value, ok := strings.CutPrefix(strings.TrimSpace(line), "loc="); ok {
			result.Location = value
		}
	}
	return result, nil
}

func singBoxProbeConfig(port int, request privateProxyProbeRequest) map[string]interface{} {
	config := map[string]interface{}{
		"log":       map[string]interface{}{"level": "warn"},
		"inbounds":  []interface{}{map[string]interface{}{"type": "mixed", "tag": "probe-in", "listen": "127.0.0.1", "listen_port": port}},
		"outbounds": request.Outbounds,
	}
	route := map[string]interface{}{"final": "proxy"}
	if len(request.Endpoints) > 0 {
		// A WireGuard endpoint cannot take host names; resolve them inside the tunnel.
		config["endpoints"] = request.Endpoints
		config["dns"] = map[string]interface{}{"servers": []interface{}{
			map[string]interface{}{"type": "udp", "tag": "probe-dns", "server": "1.1.1.1", "detour": "proxy"},
		}}
		route["default_domain_resolver"] = "probe-dns"
	}
	if len(request.Outbounds) == 0 {
		config["outbounds"] = []interface{}{}
	}
	config["route"] = route
	return config
}

func xrayProbeConfig(port int, request privateProxyProbeRequest) map[string]interface{} {
	return map[string]interface{}{
		"log": map[string]interface{}{"loglevel": "warning"},
		"inbounds": []interface{}{map[string]interface{}{
			"listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": map[string]interface{}{"auth": "noauth"},
		}},
		"outbounds": request.Outbounds,
	}
}

func simplifyProbeError(err error) string {
	message := err.Error()
	if index := strings.LastIndex(message, ": "); index >= 0 && strings.Contains(message, "Get \"") {
		return message[index+2:]
	}
	return message
}

var ansiEscape = regexp.MustCompile(`\x1b\[[0-9;]*m`)

func probeError(message, output string) error {
	output = strings.TrimSpace(ansiEscape.ReplaceAllString(output, ""))
	if output == "" {
		return errors.New(message)
	}
	lines := strings.Split(output, "\n")
	if len(lines) > 3 {
		lines = lines[len(lines)-3:]
	}
	return fmt.Errorf("%s; client log: %s", message, strings.Join(lines, " | "))
}

// limitedBuffer keeps the first 16 KiB a test client writes.
type limitedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if room := 16*1024 - b.buf.Len(); room > 0 {
		if len(p) > room {
			b.buf.Write(p[:room])
		} else {
			b.buf.Write(p)
		}
	}
	return len(p), nil
}

func (b *limitedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}
