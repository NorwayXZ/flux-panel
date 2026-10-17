package pingpong

import (
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

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
