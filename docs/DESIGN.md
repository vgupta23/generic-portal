# Design Document

Multi-application portal with Keycloak OIDC, MFA, DUNS-scoped authorization, and a header-based
(`REMOTE_USER`) integration for a legacy-style CRM application.

> Everything here is a **local development reference implementation**. Section 12 lists what must change
> before anything like this goes near production.

## Contents

1. [Goals and scope](#1-goals-and-scope)
2. [Architecture](#2-architecture)
3. [Identity model (Keycloak)](#3-identity-model-keycloak)
4. [Flow A: Portal login (OIDC Authorization Code + PKCE + MFA)](#4-flow-a-portal-login)
5. [Flow B: Token refresh](#5-flow-b-token-refresh)
6. [Flow C: DUNS selection and tiles in the portal](#6-flow-c-duns-selection-and-tiles)
7. [Flow D: Launching Retail UI (token validation in the browser)](#7-flow-d-launching-retail-ui)
8. [Flow E: Launching CRM (gateway, validation, `REMOTE_USER`)](#8-flow-e-launching-crm)
9. [`REMOTE_USER` in depth](#9-remote_user-in-depth)
10. [Direct-access and bookmark behaviour](#10-direct-access-and-bookmark-behaviour)
11. [Reference tables](#11-reference-tables)
12. [Security analysis, limitations, production hardening](#12-security-analysis)

---

## 1. Goals and scope

| # | Requirement | Where it is met |
|---|---|---|
| 1 | Central authentication through an OIDC identity provider (Keycloak) | Flow A |
| 2 | Multi-factor authentication | TOTP, section 3.5 |
| 3 | Custom claims fetched from Keycloak (department, employee id, roles, DUNS to roles) | section 3.4 |
| 4 | A portal with top nav, left nav, a DUNS drop-down, and one tile per role of the selected DUNS | Flow C |
| 5 | Tiles launch child apps; the OIDC token is handed over and **validated** by the receiving app | Flow D, Flow E |
| 6 | Each DUNS is a company (111111111 = Toy Shop, 222222222 = Flower Shop) and the app renders that company's data | Retail UI, CRM |
| 7 | Sessions do not expire while the user is active | Flow B |
| 8 | CRM receives user identity as an HTTP header (`REMOTE_USER = <duns>$<username>`) rather than a token, via a shim layer that also lists DUNS tiles | Flow E, section 9 |
| 9 | Apps can only be launched from the portal; direct URLs, bookmarks and forged headers are refused | section 10 |
| 10 | A "Snoop" page shows every HTTP header the CRM received | section 9.6 |

Out of scope: user self-registration, user administration UI, persistence of business data (all catalog and
customer data is hard-coded), TLS, high availability.

---

## 2. Architecture

### 2.1 Component view

```mermaid
flowchart LR
    subgraph Browser
      P[Portal tab<br/>localhost:3000]
      R[Retail UI tab<br/>localhost:3001]
      C[Gateway + CRM tab<br/>localhost:3002]
    end

    KC[(Keycloak 26.8.1<br/>localhost:8080<br/>realm: demo)]

    subgraph "CRM stack (Node.js)"
      SH[CRM gateway / shim<br/>:3002<br/>token validation, session,<br/>DUNS picker, reverse proxy]
      CB[CRM backend<br/>127.0.0.1:3003<br/>REMOTE_USER consumer]
    end

    P -- "OIDC code + PKCE, refresh" --> KC
    P -- "open tab + tokens in URL fragment" --> R
    P -- "open tab + token in URL fragment" --> SH
    R -- "postMessage: token-request / token-response" --> P
    R -- "JWKS, userinfo (CORS)" --> KC
    SH -- "JWKS, userinfo (server to server)" --> KC
    C --> SH
    SH -- "proxy /crm/* + REMOTE_USER + HMAC" --> CB
```

### 2.2 Components

| Component | Dir | Port | Tech | Responsibility |
|---|---|---|---|---|
| Keycloak | (Docker) | 8080 | Keycloak 26.8.1, `start-dev`, H2 | Identity provider: login, MFA, tokens, JWKS, userinfo |
| Portal | `portal/` | 3000 | React 19 + Vite dev server | OIDC client; DUNS drop-down; role tiles; token refresh; token hand-off |
| Retail UI | `retail-ui/` | 3001 | React 19 + Vite dev server | Validates tokens in the browser; per-DUNS product catalog |
| CRM gateway ("shim") | `crm-shim/` | 3002 | Node `http` server + React build | Server-side token validation; session; DUNS picker; reverse proxy that injects `REMOTE_USER` |
| CRM backend + UI | `crm-app/` | 3003 (127.0.0.1 only) | Node `http` server + React build | Serves the CRM UI and APIs; identifies the caller only from a signed `REMOTE_USER` |

The portal, Retail UI and the gateway are separate **origins** (different ports), which is why hand-off uses the
URL fragment and `postMessage` rather than shared storage. The CRM UI and gateway share the origin
`localhost:3002` because the gateway reverse-proxies the CRM under `/crm/`.

### 2.3 Trust boundaries

```mermaid
flowchart TB
    A[Untrusted: any browser, any URL typed by a user]
    B[Keycloak: root of trust for identity<br/>RS256 signing keys via JWKS]
    C[Gateway: trusted to authenticate the user<br/>and to speak for the user to the CRM]
    D[CRM backend: trusts ONLY requests carrying a valid<br/>HMAC signature produced by the gateway]
    A --> B
    A --> C
    C --> D
    A -. "blocked: no signature" .-> D
```

---

## 3. Identity model (Keycloak)

All of this is created by `scripts/setup-keycloak.sh` (idempotent).

### 3.1 Realm

`demo` (display name "Demo Realm"). Issuer: `http://localhost:8080/realms/demo`.
Token lifetimes are Keycloak defaults: access token 300 s, SSO session idle 1800 s, SSO session max 36000 s.

### 3.2 Clients

| Client ID | Type | Purpose |
|---|---|---|
| `demo-ui` | Public, standard flow only, PKCE `S256` required, direct-grant disabled. Redirect URI `http://localhost:3000/*`. Web origins `http://localhost:3000`, `http://localhost:3001`. Post-logout redirect `http://localhost:3000/*` | The portal's OIDC client. All tokens in the system are issued to it. |
| `retail-ui` | Bearer-only | Exists only to be an **audience**: lets Retail UI check that an access token was meant for it. |
| `crm-ui` | Bearer-only | Same, for the CRM gateway. |

Why bearer-only audience clients? The portal is the only interactive client. Child apps receive the portal's token,
so the way to bind a token to a consumer is the `aud` claim: an *Audience* mapper on `demo-ui` adds `retail-ui` and
`crm-ui` to the access token's `aud`.

### 3.3 Users, roles, attributes

| Item | Value |
|---|---|
| Test user | `testuser` / `password` |
| Realm role | `portal-admin` (assigned to the test user; surfaced as the `roles` claim) |
| User attribute `department` | `Engineering` |
| User attribute `employee_id` | `E-1001` |
| User attribute `duns_roles` (multivalued, max 512 chars) | `111111111:USA,RETAILUI,CRM` and `222222222:USA,RETAILUI,CRM,DAM_OP,CRR` |

The three attributes are declared in the realm's **user profile** (Keycloak 26 drops undeclared attributes).

### 3.4 Claims (protocol mappers on `demo-ui`)

| Mapper | Type | Claim | In ID token | In access token |
|---|---|---|---|---|
| `department` | User attribute | `department` (string) | yes | yes |
| `employee_id` | User attribute | `employee_id` (string) | yes | yes |
| `duns_roles` | User attribute, multivalued | `duns_roles` (string array) | yes | yes |
| `portal-roles` | Realm role | `roles` (string array) | yes | yes |
| `retail-ui-audience` | Audience | adds `retail-ui` to `aud` | no | yes |
| `crm-ui-audience` | Audience | adds `crm-ui` to `aud` | no | yes |

**`duns_roles` format.** Input format, one array element per DUNS:

```
DUNS : {ROLES}
111111111 : {USA,RETAILUI,CMM}      stored as   "111111111:USA,RETAILUI,CRM"
222222222 : {USA,DAM_OP,CRR}        stored as   "222222222:USA,RETAILUI,CRM,DAM_OP,CRR"
```

Parser (identical in portal, Retail UI and gateway): for every string drop `{` `}`, split on the first `:`, split the
right side on `,`, trim, drop empties, giving `{ "111111111": ["USA","RETAILUI","CRM"], ... }`.

A decoded access token (abridged):

```json
{
  "iss": "http://localhost:8080/realms/demo",
  "aud": ["crm-ui", "retail-ui", "account"],
  "azp": "demo-ui",
  "sub": "c8f81d16-...",
  "preferred_username": "testuser",
  "name": "Test User",
  "email": "testuser@example.com",
  "department": "Engineering",
  "employee_id": "E-1001",
  "roles": ["portal-admin", "default-roles-demo", "offline_access", "uma_authorization"],
  "duns_roles": ["111111111:USA,RETAILUI,CRM", "222222222:USA,RETAILUI,CRM,DAM_OP,CRR"],
  "exp": 1793900000
}
```

### 3.5 MFA

The built-in `browser` flow contains a conditional OTP sub-flow that prompts any user who has an OTP credential.
`CONFIGURE_TOTP` is enabled **and** a default required action, so a new user must enrol an authenticator app
(QR code) at their first login. Login order afterwards: username, password, 6-digit TOTP code.

---

## 4. Flow A: Portal login

OIDC **Authorization Code flow with PKCE (S256)**, implemented without a library in `portal/src/auth.js`.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant P as Portal (3000)
    participant K as Keycloak (8080)

    U->>P: GET /
    P->>P: no valid tokens in sessionStorage
    P->>P: generate code_verifier, state, nonce<br/>code_challenge = BASE64URL(SHA-256(verifier))
    P->>P: sessionStorage.pkce = {verifier, state, nonce}
    P->>K: 302 /protocol/openid-connect/auth<br/>client_id=demo-ui, response_type=code,<br/>scope=openid profile email, redirect_uri=http://localhost:3000/,<br/>state, nonce, code_challenge, code_challenge_method=S256
    K->>U: login page (username + password)
    U->>K: credentials
    K->>U: OTP page (enrol authenticator on first login)
    U->>K: 6-digit TOTP
    K->>P: 302 http://localhost:3000/?code=...&state=...
    P->>P: state == saved state ?  (else abort "State mismatch")
    P->>K: POST /protocol/openid-connect/token<br/>grant_type=authorization_code, code, redirect_uri,<br/>client_id=demo-ui, code_verifier
    K->>K: SHA-256(code_verifier) == stored code_challenge ?
    K-->>P: access_token, id_token, refresh_token
    P->>P: id_token.nonce == saved nonce ?  (else abort "Nonce mismatch")
    P->>P: sessionStorage.tokens = {...}, strip ?code&state from URL
    P->>U: render portal
```

Properties:

- **PKCE** prevents authorization-code interception: the code is useless without the one-time `code_verifier` that never
  left the browser.
- **state** binds the response to the request (CSRF). **nonce** binds the ID token to the request (replay).
- Tokens are held in `sessionStorage` (per tab, cleared on tab close). There is no client secret (public client).
- The portal does **not** verify the portal's own tokens' signature: they come straight from Keycloak's token endpoint
  over a direct channel, which is the OIDC-sanctioned trust model for the code flow.
- Logout: `GET /protocol/openid-connect/logout?id_token_hint=...&post_logout_redirect_uri=http://localhost:3000/`.

---

## 5. Flow B: Token refresh

Access tokens live 5 minutes. The portal keeps the user signed in without a redirect.

### 5.1 Portal

```mermaid
sequenceDiagram
    participant P as Portal
    participant K as Keycloak
    Note over P: on every new token set: timer = max(exp - now - 60s, 5s)
    P->>K: POST /token  grant_type=refresh_token, client_id=demo-ui, refresh_token
    alt session alive
        K-->>P: new access_token, id_token, refresh_token
        P->>P: store, re-arm timer
    else SSO session ended / refresh token invalid
        K-->>P: 400 invalid_grant
        P->>K: full login redirect (Flow A)
    end
```

- Concurrent refreshes are de-duplicated (single in-flight promise).
- If the tab was asleep, a `visibilitychange` handler refreshes immediately when less than 60 s remain.
- On page load with an expired access token the stored refresh token is tried before falling back to login.
- Keycloak SSO idle timeout is 30 min and max is 10 h; refreshing every ~4 min keeps an open tab alive until the 10 h cap.

### 5.2 Retail UI (token lease over `postMessage`)

Retail UI never receives the refresh token. The portal tab that opened it holds the refresh token and answers requests.

```mermaid
sequenceDiagram
    participant R as Retail UI (3001)
    participant P as Portal tab (3000, window.opener)
    participant K as Keycloak
    Note over R: 30 s before access token exp
    R->>P: postMessage({type:"token-request"}, "http://localhost:3000")
    P->>P: accept only if event.origin == "http://localhost:3001"
    opt token has < 90 s left
        P->>K: refresh (Flow B 5.1)
        K-->>P: fresh tokens
    end
    P->>R: postMessage({type:"token-response", access_token, id_token}, event.origin)
    R->>R: accept only if event.origin == "http://localhost:3000"
    R->>R: full re-validation (Flow D steps), replace tokens, keep selected DUNS
```

If the portal tab is closed, or does not answer, Retail UI ends its session at token expiry (+2 s) with
"Session expired, relaunch from the portal". The portal opens Retail UI without `noopener` precisely so that
`window.opener` exists.

The CRM gateway does not use this channel; it has its own server-side session (section 8).

---

## 6. Flow C: DUNS selection and tiles

```mermaid
flowchart TD
    T[tokens.id_token] --> D[decode claims]
    D --> M["parseDunsRoles(claims.duns_roles)<br/>{ 111111111: [USA,RETAILUI,CRM],<br/>222222222: [USA,RETAILUI,CRM,DAM_OP,CRR] }"]
    M --> L["DUNS list = Object.keys<br/>(drop-down, first = default)"]
    L --> S[selected DUNS]
    S --> F["roles = map[selected] minus NON_TILE_ROLES (USA)"]
    F --> TILES["one tile per role<br/>(portal/src/tiles.js catalog)"]
    TILES -->|click on a tile with launchUrl| LAUNCH[launch child app]
```

- Changing the drop-down re-renders the tiles with no reload.
- `USA` is in `NON_TILE_ROLES`: it grants access but renders no tile. Roles unknown to the catalog still render a
  generic tile.
- Tiles with a `launchUrl` (`RETAILUI` -> `http://localhost:3001/`, `CRM` -> `http://localhost:3002/`) open a **new tab**.
- Hand-off for Retail UI: `http://localhost:3001/#access_token=...&id_token=...&duns=<selected>`.
  Hand-off for CRM: `http://localhost:3002/#access_token=...&id_token=...&duns=<selected>` (the gateway ignores
  `id_token` and `duns`; it shows all DUNS).
- A URL **fragment** is used because browsers never send it to any server, so tokens are not written to server logs or
  `Referer` headers. Receivers erase it from the address bar immediately (`history.replaceState`).

---

## 7. Flow D: Launching Retail UI

Retail UI validates the tokens **in the browser** (`retail-ui/src/validate.js`) using the Web Crypto API.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant P as Portal (3000)
    participant R as Retail UI (3001)
    participant K as Keycloak

    U->>P: click RETAILUI tile
    P->>R: window.open(3001/#access_token&id_token&duns)
    R->>R: read fragment -> sessionStorage.retail_tokens, replaceState (URL cleaned)
    alt no tokens
        R->>U: "Launch from the portal" page
    else tokens present
        R->>K: GET /protocol/openid-connect/certs  (JWKS, cached)
        K-->>R: signing keys
        R->>R: verify access_token (audience = retail-ui)
        R->>R: verify id_token (audience = demo-ui)
        R->>R: access.sub == id.sub
        R->>K: GET /protocol/openid-connect/userinfo  (Bearer access_token)
        K-->>R: 200 (session live) / 401
        R->>R: DUNS list = duns_roles entries containing RETAILUI
        R->>U: render catalog for selected DUNS
    end
```

### 7.1 Validation checklist (applied to both tokens)

| # | Check | Failure message |
|---|---|---|
| 1 | Three dot-separated parts | `Malformed token` |
| 2 | Header `alg == RS256` (rejects `none`/HS256 downgrades) | `Unexpected algorithm` |
| 3 | `kid` found in Keycloak JWKS with `use == sig` | `Token signed by an unknown key` |
| 4 | RSASSA-PKCS1-v1_5 / SHA-256 signature verifies over `header.payload` | `Invalid token signature` |
| 5 | `iss == http://localhost:8080/realms/demo` | `Unexpected token issuer` |
| 6 | `exp` in the future | `Token has expired` |
| 7 | `aud` contains `retail-ui` (access token) / `demo-ui` (ID token) | `Token not issued for ...` |
| 8 | `access.sub == id.sub` | `Token subject mismatch` |
| 9 | `userinfo` returns 200 for the access token (user session still active, not logged out) | `Keycloak session is no longer active` |

Any failure shows an "Access denied" page with the reason and a link back to the portal; stored tokens are discarded.

### 7.2 Authorization inside Retail UI

- Authorization data comes from the **validated access token's** `duns_roles`.
- The DUNS drop-down lists every DUNS in the token; those lacking `RETAILUI` are disabled and labelled "(no access)".
  If the user has no DUNS with `RETAILUI`, a "No access" page is shown.
- The default DUNS is the one the portal had selected (the `duns` hint), else the first allowed. The user may change it
  at any time; the catalog (`retail-ui/src/catalog.js`) switches between Toy Shop and Flower Shop.

### 7.3 Limits of browser-side validation

Anyone can edit browser JavaScript, so this proves integration rather than enforcing security. A real deployment
validates tokens in a backend (compare the CRM gateway, Flow E) and serves data from it.

---

## 8. Flow E: Launching CRM

The CRM is modelled on a legacy application that trusts a web-server-injected `REMOTE_USER` (as with Apache/SiteMinder
style SSO). The **gateway** converts an OIDC token into that header.

### 8.1 End-to-end sequence

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant P as Portal (3000)
    participant S as Gateway (3002)
    participant K as Keycloak
    participant B as CRM backend (3003)

    U->>P: click CRM tile
    P->>S: window.open(3002/#access_token=...)
    S-->>U: static gateway UI (React)
    U->>U: UI reads fragment, strips it from the address bar
    U->>S: POST /shim/api/session {access_token}
    S->>K: GET JWKS (cached, refetch on unknown kid)
    S->>S: verify signature RS256, iss, exp, aud contains crm-ui
    S->>K: GET /userinfo (Bearer token), server to server
    K-->>S: 200
    S->>S: validate preferred_username matches ^[\w.@+-]+$
    S->>S: new server-side session {user, name, duns map, selected=null}
    S-->>U: 200 {user, name, duns:[{duns,roles,crm}]} + Set-Cookie shim_sid (HttpOnly, SameSite=Lax)
    U->>U: show a tile per DUNS (CRM role missing -> disabled)
    U->>S: POST /shim/api/select {duns}
    S->>S: roles(duns) contains CRM ? else 403
    S->>S: session.selected = duns, issue launch ticket (60 s, single use, bound to session + duns)
    S-->>U: {url: "/crm/?launch={ticket}"}
    U->>S: GET /crm/?launch={ticket}  (cookie)
    S->>S: session valid, ticket valid and consumed
    S->>B: GET /crm/<br/>REMOTE_USER: 222222222$testuser<br/>X-Shim-Timestamp, X-Shim-Signature
    B->>B: verify HMAC + freshness, parse REMOTE_USER
    B-->>S: CRM index.html
    S-->>U: CRM UI
    U->>S: GET /crm/api/me, /crm/api/customers  (X-CRM-Client: crm-ui)
    S->>B: same, REMOTE_USER re-injected and re-signed on every request
    B-->>U: data for the DUNS taken from REMOTE_USER
```

### 8.2 Server-side token validation (gateway)

Implemented in `crm-shim/server.js` (`validateAccessToken`) with Node's `crypto` only (no JWT library).

| # | Step |
|---|---|
| 1 | Split into 3 parts; reject if malformed |
| 2 | `alg` must be `RS256` |
| 3 | Find JWKS key by `kid` (`use == sig`); on a miss re-fetch the JWKS once (handles key rotation) |
| 4 | `crypto.verify('RSA-SHA256', "<h>.<p>", publicKeyFromJwk, signature)` |
| 5 | `iss == http://localhost:8080/realms/demo` |
| 6 | `exp` not passed |
| 7 | `aud` contains `crm-ui` |
| 8 | `GET /userinfo` with the bearer token must be 200 (revoked/ended sessions are rejected) |
| 9 | `preferred_username` must match `^[\w.@+-]+$` (prevents header injection; the name goes into an HTTP header) |

Validation happens **once per launch** (on `POST /shim/api/session`); afterwards the server-side session stands in for the
token.

### 8.3 Gateway session

| Property | Value |
|---|---|
| Storage | In-memory `Map` (lost on restart) |
| Cookie | `shim_sid`, 24 random bytes base64url, `HttpOnly`, `SameSite=Lax`, `Path=/` (add `Secure` behind TLS) |
| Content | `user`, `name`, parsed `duns -> roles`, `selected` DUNS, timestamps |
| Idle timeout | 30 min (sliding) |
| Absolute timeout | 8 h |
| Created by | Only `POST /shim/api/session` with a valid token (replaces any previous session) |
| CSRF | State-changing calls reject a mismatching `Origin` header; cookie is `SameSite=Lax` |

### 8.4 The DUNS picker ("shim layer")

- The picker UI never reads the DUNS list from a cookie. The list is returned **in the response to a token (or ticket)
  submission** and kept only in React memory. Reloading the picker without a fragment therefore shows
  "Launch from the portal".
- Tiles are drawn for every DUNS in the token; a DUNS whose roles lack `CRM` is disabled, and the server enforces it too
  (`403 No CRM access for this DUNS`).

### 8.5 Launch ticket (entering the CRM)

Opening `/crm` pages requires `?launch=<ticket>`:

| Property | Value |
|---|---|
| Created | by `POST /shim/api/select` |
| Lifetime | 60 s |
| Use | exactly once (deleted on first look-up) |
| Bound to | the session id **and** the selected DUNS |

Request classification inside `/crm/*`:

| Path | Requirement |
|---|---|
| `/crm/api/*` | live session with a selected DUNS **and** header `X-CRM-Client: crm-ui` (so typing an API URL in the address bar fails) |
| `/crm/assets/*` | live session with a selected DUNS (static JS/CSS) |
| everything else (page loads: `/crm`, `/crm/`, `/crm/anything`) | live session **and** a valid single-use launch ticket. Otherwise 401 "Launch from the portal" page |

Consequence: reloading the CRM page, or bookmarking it, shows the launch page. Navigation *inside* the CRM (left nav) is
client-side and unaffected.

### 8.6 Switching DUNS from inside the CRM

```mermaid
sequenceDiagram
    actor U as User
    participant S as Gateway
    U->>S: click "Switch DUNS" -> GET /shim/switch  (same-origin navigation, cookie)
    S->>S: require session AND Sec-Fetch-Site == same-origin
    S->>S: session.selected = null  (CRM locked until a new pick)
    S->>S: ticket = random, store {sid, exp: now+60s}
    S-->>U: 302 /#ticket={ticket}
    U->>S: POST /shim/api/ticket {ticket}
    S->>S: ticket exists, unexpired, same session, single use
    S-->>U: DUNS list  -> picker tiles
```

Typing `/shim/switch` in the address bar sends `Sec-Fetch-Site: none` and gets 403. Opening `/` without a fragment shows
the launch page.

---

## 9. `REMOTE_USER` in depth

### 9.1 Purpose

The CRM does not understand OIDC. It identifies the caller from one HTTP request header:

```
REMOTE_USER: <duns>$<username>        e.g.   REMOTE_USER: 222222222$testuser
```

- `<duns>`: the DUNS the user picked on the gateway (9 digits).
- `$`: delimiter. The CRM splits on the **first** `$`.
- `<username>`: Keycloak `preferred_username` (restricted to `[\w.@+-]+`, so it cannot contain `$` or control characters).

The header carries *identity plus tenant* in one value, so every CRM request is automatically scoped to one company.

### 9.2 Who sets it, who may not

```mermaid
flowchart LR
    BR[Browser request<br/>may contain a forged REMOTE_USER] --> SH
    subgraph SH [Gateway proxy]
      direction TB
      X1[1. copy request headers EXCEPT<br/>cookie, authorization, remote_user,<br/>x-shim-timestamp, x-shim-signature,<br/>x-forwarded-for / host / proto, connection]
      X2[2. set REMOTE_USER = selected-DUNS $ session-user]
      X3[3. set X-Shim-Timestamp = now ms]
      X4["4. set X-Shim-Signature = HMAC-SHA256 of REMOTE_USER + pipe + timestamp"]
      X5[5. set X-Forwarded-For / Host / Proto]
      X1 --> X2 --> X3 --> X4 --> X5
    end
    SH --> CB[CRM backend verifies signature<br/>then trusts REMOTE_USER]
```

The browser-supplied value is **always discarded**; the value that reaches the CRM comes from the server-side session,
which came from a validated token.

### 9.3 Signature (why a header alone is not trusted)

Anything that can reach port 3003 could otherwise just type a `REMOTE_USER`. The backend therefore also requires:

```
X-Shim-Timestamp: <unix epoch milliseconds>
X-Shim-Signature: hex( HMAC-SHA256( SHIM_SECRET, REMOTE_USER + "|" + X-Shim-Timestamp ) )
```

Backend verification (`crm-app/server.js`, `trusted()`):

1. All three headers present.
2. `|now - timestamp| <= 30 s` (limits replay of a captured request).
3. Recompute the HMAC with the shared key; compare with `crypto.timingSafeEqual` (constant time).

If any check fails the response is **403**: an HTML "Launch from the portal" page for browsers, JSON otherwise.
The backend also binds to `127.0.0.1`, so it is unreachable from the network.

`SHIM_SECRET` is generated per start by `scripts/start.sh` (`openssl rand -hex 32`) and passed to both processes through the
environment. Both fall back to a fixed development constant only if the variable is unset.

### 9.4 Parsing and use in the CRM

After the signature passes, the backend:

1. Splits `REMOTE_USER` at the first `$` into `duns` and `user`; rejects (403) if there is no `$`, `duns` is not exactly 9
   digits, or `user` is empty.
2. Uses `duns` to select the data set: `/crm/api/customers` returns the Toy Shop or Flower Shop customers.
3. Exposes both through `GET /crm/api/me`, shown in the CRM top bar (`DUNS 222222222`, username).

No token, cookie, or other credential reaches the CRM backend.

### 9.5 Header naming caveat

`REMOTE_USER` contains an underscore. Node.js preserves it. Many HTTP servers and proxies (nginx by default, Apache
mod_proxy variants) **drop headers with underscores** unless configured (`underscores_in_headers on` in nginx). If this
design is ported behind such a proxy, either enable underscore headers or agree an alternative name (e.g. `Remote-User`).

### 9.6 Snoop page

CRM left nav -> **Snoop** calls `GET /crm/api/snoop`, which returns the request line and `req.rawHeaders`, i.e. the headers
exactly as received (original case and order), including `REMOTE_USER` (highlighted), `X-Shim-Timestamp`,
`X-Shim-Signature`, `X-Forwarded-*`, `Host`, `User-Agent`, `Accept`, and `X-CRM-Client`. Browser cookies are not present
because the gateway strips them. The HMAC key is never shown (only the derived signature, which is only valid for
30 seconds for that exact value).

Example (abridged):

```
GET /crm/api/snoop  HTTP/1.1
Host:               localhost:3002
REMOTE_USER:        222222222$testuser
X-Shim-Timestamp:   1793900123456
X-Shim-Signature:   9f2c...e1
X-Forwarded-For:    ::1
X-Forwarded-Host:   localhost:3002
X-Forwarded-Proto:  http
X-CRM-Client:       crm-ui
```

---

## 10. Direct-access and bookmark behaviour

| Scenario | Result | Enforced by |
|---|---|---|
| Open Retail UI `:3001` directly | "Launch from the portal" page | Retail UI (no tokens) |
| Open Retail UI with a tampered/expired/other-audience token | "Access denied" + reason | Retail UI validation |
| Open gateway `:3002/` directly or after reload | "Launch from the portal" page | Gateway UI (no fragment) |
| Open gateway with a bogus token | "Access denied: ..." | `POST /shim/api/session` -> 401 |
| `GET /shim/api/session` (old list endpoint) | 404 | removed |
| Type `/shim/switch` in the address bar | 403 launch page | `Sec-Fetch-Site` check |
| Type/bookmark `/crm`, `/crm/`, `/crm/x` with a live session | 401 launch page | missing/used launch ticket |
| Type `/crm/api/me` in the address bar | 403 | missing `X-CRM-Client` |
| Reuse or forge `?launch=` | 401 | single-use, session-bound |
| `/crm/*` with no session | 401 | session check |
| Call CRM backend `:3003` directly with no headers | 403 | HMAC check |
| Call `:3003` with forged `REMOTE_USER` (any signature) | 403 | HMAC check |
| Replay a captured signed request after 30 s | 403 | timestamp window |
| Browser sends its own `REMOTE_USER` through the gateway | ignored and replaced | proxy header stripping |
| Pick a DUNS without the `CRM` role | 403 | `POST /shim/api/select` |

`scripts/smoke-test.sh` automates the non-login rows.

---

## 11. Reference tables

### 11.1 Gateway endpoints (`crm-shim/server.js`, port 3002)

| Method + path | Auth | Purpose |
|---|---|---|
| `POST /shim/api/session` | body `access_token` (validated) | Create session, return `{user, name, duns[]}`, set `shim_sid` |
| `DELETE /shim/api/session` | cookie | Destroy session |
| `POST /shim/api/select` | cookie | Choose DUNS; returns `/crm/?launch=<ticket>` |
| `POST /shim/api/ticket` | cookie + one-time ticket | Redeem a "Switch DUNS" ticket for the DUNS list |
| `GET /shim/switch` | cookie + `Sec-Fetch-Site: same-origin` | Clear selection, 302 to `/#ticket=...` |
| `/crm/*` | cookie (+ ticket / `X-CRM-Client` per section 8.5) | Reverse proxy to the CRM backend with `REMOTE_USER` |
| anything else | none | Static gateway UI (`crm-shim/dist`, SPA fallback) |

### 11.2 CRM backend endpoints (`crm-app/server.js`, 127.0.0.1:3003)

All require a valid HMAC-signed `REMOTE_USER`.

| Path | Returns |
|---|---|
| `/crm/api/me` | `{remoteUser, duns, user, company}` |
| `/crm/api/customers` | customers of the DUNS in `REMOTE_USER` |
| `/crm/api/snoop` | request line + raw headers |
| `/crm/**` | CRM React build (SPA fallback) |

### 11.3 Where the constants live

| Setting | Value | File |
|---|---|---|
| Issuer, client id, redirect URI | `http://localhost:8080/realms/demo`, `demo-ui` | `portal/src/auth.js` |
| Retail issuer / audiences / portal URL | | `retail-ui/src/validate.js` |
| Gateway issuer / audience `crm-ui`, ports, session timeouts | | `crm-shim/server.js` |
| Role to tile catalog, launch URLs, non-tile roles | | `portal/src/tiles.js` |
| Product catalogs | | `retail-ui/src/catalog.js` |
| CRM customer data | | `crm-app/server.js` |
| DUNS role data, realm, clients, mappers | | `scripts/setup-keycloak.sh` |

### 11.4 Timers at a glance

| What | Value |
|---|---|
| Access token | 5 min |
| Portal proactive refresh | 60 s before expiry (min 5 s) |
| Retail UI token request | 30 s before expiry; hard stop at expiry + 2 s |
| Keycloak SSO idle / max | 30 min / 10 h |
| Gateway session idle / max | 30 min / 8 h |
| Launch / switch ticket | 60 s, single use |
| `REMOTE_USER` signature freshness | 30 s |

---

## 12. Security analysis

### 12.1 Threats considered

| Threat | Mitigation |
|---|---|
| Authorization-code interception | PKCE S256 |
| CSRF on the login redirect / ID-token replay | `state`, `nonce` |
| Token leakage to servers/logs/Referer | Tokens travel in URL fragments, removed from the address bar immediately; never in query strings |
| Forged or tampered token | RS256 signature check against Keycloak JWKS; `alg` pinned |
| Token meant for another service | Audience check (`retail-ui`, `crm-ui`) |
| Token of a logged-out user | `userinfo` call at launch |
| Forged `REMOTE_USER` from a browser | Gateway strips it; CRM trusts only HMAC-signed values |
| Direct calls to the CRM backend | Bound to 127.0.0.1; HMAC required; 30 s replay window |
| Session theft from JS | `shim_sid` is `HttpOnly`; CRM backend never sees it |
| Header injection via username | Username whitelist `[\w.@+-]+` |
| Open redirect / URL guessing of CRM | Single-use, session-bound launch tickets |
| Refresh-token exposure to child apps | Only the portal holds it; Retail UI uses `postMessage` with strict origin checks |

### 12.2 Known limitations (intentional, for a demo)

1. Plain HTTP everywhere; Keycloak in `start-dev` with the embedded H2 database and default `admin/admin`.
2. Tokens in `sessionStorage` are readable by any script on the same origin (XSS impact).
3. Retail UI validates in the browser (can be bypassed by editing JS); data is hard-coded.
4. Gateway sessions are in memory, single instance, and are not re-checked against Keycloak after launch (ending the
   Keycloak session does not end an existing gateway session until it times out).
5. Refresh tokens are not rotated (Keycloak default).
6. The Retail UI refresh channel needs the portal tab to stay open.
7. The same `demo-ui` token is used by all consumers; separation is by audience only, not by per-app clients.
8. Hard-coded origins (`localhost:3000-3003`, `8080`) in source; changing hosts requires code and Keycloak edits.
9. No automated test suite beyond `scripts/smoke-test.sh`.

### 12.3 What to change for production

- TLS everywhere; `Secure` cookies; `https` issuer; real hostnames; Keycloak in production mode with PostgreSQL, a
  proper admin, and tightened redirect URIs/web origins.
- Validate tokens in a backend or API gateway for every app (use a maintained JWT library, cache JWKS with rotation).
- Move secrets (`SHIM_SECRET`) to a secret store; rotate; consider mutual TLS between gateway and CRM instead of or in
  addition to the HMAC.
- Shared session store (Redis) for the gateway; periodic back-channel logout or introspection to end sessions with the
  Keycloak session.
- Refresh-token rotation and reuse detection; consider the BFF pattern so browsers never hold tokens.
- Replace hard-coded data with real services; add rate limiting, audit logging, security headers (CSP,
  `X-Content-Type-Options`, `frame-ancestors`), and automated tests.
