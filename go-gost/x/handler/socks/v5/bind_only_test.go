package v5

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/gosocks5"
	xlogger "github.com/go-gost/x/logger"
	xmetadata "github.com/go-gost/x/metadata"
)

func TestBindOnlyRejectsConnect(t *testing.T) {
	h := NewHandler(handler.LoggerOption(xlogger.NewLogger()))
	if err := h.Init(xmetadata.NewMetadata(map[string]any{
		"bind":     true,
		"bindOnly": true,
		"notls":    true,
	})); err != nil {
		t.Fatalf("initialize handler: %v", err)
	}

	server, client := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		done <- h.Handle(context.Background(), server)
		server.Close()
	}()

	_ = client.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := client.Write([]byte{gosocks5.Ver5, 1, gosocks5.MethodNoAuth}); err != nil {
		t.Fatalf("write greeting: %v", err)
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(client, greeting); err != nil {
		t.Fatalf("read greeting: %v", err)
	}
	if greeting[1] != gosocks5.MethodNoAuth {
		t.Fatalf("unexpected auth method: %d", greeting[1])
	}

	request := []byte{gosocks5.Ver5, gosocks5.CmdConnect, 0, gosocks5.AddrIPv4, 127, 0, 0, 1, 0, 80}
	if _, err := client.Write(request); err != nil {
		t.Fatalf("write CONNECT request: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(client, reply); err != nil {
		t.Fatalf("read CONNECT reply: %v", err)
	}
	if reply[1] != gosocks5.NotAllowed {
		t.Fatalf("expected NotAllowed, got %d", reply[1])
	}

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("expected handler to reject CONNECT")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("handler did not return")
	}
}
