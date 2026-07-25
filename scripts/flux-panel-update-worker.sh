#!/usr/bin/env bash
set -uo pipefail

STATE_DIR="${FLUX_PANEL_UPDATER_STATE_DIR:-/var/lib/flux-panel-updater}"
MANAGER="${FLUX_PANEL_MANAGER:-/usr/local/sbin/flux-panel-manager}"
STATUS_FILE="${STATE_DIR}/status.properties"
REQUEST_FILE="${STATE_DIR}/update.request"
LOG_FILE="${STATE_DIR}/update.log"

write_status() {
  local state="$1"
  local message="$2"
  local started_at="${3:-0}"
  local finished_at="${4:-0}"
  local temporary
  temporary="$(mktemp "${STATE_DIR}/status.XXXXXX")"
  printf 'state=%s\nmessage=%s\nstartedAt=%s\nfinishedAt=%s\n' \
    "${state}" "${message}" "${started_at}" "${finished_at}" > "${temporary}"
  chmod 640 "${temporary}"
  mv -f "${temporary}" "${STATUS_FILE}"
}

mkdir -p "${STATE_DIR}"
rm -f "${REQUEST_FILE}"
: > "${LOG_FILE}"
chmod 640 "${LOG_FILE}"

started_at="$(date +%s%3N)"
write_status "running" "Downloading and deploying panel images" "${started_at}" 0

{
  printf '[%s] Starting Flux Panel update\n' "$(date '+%F %T')"
  if "${MANAGER}" update; then
    finished_at="$(date +%s%3N)"
    write_status "success" "Update completed" "${started_at}" "${finished_at}"
    printf '[%s] Update completed\n' "$(date '+%F %T')"
    exit 0
  else
    exit_code=$?
  fi

  finished_at="$(date +%s%3N)"
  write_status "failed" "Update failed; check the update log" "${started_at}" "${finished_at}"
  printf '[%s] Update failed with exit code %s\n' "$(date '+%F %T')" "${exit_code}"
  exit "${exit_code}"
} >> "${LOG_FILE}" 2>&1
