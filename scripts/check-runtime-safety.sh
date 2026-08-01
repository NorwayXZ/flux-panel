#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$PROJECT_DIR"

runtime_files="
.env.example
docker-compose.yml
docker-compose-source.yml
docker-compose-build.yml
springboot-backend/Dockerfile
springboot-backend/Dockerfile.runtime
"

for file in $runtime_files; do
  grep -q -- '-XX:+ExitOnOutOfMemoryError' "$file" || {
    printf 'Missing OOM recovery option: %s\n' "$file" >&2
    exit 1
  }
  if grep -Eq -- '-Xmx256m.*UseSerialGC' "$file"; then
    printf 'Unsafe legacy JVM settings detected: %s\n' "$file" >&2
    exit 1
  fi
done

for file in docker-compose.yml docker-compose-source.yml docker-compose-build.yml; do
  grep -Fq 'test: ["CMD", "/app/healthcheck.sh"]' "$file" || {
    printf 'Database-aware backend healthcheck is missing: %s\n' "$file" >&2
    exit 1
  }
  if grep -Fq '/flow/test' "$file"; then
    printf 'Legacy shallow healthcheck detected: %s\n' "$file" >&2
    exit 1
  fi
done

grep -Fq 'access_log off;' vite-frontend/nginx.conf || {
  printf 'Agent handshake access logging must remain disabled\n' >&2
  exit 1
}

printf 'Runtime safety checks passed\n'
