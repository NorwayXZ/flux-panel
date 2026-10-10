#!/bin/sh
set -eu

RELEASE="${FLUX_PANEL_DNS_AGENT_RELEASE:-2.56.0}"
DIRECTORY=/etc/flux-panel-dns
SERVICE=/etc/init.d/flux-panel-dns
STATE="$DIRECTORY/state.json"
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
download() {
  if command -v curl >/dev/null 2>&1; then curl -fL --retry 2 --connect-timeout 10 --max-time 180 "$1" -o "$2"
  else wget -T 30 "$1" -O "$2"; fi
}
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g;s/"/\\"/g'; }
stop_current_agent() {
  [ -x "$SERVICE" ] || return 0
  previous_pids=""
  for executable in /proc/[0-9]*/exe; do
    target="$(readlink "$executable" 2>/dev/null || true)"
    case "$target" in "$DIRECTORY/agent"|"$DIRECTORY/agent.previous"|"$DIRECTORY/agent (deleted)"|"$DIRECTORY/agent.previous (deleted)") ;; *) continue;; esac
    process_dir="${executable%/exe}"
    previous_pids="$previous_pids ${process_dir##*/}"
  done
  "$SERVICE" stop
  attempt=0
  while [ "$attempt" -lt 35 ]; do
    active=0
    for pid in $previous_pids; do kill -0 "$pid" 2>/dev/null && active=1; done
    [ "$active" = 1 ] || break
    sleep 1; attempt=$((attempt+1))
  done
  [ "${active:-0}" = 0 ] || fail '旧 DNS Agent 尚未退出，未替换或删除程序，请稍后重试'
}

[ "$(id -u)" = 0 ] || fail '请使用 root 运行'
[ -f /etc/openwrt_release ] && [ -f /etc/rc.common ] || fail '仅支持 OpenWrt/procd 系统'
command -v uci >/dev/null 2>&1 || fail '缺少 uci'
action="${1:-}"
if [ "$action" = uninstall ]; then
  stop_current_agent
  if [ -x "$SERVICE" ]; then "$SERVICE" disable; fi
  if [ -f "$DIRECTORY/dnsmasq-confdir" ]; then
    confdir="$(cat "$DIRECTORY/dnsmasq-confdir")"
    case "$confdir" in /etc/*|/tmp/*) rm -f "$confdir/flux-panel-smart-entry.conf";; *) fail '保存的 DNS 配置目录不安全';; esac
  fi
  if [ -f "$DIRECTORY/owned-confdir-setting" ]; then
    section="$(cat "$DIRECTORY/owned-confdir-setting")"
    current="$(uci -q get "dhcp.$section.confdir" || true)"
    owned_value="$(cat "$DIRECTORY/owned-confdir-value" 2>/dev/null || printf '%s' "${confdir:-}")"
    if [ "$current" = "$owned_value" ]; then uci -q delete "dhcp.$section.confdir"; uci commit dhcp; fi
  fi
  /etc/init.d/dnsmasq restart
  rm -f "$SERVICE" "$DIRECTORY/agent" "$DIRECTORY/agent.next" "$DIRECTORY/agent.previous" "$DIRECTORY/config.json" "$STATE" "$STATE.tmp" "$DIRECTORY/dnsmasq-confdir" "$DIRECTORY/owned-confdir-setting" "$DIRECTORY/owned-confdir-value"
  rmdir "$DIRECTORY" 2>/dev/null || true
  printf 'OpenWrt DNS Agent 已卸载，原有 DNS 规则保留\n'
  exit 0
fi
[ "$action" = install ] || fail '用法：install 面板地址 密钥，或 uninstall'
command -v ip >/dev/null 2>&1 || fail '请先安装 ip-tiny 或 ip-full'
command -v sha256sum >/dev/null 2>&1 || fail '缺少 sha256sum'
address="${2:-}"; secret="${3:-}"
[ -n "$address" ] && [ -n "$secret" ] || fail '缺少面板地址或密钥'
case "$address$secret" in *'
'*) fail '地址或密钥包含无效字符';; esac
case "$(uname -m)" in
  x86_64|amd64) architecture=amd64;;
  aarch64|arm64) architecture=arm64;;
  armv7l) architecture=armv7;;
  mips) architecture=mips;;
  mipsel) architecture=mipsle;;
  *) fail "不支持架构 $(uname -m)，支持 amd64、arm64、ARMv7、mips、mipsel";;
esac
if [ "$architecture" = mips ]; then
  . /etc/openwrt_release
  case "${DISTRIB_ARCH:-}" in mipsel*) architecture=mipsle;; mips_*) architecture=mips;;
    *) fail '无法确认 MIPS 字节序，请检查 DISTRIB_ARCH';; esac
fi
if [ -f "$DIRECTORY/config.json" ]; then
  command -v jsonfilter >/dev/null 2>&1 || fail '检查现有安装需要 jsonfilter'
  old_address="$(jsonfilter -i "$DIRECTORY/config.json" -e '@.addr')"
  old_secret="$(jsonfilter -i "$DIRECTORY/config.json" -e '@.secret')"
  [ "$old_address" = "$address" ] && [ "$old_secret" = "$secret" ] || fail '此路由器已绑定另一身份，请先卸载旧 DNS Agent'
fi
section=""
for candidate in $(uci -q -X show dhcp | sed -n 's/^dhcp\.\([^=]*\)=dnsmasq$/\1/p'); do
  [ "$(uci -q get "dhcp.$candidate.disabled" || true)" != 1 ] || continue
  port="$(uci -q get "dhcp.$candidate.port" || true)"
  [ -z "$port" ] || [ "$port" = 53 ] || continue
  section="$candidate"; break
done
[ -n "$section" ] || fail '未找到 dnsmasq 实例'
confdir="$(uci -q get "dhcp.$section.confdir" || true)"
owned=0
if [ -z "$confdir" ]; then
  confdir="$(awk -F= '$1=="conf-dir" {sub(/,.*/,"",$2);print $2;exit}' "/var/etc/dnsmasq.conf.$section" 2>/dev/null || true)"
  [ -n "$confdir" ] || confdir="/tmp/dnsmasq.$section.d"
  owned=1
fi
confdir="${confdir%%,*}"
case "$confdir" in /etc/*|/tmp/*) ;; *) fail 'dnsmasq confdir 必须是 /etc 或 /tmp 下的绝对路径';; esac
case "$confdir" in *'..'*|*'
'*) fail 'dnsmasq confdir 无效';; esac
binary="flux-openwrt-dns-$architecture"
task_tmp="$(mktemp -d)"
trap 'rm -rf "$task_tmp"' EXIT HUP INT TERM
download "https://github.com/NorwayXZ/flux-panel/releases/download/$RELEASE/$binary" "$task_tmp/agent"
download "https://github.com/NorwayXZ/flux-panel/releases/download/$RELEASE/SHA256SUMS" "$task_tmp/SHA256SUMS"
expected="$(awk -v name="$binary" '$2==name {print $1;exit}' "$task_tmp/SHA256SUMS")"
actual="$(sha256sum "$task_tmp/agent" | awk '{print $1}')"
[ -n "$expected" ] && [ "$expected" = "$actual" ] || fail 'SHA256 校验失败'
chmod 755 "$task_tmp/agent"
[ "$("$task_tmp/agent" --agent-version)" = "$RELEASE" ] || fail '二进制版本不匹配'
mkdir -p "$DIRECTORY" "$confdir"
available_kb="$(df -Pk "$DIRECTORY" | awk 'NR==2 {print $4}')"
needed_kb="$(( ($(wc -c < "$task_tmp/agent")+1023)/1024+2048 ))"
[ "$available_kb" -ge "$needed_kb" ] || fail '安装分区空间不足，请先清理空间'
cp "$task_tmp/agent" "$DIRECTORY/agent.next"
chmod 755 "$DIRECTORY/agent.next"
stop_current_agent
if [ -f "$DIRECTORY/agent" ]; then mv "$DIRECTORY/agent" "$DIRECTORY/agent.previous"; fi
mv "$DIRECTORY/agent.next" "$DIRECTORY/agent"
umask 077
printf '{"addr":"%s","secret":"%s","openwrtDnsStatePath":"%s"}\n' "$(json_escape "$address")" "$(json_escape "$secret")" "$STATE" > "$DIRECTORY/config.json"
printf '%s\n' "$confdir" > "$DIRECTORY/dnsmasq-confdir"
if [ "$owned" = 1 ]; then
  printf '%s\n' "$section" > "$DIRECTORY/owned-confdir-setting"
  printf '%s\n' "$confdir" > "$DIRECTORY/owned-confdir-value"
  uci set "dhcp.$section.confdir=$confdir"
  uci commit dhcp
fi
cat > "$SERVICE" <<'EOF'
#!/bin/sh /etc/rc.common
USE_PROCD=1
START=98
STOP=10
start_service() {
  procd_open_instance
  procd_set_param command /etc/flux-panel-dns/agent -agent-config /etc/flux-panel-dns/config.json
  procd_set_param respawn 3600 5 0
  procd_set_param stdout 1
  procd_set_param stderr 1
  procd_set_param term_timeout 30
  procd_close_instance
}
EOF
chmod 755 "$SERVICE"
"$SERVICE" enable
"$SERVICE" start
sleep 2
if ! "$SERVICE" status >/dev/null 2>&1; then
  if [ -f "$DIRECTORY/agent.previous" ]; then
    "$SERVICE" stop
    mv "$DIRECTORY/agent.previous" "$DIRECTORY/agent"
    "$SERVICE" start
  fi
  fail 'DNS Agent 未能正常启动，请查看 logread -e flux-panel-dns；更新时已尝试恢复原程序'
fi
rm -f "$DIRECTORY/agent.previous"
printf 'OpenWrt DNS Agent 已安装，域名规则将自动同步并验证；在三网优化中查看解析结果。\n'
printf '客户端需使用此路由器 DNS；业务连接直接到入口。\n'
