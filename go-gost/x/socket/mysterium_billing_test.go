package socket

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMysteriumBillingEnvCannotInheritAnotherSigner(t *testing.T) {
	t.Setenv("MYST_SHARED_SIGNER_TOKEN", "unrelated-secret")
	t.Setenv("MYST_SHARED_BILLING_URL", "https://wrong.example/pay")
	m := &mysteriumManager{directory: t.TempDir()}
	env, err := m.billingEnv()
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range env {
		if strings.HasPrefix(item, "MYST_SHARED_") {
			t.Fatal("inherited shared billing configuration")
		}
	}
	cfg := mysteriumBillingConfig{Role: "signer", Consumer: "0x1234", Token: strings.Repeat("a", 64), BinarySHA256: strings.Repeat("0", 64)}
	raw, _ := json.Marshal(cfg)
	if err := os.WriteFile(filepath.Join(m.directory, "shared-billing.json"), raw, 0600); err != nil {
		t.Fatal(err)
	}
	env, err = m.billingEnv()
	if err != nil {
		t.Fatal(err)
	}
	values := map[string]string{}
	for _, item := range env {
		parts := strings.SplitN(item, "=", 2)
		values[parts[0]] = parts[1]
	}
	if values["MYST_SHARED_BILLING_URL"] != "http://127.0.0.1:4451/pay" || values["MYST_SHARED_SIGNER_TOKEN"] != cfg.Token {
		t.Fatal("signer must also use coordinated payments")
	}
}

func TestMysteriumBillingBinaryChecksum(t *testing.T) {
	path := filepath.Join(t.TempDir(), "myst")
	if err := os.WriteFile(path, []byte("abc"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := verifyMysteriumBinary(path, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"); err != nil {
		t.Fatal(err)
	}
	if err := verifyMysteriumBinary(path, strings.Repeat("0", 64)); err == nil {
		t.Fatal("accepted altered client")
	}
}

func TestMysteriumBillingConfigRejectsInvalidInputsBeforeMutation(t *testing.T) {
	m := &mysteriumManager{directory: t.TempDir()}
	for _, request := range []mysteriumBillingRequest{{Role: "unknown"}, {Role: "consumer", Consumer: "0x" + strings.Repeat("1", 40), Endpoint: "http://example.com", Token: strings.Repeat("a", 64), BinarySHA256: strings.Repeat("0", 64)}} {
		if _, err := m.configureBilling(request); err == nil {
			t.Fatal("accepted invalid config")
		}
	}
	if _, err := os.Stat(filepath.Join(m.directory, "shared-billing.json")); !os.IsNotExist(err) {
		t.Fatal("invalid config mutated node")
	}
}
