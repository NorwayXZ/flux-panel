package pingpong

import (
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/ethereum/go-ethereum/common"
	"github.com/mysteriumnetwork/node/eventbus"
	"github.com/mysteriumnetwork/node/identity"
	"github.com/mysteriumnetwork/payments/crypto"
)

// The worker is a separate OS process, representing an independent stock myst
// consumer with its own in-memory accounting state. No real identity or network
// account is used; the test calls the production invoice calculation directly.
func TestAuditSharedIdentityWorker(t *testing.T) {
	output := os.Getenv("MYST_SHARED_AUDIT_OUTPUT")
	if output == "" {
		t.Skip("audit worker only")
	}
	cost, err := strconv.ParseInt(os.Getenv("MYST_SHARED_AUDIT_COST"), 10, 64)
	if err != nil {
		t.Fatal(err)
	}
	// Use the upstream repository's public, unfunded test key in both workers.
	ks := identity.NewMockKeystoreWith(identity.MockKeys)
	account := ks.Accounts()[0]
	if err := ks.Unlock(account, ""); err != nil {
		t.Fatal(err)
	}
	id := identity.FromAddress(account.Address.Hex())
	hermes := common.HexToAddress("0x2222222222222222222222222222222222222222")
	totals := NewConsumerTotalsStorage(eventbus.New())
	if err := totals.Store(137, id, hermes, big.NewInt(100)); err != nil {
		t.Fatal(err)
	}
	sender := &MockPeerExchangeMessageSender{chanToWriteTo: make(chan crypto.ExchangeMessage, 1)}
	payer := NewInvoicePayer(InvoicePayerDeps{ConsumerTotalsStorage: totals, Identity: id, HermesAddress: hermes, ChainID: 137, Ks: ks, PeerExchangeMessageSender: sender, EventBus: eventbus.New()})
	payer.channelAddress = identity.FromAddress("0x4444444444444444444444444444444444444444")
	invoice := crypto.Invoice{AgreementID: big.NewInt(cost), AgreementTotal: big.NewInt(cost), TransactorFee: big.NewInt(0), Hashlock: fmt.Sprintf("0x%064x", cost), ChainID: 137, Provider: common.BigToAddress(big.NewInt(cost)).Hex()}
	promise, _, err := payer.calculateAmountToPromise(invoice)
	if err != nil {
		t.Fatal(err)
	}
	if err := payer.issueExchangeMessage(invoice); err != nil {
		t.Fatal(err)
	}
	message := <-sender.chanToWriteTo
	recovered, err := message.RecoverConsumerIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if recovered != account.Address || message.Promise.Amount.Cmp(promise) != 0 {
		t.Fatal("signature or signed promise did not match")
	}
	local, err := totals.Get(137, id, hermes)
	if err != nil {
		t.Fatal(err)
	}
	data, _ := json.Marshal(map[string]int64{"initial": 100, "bill": cost, "promise": promise.Int64(), "local_total": local.Int64(), "signature_verified": 1})
	if err := os.WriteFile(output, data, 0600); err != nil {
		t.Fatal(err)
	}
}

func TestAuditSharedIdentityTwoProcesses(t *testing.T) {
	executable, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	files := []string{filepath.Join(t.TempDir(), "A.json"), filepath.Join(t.TempDir(), "B.json")}
	commands := make([]*exec.Cmd, 2)
	for i, cost := range []string{"7", "11"} {
		commands[i] = exec.Command(executable, "-test.run=^TestAuditSharedIdentityWorker$", "-test.v")
		commands[i].Env = append(os.Environ(), "MYST_SHARED_AUDIT_COST="+cost, "MYST_SHARED_AUDIT_OUTPUT="+files[i])
		if err := commands[i].Start(); err != nil {
			t.Fatal(err)
		}
	}
	for _, cmd := range commands {
		if err := cmd.Wait(); err != nil {
			t.Fatal(err)
		}
	}
	var results [2]map[string]int64
	for i, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal(data, &results[i]); err != nil {
			t.Fatal(err)
		}
	}
	if results[0]["promise"] != 107 || results[1]["promise"] != 111 {
		t.Fatalf("unexpected accounting: %v", results)
	}
	expected := int64(100 + 7 + 11)
	maxPromise := max(results[0]["promise"], results[1]["promise"])
	if maxPromise >= expected {
		t.Fatal("expected independent clients to overlap the cumulative payment range")
	}
	t.Logf("REPRODUCED: independent process A=%v; B=%v", results[0], results[1])
	t.Logf("Combined obligations require cumulative total=%d, but highest cryptographically signed payment total=%d; overlapping/unrepresented amount=%d (synthetic ledger units, not real MYST)", expected, maxPromise, expected-maxPromise)
}

func TestAuditSharedIdentityProviderTreatsPaymentConflictsAsFatal(t *testing.T) {
	tracker := &InvoiceTracker{}
	for _, failure := range []error{ErrHermesPaymentValueTooLow, ErrHermesPromiseValueTooLow, ErrHermesOverspend} {
		if err := tracker.handleHermesError(failure); !errors.Is(err, failure) {
			t.Fatalf("expected fatal session error %v, got %v", failure, err)
		}
		t.Logf("Production provider returns a fatal session error for: %v", failure)
	}
}

func TestAuditSharedIdentitySerialResynchronization(t *testing.T) {
	id := identity.FromAddress("0x1111111111111111111111111111111111111111")
	hermes := common.HexToAddress("0x2222222222222222222222222222222222222222")
	totals := NewConsumerTotalsStorage(eventbus.New())
	// A coordinator has already applied A's bill of 7 to the old total of 100.
	if err := totals.Store(137, id, hermes, big.NewInt(107)); err != nil {
		t.Fatal(err)
	}
	payer := NewInvoicePayer(InvoicePayerDeps{ConsumerTotalsStorage: totals, Identity: id, HermesAddress: hermes, ChainID: 137})
	promise, _, err := payer.calculateAmountToPromise(crypto.Invoice{AgreementID: big.NewInt(11), AgreementTotal: big.NewInt(11)})
	if err != nil {
		t.Fatal(err)
	}
	if promise.Int64() != 118 {
		t.Fatalf("coordinated total=%v", promise)
	}
	t.Log("Control: fresh, serialized accounting reaches 118; stock independent daemons have no such cross-machine coordinator")
}
