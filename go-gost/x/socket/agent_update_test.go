package socket

import (
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestAgentUpdateValidation(t *testing.T) {
	if !validAgentVersion("2.13.0") || validAgentVersion("2.13.0-beta") || validAgentVersion("latest") {
		t.Fatal("version validation returned an unexpected result")
	}
	if !validAgentUpdateToken("12345678-1234-1234-1234-123456789012", 16, 64) {
		t.Fatal("valid task id was rejected")
	}
	if validAgentUpdateToken("../../etc/passwd", 4, 64) {
		t.Fatal("unsafe task id was accepted")
	}
}

func TestRenderedAgentUpdateHelperIsValidShell(t *testing.T) {
	request := agentUpgradeRequest{TaskID: "12345678-1234-1234-1234-123456789012", TargetVersion: "2.41.4"}
	script := renderAgentUpdateHelper(request, "/etc/gost/gost", "/etc/gost/.gost.update", "/etc/gost/status.json", "/etc/gost/helper.sh")
	path := t.TempDir() + "/helper.sh"
	if err := os.WriteFile(path, []byte(script), 0600); err != nil {
		t.Fatal(err)
	}
	if output, err := exec.Command("/bin/sh", "-n", path).CombinedOutput(); err != nil {
		t.Fatalf("generated helper is invalid shell: %v: %s", err, output)
	}
}

func TestRenderAgentUpdateHelper(t *testing.T) {
	request := agentUpgradeRequest{TaskID: "12345678-1234-1234-1234-123456789012", TargetVersion: "2.13.0"}
	script := renderAgentUpdateHelper(request, "/etc/gost/gost", "/etc/gost/.gost.update", "/etc/gost/status.json", "/etc/gost/helper.sh")
	for _, expected := range []string{"write_status installing", "write_status awaiting_reconnect", ".agent-update-connected-", "mv -f \"$PREVIOUS\" \"$EXECUTABLE\"", "write_status success", "write_status rolled_back"} {
		if !strings.Contains(script, expected) {
			t.Fatalf("helper script is missing %q", expected)
		}
	}
}

func TestCompareAgentVersions(t *testing.T) {
	if compareAgentVersions("2.12.9", "2.13.0") >= 0 || compareAgentVersions("2.13.1", "2.13.0") <= 0 || compareAgentVersions("2.13.0", "2.13.0") != 0 {
		t.Fatal("semantic version comparison failed")
	}
}
