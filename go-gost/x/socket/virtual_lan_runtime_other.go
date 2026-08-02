//go:build !linux

package socket

import "errors"

type virtualLanPlatformRuntime struct{}

func startVirtualLanPlatform(state virtualLanState) (*virtualLanPlatformRuntime, error) {
	return nil, errors.New("virtual LAN is currently supported only on Linux Agent and Connector")
}
func stopVirtualLanPlatform(current *virtualLanPlatformRuntime) {}
func virtualLanPlatformStatus(current *virtualLanPlatformRuntime, state virtualLanState) (virtualLanStatusResponse, error) {
	return virtualLanStatusResponse{}, errors.New("virtual LAN is currently supported only on Linux Agent and Connector")
}
