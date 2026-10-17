package pingpong

import (
	"encoding/hex"
	"math/big"
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
