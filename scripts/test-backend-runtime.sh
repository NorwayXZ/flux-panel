#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NETWORK=flux-panel-runtime-test-net
DATABASE=flux-panel-runtime-test-db
BACKEND=flux-panel-runtime-test-backend
IMAGE=flux-panel-runtime-test-backend:local
BUILD_CONTEXT="$(mktemp -d)"

cleanup() {
  docker rm -f "${BACKEND}" "${DATABASE}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  docker image rm "${IMAGE}" >/dev/null 2>&1 || true
  rm -rf "${BUILD_CONTEXT}"
}
trap cleanup EXIT
cleanup

cp "${PROJECT_DIR}/springboot-backend/Dockerfile.runtime" "${BUILD_CONTEXT}/Dockerfile"
cp "${PROJECT_DIR}/springboot-backend/healthcheck.sh" "${BUILD_CONTEXT}/healthcheck.sh"
mkdir -p "${BUILD_CONTEXT}/target"
cp "${PROJECT_DIR}"/springboot-backend/target/*.jar "${BUILD_CONTEXT}/target/app.jar"
test -s "${BUILD_CONTEXT}/Dockerfile"
test -s "${BUILD_CONTEXT}/healthcheck.sh"
test -s "${BUILD_CONTEXT}/target/app.jar"
docker build -t "${IMAGE}" "${BUILD_CONTEXT}" >/dev/null
docker network create "${NETWORK}" >/dev/null
docker run -d \
  --name "${DATABASE}" \
  --network "${NETWORK}" \
  --tmpfs /var/lib/mysql:rw,size=512m \
  -e MYSQL_ROOT_PASSWORD=testroot \
  mysql:8.0 \
  --default-authentication-plugin=mysql_native_password \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci >/dev/null

database_ready=0
for _ in {1..90}; do
  if docker exec "${DATABASE}" mysql -h localhost -uroot -ptestroot -Nse 'SELECT 1' >/dev/null 2>&1; then
    database_ready=1
    break
  fi
  sleep 1
done
[[ "${database_ready}" -eq 1 ]]

docker exec "${DATABASE}" mysql -uroot -ptestroot -e \
  "CREATE DATABASE flux_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'flux_test'@'%' IDENTIFIED WITH mysql_native_password BY 'testpass';
   GRANT ALL PRIVILEGES ON flux_test.* TO 'flux_test'@'%';"
docker exec -i "${DATABASE}" mysql -uroot -ptestroot flux_test < "${PROJECT_DIR}/gost.sql"

docker run -d \
  --name "${BACKEND}" \
  --network "${NETWORK}" \
  --restart unless-stopped \
  -e DB_HOST="${DATABASE}" \
  -e DB_NAME=flux_test \
  -e DB_USER=flux_test \
  -e DB_PASSWORD=testpass \
  -e JWT_SECRET=test-only-secret-with-sufficient-length-123456 \
  -e LOG_DIR=/tmp/logs \
  -e 'JAVA_OPTS=-Xms128m -Xmx384m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai' \
  --health-cmd=/app/healthcheck.sh \
  --health-interval=5s \
  --health-timeout=10s \
  --health-retries=5 \
  --health-start-period=30s \
  "${IMAGE}" >/dev/null

healthy=0
for _ in {1..120}; do
  state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "${BACKEND}")
  if [[ "${state}" == "running healthy" ]]; then
    healthy=1
    break
  fi
  if [[ "${state}" != running* ]]; then
    docker logs "${BACKEND}"
    exit 1
  fi
  sleep 2
done
if [[ "${healthy}" -ne 1 ]]; then
  docker logs "${BACKEND}"
  exit 1
fi

[[ "$(docker inspect -f '{{.RestartCount}}' "${BACKEND}")" -eq 0 ]]
docker exec "${BACKEND}" wget -qO- http://localhost:6365/health/ready | grep -Fq '"status":"ready"'

printf 'Backend runtime smoke test passed\n'
