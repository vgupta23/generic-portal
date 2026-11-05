// CRM shim / gateway.
//  - validates the portal's OIDC access token (signature, issuer, expiry, audience, live session)
//  - keeps a server-side session and lets the user choose a DUNS
//  - reverse-proxies /crm/* to the CRM backend, injecting REMOTE_USER: <duns>$<username>
//    (user info reaches the CRM as an HTTP header, never as a token)
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = 3002;
const ORIGIN = `http://localhost:${PORT}`;
const CRM = { host: '127.0.0.1', port: 3003 };
const SECRET = process.env.SHIM_SECRET || 'dev-shim-secret';
const ISSUER = 'http://localhost:8080/realms/demo';
const AUDIENCE = 'crm-ui';
const PORTAL = 'http://localhost:3000/';
const IDLE_MS = 30 * 60 * 1000, MAX_MS = 8 * 60 * 60 * 1000;
const DIST = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };

// ---- token validation ------------------------------------------------------
let jwks = [];
async function loadJwks() {
  const res = await fetch(`${ISSUER}/protocol/openid-connect/certs`);
  if (!res.ok) throw new Error('Cannot load Keycloak signing keys');
  jwks = (await res.json()).keys;
}
const b64u = (s) => Buffer.from(s, 'base64url');

async function validateAccessToken(jwt) {
  const [h, p, sig] = String(jwt).split('.');
  if (!sig) throw new Error('Malformed token');
  const header = JSON.parse(b64u(h)), payload = JSON.parse(b64u(p));
  if (header.alg !== 'RS256') throw new Error('Unexpected algorithm');
  let jwk = jwks.find((k) => k.kid === header.kid && k.use === 'sig');
  if (!jwk) { await loadJwks(); jwk = jwks.find((k) => k.kid === header.kid && k.use === 'sig'); }
  if (!jwk) throw new Error('Unknown signing key');
  const ok = crypto.verify('RSA-SHA256', Buffer.from(`${h}.${p}`), crypto.createPublicKey({ key: jwk, format: 'jwk' }), b64u(sig));
  if (!ok) throw new Error('Invalid token signature');
  if (payload.iss !== ISSUER) throw new Error('Unexpected issuer');
  if (payload.exp * 1000 < Date.now()) throw new Error('Token has expired');
  if (![].concat(payload.aud || []).includes(AUDIENCE)) throw new Error(`Token not issued for ${AUDIENCE}`);
  const ui = await fetch(`${ISSUER}/protocol/openid-connect/userinfo`, { headers: { Authorization: `Bearer ${jwt}` } });
  if (!ui.ok) throw new Error('Keycloak session is no longer active');
  return payload;
}

function parseDunsRoles(claim) {
  const out = {};
  for (const entry of [].concat(claim || [])) {
    const [duns, roles = ''] = entry.replace(/[{}]/g, '').split(':');
    out[duns.trim()] = roles.split(',').map((r) => r.trim()).filter(Boolean);
  }
  return out;
}

// ---- sessions --------------------------------------------------------------
const sessions = new Map();
const launches = new Map(); // one-time CRM launch tickets: ticket -> { sid, duns, exp }
const tickets = new Map(); // one-time "switch DUNS" tickets: ticket -> { sid, exp }
const dunsList = (s) => Object.entries(s.duns).map(([d, roles]) => ({ duns: d, roles, crm: roles.includes('CRM') }));
const cookies = (req) => Object.fromEntries((req.headers.cookie || '').split(';').map((c) => c.trim().split(/=(.*)/s).slice(0, 2)).filter(([k]) => k));
function getSession(req) {
  const sid = cookies(req).shim_sid;
  const s = sid && sessions.get(sid);
  if (!s) return null;
  const now = Date.now();
  if (now - s.last > IDLE_MS || now - s.created > MAX_MS) { sessions.delete(sid); return null; }
  s.last = now;
  return s;
}

// ---- helpers ---------------------------------------------------------------
const json = (res, obj, status = 200, extra = {}) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...extra });
  res.end(JSON.stringify(obj));
};
const readJson = (req) => new Promise((resolve, reject) => {
  let body = '';
  req.on('data', (c) => { body += c; if (body.length > 100_000) { reject(new Error('Body too large')); req.destroy(); } });
  req.on('end', () => { try { resolve(JSON.parse(body || '{}')); } catch { reject(new Error('Invalid JSON')); } });
});
const launchOnly = () => `<!doctype html><meta charset="utf-8"><title>CRM</title>
<body style="font-family:system-ui;background:#f1f5f9"><div style="max-width:520px;margin:18vh auto;text-align:center;background:#fff;padding:2rem;border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.1)">
<h2>Launch from the portal</h2><p>CRM is only available when launched from the portal.</p><a href="${PORTAL}" style="color:#3730a3">Go to portal →</a></div>`;

function proxy(req, res, s, upstreamPath = req.url) {
  const skip = new Set(['cookie', 'authorization', 'remote_user', 'x-shim-timestamp', 'x-shim-signature', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'connection']);
  const headers = {};
  for (let i = 0; i < req.rawHeaders.length; i += 2) {
    if (!skip.has(req.rawHeaders[i].toLowerCase())) headers[req.rawHeaders[i]] = req.rawHeaders[i + 1];
  }
  // REMOTE_USER is signed (HMAC over value + timestamp) so the CRM can verify it came from this shim.
  const remote = `${s.selected}$${s.user}`, ts = String(Date.now());
  headers['REMOTE_USER'] = remote;
  headers['X-Shim-Timestamp'] = ts;
  headers['X-Shim-Signature'] = crypto.createHmac('sha256', SECRET).update(`${remote}|${ts}`).digest('hex');
  headers['X-Forwarded-For'] = req.socket.remoteAddress;
  headers['X-Forwarded-Host'] = req.headers.host;
  headers['X-Forwarded-Proto'] = 'http';
  const up = http.request({ ...CRM, method: req.method, path: upstreamPath, headers }, (ures) => {
    res.writeHead(ures.statusCode, ures.headers);
    ures.pipe(res);
  });
  up.on('error', () => json(res, { error: 'CRM backend unavailable' }, 502));
  req.pipe(up);
}

// ---- server ----------------------------------------------------------------
http.createServer(async (req, res) => {
  try {
    const { pathname } = new URL(req.url, ORIGIN);
    const accepts = req.headers.accept || '';

    if (pathname.startsWith('/shim/api/')) {
      if (req.method !== 'GET' && req.headers.origin && req.headers.origin !== ORIGIN) return json(res, { error: 'Bad origin' }, 403);
      const s = getSession(req);

      if (pathname === '/shim/api/session' && req.method === 'POST') {
        const { access_token } = await readJson(req);
        let payload;
        try { payload = await validateAccessToken(access_token); }
        catch (e) { return json(res, { error: e.message }, 401); }
        const user = payload.preferred_username;
        if (!/^[\w.@+-]+$/.test(user || '')) return json(res, { error: 'Unsupported username' }, 403);
        sessions.delete(cookies(req).shim_sid); // a new launch always replaces the old session
        const sid = crypto.randomBytes(24).toString('base64url');
        sessions.set(sid, { user, name: payload.name, duns: parseDunsRoles(payload.duns_roles), selected: null, created: Date.now(), last: Date.now() });
        const created = sessions.get(sid);
        return json(res, { ok: true, user: created.user, name: created.name, duns: dunsList(created) }, 200,
          { 'Set-Cookie': `shim_sid=${sid}; HttpOnly; SameSite=Lax; Path=/` });
      }
      // Redeem a one-time ticket (issued by /shim/switch from inside the CRM) for the DUNS list.
      if (pathname === '/shim/api/ticket' && req.method === 'POST') {
        const { ticket } = await readJson(req);
        const t = tickets.get(ticket);
        tickets.delete(ticket);
        const sid = cookies(req).shim_sid;
        if (!s || !t || t.sid !== sid || t.exp < Date.now()) return json(res, { error: 'Launch from the portal' }, 401);
        return json(res, { ok: true, user: s.user, name: s.name, duns: dunsList(s) });
      }
      if (pathname === '/shim/api/session' && req.method === 'DELETE') {
        const sid = cookies(req).shim_sid; sessions.delete(sid);
        return json(res, { ok: true }, 200, { 'Set-Cookie': 'shim_sid=; Max-Age=0; Path=/' });
      }
      if (pathname === '/shim/api/select' && req.method === 'POST') {
        if (!s) return json(res, { error: 'No session' }, 401);
        const { duns } = await readJson(req);
        if (!s.duns[duns]?.includes('CRM')) return json(res, { error: 'No CRM access for this DUNS' }, 403);
        s.selected = duns;
        const launch = crypto.randomBytes(18).toString('base64url');
        launches.set(launch, { sid: cookies(req).shim_sid, duns, exp: Date.now() + 60_000 });
        return json(res, { ok: true, url: `/crm/?launch=${launch}` });
      }
      return json(res, { error: 'Not found' }, 404);
    }

    // "Switch DUNS" link inside the CRM: only honoured as a same-origin navigation with a live session.
    if (pathname === '/shim/switch') {
      const s = getSession(req);
      if (!s || req.headers['sec-fetch-site'] !== 'same-origin') {
        res.writeHead(403, { 'Content-Type': 'text/html' });
        return res.end(launchOnly());
      }
      s.selected = null;
      const ticket = crypto.randomBytes(18).toString('base64url');
      tickets.set(ticket, { sid: cookies(req).shim_sid, exp: Date.now() + 60_000 });
      res.writeHead(302, { Location: `/#ticket=${ticket}` });
      return res.end();
    }

    if (pathname === '/crm' || pathname.startsWith('/crm/')) {
      const s = getSession(req);
      const refuse = (status, msg) => {
        if (accepts.includes('text/html')) { res.writeHead(status, { 'Content-Type': 'text/html' }); return res.end(launchOnly()); }
        return json(res, { error: msg }, status);
      };
      if (!s?.selected) return refuse(401, 'Launch from the portal');

      const isApi = pathname.startsWith('/crm/api/'), isAsset = pathname.startsWith('/crm/assets/');
      if (isApi) {
        // Only the CRM app's own fetch calls; typing an API URL in the address bar is refused.
        if (req.headers['x-crm-client'] !== 'crm-ui') return refuse(403, 'Launch from the portal');
        return proxy(req, res, s);
      }
      if (isAsset) return proxy(req, res, s);

      // Anything else is a page load of the CRM: it must carry a single-use launch ticket from the picker.
      const url = new URL(req.url, ORIGIN);
      const ticket = url.searchParams.get('launch');
      const l = launches.get(ticket);
      launches.delete(ticket);
      if (!l || l.sid !== cookies(req).shim_sid || l.duns !== s.selected || l.exp < Date.now()) {
        return refuse(401, 'Launch from the portal');
      }
      return proxy(req, res, s, '/crm/');
    }

    // Shim UI (static, SPA fallback)
    let file = path.resolve(DIST, '.' + decodeURIComponent(pathname));
    if (!file.startsWith(DIST + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(DIST, 'index.html');
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  } catch (e) {
    json(res, { error: e.message }, 400);
  }
}).listen(PORT, () => console.log(`CRM shim on ${ORIGIN}`));
