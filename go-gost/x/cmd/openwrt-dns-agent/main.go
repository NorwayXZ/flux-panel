package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/go-gost/x/dnsdecision"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/gorilla/websocket"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

var version = "2.54.0"

type configuration struct {
	Addr      string `json:"addr"`
	Secret    string `json:"secret"`
	StatePath string `json:"openwrtDnsStatePath"`
}

type response struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId,omitempty"`
	Success   bool   `json:"success"`
	Message   string `json:"message"`
	Data      any    `json:"data,omitempty"`
}

func main() {
	configPath := flag.String("agent-config", "/etc/flux-panel-dns/config.json", "Connection configuration")
	showVersion := flag.Bool("agent-version", false, "Print version")
	flag.Parse()
	if *showVersion {
		fmt.Println(version)
		return
	}
	data, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	var config configuration
	if err = json.Unmarshal(data, &config); err != nil || config.Addr == "" || config.Secret == "" {
		log.Fatal("Invalid connection configuration")
	}
	cipher, err := crypto.NewAESCrypto(config.Secret)
	if err != nil {
		log.Fatal(err)
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	var mu sync.Mutex
	var conn *websocket.Conn
	send := func(value response) {
		payload, err := json.Marshal(value)
		if err != nil {
			return
		}
		encrypted, err := cipher.Encrypt(payload)
		if err != nil {
			return
		}
		wrapper := map[string]any{"encrypted": true, "data": encrypted, "timestamp": time.Now().Unix()}
		mu.Lock()
		defer mu.Unlock()
		if conn == nil {
			return
		}
		_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		_ = conn.WriteJSON(wrapper)
	}
	manager := dnsdecision.NewOpenWrtDNSManager(config.StatePath, func(kind string, fields map[string]interface{}) {
		send(response{Type: kind, Success: true, Message: "OK", Data: fields})
	})
	if err := manager.Start(ctx); err != nil {
		log.Fatal(err)
	}
	defer manager.Stop()
	raw := config.Addr
	if !strings.Contains(raw, "://") {
		raw = "http://" + raw
	}
	endpoint, err := url.Parse(raw)
	if err != nil || endpoint.Host == "" {
		log.Fatal("Invalid panel URL")
	}
	switch endpoint.Scheme {
	case "https", "wss":
		endpoint.Scheme = "wss"
	case "http", "ws":
		endpoint.Scheme = "ws"
	default:
		log.Fatal("Unsupported panel URL")
	}
	endpoint.Path = strings.TrimRight(endpoint.Path, "/") + "/system-info"
	query := url.Values{"type": {"2"}, "version": {version}, "role": {"openwrt_dns"}}
	endpoint.RawQuery = query.Encode()
	go func() {
		<-ctx.Done()
		mu.Lock()
		if conn != nil {
			_ = conn.Close()
		}
		mu.Unlock()
	}()
	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				mu.Lock()
				if conn != nil {
					_ = conn.WriteControl(websocket.PingMessage, []byte("dns"), time.Now().Add(5*time.Second))
				}
				mu.Unlock()
			}
		}
	}()
	for ctx.Err() == nil {
		dialer := *websocket.DefaultDialer
		dialer.HandshakeTimeout = 5 * time.Second
		active, _, err := dialer.DialContext(ctx, endpoint.String(), http.Header{"X-Flux-Dns-Secret": []string{config.Secret}})
		if err != nil {
			log.Println("Panel unavailable, keeping cached DNS policy")
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}
		active.SetReadLimit(4 * 1024 * 1024)
		mu.Lock()
		conn = active
		mu.Unlock()
		log.Println("Panel connected")
		manager.ReportNow()
		active.SetPongHandler(func(string) error { return active.SetReadDeadline(time.Now().Add(60 * time.Second)) })
		for ctx.Err() == nil {
			_ = active.SetReadDeadline(time.Now().Add(60 * time.Second))
			_, payload, readErr := active.ReadMessage()
			if readErr != nil {
				break
			}
			var envelope struct {
				Encrypted bool   `json:"encrypted"`
				Data      string `json:"data"`
			}
			if json.Unmarshal(payload, &envelope) == nil && envelope.Encrypted {
				payload, err = cipher.Decrypt(envelope.Data)
				if err != nil {
					continue
				}
			}
			var command struct {
				Type      string          `json:"type"`
				RequestID string          `json:"requestId"`
				Data      json.RawMessage `json:"data"`
			}
			if json.Unmarshal(payload, &command) != nil {
				continue
			}
			if command.Type == "call" {
				continue
			}
			if command.Type == "OpenWrtDnsPolicy" && !envelope.Encrypted {
				continue
			}
			result := response{Type: command.Type + "Response", RequestID: command.RequestID, Message: "Unsupported command"}
			if command.Type == "OpenWrtDnsPolicy" {
				var policy dnsdecision.OpenWrtDNSPolicy
				err = json.Unmarshal(command.Data, &policy)
				if err == nil {
					err = manager.ApplyPolicy(policy)
				}
				if err == nil {
					result.Success = true
					result.Message = "OK"
				} else {
					result.Message = err.Error()
				}
			}
			send(result)
		}
		mu.Lock()
		conn = nil
		_ = active.Close()
		mu.Unlock()
		select {
		case <-ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}
