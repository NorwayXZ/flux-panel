package socket

import (
	"bytes"
	"sync"

	"github.com/go-gost/x/config"
)

var configSaveMu sync.Mutex

func saveConfig() error {
	configSaveMu.Lock()
	defer configSaveMu.Unlock()
	var data bytes.Buffer
	if err := config.Global().Write(&data, "json"); err != nil {
		return err
	}
	return writePrivateFile("gost.json", data.Bytes())
}
