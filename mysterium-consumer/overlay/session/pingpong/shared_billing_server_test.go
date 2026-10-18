package pingpong

import (
	"encoding/hex"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/mysteriumnetwork/node/identity"
	"github.com/mysteriumnetwork/payments/crypto"
	bolt "go.etcd.io/bbolt"
)

type sharedInfoMock struct {
	latest *crypto.ExchangeMessage
	fail   bool
}

func (m *sharedInfoMock) GetConsumerData(int64, string, time.Duration) (HermesUserInfo, error) {
	latest := LatestPromise{Amount: big.NewInt(0)}
	if m.latest != nil {
		p := m.latest.Promise
		latest = LatestPromise{ChainID: p.ChainID, ChannelID: hex.EncodeToString(p.ChannelID), Amount: p.Amount, Fee: p.Fee, Hashlock: hex.EncodeToString(p.Hashlock), Signature: hex.EncodeToString(p.Signature)}
	}
	return HermesUserInfo{Balance: big.NewInt(10000), Settled: big.NewInt(0), LatestPromise: latest}, nil
}

func TestSharedSignerPersistenceAndPendingGate(t *testing.T) {
	ks := identity.NewMockKeystoreWith(identity.MockKeys)
	account := ks.Accounts()[0]
	if err := ks.Unlock(account, ""); err != nil {
		t.Fatal(err)
	}
	addr := &mockAddressProvider{}
	path := filepath.Join(t.TempDir(), "billing.db")
	db, err := bolt.Open(path, 0600, nil)
	if err != nil {
		t.Fatal(err)
	}
	info := &sharedInfoMock{}
	s := &SharedBillingServer{db: db, signer: ks, info: info, addresses: addr, consumer: account.Address, chain: 137}
	channel, err := addr.GetActiveChannelAddress(137, account.Address)
	if err != nil {
		t.Fatal(err)
	}
	hermes, err := addr.GetActiveHermes(137)
	if err != nil {
		t.Fatal(err)
	}
	req := SharedPaymentRequest{Consumer: account.Address.Hex(), Channel: channel.Hex(), Hermes: hermes.Hex(), Invoice: crypto.Invoice{
		ChainID: 137, Provider: common.HexToAddress("0x1234").Hex(), AgreementID: big.NewInt(1), AgreementTotal: big.NewInt(7), TransactorFee: big.NewInt(0), Hashlock: strings.Repeat("01", 32)}}
	first, err := s.pay(req)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := first.RecoverConsumerIdentity()
	if err != nil || signer != account.Address {
		t.Fatal("invalid central signature", err)
	}
	if first.Promise.Amount.Int64() != 7 {
		t.Fatal(first.Promise.Amount)
	}
	// Concurrent replays must return the exact durable signature, without billing twice.
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			msg, err := s.pay(req)
			if err != nil || msg.Signature != first.Signature {
				t.Error("replay failed", err)
			}
		}()
	}
	wg.Wait()
	db.Close()
	db, err = bolt.Open(path, 0600, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s.db = db
	replay, err := s.pay(req)
	if err != nil || replay.Signature != first.Signature {
		t.Fatal("restart replay failed", err)
	}
	other := req
	other.Invoice.AgreementID = big.NewInt(2)
	other.Invoice.Hashlock = strings.Repeat("02", 32)
	if _, err := s.pay(other); err == nil || !strings.Contains(err.Error(), "awaiting Hermes") {
		t.Fatal("unconfirmed promise must block another node", err)
	}
	info.latest = first
	second, err := s.pay(other)
	if err != nil || second.Promise.Amount.Int64() != 14 {
		t.Fatal("confirmed previous payment should unblock next node", err)
	}
	changed := req
	changed.Invoice.AgreementTotal = big.NewInt(8)
	if _, err := s.pay(changed); err == nil {
		t.Fatal("changed replay accepted")
	}
	wrong := req
	wrong.Consumer = common.HexToAddress("0x9999").Hex()
	if _, err := s.pay(wrong); err == nil {
		t.Fatal("wrong consumer accepted")
	}
}

func TestSharedSignerMovesPastALostPayment(t *testing.T) {
	ks := identity.NewMockKeystoreWith(identity.MockKeys)
	account := ks.Accounts()[0]
	if err := ks.Unlock(account, ""); err != nil {
		t.Fatal(err)
	}
	addr := &mockAddressProvider{}
	db, err := bolt.Open(filepath.Join(t.TempDir(), "billing.db"), 0600, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Unix(1_800_000_000, 0)
	sharedNow = func() time.Time { return now }
	defer func() { sharedNow = time.Now }()
	info := &sharedInfoMock{}
	s := &SharedBillingServer{db: db, signer: ks, info: info, addresses: addr, consumer: account.Address, chain: 137}
	channel, _ := addr.GetActiveChannelAddress(137, account.Address)
	hermes, _ := addr.GetActiveHermes(137)
	req := SharedPaymentRequest{Consumer: account.Address.Hex(), Channel: channel.Hex(), Hermes: hermes.Hex(), Invoice: crypto.Invoice{
		ChainID: 137, Provider: common.HexToAddress("0x1234").Hex(), AgreementID: big.NewInt(1), AgreementTotal: big.NewInt(7), TransactorFee: big.NewInt(0), Hashlock: strings.Repeat("01", 32)}}
	if _, err := s.pay(req); err != nil {
		t.Fatal(err)
	}
	// The signed payment never reaches a provider, so Hermes never reports it.
	other := req
	other.Invoice.Provider = common.HexToAddress("0x5678").Hex()
	other.Invoice.AgreementTotal = big.NewInt(5)
	other.Invoice.Hashlock = strings.Repeat("02", 32)
	now = now.Add(2 * time.Minute)
	if _, err := s.pay(other); err == nil || !strings.Contains(err.Error(), "awaiting Hermes") {
		t.Fatal("a recent payment must still block", err)
	}
	now = now.Add(2 * time.Minute)
	next, err := s.pay(other)
	if err != nil {
		t.Fatal("an overdue payment must not block the pool forever", err)
	}
	if next.Promise.Amount.Int64() != 12 {
		t.Fatalf("next promise %d must continue above the lost one", next.Promise.Amount.Int64())
	}
	// The new payment is pending again until Hermes sees it.
	third := other
	third.Invoice.AgreementID = big.NewInt(3)
	third.Invoice.Hashlock = strings.Repeat("03", 32)
	if _, err := s.pay(third); err == nil || !strings.Contains(err.Error(), "awaiting Hermes") {
		t.Fatal("the new payment must be confirmed before the next", err)
	}
}

func TestSharedSignerTreatsOldLedgerPendingAsOverdue(t *testing.T) {
	state := sharedState{}
	if err := json.Unmarshal([]byte(`{"Total":7,"Pending":null}`), &state); err != nil || state.PendingAt != 0 {
		t.Fatal("older ledgers have no signing time", err)
	}
	if sharedNow().Sub(time.Unix(state.PendingAt, 0)) < sharedPendingTimeout {
		t.Fatal("a pending payment without a time must count as overdue")
	}
}

func TestSharedSignerRequiresLoopbackSecret(t *testing.T) {
	s := &SharedBillingServer{token: "expected-secret"}
	for _, header := range []string{"", "Bearer wrong"} {
		request := httptest.NewRequest(http.MethodPost, "/pay", strings.NewReader("{}"))
		request.Header.Set("Authorization", header)
		response := httptest.NewRecorder()
		s.ServeHTTP(response, request)
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("unauthorized request status=%d", response.Code)
		}
	}
}

// A slow Hermes query must not monopolize the ledger writer, even when the
// HTTP caller has already timed out and retries arrive from other consumers.
type sharedBlockingInfo struct {
	entered chan struct{}
	release chan struct{}
}

func (m *sharedBlockingInfo) GetConsumerData(int64, string, time.Duration) (HermesUserInfo, error) {
	close(m.entered)
	<-m.release
	return HermesUserInfo{Balance: big.NewInt(10000), Settled: big.NewInt(0), LatestPromise: LatestPromise{Amount: big.NewInt(0)}}, nil
}

func TestSharedHermesQueryDoesNotHoldLedgerWriter(t *testing.T) {
	ks := identity.NewMockKeystoreWith(identity.MockKeys)
	account := ks.Accounts()[0]
	if err := ks.Unlock(account, ""); err != nil {
		t.Fatal(err)
	}
	db, err := bolt.Open(filepath.Join(t.TempDir(), "billing.db"), 0600, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	info := &sharedBlockingInfo{entered: make(chan struct{}), release: make(chan struct{})}
	addr := &mockAddressProvider{}
	channel, _ := addr.GetActiveChannelAddress(137, account.Address)
	hermes, _ := addr.GetActiveHermes(137)
	s := &SharedBillingServer{db: db, signer: ks, info: info, addresses: addr, consumer: account.Address, chain: 137}
	req := SharedPaymentRequest{Consumer: account.Address.Hex(), Channel: channel.Hex(), Hermes: hermes.Hex(), Invoice: crypto.Invoice{
		ChainID: 137, Provider: common.HexToAddress("0x1234").Hex(), AgreementID: big.NewInt(1), AgreementTotal: big.NewInt(7), TransactorFee: big.NewInt(0), Hashlock: strings.Repeat("01", 32)}}
	finished := make(chan error, 1)
	go func() { _, err := s.pay(req); finished <- err }()
	<-info.entered
	writer := make(chan error, 1)
	go func() { writer <- db.Update(func(*bolt.Tx) error { return nil }) }()
	select {
	case err := <-writer:
		if err != nil {
			t.Error(err)
		}
	case <-time.After(time.Second):
		t.Error("Hermes network request holds ledger writer")
	}
	close(info.release)
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
}
