//go:build !linux

package socket

import "errors"

func launchAgentUpgradeHelper(_, _, _ string) error {
	return errors.New("agent self-update is only available on Linux")
}
