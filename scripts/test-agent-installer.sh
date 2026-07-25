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
  chmod 755 "$mock_dir/rc-service" "$mock_dir/rc-update"

  PATH="$mock_dir:$PATH" \
    GOST_KEEP_SCRIPT=1 \
    GOST_SERVICE_MANAGER=openrc \
    GOST_DOWNLOAD_URL=https://example.invalid/gost \
    GOST_INSTALL_DIR="$case_root/etc/gost" \
    GOST_OPENRC_DIR="$case_root/etc/init.d" \
    sh "$PROJECT_DIR/install.sh" -a 127.0.0.1:6365 -s test-secret >/dev/null

  test -x "$case_root/etc/init.d/gost"
  grep -Fq '#!/sbin/openrc-run' "$case_root/etc/init.d/gost"
  grep -Fq "command=\"$case_root/etc/gost/gost\"" "$case_root/etc/init.d/gost"
  grep -Fq '"addr": "127.0.0.1:6365"' "$case_root/etc/gost/config.json"
  grep -Fq 'rc-update add gost default' "$event_log"
  grep -Fq 'rc-service gost start' "$event_log"
  grep -Fq 'rc-service gost status' "$event_log"
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
  grep -Fq "ExecStart=$case_root/etc/gost/gost" "$case_root/etc/systemd/system/gost.service"
  grep -Fq 'systemctl enable gost' "$event_log"
  grep -Fq 'systemctl start gost' "$event_log"
  grep -Fq 'systemctl is-active' "$event_log"
}

sh -n "$PROJECT_DIR/install.sh"
run_openrc_test
run_systemd_test
printf 'Agent installer tests passed\n'
