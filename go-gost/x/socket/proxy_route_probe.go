package socket

import (
	"bufio"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"time"
)

type proxyRouteProbeRequest struct {
	ProxyType string `json:"proxyType"`
	ProxyHost string `json:"proxyHost"`
	ProxyPort int    `json:"proxyPort"`
	Username  string `json:"username"`
	Password  string `json:"password"`
	Target    string `json:"target"`
	TimeoutMs int    `json:"timeoutMs"`
}

type proxyRouteProbeResponse struct {
	Success    bool    `json:"success"`
	Target     string  `json:"target"`
	LatencyMs  float64 `json:"latencyMs"`
	LocalAddr  string  `json:"localAddress,omitempty"`
	RemoteAddr string  `json:"remoteAddress,omitempty"`
	Error      string  `json:"error,omitempty"`
}

func (w *WebSocketReporter) handleProxyRouteProbe(data interface{}) (proxyRouteProbeResponse, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return proxyRouteProbeResponse{}, err
	}
	var request proxyRouteProbeRequest
	if err := json.Unmarshal(raw, &request); err != nil {
		return proxyRouteProbeResponse{}, err
	}
	request.ProxyType = strings.ToLower(strings.TrimSpace(request.ProxyType))
	if request.ProxyType != "socks5" && request.ProxyType != "http" {
		return proxyRouteProbeResponse{}, errors.New("proxy type must be socks5 or http")
	}
	if request.ProxyPort < 1 || request.ProxyPort > 65535 {
		return proxyRouteProbeResponse{}, errors.New("proxy port is invalid")
	}
	if request.ProxyHost == "" {
		request.ProxyHost = "127.0.0.1"
	}
	if request.Target == "" {
		request.Target = "www.cloudflare.com:443"
	}
	if _, _, err := net.SplitHostPort(request.Target); err != nil {
		return proxyRouteProbeResponse{}, errors.New("probe target is invalid")
	}
	if request.TimeoutMs < 500 || request.TimeoutMs > 15000 {
		request.TimeoutMs = 5000
	}
	started := time.Now()
	connection, err := net.DialTimeout("tcp", net.JoinHostPort(request.ProxyHost, strconv.Itoa(request.ProxyPort)), time.Duration(request.TimeoutMs)*time.Millisecond)
	result := proxyRouteProbeResponse{Target: request.Target}
	if err != nil {
		result.Error = err.Error()
		return result, nil
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(time.Duration(request.TimeoutMs) * time.Millisecond))
	result.LocalAddr = connection.LocalAddr().String()
	result.RemoteAddr = connection.RemoteAddr().String()
	if request.ProxyType == "socks5" {
		err = socks5Connect(connection, request)
	} else {
		err = httpProxyConnect(connection, request)
	}
	if err != nil {
		result.Error = err.Error()
		return result, nil
	}
	result.Success = true
	result.LatencyMs = float64(time.Since(started).Microseconds()) / 1000
	return result, nil
}

func socks5Connect(connection net.Conn, request proxyRouteProbeRequest) error {
	method := byte(2)
	if request.Username == "" && request.Password == "" {
		method = 0
	}
	if _, err := connection.Write([]byte{5, 1, method}); err != nil {
		return err
	}
	response := make([]byte, 2)
	if _, err := io.ReadFull(connection, response); err != nil {
		return err
	}
	if response[0] != 5 || response[1] != method {
		return errors.New("SOCKS5 proxy rejected the requested authentication method")
	}
	if method == 2 {
		if len(request.Username) > 255 || len(request.Password) > 255 {
			return errors.New("SOCKS5 credentials are too long")
		}
		auth := []byte{1, byte(len(request.Username))}
		auth = append(auth, []byte(request.Username)...)
		auth = append(auth, byte(len(request.Password)))
		auth = append(auth, []byte(request.Password)...)
		if _, err := connection.Write(auth); err != nil {
			return err
		}
		if _, err := io.ReadFull(connection, response); err != nil {
			return err
		}
		if response[1] != 0 {
			return errors.New("SOCKS5 authentication failed")
		}
	}
	host, portText, _ := net.SplitHostPort(request.Target)
	port, _ := strconv.Atoi(portText)
	packet := []byte{5, 1, 0, 3, byte(len(host))}
	packet = append(packet, []byte(host)...)
	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, uint16(port))
	packet = append(packet, portBytes...)
	if _, err := connection.Write(packet); err != nil {
		return err
	}
	header := make([]byte, 4)
	if _, err := io.ReadFull(connection, header); err != nil {
		return err
	}
	if header[1] != 0 {
		return fmt.Errorf("SOCKS5 exit returned status %d", header[1])
	}
	length := 0
	switch header[3] {
	case 1:
		length = 4
	case 4:
		length = 16
	case 3:
		one := make([]byte, 1)
		if _, err := io.ReadFull(connection, one); err != nil {
			return err
		}
		length = int(one[0])
	default:
		return errors.New("SOCKS5 exit returned invalid address type")
	}
	_, err := io.ReadFull(connection, make([]byte, length+2))
	return err
}

func httpProxyConnect(connection net.Conn, request proxyRouteProbeRequest) error {
	auth := base64.StdEncoding.EncodeToString([]byte(request.Username + ":" + request.Password))
	message := "CONNECT " + request.Target + " HTTP/1.1\r\nHost: " + request.Target + "\r\nProxy-Authorization: Basic " + auth + "\r\nConnection: close\r\n\r\n"
	if _, err := connection.Write([]byte(message)); err != nil {
		return err
	}
	line, err := bufio.NewReader(connection).ReadString('\n')
	if err != nil {
		return err
	}
	if !strings.Contains(line, " 200 ") {
		return errors.New("HTTP proxy CONNECT failed: " + strings.TrimSpace(line))
	}
	return nil
}
