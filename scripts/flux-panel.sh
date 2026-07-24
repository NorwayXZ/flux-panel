#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="${FLUX_PANEL_REPOSITORY:-NorwayXZ/flux-panel}"
BRANCH="${FLUX_PANEL_BRANCH:-main}"
INSTALL_DIR="${FLUX_PANEL_DIR:-/opt/flux-panel}"
CONFIG_DIR="${FLUX_PANEL_CONFIG_DIR:-/etc/flux-panel}"
ENV_FILE="${CONFIG_DIR}/flux-panel.env"
COMPOSE_FILE="${INSTALL_DIR}/docker-compose-source.yml"
SOURCE_URL="https://github.com/${REPOSITORY}/archive/refs/heads/${BRANCH}.tar.gz"

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

check_host() {
  [[ "$(uname -s)" == "Linux" ]] || fail "only Linux panel hosts are supported"
  case "$(uname -m)" in
    x86_64|amd64) ;;
    *) fail "the bundled MySQL 5.7 deployment currently requires an x86_64 host" ;;
  esac

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

  validate_port FLUX_PANEL_FRONTEND_PORT "${frontend_port}"
  validate_port FLUX_PANEL_BACKEND_PORT "${backend_port}"
  [[ "${frontend_port}" != "${backend_port}" ]] || fail "frontend and backend ports must be different"

  mkdir -p "${CONFIG_DIR}"
  if [[ -f "${ENV_FILE}" ]]; then
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
FRONTEND_PORT=${frontend_port}
BACKEND_PORT=${backend_port}
EOF
  chmod 600 "${ENV_FILE}"
  log "created protected configuration: ${ENV_FILE}"
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
  fail "services did not become healthy in time; run: docker logs springboot-backend"
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

  log "building and starting Flux Panel; the first build may take several minutes"
  compose up -d --build
  wait_for_services

  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  log "installation complete: http://SERVER_IP:${FRONTEND_PORT}"
  log "default login: admin_user / admin_user"
  log "change the default password immediately after signing in"
}

update_panel() {
  check_host
  [[ -f "${ENV_FILE}" ]] || fail "configuration not found: ${ENV_FILE}"
  [[ -f "${COMPOSE_FILE}" ]] || fail "installation not found: ${INSTALL_DIR}"

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

  if ! compose up -d --build; then
    log "update failed; restoring previous source"
    rm -rf "${INSTALL_DIR}"
    mv "${backup}" "${INSTALL_DIR}"
    compose up -d --build
    fail "update failed and the previous source was restored"
  fi
  wait_for_services
  rm -rf "${backup}"
  log "update complete"
}

uninstall_panel() {
  check_host
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
