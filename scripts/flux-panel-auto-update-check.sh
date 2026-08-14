#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="${FLUX_PANEL_REPOSITORY:-NorwayXZ/flux-panel}"
CONFIG_DIR="${FLUX_PANEL_CONFIG_DIR:-/etc/flux-panel}"
ENV_FILE="${CONFIG_DIR}/flux-panel.env"
STATE_DIR="${FLUX_PANEL_UPDATER_STATE_DIR:-/var/lib/flux-panel-updater}"
REQUEST_FILE="${STATE_DIR}/update.request"
LOCK_FILE="${FLUX_PANEL_UPDATE_LOCK_FILE:-/run/flux-panel-update.lock}"
API_URL="https://api.github.com/repos/${REPOSITORY}/releases/latest"

log() {
  printf '[flux-panel-auto-update] %s\n' "$*"
}

read_env_value() {
  local key="$1"
  [[ -f "${ENV_FILE}" ]] || return 0
  sed -n "s/^${key}=//p" "${ENV_FILE}" | tail -n 1
}

normalize_version() {
  local value="${1#v}"
  value="${value#V}"
  printf '%s\n' "${value%%[[:space:]]*}"
}

version_gt() {
  local left right index left_part right_part
  left="$(normalize_version "$1")"
  right="$(normalize_version "$2")"
  for index in 1 2 3; do
    left_part="$(printf '%s' "${left}" | cut -d. -f"${index}" | sed 's/[^0-9].*$//')"
    right_part="$(printf '%s' "${right}" | cut -d. -f"${index}" | sed 's/[^0-9].*$//')"
    left_part="${left_part:-0}"
    right_part="${right_part:-0}"
    if ((10#${left_part} > 10#${right_part})); then return 0; fi
    if ((10#${left_part} < 10#${right_part})); then return 1; fi
  done
  return 1
}

if [[ "$(read_env_value AUTO_UPDATE_ENABLED)" == "0" ]]; then
  log "automatic updates are disabled in ${ENV_FILE}"
  exit 0
fi

mkdir -p "${STATE_DIR}"
if [[ -e "${REQUEST_FILE}" ]]; then
  log "an update request is already waiting"
  exit 0
fi

if command -v flock >/dev/null 2>&1; then
  exec 9>"${LOCK_FILE}"
  if ! flock -n 9; then
    log "an update or rollback is already running"
    exit 0
  fi
fi

current_version="$(read_env_value PANEL_VERSION)"
if [[ -z "${current_version}" && -f "${FLUX_PANEL_DIR:-/opt/flux-panel}/VERSION" ]]; then
  current_version="$(tr -d '[:space:]' < "${FLUX_PANEL_DIR:-/opt/flux-panel}/VERSION")"
fi
if [[ -z "${current_version}" ]]; then
  log "local panel version is unavailable"
  exit 0
fi

payload="$(curl -fsSL --retry 2 --connect-timeout 10 --max-time 20 \
  -H 'Accept: application/vnd.github+json' "${API_URL}" 2>/dev/null || true)"
latest_version="$(printf '%s' "${payload}" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
latest_version="$(normalize_version "${latest_version}")"
if [[ -z "${latest_version}" ]]; then
  log "GitHub latest release could not be read"
  exit 0
fi

if ! version_gt "${latest_version}" "${current_version}"; then
  log "panel is up to date: ${current_version}"
  exit 0
fi

temporary="$(mktemp "${STATE_DIR}/request.XXXXXX")"
printf 'version=%s\nreason=GitHub latest release detected\nrequestedAt=%s\n' \
  "${latest_version}" "$(date +%s%3N)" > "${temporary}"
chmod 640 "${temporary}"
mv -f "${temporary}" "${REQUEST_FILE}"
log "queued update ${current_version} -> ${latest_version}"
