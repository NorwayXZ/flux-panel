#!/bin/sh
set -eu

failures_file=${HEALTHCHECK_FAILURES_FILE:-/tmp/flux-healthcheck-failures}
ready_url=${HEALTHCHECK_READY_URL:-http://localhost:6365/health/ready}
failure_threshold=${HEALTHCHECK_FAILURE_THRESHOLD:-5}
target_pid=${HEALTHCHECK_TARGET_PID:-1}
kill_grace_seconds=${HEALTHCHECK_KILL_GRACE_SECONDS:-5}
startup_grace_seconds=${HEALTHCHECK_STARTUP_GRACE_SECONDS:-120}

case "$failure_threshold" in (*[!0-9]*|'') failure_threshold=5 ;; esac
case "$target_pid" in (*[!0-9]*|'') target_pid=1 ;; esac
case "$kill_grace_seconds" in (*[!0-9]*|'') kill_grace_seconds=5 ;; esac
case "$startup_grace_seconds" in (*[!0-9]*|'') startup_grace_seconds=120 ;; esac

if wget --quiet --tries=1 --timeout=8 --spider "$ready_url"; then
  rm -f "$failures_file"
  exit 0
fi

process_age_seconds=${HEALTHCHECK_PROCESS_AGE_SECONDS:-}
if [ -z "$process_age_seconds" ] && [ -r /proc/uptime ] && [ -r "/proc/$target_pid/stat" ]; then
  uptime_seconds=$(cut -d. -f1 /proc/uptime 2>/dev/null || printf 0)
  process_start_ticks=$(awk '{print $22}' "/proc/$target_pid/stat" 2>/dev/null || printf 0)
  clock_ticks=$(getconf CLK_TCK 2>/dev/null || printf 100)
  case "$uptime_seconds:$process_start_ticks:$clock_ticks" in
    *[!0-9:]*) process_age_seconds= ;;
    *)
      if [ "$clock_ticks" -gt 0 ]; then
        process_age_seconds=$((uptime_seconds - process_start_ticks / clock_ticks))
      fi
      ;;
  esac
fi
case "$process_age_seconds" in (*[!0-9]*|'') process_age_seconds=$startup_grace_seconds ;; esac
if [ "$process_age_seconds" -lt "$startup_grace_seconds" ]; then
  rm -f "$failures_file"
  exit 1
fi

process_start=unknown
if [ -r "/proc/$target_pid/stat" ]; then
  process_start=$(awk '{print $22}' "/proc/$target_pid/stat" 2>/dev/null || printf unknown)
fi

recorded_start=
failures=0
if [ -r "$failures_file" ]; then
  read -r recorded_start failures < "$failures_file" || true
fi
if [ "$recorded_start" != "$process_start" ]; then
  failures=0
fi
case "$failures" in (*[!0-9]*|'') failures=0 ;; esac
failures=$((failures + 1))
printf '%s %s\n' "$process_start" "$failures" > "$failures_file"

# Docker does not restart unhealthy containers; exit only after sustained failure.
if [ "$failures" -ge "$failure_threshold" ]; then
  kill -TERM "$target_pid" 2>/dev/null || true
  sleep "$kill_grace_seconds"
  kill -KILL "$target_pid" 2>/dev/null || true
fi
exit 1
