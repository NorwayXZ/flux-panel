// Copyright 2026 Flux Panel contributors. SPDX-License-Identifier: GPL-3.0-or-later
package pingpong

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/mysteriumnetwork/payments/crypto"
)

type SharedPaymentRequest struct {
	Consumer string
	Channel  string
	Hermes   string
	Invoice  crypto.Invoice
}

// SharedBillingEnabled is deliberately explicit: failure never falls back to
// local signing, which would create overlapping promises for the shared key.
func SharedBillingEnabled() bool { return os.Getenv("MYST_SHARED_BILLING_URL") != "" }

func (ip *InvoicePayer) issueSharedExchangeMessage(invoice crypto.Invoice) error {
	endpoint := os.Getenv("MYST_SHARED_BILLING_URL")
	u, err := url.Parse(endpoint)
	if err != nil || (u.Scheme != "https" && endpoint != "http://127.0.0.1:4451/pay") || u.Host == "" || u.User != nil {
		return fmt.Errorf("shared billing requires an HTTPS endpoint")
	}
	token := os.Getenv("MYST_SHARED_BILLING_TOKEN")
	if token == "" {
		return fmt.Errorf("shared billing token missing")
	}
	payload, err := json.Marshal(SharedPaymentRequest{Consumer: ip.deps.Identity.Address,
		Channel: ip.channelAddress.Address, Hermes: ip.deps.HermesAddress.Hex(), Invoice: invoice})
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	var resp *http.Response
	for {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
		if err != nil {
			return err
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		resp, err = client.Do(req)
		if err != nil {
			return fmt.Errorf("shared billing unavailable: %w", err)
		}
		if resp.StatusCode == http.StatusOK {
			break
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusConflict {
			return fmt.Errorf("shared billing rejected invoice (HTTP %d)", resp.StatusCode)
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("shared payment confirmation timed out")
		case <-time.After(500 * time.Millisecond):
		}
	}
	defer resp.Body.Close()
	var message crypto.ExchangeMessage
	if err := json.NewDecoder(io.LimitReader(resp.Body, 32768)).Decode(&message); err != nil {
		return err
	}
	if message.AgreementID == nil || message.AgreementTotal == nil || message.Promise.Amount == nil || message.Promise.Fee == nil {
		return fmt.Errorf("incomplete shared payment response")
	}
	signer, err := message.RecoverConsumerIdentity()
	if err != nil || signer != ip.deps.Identity.ToCommonAddress() || message.ChainID != invoice.ChainID ||
		message.AgreementID == nil || message.AgreementTotal == nil || message.Promise.Amount == nil ||
		message.AgreementID.Cmp(invoice.AgreementID) != 0 || message.AgreementTotal.Cmp(invoice.AgreementTotal) != 0 ||
		!bytes.Equal(message.Promise.ChannelID, common.FromHex(ip.channelAddress.Address)) ||
		common.HexToAddress(message.Provider) != common.HexToAddress(invoice.Provider) ||
		common.HexToAddress(message.HermesID) != ip.deps.HermesAddress ||
		!bytes.Equal(message.Promise.Hashlock, common.FromHex(invoice.Hashlock)) ||
		message.Promise.ChainID != invoice.ChainID || message.Promise.Fee == nil || message.Promise.Fee.Cmp(invoice.TransactorFee) != 0 ||
		!message.Promise.IsPromiseValid(signer) {
		return fmt.Errorf("invalid shared payment response")
	}
	if err := ip.deps.PeerExchangeMessageSender.Send(message); err != nil {
		return err
	}
	if err := ip.deps.ConsumerTotalsStorage.Store(ip.chainID(), ip.deps.Identity, ip.deps.HermesAddress, message.Promise.Amount); err != nil {
		return err
	}
	ip.publishInvoicePayedEvent(invoice)
	return nil
}
