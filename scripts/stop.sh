#!/usr/bin/env bash
# Stops the app processes (not Keycloak). Uses pid files, then frees ports 3000-3003 as a fallback.
cd "$(dirname "$0")/.."
for f in .run/*.pid; do
  [ -f "$f" ] || continue
  kill "$(cat "$f")" 2>/dev/null || true
  rm -f "$f"
done
if command -v lsof >/dev/null 2>&1; then
  for port in 3000 3001 3002 3003; do
    pids="$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null || true)"
    [ -n "$pids" ] && kill $pids 2>/dev/null || true
  done
fi
sleep 0.5
echo "Apps stopped (Keycloak left running; stop it with: docker stop keycloak)."
