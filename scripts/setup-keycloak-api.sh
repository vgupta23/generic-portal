#!/usr/bin/env bash
# One command that configures everything the Retail API needs in Keycloak, in the right order. Idempotent.
# Run after ./scripts/start-keycloak.sh. Pass --remove to take the API test setup out again (realm and portal stay).
#
#   1. scripts/setup-keycloak.sh                                  realm, portal/retail/crm clients, mappers, MFA, testuser
#   2. retail-api/scripts/setup-keycloak-api-test.sh              client retail-api-test + users apiuser/toyonly/noretail
#   3. retail-api/scripts/setup-keycloak-duns-clients.sh          per-DUNS clients, resource clients, roles, <DUNS>:<ROLE> scopes, grants
#   4. keycloak-extensions/resource-role-mapper/deploy.sh         custom mapper SPI (built in Docker, jar copied in, Keycloak restarted)
#                                                                 skipped when the mapper is already installed (--redeploy forces it)
#   5. retail-api/scripts/setup-keycloak-duns-resource-scopes.sh  duns-<DUNS> scopes + resource= filter mapper
#
# DEV/TEST ONLY: enables the password grant and creates MFA-less users with fixed secrets.
set -euo pipefail
cd "$(dirname "$0")/.."

export KC_CONTAINER="${KC_CONTAINER:-keycloak}"
export KC_ADMIN="${KC_ADMIN:-admin}"
export KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
MAPPER_TYPE=resource-role-filter-mapper

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" ||
  { echo "ERROR: container '$KC_CONTAINER' is not running. Run ./scripts/start-keycloak.sh" >&2; exit 1; }

mapper_installed() {
  local info
  docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 \
    --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null 2>&1 || return 1
  info="$(docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh get serverinfo 2>/dev/null)" || return 1
  grep -q "$MAPPER_TYPE" <<<"$info"
}

if [ "${1:-}" = "--remove" ]; then
  retail-api/scripts/setup-keycloak-duns-resource-scopes.sh --remove
  retail-api/scripts/setup-keycloak-duns-clients.sh --remove
  retail-api/scripts/setup-keycloak-api-test.sh --remove
  echo "API test setup removed. (The mapper jar stays in the container; it is harmless.)"
  exit 0
fi

echo "##### 1/5 Realm, portal clients, test user"
./scripts/setup-keycloak.sh
echo; echo "##### 2/5 API test client and users"
retail-api/scripts/setup-keycloak-api-test.sh
echo; echo "##### 3/5 Per-DUNS clients, roles, scopes, grants"
retail-api/scripts/setup-keycloak-duns-clients.sh
echo; echo "##### 4/5 resource-role-filter mapper (custom Keycloak provider)"
if [ "${1:-}" != "--redeploy" ] && mapper_installed; then echo "   already installed (use --redeploy to rebuild)"
else keycloak-extensions/resource-role-mapper/deploy.sh; fi
echo; echo "##### 5/5 Per-DUNS resource= scopes"
retail-api/scripts/setup-keycloak-duns-resource-scopes.sh

echo
echo "Keycloak is ready for the Retail API. Next: (cd retail-api && docker compose up -d --build), then import postman/ and run it."
