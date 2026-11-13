# Retail API (Spring Boot)

REST API for the Toy Shop and Flower Shop catalogs. Every call must carry two valid Keycloak-issued JWTs
(an **access token** and an **ID token**) and the user must hold the **`RETAILUI`** role for the DUNS that owns the data.
Anything else is refused with a JSON `access_denied` error.

| | |
|---|---|
| Stack | Spring Boot 3.5.16, Java 21, Spring Security OAuth2 Resource Server |
| Container | `retail-api:1.0.0`, host port **8090** -> container 8080 |
| Tests | 37 JUnit/MockMvc tests (`./scripts/mvn-docker.sh test`) + a 24-request Postman collection |

## Endpoints

| Method + path | Needs `RETAILUI` under DUNS | Returns |
|---|---|---|
| `GET /api/toys` | `111111111` (Toy Shop) | `{duns, company, count, items[]}` |
| `GET /api/toys/{id}` | `111111111` | one toy (`t1`..`t6`), 404 `not_found` otherwise |
| `GET /api/flowers` | `222222222` (Flower Shop) | `{duns, company, count, items[]}` |
| `GET /api/flowers/{id}` | `222222222` | one flower (`f1`..`f6`), 404 otherwise |
| `GET /api/me` | any DUNS | identity and custom claims the API derived from the verified tokens |
| `GET /actuator/health` | none (public) | `{"status":"UP"}` |

Required headers on `/api/**`:

```
Authorization: Bearer <access token>
X-ID-Token:    <ID token>
```

## Access rules and errors

Both tokens are verified on every request: **RS256 signature** (Keycloak JWKS), **issuer**, **expiry**, **audience**.

| Check | Failure -> |
|---|---|
| `Authorization` header missing / not Bearer / malformed / expired / bad signature / wrong issuer | **401** |
| Access token `aud` does not contain `retail-ui` | **401** |
| `X-ID-Token` missing, malformed, expired, bad signature, wrong issuer | **401** |
| ID token `aud` not in `demo-ui, retail-api-test` | **401** |
| ID token `sub` differs from access token `sub` (two different users) | **401** |
| Valid tokens but `duns_roles` has no `RETAILUI` for the DUNS that owns the endpoint (toys -> 111111111, flowers -> 222222222) | **403** |
| No `duns_roles` claim at all, role spelled differently (case-sensitive), or role under another DUNS | **403** |
| Any other path (`/api/anything-else`) | 401 / 403, never 404 |
| Keycloak's signing keys cannot be fetched (Keycloak down or unreachable from the container) | **503** `service_unavailable` + `Retry-After: 5`; recovers by itself |

Error body (same shape for 401, 403 and, with `"error": "service_unavailable"`, 503):

```json
{ "timestamp": "2026-11-07T20:04:51Z", "status": 403, "error": "access_denied",
  "message": "Access denied: role RETAILUI is required for DUNS 222222222", "path": "/api/flowers" }
```

The `duns_roles` claim (a string array, one value per DUNS, e.g. `"111111111:USA,RETAILUI,CRM"`) is turned into authorities
such as `111111111:RETAILUI`; the rules live in `security/SecurityConfig.java`.

## Prerequisites

- **Docker Desktop** running (that is all you need: Maven and the JDK run inside containers).
- The main solution's Keycloak running and configured: `./scripts/start-keycloak.sh && ./scripts/setup-keycloak.sh` (repo root).
- Free host port **8090**.
- Optional: a local JDK 21 + Maven 3.9 if you prefer `mvn test` directly instead of `scripts/mvn-docker.sh`.
- Optional: Postman (desktop or web) for the collection; Node.js if you want to run it headless with Newman.

## Build, run, test

```bash
cd retail-api

# 1. Unit/integration tests (no Keycloak needed; tokens are signed with a throwaway test key)
./scripts/mvn-docker.sh test

# 2. Build the image and start the container (shows up in Docker Desktop as "retail-api")
docker compose up -d --build
curl http://localhost:8090/actuator/health        # {"status":"UP"}

# 3. Dev-only: create a Keycloak test client + 3 test users so Postman/curl can get real tokens
./scripts/setup-keycloak-api-test.sh
```

Stop / remove: `docker compose down`. Remove the dev test client and users: `./scripts/setup-keycloak-api-test.sh --remove`.

### How the container reaches Keycloak

Tokens carry `iss = http://localhost:8080/realms/demo` (issued to a client on your machine), but inside the container
`localhost` is the container itself. So `docker-compose.yml` keeps the **issuer** as `localhost:8080` and fetches the
**signing keys** from `http://host.docker.internal:8080/...`. Keys are fetched lazily, so the API starts even if Keycloak is down;
calls fail with 401 until it is reachable.

### Configuration (env vars, or `application.yml`)

| Env var | Default | Meaning |
|---|---|---|
| `RETAIL_SECURITY_ISSUER` | `http://localhost:8080/realms/demo` | required `iss` |
| `RETAIL_SECURITY_JWK_SET_URI` | `http://localhost:8080/realms/demo/protocol/openid-connect/certs` | where signing keys come from |
| `RETAIL_SECURITY_ACCESS_AUDIENCE` | `retail-ui` | required in access token `aud` |
| `RETAIL_SECURITY_ID_AUDIENCES` | `demo-ui,retail-api-test` | accepted ID token `aud` |
| `RETAIL_SECURITY_REQUIRED_ROLE` | `RETAILUI` | role needed |
| `RETAIL_SECURITY_TOY_DUNS` / `_FLOWER_DUNS` | `111111111` / `222222222` | DUNS owning each catalog |

## For external agents: how to get tokens and call the API

Any script, service or AI agent can call the API if it can obtain an **access token and an ID token** for a user who holds
`RETAILUI`. Full explanation, diagrams and troubleshooting: **[docs/DESIGN.md section 13](../docs/DESIGN.md#13-retail-rest-api-and-external-agents)**.

### One-time setup (Keycloak admin + API operator)

```bash
# 1. Keycloak running and configured (repo root)
./scripts/start-keycloak.sh && ./scripts/setup-keycloak.sh

# 2. Register the agent as an OIDC client  (--device: user approves in a browser, MFA works;
#    add --confidential for a client secret; --password-grant only for dev/test)
cd retail-api
./scripts/register-agent-client.sh my-agent --device

# 3. Give the user RETAILUI for the DUNS they need: Keycloak admin console -> Users -> <user> -> Attributes -> "DUNS roles"
#    e.g.   111111111:USA,RETAILUI        (one value per DUNS)

# 4. Allow-list the client's ID-token audience in the API, then recreate the container
echo 'RETAIL_SECURITY_ID_AUDIENCES=demo-ui,retail-api-test,my-agent' > .env
docker compose up -d
```

Skipping step 4 gives `401 ... Token audience [my-agent] is not accepted by this API`; skipping step 3 gives `403 ... RETAILUI is required`.

### At run time (the agent)

1. **Get the tokens** from Keycloak. `scope=openid` is mandatory, otherwise there is no ID token. Device grant (headless agent):

   ```bash
   KC=http://localhost:8080/realms/demo/protocol/openid-connect
   curl -s -d client_id=my-agent -d scope=openid $KC/auth/device
   #  -> device_code, user_code, verification_uri_complete   (show the URL to the user; they sign in and click Yes)
   curl -s -d client_id=my-agent -d grant_type=urn:ietf:params:oauth:grant-type:device_code -d device_code=<device_code> $KC/token
   #  repeat every 5 s: {"error":"authorization_pending"} ... then {"access_token","id_token","refresh_token"}
   ```

   Dev/test alternative (password grant, MFA-less users only):
   `curl -s -d client_id=retail-api-test -d grant_type=password -d username=apiuser -d password=password -d scope=openid $KC/token`

2. **Call the API with both tokens:**

   ```bash
   curl -H "Authorization: Bearer $ACCESS_TOKEN" -H "X-ID-Token: $ID_TOKEN" http://localhost:8090/api/toys
   ```

3. **Refresh before they expire** (every 5 min): `curl -s -d client_id=my-agent -d grant_type=refresh_token -d refresh_token=$REFRESH -d scope=openid $KC/token`
   and use the **new pair** of tokens (add `-d client_secret=...` for a confidential client).

A complete runnable reference client (token, calls, refresh) is `examples/call-api.sh`:

```bash
./examples/call-api.sh                                            # dev client + apiuser
CLIENT_ID=my-agent CLIENT_SECRET=... USERNAME=u PASSWORD=p ./examples/call-api.sh   # your own client (password grant must be enabled)
```

## Maven tests

`./scripts/mvn-docker.sh test` runs 37 tests in four classes:

| Class | Covers |
|---|---|
| `RetailApiEndpointsTest` (8) | list/get toys and flowers, 404s, `/api/me` claims, public health |
| `RetailApiAccessDeniedTest` (22) | missing/garbage/expired/wrongly-signed/wrong-issuer/wrong-audience/`alg=none` access tokens; missing/invalid/other-user ID tokens; no RETAILUI anywhere, only for one DUNS, on the wrong DUNS, wrong case, no claim; unknown paths |
| `JwksUnavailableTest` (3) | signing keys unreachable -> 503, no-token still 401, health stays up |
| `DunsRolesTest` (4) | `duns_roles` parsing |

They need no Keycloak: `TestTokens` mints RS256 JWTs like Keycloak's, and `TestJwtConfig` swaps only the signature key source.

## Postman

1. Run the three commands in "Build, run, test" (tests optional), then set up Keycloak once:
   `./scripts/setup-keycloak-api-test.sh && ./scripts/setup-keycloak-duns-clients.sh`
2. Get the collection into Postman, either way:
   - **Import** the portable file `postman/export/retail-api.postman_collection.json`, or
   - open the repo as a local workspace: Postman's native folder `postman/collections/Retail API (toys & flowers)/` (and
     `.postman/resources.yaml`) is what the Postman app writes when you link the repo.
3. Open the collection -> **Run** (Collection Runner) -> **Run Retail API**.

### One client per DUNS, one scope per resource

| DUNS | Client id (own secret) | Owns | Scope that requests RETAILUI |
|---|---|---|---|
| 111111111 | `retail-duns-111111111` / `retail-duns-111111111-secret` | toys | `111111111:RETAILUI` |
| 222222222 | `retail-duns-222222222` / `retail-duns-222222222-secret` | flowers | `222222222:RETAILUI` |

```
POST /realms/demo/protocol/openid-connect/token
client_id=retail-duns-111111111  client_secret=...  grant_type=password  username=apiuser  password=password
scope=openid 111111111:RETAILUI
```

The token then carries `duns=111111111` and `resource_access.duns-111111111.roles=["RETAILUI"]` and nothing else: no other
role, no other DUNS. A user who lacks the role gets a token without it, and no scope means no roles. (Roles live on a
separate bearer-only client `duns-<DUNS>` because Keycloak always includes a client's own roles in its tokens.)
The API grants `<duns>:<role>` authorities from those claims; the toys need RETAILUI of DUNS 111111111, the flowers of 222222222.
Dev secrets are fixed in `scripts/setup-keycloak-duns-clients.sh`; the clients' ids are accepted as ID-token audiences via
`RETAIL_SECURITY_ID_AUDIENCES` (defaults in `docker-compose.yml`).

Request names in the collection show which client (DUNS) and scope (resource) each call uses. Folders:

| Folder | What it does |
|---|---|
| 0. Get tokens | 8 token requests: apiuser/toyonly/noretail against each DUNS client, plus `CRM`-only and no-scope tokens |
| 1. Health | public endpoint |
| 2-3. Toys, Flowers | happy paths with the matching DUNS's token |
| 4. Identity echo | `/api/me` shows exactly one DUNS and one role per token |
| 5. Access denied (401) | no token, garbage token, tampered signature, ID token missing/garbage/other user, ID token used as access token |
| 6. Access denied (403) | wrong DUNS for the data, wrong resource scope, no scope, user without the role |

55 requests, 139 assertions (folders 0-6 above plus the `resource=` variant below). Headless (needs `localhost` URLs, so run it on the host): `npx newman run postman/export/retail-api.postman_collection.json`.

### Variant: one scope per DUNS, role chosen by `resource=`

Instead of one scope per (DUNS, role), each DUNS has a single scope `duns-<DUNS>` that maps **all** of that DUNS's roles. The
role that ends up in the token is picked at request time by the token request's `resource` parameter:

```
POST /realms/demo/protocol/openid-connect/token
client_id=retail-duns-111111111  client_secret=...  grant_type=password  username=apiuser  password=password
scope=openid duns-111111111  resource=RETAILUI
```
-> `duns=111111111`, `resource_access.duns-111111111.roles=["RETAILUI"]` (apiuser also holds USA and CRM there; they are removed).

Plain Keycloak cannot do this (no built-in mapper reads a request parameter), so it is a small custom protocol mapper,
`keycloak-extensions/resource-role-mapper` (Java SPI, provider id `resource-role-filter-mapper`), attached to each `duns-<DUNS>` scope.
It runs after the standard role mappers and only ever **removes** roles:

| Request | Token roles |
|---|---|
| `resource=RETAILUI` | RETAILUI (if the user holds it under that DUNS) |
| `resource="RETAILUI CRR"` (space or comma separated) | both (Keycloak rejects a repeated `resource` parameter) |
| no `resource` | none (deny by default) |
| a role the user lacks, or that does not exist | none (the filter never adds) |
| refresh_token grant without `resource` | the role(s) of the original request (remembered on the client session) |

`resource` is a plain role name here; it is not validated as the absolute URI that RFC 8707 asks for.
The other DUNS's scope is not available to a client (`invalid_scope`), so a DUNS client cannot reach another DUNS's roles.

```bash
./keycloak-extensions/resource-role-mapper/deploy.sh                   # build (Maven in Docker), install the jar, restart Keycloak
./retail-api/scripts/setup-keycloak-duns-resource-scopes.sh            # scopes, scope mappings, mapper; needs the two setup scripts above
```
The jar lives in the Keycloak container's filesystem; re-run `deploy.sh` if the container is recreated. The Keycloak version in
`keycloak-extensions/resource-role-mapper/pom.xml` must match the server. Postman folders 7-9 exercise it (token matrix, happy paths, denials);
the API needed no change, it already reads `resource_access.duns-<DUNS>.roles`.

| Test user | `duns_roles` | Toys | Flowers |
|---|---|---|---|
| `apiuser` / `password` | `111111111:USA,RETAILUI,CRM` + `222222222:USA,RETAILUI,CRM,DAM_OP,CRR` | 200 | 200 |
| `toyonly` / `password` | `111111111:USA,RETAILUI` + `222222222:USA,CRM` | 200 | 403 |
| `noretail` / `password` | `111111111:USA,CRM` + `222222222:USA,DAM_OP` | 403 | 403 |

## Calling it with the portal's own tokens

Log in to the portal at http://localhost:3000, open the browser dev tools console and run
`JSON.parse(sessionStorage.tokens)` to get `access_token` and `id_token`. Then:

```bash
curl -H "Authorization: Bearer $ACCESS" -H "X-ID-Token: $ID" http://localhost:8090/api/toys
```

(The portal's tokens carry `aud` `demo-ui` for the ID token and `retail-ui` for the access token, which the API accepts.)
Tokens last 5 minutes.

## Security notes (dev)

- The dev test client (`retail-api-test`) enables the OAuth2 **password grant** and the test users have **no MFA**; use it only on a local
  demo realm and remove it afterwards (`--remove`).
- Plain HTTP, no rate limiting, no CORS; data is in memory.
- Revocation is not checked per request (no `userinfo` call): a logged-out user's still-valid token works until it expires (5 min).

> The top-level `postman/` folder is the primary home for Postman collections, environments and exports. Build or export new collections there, not under `retail-api/`.
