#!/usr/bin/env bash
# DEV/TEST ONLY. Adds what Postman (and curl) need to obtain real tokens without a browser or TOTP:
#   - client "retail-api-test": public, password (direct) grant enabled, same claim mappers as the portal
#   - three test users with different DUNS roles, no MFA, password "password"
# It deliberately weakens the demo realm (password grant, MFA-less users). Remove with --remove.
# Prerequisite: ../scripts/setup-keycloak.sh has been run (realm, user-profile attributes, audience clients).
set -euo pipefail

KC_CONTAINER="${KC_CONTAINER:-keycloak}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM=demo
CLIENT=retail-api-test
PASSWORD=password

K() { docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
uuid() { K get "$1" -r $REALM -q "$2" --fields id --format csv --noquotes 2>/dev/null | head -1; }

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" || { echo "ERROR: Keycloak container '$KC_CONTAINER' is not running." >&2; exit 1; }
K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null
K get "realms/$REALM" >/dev/null 2>&1 || { echo "ERROR: realm '$REALM' missing. Run ./scripts/setup-keycloak.sh first." >&2; exit 1; }

if [ "${1:-}" = "--remove" ]; then
  for u in apiuser toyonly noretail; do id="$(uuid users username=$u)"; [ -n "$id" ] && K delete "users/$id" -r $REALM; done
  id="$(uuid clients clientId=$CLIENT)"; [ -n "$id" ] && K delete "clients/$id" -r $REALM
  echo "Removed test client and users."; exit 0
fi

echo "== Client '$CLIENT'"
CID="$(uuid clients clientId=$CLIENT)"
if [ -z "$CID" ]; then
  K create clients -r $REALM -s clientId=$CLIENT -s publicClient=true -s standardFlowEnabled=false \
    -s directAccessGrantsEnabled=true -s name="Retail API test client (dev only)" >/dev/null
  CID="$(uuid clients clientId=$CLIENT)"; echo "   created"
else echo "   exists"; fi

mapper() { # <name> <type> [-s config...]
  local name="$1" type="$2"; shift 2
  if K get "clients/$CID/protocol-mappers/models" -r $REALM --fields name --format csv --noquotes | grep -qx "$name"; then
    echo "   mapper $name exists"
  else
    K create "clients/$CID/protocol-mappers/models" -r $REALM -s name="$name" -s protocol=openid-connect -s protocolMapper="$type" "$@" >/dev/null
    echo "   mapper $name created"
  fi
}
attr() { mapper "$1" oidc-usermodel-attribute-mapper -s "config.\"user.attribute\"=$1" -s "config.\"claim.name\"=$1" \
  -s 'config."jsonType.label"=String' -s "config.\"multivalued\"=$2" -s 'config."id.token.claim"=true' \
  -s 'config."access.token.claim"=true' -s 'config."userinfo.token.claim"=true'; }
attr department false; attr employee_id false; attr duns_roles true
mapper portal-roles oidc-usermodel-realm-role-mapper -s 'config."claim.name"=roles' -s 'config."multivalued"=true' \
  -s 'config."jsonType.label"=String' -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true'
mapper retail-ui-audience oidc-audience-mapper -s 'config."included.client.audience"=retail-ui' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false'

mkuser() { # <username> <duns_roles values...>
  local name="$1"; shift
  local id; id="$(uuid users username=$name)"
  if [ -z "$id" ]; then
    K create users -r $REALM -s username="$name" -s enabled=true >/dev/null
    id="$(uuid users username=$name)"
    K set-password -r $REALM --username "$name" --new-password $PASSWORD
  fi
  local json; json="$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1:]))' "$@")"
  K update "users/$id" -r $REALM -f - <<JSON
{"email":"$name@example.com","firstName":"$name","lastName":"Test","emailVerified":true,"requiredActions":[],
 "attributes":{"department":["QA"],"employee_id":["T-$name"],"duns_roles":$json}}
JSON
  echo "   user $name  ->  $*"
}
echo "== Test users (password: $PASSWORD, no MFA)"
mkuser apiuser  "111111111:USA,RETAILUI,CRM" "222222222:USA,RETAILUI,CRM,DAM_OP,CRR"
mkuser toyonly  "111111111:USA,RETAILUI"     "222222222:USA,CRM"
mkuser noretail "111111111:USA,CRM"          "222222222:USA,DAM_OP"

echo "== Verifying a password-grant token"
curl -fs -d client_id=$CLIENT -d grant_type=password -d username=apiuser -d password=$PASSWORD -d scope=openid \
  http://localhost:8080/realms/$REALM/protocol/openid-connect/token | python3 -c '
import sys, json, base64
d = json.load(sys.stdin)
def claims(t):
    p = t.split(".")[1]; p += "=" * (-len(p) % 4); return json.loads(base64.urlsafe_b64decode(p))
a, i = claims(d["access_token"]), claims(d["id_token"])
print("   access aud:", a["aud"], "| id aud:", i["aud"], "| duns_roles:", a.get("duns_roles"))'
echo "Done. Remove later with: ./scripts/setup-keycloak-api-test.sh --remove"
