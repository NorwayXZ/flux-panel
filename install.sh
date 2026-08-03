#!/bin/sh

set -u

AGENT_RELEASE="${FLUX_PANEL_AGENT_RELEASE:-2.46.1}"
AGENT_REPOSITORY="${FLUX_PANEL_AGENT_REPOSITORY:-NorwayXZ/flux-panel}"
INSTALL_DIR="${GOST_INSTALL_DIR:-/etc/gost}"
SYSTEMD_DIR="${GOST_SYSTEMD_DIR:-/etc/systemd/system}"
OPENRC_DIR="${GOST_OPENRC_DIR:-/etc/init.d}"
SERVICE_NAME="gost"
PID_FILE="${GOST_PID_FILE:-/run/gost.pid}"
PROC_ROOT="${GOST_PROC_ROOT:-/proc}"
KILL_COMMAND="${GOST_KILL_COMMAND:-kill}"
SERVER_ADDR="${SERVER_ADDR:-}"
SECRET="${SECRET:-}"
AGENT_ROLE="${AGENT_ROLE:-node}"
SERVICE_MANAGER=""
UNINSTALL_ONLY=0
UPDATE_ONLY=0
REPLACE_IDENTITY=0
DOWNLOAD_URL=""
CHECKSUM_URL=""
BINARY_NAME=""
MIN_FREE_KB="${GOST_MIN_FREE_KB:-98304}"

configure_role_paths() {
  if [ "$AGENT_ROLE" = "connector" ]; then
    [ "${GOST_INSTALL_DIR+x}" = "x" ] || INSTALL_DIR="/etc/flux-connector"
    [ "${GOST_SERVICE_NAME+x}" = "x" ] || SERVICE_NAME="flux-connector"
    [ "${GOST_PID_FILE+x}" = "x" ] || PID_FILE="/run/flux-connector.pid"
  elif [ "${GOST_SERVICE_NAME+x}" = "x" ]; then
    SERVICE_NAME="$GOST_SERVICE_NAME"
  fi
}

log() {
  printf '%s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [ "$(id -u)" -eq 0 ] || fail "请使用 root 用户运行此脚本"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少必要命令: $1"
}

get_architecture() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64\n' ;;
    aarch64|arm64) printf 'arm64\n' ;;
    *) fail "不支持的系统架构: $(uname -m)，目前支持 amd64 和 arm64" ;;
  esac
}

build_download_url() {
  printf 'https://github.com/%s/releases/download/%s/%s\n' \
    "$AGENT_REPOSITORY" "$AGENT_RELEASE" "$BINARY_NAME"
}

detect_download_url() {
  BINARY_NAME="gost-$(get_architecture)"
  if [ -n "${GOST_DOWNLOAD_URL:-}" ]; then
    DOWNLOAD_URL="$GOST_DOWNLOAD_URL"
    CHECKSUM_URL=""
    return
  fi

  DOWNLOAD_URL="$(build_download_url)"
  CHECKSUM_URL="https://github.com/$AGENT_REPOSITORY/releases/download/$AGENT_RELEASE/SHA256SUMS"
  country="$(curl -fsS --connect-timeout 3 --max-time 5 https://ipinfo.io/country 2>/dev/null || true)"
  if [ "$country" = "CN" ]; then
    DOWNLOAD_URL="https://ghfast.top/$DOWNLOAD_URL"
    CHECKSUM_URL="https://ghfast.top/$CHECKSUM_URL"
  fi
}

detect_service_manager() {
  if [ -n "${GOST_SERVICE_MANAGER:-}" ]; then
    SERVICE_MANAGER="$GOST_SERVICE_MANAGER"
  elif command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    SERVICE_MANAGER="systemd"
  elif command -v rc-service >/dev/null 2>&1 && command -v rc-update >/dev/null 2>&1; then
    SERVICE_MANAGER="openrc"
  else
    fail "未检测到可用的服务管理器；支持 systemd 和 Alpine/OpenRC"
  fi

  case "$SERVICE_MANAGER" in
    systemd|openrc) ;;
    *) fail "不支持的服务管理器: $SERVICE_MANAGER" ;;
  esac
}

service_exists() {
  case "$SERVICE_MANAGER" in
    systemd) [ -f "$SYSTEMD_DIR/$SERVICE_NAME.service" ] ;;
    openrc) [ -f "$OPENRC_DIR/$SERVICE_NAME" ] ;;
  esac
}

stop_service() {
  if ! service_exists; then
    return 0
  fi
  case "$SERVICE_MANAGER" in
    systemd) systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true ;;
    openrc) rc-service "$SERVICE_NAME" stop >/dev/null 2>&1 || true ;;
  esac
}

find_agent_pids() {
  for proc_dir in "$PROC_ROOT"/[0-9]*; do
    [ -d "$proc_dir" ] || continue
    executable="$(readlink "$proc_dir/exe" 2>/dev/null || true)"
    case "$executable" in
      "$INSTALL_DIR/gost"|"$INSTALL_DIR/gost (deleted)")
        printf '%s\n' "${proc_dir##*/}"
        ;;
    esac
  done
}

stop_orphaned_processes() {
  pids="$(find_agent_pids)"
  if [ -n "$pids" ]; then
    log "清理未被服务管理器接管的 GOST 进程"
    for pid in $pids; do
      "$KILL_COMMAND" "$pid" >/dev/null 2>&1 || true
    done

    attempts=0
    while [ "$attempts" -lt 5 ] && [ -n "$(find_agent_pids)" ]; do
      sleep 1
      attempts=$((attempts + 1))
    done

    remaining_pids="$(find_agent_pids)"
    for pid in $remaining_pids; do
      "$KILL_COMMAND" -9 "$pid" >/dev/null 2>&1 || true
    done
  fi

  rm -f "$PID_FILE"
}

disable_service() {
  if ! service_exists; then
    return 0
  fi
  case "$SERVICE_MANAGER" in
    systemd) systemctl disable "$SERVICE_NAME" >/dev/null 2>&1 || true ;;
    openrc) rc-update del "$SERVICE_NAME" default >/dev/null 2>&1 || true ;;
  esac
}

enable_service() {
  case "$SERVICE_MANAGER" in
    systemd) systemctl enable "$SERVICE_NAME" >/dev/null ;;
    openrc) rc-update add "$SERVICE_NAME" default >/dev/null ;;
  esac
}

start_service() {
  case "$SERVICE_MANAGER" in
    systemd) systemctl start "$SERVICE_NAME" ;;
    openrc) rc-service "$SERVICE_NAME" start ;;
  esac
}

service_is_active() {
  case "$SERVICE_MANAGER" in
    systemd) systemctl is-active --quiet "$SERVICE_NAME" ;;
    openrc) rc-service "$SERVICE_NAME" status >/dev/null 2>&1 ;;
  esac
}

service_status_text() {
  case "$SERVICE_MANAGER" in
    systemd) systemctl is-active "$SERVICE_NAME" 2>/dev/null || true ;;
    openrc) rc-service "$SERVICE_NAME" status 2>&1 || true ;;
  esac
}

print_log_hint() {
  case "$SERVICE_MANAGER" in
    systemd) log "请执行: journalctl -u $SERVICE_NAME -f" ;;
    openrc) log "请执行: rc-service $SERVICE_NAME status && tail -f /var/log/gost.log" ;;
  esac
}

write_service_definition() {
  case "$SERVICE_MANAGER" in
    systemd)
      mkdir -p "$SYSTEMD_DIR"
      cat > "$SYSTEMD_DIR/$SERVICE_NAME.service" <<EOF
[Unit]
Description=Gost Proxy Service
After=network.target
StartLimitIntervalSec=0

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/gost -C $INSTALL_DIR/gost.json
Restart=always
RestartSec=1

[Install]
WantedBy=multi-user.target
EOF
      systemctl daemon-reload
      ;;
    openrc)
      mkdir -p "$OPENRC_DIR"
      cat > "$OPENRC_DIR/$SERVICE_NAME" <<EOF
#!/sbin/openrc-run

name="Gost Proxy Service"
description="Flux Panel GOST agent"
command="$INSTALL_DIR/gost"
command_args="-C $INSTALL_DIR/gost.json"
command_background="yes"
directory="$INSTALL_DIR"
pidfile="$PID_FILE"
output_log="/var/log/gost.log"
error_log="/var/log/gost.log"

depend() {
  use net
  after firewall
}
EOF
      chmod 755 "$OPENRC_DIR/$SERVICE_NAME"
      ;;
  esac
}

remove_service_definition() {
  case "$SERVICE_MANAGER" in
    systemd)
      rm -f "$SYSTEMD_DIR/$SERVICE_NAME.service"
      systemctl daemon-reload
      ;;
    openrc)
      rm -f "$OPENRC_DIR/$SERVICE_NAME"
      ;;
  esac
}

check_and_install_tcpkill() {
  command -v tcpkill >/dev/null 2>&1 && return 0
  [ -f /etc/os-release ] || return 0

  # shellcheck disable=SC1091
  . /etc/os-release
  case "${ID:-}" in
    ubuntu|debian)
      apt-get update >/dev/null 2>&1 && apt-get install -y dsniff >/dev/null 2>&1 || true
      ;;
    alpine)
      apk add --no-cache dsniff >/dev/null 2>&1 || true
      ;;
    centos|rhel|fedora)
      if command -v dnf >/dev/null 2>&1; then
        dnf install -y dsniff >/dev/null 2>&1 || true
      elif command -v yum >/dev/null 2>&1; then
        yum install -y dsniff >/dev/null 2>&1 || true
      fi
      ;;
    arch|manjaro)
      pacman -S --noconfirm dsniff >/dev/null 2>&1 || true
      ;;
  esac
}

storage_value() {
  mode="$1"
  path="$2"
  case "$mode" in
    blocks) df -Pk "$path" 2>/dev/null | awk 'NR == 2 {print $4}' ;;
    inodes) df -Pi "$path" 2>/dev/null | awk 'NR == 2 {print $4}' ;;
  esac
}

prepare_download_destination() {
  destination="$1"
  directory="$(dirname "$destination")"
  probe="$directory/.gost-write-test.$$"

  mkdir -p "$directory" || fail "无法创建 Agent 安装目录: $directory"
  rm -f "$destination" "$destination.sha256sums"
  if ! : > "$probe" 2>/dev/null; then
    fail "Agent 安装目录不可写: $directory；请检查磁盘是否只读或已满"
  fi
  rm -f "$probe"

  available_kb="$(storage_value blocks "$directory")"
  if [ -n "$available_kb" ] && [ "$available_kb" -eq "$available_kb" ] 2>/dev/null \
      && [ "$available_kb" -lt "$MIN_FREE_KB" ]; then
    fail "Agent 安装分区空间不足：可用 $((available_kb / 1024)) MB，至少需要 $((MIN_FREE_KB / 1024)) MB；请先执行 df -h 和 du -xhd1 / 排查"
  fi

  available_inodes="$(storage_value inodes "$directory")"
  if [ -n "$available_inodes" ] && [ "$available_inodes" -eq "$available_inodes" ] 2>/dev/null \
      && [ "$available_inodes" -lt 16 ]; then
    fail "Agent 安装分区 inode 不足：仅剩 $available_inodes；请先执行 df -i 排查"
  fi
}

download_failure() {
  destination="$1"
  status="$2"
  directory="$(dirname "$destination")"
  available_kb="$(storage_value blocks "$directory")"
  rm -f "$destination" "$destination.sha256sums"
  if [ "$status" = "23" ] && [ -n "$available_kb" ]; then
    fail "Agent 下载写入失败（curl $status，可用磁盘约 $((available_kb / 1024)) MB）；请检查 df -h、df -i 以及文件系统是否只读"
  fi
  fail "Agent 下载失败（curl $status）；已清理残缺文件，请检查网络和下载地址"
}

download_binary() {
  destination="$1"
  prepare_download_destination "$destination"
  log "下载 GOST Agent: $(get_architecture)"
  if curl -fL --retry 3 --connect-timeout 15 "$DOWNLOAD_URL" -o "$destination"; then
    :
  else
    download_status="$?"
    download_failure "$destination" "$download_status"
  fi
  [ -s "$destination" ] || fail "下载失败，请检查网络或下载地址"
  verify_binary_checksum "$destination"
  chmod 755 "$destination"
  verify_binary_version "$destination"
}

calculate_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail "缺少 SHA256 校验工具（sha256sum 或 shasum）"
  fi
}

verify_binary_checksum() {
  destination="$1"
  expected="${GOST_SHA256:-}"
  checksum_file="$destination.sha256sums"
  if [ -z "$expected" ] && [ -n "$CHECKSUM_URL" ]; then
    if ! curl -fL --retry 3 --connect-timeout 15 "$CHECKSUM_URL" -o "$checksum_file"; then
      rm -f "$checksum_file" "$destination"
      fail "无法下载 Agent SHA256 校验文件；已删除未校验的 Agent 文件"
    fi
    expected="$(awk -v file="$BINARY_NAME" '$2 == file || $2 == "*" file {print $1; exit}' "$checksum_file")"
    rm -f "$checksum_file"
    [ -n "$expected" ] || fail "校验文件中缺少 $BINARY_NAME"
  fi
  if [ -z "$expected" ]; then
    log "使用自定义下载地址，未提供 GOST_SHA256，跳过校验"
    return 0
  fi
  actual="$(calculate_sha256 "$destination")"
  [ "$actual" = "$expected" ] || fail "Agent SHA256 校验失败"
  log "Agent SHA256 校验通过"
}

verify_binary_version() {
  destination="$1"
  if [ -n "${GOST_DOWNLOAD_URL:-}" ]; then
    return 0
  fi
  actual_version="$($destination --agent-version 2>/dev/null || true)"
  [ "$actual_version" = "$AGENT_RELEASE" ] || fail "Agent 版本校验失败，期望 $AGENT_RELEASE，实际 ${actual_version:-未知}"
  log "Agent 版本校验通过: $actual_version"
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

write_agent_config() {
  escaped_addr="$(json_escape "$SERVER_ADDR")"
  escaped_secret="$(json_escape "$SECRET")"
  escaped_role="$(json_escape "$AGENT_ROLE")"
  cat > "$INSTALL_DIR/config.json" <<EOF
{
  "addr": "$escaped_addr",
  "secret": "$escaped_secret",
  "role": "$escaped_role"
}
EOF

  if [ ! -f "$INSTALL_DIR/gost.json" ]; then
    printf '{}\n' > "$INSTALL_DIR/gost.json"
  fi
  chmod 600 "$INSTALL_DIR/config.json" "$INSTALL_DIR/gost.json"
}

read_config_value() {
  key="$1"
  file="$2"
  sed -n "s/^[[:space:]]*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

protect_existing_identity() {
  config_file="$INSTALL_DIR/config.json"
  [ -f "$config_file" ] || return 0
  old_addr="$(read_config_value addr "$config_file")"
  old_secret="$(read_config_value secret "$config_file")"
  old_role="$(read_config_value role "$config_file")"
  [ -n "$old_role" ] || old_role="node"
  if [ "$old_addr" = "$SERVER_ADDR" ] && [ "$old_secret" = "$SECRET" ] && [ "$old_role" = "$AGENT_ROLE" ]; then
    return 0
  fi
  [ "$REPLACE_IDENTITY" = "1" ] || fail "检测到现有 Agent 身份与本次安装不同。为防止节点密钥被覆盖，请核对安装命令；确认替换时追加 -R"
  log "已确认替换现有 Agent 身份"
}

get_config_params() {
  if [ -z "$SERVER_ADDR" ]; then
    printf '服务器地址: '
    read -r SERVER_ADDR
  fi
  if [ -z "$SECRET" ]; then
    printf '密钥: '
    read -r SECRET
  fi
  [ -n "$SERVER_ADDR" ] && [ -n "$SECRET" ] || fail "服务器地址和密钥不能为空"
  case "$AGENT_ROLE" in
    node|connector) ;;
    *) fail "不支持的 Agent 角色: $AGENT_ROLE" ;;
  esac
}

install_gost() {
  log "开始安装 GOST Agent..."
  get_config_params
  protect_existing_identity
  detect_service_manager
  detect_download_url
  check_and_install_tcpkill

  mkdir -p "$INSTALL_DIR"
  download_binary "$INSTALL_DIR/gost.new"

  if service_exists; then
    log "停止已有 GOST 服务"
    stop_service
    disable_service
  fi
  stop_orphaned_processes

  mv -f "$INSTALL_DIR/gost.new" "$INSTALL_DIR/gost"
  write_agent_config
  write_service_definition
  enable_service
  start_service

  sleep 2
  if service_is_active; then
    log "安装完成，GOST 服务已启动并设置为开机启动"
    log "服务管理器: $SERVICE_MANAGER"
    log "配置目录: $INSTALL_DIR"
    log "服务状态: $(service_status_text)"
  else
    log "GOST 服务启动失败"
    print_log_hint
    exit 1
  fi
}

update_gost() {
  [ -d "$INSTALL_DIR" ] && [ -f "$INSTALL_DIR/config.json" ] || fail "GOST 尚未安装"
  detect_service_manager
  detect_download_url
  check_and_install_tcpkill

  log "下载最新 GOST Agent..."
  download_binary "$INSTALL_DIR/gost.new"
  cp -f "$INSTALL_DIR/gost" "$INSTALL_DIR/gost.previous"

  update_task="${FLUX_AGENT_UPDATE_TASK_ID:-manual-$(date +%s)-$$}"
  update_status="$INSTALL_DIR/.agent-update-status.json"
  update_ack="$INSTALL_DIR/.agent-update-connected-$update_task"
  rm -f "$update_ack"
  stop_service
  stop_orphaned_processes
  mv -f "$INSTALL_DIR/gost.new" "$INSTALL_DIR/gost"

  if ! write_service_definition || ! enable_service; then
    log "服务启动配置更新失败，正在恢复旧版本"
    mv -f "$INSTALL_DIR/gost.previous" "$INSTALL_DIR/gost"
    start_service || true
    fail "更新失败，旧版本已恢复"
  fi

  printf '{"taskId":"%s","targetVersion":"%s","state":"awaiting_reconnect"}\n' \
    "$update_task" "$AGENT_RELEASE" > "$update_status"
  start_service || true
  attempts=0
  while [ "$attempts" -lt 45 ]; do
    if [ -s "$update_ack" ] && service_is_active; then
      rm -f "$INSTALL_DIR/gost.previous" "$update_ack" "$update_status"
      log "更新完成，新 Agent 已连接面板并通过确认"
      return 0
    fi
    if [ "$attempts" -ge 3 ] && ! service_is_active; then
      break
    fi
    attempts=$((attempts + 1))
    sleep 1
  done

  log "新版本未能在 45 秒内连接面板，正在恢复旧版本"
  stop_service
  stop_orphaned_processes
  mv -f "$INSTALL_DIR/gost.previous" "$INSTALL_DIR/gost"
  printf '{"taskId":"%s","targetVersion":"%s","state":"rolled_back"}\n' \
    "$update_task" "$AGENT_RELEASE" > "$update_status"
  start_service || true
  rm -f "$update_ack"
  fail "更新失败，旧版本已恢复并重新启动"
}

uninstall_gost() {
  detect_service_manager
  if [ "$UNINSTALL_ONLY" != "1" ]; then
    printf '确认卸载 GOST 吗？此操作将删除所有相关文件 (y/N): '
    read -r confirm
    case "$confirm" in
      y|Y) ;;
      *) log "已取消卸载"; return 0 ;;
    esac
  fi

  stop_service
  stop_orphaned_processes
  disable_service
  remove_service_definition
  rm -rf "$INSTALL_DIR"
  log "卸载完成"
}

delete_self() {
  [ "${GOST_KEEP_SCRIPT:-0}" = "1" ] && return 0
  script_path="$(readlink -f "$0" 2>/dev/null || printf '%s' "$0")"
  rm -f "$script_path" 2>/dev/null || true
}

show_menu() {
  cat <<'EOF'
===============================================
             GOST Agent 管理脚本
===============================================
1. 安装
2. 更新
3. 卸载
4. 退出
===============================================
EOF
}

usage() {
  log "用法: $0 -a 面板地址 -s 密钥 [-r node|connector] [-R 确认替换现有身份]，$0 -U 更新，或 $0 -r connector -u 卸载"
}

main() {
  require_root
  require_command curl
  require_command sed
  require_command awk

  while getopts "a:s:r:uURh" opt; do
    case "$opt" in
      a) SERVER_ADDR="$OPTARG" ;;
      s) SECRET="$OPTARG" ;;
      r) AGENT_ROLE="$OPTARG" ;;
      u) UNINSTALL_ONLY=1 ;;
      U) UPDATE_ONLY=1 ;;
      R) REPLACE_IDENTITY=1 ;;
      h) usage; exit 0 ;;
      *) usage; exit 1 ;;
    esac
  done

  configure_role_paths

  if [ "$UPDATE_ONLY" = "1" ]; then
    update_gost
    delete_self
    exit 0
  fi

  if [ "$UNINSTALL_ONLY" = "1" ]; then
    uninstall_gost
    delete_self
    exit 0
  fi

  if [ -n "$SERVER_ADDR" ] && [ -n "$SECRET" ]; then
    install_gost
    delete_self
    exit 0
  fi

  while true; do
    show_menu
    printf '请输入选项 (1-4): '
    read -r choice
    case "$choice" in
      1) install_gost; delete_self; exit 0 ;;
      2) update_gost; delete_self; exit 0 ;;
      3) uninstall_gost; delete_self; exit 0 ;;
      4) exit 0 ;;
      *) log "无效选项，请输入 1-4" ;;
    esac
  done
}

main "$@"
