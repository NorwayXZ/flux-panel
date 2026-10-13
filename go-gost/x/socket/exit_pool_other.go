//go:build !linux

package socket

import (
	"context"
	"errors"
)

type unsupportedExitRunner struct{}

func newExitRunner() exitRunner { return unsupportedExitRunner{} }

func (unsupportedExitRunner) prepare() (string, error) {
	return "", errors.New("residential exits are supported only on Linux nodes")
}

func (unsupportedExitRunner) cleanup(string) {}

func (unsupportedExitRunner) connect(context.Context, string, string, exitMemberSpec) (exitSession, error) {
	return nil, errors.New("residential exits are supported only on Linux nodes")
}
