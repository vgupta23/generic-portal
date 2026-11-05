#!/usr/bin/env bash
# Checks prerequisites, installs npm dependencies for every app and builds the CRM apps.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() { echo "ERROR: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "'$1' is required but not installed. $2"; }

echo "== Checking prerequisites"
need docker  "Install Docker Desktop (macOS/Windows) or Docker Engine (Linux)."
need node    "Install Node.js 20.19+ or 22.12+ (https://nodejs.org)."
need npm     "It ships with Node.js."
need python3 "Install Python 3 (used by scripts/setup-keycloak.sh to edit JSON)."
need curl    "Install curl."
need openssl "Install openssl (used to generate the gateway signing key)."
docker info >/dev/null 2>&1 || fail "Docker is installed but the daemon is not running. Start Docker Desktop."

node -e '
  const [maj, min] = process.versions.node.split(".").map(Number);
  const ok = (maj === 20 && min >= 19) || (maj === 22 && min >= 12) || maj > 22;
  if (!ok) { console.error("ERROR: Node " + process.versions.node + " is too old; need 20.19+ or 22.12+"); process.exit(1); }
'
echo "   docker $(docker --version | awk '{print $3}' | tr -d ,), node $(node -v), npm $(npm -v), python3 $(python3 -V | awk '{print $2}')"

for port in 3000 3001 3002 3003 8080; do
  if command -v lsof >/dev/null 2>&1 && lsof -iTCP:$port -sTCP:LISTEN -P >/dev/null 2>&1; then
    echo "   note: port $port is already in use (fine if it is this solution already running)"
  fi
done

for app in portal retail-ui crm-shim crm-app; do
  echo "== Installing $app"
  (cd "$app" && if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi)
done

for app in crm-shim crm-app; do
  echo "== Building $app"
  (cd "$app" && npm run build)
done

echo
echo "Install complete. Next:"
echo "  ./scripts/start-keycloak.sh   # run Keycloak in Docker"
echo "  ./scripts/setup-keycloak.sh   # create realm, clients, mappers, test user"
echo "  ./scripts/start.sh            # start portal, retail UI, CRM gateway + CRM"
