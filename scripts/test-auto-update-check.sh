#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

mkdir -p "${TEST_ROOT}/bin" "${TEST_ROOT}/etc" "${TEST_ROOT}/state"
cat > "${TEST_ROOT}/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '{"tag_name":"v2.51.14"}\n'
EOF
chmod 755 "${TEST_ROOT}/bin/curl"

cat > "${TEST_ROOT}/etc/flux-panel.env" <<'EOF'
PANEL_VERSION=2.51.10
AUTO_UPDATE_ENABLED=1
EOF

PATH="${TEST_ROOT}/bin:${PATH}" \
FLUX_PANEL_CONFIG_DIR="${TEST_ROOT}/etc" \
FLUX_PANEL_UPDATER_STATE_DIR="${TEST_ROOT}/state" \
FLUX_PANEL_UPDATE_LOCK_FILE="${TEST_ROOT}/update.lock" \
FLUX_PANEL_DIR="${TEST_ROOT}" \
bash "${PROJECT_DIR}/scripts/flux-panel-auto-update-check.sh" >/dev/null

grep -Fq 'version=2.51.14' "${TEST_ROOT}/state/update.request"

printf 'Automatic update checker test passed\n'
