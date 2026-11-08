#!/usr/bin/env bash
# Reference client for an external agent: get an access token + ID token from Keycloak, then call the Retail API.
# Requires: curl, python3 (only to pretty-print/decode JSON).
#
#   ./examples/call-api.sh                       # uses the dev test client + user apiuser
#   CLIENT_ID=my-agent CLIENT_SECRET=... USERNAME=alice PASSWORD=... ./examples/call-api.sh
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="${REALM:-demo}"
API_URL="${API_URL:-http://localhost:8090}"
CLIENT_ID="${CLIENT_ID:-retail-api-test}"
CLIENT_SECRET="${CLIENT_SECRET:-}"          # only for confidential clients
USERNAME="${USERNAME:-apiuser}"
PASSWORD="${PASSWORD:-password}"
TOKEN_URL="$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token"

json() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }
claims() { # <jwt> <claim>... : print the selected claims of a token (payload is not verified here)
  python3 - "$@" <<'PY'
import sys, json, base64
tok, wanted = sys.argv[1], sys.argv[2:]
p = tok.split(".")[1]; p += "=" * (-len(p) % 4)
c = json.loads(base64.urlsafe_b64decode(p))
print({k: c.get(k) for k in wanted})
PY
}

echo "== 1. Get tokens (password grant; scope=openid is what makes Keycloak return an ID token)"
SECRET_ARG=(); [ -n "$CLIENT_SECRET" ] && SECRET_ARG=(-d "client_secret=$CLIENT_SECRET")
RESP="$(curl -fsS ${SECRET_ARG[@]+"${SECRET_ARG[@]}"} -d "client_id=$CLIENT_ID" -d grant_type=password \
        -d "username=$USERNAME" -d "password=$PASSWORD" -d scope=openid "$TOKEN_URL")"
ACCESS="$(echo "$RESP" | json "['access_token']")"
ID="$(echo "$RESP" | json "['id_token']")"
REFRESH="$(echo "$RESP" | json "['refresh_token']")"
echo "   access_token: ${ACCESS:0:30}...  ($(echo "$RESP" | json "['expires_in']")s)"
echo "   id_token    : ${ID:0:30}..."
echo "   access claims: $(claims "$ACCESS" iss aud azp preferred_username duns_roles)"
echo "   id claims    : $(claims "$ID" iss aud sub)"

call() { # <path>
  echo "== GET $1"
  curl -sS -w "\n   -> HTTP %{http_code}\n" -H "Authorization: Bearer $ACCESS" -H "X-ID-Token: $ID" "$API_URL$1" | python3 -c "
import sys
raw = sys.stdin.read(); body, _, status = raw.rpartition('\n   -> ')
import json
try: print(json.dumps(json.loads(body), indent=2)[:700])
except Exception: print(body)
print('   -> ' + status.strip())"
}
call /api/toys/t1
call /api/flowers
call /api/me

echo "== 4. Refresh before the access token expires (no password needed)"
NEW="$(curl -fsS ${SECRET_ARG[@]+"${SECRET_ARG[@]}"} -d "client_id=$CLIENT_ID" -d grant_type=refresh_token -d "refresh_token=$REFRESH" -d scope=openid "$TOKEN_URL")"
ACCESS="$(echo "$NEW" | json "['access_token']")"; ID="$(echo "$NEW" | json "['id_token']")"
echo "   got a fresh access token and ID token"
call /api/toys/t2
