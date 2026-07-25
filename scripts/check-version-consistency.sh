#!/bin/sh
set -eu

PROJECT_DIR="$(cd -- "$(dirname "$0")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$PROJECT_DIR/VERSION")"

check_contains() {
  file="$1"
  expected="$2"
  grep -Fq "$expected" "$PROJECT_DIR/$file" || {
    printf 'Version %s is missing from %s\n' "$VERSION" "$file" >&2
    exit 1
  }
}

check_contains install.sh "FLUX_PANEL_AGENT_RELEASE:-$VERSION"
check_contains go-gost/agent_version.go "const agentVersion = \"$VERSION\""
check_contains docker-compose-source.yml "PANEL_VERSION:-$VERSION"
check_contains .env.example "PANEL_VERSION=$VERSION"
check_contains vite-frontend/src/config/site.ts "VERSION = \"$VERSION\""
check_contains springboot-backend/src/main/java/com/admin/service/impl/NodeServiceImpl.java "/$VERSION/install.sh"

printf 'Version consistency checks passed: %s\n' "$VERSION"
