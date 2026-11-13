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
- **Retail REST API** (Spring Boot, Docker): `/api/toys` and `/api/flowers`, protected by the same Keycloak tokens.
  Machine callers get **one token per DUNS**, and the role in the token is chosen at request time with a
  `resource=` parameter (custom Keycloak provider in `keycloak-extensions/`). A **Postman collection** exercises it.
- **Everything Keycloak needs is scripted** (realm, clients, scopes, roles, users, custom mapper): no manual
  admin-console steps and no realm export to import.

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

The optional Retail API and the Keycloak mapper provider are built with Maven **inside Docker** (`maven:3.9-eclipse-temurin-21`),
so no local JDK or Maven is needed. Postman (desktop or web) is optional, for the API collection.

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

# 6. Optional: the Retail REST API (see section 3b)
./scripts/setup-keycloak-api.sh                       # all API-related Keycloak config + the mapper provider
(cd retail-api && docker compose up -d --build)       # API on http://localhost:8090
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
| `scripts/setup-keycloak-api.sh` | One command for all API-related Keycloak config, in order: `setup-keycloak.sh`, `retail-api/scripts/setup-keycloak-api-test.sh`, `setup-keycloak-duns-clients.sh`, the mapper `deploy.sh` (skipped if already installed; `--redeploy` forces it), `setup-keycloak-duns-resource-scopes.sh`. Idempotent; `--remove` undoes the API setup. Dev/test only |
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

`retail-api/` is a separate Spring Boot service exposing `/api/toys` and `/api/flowers`. Every call needs an **access token**
(`Authorization: Bearer`) plus the **ID token** (`X-ID-Token`), both from Keycloak, and the role `RETAILUI` for the DUNS that
owns the data. Anything else gets a JSON `access_denied` (401/403). It runs as a Docker container on **port 8090**, with 37 Maven
tests and a Postman collection. Docker is the only requirement.

### Setup and run

```bash
# Keycloak must be running (steps 2-3 above). Then, from the repo root:
./scripts/setup-keycloak-api.sh                  # Keycloak side, see "What the script creates"
cd retail-api
./scripts/mvn-docker.sh test                     # optional: 37 tests, no Keycloak needed
docker compose up -d --build                     # container "retail-api"
curl http://localhost:8090/actuator/health       # {"status":"UP"}
./examples/call-api.sh                           # get both tokens from Keycloak and call the API
```

### What `setup-keycloak-api.sh` creates in Keycloak

| Object | Names | Purpose |
|---|---|---|
| Test client + users (no MFA, password `password`) | client `retail-api-test`; users `apiuser`, `toyonly`, `noretail` | Browser-less tokens for curl/Postman; different DUNS roles to test 200/403 |
| One **caller client per DUNS** | `retail-duns-111111111`, `retail-duns-222222222` (secrets `retail-duns-<DUNS>-secret`) | The client that asks for a token names the DUNS; full-scope off, so only requested roles are issued |
| One **resource client per DUNS** | `duns-111111111`, `duns-222222222` (bearer-only) | Holds that DUNS's roles (`USA`, `RETAILUI`, `CRM`, `DAM_OP`, `CRR`) as client roles |
| Scopes `<DUNS>:<ROLE>` | e.g. `111111111:RETAILUI` | Variant A: one scope per DUNS and role |
| Scopes `duns-<DUNS>` + mapper `resource-role-filter` | `duns-111111111`, `duns-222222222` | Variant B: one scope per DUNS; the role is chosen by the request's `resource=` parameter |
| Custom provider `resource-role-filter-mapper` | `keycloak-extensions/resource-role-mapper/` (Java, built in Docker, jar copied to `/opt/keycloak/providers`) | Keycloak has no built-in mapper that reads a request parameter |

Token request for variant B (the role in the token is exactly the one named in `resource`; none if omitted):

```bash
curl -s http://localhost:8080/realms/demo/protocol/openid-connect/token \
  -d client_id=retail-duns-111111111 -d client_secret=retail-duns-111111111-secret \
  -d grant_type=password -d username=apiuser -d password=password \
  -d scope="openid duns-111111111" -d resource=RETAILUI
# -> access token with duns=111111111, resource_access.duns-111111111.roles=["RETAILUI"]
```

`python3 jwtdecode.py <token>` prints the claims of a token. Note: the mapper jar lives inside the Keycloak container, so after
`docker rm keycloak` just re-run `./scripts/setup-keycloak-api.sh` (it rebuilds the jar when the provider is missing).
The Keycloak version in the mapper's `pom.xml` must match `KEYCLOAK_IMAGE`. Remove everything API-related with
`./scripts/setup-keycloak-api.sh --remove`. This setup enables the password grant and MFA-less users: **local dev only**.

### Postman

Import the folder `postman/` (Postman: *Import -> Folder*, or open it as a workspace). It contains the collection
`Retail API (toys & flowers)` (55 requests, 139 assertions; token requests in folders 0 and 7, happy paths, 401 and 403
cases). URLs, realm and the dev client secrets are collection variables (API `localhost:8090`, Keycloak `localhost:8080`), so
nothing needs configuring; the bundled `Retail API` environment is empty. Run the collection top to bottom with the Collection Runner.
`postman/export/retail-api.postman_collection.json` is for headless runs: `npx newman run postman/export/retail-api.postman_collection.json`.

### External agents

Scripts, services and AI agents: *register an OIDC client* (`retail-api/scripts/register-agent-client.sh my-agent --device`)
-> *give the user `RETAILUI` in their `duns_roles` attribute* -> *allow-list the client in `retail-api/.env`* and
`docker compose up -d` -> *get an access token + ID token with `scope=openid`* (device grant, auth code + PKCE, or the dev
password grant) -> *call the API with both tokens* -> *refresh every ~5 minutes*. Step-by-step:
[retail-api/README.md](retail-api/README.md#for-external-agents-how-to-get-tokens-and-call-the-api);
diagrams and validation rules: [docs/DESIGN.md section 13](docs/DESIGN.md#13-retail-rest-api-and-external-agents).

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
./scripts/setup-keycloak-api.sh      # only if you use the Retail API; restores clients, scopes and the mapper jar
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
├── retail-api/                  Spring Boot REST API (toys, flowers): Dockerfile, compose, Maven tests,
│   ├── scripts/                 Keycloak setup for the API (test users, per-DUNS clients/scopes), agent registration, mvn-docker
│   └── examples/call-api.sh     example client
├── keycloak-extensions/
│   └── resource-role-mapper/    custom Keycloak protocol mapper (Java SPI) that honours resource=; deploy.sh builds and installs it
├── postman/                     Postman collection, environment, globals and a Newman-ready export
├── jwtdecode.py                 prints the claims of a JWT
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
| API caller clients / secrets | `retail-duns-<DUNS>` / `retail-duns-<DUNS>-secret` | `DUNS_LIST` in `retail-api/scripts/setup-keycloak-duns-*.sh` |
| ID-token audiences accepted by the API | `demo-ui, retail-api-test, retail-duns-111111111, retail-duns-222222222` | `RETAIL_SECURITY_ID_AUDIENCES` (compose default / `retail-api/.env`) |
| Ports | 8080 / 3000-3003 (+ 8090 API) | Source constants and Keycloak redirect URIs / web origins; not parameterised |
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
| `resource=` token has no roles / `invalid_scope` | Mapper not installed or scope missing: re-run `./scripts/setup-keycloak-api.sh` (use `--redeploy` to rebuild the jar). Omitting `resource` deliberately yields no roles |
| Retail API returns 401 for a new client's ID token | Add the client to `RETAIL_SECURITY_ID_AUDIENCES` and `docker compose up -d` |
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
