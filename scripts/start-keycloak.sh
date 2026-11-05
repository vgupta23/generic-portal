#!/usr/bin/env bash
# Pulls and starts Keycloak in Docker (dev mode, embedded H2 database persisted in a Docker volume).
set -euo pipefail

KC_CONTAINER="${KC_CONTAINER:-keycloak}"
KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.8.1}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"

docker info >/dev/null 2>&1 || { echo "ERROR: Docker daemon is not running." >&2; exit 1; }

if docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER"; then
  echo "Container '$KC_CONTAINER' is already running."
elif docker ps -a --format '{{.Names}}' | grep -qx "$KC_CONTAINER"; then
  echo "Starting existing container '$KC_CONTAINER'..."
  docker start "$KC_CONTAINER" >/dev/null
else
  if lsof -iTCP:8080 -sTCP:LISTEN -P >/dev/null 2>&1; then
    echo "ERROR: port 8080 is in use by another process. Free it (the apps expect Keycloak on 8080)." >&2; exit 1
  fi
  echo "Pulling $KEYCLOAK_IMAGE ..."
  docker pull "$KEYCLOAK_IMAGE"
  docker run -d --name "$KC_CONTAINER" -p 8080:8080 \
    -v keycloak_data:/opt/keycloak/data \
    -e KC_BOOTSTRAP_ADMIN_USERNAME="$KC_ADMIN" -e KC_BOOTSTRAP_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" \
    "$KEYCLOAK_IMAGE" start-dev >/dev/null
fi

printf "Waiting for Keycloak"
for _ in $(seq 1 60); do
  if curl -fs -o /dev/null http://localhost:8080/realms/master; then
    echo " ready."
    echo "Keycloak: http://localhost:8080   (admin console: http://localhost:8080/admin, $KC_ADMIN / $KC_ADMIN_PASSWORD)"
    exit 0
  fi
  printf "."; sleep 2
done
echo; echo "ERROR: Keycloak did not become ready. See: docker logs $KC_CONTAINER" >&2; exit 1
