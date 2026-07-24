#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="${FLUX_PANEL_REPOSITORY:-NorwayXZ/flux-panel}"
BRANCH="${FLUX_PANEL_BRANCH:-main}"
INSTALL_DIR="${FLUX_PANEL_DIR:-/opt/flux-panel}"
CONFIG_DIR="${FLUX_PANEL_CONFIG_DIR:-/etc/flux-panel}"
ENV_FILE="${CONFIG_DIR}/flux-panel.env"
COMPOSE_FILE="${INSTALL_DIR}/docker-compose-source.yml"
SOURCE_URL="https://github.com/${REPOSITORY}/archive/refs/heads/${BRANCH}.tar.gz"
UPDATER_STATE_DIR="${FLUX_PANEL_UPDATER_STATE_DIR:-/var/lib/flux-panel-updater}"
MANAGER_BIN="${FLUX_PANEL_MANAGER_BIN:-/usr/local/sbin/flux-panel-manager}"
WORKER_BIN="${FLUX_PANEL_WORKER_BIN:-/usr/local/sbin/flux-panel-update-worker}"
UPDATER_SERVICE="flux-panel-updater.service"
UPDATER_PATH="flux-panel-updater.path"

log() {
  printf '[flux-panel] %s\n' "$*"
}

fail() {
  printf '[flux-panel] ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || fail "run this command as root or with sudo"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

host_architecture() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64\n' ;;
    aarch64|arm64) printf 'arm64\n' ;;
    *) fail "unsupported architecture: $(uname -m); supported architectures: amd64, arm64" ;;
  esac
}

default_mysql_image() {
  case "$(host_architecture)" in
    amd64) printf 'mysql:5.7\n' ;;
    arm64) printf 'mysql:8.0\n' ;;
  esac
}

check_host() {
  [[ "$(uname -s)" == "Linux" ]] || fail "only Linux panel hosts are supported"
  host_architecture >/dev/null

  require_command curl
  require_command tar
  require_command docker
  docker info >/dev/null 2>&1 || fail "Docker is not running"
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"
}

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

random_secret() {
  od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

validate_port() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^[0-9]+$ ]] || fail "${name} must be a number"
  (( value >= 1 && value <= 65535 )) || fail "${name} must be between 1 and 65535"
}

download_source() {
  local destination="$1"
  local archive
  archive="$(mktemp)"
  log "downloading ${REPOSITORY} (${BRANCH})"
  curl -fL --retry 3 --connect-timeout 15 "${SOURCE_URL}" -o "${archive}"
  mkdir -p "${destination}"
  tar -xzf "${archive}" --strip-components=1 -C "${destination}"
  rm -f "${archive}"
  [[ -f "${destination}/docker-compose-source.yml" ]] || fail "downloaded source is incomplete"
}

ensure_environment() {
  local frontend_port="${FLUX_PANEL_FRONTEND_PORT:-6366}"
  local backend_port="${FLUX_PANEL_BACKEND_PORT:-6365}"
  local mysql_image
  mysql_image="$(default_mysql_image)"

  validate_port FLUX_PANEL_FRONTEND_PORT "${frontend_port}"
  validate_port FLUX_PANEL_BACKEND_PORT "${backend_port}"
  [[ "${frontend_port}" != "${backend_port}" ]] || fail "frontend and backend ports must be different"

  mkdir -p "${CONFIG_DIR}"
  if [[ -f "${ENV_FILE}" ]]; then
    if ! grep -q '^MYSQL_IMAGE=' "${ENV_FILE}"; then
      printf '\nMYSQL_IMAGE=%s\n' "${mysql_image}" >> "${ENV_FILE}"
      log "pinned database image for $(host_architecture): ${mysql_image}"
    fi
    log "reusing existing configuration: ${ENV_FILE}"
    return
  fi

  if docker volume inspect mysql_data >/dev/null 2>&1; then
    fail "mysql_data already exists but ${ENV_FILE} is missing; restore the original environment file or remove the volume manually"
  fi

  cat > "${ENV_FILE}" <<EOF
DB_NAME=flux_panel
DB_USER=flux_panel
DB_PASSWORD=$(random_secret)
JWT_SECRET=$(random_secret)
MYSQL_IMAGE=${mysql_image}
FRONTEND_PORT=${frontend_port}
BACKEND_PORT=${backend_port}
EOF
  chmod 600 "${ENV_FILE}"
  log "created protected configuration: ${ENV_FILE}"
  log "selected database image for $(host_architecture): ${mysql_image}"
}

write_updater_status() {
  local state="$1"
  local message="$2"
  local temporary
  temporary="$(mktemp "${UPDATER_STATE_DIR}/status.XXXXXX")"
  printf 'state=%s\nmessage=%s\nstartedAt=0\nfinishedAt=0\n' "${state}" "${message}" > "${temporary}"
  chmod 640 "${temporary}"
  mv -f "${temporary}" "${UPDATER_STATE_DIR}/status.properties"
}

install_update_service() {
  rm -f "${UPDATER_STATE_DIR}/enabled"
  if ! command -v systemctl >/dev/null 2>&1 || [[ ! -d /run/systemd/system ]]; then
    log "systemd is unavailable; online updates are disabled, command-line updates remain available"
    return 0
  fi

  mkdir -p "${UPDATER_STATE_DIR}"
  chmod 750 "${UPDATER_STATE_DIR}"

  local temporary_manager temporary_worker
  temporary_manager="$(mktemp "${MANAGER_BIN}.XXXXXX")"
  temporary_worker="$(mktemp "${WORKER_BIN}.XXXXXX")"
  install -m 750 "${INSTALL_DIR}/scripts/flux-panel.sh" "${temporary_manager}"
  install -m 750 "${INSTALL_DIR}/scripts/flux-panel-update-worker.sh" "${temporary_worker}"
  mv -f "${temporary_manager}" "${MANAGER_BIN}"
  mv -f "${temporary_worker}" "${WORKER_BIN}"

  cat > "/etc/systemd/system/${UPDATER_SERVICE}" <<EOF
[Unit]
Description=Flux Panel restricted update worker
After=docker.service network-online.target
Wants=network-online.target
Requires=docker.service

[Service]
Type=oneshot
ExecStart=${WORKER_BIN}
TimeoutStartSec=0
Nice=10
EOF

  cat > "/etc/systemd/system/${UPDATER_PATH}" <<EOF
[Unit]
Description=Watch for Flux Panel update requests

[Path]
PathExists=${UPDATER_STATE_DIR}/update.request
Unit=${UPDATER_SERVICE}

[Install]
WantedBy=multi-user.target
EOF

  if ! systemctl daemon-reload || ! systemctl enable --now "${UPDATER_PATH}" >/dev/null; then
    log "online update service could not be installed; command-line updates remain available"
    return 0
  fi
  if systemctl is-active --quiet "${UPDATER_PATH}"; then
    touch "${UPDATER_STATE_DIR}/enabled"
    chmod 640 "${UPDATER_STATE_DIR}/enabled"
    [[ -f "${UPDATER_STATE_DIR}/status.properties" ]] || write_updater_status "idle" "Ready"
    log "online update service is ready"
  else
    log "online update service could not be started; command-line updates remain available"
  fi
}

remove_update_service() {
  local remove_state="${1:-0}"
  rm -f "${UPDATER_STATE_DIR}/enabled" "${UPDATER_STATE_DIR}/update.request"
  if command -v systemctl >/dev/null 2>&1 && [[ -d /run/systemd/system ]]; then
    systemctl disable --now "${UPDATER_PATH}" >/dev/null 2>&1 || true
    systemctl stop "${UPDATER_SERVICE}" >/dev/null 2>&1 || true
    rm -f "/etc/systemd/system/${UPDATER_SERVICE}" "/etc/systemd/system/${UPDATER_PATH}"
    systemctl daemon-reload || true
  fi
  rm -f "${MANAGER_BIN}" "${WORKER_BIN}"
  if [[ "${remove_state}" == "1" ]]; then
    rm -rf "${UPDATER_STATE_DIR}"
  fi
}

wait_for_services() {
  local attempt
  for attempt in $(seq 1 90); do
    if [[ "$(docker inspect -f '{{.State.Health.Status}}' springboot-backend 2>/dev/null || true)" == "healthy" ]] &&
       [[ "$(docker inspect -f '{{.State.Status}}' vite-frontend 2>/dev/null || true)" == "running" ]]; then
      log "all services are running"
      return 0
    fi
    sleep 2
  done
  compose ps || true
  return 1
}

install_panel() {
  check_host
  if [[ -f "${COMPOSE_FILE}" ]]; then
    fail "Flux Panel is already installed in ${INSTALL_DIR}; use the update command"
  fi
  if docker ps -a --format '{{.Names}}' | grep -Eq '^(gost-mysql|springboot-backend|vite-frontend)$'; then
    fail "Flux Panel container names already exist; remove the old installation or use its update procedure first"
  fi

  local staging
  staging="$(mktemp -d)"
  trap 'rm -rf "${staging:-}"' EXIT
  download_source "${staging}"
  ensure_environment

  mkdir -p "$(dirname "${INSTALL_DIR}")"
  rm -rf "${INSTALL_DIR}"
  mv "${staging}" "${INSTALL_DIR}"
  trap - EXIT

  install_update_service

  log "building and starting Flux Panel; the first build may take several minutes"
  compose up -d --build
  wait_for_services || fail "services did not become healthy in time; run: docker logs springboot-backend"

  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  log "installation complete: http://SERVER_IP:${FRONTEND_PORT}"
  log "default login: admin_user / admin_user"
  log "change the default password immediately after signing in"
}

update_panel() {
  check_host
  require_command flock
  [[ -f "${ENV_FILE}" ]] || fail "configuration not found: ${ENV_FILE}"
  [[ -f "${COMPOSE_FILE}" ]] || fail "installation not found: ${INSTALL_DIR}"

  exec 9>/run/flux-panel-update.lock
  flock -n 9 || fail "another Flux Panel update is already running"

  local staging backup
  staging="$(mktemp -d)"
  backup="${INSTALL_DIR}.previous"
  trap 'rm -rf "${staging:-}"' EXIT
  download_source "${staging}"

  log "replacing application source"
  rm -rf "${backup}"
  mv "${INSTALL_DIR}" "${backup}"
  mv "${staging}" "${INSTALL_DIR}"
  trap - EXIT

  install_update_service

  if ! compose up -d --build || ! wait_for_services; then
    log "update failed or services did not become healthy; restoring previous source"
    rm -rf "${INSTALL_DIR}"
    mv "${backup}" "${INSTALL_DIR}"
    install_update_service
    compose up -d --build
    wait_for_services || fail "update and rollback both failed; inspect the container logs immediately"
    fail "update failed and the previous source was restored"
  fi
  rm -rf "${backup}"
  log "update complete"
}

uninstall_panel() {
  check_host
  remove_update_service 0
  if [[ -f "${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    log "stopping and removing application containers"
    compose down --remove-orphans
  else
    log "installation files are incomplete; removing known application containers"
    docker rm -f vite-frontend springboot-backend gost-mysql >/dev/null 2>&1 || true
  fi
  rm -rf "${INSTALL_DIR}" "${INSTALL_DIR}.previous"
  log "application removed; database volumes and ${ENV_FILE} were preserved"
  log "run the install command again to restore service with the preserved data"
}

purge_panel() {
  check_host
  [[ "${FLUX_PANEL_PURGE:-}" == "1" ]] || fail "set FLUX_PANEL_PURGE=1 to confirm permanent data deletion"

  if [[ -f "${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    compose down --volumes --remove-orphans || true
  fi
  docker rm -f vite-frontend springboot-backend gost-mysql >/dev/null 2>&1 || true
  docker volume rm mysql_data backend_logs >/dev/null 2>&1 || true
  docker network rm gost-network >/dev/null 2>&1 || true
  remove_update_service 1
  rm -rf "${INSTALL_DIR}" "${INSTALL_DIR}.previous" "${CONFIG_DIR}"
  log "Flux Panel containers, source, configuration, and database volumes were permanently deleted"
}

show_status() {
  check_host
  if [[ -f "${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    compose ps
  else
    docker ps --filter name=gost-mysql --filter name=springboot-backend --filter name=vite-frontend
  fi
}

usage() {
  cat <<'EOF'
Usage: flux-panel.sh <install|update|uninstall|purge|status>

Environment variables:
  FLUX_PANEL_FRONTEND_PORT  Public web port, default: 6366
  FLUX_PANEL_BACKEND_PORT   Agent/API port, default: 6365
  FLUX_PANEL_BRANCH         Git branch to install, default: main
  FLUX_PANEL_DIR            Source directory, default: /opt/flux-panel
  FLUX_PANEL_PURGE=1        Required confirmation for permanent deletion
EOF
}

main() {
  require_root
  case "${1:-}" in
    install) install_panel ;;
    update) update_panel ;;
    uninstall) uninstall_panel ;;
    purge) purge_panel ;;
    status) show_status ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
