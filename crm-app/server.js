// CRM backend. Serves the CRM React build and a few JSON APIs under /crm/.
// It identifies the caller ONLY from the REMOTE_USER header ("<duns>$<username>"), and only trusts
// that header when it is signed by the shim (shared HMAC key, 30s freshness window).
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = 3003;
const SECRET = process.env.SHIM_SECRET || 'dev-shim-secret';
const DIST = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist');
const PORTAL = 'http://localhost:3000/';

const COMPANIES = {
  '111111111': { name: 'Toy Shop', customers: [
    { id: 1, name: 'Lily Parker', email: 'lily@example.com', tier: 'Gold', lastOrder: 'Teddy Bear' },
    { id: 2, name: 'Omar Hassan', email: 'omar@example.com', tier: 'Silver', lastOrder: 'Robot Kit' },
    { id: 3, name: 'Chen Wei', email: 'chen@example.com', tier: 'Bronze', lastOrder: 'Jigsaw Puzzle' },
    { id: 4, name: 'Sofia Rossi', email: 'sofia@example.com', tier: 'Gold', lastOrder: 'Kite' },
  ] },
  '222222222': { name: 'Flower Shop', customers: [
    { id: 1, name: 'Grace Miller', email: 'grace@example.com', tier: 'Gold', lastOrder: 'Red Roses (dozen)' },
    { id: 2, name: 'Ravi Patel', email: 'ravi@example.com', tier: 'Silver', lastOrder: 'Tulip Bouquet' },
    { id: 3, name: 'Emma Novak', email: 'emma@example.com', tier: 'Bronze', lastOrder: 'Sunflowers' },
    { id: 4, name: 'Diego Alvarez', email: 'diego@example.com', tier: 'Silver', lastOrder: 'Lavender Bunch' },
    { id: 5, name: 'Aiko Tanaka', email: 'aiko@example.com', tier: 'Gold', lastOrder: 'Cherry Blossom Branch' },
  ] },
};

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' };

const launchOnly = (note) => `<!doctype html><meta charset="utf-8"><title>CRM</title>
<body style="font-family:system-ui;background:#f1f5f9"><div style="max-width:520px;margin:18vh auto;text-align:center;background:#fff;padding:2rem;border-radius:12px;box-shadow:0 1px 3px rgba(0,0,0,.1)">
<h2>Launch from the portal</h2><p>${note}</p><a href="${PORTAL}" style="color:#3730a3">Go to portal →</a></div>`;

function deny(req, res, note) {
  const html = (req.headers.accept || '').includes('text/html');
  res.writeHead(403, { 'Content-Type': html ? 'text/html' : 'application/json' });
  res.end(html ? launchOnly(note) : JSON.stringify({ error: note }));
}

const json = (res, obj, status = 200) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(obj));
};

// REMOTE_USER is only believed if it carries the shim's fresh HMAC signature over (value, timestamp).
function trusted(req) {
  const remote = req.headers['remote_user'], ts = req.headers['x-shim-timestamp'], sig = req.headers['x-shim-signature'];
  if (!remote || !ts || !sig || Math.abs(Date.now() - Number(ts)) > 30_000) return false;
  const want = Buffer.from(crypto.createHmac('sha256', SECRET).update(`${remote}|${ts}`).digest('hex'));
  const given = Buffer.from(String(sig));
  return given.length === want.length && crypto.timingSafeEqual(given, want);
}

http.createServer((req, res) => {
  if (!trusted(req)) return deny(req, res, 'CRM can only be launched from the portal (valid REMOTE_USER required).');

  const remote = String(req.headers['remote_user'] || '');
  const i = remote.indexOf('$');
  const duns = remote.slice(0, i), user = remote.slice(i + 1);
  if (i < 1 || !/^\d{9}$/.test(duns) || !user) return deny(req, res, 'Missing or malformed REMOTE_USER.');

  const url = new URL(req.url, 'http://x');
  const company = COMPANIES[duns] || { name: `DUNS ${duns}`, customers: [] };

  if (url.pathname === '/crm/api/me') return json(res, { remoteUser: remote, duns, user, company: company.name });
  if (url.pathname === '/crm/api/customers') return json(res, company.customers);
  if (url.pathname === '/crm/api/snoop') {
    const headers = [];
    for (let n = 0; n < req.rawHeaders.length; n += 2) {
      const name = req.rawHeaders[n];
      headers.push({ name, value: req.rawHeaders[n + 1] });
    }
    return json(res, {
      request: { method: req.method, url: req.url, httpVersion: req.httpVersion, remoteAddress: req.socket.remoteAddress },
      headers,
    });
  }
  if (url.pathname.startsWith('/crm/api/')) return json(res, { error: 'Not found' }, 404);

  // Static files (SPA fallback to index.html)
  let rel = decodeURIComponent(url.pathname).replace(/^\/crm\/?/, '');
  let file = path.resolve(DIST, rel || 'index.html');
  if (!file.startsWith(DIST + path.sep) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(DIST, 'index.html');
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}).listen(PORT, '127.0.0.1', () => console.log(`CRM backend on http://127.0.0.1:${PORT}`));
