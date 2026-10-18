package pingpong

import (
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/mysteriumnetwork/node/eventbus"
	"github.com/mysteriumnetwork/node/identity"
	"github.com/mysteriumnetwork/payments/crypto"
)

func TestSharedConsumerPreserves256BitInvoicesAndValidatesSigner(t *testing.T) {
	ks := identity.NewMockKeystoreWith(identity.MockKeys)
	account := ks.Accounts()[0]
	if err := ks.Unlock(account, ""); err != nil {
		t.Fatal(err)
	}
	id := identity.FromAddress(account.Address.Hex())
	invoice := crypto.Invoice{ChainID: 137, AgreementID: new(big.Int).Lsh(big.NewInt(1), 200), AgreementTotal: big.NewInt(7), TransactorFee: big.NewInt(0), Hashlock: strings.Repeat("01", 32), Provider: common.HexToAddress("0x1234").Hex()}
	channel := identity.FromAddress(common.HexToAddress("0x5678").Hex())
	hermes := common.HexToAddress("0x9999")
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Error("missing authorization")
		}
		var request SharedPaymentRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
			return
		}
		if request.Invoice.AgreementID.Cmp(invoice.AgreementID) != 0 {
			t.Error("lost integer precision")
		}
		msg, err := crypto.CreateExchangeMessage(137, request.Invoice, big.NewInt(107), channel.Address, hermes.Hex(), ks, account.Address)
		if err != nil {
			t.Error(err)
			return
		}
		_ = json.NewEncoder(w).Encode(msg)
	}))
	defer server.Close()
	t.Setenv("MYST_SHARED_BILLING_URL", server.URL)
	t.Setenv("MYST_SHARED_BILLING_TOKEN", "test-token")
	// Trust only this test server; production uses Go's system certificate roots.
	old := http.DefaultTransport
	http.DefaultTransport = server.Client().Transport
	defer func() { http.DefaultTransport = old }()
	sender := &MockPeerExchangeMessageSender{chanToWriteTo: make(chan crypto.ExchangeMessage, 1)}
	payer := NewInvoicePayer(InvoicePayerDeps{Identity: id, HermesAddress: hermes, ChainID: 137,
		ConsumerTotalsStorage: NewConsumerTotalsStorage(eventbus.New()), PeerExchangeMessageSender: sender, EventBus: eventbus.New()})
	payer.channelAddress = channel
	if err := payer.issueSharedExchangeMessage(invoice); err != nil {
		t.Fatal(err)
	}
	message := <-sender.chanToWriteTo
	if message.Promise.Amount.Int64() != 107 {
		t.Fatal("wrong centralized total")
	}
}

func TestSharedConsumerRetriesTheSameInvoiceWhilePanelRestarts(t *testing.T) {
	ks := identity.NewMockKeystoreWith(identity.MockKeys)
	account := ks.Accounts()[0]
	if err := ks.Unlock(account, ""); err != nil {
		t.Fatal(err)
	}
	id := identity.FromAddress(account.Address.Hex())
	invoice := crypto.Invoice{ChainID: 137, AgreementID: big.NewInt(9), AgreementTotal: big.NewInt(7), TransactorFee: big.NewInt(0), Hashlock: strings.Repeat("02", 32), Provider: common.HexToAddress("0x1234").Hex()}
	channel := identity.FromAddress(common.HexToAddress("0x5678").Hex())
	hermes := common.HexToAddress("0x9999")
	var mu sync.Mutex
	hashlocks := []string{}
	// The panel proxy answers 502 while the backend restarts, then the signer replies.
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request SharedPaymentRequest
		_ = json.NewDecoder(r.Body).Decode(&request)
		mu.Lock()
		hashlocks = append(hashlocks, request.Invoice.Hashlock)
		attempt := len(hashlocks)
		mu.Unlock()
		switch attempt {
		case 1, 2:
			w.WriteHeader(http.StatusBadGateway)
			return
		case 3:
			w.WriteHeader(http.StatusConflict)
			return
		}
		msg, err := crypto.CreateExchangeMessage(137, request.Invoice, big.NewInt(7), channel.Address, hermes.Hex(), ks, account.Address)
		if err != nil {
			t.Error(err)
			return
		}
		_ = json.NewEncoder(w).Encode(msg)
	}))
	defer server.Close()
	t.Setenv("MYST_SHARED_BILLING_URL", server.URL)
	t.Setenv("MYST_SHARED_BILLING_TOKEN", "test-token")
	old := http.DefaultTransport
	http.DefaultTransport = server.Client().Transport
	defer func() { http.DefaultTransport = old }()
	oldDelay := sharedPaymentRetryDelay
	sharedPaymentRetryDelay = 10 * time.Millisecond
	defer func() { sharedPaymentRetryDelay = oldDelay }()
	sender := &MockPeerExchangeMessageSender{chanToWriteTo: make(chan crypto.ExchangeMessage, 1)}
	payer := NewInvoicePayer(InvoicePayerDeps{Identity: id, HermesAddress: hermes, ChainID: 137,
		ConsumerTotalsStorage: NewConsumerTotalsStorage(eventbus.New()), PeerExchangeMessageSender: sender, EventBus: eventbus.New()})
	payer.channelAddress = channel
	if err := payer.issueSharedExchangeMessage(invoice); err != nil {
		t.Fatal(err)
	}
	<-sender.chanToWriteTo
	if len(hashlocks) != 4 {
		t.Fatalf("attempts %d", len(hashlocks))
	}
	for _, h := range hashlocks {
		if h != invoice.Hashlock {
			t.Fatal("a retry must repeat the same invoice")
		}
	}
}

func TestSharedConsumerStopsOnRejectionAndAfterTheWindow(t *testing.T) {
	calls := 0
	status := http.StatusUnauthorized
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(status)
	}))
	defer server.Close()
	t.Setenv("MYST_SHARED_BILLING_URL", server.URL)
	t.Setenv("MYST_SHARED_BILLING_TOKEN", "test-token")
	old := http.DefaultTransport
	http.DefaultTransport = server.Client().Transport
	defer func() { http.DefaultTransport = old }()
	oldDelay, oldWindow := sharedPaymentRetryDelay, sharedPaymentRetryWindow
	sharedPaymentRetryDelay, sharedPaymentRetryWindow = 10*time.Millisecond, 200*time.Millisecond
	defer func() { sharedPaymentRetryDelay, sharedPaymentRetryWindow = oldDelay, oldWindow }()
	payer := NewInvoicePayer(InvoicePayerDeps{Identity: identity.FromAddress(common.HexToAddress("0x1").Hex()), HermesAddress: common.HexToAddress("0x9999"), ChainID: 137,
		ConsumerTotalsStorage: NewConsumerTotalsStorage(eventbus.New()), EventBus: eventbus.New()})
	payer.channelAddress = identity.FromAddress(common.HexToAddress("0x5678").Hex())
	invoice := crypto.Invoice{ChainID: 137, AgreementID: big.NewInt(1), AgreementTotal: big.NewInt(1), TransactorFee: big.NewInt(0), Hashlock: strings.Repeat("03", 32)}
	if err := payer.issueSharedExchangeMessage(invoice); err == nil || !strings.Contains(err.Error(), "HTTP 401") || calls != 1 {
		t.Fatalf("an unauthorized node must not retry: %v after %d calls", err, calls)
	}
	status, calls = http.StatusServiceUnavailable, 0
	started := time.Now()
	if err := payer.issueSharedExchangeMessage(invoice); err == nil || !strings.Contains(err.Error(), "timed out") || calls < 2 {
		t.Fatalf("an unavailable panel must be retried until the window ends: %v after %d calls", err, calls)
	}
	if time.Since(started) > 2*time.Second {
		t.Fatal("retries outlived the window")
	}
}
