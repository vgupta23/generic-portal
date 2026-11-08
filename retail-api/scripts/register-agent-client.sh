#!/usr/bin/env bash
# Registers an OIDC client for an external agent (script, service, tool) that must call the Retail API on behalf of a user.
# The client gets the same claim mappers as the portal (duns_roles, roles, department, employee_id) and the retail-ui
# audience on its ACCESS token, so tokens issued to it satisfy the API.
#
# Usage: ./scripts/register-agent-client.sh <client-id> [--confidential] [--password-grant] [--device]
#   --confidential    client has a secret (prints it); default is a public client (no secret)
#   --password-grant  enable the password (direct) grant            DEV/TEST ONLY, needs MFA-less users
#   --device          enable the OAuth2 device authorization grant   headless agents; the user approves in a browser (MFA works)
# Prerequisite: ../scripts/setup-keycloak.sh has been run.
set -euo pipefail

CLIENT="${1:-}"; shift || true
[ -n "$CLIENT" ] && [[ "$CLIENT" != --* ]] || { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }
CONF=false; PWD_GRANT=false; DEVICE=false
for a in "$@"; do case "$a" in
  --confidential) CONF=true;; --password-grant) PWD_GRANT=true;; --device) DEVICE=true;;
  *) echo "unknown option $a" >&2; exit 1;; esac; done

KC_CONTAINER="${KC_CONTAINER:-keycloak}"; KC_ADMIN="${KC_ADMIN:-admin}"; KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM=demo
K() { docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
uuid() { K get "$1" -r $REALM -q "$2" --fields id --format csv --noquotes 2>/dev/null | head -1; }

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" || { echo "ERROR: Keycloak container '$KC_CONTAINER' is not running." >&2; exit 1; }
K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null
K get "realms/$REALM" >/dev/null 2>&1 || { echo "ERROR: realm '$REALM' missing. Run ./scripts/setup-keycloak.sh first." >&2; exit 1; }

PUBLIC=true; [ "$CONF" = true ] && PUBLIC=false
CID="$(uuid clients clientId=$CLIENT)"
if [ -z "$CID" ]; then
  K create clients -r $REALM -s clientId="$CLIENT" -s publicClient=$PUBLIC -s standardFlowEnabled=false \
    -s directAccessGrantsEnabled=$PWD_GRANT -s serviceAccountsEnabled=false -s name="Agent: $CLIENT" >/dev/null
  CID="$(uuid clients clientId=$CLIENT)"; echo "created client '$CLIENT'"
else
  K update "clients/$CID" -r $REALM -s publicClient=$PUBLIC -s directAccessGrantsEnabled=$PWD_GRANT >/dev/null
  echo "client '$CLIENT' exists (grants updated)"
fi
K update "clients/$CID" -r $REALM -s "attributes.\"oauth2.device.authorization.grant.enabled\"=$DEVICE" >/dev/null

mapper() { # <name> <type> [-s config...]
  local name="$1" type="$2"; shift 2
  K get "clients/$CID/protocol-mappers/models" -r $REALM --fields name --format csv --noquotes | grep -qx "$name" ||
    K create "clients/$CID/protocol-mappers/models" -r $REALM -s name="$name" -s protocol=openid-connect -s protocolMapper="$type" "$@" >/dev/null
}
attr() { mapper "$1" oidc-usermodel-attribute-mapper -s "config.\"user.attribute\"=$1" -s "config.\"claim.name\"=$1" \
  -s 'config."jsonType.label"=String' -s "config.\"multivalued\"=$2" -s 'config."id.token.claim"=true' \
  -s 'config."access.token.claim"=true' -s 'config."userinfo.token.claim"=true'; }
attr department false; attr employee_id false; attr duns_roles true
mapper portal-roles oidc-usermodel-realm-role-mapper -s 'config."claim.name"=roles' -s 'config."multivalued"=true' \
  -s 'config."jsonType.label"=String' -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true'
mapper retail-ui-audience oidc-audience-mapper -s 'config."included.client.audience"=retail-ui' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false'

echo
echo "Client id      : $CLIENT"
if [ "$CONF" = true ]; then
  echo "Client secret  : $(K get "clients/$CID/client-secret" -r $REALM --fields value --format csv --noquotes | head -1)"
fi
echo "Token endpoint : http://localhost:8080/realms/$REALM/protocol/openid-connect/token"
[ "$DEVICE" = true ] && echo "Device endpoint: http://localhost:8080/realms/$REALM/protocol/openid-connect/auth/device"
echo
echo "REQUIRED next step - tell the API to accept this client's ID tokens (otherwise every call is 401 'audience'):"
echo "  1. add it to the comma-separated list in retail-api/.env (keep existing entries):"
echo "       RETAIL_SECURITY_ID_AUDIENCES=demo-ui,retail-api-test,$CLIENT"
echo "  2. cd retail-api && docker compose up -d      # recreates the container with the new list"
