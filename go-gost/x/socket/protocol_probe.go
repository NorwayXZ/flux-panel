package socket

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	protocolProbeDefaultDownloadBytes int64 = 32 * 1024 * 1024
	protocolProbeDefaultUploadBytes   int64 = 16 * 1024 * 1024
	protocolProbeMaxBytes             int64 = 256 * 1024 * 1024
)

type protocolProbeRequest struct {
	ProxyType     string `json:"proxyType"`
	ProxyHost     string `json:"proxyHost"`
	ProxyPort     int    `json:"proxyPort"`
	Username      string `json:"username"`
	Password      string `json:"password"`
	DownloadURL   string `json:"downloadUrl"`
	UploadURL     string `json:"uploadUrl"`
	DownloadBytes int64  `json:"downloadBytes"`
	UploadBytes   int64  `json:"uploadBytes"`
	TimeoutMs     int    `json:"timeoutMs"`
}

type protocolProbeResponse struct {
	Success        bool    `json:"success"`
	Available      bool    `json:"available"`
	LatencyMs      float64 `json:"latencyMs,omitempty"`
	HandshakeMs    float64 `json:"handshakeMs,omitempty"`
	DownloadBytes  int64   `json:"downloadBytes"`
	DownloadMbps   float64 `json:"downloadMbps,omitempty"`
	UploadBytes    int64   `json:"uploadBytes"`
	UploadMbps     float64 `json:"uploadMbps,omitempty"`
	DownloadStatus int     `json:"downloadStatus,omitempty"`
	UploadStatus   int     `json:"uploadStatus,omitempty"`
	Error          string  `json:"error,omitempty"`
	DownloadError  string  `json:"downloadError,omitempty"`
	UploadError    string  `json:"uploadError,omitempty"`
}

type protocolProbeDialer struct {
	request     protocolProbeRequest
	handshakeMs float64
}

func (d *protocolProbeDialer) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	started := time.Now()
	connection, err := (&net.Dialer{}).DialContext(ctx, "tcp",
		net.JoinHostPort(strings.Trim(d.request.ProxyHost, "[]"), strconv.Itoa(d.request.ProxyPort)))
	if err != nil {
		return nil, err
	}
	timeout := time.Duration(d.request.TimeoutMs) * time.Millisecond
	_ = connection.SetDeadline(time.Now().Add(timeout))
	if d.request.ProxyType == "socks5" {
		err = socks5Connect(connection, proxyRouteProbeRequest{
			Username: d.request.Username,
			Password: d.request.Password,
			Target:   address,
		})
	} else {
		err = httpProxyConnect(connection, proxyRouteProbeRequest{
			Username: d.request.Username,
			Password: d.request.Password,
			Target:   address,
		})
	}
	if err != nil {
		_ = connection.Close()
		return nil, err
	}
	d.handshakeMs = float64(time.Since(started).Microseconds()) / 1000
	_ = connection.SetDeadline(time.Time{})
	return connection, nil
}

func (w *WebSocketReporter) handleProtocolProbe(data interface{}) (protocolProbeResponse, error) {
	raw, err := json.Marshal(data)
	if err != nil {
		return protocolProbeResponse{}, err
	}
	var request protocolProbeRequest
	if err := json.Unmarshal(raw, &request); err != nil {
		return protocolProbeResponse{}, err
	}
	if err := normalizeProtocolProbeRequest(&request); err != nil {
		return protocolProbeResponse{}, err
	}

	response := protocolProbeResponse{}
	downloadClient, downloadDialer := newProtocolProbeClient(request)
	downloadStarted := time.Now()
	downloadURL := addBytesQuery(request.DownloadURL, request.DownloadBytes)
	downloadRequest, err := http.NewRequestWithContext(context.Background(), http.MethodGet, downloadURL, nil)
	if err == nil {
		downloadRequest.Header.Set("Cache-Control", "no-cache")
		downloadRequest.Header.Set("Pragma", "no-cache")
		var downloadResult probeHTTPResult
		downloadResult = executeProtocolProbeRequest(downloadClient, downloadRequest, request.DownloadBytes, downloadStarted, false)
		response.DownloadStatus = downloadResult.status
		response.DownloadBytes = downloadResult.bytes
		response.DownloadMbps = downloadResult.mbps
		response.LatencyMs = downloadResult.headerMs
		response.HandshakeMs = downloadDialer.handshakeMs
		if downloadResult.err != nil {
			response.DownloadError = conciseProtocolProbeError(downloadResult.err)
		}
	} else {
		response.DownloadError = err.Error()
	}
	downloadClient.CloseIdleConnections()

	uploadClient, uploadDialer := newProtocolProbeClient(request)
	uploadStarted := time.Now()
	uploadRequest, err := http.NewRequestWithContext(context.Background(), http.MethodPost, request.UploadURL,
		&zeroReader{remaining: request.UploadBytes})
	if err == nil {
		uploadRequest.ContentLength = request.UploadBytes
		uploadRequest.Header.Set("Content-Type", "application/octet-stream")
		uploadRequest.Header.Set("Cache-Control", "no-cache")
		uploadResult := executeProtocolProbeRequest(uploadClient, uploadRequest, request.UploadBytes, uploadStarted, true)
		response.UploadStatus = uploadResult.status
		response.UploadBytes = uploadResult.bytes
		response.UploadMbps = uploadResult.mbps
		if response.HandshakeMs == 0 {
			response.HandshakeMs = uploadDialer.handshakeMs
		}
		if uploadResult.err != nil {
			response.UploadError = conciseProtocolProbeError(uploadResult.err)
		}
	} else {
		response.UploadError = err.Error()
	}
	uploadClient.CloseIdleConnections()

	response.Available = response.DownloadError == "" && response.UploadError == "" &&
		response.DownloadBytes > 0 && response.UploadBytes > 0
	response.Success = response.Available
	if response.DownloadError != "" {
		response.Error = "下载探测失败：" + response.DownloadError
	} else if response.UploadError != "" {
		response.Error = "上传探测失败：" + response.UploadError
	}
	return response, nil
}

type probeHTTPResult struct {
	status   int
	bytes    int64
	mbps     float64
	headerMs float64
	err      error
}

func executeProtocolProbeRequest(client *http.Client, request *http.Request, expected int64, started time.Time, upload bool) probeHTTPResult {
	result := probeHTTPResult{}
	response, err := client.Do(request)
	if err != nil {
		result.err = err
		return result
	}
	defer response.Body.Close()
	result.status = response.StatusCode
	result.headerMs = float64(time.Since(started).Microseconds()) / 1000
	if response.StatusCode < 200 || response.StatusCode >= 400 {
		result.err = fmt.Errorf("HTTP 状态码 %d", response.StatusCode)
		return result
	}
	if upload {
		// Upload success is established by the request reaching a successful
		// response. The upload endpoint may legitimately return an empty body.
		result.bytes = expected
		_, _ = io.Copy(io.Discard, response.Body)
	} else {
		limit := expected
		if limit < 1 {
			limit = protocolProbeDefaultDownloadBytes
		}
		written, copyErr := io.Copy(io.Discard, io.LimitReader(response.Body, limit))
		result.bytes = written
		if copyErr != nil {
			result.err = copyErr
		}
	}
	duration := time.Since(started).Seconds()
	if duration > 0 {
		result.mbps = float64(result.bytes*8) / duration / 1_000_000
	}
	if !upload && result.bytes == 0 {
		result.err = errors.New("响应没有返回有效数据")
	}
	return result
}

func newProtocolProbeClient(request protocolProbeRequest) (*http.Client, *protocolProbeDialer) {
	dialer := &protocolProbeDialer{request: request}
	transport := &http.Transport{
		DisableCompression: true,
		ForceAttemptHTTP2:  false,
		DialContext:        dialer.dialContext,
	}
	return &http.Client{
		Transport: transport,
		Timeout:   time.Duration(request.TimeoutMs) * time.Millisecond,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}, dialer
}

func normalizeProtocolProbeRequest(request *protocolProbeRequest) error {
	request.ProxyType = strings.ToLower(strings.TrimSpace(request.ProxyType))
	if request.ProxyType != "socks5" && request.ProxyType != "http" {
		return errors.New("protocol probe currently supports socks5 and http client probes only")
	}
	if request.ProxyHost == "" {
		request.ProxyHost = "127.0.0.1"
	}
	if request.ProxyPort < 1 || request.ProxyPort > 65535 {
		return errors.New("proxy port is invalid")
	}
	if request.DownloadBytes < 1 {
		request.DownloadBytes = protocolProbeDefaultDownloadBytes
	}
	if request.UploadBytes < 1 {
		request.UploadBytes = protocolProbeDefaultUploadBytes
	}
	if request.DownloadBytes > protocolProbeMaxBytes || request.UploadBytes > protocolProbeMaxBytes {
		return errors.New("protocol probe payload is too large")
	}
	if request.TimeoutMs < 1_000 || request.TimeoutMs > 120_000 {
		request.TimeoutMs = 30_000
	}
	for _, target := range []string{request.DownloadURL, request.UploadURL} {
		parsed, err := url.Parse(strings.TrimSpace(target))
		if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
			return errors.New("protocol probe target must be a valid http or https URL")
		}
	}
	return nil
}

func addBytesQuery(raw string, bytes int64) string {
	parsed, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	query := parsed.Query()
	query.Set("bytes", strconv.FormatInt(bytes, 10))
	parsed.RawQuery = query.Encode()
	return parsed.String()
}

type zeroReader struct {
	remaining int64
}

func (r *zeroReader) Read(buffer []byte) (int, error) {
	if r.remaining <= 0 {
		return 0, io.EOF
	}
	count := int64(len(buffer))
	if count > r.remaining {
		count = r.remaining
	}
	for index := 0; index < int(count); index++ {
		buffer[index] = 0
	}
	r.remaining -= count
	return int(count), nil
}

func conciseProtocolProbeError(err error) string {
	if err == nil {
		return ""
	}
	message := strings.ReplaceAll(err.Error(), "\n", " ")
	if len(message) > 500 {
		return message[:500]
	}
	return message
}
