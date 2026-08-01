#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
SLEEP_PID=""
cleanup() {
  if [[ -n "${SLEEP_PID}" ]]; then
    kill -KILL "${SLEEP_PID}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TEST_ROOT}"
}
trap cleanup EXIT

mkdir -p "${TEST_ROOT}/bin"
cat > "${TEST_ROOT}/bin/wget" <<'EOF'
#!/bin/sh
if [ "${HEALTHCHECK_TEST_RESULT:-failure}" = "success" ]; then
  exit 0
fi
exit 1
EOF
chmod 755 "${TEST_ROOT}/bin/wget"

run_check() {
  env \
    PATH="${TEST_ROOT}/bin:${PATH}" \
    HEALTHCHECK_FAILURES_FILE="${TEST_ROOT}/failures" \
    HEALTHCHECK_FAILURE_THRESHOLD=5 \
    HEALTHCHECK_TARGET_PID="${SLEEP_PID}" \
    HEALTHCHECK_KILL_GRACE_SECONDS=0 \
    HEALTHCHECK_STARTUP_GRACE_SECONDS=120 \
    HEALTHCHECK_PROCESS_AGE_SECONDS=121 \
    HEALTHCHECK_TEST_RESULT="${1:-failure}" \
    sh "${PROJECT_DIR}/springboot-backend/healthcheck.sh"
}

sleep 60 &
SLEEP_PID=$!

if env \
    PATH="${TEST_ROOT}/bin:${PATH}" \
    HEALTHCHECK_FAILURES_FILE="${TEST_ROOT}/failures" \
    HEALTHCHECK_FAILURE_THRESHOLD=1 \
    HEALTHCHECK_TARGET_PID="${SLEEP_PID}" \
    HEALTHCHECK_STARTUP_GRACE_SECONDS=120 \
    HEALTHCHECK_PROCESS_AGE_SECONDS=30 \
    HEALTHCHECK_TEST_RESULT=failure \
    sh "${PROJECT_DIR}/springboot-backend/healthcheck.sh"; then
  printf 'startup readiness probe unexpectedly passed\n' >&2
  exit 1
fi
kill -0 "${SLEEP_PID}"
[[ ! -e "${TEST_ROOT}/failures" ]]

for expected in 1 2 3 4; do
  if run_check failure; then
    printf 'failed readiness probe unexpectedly passed\n' >&2
    exit 1
  fi
  [[ "$(awk '{print $2}' "${TEST_ROOT}/failures")" == "${expected}" ]]
  kill -0 "${SLEEP_PID}"
done

if run_check failure; then
  printf 'failure threshold probe unexpectedly passed\n' >&2
  exit 1
fi
for _ in {1..20}; do
  kill -0 "${SLEEP_PID}" >/dev/null 2>&1 || break
  sleep 0.05
done
if kill -0 "${SLEEP_PID}" >/dev/null 2>&1; then
  printf 'sustained readiness failures did not terminate the target process\n' >&2
  exit 1
fi
wait "${SLEEP_PID}" 2>/dev/null || true
SLEEP_PID=""

printf 'stale-process 4\n' > "${TEST_ROOT}/failures"
if run_check failure; then
  printf 'probe with stale process identity unexpectedly passed\n' >&2
  exit 1
fi
[[ "$(awk '{print $2}' "${TEST_ROOT}/failures")" == "1" ]]

run_check success
[[ ! -e "${TEST_ROOT}/failures" ]]

printf 'Runtime healthcheck tests passed\n'
