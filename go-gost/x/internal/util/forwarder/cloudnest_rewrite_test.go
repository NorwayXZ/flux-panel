package forwarder

import "testing"

func TestRewriteCloudNestLocation(t *testing.T) {
	actual := rewriteCloudNestLocation("http://127.0.0.1:54321/abc123/login", "/abc123", "/", "xui.example.com")
	if actual != "https://xui.example.com/login" {
		t.Fatalf("unexpected rewritten location: %s", actual)
	}
}

func TestRewriteCloudNestLocationPreservesExternalRedirect(t *testing.T) {
	actual := rewriteCloudNestLocation("https://login.example.net/oauth", "/abc123", "/", "xui.example.com")
	if actual != "https://login.example.net/oauth" {
		t.Fatalf("external redirect must remain unchanged: %s", actual)
	}
}

func TestRewriteCloudNestCookiePath(t *testing.T) {
	actual := rewriteCloudNestCookiePath("session=abc; Path=/abc123; HttpOnly", "/abc123", "/")
	if actual != "session=abc; Path=/; HttpOnly" {
		t.Fatalf("unexpected cookie: %s", actual)
	}
}
