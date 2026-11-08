# Generic Portal: Keycloak SSO, MFA, DUNS-scoped apps, and a `REMOTE_USER` CRM

A local reference implementation of a multi-application portal secured by **Keycloak (OIDC)**:

- **Portal** (React): logs in with OIDC Authorization Code + PKCE and **TOTP MFA**, shows a DUNS drop-down and one
  tile per role of the selected DUNS, refreshes tokens silently.
- **Retail UI** (React): launched from the portal with the OIDC token, **validates it** (signature, issuer, audience,
  expiry, live session) and shows a Toy Shop or Flower Shop catalog per DUNS.
- **CRM** (React + Node): launched from the portal through a **gateway ("shim")** that validates the token server-side,
  lets the user pick a DUNS, and passes identity to the CRM as an HTTP header
  **`REMOTE_USER: <duns>$<username>`** (HMAC-signed). The CRM has a **Snoop** page that lists all headers it received.
- Every app refuses to run unless launched from the portal.

Full architecture, sequence diagrams, token-validation steps and `REMOTE_USER` details:
**[docs/DESIGN.md](docs/DESIGN.md)**.

> **Development setup only.** HTTP, default admin password, in-memory sessions, hard-coded data. See
> [DESIGN.md section 12](docs/DESIGN.md#12-security-analysis) before reusing any of it.

---

## 1. Prerequisites

You must install these yourself **before** running any script. The scripts install npm packages and start Keycloak
for you, but they do **not** install the software below.

| Requirement | Version | Why | Check |
|---|---|---|---|
| **Docker** (Docker Desktop on macOS/Windows, Docker Engine on Linux) with the daemon **running** | Recent (tested 29.8.0) | Runs Keycloak; `setup-keycloak.sh` drives it with `docker exec` | `docker info` |
| **Node.js** and **npm** | Node **20.19+ or 22.12+** (tested 26.13) | Vite 7 build/dev server; the CRM gateway and backend are plain Node | `node -v` |
| **Python 3** | 3.8+ | `setup-keycloak.sh` edits JSON (user-profile, DUNS data) | `python3 -V` |
| **curl** | any | Health checks and the smoke test | `curl --version` |
| **openssl** | any | Generates the gateway's per-start signing key | `openssl version` |
| **bash** | 3.2+ (the macOS default works) | All scripts are `#!/usr/bin/env bash` | `bash --version` |
| **lsof** (optional) | any | Frees ports on stop; detects port clashes. Present on macOS; `apt install lsof` on Linux | `lsof -v` |
| An **authenticator app** (Google Authenticator, Authy, 1Password, Microsoft Authenticator, ...) | | The test user must enrol TOTP at first login | |
| A modern **browser** (Chrome, Firefox, Safari, Edge) | | Uses Web Crypto, `sessionStorage`, `postMessage`; allow pop-ups for `localhost` (tiles open new tabs) | |
| **Internet access** (first run) | | Pull the Keycloak image from `quay.io`; `npm ci` downloads packages | |

**Network ports that must be free** on your machine:

| Port | Used by |
|---|---|
| 8080 | Keycloak |
| 3000 | Portal |
| 3001 | Retail UI |
| 3002 | CRM gateway |
| 3003 | CRM backend (bound to 127.0.0.1) |
| 8090 | Retail API container (optional, `retail-api/`) |

**Hardware:** about 1 GB free RAM for the Keycloak container, 1-2 GB disk for the image and `node_modules`. Docker
Desktop should have at least 2 GB of memory assigned. Verified on macOS (Apple silicon, linux/arm64 image); Linux and
Windows (WSL2) should work because only standard tooling is used, but are untested.

**The hostname must be `localhost`.** Redirect URIs, CORS origins and token issuer are all `localhost`. Do not use
`127.0.0.1` in the browser.

---

## 2. Install and run (step by step)

Run everything from the repository root.

```bash
# 1. Check prerequisites, install npm dependencies for all four apps, build the CRM apps
./scripts/install.sh

# 2. Pull and start Keycloak 26.8.1 in Docker (container "keycloak", port 8080)
./scripts/start-keycloak.sh

# 3. Create the realm, clients, claim mappers, MFA and the test user (safe to re-run)
./scripts/setup-keycloak.sh

# 4. Start portal (3000), retail UI (3001), CRM gateway (3002) and CRM backend (3003)
./scripts/start.sh

# 5. Optional: verify the install and the access-control rules
./scripts/smoke-test.sh
```

Then open **http://localhost:3000**.

Order matters: Keycloak must be running and configured (steps 2-3) before `start.sh`, which refuses to start
otherwise.

### What each script does

| Script | What it does |
|---|---|
| `scripts/install.sh` | Verifies Docker/Node/npm/Python/curl/openssl and Node version; `npm ci` in `portal`, `retail-ui`, `crm-shim`, `crm-app`; `npm run build` in `crm-shim` and `crm-app` |
| `scripts/start-keycloak.sh` | `docker pull` + `docker run` of `quay.io/keycloak/keycloak:26.8.1` (`start-dev`, admin `admin`/`admin`, data in Docker volume `keycloak_data`); starts the container if it already exists; waits until ready |
| `scripts/setup-keycloak.sh` | Creates realm `demo`, user-profile attributes, OTP required action, clients `demo-ui` / `retail-ui` / `crm-ui`, all protocol mappers, role `portal-admin`, user `testuser` with DUNS data. Idempotent: existing objects are left alone |
| `scripts/start.sh` | Checks Keycloak and builds, generates a fresh `SHIM_SECRET`, starts the four processes in the background, writes `logs/*.log` and `.run/*.pid` |
| `scripts/stop.sh` | Stops the four app processes (pid files, then anything left on ports 3000-3003). Keycloak keeps running |
| `scripts/smoke-test.sh` | 13 checks: services up, Keycloak client accepted, and every "direct access is refused" rule. Needs no login |

---

## 3. First login and what to try

| | |
|---|---|
| Portal | http://localhost:3000 |
| Keycloak account / realm | http://localhost:8080/realms/demo |
| Keycloak admin console | http://localhost:8080/admin  (`admin` / `admin`) |
| Test user | `testuser` / `password` |

1. Open http://localhost:3000. You are redirected to Keycloak.
2. Log in as `testuser` / `password`.
3. **Enrol MFA**: scan the QR code with your authenticator app and enter the 6-digit code. Later logins ask for the
   code after the password.
4. The portal shows the DUNS drop-down (first DUNS selected) and tiles for that DUNS. Switch DUNS: the tiles change.
5. Click **RETAILUI**: Retail UI opens in a new tab, validates the token, and shows the Toy Shop (`111111111`) or Flower
   Shop (`222222222`) catalog. Change the DUNS drop-down there too.
6. Click **CRM**: the gateway opens with one tile per DUNS. Pick one: the CRM opens. Use the left nav:
   **Dashboard**, **Customers**, **Snoop** (all HTTP headers incl. `REMOTE_USER: 222222222$testuser`).
   "Switch DUNS" returns to the picker.
7. Try the refusals: open http://localhost:3001, http://localhost:3002 or http://localhost:3002/crm/ directly. Each shows
   "Launch from the portal".

### The seeded data

| DUNS | Company | Roles | Tiles in portal |
|---|---|---|---|
| 111111111 | Toy Shop | `USA, RETAILUI, CRM` | Retail UI, CRM (`USA` grants access but draws no tile) |
| 222222222 | Flower Shop | `USA, RETAILUI, CRM, DAM_OP, CRR` | Retail UI, CRM, DAM Operations, CRR |

Tiles without a launch URL (DAM_OP, CRR) are display-only placeholders.

### Changing who sees what

Edit the `duns_roles` attribute in the admin console (Users -> testuser -> Attributes, **DUNS roles**, one value per DUNS
in the form `DUNS:ROLE,ROLE,...`), or edit `DUNS_ROLES` in `scripts/setup-keycloak.sh`. Log out and back in to get a
token with the new claim. Add a tile for a new role in `portal/src/tiles.js`.

---

## 3b. Retail REST API (Spring Boot, optional)

`retail-api/` is a separate Spring Boot service exposing `/api/toys` and `/api/flowers`, protected by the same Keycloak
tokens: an **access token** (`Authorization: Bearer`) plus the **ID token** (`X-ID-Token`), role `RETAILUI` for the DUNS that owns
the data, JSON `access_denied` (401/403) otherwise. It runs as a Docker container on **port 8090**, with 37 Maven tests and a
Postman collection. Needs Docker only (Maven runs in a container):

```bash
cd retail-api
./scripts/mvn-docker.sh test                 # 37 tests
docker compose up -d --build                 # container "retail-api" in Docker Desktop
./scripts/setup-keycloak-api-test.sh         # dev-only test client + users for Postman/curl
./examples/call-api.sh                       # get both tokens from Keycloak and call the API
```

**External agents** (scripts, services, AI agents) follow this sequence: *register an OIDC client* (`scripts/register-agent-client.sh my-agent --device`)
-> *give the user `RETAILUI` in their `duns_roles` attribute* -> *allow-list the client in `retail-api/.env`* and `docker compose up -d`
-> *get an access token + ID token with `scope=openid`* (device grant, auth code + PKCE, or password grant for dev) -> *call the API with
both tokens* -> *refresh every ~5 minutes*. Step-by-step commands: [retail-api/README.md](retail-api/README.md#for-external-agents-how-to-get-tokens-and-call-the-api);
diagrams, validation rules and troubleshooting: [docs/DESIGN.md section 13](docs/DESIGN.md#13-retail-rest-api-and-external-agents).

---

## 4. Stopping, restarting, resetting

```bash
./scripts/stop.sh                 # stop portal, retail UI, CRM gateway/backend
./scripts/start.sh                # start them again (also restarts if already running)
docker stop keycloak              # stop Keycloak (data is kept in the keycloak_data volume)
docker start keycloak             # or: ./scripts/start-keycloak.sh

# Full reset (deletes the realm, users, OTP enrolment):
./scripts/stop.sh && docker rm -f keycloak && docker volume rm keycloak_data
./scripts/start-keycloak.sh && ./scripts/setup-keycloak.sh && ./scripts/start.sh
```

To re-test MFA enrolment without a full reset: admin console -> Users -> testuser -> Credentials -> delete the OTP
credential, and add the **Configure OTP** required action on the user.

Logs: `logs/portal.log`, `logs/retail-ui.log`, `logs/crm-shim.log`, `logs/crm-app.log`; Keycloak: `docker logs keycloak`.

After editing `crm-shim/` or `crm-app/` source, rebuild its UI and restart:

```bash
(cd crm-app && npm run build) && (cd crm-shim && npm run build) && ./scripts/start.sh
```

The portal and Retail UI run in Vite dev mode and hot-reload automatically.

---

## 5. Repository layout

```
.
├── README.md                    this file
├── docs/DESIGN.md               architecture, flows, token validation, REMOTE_USER
├── scripts/                     install / Keycloak / start / stop / smoke test
├── portal/                      React portal (Vite, port 3000)
│   └── src/ auth.js (OIDC+PKCE, refresh) · App.jsx (UI, launch, postMessage) · tiles.js (role -> tile)
├── retail-ui/                   React retail front (Vite, port 3001)
│   └── src/ validate.js (JWKS/RS256 validation) · App.jsx · catalog.js (toys / flowers)
├── crm-shim/                    CRM gateway (Node server.js + React picker, port 3002)
│   ├── server.js                token validation, sessions, launch tickets, reverse proxy, REMOTE_USER injection
│   └── src/App.jsx              DUNS tile picker
├── crm-app/                     CRM (Node server.js + React UI, 127.0.0.1:3003)
│   ├── server.js                HMAC check, REMOTE_USER parsing, /crm/api/*
│   └── src/App.jsx              Dashboard / Customers / Snoop
├── retail-api/                  Spring Boot REST API (toys, flowers): Dockerfile, compose, Maven tests, Postman collection,
│                                agent client registration script, example client (examples/call-api.sh)
├── ui-legacy/                   first single-page prototype (superseded, not used)
├── logs/  .run/                 created at runtime (git-ignored)
```

---

## 6. Configuration reference

Everything is intentionally hard-coded for localhost. Defaults and the places to change them:

| What | Default | Override / location |
|---|---|---|
| Keycloak admin | `admin` / `admin` | `KC_ADMIN`, `KC_ADMIN_PASSWORD` env vars for the Keycloak scripts |
| Keycloak container / image | `keycloak`, `quay.io/keycloak/keycloak:26.8.1` | `KC_CONTAINER`, `KEYCLOAK_IMAGE` env vars |
| Test user / password | `testuser` / `password` | `TEST_USER`, `TEST_PASSWORD` env vars for `setup-keycloak.sh` (applied when the user is first created) |
| Realm, clients | `demo`; `demo-ui`, `retail-ui`, `crm-ui` | `scripts/setup-keycloak.sh` and the `CFG` blocks in `portal/src/auth.js`, `retail-ui/src/validate.js`, `crm-shim/server.js` |
| Ports | 8080 / 3000-3003 | Source constants and Keycloak redirect URIs / web origins; not parameterised |
| Gateway-to-CRM signing key | random per start | `SHIM_SECRET` env var (set by `start.sh`) |
| Gateway session idle / max | 30 min / 8 h | `IDLE_MS`, `MAX_MS` in `crm-shim/server.js` |
| Token lifespan | 5 min | Keycloak admin -> Realm settings -> Tokens |

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `install.sh`: "'docker' is required" / "daemon is not running" | Install and start Docker Desktop, then retry |
| `install.sh`: Node too old | Install Node 20.19+ or 22.12+ (Vite 7 requirement) |
| `start-keycloak.sh`: "port 8080 is in use" | Stop the other process or container using 8080 |
| `start.sh`: "Keycloak realm 'demo' not reachable" | Run `start-keycloak.sh` then `setup-keycloak.sh` |
| `start.sh`: "not installed/built" | Run `install.sh` |
| `Port 3000 is already in use` (Vite `strictPort`) | `./scripts/stop.sh`, or find the process with `lsof -iTCP:3000 -sTCP:LISTEN` |
| Browser shows "Invalid redirect URI" or Keycloak error page | You are not on `http://localhost:3000`; re-run `setup-keycloak.sh` |
| Tile click does nothing | Browser blocked the pop-up; allow pop-ups for `localhost` |
| Retail UI / CRM says "Access denied: Token not issued for ..." | The token predates the audience mappers. Log out of the portal and log in again |
| Retail UI says "Session expired, relaunch from the portal" | The portal tab that launched it was closed (it holds the refresh token), or the Keycloak session ended |
| CRM page shows "Launch from the portal" after a browser reload | By design: each CRM page load needs a single-use launch ticket. Relaunch via the portal or "Switch DUNS" |
| CRM gateway "Keycloak session is no longer active" | You logged out of Keycloak, or the token is stale; relaunch from the portal |
| Lost the authenticator device | Admin console -> Users -> testuser -> Credentials -> delete OTP, add required action "Configure OTP" |
| `setup-keycloak.sh`: container not running | `./scripts/start-keycloak.sh` first |
| `docker pull` from quay.io fails | Network or proxy; retry, or set `KEYCLOAK_IMAGE` to a mirror |
| Everything fails after a laptop reboot | Docker may not have restarted Keycloak: `./scripts/start-keycloak.sh && ./scripts/start.sh` |

Run `./scripts/smoke-test.sh` to see exactly which layer is failing.

---

## 8. How it works, in one paragraph

The portal authenticates with Keycloak (code + PKCE + TOTP) and reads the `duns_roles` claim. Retail UI is handed the
tokens in a URL fragment and validates them in the browser against Keycloak's JWKS, asking the portal tab for fresh
tokens over `postMessage`. The CRM gateway is handed only the access token, validates it server-side, keeps a session,
shows the DUNS tiles, issues a single-use launch ticket, and reverse-proxies the CRM while stripping any
client-supplied identity and injecting `REMOTE_USER: <duns>$<username>` with an HMAC signature that the CRM backend
verifies. Read [docs/DESIGN.md](docs/DESIGN.md) for the sequence diagrams and the exact checks.
