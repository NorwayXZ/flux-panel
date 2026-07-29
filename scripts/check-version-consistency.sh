#!/bin/sh
set -eu

PROJECT_DIR="$(cd -- "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$PROJECT_DIR/VERSION")"
AGENT_VERSION="$(tr -d '[:space:]' < "$PROJECT_DIR/AGENT_VERSION")"

check_contains() {
  file="$1"
  expected="$2"
  grep -Fq "$expected" "$PROJECT_DIR/$file" || {
    printf 'Version %s is missing from %s\n' "$VERSION" "$file" >&2
    exit 1
  }
}

check_contains docker-compose-source.yml "PANEL_VERSION:-$VERSION"
check_contains .env.example "PANEL_VERSION=$VERSION"
check_contains vite-frontend/src/config/site.ts "VERSION = \"$VERSION\""
check_contains install.sh "FLUX_PANEL_AGENT_RELEASE:-$AGENT_VERSION"
check_contains install-connector-macos.sh "FLUX_PANEL_CONNECTOR_RELEASE:-$AGENT_VERSION"
check_contains install-connector.ps1 "Release = \"$AGENT_VERSION\""
check_contains go-gost/agent_version.go "const agentVersion = \"$AGENT_VERSION\""
check_contains springboot-backend/src/main/java/com/admin/service/impl/NodeServiceImpl.java "/$AGENT_VERSION/install.sh"
check_contains springboot-backend/src/main/java/com/admin/common/utils/ConnectorInstallCommandUtil.java "RELEASE = \"$AGENT_VERSION\""
check_contains springboot-backend/src/main/java/com/admin/service/AgentUpgradeService.java "TARGET_VERSION = \"$AGENT_VERSION\""
check_contains springboot-backend/src/test/java/com/admin/common/utils/ConnectorInstallCommandUtilTests.java "/$AGENT_VERSION/install.sh"
check_contains springboot-backend/src/test/java/com/admin/service/AgentUpgradeServiceTests.java "/$AGENT_VERSION/install.sh"

printf 'Version consistency checks passed: panel=%s agent=%s\n' "$VERSION" "$AGENT_VERSION"
