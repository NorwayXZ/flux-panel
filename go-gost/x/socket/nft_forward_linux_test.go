//go:build linux

package socket

import "testing"

func TestParseNFTForwardCounters(t *testing.T) {
	content := []byte(`{"nftables":[{"rule":{"family":"ip","table":"cloudnest_nat","chain":"forward","expr":[{"counter":{"packets":12,"bytes":4096}},{"accept":null}],"comment":"cloudnest:nft-forward:8:tcp:traffic"}}]}`)
	counters := parseNFTForwardCounters(content)
	value, exists := counters["cloudnest:nft-forward:8:tcp:traffic"]
	if !exists || value.Packets != 12 || value.Bytes != 4096 {
		t.Fatalf("unexpected counters: %+v", counters)
	}
}

func TestFindExternalNFTForwardConflict(t *testing.T) {
	content := []byte(`{"nftables":[{"rule":{"family":"ip","table":"external","chain":"prerouting","expr":[{"match":{"op":"==","left":{"payload":{"protocol":"tcp","field":"dport"}},"right":443}},{"dnat":{"addr":"10.0.0.2","port":8443}}]}}]}`)
	conflicts := findExternalNFTForwardConflicts(content, []nftForwardCheck{{Protocol: "tcp", ListenPort: 443}})
	if len(conflicts) != 1 || conflicts[0].Table != "external" {
		t.Fatalf("unexpected conflicts: %+v", conflicts)
	}
}
