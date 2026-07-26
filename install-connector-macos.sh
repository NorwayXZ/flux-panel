#!/bin/sh

set -eu

RELEASE="${FLUX_PANEL_CONNECTOR_RELEASE:-2.14.1}"
INSTALL_DIR="/Library/Application Support/FluxConnector"
SERVICE_LABEL="com.fluxpanel.connector"
PLIST_PATH="/Library/LaunchDaemons/$SERVICE_LABEL.plist"
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
  launchctl bootout "system/$SERVICE_LABEL" >/dev/null 2>&1 || true
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

printf '下载 Flux Connector: macOS %s\n' "$arch"
curl -fL --retry 3 --connect-timeout 15 "$download_url" -o "$download_path"
[ -s "$download_path" ] || fail "Connector 下载失败"
chmod 755 "$download_path"
xattr -d com.apple.quarantine "$download_path" >/dev/null 2>&1 || true

launchctl bootout "system/$SERVICE_LABEL" >/dev/null 2>&1 || true
mkdir -p "$INSTALL_DIR"
if [ -f "$INSTALL_DIR/gost" ]; then
  cp -p "$INSTALL_DIR/gost" "$backup_path"
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
  <key>StandardOutPath</key><string>/Library/Logs/FluxConnector.log</string>
  <key>StandardErrorPath</key><string>/Library/Logs/FluxConnector.log</string>
</dict>
</plist>
EOF
chmod 644 "$PLIST_PATH"

if launchctl bootstrap system "$PLIST_PATH" && launchctl enable "system/$SERVICE_LABEL"; then
  launchctl kickstart -k "system/$SERVICE_LABEL"
  sleep 2
  if launchctl print "system/$SERVICE_LABEL" >/dev/null 2>&1; then
    rm -f "$backup_path"
    printf 'Flux Connector 安装完成，LaunchDaemon 已启动\n'
    printf '配置目录: %s\n' "$INSTALL_DIR"
    exit 0
  fi
fi

launchctl bootout "system/$SERVICE_LABEL" >/dev/null 2>&1 || true
if [ -f "$backup_path" ]; then
  mv -f "$backup_path" "$INSTALL_DIR/gost"
  launchctl bootstrap system "$PLIST_PATH" >/dev/null 2>&1 || true
fi
fail "新版本启动失败，已尝试恢复旧版本"
