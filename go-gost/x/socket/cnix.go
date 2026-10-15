package socket

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"strings"
	"sync"
	"time"

	wire "github.com/go-gost/relay"
	"github.com/go-gost/x/config"
	relayhandler "github.com/go-gost/x/handler/relay"
	"github.com/go-gost/x/registry"
)

var cnixUpdateMu sync.Mutex

// Authenticate the TLS identity and require relay to reject an unissued
// principal, without creating a business or granting a destination.
func cnixTransportProbe(data interface{}) (map[string]interface{}, error) {
	var request struct {
		Address    string `json:"address"`
		CAPEM      string `json:"caPem"`
		ServerName string `json:"serverName"`
	}
	if err := decodeCommandData(data, &request); err != nil {
		return nil, err
	}
	if _, _, err := net.SplitHostPort(request.Address); err != nil {
		return nil, errors.New("invalid CNIX external endpoint")
	}
	if !strings.HasPrefix(request.ServerName, "cnix-") || !strings.HasSuffix(request.ServerName, ".invalid") {
		return nil, errors.New("invalid CNIX TLS identity")
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM([]byte(request.CAPEM)) {
		return nil, errors.New("invalid CNIX trust certificate")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	start := time.Now()
	dialer := tls.Dialer{NetDialer: &net.Dialer{Timeout: 4 * time.Second}, Config: &tls.Config{RootCAs: roots, ServerName: request.ServerName, MinVersion: tls.VersionTLS12}}
	connection, err := dialer.DialContext(ctx, "tcp", request.Address)
	if err != nil {
		return nil, err
	}
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(2 * time.Second))
	random := make([]byte, 16)
	if _, err = rand.Read(random); err != nil {
		return nil, err
	}
	rejectedUser := fmt.Sprintf("cnix-unissued-probe-%x", random)
	probe := wire.Request{Version: wire.Version1, Cmd: wire.CmdConnect, Features: []wire.Feature{&wire.UserAuthFeature{Username: rejectedUser, Password: rejectedUser}}}
	if _, err = probe.WriteTo(connection); err != nil {
		return nil, err
	}
	var response wire.Response
	if _, err = response.ReadFrom(connection); err != nil {
		return nil, err
	}
	if response.Version != wire.Version1 || response.Status != wire.StatusUnauthorized {
		return nil, errors.New("shared relay did not reject unissued credentials")
	}
	return map[string]interface{}{"tlsVerified": true, "authenticationRequired": true, "elapsedMs": float64(time.Since(start).Microseconds()) / 1000}, nil
}

func cnixProbe(data interface{}) error {
	var request struct {
		Chain  string `json:"chain"`
		Target string `json:"target"`
	}
	if err := decodeCommandData(data, &request); err != nil {
		return err
	}
	value := registry.ChainRegistry().Get(request.Chain)
	if value == nil {
		return errors.New("shared relay chain is unavailable")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	route := value.Route(ctx, "tcp", request.Target)
	if route == nil || len(route.Nodes()) == 0 {
		return errors.New("shared relay route is empty")
	}
	connection, err := route.Dial(ctx, "tcp", request.Target)
	if err != nil {
		return err
	}
	return connection.Close()
}

func cnixCertificate(name string) (map[string]string, error) {
	if !strings.HasPrefix(name, "cnix-") || strings.ContainsAny(name, "/\\ \n") {
		return nil, errors.New("invalid CNIX certificate name")
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}
	template := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: name}, DNSNames: []string{name}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(3, 0, 0), KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	cert, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	private, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, err
	}
	return map[string]string{"certPem": string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert})), "keyPem": string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: private})), "serverName": name}, nil
}

func cnixGrants(data interface{}) error {
	var request struct {
		Service string                     `json:"service"`
		Grants  []relayhandler.SharedGrant `json:"grants"`
	}
	if err := decodeCommandData(data, &request); err != nil {
		return err
	}
	cnixUpdateMu.Lock()
	defer cnixUpdateMu.Unlock()
	var old interface{}
	var handler *config.HandlerConfig
	var previous *config.ServiceConfig
	for _, service := range config.Global().Services {
		if service.Name == request.Service && service.Handler != nil && service.Handler.Metadata["cnix.shared"] == true {
			handler = service.Handler
			previous = service
			old = handler.Metadata["cnix.grants"]
			break
		}
	}
	if handler == nil {
		return errors.New("shared relay service not found")
	}
	if err := relayhandler.SharedGrants(request.Service, request.Grants); err != nil {
		return err
	}
	replacement := *previous
	nextHandler := *handler
	nextHandler.Metadata = make(map[string]any, len(handler.Metadata))
	for key, value := range handler.Metadata {
		nextHandler.Metadata[key] = value
	}
	nextHandler.Metadata["cnix.grants"] = request.Grants
	replacement.Handler = &nextHandler
	swap := func(value *config.ServiceConfig) {
		config.OnUpdate(func(c *config.Config) error {
			next := append([]*config.ServiceConfig(nil), c.Services...)
			for i, service := range next {
				if service.Name == request.Service {
					next[i] = value
				}
			}
			c.Services = next
			return nil
		})
	}
	swap(&replacement)
	if err := saveConfig(); err != nil {
		swap(previous)
		var rollback struct {
			Grants []relayhandler.SharedGrant `json:"grants"`
		}
		_ = decodeCommandData(map[string]interface{}{"grants": old}, &rollback)
		_ = relayhandler.SharedGrants(request.Service, rollback.Grants)
		return err
	}
	return nil
}
