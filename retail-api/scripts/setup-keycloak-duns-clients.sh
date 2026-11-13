#!/usr/bin/env bash
# DEV/TEST ONLY. One confidential Keycloak client per DUNS, and one OAuth scope per (DUNS, role) "resource".
#
#   client  retail-duns-<DUNS>   the caller: own client secret; password (direct) grant
#   client  duns-<DUNS>          the resource: bearer-only, holds that DUNS's roles (RETAILUI, CRM, ...)
#                                (roles live on a separate client because Keycloak always includes a client's OWN
#                                roles in its tokens; roles of another client are only included when a scope maps them)
#   scope   <DUNS>:<ROLE>        e.g. 111111111:RETAILUI; optional scope of that DUNS's client only
#
# A token request names the DUNS (by client_id + client_secret) and the resource (by scope):
#   client_id=retail-duns-111111111  scope="openid 111111111:RETAILUI"
# Keycloak then puts ONLY that role of that DUNS into the token: claim duns=111111111 and
# resource_access.duns-111111111.roles=[RETAILUI]. No scope requested -> no roles at all.
# This works because each client has "full scope allowed" off and each scope maps just that client's single role.
#
# Prerequisite: ./scripts/setup-keycloak-api-test.sh (test users apiuser/toyonly/noretail). Remove with --remove.
# Secrets are fixed dev values (retail-duns-<DUNS>-secret) so the Postman collection works out of the box.
set -euo pipefail

KC_CONTAINER="${KC_CONTAINER:-keycloak}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM=demo
# <DUNS>:<roles that DUNS knows about>
DUNS_LIST=("111111111:USA RETAILUI CRM" "222222222:USA RETAILUI CRM DAM_OP CRR")
# <user>|<DUNS>:<roles granted under that DUNS>  (mirrors duns_roles in setup-keycloak-api-test.sh)
GRANTS=(
  "apiuser|111111111:USA RETAILUI CRM"      "apiuser|222222222:USA RETAILUI CRM DAM_OP CRR"
  "toyonly|111111111:USA RETAILUI"          "toyonly|222222222:USA CRM"
  "noretail|111111111:USA CRM"              "noretail|222222222:USA DAM_OP"
)

K() { docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
uuid() { K get "$1" -r $REALM -q "$2" --fields id --format csv --noquotes 2>/dev/null | head -1; }
# client-scopes can't be filtered server-side: match the exact name
scope_id() { K get client-scopes -r $REALM --fields id,name --format csv --noquotes 2>/dev/null | awk -F, -v n="$1" '$2==n{print $1; exit}'; }
client_of() { echo "retail-duns-$1"; }
res_of() { echo "duns-$1"; }

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" || { echo "ERROR: Keycloak container '$KC_CONTAINER' is not running." >&2; exit 1; }
K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null
K get "realms/$REALM" >/dev/null 2>&1 || { echo "ERROR: realm '$REALM' missing. Run ./scripts/setup-keycloak.sh first." >&2; exit 1; }

if [ "${1:-}" = "--remove" ]; then
  for d in "${DUNS_LIST[@]}"; do
    duns="${d%%:*}"
    for role in ${d#*:}; do id="$(scope_id "$duns:$role")"; [ -n "$id" ] && K delete "client-scopes/$id" -r $REALM; done
    for c in "$(client_of "$duns")" "$(res_of "$duns")"; do id="$(uuid clients "clientId=$c")"; [ -n "$id" ] && K delete "clients/$id" -r $REALM; done
  done
  echo "Removed per-DUNS clients and scopes."; exit 0
fi

for d in "${DUNS_LIST[@]}"; do
  duns="${d%%:*}"; roles="${d#*:}"; client="$(client_of "$duns")"
  echo "== DUNS $duns -> client '$client'"
  res="$(res_of "$duns")"
  RES="$(uuid clients clientId=$res)"
  if [ -z "$RES" ]; then
    K create clients -r $REALM -s clientId="$res" -s name="DUNS $duns roles (resource)" -s enabled=true -s bearerOnly=true >/dev/null
    RES="$(uuid clients clientId=$res)"; echo "   resource client '$res' created"
  fi
  CID="$(uuid clients clientId=$client)"
  if [ -z "$CID" ]; then
    K create clients -r $REALM -s clientId="$client" -s name="Retail API DUNS $duns (dev only)" -s enabled=true \
      -s publicClient=false -s standardFlowEnabled=false -s directAccessGrantsEnabled=true -s serviceAccountsEnabled=false \
      -s fullScopeAllowed=false -s secret="$client-secret" >/dev/null
    CID="$(uuid clients clientId=$client)"; echo "   client created"
  else
    K update "clients/$CID" -r $REALM -s fullScopeAllowed=false -s publicClient=false -s secret="$client-secret" >/dev/null
    echo "   client exists (secret + full-scope=off enforced)"
  fi

  mapper() { # <name> <type> [-s config...]
    local name="$1" type="$2"; shift 2
    K get "clients/$CID/protocol-mappers/models" -r $REALM --fields name --format csv --noquotes | grep -qx "$name" ||
      K create "clients/$CID/protocol-mappers/models" -r $REALM -s name="$name" -s protocol=openid-connect -s protocolMapper="$type" "$@" >/dev/null
  }
  mapper duns oidc-hardcoded-claim-mapper -s 'config."claim.name"=duns' -s "config.\"claim.value\"=$duns" \
    -s 'config."jsonType.label"=String' -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=true' \
    -s 'config."userinfo.token.claim"=true'
  for a in department employee_id; do
    mapper "$a" oidc-usermodel-attribute-mapper -s "config.\"user.attribute\"=$a" -s "config.\"claim.name\"=$a" \
      -s 'config."jsonType.label"=String' -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' \
      -s 'config."userinfo.token.claim"=true'
  done
  mapper retail-ui-audience oidc-audience-mapper -s 'config."included.client.audience"=retail-ui' \
    -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false'

  for role in $roles; do
    # client role (what a user is granted)
    K get "clients/$RES/roles/$role" -r $REALM >/dev/null 2>&1 || K create "clients/$RES/roles" -r $REALM -s name="$role" >/dev/null
    # scope "<DUNS>:<ROLE>" whose only role scope mapping is this one role of this one client
    SID="$(scope_id "$duns:$role")"
    if [ -z "$SID" ]; then
      K create client-scopes -r $REALM -s "name=$duns:$role" -s protocol=openid-connect \
        -s description="Access to $role under DUNS $duns" -s 'attributes."include.in.token.scope"=true' \
        -s 'attributes."display.on.consent.screen"=false' >/dev/null
      SID="$(scope_id "$duns:$role")"
      RID="$(K get "clients/$RES/roles/$role" -r $REALM --fields id --format csv --noquotes | head -1)"
      K create "client-scopes/$SID/scope-mappings/clients/$RES" -r $REALM -b "[{\"id\":\"$RID\",\"name\":\"$role\"}]" >/dev/null
    fi
    K update "clients/$CID/optional-client-scopes/$SID" -r $REALM -n -b '{}' >/dev/null
    echo "   scope $duns:$role"
  done
done

echo "== Grant client roles to the test users"
for g in "${GRANTS[@]}"; do
  user="${g%%|*}"; rest="${g#*|}"; duns="${rest%%:*}"
  for role in ${rest#*:}; do
    K add-roles -r $REALM --uusername "$user" --cclientid "$(res_of "$duns")" --rolename "$role"
  done
  echo "   $user @ $duns -> ${rest#*:}"
done

echo "== Verifying: apiuser, DUNS 111111111, scope '111111111:RETAILUI'"
curl -fs -d client_id=retail-duns-111111111 -d client_secret=retail-duns-111111111-secret -d grant_type=password \
  -d username=apiuser -d password=password --data-urlencode "scope=openid 111111111:RETAILUI" \
  http://localhost:8080/realms/$REALM/protocol/openid-connect/token | python3 -c '
import sys, json, base64
d = json.load(sys.stdin)
p = d["access_token"].split(".")[1]; p += "=" * (-len(p) % 4)
a = json.loads(base64.urlsafe_b64decode(p))
print("   azp:", a["azp"], "| duns:", a.get("duns"), "| resource_access:", a.get("resource_access"), "| scope:", a.get("scope"))'
echo "Done. Add the new clients as ID-token audiences: see RETAIL_SECURITY_ID_AUDIENCES in docker-compose.yml."
