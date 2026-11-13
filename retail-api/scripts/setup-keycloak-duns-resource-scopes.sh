#!/usr/bin/env bash
# DEV/TEST ONLY. One OAuth scope per DUNS holding ALL of that DUNS's roles; the role actually put into the token is
# chosen at request time by the token request's "resource" parameter.
#
#   scope  duns-<DUNS>   optional scope of client retail-duns-<DUNS>
#                        - scope mappings: every role of resource client duns-<DUNS>
#                        - protocol mapper "resource-role-filter" (custom SPI, keycloak-extensions/resource-role-mapper)
#                          which drops every role that is not named in resource=
#
#   client_id=retail-duns-111111111  scope="openid duns-111111111"  resource=RETAILUI
#     -> duns=111111111, resource_access.duns-111111111.roles=[RETAILUI]
#
# Prerequisites: setup-keycloak-api-test.sh, setup-keycloak-duns-clients.sh (clients, roles, grants) and
# keycloak-extensions/resource-role-mapper/deploy.sh (the mapper SPI). Remove with --remove.
set -euo pipefail

KC_CONTAINER="${KC_CONTAINER:-keycloak}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
REALM=demo
DUNS_LIST=(111111111 222222222)
MAPPER_TYPE=resource-role-filter-mapper

K() { docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
uuid() { K get "$1" -r $REALM -q "$2" --fields id --format csv --noquotes 2>/dev/null | head -1; }
scope_id() { K get client-scopes -r $REALM --fields id,name --format csv --noquotes 2>/dev/null | awk -F, -v n="$1" '$2==n{print $1; exit}'; }

docker ps --format '{{.Names}}' | grep -qx "$KC_CONTAINER" || { echo "ERROR: Keycloak container '$KC_CONTAINER' is not running." >&2; exit 1; }
K config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD" >/dev/null

if [ "${1:-}" = "--remove" ]; then
  for duns in "${DUNS_LIST[@]}"; do id="$(scope_id "duns-$duns")"; [ -n "$id" ] && K delete "client-scopes/$id" -r $REALM; done
  echo "Removed per-DUNS scopes."; exit 0
fi

INFO="$(K get serverinfo 2>/dev/null)"
grep -q "$MAPPER_TYPE" <<<"$INFO" ||
  { echo "ERROR: mapper '$MAPPER_TYPE' is not installed. Run keycloak-extensions/resource-role-mapper/deploy.sh" >&2; exit 1; }

for duns in "${DUNS_LIST[@]}"; do
  res="duns-$duns"; client="retail-duns-$duns"
  RES="$(uuid clients "clientId=$res")"; CID="$(uuid clients "clientId=$client")"
  [ -n "$RES" ] && [ -n "$CID" ] || { echo "ERROR: run setup-keycloak-duns-clients.sh first (missing $res / $client)." >&2; exit 1; }
  echo "== DUNS $duns -> scope '$res'"

  SID="$(scope_id "$res")"
  if [ -z "$SID" ]; then
    K create client-scopes -r $REALM -s "name=$res" -s protocol=openid-connect \
      -s description="All roles of DUNS $duns; token request 'resource' picks the one that is issued" \
      -s 'attributes."include.in.token.scope"=true' -s 'attributes."display.on.consent.screen"=false' >/dev/null
    SID="$(scope_id "$res")"; echo "   scope created"
  else echo "   scope exists"; fi

  # every role of the DUNS (idempotent: re-adding an existing scope mapping is a no-op)
  ROLES="$(K get "clients/$RES/roles" -r $REALM)"
  K create "client-scopes/$SID/scope-mappings/clients/$RES" -r $REALM -b "$ROLES" >/dev/null
  echo "   scope mappings: $(echo "$ROLES" | python3 -c 'import sys,json;print(*[r["name"] for r in json.load(sys.stdin)])')"

  if K get "client-scopes/$SID/protocol-mappers/models" -r $REALM --fields name --format csv --noquotes | grep -qx resource-role-filter; then
    echo "   mapper exists"
  else
    K create "client-scopes/$SID/protocol-mappers/models" -r $REALM -s name=resource-role-filter -s protocol=openid-connect \
      -s protocolMapper=$MAPPER_TYPE -s 'config."request.parameter"=resource' -s 'config."client.id.prefix"=duns-' \
      -s 'config."access.token.claim"=true' >/dev/null
    echo "   mapper created"
  fi
  K update "clients/$CID/optional-client-scopes/$SID" -r $REALM -n -b '{}' >/dev/null
done

echo "== Verifying: apiuser, DUNS 111111111, scope 'duns-111111111', resource=RETAILUI"
curl -fs -d client_id=retail-duns-111111111 -d client_secret=retail-duns-111111111-secret -d grant_type=password \
  -d username=apiuser -d password=password -d scope="openid duns-111111111" -d resource=RETAILUI \
  http://localhost:8080/realms/$REALM/protocol/openid-connect/token | python3 -c '
import sys, json, base64
d = json.load(sys.stdin)
p = d["access_token"].split(".")[1]; p += "=" * (-len(p) % 4)
a = json.loads(base64.urlsafe_b64decode(p))
print("   duns:", a.get("duns"), "| resource_access:", a.get("resource_access"), "| scope:", a.get("scope"))'
