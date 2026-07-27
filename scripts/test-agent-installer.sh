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
run_connector_uninstall_test
printf 'Agent installer tests passed\n'
