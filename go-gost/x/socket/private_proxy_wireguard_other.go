//go:build !linux

package socket

import "errors"

type wireGuardRuntime struct{}

func (m *privateProxyRuntimeManager) addWireGuard(request privateProxyRuntimeRequest) (privateProxyRuntimeResponse, error) {
	return privateProxyRuntimeResponse{}, errors.New("WireGuard private proxy is supported only on Linux nodes")
}

func (m *privateProxyRuntimeManager) ensureWireGuardRunning(state privateProxyRuntimeState) error {
	return errors.New("WireGuard private proxy is supported only on Linux nodes")
}

func (m *privateProxyRuntimeManager) stopWireGuard(name string) {}

func (m *privateProxyRuntimeManager) stopAllWireGuards() {}
