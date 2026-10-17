// Copyright 2026 Flux Panel contributors. SPDX-License-Identifier: GPL-3.0-or-later
package pingpong

import (
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/mysteriumnetwork/payments/crypto"
	bolt "go.etcd.io/bbolt"
)

type sharedState struct {
	Total   *big.Int
	Pending *crypto.ExchangeMessage
}

type SharedBillingServer struct {
	db        *bolt.DB
	signer    hashSigner
	info      consumerInfoGetter
	addresses addressProvider
	consumer  common.Address
	chain     int64
	token     string
}

// StartSharedBillingServer binds only loopback. The Agent's authenticated command
// channel is the sole remote entry point; the signing key stays in myst's keystore.
func StartSharedBillingServer(signer hashSigner, info consumerInfoGetter, addresses addressProvider, directory string) error {
	token := os.Getenv("MYST_SHARED_SIGNER_TOKEN")
	if token == "" {
		return nil
	}
	consumer := os.Getenv("MYST_SHARED_CONSUMER")
	if !common.IsHexAddress(consumer) {
		return fmt.Errorf("invalid shared consumer")
	}
	db, err := bolt.Open(filepath.Join(directory, "shared-billing.db"), 0600, &bolt.Options{Timeout: time.Second})
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", "127.0.0.1:4451")
	if err != nil {
		db.Close()
		return err
	}
	s := &SharedBillingServer{db: db, signer: signer, info: info, addresses: addresses,
		consumer: common.HexToAddress(consumer), chain: 137, token: token}
	server := &http.Server{Handler: s, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 35 * time.Second, IdleTimeout: 30 * time.Second}
	go func() { _ = server.Serve(listener); _ = db.Close() }()
	return nil
}

func (s *SharedBillingServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if r.Method != http.MethodPost || r.URL.Path != "/pay" {
		http.Error(w, "not found", 404)
		return
	}
	if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+s.token)) != 1 {
		http.Error(w, "unauthorized", 401)
		return
	}
	var request SharedPaymentRequest
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		http.Error(w, "invalid request", 400)
		return
	}
	message, err := s.pay(request)
	if err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	_ = json.NewEncoder(w).Encode(message)
}

func (s *SharedBillingServer) pay(request SharedPaymentRequest) (*crypto.ExchangeMessage, error) {
	in := request.Invoice
	if !common.IsHexAddress(request.Consumer) || common.HexToAddress(request.Consumer) != s.consumer ||
		in.ChainID != s.chain || !common.IsHexAddress(in.Provider) || !common.IsHexAddress(request.Hermes) ||
		!common.IsHexAddress(request.Channel) || in.AgreementID == nil || in.AgreementTotal == nil || in.TransactorFee == nil ||
		in.AgreementID.Sign() < 0 || in.AgreementTotal.Sign() < 0 || in.TransactorFee.Sign() < 0 ||
		in.AgreementID.BitLen() > 256 || in.AgreementTotal.BitLen() > 256 || in.TransactorFee.BitLen() > 256 {
		return nil, fmt.Errorf("invalid shared invoice")
	}
	hashlock, err := hex.DecodeString(in.Hashlock)
	if err != nil || len(hashlock) != 32 {
		return nil, fmt.Errorf("invalid hashlock")
	}
	hermes, err := s.addresses.GetActiveHermes(s.chain)
	if err != nil {
		return nil, err
	}
	channel, err := s.addresses.GetActiveChannelAddress(s.chain, s.consumer)
	if err != nil {
		return nil, err
	}
	if hermes != common.HexToAddress(request.Hermes) || channel != common.HexToAddress(request.Channel) {
		return nil, fmt.Errorf("inactive payment channel")
	}
	var result *crypto.ExchangeMessage
	err = s.db.Update(func(tx *bolt.Tx) error {
		bucket, err := tx.CreateBucketIfNotExists([]byte(fmt.Sprintf("%d:%s:%s:%s", s.chain, s.consumer.Hex(), hermes.Hex(), channel.Hex())))
		if err != nil {
			return err
		}
		agreement := strings.ToLower(in.Provider) + ":" + in.AgreementID.String()
		invoiceKey := []byte("invoice:" + agreement + ":" + in.Hashlock)
		if saved := bucket.Get(invoiceKey); saved != nil {
			var old struct {
				Invoice crypto.Invoice
				Message crypto.ExchangeMessage
			}
			if err := json.Unmarshal(saved, &old); err != nil {
				return err
			}
			before, _ := json.Marshal(old.Invoice)
			after, _ := json.Marshal(in)
			if string(before) != string(after) {
				return fmt.Errorf("invoice replay changed payload")
			}
			result = &old.Message
			return nil
		}
		data, err := s.info.GetConsumerData(s.chain, s.consumer.Hex(), -1)
		if err != nil {
			return fmt.Errorf("cannot reconcile Hermes: %w", err)
		}
		if err := data.LatestPromise.isValid(s.consumer.Hex()); err != nil {
			return err
		}
		latest := data.LatestPromise.Amount
		if latest == nil {
			latest = new(big.Int)
		}
		state := sharedState{Total: new(big.Int)}
		if raw := bucket.Get([]byte("state")); raw != nil {
			if err := json.Unmarshal(raw, &state); err != nil {
				return err
			}
		}
		// Do not issue the next promise until the previous signed one is observed at
		// Hermes. A crashed sender leaves an explicit pending payment, never reuse.
		if state.Pending != nil && state.Pending.Promise.Amount.Sign() > 0 {
			if latest.Cmp(state.Pending.Promise.Amount) < 0 {
				return fmt.Errorf("previous payment awaiting Hermes confirmation")
			}
			if latest.Cmp(state.Pending.Promise.Amount) > 0 {
				return fmt.Errorf("uncoordinated shared identity spending detected")
			}
		}
		if latest.Cmp(state.Total) > 0 {
			state.Total.Set(latest)
		}
		last := new(big.Int)
		if raw := bucket.Get([]byte("agreement:" + agreement)); raw != nil {
			if _, ok := last.SetString(string(raw), 10); !ok {
				return fmt.Errorf("invalid saved agreement")
			}
		}
		delta := new(big.Int).Sub(in.AgreementTotal, last)
		if delta.Sign() < 0 {
			return fmt.Errorf("agreement total decreased")
		}
		amount := new(big.Int).Add(state.Total, delta)
		// Balance and settled are cumulative in Hermes's consumer record.
		limit := new(big.Int)
		if data.Balance != nil {
			limit.Set(data.Balance)
		}
		if data.Settled != nil {
			limit.Add(limit, data.Settled)
		}
		if amount.Cmp(limit) > 0 {
			return fmt.Errorf("shared balance insufficient")
		}
		msg, err := crypto.CreateExchangeMessage(s.chain, in, amount, channel.Hex()[2:], hermes.Hex(), s.signer, s.consumer)
		if err != nil {
			return err
		}
		record, err := json.Marshal(struct {
			Invoice crypto.Invoice
			Message *crypto.ExchangeMessage
		}{in, msg})
		if err != nil {
			return err
		}
		if err := bucket.Put(invoiceKey, record); err != nil {
			return err
		}
		if err := bucket.Put([]byte("agreement:"+agreement), []byte(in.AgreementTotal.String())); err != nil {
			return err
		}
		state.Total = amount
		state.Pending = msg
		raw, err := json.Marshal(state)
		if err != nil {
			return err
		}
		if err := bucket.Put([]byte("state"), raw); err != nil {
			return err
		}
		result = msg
		return nil
	})
	return result, err
}
