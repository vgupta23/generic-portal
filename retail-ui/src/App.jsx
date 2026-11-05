import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CFG, parseDunsRoles, validateTokens } from './validate.js';
import { COMPANIES } from './catalog.js';

const NAV = [
  { id: 'catalog', label: 'Catalog', icon: '🛒' },
  { id: 'inventory', label: 'Inventory', icon: '📦' },
  { id: 'session', label: 'Session & Token', icon: '🔐' },
];
const REQUIRED_ROLE = 'RETAILUI';

// The portal passes tokens in the URL fragment (never sent to a server).
function takeTokensFromFragment() {
  const h = new URLSearchParams(window.location.hash.slice(1));
  if (h.get('access_token') && h.get('id_token')) {
    const t = { access_token: h.get('access_token'), id_token: h.get('id_token'), duns: h.get('duns') };
    sessionStorage.setItem('retail_tokens', JSON.stringify(t));
    window.history.replaceState({}, '', window.location.pathname);
    return t;
  }
  return JSON.parse(sessionStorage.getItem('retail_tokens') || 'null');
}

const Notice = ({ children }) => <div className="notice">{children}</div>;

export default function App() {
  const [state, setState] = useState({ status: 'loading' });
  const [duns, setDuns] = useState('');
  const [page, setPage] = useState('catalog');

  const applyTokens = useCallback(async (t) => {
    const claims = await validateTokens(t);
    const next = { ...t, duns: t.duns ?? JSON.parse(sessionStorage.getItem('retail_tokens') || '{}').duns };
    sessionStorage.setItem('retail_tokens', JSON.stringify(next));
    setState({ status: 'ok', tokens: next, ...claims });
  }, []);

  // Tokens are renewed by the portal tab that launched this app (it holds the refresh token).
  const requestFresh = useCallback(() => {
    if (!window.opener || window.opener.closed) return false;
    window.opener.postMessage({ type: 'token-request' }, CFG.portalOrigin);
    return true;
  }, []);

  const fail = useCallback((message) => {
    sessionStorage.removeItem('retail_tokens');
    setState({ status: 'invalid', error: message });
  }, []);

  useEffect(() => {
    const onMessage = (e) => {
      if (e.origin !== CFG.portalOrigin || e.data?.type !== 'token-response') return;
      applyTokens(e.data).catch((err) => fail(err.message));
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [applyTokens, fail]);

  useEffect(() => {
    const t = takeTokensFromFragment();
    if (!t) return setState({ status: 'none' });
    applyTokens(t).catch((e) => {
      // e.g. stale tokens after a reload: try the portal tab before giving up
      if (requestFresh()) setTimeout(() => setState((s) => (s.status === 'loading' ? { status: 'invalid', error: e.message } : s)), 5000);
      else fail(e.message);
    });
  }, [applyTokens, requestFresh, fail]);

  // Ask the portal for new tokens 30s before expiry; if none arrive, the session ends at expiry.
  const expRef = useRef(null);
  useEffect(() => {
    if (state.status !== 'ok') return;
    const msLeft = state.access.exp * 1000 - Date.now();
    expRef.current = state.access.exp;
    const ask = setTimeout(requestFresh, Math.max(msLeft - 30000, 1000));
    const end = setTimeout(() => {
      if (expRef.current === state.access.exp) fail('Session expired — relaunch from the portal');
    }, msLeft + 2000);
    return () => { clearTimeout(ask); clearTimeout(end); };
  }, [state, requestFresh, fail]);

  const dunsRoles = useMemo(() => parseDunsRoles(state.access?.duns_roles), [state.access]);
  // Only DUNS where the user holds the RETAILUI role.
  const dunsList = Object.keys(dunsRoles).filter((d) => dunsRoles[d].includes(REQUIRED_ROLE));

  useEffect(() => {
    if (state.status !== 'ok' || !dunsList.length || dunsList.includes(duns)) return;
    setDuns(dunsList.includes(state.tokens.duns) ? state.tokens.duns : dunsList[0]);
  }, [state, dunsList, duns]);

  if (state.status === 'loading') return <div className="center">Validating token…</div>;
  if (state.status === 'none')
    return (
      <Notice>
        <h2>Launch from the portal</h2>
        <p>This app needs a token from the portal.</p>
        <a href={CFG.portalUrl}>Go to portal →</a>
      </Notice>
    );
  if (state.status === 'invalid')
    return (
      <Notice>
        <h2>Access denied</h2>
        <p>Token validation failed: <b>{state.error}</b></p>
        <a href={CFG.portalUrl}>Back to portal →</a>
      </Notice>
    );
  if (!dunsList.length)
    return (
      <Notice>
        <h2>No access</h2>
        <p>Your account has no DUNS with the {REQUIRED_ROLE} role.</p>
        <a href={CFG.portalUrl}>Back to portal →</a>
      </Notice>
    );

  const company = COMPANIES[duns];
  const products = company?.products || [];
  const exp = new Date(state.access.exp * 1000).toLocaleTimeString();

  return (
    <div className="shell">
      <header className="topnav">
        <div className="brand"><a href={CFG.portalUrl}>Retail UI</a> · {company?.name || duns}</div>
        <div className="spacer" />
        <label className="duns-picker">
          DUNS
          <select value={duns} onChange={(e) => setDuns(e.target.value)}>
            {Object.keys(dunsRoles).map((d) => (
              <option key={d} value={d} disabled={!dunsList.includes(d)}>
                {d}{dunsList.includes(d) ? '' : ' (no access)'}
              </option>
            ))}
          </select>
        </label>
        <span className="user">{state.id.name || state.id.preferred_username}</span>
        <a className="user" href={CFG.portalUrl}>← Portal</a>
      </header>

      <div className="body">
        <nav className="leftnav">
          {NAV.map((n) => (
            <button key={n.id} className={page === n.id ? 'active' : ''} onClick={() => setPage(n.id)}>
              <span>{n.icon}</span> {n.label}
            </button>
          ))}
        </nav>

        <main className="content">
          {page === 'catalog' && (
            <>
              <h1>{company?.name || 'Unknown company'} — Catalog</h1>
              <p className="muted">{products.length} products for DUNS <b>{duns}</b></p>
              <div className="products">
                {products.map((p) => (
                  <div className="product" key={p.id}>
                    <div className="emoji">{p.emoji}</div>
                    <h3>{p.name}</h3>
                    <div className="price">${p.price.toFixed(2)}</div>
                    <div className="stock">{p.stock} in stock</div>
                  </div>
                ))}
              </div>
            </>
          )}

          {page === 'inventory' && (
            <>
              <h1>Inventory — {company?.name}</h1>
              <table cellPadding="8" style={{ background: '#fff', borderCollapse: 'collapse' }}>
                <thead><tr><th align="left">Product</th><th>Price</th><th>Stock</th></tr></thead>
                <tbody>
                  {products.map((p) => (
                    <tr key={p.id}><td>{p.emoji} {p.name}</td><td>${p.price.toFixed(2)}</td><td align="center">{p.stock}</td></tr>
                  ))}
                </tbody>
              </table>
            </>
          )}

          {page === 'session' && (
            <>
              <h1>Session &amp; Token</h1>
              <p><span className="badge-ok">✔ signature, issuer, audience, expiry and live session verified</span></p>
              <p className="muted">Access token expires {exp}.</p>
              <h3>Validated access token claims</h3>
              <pre>{JSON.stringify(state.access, null, 2)}</pre>
            </>
          )}
        </main>
      </div>
    </div>
  );
}
