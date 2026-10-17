package socket

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type mysteriumBillingConfig struct {
	Role         string `json:"role"`
	Consumer     string `json:"consumer"`
	Endpoint     string `json:"endpoint,omitempty"`
	Token        string `json:"token"`
	BinarySHA256 string `json:"binarySha256"`
}

func (m *mysteriumManager) billingConfig() (*mysteriumBillingConfig, error) {
	raw, err := os.ReadFile(filepath.Join(m.directory, "shared-billing.json"))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var cfg mysteriumBillingConfig
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return nil, err
	}
	if (cfg.Role != "signer" && cfg.Role != "consumer") || len(cfg.Token) < 32 || len(cfg.BinarySHA256) != 64 {
		return nil, errors.New("invalid saved shared billing configuration")
	}
	return &cfg, nil
}

func (m *mysteriumManager) billingEnv() ([]string, error) {
	cfg, err := m.billingConfig()
	if err != nil {
		return nil, err
	}
	env := mysteriumDaemonEnv()
	// Never inherit a different daemon's signing configuration.
	clean := env[:0]
	for _, item := range env {
		if !strings.HasPrefix(item, "MYST_SHARED_") {
			clean = append(clean, item)
		}
	}
	if cfg == nil {
		return clean, nil
	}
	if cfg.Role == "signer" {
		return append(clean, "MYST_SHARED_SIGNER_TOKEN="+cfg.Token, "MYST_SHARED_CONSUMER="+cfg.Consumer, "MYST_SHARED_BILLING_URL=http://127.0.0.1:4451/pay", "MYST_SHARED_BILLING_TOKEN="+cfg.Token), nil
	}
	return append(clean, "MYST_SHARED_BILLING_URL="+cfg.Endpoint, "MYST_SHARED_BILLING_TOKEN="+cfg.Token), nil
}

type mysteriumBillingAsset struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}

type mysteriumBillingRequest struct {
	BinaryAssets map[string]mysteriumBillingAsset `json:"binaryAssets"`
	Role         string                           `json:"role"`
	Consumer     string                           `json:"consumer"`
	Endpoint     string                           `json:"endpoint"`
	Token        string                           `json:"token"`
	BinaryURL    string                           `json:"binaryUrl"`
	BinarySHA256 string                           `json:"binarySha256"`
	IdentityData string                           `json:"identityData"`
	Passphrase   string                           `json:"passphrase"`
}

func (m *mysteriumManager) configureBilling(request mysteriumBillingRequest) (map[string]interface{}, error) {
	if asset, ok := request.BinaryAssets[runtime.GOOS+"-"+runtime.GOARCH]; ok {
		request.BinaryURL = asset.URL
		request.BinarySHA256 = asset.SHA256
	}
	if request.Role != "signer" && request.Role != "consumer" {
		return nil, errors.New("invalid shared billing role")
	}
	if len(request.Consumer) != 42 || !strings.HasPrefix(request.Consumer, "0x") {
		return nil, errors.New("invalid shared identity")
	}
	if _, err := hex.DecodeString(request.Consumer[2:]); err != nil {
		return nil, err
	}
	if request.Role == "consumer" {
		u, err := url.Parse(request.Endpoint)
		if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || len(request.Token) < 32 {
			return nil, errors.New("shared billing requires HTTPS and a node token")
		}
		if request.IdentityData == "" || request.Passphrase == "" {
			return nil, errors.New("encrypted identity required")
		}
	}
	u, err := url.Parse(request.BinaryURL)
	if err != nil || u.Scheme != "https" || u.Host != "github.com" || !strings.HasPrefix(u.Path, "/NorwayXZ/flux-panel/releases/download/") {
		return nil, errors.New("shared consumer binary must come from the project release")
	}
	checksum, err := hex.DecodeString(request.BinarySHA256)
	if err != nil || len(checksum) != 32 {
		return nil, errors.New("binary SHA256 required")
	}
	if err := m.ensureRunning(); err != nil {
		return nil, err
	}
	id, err := m.identity()
	if err != nil {
		return nil, err
	}
	if request.Role == "signer" && !strings.EqualFold(id, request.Consumer) {
		return nil, errors.New("signer identity does not match funded account")
	}
	if request.Role == "signer" {
		token := make([]byte, 32)
		if _, err := rand.Read(token); err != nil {
			return nil, err
		}
		request.Token = hex.EncodeToString(token)
	}
	download := filepath.Join(m.directory, ".shared-myst.new")
	if err := downloadFile(&http.Client{Timeout: 3 * time.Minute}, request.BinaryURL, download, 128*1024*1024); err != nil {
		return nil, err
	}
	defer os.Remove(download)
	if err := verifyMysteriumBinary(download, request.BinarySHA256); err != nil {
		return nil, err
	}
	if err := os.Chmod(download, 0700); err != nil {
		return nil, err
	}
	if request.Role == "consumer" {
		var imported struct {
			ID string `json:"id"`
		}
		// Importing leaves the former account key in the keystore for recovery.
		if err := m.call(http.MethodPost, "/identities-import", map[string]interface{}{"data": request.IdentityData, "current_passphrase": request.Passphrase, "new_passphrase": "", "set_default": true}, &imported); err != nil {
			return nil, err
		}
		if !strings.EqualFold(imported.ID, request.Consumer) {
			return nil, errors.New("imported identity does not match shared account")
		}
	}
	m.stop()
	if err := os.Rename(download, filepath.Join(m.directory, "bin", "myst-shared")); err != nil {
		return nil, err
	}
	cfg := mysteriumBillingConfig{Role: request.Role, Consumer: request.Consumer, Endpoint: request.Endpoint, Token: request.Token, BinarySHA256: request.BinarySHA256}
	raw, _ := json.Marshal(cfg)
	temporary := filepath.Join(m.directory, ".shared-billing.new")
	if err := os.WriteFile(temporary, raw, 0600); err != nil {
		return nil, err
	}
	if err := os.Rename(temporary, filepath.Join(m.directory, "shared-billing.json")); err != nil {
		return nil, err
	}
	if err := m.ensureRunning(); err != nil {
		return nil, err
	}
	if _, err := m.identity(); err != nil {
		return nil, err
	}
	go m.reconnectWanted()
	result := map[string]interface{}{"role": cfg.Role, "consumer": cfg.Consumer}
	if cfg.Role == "signer" {
		result["signerToken"] = cfg.Token
	}
	return result, nil
}

func (m *mysteriumManager) exportBillingIdentity(passphrase string) (interface{}, error) {
	if len(passphrase) < 32 {
		return nil, errors.New("export passphrase too short")
	}
	if err := m.ensureRunning(); err != nil {
		return nil, err
	}
	id, err := m.identity()
	if err != nil {
		return nil, err
	}
	var exported json.RawMessage
	if err := m.call(http.MethodPost, "/identities/export", map[string]string{"identity": id, "newpassphrase": passphrase}, &exported); err != nil {
		return nil, err
	}
	return map[string]interface{}{"identity": id, "identityData": []byte(exported)}, nil
}

func (m *mysteriumManager) signBillingInvoice(invoice json.RawMessage) (interface{}, error) {
	cfg, err := m.billingConfig()
	if err != nil {
		return nil, err
	}
	if cfg == nil || cfg.Role != "signer" {
		return nil, errors.New("node is not a shared signer")
	}
	if err := m.ensureRunning(); err != nil {
		return nil, err
	}
	if _, err := m.identity(); err != nil {
		return nil, err
	}
	request, err := http.NewRequest(http.MethodPost, "http://127.0.0.1:4451/pay", bytes.NewReader(invoice))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+cfg.Token)
	response, err := (&http.Client{Timeout: 32 * time.Second}).Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, 32768))
	if err != nil {
		return nil, err
	}
	if response.StatusCode != 200 {
		return nil, fmt.Errorf("shared signer: %s", strings.TrimSpace(string(body)))
	}
	if !json.Valid(body) {
		return nil, errors.New("invalid signer response")
	}
	return map[string]string{"signedPayload": string(body)}, nil
}

func verifyMysteriumBinary(path, expected string) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return err
	}
	if !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), expected) {
		return errors.New("shared myst checksum mismatch")
	}
	return nil
}
