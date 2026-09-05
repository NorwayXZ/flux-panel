#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NETWORK=flux-panel-runtime-test-net
DATABASE=flux-panel-runtime-test-db
BACKEND=flux-panel-runtime-test-backend
IMAGE=flux-panel-runtime-test-backend:local
BUILD_CONTEXT=

cleanup() {
  docker rm -f "${BACKEND}" "${DATABASE}" >/dev/null 2>&1 || true
  docker network rm "${NETWORK}" >/dev/null 2>&1 || true
  docker image rm "${IMAGE}" >/dev/null 2>&1 || true
  [[ -z "${BUILD_CONTEXT}" ]] || rm -rf "${BUILD_CONTEXT}"
}
trap cleanup EXIT
cleanup
BUILD_CONTEXT="$(mktemp -d)"

cp "${PROJECT_DIR}/springboot-backend/Dockerfile.runtime" "${BUILD_CONTEXT}/Dockerfile.runtime"
cp "${PROJECT_DIR}/springboot-backend/Dockerfile.runtime.dockerignore" \
  "${BUILD_CONTEXT}/Dockerfile.runtime.dockerignore"
cp "${PROJECT_DIR}/springboot-backend/healthcheck.sh" "${BUILD_CONTEXT}/healthcheck.sh"
mkdir -p "${BUILD_CONTEXT}/target"
cp "${PROJECT_DIR}"/springboot-backend/target/*.jar "${BUILD_CONTEXT}/target/app.jar"
test -s "${BUILD_CONTEXT}/Dockerfile.runtime"
test -s "${BUILD_CONTEXT}/Dockerfile.runtime.dockerignore"
test -s "${BUILD_CONTEXT}/healthcheck.sh"
test -s "${BUILD_CONTEXT}/target/app.jar"
docker build -f "${BUILD_CONTEXT}/Dockerfile.runtime" -t "${IMAGE}" "${BUILD_CONTEXT}" >/dev/null
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
# Seed the original failover schema so startup exercises a real legacy-table upgrade.
docker exec -i "${DATABASE}" mysql -uroot -ptestroot flux_test < \
  "${PROJECT_DIR}/migrations/20260727_cross_entry_failover.sql"

# Exercise both upgrade paths: an old installation may still have 255-character
# monitoring columns before the migration or startup schema initializer runs.
docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -e \
  "ALTER TABLE monitoring_current MODIFY COLUMN detail varchar(255) DEFAULT NULL;
   ALTER TABLE monitoring_history MODIFY COLUMN detail varchar(255) DEFAULT NULL;
   ALTER TABLE monitoring_alert MODIFY COLUMN detail varchar(255) DEFAULT NULL;"
docker exec -i "${DATABASE}" mysql -uroot -ptestroot flux_test < \
  "${PROJECT_DIR}/migrations/20260818_monitoring_detail_width.sql"
for table in monitoring_current monitoring_history monitoring_alert; do
  width=$(docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -Nse \
    "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='${table}' AND column_name='detail'")
  [[ "${width}" == "500" ]]
done
# Leave the database in the legacy shape so the backend startup initializer is
# tested independently from the migration above.
docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -e \
  "ALTER TABLE monitoring_current MODIFY COLUMN detail varchar(255) DEFAULT NULL;
   ALTER TABLE monitoring_history MODIFY COLUMN detail varchar(255) DEFAULT NULL;
   ALTER TABLE monitoring_alert MODIFY COLUMN detail varchar(255) DEFAULT NULL;"

# Seed an offline node and verify the real scheduled monitoring path creates an alert.
docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -e \
  "INSERT INTO node (id,owner_user_id,name,secret,server_ip,port_sta,port_end,created_time,status)
   VALUES (900001,1,'monitoring-runtime-offline-node','runtime-test-secret','203.0.113.1',10000,10010,UNIX_TIMESTAMP()*1000,0);"

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

for column in activity_in_flow activity_out_flow last_in_flow_at last_out_flow_at last_activity_at; do
  column_exists=$(docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -Nse \
    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='cross_entry_failover_member' AND column_name='${column}'")
  [[ "${column_exists}" -eq 1 ]]
done

for table in monitoring_current monitoring_history monitoring_alert; do
  width=$(docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -Nse \
    "SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='${table}' AND column_name='detail'")
  [[ "${width}" == "500" ]]
done

monitoring_alert_ready=0
for _ in {1..45}; do
  alert_status=$(docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -Nse \
    "SELECT status FROM monitoring_alert WHERE resource_type='node' AND resource_id=900001 ORDER BY id DESC LIMIT 1" || true)
  if [[ "${alert_status}" == "open" ]]; then
    monitoring_alert_ready=1
    break
  fi
  sleep 2
done
[[ "${monitoring_alert_ready}" -eq 1 ]]
alert_detail_length=$(docker exec "${DATABASE}" mysql -uroot -ptestroot flux_test -Nse \
  "SELECT CHAR_LENGTH(detail) FROM monitoring_alert WHERE resource_type='node' AND resource_id=900001 AND status='open' ORDER BY id DESC LIMIT 1")
[[ "${alert_detail_length}" -le 500 ]]

printf 'Backend runtime and monitoring integration test passed\n'
