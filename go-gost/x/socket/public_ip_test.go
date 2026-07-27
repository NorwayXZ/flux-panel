package socket

import "testing"

func TestPublicIPRejectsUnknownFamily(t *testing.T) {
	reporter := &WebSocketReporter{}
	if _, err := reporter.handlePublicIPQuery(map[string]interface{}{"family": "auto"}); err == nil {
		t.Fatal("expected unsupported family error")
	}
}
