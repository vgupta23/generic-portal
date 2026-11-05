#!/usr/bin/env bash
# Starts portal (3000), retail UI (3001), CRM gateway/shim (3002) and CRM backend (3003, localhost only).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
mkdir -p logs .run

curl -fs -o /dev/null http://localhost:8080/realms/demo || {
  echo "ERROR: Keycloak realm 'demo' not reachable at http://localhost:8080." >&2
  echo "       Run ./scripts/start-keycloak.sh and ./scripts/setup-keycloak.sh first." >&2; exit 1; }
[ -d portal/node_modules ] && [ -f crm-shim/dist/index.html ] && [ -f crm-app/dist/index.html ] || {
  echo "ERROR: not installed/built. Run ./scripts/install.sh first." >&2; exit 1; }

./scripts/stop.sh >/dev/null

# The gateway signs REMOTE_USER with this key and the CRM backend verifies it. New random key on every start.
export SHIM_SECRET="$(openssl rand -hex 32)"

launch() { # <name> <dir> <command...>
  local name="$1" dir="$2"; shift 2
  (
    cd "$ROOT/$dir"
    nohup "$@" > "$ROOT/logs/$name.log" 2>&1 &
    echo $! > "$ROOT/.run/$name.pid"
  )
}
launch portal    portal    node node_modules/vite/bin/vite.js
launch retail-ui retail-ui node node_modules/vite/bin/vite.js
launch crm-app   crm-app   node server.js
launch crm-shim  crm-shim  node server.js

for entry in "portal:3000" "retail-ui:3001" "crm-shim:3002"; do
  name="${entry%%:*}"; port="${entry##*:}"
  for _ in $(seq 1 30); do curl -fs -o /dev/null "http://localhost:$port/" && break; sleep 0.5; done
  curl -fs -o /dev/null "http://localhost:$port/" || { echo "ERROR: $name did not start; see logs/$name.log" >&2; exit 1; }
done

cat <<MSG
All services are up:
  Portal (start here) : http://localhost:3000
  Retail UI           : http://localhost:3001   (launch from the portal)
  CRM gateway         : http://localhost:3002   (launch from the portal)
  Keycloak            : http://localhost:8080   (admin console: /admin)
Logs: ./logs/*.log      Stop: ./scripts/stop.sh
MSG
