#!/usr/bin/env bash
# Builds the resource-role-filter mapper (Maven in Docker, nothing local needed) and installs it into the running
# Keycloak container, then restarts Keycloak so it is picked up (start-dev rebuilds on boot).
# The jar lives in the container's filesystem: re-run this after recreating the container.
set -euo pipefail
cd "$(dirname "$0")"
KC_CONTAINER="${KC_CONTAINER:-keycloak}"

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" || { echo "ERROR: Keycloak container '$KC_CONTAINER' is not running." >&2; exit 1; }

echo "== Building"
docker run --rm -v "$(pwd)":/workspace -w /workspace -v retail-api-m2:/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -B -q package

echo "== Installing into $KC_CONTAINER:/opt/keycloak/providers"
docker cp target/resource-role-mapper.jar "$KC_CONTAINER:/opt/keycloak/providers/resource-role-mapper.jar"
docker restart "$KC_CONTAINER" >/dev/null

printf "Waiting for Keycloak"
for _ in $(seq 1 90); do
  if curl -fs -o /dev/null http://localhost:8080/realms/master; then echo " ready."; exit 0; fi
  printf "."; sleep 2
done
echo; echo "ERROR: Keycloak did not come back. See: docker logs $KC_CONTAINER" >&2; exit 1
