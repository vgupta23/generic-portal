#!/usr/bin/env bash
# Idempotently configures Keycloak for this solution: realm, clients, claim mappers, MFA and a test user.
# Safe to re-run. Requires a running Keycloak container (see start-keycloak.sh).
set -euo pipefail

KC_CONTAINER="${KC_CONTAINER:-keycloak}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
KC_PUBLIC_URL="${KC_PUBLIC_URL:-http://localhost:8080}"
REALM=demo
TEST_USER="${TEST_USER:-testuser}"
TEST_PASSWORD="${TEST_PASSWORD:-password}"
# Format "<DUNS>:<ROLE>,<ROLE>,..." - one value per DUNS. Edit to change who sees what.
DUNS_ROLES=("111111111:USA,RETAILUI,CRM" "222222222:USA,RETAILUI,CRM,DAM_OP,CRR")

K() { docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
step() { echo "== $*"; }

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" || { echo "ERROR: container '$KC_CONTAINER' is not running. Run ./scripts/start-keycloak.sh" >&2; exit 1; }

step "Logging in to Keycloak admin API"
K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null

step "Realm '$REALM'"
if K get "realms/$REALM" >/dev/null 2>&1; then echo "   exists"; else
  K create realms -s realm=$REALM -s enabled=true -s displayName="Demo Realm" >/dev/null; echo "   created"
fi

step "User profile attributes (department, employee_id, duns_roles)"
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
K get users/profile -r $REALM > "$TMP"
python3 - "$TMP" <<'PY'
import json, sys
path = sys.argv[1]
profile = json.load(open(path))
have = {a["name"] for a in profile["attributes"]}
wanted = [
    {"name": "department", "displayName": "department", "multivalued": False},
    {"name": "employee_id", "displayName": "employee_id", "multivalued": False},
    {"name": "duns_roles", "displayName": "DUNS roles", "multivalued": True, "validations": {"length": {"max": 512}}},
]
for a in wanted:
    if a["name"] not in have:
        a["permissions"] = {"view": ["admin", "user"], "edit": ["admin"]}
        profile["attributes"].append(a)
json.dump(profile, open(path, "w"))
PY
K update users/profile -r $REALM -f - < "$TMP"

step "MFA: Configure OTP required action (enabled + default for new users)"
K update authentication/required-actions/CONFIGURE_TOTP -r $REALM -s enabled=true -s defaultAction=true

client_uuid() { K get clients -r $REALM -q "clientId=$1" --fields id --format csv --noquotes 2>/dev/null | head -1; }

step "Client 'demo-ui' (portal: public, auth code + PKCE S256)"
CID="$(client_uuid demo-ui)"
if [ -z "$CID" ]; then
  K create clients -r $REALM -s clientId=demo-ui -s publicClient=true -s standardFlowEnabled=true -s directAccessGrantsEnabled=false >/dev/null
  CID="$(client_uuid demo-ui)"; echo "   created"
else echo "   exists (re-applying settings)"; fi
K update "clients/$CID" -r $REALM \
  -s publicClient=true -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
  -s 'redirectUris=["http://localhost:3000/*"]' \
  -s 'webOrigins=["http://localhost:3000","http://localhost:3001"]' \
  -s 'attributes."pkce.code.challenge.method"=S256' \
  -s 'attributes."post.logout.redirect.uris"=http://localhost:3000/*'

for RES in retail-ui crm-ui; do
  step "Client '$RES' (bearer-only resource server; becomes an audience of the portal's access token)"
  if [ -z "$(client_uuid $RES)" ]; then
    K create clients -r $REALM -s clientId=$RES -s bearerOnly=true -s publicClient=false >/dev/null; echo "   created"
  else echo "   exists"; fi
done

ensure_mapper() { # <client-uuid> <name> <mapper-type> [-s config...]
  local cid="$1" name="$2" type="$3"; shift 3
  if K get "clients/$cid/protocol-mappers/models" -r $REALM --fields name --format csv --noquotes | grep -qx "$name"; then
    echo "   mapper '$name' exists"
  else
    K create "clients/$cid/protocol-mappers/models" -r $REALM -s name="$name" -s protocol=openid-connect -s protocolMapper="$type" "$@" >/dev/null
    echo "   mapper '$name' created"
  fi
}
attr_mapper() { # <name> <multivalued>
  ensure_mapper "$CID" "$1" oidc-usermodel-attribute-mapper \
    -s "config.\"user.attribute\"=$1" -s "config.\"claim.name\"=$1" -s 'config."jsonType.label"=String' \
    -s "config.\"multivalued\"=$2" -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' -s 'config."userinfo.token.claim"=true'
}

step "Protocol mappers on 'demo-ui' (custom claims + audiences)"
attr_mapper department false
attr_mapper employee_id false
attr_mapper duns_roles true
ensure_mapper "$CID" portal-roles oidc-usermodel-realm-role-mapper \
  -s 'config."claim.name"=roles' -s 'config."multivalued"=true' -s 'config."jsonType.label"=String' \
  -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true'
for RES in retail-ui crm-ui; do
  ensure_mapper "$CID" "$RES-audience" oidc-audience-mapper \
    -s "config.\"included.client.audience\"=$RES" -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false'
done

step "Realm role 'portal-admin'"
K get "roles/portal-admin" -r $REALM >/dev/null 2>&1 && echo "   exists" || { K create roles -r $REALM -s name=portal-admin -s description="Portal administrator" >/dev/null; echo "   created"; }

step "Test user '$TEST_USER'"
UID_="$(K get users -r $REALM -q "username=$TEST_USER" --fields id --format csv --noquotes | head -1)"
if [ -z "$UID_" ]; then
  K create users -r $REALM -s username="$TEST_USER" -s enabled=true >/dev/null
  UID_="$(K get users -r $REALM -q "username=$TEST_USER" --fields id --format csv --noquotes | head -1)"
  K set-password -r $REALM --username "$TEST_USER" --new-password "$TEST_PASSWORD"
  NEW_USER=1; echo "   created (password set; OTP enrolment will be required at first login)"
else NEW_USER=0; echo "   exists (password and OTP left untouched)"; fi

DUNS_JSON="$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1:]))' "${DUNS_ROLES[@]}")"
K update "users/$UID_" -r $REALM -f - <<JSON
{"email":"$TEST_USER@example.com","firstName":"Test","lastName":"User","emailVerified":true,
 "attributes":{"department":["Engineering"],"employee_id":["E-1001"],"duns_roles":$DUNS_JSON}}
JSON
[ "$NEW_USER" = 1 ] && K update "users/$UID_" -r $REALM -s 'requiredActions=["CONFIGURE_TOTP"]'
K add-roles -r $REALM --uusername "$TEST_USER" --rolename portal-admin >/dev/null 2>&1 || true

step "Verifying"
ISS="$(curl -fs "$KC_PUBLIC_URL/realms/$REALM/.well-known/openid-configuration" | python3 -c 'import sys,json;print(json.load(sys.stdin)["issuer"])')"
echo "   issuer: $ISS"
echo
echo "Keycloak is configured. Test login: $TEST_USER / $TEST_PASSWORD (you will enrol an authenticator app on first login)."
