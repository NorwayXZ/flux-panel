//go:build linux

package socket

import (
	"os"
	"testing"
)

func TestVirtualLanLinuxRuntimeLifecycle(t *testing.T) {
	if os.Getenv("CLOUDNEST_RUN_PRIVILEGED_TESTS") != "1" {
		t.Skip("privileged Linux runtime test is disabled")
	}
	manager := newVirtualLanRuntimeManager()
	manager.directory = t.TempDir()
	key, err := manager.prepareKey("vlan-linux-test")
	if err != nil {
		t.Fatal(err)
	}
	_, peerPublic, err := newVirtualLanKeypair()
	if err != nil {
		t.Fatal(err)
	}
	status, err := manager.apply(virtualLanApplyRequest{Name: "vlan-linux-test", InterfaceAddress: "10.254.254.2/24", Peers: []virtualLanPeer{{PublicKey: peerPublic, AllowedIPs: []string{"10.254.254.0/24"}, Endpoint: "127.0.0.1:59999", PersistentKeepalive: 1}}})
	if err != nil {
		t.Fatalf("apply Linux runtime: %v", err)
	}
	if !status.Active || status.InterfaceName == "" || status.PublicKey != key.PublicKey {
		t.Fatalf("unexpected status: %+v", status)
	}
	if err := manager.pause("vlan-linux-test"); err != nil {
		t.Fatalf("pause: %v", err)
	}
	resumed, err := manager.resume("vlan-linux-test")
	if err != nil {
		t.Fatalf("resume: %v", err)
	}
	if !resumed.Active {
		t.Fatalf("runtime did not resume: %+v", resumed)
	}
	if err := manager.delete("vlan-linux-test"); err != nil {
		t.Fatalf("delete: %v", err)
	}
}
