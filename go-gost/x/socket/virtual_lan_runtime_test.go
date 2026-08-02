package socket

import (
	"os"
	"runtime"
	"testing"
)

func TestVirtualLanKeyPersists(t *testing.T) {
	manager := newVirtualLanRuntimeManager()
	manager.directory = t.TempDir()
	first, err := manager.prepareKey("vlan-test")
	if err != nil {
		if runtime.GOOS != "linux" {
			t.Skip("key preparation intentionally requires Linux")
		}
		t.Fatal(err)
	}
	second, err := manager.prepareKey("vlan-test")
	if err != nil {
		t.Fatal(err)
	}
	if first.PublicKey == "" || first.PublicKey != second.PublicKey {
		t.Fatalf("key did not persist: %+v %+v", first, second)
	}
	if info, err := os.Stat(manager.statePath("vlan-test")); err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("state permissions: %v %v", info, err)
	}
}

func TestVirtualLanRequestValidation(t *testing.T) {
	_, publicKey, err := newVirtualLanKeypair()
	if err != nil {
		t.Fatal(err)
	}
	valid := virtualLanApplyRequest{Name: "vlan-1", InterfaceAddress: "10.88.0.2/24", Peers: []virtualLanPeer{{PublicKey: publicKey, AllowedIPs: []string{"10.88.0.0/24"}, Endpoint: "203.0.113.1:51820", PersistentKeepalive: 25}}}
	if err := validateVirtualLanRequest(&valid); err != nil {
		t.Fatalf("valid config rejected: %v", err)
	}
	invalid := valid
	invalid.Peers = []virtualLanPeer{{PublicKey: publicKey, AllowedIPs: []string{"10.99.0.1/32"}}}
	if err := validateVirtualLanRequest(&invalid); err == nil {
		t.Fatal("route outside virtual network was accepted")
	}
}
