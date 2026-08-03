#!/bin/sh

set -eu

PROJECT_DIR="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

make_mock_commands() {
  mock_dir="$1"
  mkdir -p "$mock_dir"

  cat > "$mock_dir/id" <<'EOF'
#!/bin/sh
printf '0\n'
EOF
  cat > "$mock_dir/uname" <<'EOF'
#!/bin/sh
printf 'x86_64\n'
EOF
  cat > "$mock_dir/curl" <<'EOF'
#!/bin/sh
output=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-o" ]; then
    shift
    output="$1"
  fi
  shift
done
[ -n "$output" ] || exit 1
printf '#!/bin/sh\nexit 0\n' > "$output"
EOF
  cat > "$mock_dir/tcpkill" <<'EOF'
#!/bin/sh
exit 0
EOF
  cat > "$mock_dir/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod 755 "$mock_dir"/*
}

run_openrc_test() {
  case_root="$TEST_ROOT/openrc"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"

  mkdir -p "$case_root/etc/gost" "$case_root/proc/4242" "$case_root/run"
  printf '#!/bin/sh\nexit 0\n' > "$case_root/etc/gost/gost"
  chmod 755 "$case_root/etc/gost/gost"
  ln -s "$case_root/etc/gost/gost" "$case_root/proc/4242/exe"
  printf '4242\n' > "$case_root/run/gost.pid"

  cat > "$mock_dir/kill-agent" <<EOF
#!/bin/sh
printf 'kill-agent %s %s\n' "\${1:-}" "\${2:-}" >> "$event_log"
case "\${1:-}" in
  -9) pid="\${2:-}" ;;
  *) pid="\${1:-}" ;;
esac
rm -f "$case_root/proc/\$pid/exe"
EOF

  cat > "$mock_dir/rc-service" <<EOF
#!/bin/sh
printf 'rc-service %s %s\n' "\$1" "\$2" >> "$event_log"
exit 0
EOF
  cat > "$mock_dir/rc-update" <<EOF
#!/bin/sh
printf 'rc-update %s %s %s\n' "\$1" "\$2" "\$3" >> "$event_log"
exit 0
EOF
  chmod 755 "$mock_dir/rc-service" "$mock_dir/rc-update" "$mock_dir/kill-agent"

  PATH="$mock_dir:$PATH" \
    GOST_KEEP_SCRIPT=1 \
    GOST_SERVICE_MANAGER=openrc \
    GOST_DOWNLOAD_URL=https://example.invalid/gost \
    GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_OPENRC_DIR="$case_root/etc/init.d" \
    GOST_PROC_ROOT="$case_root/proc" \
    GOST_PID_FILE="$case_root/run/gost.pid" \
    GOST_KILL_COMMAND="$mock_dir/kill-agent" \
    sh "$PROJECT_DIR/install.sh" -a 127.0.0.1:6365 -s test-secret -r connector >/dev/null

  test -x "$case_root/etc/init.d/flux-connector"
  grep -Fq '#!/sbin/openrc-run' "$case_root/etc/init.d/flux-connector"
  grep -Fq "command=\"$case_root/etc/gost/gost\"" "$case_root/etc/init.d/flux-connector"
  grep -Fq "command_args=\"-C $case_root/etc/gost/gost.json\"" "$case_root/etc/init.d/flux-connector"
  grep -Fq 'use net' "$case_root/etc/init.d/flux-connector"
  ! grep -Fq 'need net' "$case_root/etc/init.d/flux-connector"
  grep -Fq '"addr": "127.0.0.1:6365"' "$case_root/etc/gost/config.json"
  grep -Fq '"role": "connector"' "$case_root/etc/gost/config.json"
  grep -Fq 'rc-update add flux-connector default' "$event_log"
  grep -Fq 'rc-service flux-connector start' "$event_log"
  grep -Fq 'rc-service flux-connector status' "$event_log"
  grep -Fq 'kill-agent 4242 ' "$event_log"
  test ! -e "$case_root/run/gost.pid"
}

run_systemd_test() {
  case_root="$TEST_ROOT/systemd"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"

  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s %s\n' "\${1:-}" "\${2:-}" >> "$event_log"
if [ "\${1:-}" = "start" ] && [ -f "$case_root/etc/gost/.agent-update-status.json" ]; then
  task_id=\$(sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p' "$case_root/etc/gost/.agent-update-status.json")
  [ -n "\$task_id" ] && printf 'test-version\n' > "$case_root/etc/gost/.agent-update-connected-\$task_id"
fi
exit 0
EOF
  chmod 755 "$mock_dir/systemctl"

  PATH="$mock_dir:$PATH" \
    GOST_KEEP_SCRIPT=1 \
    GOST_SERVICE_MANAGER=systemd \
    GOST_DOWNLOAD_URL=https://example.invalid/gost \
    GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -a 127.0.0.1:6365 -s test-secret >/dev/null

  test -f "$case_root/etc/systemd/system/gost.service"
  grep -Fq "ExecStart=$case_root/etc/gost/gost -C $case_root/etc/gost/gost.json" "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'After=network.target' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'StartLimitIntervalSec=0' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'Restart=always' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'RestartSec=1' "$case_root/etc/systemd/system/gost.service"
  ! grep -Fq 'network-online.target' "$case_root/etc/systemd/system/gost.service"
  grep -Fq '"role": "node"' "$case_root/etc/gost/config.json"
  grep -Fq 'systemctl enable gost' "$event_log"
  grep -Fq 'systemctl start gost' "$event_log"
  grep -Fq 'systemctl is-active' "$event_log"
}

run_systemd_update_test() {
  case_root="$TEST_ROOT/systemd-update"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"

  mkdir -p "$case_root/etc/gost" "$case_root/etc/systemd/system"
  printf '#!/bin/sh\nexit 0\n' > "$case_root/etc/gost/gost"
  chmod 755 "$case_root/etc/gost/gost"
  printf '{}\n' > "$case_root/etc/gost/config.json"
  cat > "$case_root/etc/systemd/system/gost.service" <<EOF
[Unit]
Description=Gost Proxy Service
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$case_root/etc/gost/gost -C $case_root/etc/gost/gost.json
Restart=on-failure
RestartSec=3
EOF

  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s %s\n' "\${1:-}" "\${2:-}" >> "$event_log"
if [ "\${1:-}" = "start" ] && [ -f "$case_root/etc/gost/.agent-update-status.json" ]; then
  task_id=\$(sed -n 's/.*"taskId":"\([^"]*\)".*/\1/p' "$case_root/etc/gost/.agent-update-status.json")
  [ -n "\$task_id" ] && printf 'test-version\n' > "$case_root/etc/gost/.agent-update-connected-\$task_id"
fi
exit 0
EOF
  chmod 755 "$mock_dir/systemctl"

  PATH="$mock_dir:$PATH" \
    GOST_KEEP_SCRIPT=1 \
    GOST_SERVICE_MANAGER=systemd \
    GOST_DOWNLOAD_URL=https://example.invalid/gost \
    GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -U >/dev/null

  grep -Fq 'After=network.target' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'StartLimitIntervalSec=0' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'Restart=always' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'RestartSec=1' "$case_root/etc/systemd/system/gost.service"
  ! grep -Fq 'network-online.target' "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'systemctl daemon-reload ' "$event_log"
  grep -Fq 'systemctl enable gost' "$event_log"
  test ! -e "$case_root/etc/gost/gost.previous"
  test ! -e "$case_root/etc/gost/.agent-update-status.json"
}

run_systemd_update_rollback_test() {
  case_root="$TEST_ROOT/systemd-update-rollback"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"

  mkdir -p "$case_root/etc/gost" "$case_root/etc/systemd/system"
  printf '#!/bin/sh\nprintf old-agent\n' > "$case_root/etc/gost/gost"
  chmod 755 "$case_root/etc/gost/gost"
  printf '{}\n' > "$case_root/etc/gost/config.json"
  printf '[Service]\n' > "$case_root/etc/systemd/system/gost.service"
  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s %s\n' "\${1:-}" "\${2:-}" >> "$event_log"
exit 0
EOF
  chmod 755 "$mock_dir/systemctl"

  if PATH="$mock_dir:$PATH" \
    GOST_KEEP_SCRIPT=1 \
    GOST_SERVICE_MANAGER=systemd \
    GOST_DOWNLOAD_URL=https://example.invalid/gost \
    GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -U >/dev/null 2>&1; then
    printf 'expected disconnected update to fail\n' >&2
    exit 1
  fi

  grep -Fq 'old-agent' "$case_root/etc/gost/gost"
  grep -Fq '"state":"rolled_back"' "$case_root/etc/gost/.agent-update-status.json"
  grep -Fq 'systemctl start gost' "$event_log"
}

run_identity_replacement_guard_test() {
  case_root="$TEST_ROOT/identity-guard"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"

  mkdir -p "$case_root/etc/gost" "$case_root/etc/systemd/system"
  printf '#!/bin/sh\nexit 0\n' > "$case_root/etc/gost/gost"
  chmod 755 "$case_root/etc/gost/gost"
  cat > "$case_root/etc/gost/config.json" <<EOF
{
  "addr": "old-panel:6366",
  "secret": "old-secret",
  "role": "node"
}
EOF
  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s %s\n' "\${1:-}" "\${2:-}" >> "$event_log"
exit 0
EOF
  chmod 755 "$mock_dir/systemctl"

  if PATH="$mock_dir:$PATH" GOST_KEEP_SCRIPT=1 GOST_SERVICE_MANAGER=systemd \
    GOST_DOWNLOAD_URL=https://example.invalid/gost GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -a new-panel:6366 -s new-secret >/dev/null 2>&1; then
    printf 'expected identity replacement guard to reject install\n' >&2
    exit 1
  fi
  grep -Fq '"secret": "old-secret"' "$case_root/etc/gost/config.json"

  PATH="$mock_dir:$PATH" GOST_KEEP_SCRIPT=1 GOST_SERVICE_MANAGER=systemd \
    GOST_DOWNLOAD_URL=https://example.invalid/gost GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -a new-panel:6366 -s new-secret -R >/dev/null
  grep -Fq '"secret": "new-secret"' "$case_root/etc/gost/config.json"
}

run_connector_uninstall_test() {
  case_root="$TEST_ROOT/connector-uninstall"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"

  mkdir -p "$case_root/etc/flux-connector" "$case_root/etc/systemd/system"
  printf '{}\n' > "$case_root/etc/flux-connector/config.json"
  printf '[Service]\n' > "$case_root/etc/systemd/system/flux-connector.service"

  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s %s\n' "\${1:-}" "\${2:-}" >> "$event_log"
exit 0
EOF
  chmod 755 "$mock_dir/systemctl"

  PATH="$mock_dir:$PATH" \
    GOST_KEEP_SCRIPT=1 \
    GOST_SERVICE_MANAGER=systemd \
    GOST_INSTALL_DIR="$case_root/etc/flux-connector" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    GOST_PROC_ROOT="$case_root/proc" \
    sh "$PROJECT_DIR/install.sh" -r connector -u >/dev/null

  test ! -e "$case_root/etc/flux-connector"
  test ! -e "$case_root/etc/systemd/system/flux-connector.service"
  grep -Fq 'systemctl stop flux-connector' "$event_log"
  grep -Fq 'systemctl disable flux-connector' "$event_log"
  grep -Fq 'systemctl daemon-reload ' "$event_log"
}

run_low_disk_guard_test() {
  case_root="$TEST_ROOT/low-disk"
  mock_dir="$case_root/bin"
  output="$case_root/output.log"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"
  mkdir -p "$case_root/etc/gost" "$case_root/etc/systemd/system"
  printf '[Service]\n' > "$case_root/etc/systemd/system/gost.service"

  cat > "$mock_dir/df" <<'EOF'
#!/bin/sh
case "$1" in
  -Pi) printf 'Filesystem Inodes IUsed IFree IUse%% Mounted on\nmock 1000 1 999 1%% /\n' ;;
  *) printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\nmock 100000 99000 1024 99%% /\n' ;;
esac
EOF
  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s\n' "\$*" >> "$event_log"
exit 0
EOF
  chmod 755 "$mock_dir/df" "$mock_dir/systemctl"

  if PATH="$mock_dir:$PATH" GOST_KEEP_SCRIPT=1 GOST_SERVICE_MANAGER=systemd \
    GOST_DOWNLOAD_URL=https://example.invalid/gost GOST_MIN_FREE_KB=2048 \
    GOST_INSTALL_DIR="$case_root/etc/gost" GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -a 127.0.0.1:6365 -s test-secret >"$output" 2>&1; then
    printf 'expected low disk preflight to fail\n' >&2
    exit 1
  fi

  grep -Fq 'Agent 安装分区空间不足' "$output"
  test ! -e "$case_root/etc/gost/gost.new"
  test ! -s "$event_log"
}

run_version_mismatch_guard_test() {
  case_root="$TEST_ROOT/version-mismatch"
  mock_dir="$case_root/bin"
  output="$case_root/output.log"
  event_log="$case_root/events.log"
  make_mock_commands "$mock_dir"
  mkdir -p "$case_root/etc/gost" "$case_root/etc/systemd/system"
  printf '[Service]\n' > "$case_root/etc/systemd/system/gost.service"

  cat > "$mock_dir/curl" <<'EOF'
#!/bin/sh
output=""
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-o" ]; then shift; output="$1"; fi
  shift
done
[ -n "$output" ] || exit 1
cat > "$output" <<'AGENT'
#!/bin/sh
[ "${1:-}" = "--agent-version" ] && printf 'old-version\n'
AGENT
EOF
  cat > "$mock_dir/sha256sum" <<'EOF'
#!/bin/sh
printf 'test-checksum  %s\n' "$1"
EOF
  cat > "$mock_dir/systemctl" <<EOF
#!/bin/sh
printf 'systemctl %s\n' "\$*" >> "$event_log"
exit 0
EOF
  chmod 755 "$mock_dir/curl" "$mock_dir/sha256sum" "$mock_dir/systemctl"

  if PATH="$mock_dir:$PATH" GOST_KEEP_SCRIPT=1 GOST_SERVICE_MANAGER=systemd \
    GOST_SHA256=test-checksum GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_SYSTEMD_DIR="$case_root/etc/systemd/system" \
    sh "$PROJECT_DIR/install.sh" -a 127.0.0.1:6365 -s test-secret >"$output" 2>&1; then
    printf 'expected Agent version mismatch to fail\n' >&2
    exit 1
  fi

  grep -Fq 'Agent 版本校验失败' "$output" || {
    cat "$output" >&2
    return 1
  }
  test ! -e "$case_root/etc/gost/gost.new"
  test ! -s "$event_log"
}

run_macos_bootstrap_retry_test() {
  case_root="$TEST_ROOT/macos-retry"
  mock_dir="$case_root/bin"
  event_log="$case_root/events.log"
  service_state="$case_root/service.state"
  bootstrap_count="$case_root/bootstrap.count"
  install_dir="$case_root/Library/Application Support/FluxConnector"
  plist_path="$case_root/Library/LaunchDaemons/com.fluxpanel.connector.plist"
  make_mock_commands "$mock_dir"

  mkdir -p "$install_dir" "$(dirname "$plist_path")" "$case_root/Library/Logs"
  printf '#!/bin/sh\nexit 0\n' > "$install_dir/gost"
  printf '{"addr":"old"}\n' > "$install_dir/config.json"
  printf '{}\n' > "$install_dir/gost.json"
  printf '<plist></plist>\n' > "$plist_path"
  chmod 755 "$install_dir/gost"
  : > "$service_state"
  printf '0\n' > "$bootstrap_count"

  cat > "$mock_dir/launchctl" <<EOF
#!/bin/sh
printf 'launchctl %s\n' "\$*" >> "$event_log"
case "\${1:-}" in
  print)
    [ -f "$service_state" ] || exit 1
    printf 'state = running\npid = 4242\n'
    ;;
  bootout)
    rm -f "$service_state"
    ;;
  bootstrap)
    count="\$(cat "$bootstrap_count")"
    count=\$((count + 1))
    printf '%s\n' "\$count" > "$bootstrap_count"
    if [ "\$count" -eq 1 ]; then
      printf 'Bootstrap failed: 5: Input/output error\n' >&2
      exit 5
    fi
    : > "$service_state"
    ;;
  enable|kickstart) exit 0 ;;
esac
EOF
  cat > "$mock_dir/plutil" <<'EOF'
#!/bin/sh
exit 0
EOF
  cat > "$mock_dir/chown" <<'EOF'
#!/bin/sh
exit 0
EOF
  chmod 755 "$mock_dir/launchctl" "$mock_dir/plutil" "$mock_dir/chown"

  PATH="$mock_dir:$PATH" \
    FLUX_CONNECTOR_INSTALL_DIR="$install_dir" \
    FLUX_CONNECTOR_PLIST_PATH="$plist_path" \
    FLUX_CONNECTOR_LOG_PATH="$case_root/Library/Logs/FluxConnector.log" \
    sh "$PROJECT_DIR/install-connector-macos.sh" -a 127.0.0.1:6366 -s test-secret >/dev/null 2>&1

  test "$(cat "$bootstrap_count")" -eq 2
  grep -Fq 'launchctl bootout system/com.fluxpanel.connector' "$event_log"
  grep -Fq 'launchctl bootstrap system' "$event_log"
  grep -Fq '"addr": "127.0.0.1:6366"' "$install_dir/config.json"
  test ! -e "$install_dir/gost.previous"
  test ! -e "$install_dir/config.previous.json"
  test ! -e "$install_dir/com.fluxpanel.connector.previous.plist"
}

sh -n "$PROJECT_DIR/install.sh"
sh -n "$PROJECT_DIR/install-connector-macos.sh"
grep -Fq 'com.fluxpanel.connector' "$PROJECT_DIR/install-connector-macos.sh"
grep -Fq 'gost-darwin-$arch' "$PROJECT_DIR/install-connector-macos.sh"
grep -Fq 'UNINSTALL_ONLY=1' "$PROJECT_DIR/install-connector-macos.sh"
grep -Fq 'New-Service -Name $ServiceName' "$PROJECT_DIR/install-connector.ps1"
grep -Fq 'gost-windows-$arch.exe' "$PROJECT_DIR/install-connector.ps1"
grep -Fq 'if ($Uninstall)' "$PROJECT_DIR/install-connector.ps1"
run_openrc_test
run_systemd_test
run_systemd_update_test
run_systemd_update_rollback_test
run_identity_replacement_guard_test
run_connector_uninstall_test
run_low_disk_guard_test
run_version_mismatch_guard_test
run_macos_bootstrap_retry_test
printf 'Agent installer tests passed\n'
