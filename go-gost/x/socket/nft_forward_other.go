//go:build !linux

package socket

import "errors"

type unsupportedNFTForwardSystem struct{}

func (s *unsupportedNFTForwardSystem) supported() bool { return false }

func newNFTForwardSystem() nftForwardSystem {
	return &unsupportedNFTForwardSystem{}
}

func (s *unsupportedNFTForwardSystem) preflight([]nftForwardCheck) (nftForwardPreflightResponse, error) {
	return nftForwardPreflightResponse{Supported: false, Available: false}, errors.New("nftables forwarding is available only on Linux nodes")
}

func (s *unsupportedNFTForwardSystem) tableExists() (bool, error) {
	return false, nil
}

func (s *unsupportedNFTForwardSystem) apply(string) error {
	return errors.New("nftables forwarding is available only on Linux nodes")
}

func (s *unsupportedNFTForwardSystem) readCounters() (bool, map[string]nftForwardCounter, error) {
	return false, map[string]nftForwardCounter{}, nil
}

func (s *unsupportedNFTForwardSystem) enableIPv4Forwarding() error {
	return errors.New("nftables forwarding is available only on Linux nodes")
}
