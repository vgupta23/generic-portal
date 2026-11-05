#!/usr/bin/env bash
# Quick checks that everything is up and that direct (non-portal) access is refused. No login needed.
cd "$(dirname "$0")/.."
pass=0; failn=0
check() { # <description> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "  PASS  $1"; pass=$((pass+1)); else echo "  FAIL  $1 (expected $2, got $3)"; failn=$((failn+1)); fi
}
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
HT='Accept: text/html'

echo "Services"
check "Keycloak realm 'demo' discovery"            200 "$(code http://localhost:8080/realms/demo/.well-known/openid-configuration)"
check "Portal serves"                              200 "$(code http://localhost:3000/)"
check "Retail UI serves"                           200 "$(code http://localhost:3001/)"
check "CRM gateway serves"                         200 "$(code http://localhost:3002/)"
echo "Keycloak configuration"
CLIENTS="$(curl -s http://localhost:8080/realms/demo/protocol/openid-connect/auth?client_id=demo-ui\&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2F\&response_type=code\&scope=openid\&code_challenge=x\&code_challenge_method=S256 -o /dev/null -w '%{http_code}')"
check "demo-ui client accepted by Keycloak"        302 "$CLIENTS"
echo "Direct access is refused"
check "Gateway /shim/api/session without token"    401 "$(code -X POST -H 'Content-Type: application/json' -d '{"access_token":"a.b.c"}' http://localhost:3002/shim/api/session)"
check "Gateway DUNS list endpoint is gone"         404 "$(code http://localhost:3002/shim/api/session)"
check "Gateway /shim/switch without session"       403 "$(code -H 'Sec-Fetch-Site: same-origin' http://localhost:3002/shim/switch)"
check "CRM /crm/ without session (html)"           401 "$(code -H "$HT" http://localhost:3002/crm/)"
check "CRM /crm without session"                   401 "$(code -H "$HT" http://localhost:3002/crm)"
check "CRM backend direct, no REMOTE_USER"         403 "$(code -H "$HT" http://localhost:3003/crm/)"
check "CRM backend direct, forged REMOTE_USER"     403 "$(code -H 'REMOTE_USER: 111111111$evil' http://localhost:3003/crm/api/me)"
check "CRM backend direct, bad signature"          403 "$(code -H 'REMOTE_USER: 111111111$evil' -H "X-Shim-Timestamp: $(date +%s)000" -H 'X-Shim-Signature: deadbeef' http://localhost:3003/crm/api/me)"
echo
echo "$pass passed, $failn failed"
[ "$failn" = 0 ]
