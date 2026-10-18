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

var (
	sharedPaymentRetryWindow    = 100 * time.Second
	sharedPaymentAttemptTimeout = 40 * time.Second
	sharedPaymentRetryDelay     = time.Second
)

// sharedPaymentRetryable covers a signer still awaiting Hermes (409) and a
// panel that is restarting or briefly unreachable (5xx from its proxy).
func sharedPaymentRetryable(status int) bool {
	return status == http.StatusConflict || status == http.StatusTooManyRequests || status >= 500
}

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
	// Providers end a session after about two minutes without payment. Retrying
	// the same invoice for up to 100 s rides out a panel restart: the signer
	// returns the identical signed message for a repeated invoice, so a payment
	// signed just before the restart is delivered instead of lost.
	ctx, cancel := context.WithTimeout(context.Background(), sharedPaymentRetryWindow)
	defer cancel()
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	var resp *http.Response
	var lastErr error
	for {
		attempt, cancelAttempt := context.WithTimeout(ctx, sharedPaymentAttemptTimeout)
		req, err := http.NewRequestWithContext(attempt, http.MethodPost, endpoint, bytes.NewReader(payload))
		if err != nil {
			cancelAttempt()
			return err
		}
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("Content-Type", "application/json")
		resp, err = client.Do(req)
		if err == nil && resp.StatusCode == http.StatusOK {
			// The body is read below; the attempt context must outlive it.
			defer cancelAttempt()
			break
		}
		cancelAttempt()
		if err != nil {
			lastErr = fmt.Errorf("shared billing unavailable: %w", err)
		} else {
			resp.Body.Close()
			if !sharedPaymentRetryable(resp.StatusCode) {
				return fmt.Errorf("shared billing rejected invoice (HTTP %d)", resp.StatusCode)
			}
			lastErr = fmt.Errorf("shared billing not ready (HTTP %d)", resp.StatusCode)
		}
		select {
		case <-ctx.Done():
			return fmt.Errorf("shared payment confirmation timed out: %v", lastErr)
		case <-time.After(sharedPaymentRetryDelay):
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
