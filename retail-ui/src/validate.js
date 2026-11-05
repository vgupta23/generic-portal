// Validates tokens handed over by the portal against Keycloak:
//  1. RS256 signature, using the realm JWKS
//  2. issuer, expiry, audience
//  3. live session check via the userinfo endpoint
export const CFG = {
  issuer: 'http://localhost:8080/realms/demo',
  portalUrl: 'http://localhost:3000/',
  portalOrigin: 'http://localhost:3000',
  clientAudience: 'retail-ui', // must appear in the access token `aud`
  idTokenAudience: 'demo-ui',  // the portal's client id (ID token `aud`)
};

const b64uToBytes = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
const parts = (jwt) => {
  const [h, p, sig] = jwt.split('.');
  if (!sig) throw new Error('Malformed token');
  return {
    header: JSON.parse(new TextDecoder().decode(b64uToBytes(h))),
    payload: JSON.parse(new TextDecoder().decode(b64uToBytes(p))),
    signed: new TextEncoder().encode(`${h}.${p}`),
    sig: b64uToBytes(sig),
  };
};

let jwksCache;
async function getKey(kid) {
  if (!jwksCache) {
    const res = await fetch(`${CFG.issuer}/protocol/openid-connect/certs`);
    if (!res.ok) throw new Error('Could not load signing keys from Keycloak');
    jwksCache = (await res.json()).keys;
  }
  const jwk = jwksCache.find((k) => k.kid === kid && k.use === 'sig');
  if (!jwk) throw new Error('Token signed by an unknown key');
  return crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
}

async function verify(jwt, { audience }) {
  const { header, payload, signed, sig } = parts(jwt);
  if (header.alg !== 'RS256') throw new Error(`Unexpected algorithm ${header.alg}`);
  const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', await getKey(header.kid), sig, signed);
  if (!ok) throw new Error('Invalid token signature');
  if (payload.iss !== CFG.issuer) throw new Error('Unexpected token issuer');
  if (payload.exp * 1000 < Date.now()) throw new Error('Token has expired');
  const aud = [].concat(payload.aud || []);
  if (!aud.includes(audience)) throw new Error(`Token not issued for ${audience}`);
  return payload;
}

export async function validateTokens({ access_token, id_token }) {
  const access = await verify(access_token, { audience: CFG.clientAudience });
  const id = await verify(id_token, { audience: CFG.idTokenAudience });
  if (access.sub !== id.sub) throw new Error('Token subject mismatch');
  const res = await fetch(`${CFG.issuer}/protocol/openid-connect/userinfo`, {
    headers: { Authorization: `Bearer ${access_token}` },
  });
  if (!res.ok) throw new Error('Keycloak session is no longer active');
  return { access, id };
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
