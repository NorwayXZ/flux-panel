#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY="${FLUX_PANEL_REPOSITORY:-NorwayXZ/flux-panel}"
BRANCH="${FLUX_PANEL_BRANCH:-main}"
INSTALL_DIR="${FLUX_PANEL_DIR:-/opt/flux-panel}"
CONFIG_DIR="${FLUX_PANEL_CONFIG_DIR:-/etc/flux-panel}"
ENV_FILE="${CONFIG_DIR}/flux-panel.env"
COMPOSE_FILE="${FLUX_PANEL_COMPOSE_FILE:-${INSTALL_DIR}/docker-compose.yml}"
SOURCE_COMPOSE_FILE="${INSTALL_DIR}/docker-compose-source.yml"
SOURCE_URL="https://github.com/${REPOSITORY}/archive/refs/heads/${BRANCH}.tar.gz"
UPDATER_STATE_DIR="${FLUX_PANEL_UPDATER_STATE_DIR:-/var/lib/flux-panel-updater}"
MANAGER_BIN="${FLUX_PANEL_MANAGER_BIN:-/usr/local/sbin/flux-panel-manager}"
WORKER_BIN="${FLUX_PANEL_WORKER_BIN:-/usr/local/sbin/flux-panel-update-worker}"
AUTO_UPDATE_CHECK_BIN="${FLUX_PANEL_AUTO_UPDATE_CHECK_BIN:-/usr/local/sbin/flux-panel-auto-update-check}"
UPDATER_SERVICE="flux-panel-updater.service"
UPDATER_PATH="flux-panel-updater.path"
AUTO_UPDATE_SERVICE="flux-panel-auto-update.service"
AUTO_UPDATE_TIMER="flux-panel-auto-update.timer"
UPDATE_LOCK_FILE="${FLUX_PANEL_UPDATE_LOCK_FILE:-/run/flux-panel-update.lock}"

log() {
  printf '[flux-panel] %s\n' "$*"
}

fail() {
  printf '[flux-panel] ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [[ "${EUID}" -eq 0 || "${FLUX_PANEL_TEST_MODE:-0}" == "1" ]] \
    || fail "run this command as root or with sudo"
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
  [[ "$(uname -s)" == "Linux" || "${FLUX_PANEL_TEST_MODE:-0}" == "1" ]] \
    || fail "only Linux panel hosts are supported"
  host_architecture >/dev/null

  require_command curl
  require_command tar
  require_command awk
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

project_version() {
  local directory="$1"
  local version_file="${directory}/VERSION"
  [[ -f "${version_file}" ]] || fail "version file is missing: ${version_file}"

  local version
  version="$(tr -d '[:space:]' < "${version_file}")"
  [[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] \
    || fail "invalid panel version: ${version}"
  printf '%s\n' "${version}"
}

read_env_value() {
  local key="$1"
  [[ -f "${ENV_FILE}" ]] || return 0
  sed -n "s/^${key}=//p" "${ENV_FILE}" | tail -n 1
}

set_env_value() {
  local key="$1"
  local value="$2"
  local temporary
  temporary="$(mktemp "${CONFIG_DIR}/env.XXXXXX")"

  if [[ -f "${ENV_FILE}" ]]; then
    awk -v key="${key}" -v value="${value}" '
      BEGIN { updated = 0 }
      index($0, key "=") == 1 {
        if (!updated) print key "=" value
        updated = 1
        next
      }
      { print }
      END { if (!updated) print key "=" value }
    ' "${ENV_FILE}" > "${temporary}"
  else
    printf '%s=%s\n' "${key}" "${value}" > "${temporary}"
  fi

  chmod 600 "${temporary}"
  mv -f "${temporary}" "${ENV_FILE}"
}

ensure_env_default() {
  local key="$1"
  local value="$2"
  grep -q "^${key}=" "${ENV_FILE}" 2>/dev/null || printf '%s=%s\n' "${key}" "${value}" >> "${ENV_FILE}"
}

host_memory_mb() {
  if [[ "${FLUX_PANEL_HOST_MEMORY_MB:-}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${FLUX_PANEL_HOST_MEMORY_MB}"
    return
  fi
  awk '/^MemTotal:/ { printf "%d\n", $2 / 1024; found=1; exit } END { if (!found) print 2048 }' /proc/meminfo
}

recommended_java_opts() {
  local memory_mb
  memory_mb="$(host_memory_mb)"
  if (( memory_mb < 2048 )); then
    printf '"-Xms128m -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"\n'
  elif (( memory_mb < 4096 )); then
    printf '"-Xms192m -Xmx512m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"\n'
  elif (( memory_mb < 8192 )); then
    printf '"-Xms256m -Xmx768m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"\n'
  else
    printf '"-Xms256m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"\n'
  fi
}

ensure_safe_java_opts() {
  local current recommended migrated
  current="$(read_env_value JAVA_OPTS)"
  recommended="$(recommended_java_opts)"
  if [[ -z "${current}" ]]; then
    set_env_value JAVA_OPTS "${recommended}"
    log "configured backend JVM for $(host_memory_mb) MB host memory"
    return
  fi

  migrated="${current}"
  if [[ "${current}" == *"-Xmx256m"* && "${current}" == *"UseSerialGC"* ]]; then
    migrated="${recommended}"
  elif [[ "${current}" != *"ExitOnOutOfMemoryError"* ]]; then
    if [[ "${current}" == \"*\" ]]; then
      migrated="${current%\"} -XX:+ExitOnOutOfMemoryError\""
    else
      migrated="${current} -XX:+ExitOnOutOfMemoryError"
    fi
  fi

  if [[ "${migrated}" != "${current}" ]]; then
    local backup="${ENV_FILE}.before-jvm-migration-$(date +%Y%m%d-%H%M%S)"
    cp -p "${ENV_FILE}" "${backup}"
    set_env_value JAVA_OPTS "${migrated}"
    log "migrated unsafe JVM settings; backup: ${backup}"
  fi
}

check_disk_capacity() {
  local available_mb usage_percent parent_dir
  parent_dir="${INSTALL_DIR%/*}"
  [[ -d "${parent_dir}" ]] || parent_dir=/
  available_mb="${FLUX_PANEL_DISK_AVAILABLE_MB:-$(df -Pm "${parent_dir}" | awk 'NR==2 {print $4}')}"
  usage_percent="${FLUX_PANEL_DISK_USAGE_PERCENT:-$(df -P "${parent_dir}" | awk 'NR==2 {gsub(/%/, "", $5); print $5}')}"
  [[ "${available_mb}" =~ ^[0-9]+$ ]] || fail "could not determine available disk space"
  [[ "${usage_percent}" =~ ^[0-9]+$ ]] || fail "could not determine filesystem usage"
  (( available_mb >= 2048 )) || fail "at least 2 GB free disk space is required; available: ${available_mb} MB"
  if (( usage_percent >= 85 )); then
    log "WARNING: filesystem usage is ${usage_percent}%; inspect logs and Docker data before it reaches 100%"
  fi
}

ensure_runtime_defaults() {
  ensure_env_default IMAGE_REGISTRY ghcr.io/norwayxz
  ensure_safe_java_opts
  ensure_env_default DB_POOL_MIN_IDLE 1
  ensure_env_default DB_POOL_MAX_SIZE 10
  ensure_env_default TOMCAT_MIN_SPARE_THREADS 2
  ensure_env_default TOMCAT_MAX_THREADS 100
  ensure_env_default TOMCAT_MAX_CONNECTIONS 512
  ensure_env_default TOMCAT_ACCEPT_COUNT 100
  ensure_env_default MYSQL_MAX_CONNECTIONS 200
  ensure_env_default MYSQL_BUFFER_POOL_SIZE 128M
  ensure_env_default BACKEND_HEALTH_START_PERIOD 420s
  ensure_env_default BACKEND_HEALTH_RETRIES 6
  ensure_env_default FORWARD_HEALTH_CHECK_INTERVAL_MS 60000
  ensure_env_default FORWARD_FAILURE_THRESHOLD 2
  ensure_env_default FORWARD_RECOVERY_THRESHOLD 2
  ensure_env_default FORWARD_SWITCH_COOLDOWN_MS 120000
  ensure_env_default FORWARD_FAILBACK_STABLE_MS 180000
  ensure_env_default FORWARD_LATENCY_SWITCH_GAP_MS 15
  ensure_env_default AUTO_UPDATE_ENABLED 1
  chmod 600 "${ENV_FILE}"
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
  local panel_version="$1"
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
    ensure_runtime_defaults
    set_env_value PANEL_VERSION "${panel_version}"
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
IMAGE_REGISTRY=ghcr.io/norwayxz
PANEL_VERSION=${panel_version}
FRONTEND_PORT=${frontend_port}
BACKEND_PORT=${backend_port}
DB_POOL_MIN_IDLE=1
DB_POOL_MAX_SIZE=10
TOMCAT_MIN_SPARE_THREADS=2
TOMCAT_MAX_THREADS=100
TOMCAT_MAX_CONNECTIONS=512
TOMCAT_ACCEPT_COUNT=100
MYSQL_MAX_CONNECTIONS=200
MYSQL_BUFFER_POOL_SIZE=128M
BACKEND_HEALTH_START_PERIOD=420s
BACKEND_HEALTH_RETRIES=6
FORWARD_HEALTH_CHECK_INTERVAL_MS=60000
FORWARD_FAILURE_THRESHOLD=2
FORWARD_RECOVERY_THRESHOLD=2
FORWARD_SWITCH_COOLDOWN_MS=120000
FORWARD_FAILBACK_STABLE_MS=180000
FORWARD_LATENCY_SWITCH_GAP_MS=15
AUTO_UPDATE_ENABLED=1
JAVA_OPTS=$(recommended_java_opts)
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
  if [[ "${FLUX_PANEL_DISABLE_ONLINE_UPDATES:-0}" == "1" ]]; then
    log "online updates are disabled by configuration; command-line updates remain available"
    return 0
  fi
  if ! command -v systemctl >/dev/null 2>&1 || [[ ! -d /run/systemd/system ]]; then
    log "systemd is unavailable; online updates are disabled, command-line updates remain available"
    return 0
  fi

  mkdir -p "${UPDATER_STATE_DIR}"
  chmod 750 "${UPDATER_STATE_DIR}"

  local temporary_manager temporary_worker
  local temporary_auto_update
  temporary_manager="$(mktemp "${MANAGER_BIN}.XXXXXX")"
  temporary_worker="$(mktemp "${WORKER_BIN}.XXXXXX")"
  temporary_auto_update="$(mktemp "${AUTO_UPDATE_CHECK_BIN}.XXXXXX")"
  install -m 750 "${INSTALL_DIR}/scripts/flux-panel.sh" "${temporary_manager}"
  install -m 750 "${INSTALL_DIR}/scripts/flux-panel-update-worker.sh" "${temporary_worker}"
  install -m 750 "${INSTALL_DIR}/scripts/flux-panel-auto-update-check.sh" "${temporary_auto_update}"
  mv -f "${temporary_manager}" "${MANAGER_BIN}"
  mv -f "${temporary_worker}" "${WORKER_BIN}"
  mv -f "${temporary_auto_update}" "${AUTO_UPDATE_CHECK_BIN}"

  cat > "/etc/systemd/system/${UPDATER_SERVICE}" <<EOF
[Unit]
Description=Flux Panel restricted update worker
After=docker.service network-online.target
Wants=network-online.target
Requires=docker.service

[Service]
Type=oneshot
ExecStart=${WORKER_BIN}
Environment="FLUX_PANEL_DIR=${INSTALL_DIR}"
Environment="FLUX_PANEL_CONFIG_DIR=${CONFIG_DIR}"
Environment="FLUX_PANEL_UPDATER_STATE_DIR=${UPDATER_STATE_DIR}"
Environment="FLUX_PANEL_MANAGER=${MANAGER_BIN}"
Environment="FLUX_PANEL_UPDATE_LOCK_FILE=${UPDATE_LOCK_FILE}"
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

  cat > "/etc/systemd/system/${AUTO_UPDATE_SERVICE}" <<EOF
[Unit]
Description=Check for new Flux Panel releases
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=${AUTO_UPDATE_CHECK_BIN}
Environment="FLUX_PANEL_REPOSITORY=${REPOSITORY}"
Environment="FLUX_PANEL_DIR=${INSTALL_DIR}"
Environment="FLUX_PANEL_CONFIG_DIR=${CONFIG_DIR}"
Environment="FLUX_PANEL_UPDATER_STATE_DIR=${UPDATER_STATE_DIR}"
Environment="FLUX_PANEL_UPDATE_LOCK_FILE=${UPDATE_LOCK_FILE}"
Nice=10
EOF

  cat > "/etc/systemd/system/${AUTO_UPDATE_TIMER}" <<EOF
[Unit]
Description=Periodic Flux Panel release check

[Timer]
OnBootSec=10min
OnUnitActiveSec=15min
Persistent=true
Unit=${AUTO_UPDATE_SERVICE}

[Install]
WantedBy=timers.target
EOF

  if ! systemctl daemon-reload \
      || ! systemctl enable --now "${UPDATER_PATH}" >/dev/null \
      || ! systemctl enable --now "${AUTO_UPDATE_TIMER}" >/dev/null; then
    log "online update service could not be installed; command-line updates remain available"
    return 0
  fi
  if systemctl is-active --quiet "${UPDATER_PATH}" && systemctl is-active --quiet "${AUTO_UPDATE_TIMER}"; then
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
    systemctl disable --now "${AUTO_UPDATE_TIMER}" >/dev/null 2>&1 || true
    systemctl stop "${AUTO_UPDATE_SERVICE}" >/dev/null 2>&1 || true
    rm -f "/etc/systemd/system/${UPDATER_SERVICE}" "/etc/systemd/system/${UPDATER_PATH}" \
      "/etc/systemd/system/${AUTO_UPDATE_SERVICE}" "/etc/systemd/system/${AUTO_UPDATE_TIMER}"
    systemctl daemon-reload || true
  fi
  rm -f "${MANAGER_BIN}" "${WORKER_BIN}" "${AUTO_UPDATE_CHECK_BIN}"
  if [[ "${remove_state}" == "1" ]]; then
    rm -rf "${UPDATER_STATE_DIR}"
  fi
}

wait_for_services() {
  local attempt max_attempts backend_status frontend_status
  max_attempts="${FLUX_PANEL_SERVICE_WAIT_ATTEMPTS:-240}"
  [[ "${max_attempts}" =~ ^[0-9]+$ ]] && ((max_attempts > 0)) || max_attempts=240
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    backend_status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' springboot-backend 2>/dev/null || true)"
    frontend_status="$(docker inspect -f '{{.State.Status}}' vite-frontend 2>/dev/null || true)"
    if [[ "${backend_status}" == "healthy" && "${frontend_status}" == "running" ]]; then
      log "all services are running"
      return 0
    fi
    if ((attempt == 1 || attempt % 15 == 0)); then
      log "waiting for services (${attempt}/${max_attempts}): backend=${backend_status:-missing}, frontend=${frontend_status:-missing}"
    fi
    sleep 2
  done
  compose ps || true
  return 1
}

pull_release_images() {
  log "pulling Flux Panel ${PANEL_VERSION:-$(read_env_value PANEL_VERSION)} images"
  compose pull mysql backend frontend || return 1
}

deploy_release() {
  pull_release_images || return 1
  compose up -d --no-build || return 1
  wait_for_services || return 1
}

cleanup_old_release_images() {
  local current_version="$1"
  local previous_version="$2"
  local registry
  registry="$(read_env_value IMAGE_REGISTRY)"
  [[ -n "${registry}" ]] || registry="ghcr.io/norwayxz"

  local component repository reference tag
  for component in backend frontend; do
    repository="${registry}/flux-panel-${component}"
    while IFS= read -r reference; do
      [[ -n "${reference}" ]] || continue
      tag="${reference##*:}"
      [[ "${tag}" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || continue
      [[ "${tag}" == "${current_version}" || "${tag}" == "${previous_version}" ]] && continue
      docker image rm "${reference}" >/dev/null 2>&1 || true
    done < <(docker image ls "${repository}" --format '{{.Repository}}:{{.Tag}}')
  done
}

installed_compose_file() {
  if [[ -f "${INSTALL_DIR}/docker-compose.yml" ]]; then
    printf '%s\n' "${INSTALL_DIR}/docker-compose.yml"
  elif [[ -f "${SOURCE_COMPOSE_FILE}" ]]; then
    printf '%s\n' "${SOURCE_COMPOSE_FILE}"
  fi
}

install_panel() {
  check_host
  check_disk_capacity
  if [[ -n "$(installed_compose_file)" ]]; then
    fail "Flux Panel is already installed in ${INSTALL_DIR}; use the update command"
  fi
  if docker ps -a --format '{{.Names}}' | grep -Eq '^(gost-mysql|springboot-backend|vite-frontend)$'; then
    fail "Flux Panel container names already exist; remove the old installation or use its update procedure first"
  fi

  local staging panel_version
  staging="$(mktemp -d)"
  trap 'rm -rf "${staging:-}"' EXIT
  download_source "${staging}"
  panel_version="$(project_version "${staging}")"
  ensure_environment "${panel_version}"

  mkdir -p "$(dirname "${INSTALL_DIR}")"
  rm -rf "${INSTALL_DIR}"
  mv "${staging}" "${INSTALL_DIR}"
  trap - EXIT

  install_update_service

  log "downloading prebuilt Flux Panel ${panel_version} images"
  deploy_release || fail "services did not become healthy; run: docker logs springboot-backend"

  local installed_frontend_port
  installed_frontend_port="$(read_env_value FRONTEND_PORT)"
  log "installation complete: http://SERVER_IP:${installed_frontend_port}"
  log "default login: admin_user / admin_user"
  log "change the default password immediately after signing in"
}

update_panel() {
  check_host
  check_disk_capacity
  require_command flock
  [[ -f "${ENV_FILE}" ]] || fail "configuration not found: ${ENV_FILE}"
  local previous_compose
  previous_compose="$(installed_compose_file)"
  [[ -n "${previous_compose}" ]] || fail "installation not found: ${INSTALL_DIR}"

  mkdir -p "$(dirname "${UPDATE_LOCK_FILE}")"
  exec 9>"${UPDATE_LOCK_FILE}"
  flock -n 9 || fail "another Flux Panel update is already running"

  local staging backup target_version previous_version
  staging="$(mktemp -d)"
  backup="${INSTALL_DIR}.previous"
  trap 'rm -rf "${staging:-}"' EXIT
  download_source "${staging}"
  target_version="$(project_version "${staging}")"
  previous_version="$(read_env_value PANEL_VERSION)"
  if [[ -z "${previous_version}" && -f "${INSTALL_DIR}/VERSION" ]]; then
    previous_version="$(project_version "${INSTALL_DIR}")"
  fi

  log "replacing application source"
  rm -rf "${backup}"
  mv "${INSTALL_DIR}" "${backup}"
  mv "${staging}" "${INSTALL_DIR}"
  trap - EXIT
  COMPOSE_FILE="${INSTALL_DIR}/docker-compose.yml"
  ensure_runtime_defaults
  set_env_value PANEL_VERSION "${target_version}"

  install_update_service

  if ! deploy_release; then
    log "update failed or services did not become healthy; restoring the previous release"
    rm -rf "${INSTALL_DIR}"
    mv "${backup}" "${INSTALL_DIR}"
    COMPOSE_FILE="${previous_compose}"
    if [[ -n "${previous_version}" ]]; then
      set_env_value PANEL_VERSION "${previous_version}"
    fi
    install_update_service
    if [[ "${previous_compose}" == "${SOURCE_COMPOSE_FILE}" ]]; then
      compose up -d --build
    else
      compose up -d --no-build
    fi
    wait_for_services || fail "update and rollback both failed; inspect the container logs immediately"
    fail "update failed and the previous release was restored"
  fi
  rm -rf "${backup}"
  set_env_value PREVIOUS_PANEL_VERSION "${previous_version}"
  cleanup_old_release_images "${target_version}" "${previous_version}"
  log "update complete"
}

rollback_panel() {
  check_host
  check_disk_capacity
  require_command flock
  [[ -f "${ENV_FILE}" ]] || fail "configuration not found: ${ENV_FILE}"
  COMPOSE_FILE="$(installed_compose_file)"
  [[ -n "${COMPOSE_FILE}" ]] || fail "installation not found: ${INSTALL_DIR}"

  mkdir -p "$(dirname "${UPDATE_LOCK_FILE}")"
  exec 9>"${UPDATE_LOCK_FILE}"
  flock -n 9 || fail "another Flux Panel update or rollback is already running"

  local current_version previous_version
  current_version="$(read_env_value PANEL_VERSION)"
  previous_version="$(read_env_value PREVIOUS_PANEL_VERSION)"
  [[ -n "${previous_version}" ]] || fail "no previous release is recorded; update successfully once before using rollback"
  [[ "${previous_version}" != "${current_version}" ]] || fail "previous release is the same as the current release"

  log "rolling back Flux Panel ${current_version} -> ${previous_version}"
  set_env_value PANEL_VERSION "${previous_version}"
  set_env_value PREVIOUS_PANEL_VERSION "${current_version}"
  if ! deploy_release; then
    log "rollback target failed health checks; restoring ${current_version}"
    set_env_value PANEL_VERSION "${current_version}"
    set_env_value PREVIOUS_PANEL_VERSION "${previous_version}"
    deploy_release || fail "rollback and recovery both failed; inspect the container logs immediately"
    fail "rollback failed and ${current_version} was restored"
  fi
  cleanup_old_release_images "${previous_version}" "${current_version}"
  log "rollback complete; current release: ${previous_version}"
}

uninstall_panel() {
  check_host
  remove_update_service 0
  COMPOSE_FILE="$(installed_compose_file)"
  if [[ -n "${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
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

  COMPOSE_FILE="$(installed_compose_file)"
  if [[ -n "${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
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
  COMPOSE_FILE="$(installed_compose_file)"
  if [[ -n "${COMPOSE_FILE}" && -f "${ENV_FILE}" ]]; then
    compose ps
  else
    docker ps --filter name=gost-mysql --filter name=springboot-backend --filter name=vite-frontend
  fi
}

usage() {
  cat <<'EOF'
Usage: flux-panel.sh <install|update|rollback|uninstall|purge|status>

Environment variables:
  FLUX_PANEL_FRONTEND_PORT  Public web port, default: 6366
  FLUX_PANEL_BACKEND_PORT   Agent/API port, default: 6365
  FLUX_PANEL_BRANCH         Git branch to install, default: main
  FLUX_PANEL_DIR            Application directory, default: /opt/flux-panel
  FLUX_PANEL_COMPOSE_FILE   Optional custom Compose file for development
  FLUX_PANEL_DISABLE_ONLINE_UPDATES=1  Skip systemd update service installation
  AUTO_UPDATE_ENABLED=0  Keep the request watcher but disable periodic release checks
  FLUX_PANEL_PURGE=1        Required confirmation for permanent deletion
EOF
}

main() {
  require_root
  case "${1:-}" in
    install) install_panel ;;
    update) update_panel ;;
    rollback) rollback_panel ;;
    uninstall) uninstall_panel ;;
    purge) purge_panel ;;
    status) show_status ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
