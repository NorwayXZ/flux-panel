#!/bin/sh

set -eu

RELEASE="${FLUX_PANEL_CONNECTOR_RELEASE:-2.49.0}"
INSTALL_DIR="${FLUX_CONNECTOR_INSTALL_DIR:-/Library/Application Support/FluxConnector}"
SERVICE_LABEL="com.fluxpanel.connector"
PLIST_PATH="${FLUX_CONNECTOR_PLIST_PATH:-/Library/LaunchDaemons/$SERVICE_LABEL.plist}"
LOG_PATH="${FLUX_CONNECTOR_LOG_PATH:-/Library/Logs/FluxConnector.log}"
SERVER_ADDR=""
SECRET=""
UNINSTALL_ONLY=0

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [ "$(id -u)" -eq 0 ] || fail "请使用 sudo 运行此脚本"
}

architecture() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64\n' ;;
    arm64|aarch64) printf 'arm64\n' ;;
    *) fail "不支持的 macOS 架构: $(uname -m)" ;;
  esac
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

service_loaded() {
  launchctl print "system/$SERVICE_LABEL" >/dev/null 2>&1
}

service_running() {
  launchctl print "system/$SERVICE_LABEL" 2>/dev/null | grep -Eq 'state = running|pid = [1-9][0-9]*'
}

wait_for_service_removal() {
  attempt=0
  while [ "$attempt" -lt 15 ]; do
    service_loaded || return 0
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

stop_service() {
  launchctl bootout "system/$SERVICE_LABEL" >/dev/null 2>&1 || true
  if ! wait_for_service_removal; then
    launchctl bootout system "$PLIST_PATH" >/dev/null 2>&1 || true
    wait_for_service_removal || return 1
  fi
}

start_service() {
  start_attempt=1
  while [ "$start_attempt" -le 3 ]; do
    if launchctl bootstrap system "$PLIST_PATH"; then
      launchctl enable "system/$SERVICE_LABEL" >/dev/null 2>&1 || true
      launchctl kickstart -k "system/$SERVICE_LABEL" >/dev/null 2>&1 || true
      check_attempt=0
      while [ "$check_attempt" -lt 10 ]; do
        service_running && return 0
        check_attempt=$((check_attempt + 1))
        sleep 1
      done
    fi
    printf 'LaunchDaemon 启动失败，准备第 %s/3 次重试\n' "$start_attempt" >&2
    stop_service || true
    sleep 1
    start_attempt=$((start_attempt + 1))
  done
  return 1
}

while getopts "a:s:r:uh" opt; do
  case "$opt" in
    a) SERVER_ADDR="$OPTARG" ;;
    s) SECRET="$OPTARG" ;;
    r) RELEASE="$OPTARG" ;;
    u) UNINSTALL_ONLY=1 ;;
    h) printf '用法: sudo %s -a 面板地址 -s 密钥，或 sudo %s -u 卸载\n' "$0" "$0"; exit 0 ;;
    *) exit 1 ;;
  esac
done

require_root
if [ "$UNINSTALL_ONLY" = "1" ]; then
  stop_service || true
  rm -f "$PLIST_PATH"
  rm -rf "$INSTALL_DIR"
  printf 'Flux Connector 已卸载\n'
  exit 0
fi
[ -n "$SERVER_ADDR" ] && [ -n "$SECRET" ] || fail "面板地址和密钥不能为空"
command -v curl >/dev/null 2>&1 || fail "缺少 curl"

arch="$(architecture)"
download_url="https://github.com/NorwayXZ/flux-panel/releases/download/$RELEASE/gost-darwin-$arch"
download_path="/tmp/flux-connector-$arch.$$"
backup_path="$INSTALL_DIR/gost.previous"
config_backup_path="$INSTALL_DIR/config.previous.json"
plist_backup_path="$INSTALL_DIR/$SERVICE_LABEL.previous.plist"

printf '下载 Flux Connector: macOS %s\n' "$arch"
curl -fL --retry 3 --connect-timeout 15 "$download_url" -o "$download_path"
[ -s "$download_path" ] || fail "Connector 下载失败"
chmod 755 "$download_path"
xattr -d com.apple.quarantine "$download_path" >/dev/null 2>&1 || true

stop_service || fail "旧 LaunchDaemon 未能在 15 秒内停止，请稍后重试"
mkdir -p "$INSTALL_DIR"
if [ -f "$INSTALL_DIR/gost" ]; then
  cp -p "$INSTALL_DIR/gost" "$backup_path"
fi
if [ -f "$INSTALL_DIR/config.json" ]; then
  cp -p "$INSTALL_DIR/config.json" "$config_backup_path"
fi
if [ -f "$PLIST_PATH" ]; then
  cp -p "$PLIST_PATH" "$plist_backup_path"
fi
mv -f "$download_path" "$INSTALL_DIR/gost"

cat > "$INSTALL_DIR/config.json" <<EOF
{
  "addr": "$(json_escape "$SERVER_ADDR")",
  "secret": "$(json_escape "$SECRET")",
  "role": "connector"
}
EOF
[ -f "$INSTALL_DIR/gost.json" ] || printf '{}\n' > "$INSTALL_DIR/gost.json"
chmod 600 "$INSTALL_DIR/config.json" "$INSTALL_DIR/gost.json"

cat > "$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$SERVICE_LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$INSTALL_DIR/gost</string>
    <string>-agent-config</string><string>$INSTALL_DIR/config.json</string>
    <string>-C</string><string>$INSTALL_DIR/gost.json</string>
  </array>
  <key>WorkingDirectory</key><string>$INSTALL_DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$LOG_PATH</string>
  <key>StandardErrorPath</key><string>$LOG_PATH</string>
</dict>
</plist>
EOF
chmod 644 "$PLIST_PATH"
chown root:wheel "$PLIST_PATH" "$INSTALL_DIR/gost" >/dev/null 2>&1 || true
plutil -lint "$PLIST_PATH" >/dev/null || fail "LaunchDaemon 配置格式无效"

if start_service; then
  rm -f "$backup_path" "$config_backup_path" "$plist_backup_path"
  printf 'Flux Connector 安装完成，LaunchDaemon 已启动\n'
  printf '配置目录: %s\n' "$INSTALL_DIR"
  exit 0
fi

stop_service || true
if [ -f "$backup_path" ]; then
  mv -f "$backup_path" "$INSTALL_DIR/gost"
else
  rm -f "$INSTALL_DIR/gost"
fi
if [ -f "$config_backup_path" ]; then
  mv -f "$config_backup_path" "$INSTALL_DIR/config.json"
else
  rm -f "$INSTALL_DIR/config.json"
fi
if [ -f "$plist_backup_path" ]; then
  mv -f "$plist_backup_path" "$PLIST_PATH"
else
  rm -f "$PLIST_PATH"
fi
if [ -f "$INSTALL_DIR/gost" ] && [ -f "$PLIST_PATH" ] && start_service; then
  fail "新版本启动失败，旧版本已恢复并重新启动"
fi
fail "新版本启动失败，旧版本也未能恢复；请执行: sudo launchctl bootstrap system '$PLIST_PATH'"
