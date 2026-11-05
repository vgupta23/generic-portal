// Minimal OIDC Authorization Code + PKCE client for Keycloak.
export const CFG = {
  issuer: 'http://localhost:8080/realms/demo',
  clientId: 'demo-ui',
  redirectUri: window.location.origin + '/',
};

const b64url = (buf) =>
  btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const rand = () => b64url(crypto.getRandomValues(new Uint8Array(32)));

export const decode = (jwt) => {
  const b = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(decodeURIComponent(escape(atob(b))));
};

export async function login() {
  const verifier = rand(), state = rand(), nonce = rand();
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  sessionStorage.setItem('pkce', JSON.stringify({ verifier, state, nonce }));
  const p = new URLSearchParams({
    client_id: CFG.clientId, redirect_uri: CFG.redirectUri, response_type: 'code',
    scope: 'openid profile email', state, nonce,
    code_challenge: challenge, code_challenge_method: 'S256',
  });
  window.location.href = `${CFG.issuer}/protocol/openid-connect/auth?${p}`;
}

async function exchange(code, returnedState) {
  const saved = JSON.parse(sessionStorage.getItem('pkce') || '{}');
  sessionStorage.removeItem('pkce');
  if (!saved.state || saved.state !== returnedState) throw new Error('State mismatch');
  const res = await fetch(`${CFG.issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code', client_id: CFG.clientId, code,
      redirect_uri: CFG.redirectUri, code_verifier: saved.verifier,
    }),
  });
  if (!res.ok) throw new Error('Token request failed: ' + (await res.text()));
  const tokens = await res.json();
  if (decode(tokens.id_token).nonce !== saved.nonce) throw new Error('Nonce mismatch');
  sessionStorage.setItem('tokens', JSON.stringify(tokens));
  return tokens;
}

// Exchanges the refresh token for a fresh token set.
export async function refresh(tokens) {
  const res = await fetch(`${CFG.issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token', client_id: CFG.clientId, refresh_token: tokens.refresh_token,
    }),
  });
  if (!res.ok) throw new Error('Refresh failed: ' + (await res.text()));
  const fresh = await res.json();
  sessionStorage.setItem('tokens', JSON.stringify(fresh));
  return fresh;
}

// Returns tokens, or redirects to Keycloak and returns null.
export async function authenticate() {
  const q = new URLSearchParams(window.location.search);
  if (q.get('error')) throw new Error(q.get('error_description') || q.get('error'));
  if (q.has('code')) {
    const tokens = await exchange(q.get('code'), q.get('state'));
    window.history.replaceState({}, '', CFG.redirectUri);
    return tokens;
  }
  const stored = JSON.parse(sessionStorage.getItem('tokens') || 'null');
  if (stored && decode(stored.access_token).exp * 1000 > Date.now()) return stored;
  if (stored?.refresh_token) {
    try { return await refresh(stored); } catch { /* session ended: fall through to login */ }
  }
  sessionStorage.removeItem('tokens');
  await login();
  return null;
}

export function logout(tokens) {
  sessionStorage.removeItem('tokens');
  const p = new URLSearchParams({ id_token_hint: tokens.id_token, post_logout_redirect_uri: CFG.redirectUri });
  window.location.href = `${CFG.issuer}/protocol/openid-connect/logout?${p}`;
}

// Parses the duns_roles claim: ["111111111:USA,RETAILUI,CRM", ...] -> { "111111111": ["USA","RETAILUI","CRM"] }
export function parseDunsRoles(claim) {
  const list = Array.isArray(claim) ? claim : claim ? [claim] : [];
  const out = {};
  for (const entry of list) {
    const [duns, roles = ''] = entry.replace(/[{}]/g, '').split(':');
    out[duns.trim()] = roles.split(',').map((r) => r.trim()).filter(Boolean);
  }
  return out;
}
