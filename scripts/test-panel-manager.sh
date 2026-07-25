#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

FIXTURE_DIR="${TEST_ROOT}/fixture"
MOCK_DIR="${TEST_ROOT}/bin"
EVENT_LOG="${TEST_ROOT}/events.log"
INSTALL_DIR="${TEST_ROOT}/opt/flux-panel"
CONFIG_DIR="${TEST_ROOT}/etc/flux-panel"

mkdir -p "${FIXTURE_DIR}/scripts" "${MOCK_DIR}"
cp "${PROJECT_DIR}/VERSION" "${PROJECT_DIR}/docker-compose.yml" \
  "${PROJECT_DIR}/docker-compose-source.yml" "${PROJECT_DIR}/gost.sql" "${FIXTURE_DIR}/"
cp "${PROJECT_DIR}/scripts/flux-panel.sh" \
  "${PROJECT_DIR}/scripts/flux-panel-update-worker.sh" "${FIXTURE_DIR}/scripts/"

cat > "${MOCK_DIR}/curl" <<'EOF'
#!/usr/bin/env bash
set -eu
output=""
while [[ "$#" -gt 0 ]]; do
  if [[ "$1" == "-o" ]]; then
    shift
    output="$1"
  fi
  shift
done
[[ -n "${output}" ]]
: > "${output}"
EOF

cat > "${MOCK_DIR}/tar" <<'EOF'
#!/usr/bin/env bash
set -eu
destination=""
while [[ "$#" -gt 0 ]]; do
  if [[ "$1" == "-C" ]]; then
    shift
    destination="$1"
  fi
  shift
done
[[ -n "${destination}" ]]
mkdir -p "${destination}"
cp -R "${PANEL_TEST_FIXTURE}/." "${destination}/"
EOF

cat > "${MOCK_DIR}/flock" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

cat > "${MOCK_DIR}/docker" <<'EOF'
#!/usr/bin/env bash
set -u
printf 'docker' >> "${PANEL_TEST_EVENT_LOG}"
printf ' %q' "$@" >> "${PANEL_TEST_EVENT_LOG}"
printf '\n' >> "${PANEL_TEST_EVENT_LOG}"

case "${1:-}" in
  info) exit 0 ;;
  volume) exit 1 ;;
  ps) exit 0 ;;
  inspect)
    name="${*: -1}"
    if [[ "${name}" == "springboot-backend" ]]; then
      printf 'healthy\n'
    else
      printf 'running\n'
    fi
    exit 0
    ;;
  image)
    exit 0
    ;;
  compose)
    if [[ " $* " == *" version "* ]]; then
      printf 'Docker Compose version v2.30.0\n'
      exit 0
    fi
    if [[ " $* " == *" up "* && -n "${PANEL_TEST_FAIL_ONCE_FILE:-}" \
          && ! -e "${PANEL_TEST_FAIL_ONCE_FILE}" ]]; then
      : > "${PANEL_TEST_FAIL_ONCE_FILE}"
      exit 1
    fi
    exit 0
    ;;
esac
exit 0
EOF

chmod 755 "${MOCK_DIR}"/*

run_manager() {
  env \
    PATH="${MOCK_DIR}:${PATH}" \
    PANEL_TEST_FIXTURE="${FIXTURE_DIR}" \
    PANEL_TEST_EVENT_LOG="${EVENT_LOG}" \
    FLUX_PANEL_DIR="${INSTALL_DIR}" \
    FLUX_PANEL_CONFIG_DIR="${CONFIG_DIR}" \
    FLUX_PANEL_UPDATER_STATE_DIR="${TEST_ROOT}/updater" \
    FLUX_PANEL_MANAGER_BIN="${TEST_ROOT}/sbin/flux-panel-manager" \
    FLUX_PANEL_WORKER_BIN="${TEST_ROOT}/sbin/flux-panel-update-worker" \
    bash "${PROJECT_DIR}/scripts/flux-panel.sh" "$@"
}

run_manager install >/dev/null
grep -Fq 'PANEL_VERSION=2.2.0' "${CONFIG_DIR}/flux-panel.env"
grep -Fq 'DB_POOL_MAX_SIZE=10' "${CONFIG_DIR}/flux-panel.env"
grep -Eq 'docker compose .* pull mysql backend frontend' "${EVENT_LOG}"
grep -Eq 'docker compose .* up -d --no-build' "${EVENT_LOG}"
if grep -Eq 'docker compose .* up .*--build' "${EVENT_LOG}"; then
  printf 'install unexpectedly used a source build\n' >&2
  exit 1
fi

printf '2.2.1\n' > "${FIXTURE_DIR}/VERSION"
: > "${EVENT_LOG}"
run_manager update >/dev/null
grep -Fq 'PANEL_VERSION=2.2.1' "${CONFIG_DIR}/flux-panel.env"
grep -Fq 'PREVIOUS_PANEL_VERSION=2.2.0' "${CONFIG_DIR}/flux-panel.env"
grep -Fq '2.2.1' "${INSTALL_DIR}/VERSION"
grep -Eq 'docker compose .* pull mysql backend frontend' "${EVENT_LOG}"
grep -Eq 'docker compose .* up -d --no-build' "${EVENT_LOG}"

printf '2.2.2\n' > "${FIXTURE_DIR}/VERSION"
: > "${EVENT_LOG}"
FAIL_ONCE_FILE="${TEST_ROOT}/fail-once"
if PANEL_TEST_FAIL_ONCE_FILE="${FAIL_ONCE_FILE}" run_manager update >/dev/null 2>&1; then
  printf 'failed deployment unexpectedly succeeded\n' >&2
  exit 1
fi
grep -Fq 'PANEL_VERSION=2.2.1' "${CONFIG_DIR}/flux-panel.env"
grep -Fq '2.2.1' "${INSTALL_DIR}/VERSION"
grep -Eq 'docker compose .* up -d --no-build' "${EVENT_LOG}"

printf 'Panel manager tests passed\n'
