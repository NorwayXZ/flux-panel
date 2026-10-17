#!/usr/bin/env bash
# Trial of Mysterium residential exits on one node, before building them into the panel.
#
#   bash mysterium-poc.sh install             download myst, start it, create the account
#   bash mysterium-poc.sh info                account address, registration and balance
#   bash mysterium-poc.sh topup paypal 6.3    create a top-up and print the payment link
#   bash mysterium-poc.sh register            register the account once it has a balance
#   bash mysterium-poc.sh connect US --agree  connect to a residential node in that country
#   bash mysterium-poc.sh test                exit IP, latency, download speed and cost
#   bash mysterium-poc.sh down                disconnect
#   bash mysterium-poc.sh stop | uninstall    stop myst; uninstall keeps the account key unless --purge
#
# Everything listens on 127.0.0.1 only: the myst API on $API_PORT and the exit proxy on $PROXY_PORT.
set -euo pipefail

MYST_VERSION="${MYST_VERSION:-1.39.7}"
BASE="${MYST_DIR:-/opt/cloudnest-mysterium}"
API_PORT="${API_PORT:-4449}"
PROXY_PORT="${PROXY_PORT:-18900}"
MYST="$BASE/bin/myst"
API="http://127.0.0.1:$API_PORT"
PIDFILE="$BASE/myst.pid"
STATE="$BASE/test-state"
SELF="bash mysterium-poc.sh"

say() { printf '\033[1;36m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
die() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }
cli() { "$MYST" "$1" --tequilapi.port="$API_PORT" "${@:2}" 2>&1 | sed 's/\x1b\[[0-9;]*m//g'; }
running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

asset() {
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) echo myst_linux_amd64.tar.gz ;;
    Linux-aarch64 | Linux-arm64) echo myst_linux_arm64.tar.gz ;;
    Linux-armv7l | Linux-armv6l) echo myst_linux_arm.tar.gz ;;
    Darwin-arm64) echo myst_darwin_arm64.tar.gz ;;
    Darwin-x86_64) echo myst_darwin_amd64.tar.gz ;;
    *) die "不支持的系统：$(uname -s) $(uname -m)" ;;
  esac
}

start_daemon() {
  running && return 0
  mkdir -p "$BASE/data" "$BASE/logs"
  # --proxymode runs connections as local proxies without touching the system routes;
  # --proxy.bind.address keeps those proxies on loopback (the default is 0.0.0.0).
  nohup "$MYST" --data-dir="$BASE/data" --config-dir="$BASE/bin/config" --log-dir="$BASE/logs" \
    --proxymode --proxy.bind.address=127.0.0.1 \
    --tequilapi.address=127.0.0.1 --tequilapi.port="$API_PORT" --ui.enable=false daemon \
    >"$BASE/logs/daemon.out" 2>&1 &
  echo $! >"$PIDFILE"
  for _ in $(seq 1 30); do
    curl -fs -m 2 "$API/healthcheck" >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "myst 没有启动，日志：$BASE/logs/daemon.out"
}

install() {
  command -v curl >/dev/null || die "需要 curl"
  mkdir -p "$BASE/bin"
  if [ ! -x "$MYST" ]; then
    say "下载 myst $MYST_VERSION ..."
    curl -fL --retry 3 -o "$BASE/myst.tgz" \
      "https://github.com/mysteriumnetwork/node/releases/download/$MYST_VERSION/$(asset)"
    tar xzf "$BASE/myst.tgz" -C "$BASE/bin"
    rm -f "$BASE/myst.tgz"
  fi
  start_daemon
  # Creates the account (an identity key in $BASE/data/keystore) on first run and unlocks it.
  curl -fs -m 30 -X PUT "$API/identities/current" -H 'Content-Type: application/json' -d '{"passphrase":""}' >/dev/null
  info
  warn "账户密钥保存在 $BASE/data/keystore，里面有余额，请不要删除。"
}

info() {
  start_daemon
  cli account info
}

topup() {
  start_daemon
  local gateway="${1:-paypal}" usd="${2:-6.3}" country="${3:-US}"
  # The amount is in MYST; convert the USD amount with the current exchange rate.
  local rate
  rate=$(curl -fs -m 15 "https://api.coingecko.com/api/v3/simple/price?ids=mysterium&vs_currencies=usd" | sed -n 's/.*"usd":\([0-9.]*\).*/\1/p')
  [ -n "$rate" ] || die "取不到 MYST 汇率，可以直接用 --amount 指定 MYST 数量：$MYST account --tequilapi.port=$API_PORT topup ..."
  local myst
  myst=$(awk -v u="$usd" -v r="$rate" 'BEGIN { printf "%.2f", u / r }')
  say "创建充值订单：$gateway，约 $usd 美元 ≈ $myst MYST（汇率 $rate）"
  local currency=USD
  [ "$gateway" = coingate ] && currency="${4:-USDT}"
  cli account topup --amount "$myst" --currency "$currency" --gateway "$gateway" --country "$country"
  warn "用浏览器打开上面的付款链接，自己完成付款。到账后运行：$SELF register"
}

register() {
  start_daemon
  cli account register
  sleep 5
  cli account info
}

connect() {
  start_daemon
  local country="${1:-US}" agree="${2:-}"
  if [ "$agree" != "--agree" ]; then
    echo "连接需要同意 Mysterium 的用户条款："
    echo "  https://github.com/mysteriumnetwork/terms/blob/master/documents/TERMS_END_USER.md"
    die "看过并同意后，运行：$SELF connect $country --agree"
  fi
  cli connection down --proxy "$PROXY_PORT" >/dev/null 2>&1 || true
  say "连接 $country 家宽节点（按质量排序），本地代理 127.0.0.1:$PROXY_PORT ..."
  cli connection up --agreed-terms-and-conditions --country "$country" --location-type residential \
    --sort quality --proxy "$PROXY_PORT" --service-type wireguard
  balance >"$STATE.balance"
  date +%s >"$STATE.since"
  cli connection info --proxy "$PROXY_PORT" || true
}

balance() {
  cli account info | sed -n 's/.*Balance: *\([0-9.]*\).*/\1/p' | head -1
}

test_exit() {
  start_daemon
  local proxy="http://127.0.0.1:$PROXY_PORT"
  say "出口信息（Cloudflare）："
  curl -fs -m 20 -x "$proxy" https://www.cloudflare.com/cdn-cgi/trace | grep -E '^(ip|loc|colo)=' || die "代理不通，先运行 connect"
  say "IP 归属（ipinfo.io）："
  curl -fs -m 20 -x "$proxy" https://ipinfo.io/json | grep -E '"(ip|city|region|country|org)"' || true
  say "延迟（5 次访问 www.google.com 的首字节时间）："
  for _ in 1 2 3 4 5; do
    curl -s -o /dev/null -m 15 -x "$proxy" -w '  %{time_starttransfer}s\n' https://www.google.com/generate_204 || echo "  失败"
  done
  say "下载速度（25 MB）："
  curl -s -o /dev/null -m 120 -x "$proxy" -w '%{speed_download} %{time_total}' \
    "https://speed.cloudflare.com/__down?bytes=25000000" | awk '{ printf "  %.1f Mbps，用时 %.1f 秒\n", $1 * 8 / 1000000, $2 }'
  say "流量和扣费（从 connect 开始算；Mysterium 结算有延迟，几分钟后再看会更准）："
  cli connection info --proxy "$PROXY_PORT" | grep -iE 'traffic|session|duration|spent|consumed|bytes' || true
  local before now
  before=$(cat "$STATE.balance" 2>/dev/null || echo "")
  now=$(balance)
  if [ -n "$before" ] && [ -n "$now" ]; then
    awk -v b="$before" -v n="$now" 'BEGIN { printf "  余额 %s → %s MYST，已花 %.4f MYST\n", b, n, b - n }'
  else
    echo "  当前余额：$now MYST"
  fi
}

down() {
  start_daemon
  cli connection down --proxy "$PROXY_PORT"
}

stop() {
  if ! running; then
    echo "myst 没在运行"
    return 0
  fi
  local pid
  pid=$(cat "$PIDFILE")
  kill "$pid"
  # myst closes its sessions before exiting; give it a moment, then force it.
  for _ in $(seq 1 15); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PIDFILE"
  say "已停止 myst"
}

uninstall() {
  stop
  if [ "${1:-}" = "--purge" ]; then
    rm -rf "$BASE" && say "已删除 $BASE（包括账户密钥）"
  else
    find "$BASE" -mindepth 1 -maxdepth 1 ! -name data -exec rm -rf {} +
    say "已删除程序，账户密钥保留在 $BASE/data/keystore；彻底删除请加 --purge"
  fi
}

case "${1:-}" in
  install) install ;;
  info) info ;;
  topup) shift; topup "$@" ;;
  register) register ;;
  connect) shift; connect "$@" ;;
  test) test_exit ;;
  down) down ;;
  stop) stop ;;
  uninstall) shift; uninstall "$@" ;;
  *) cat <<'USAGE'
用法：
  bash mysterium-poc.sh install             下载并启动 myst，创建账户
  bash mysterium-poc.sh info                查看账户地址、注册状态和余额
  bash mysterium-poc.sh topup paypal 6.3    创建充值订单并打印付款链接（paypal / stripe / coingate）
  bash mysterium-poc.sh register            到账后注册账户
  bash mysterium-poc.sh connect US --agree  连接该国家的家宽节点（需同意 Mysterium 用户条款）
  bash mysterium-poc.sh test                测出口 IP、延迟、下载速度和扣费
  bash mysterium-poc.sh down                断开连接
  bash mysterium-poc.sh stop | uninstall    停止；uninstall 默认保留账户密钥，加 --purge 全删
USAGE
  ;;
esac
