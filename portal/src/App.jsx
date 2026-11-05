import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { authenticate, decode, login, logout, parseDunsRoles, refresh } from './auth.js';
import { NON_TILE_ROLES, tileFor } from './tiles.js';

const NAV = [
  { id: 'home', label: 'Home', icon: '🏠' },
  { id: 'apps', label: 'Applications', icon: '🧩' },
  { id: 'profile', label: 'Profile & Token', icon: '👤' },
];

// Opens a tile's app in a new tab, handing over the OIDC tokens via the URL fragment.
function launch(tile, tokens, duns) {
  const frag = new URLSearchParams({ access_token: tokens.access_token, id_token: tokens.id_token, duns });
  // No `noopener`: the launched app asks this tab for fresh tokens (see message listener).
  window.open(`${tile.launchUrl}#${frag}`, '_blank');
}

export default function App() {
  const [tokens, setTokens] = useState(null);
  const [error, setError] = useState(null);
  const [duns, setDuns] = useState('');
  const [page, setPage] = useState('home');

  useEffect(() => {
    authenticate().then(setTokens).catch((e) => setError(e.message));
  }, []);

  const claims = useMemo(() => (tokens ? decode(tokens.id_token) : null), [tokens]);
  const dunsRoles = useMemo(() => parseDunsRoles(claims?.duns_roles), [claims]);
  const dunsList = Object.keys(dunsRoles);

  // Default to the first DUNS once claims arrive.
  useEffect(() => {
    if (dunsList.length && !dunsList.includes(duns)) setDuns(dunsList[0]);
  }, [dunsList, duns]);

  // --- Token refresh -------------------------------------------------------
  const tokensRef = useRef(null);
  tokensRef.current = tokens;
  const inflight = useRef(null);

  const doRefresh = useCallback(() => {
    if (!inflight.current) {
      inflight.current = refresh(tokensRef.current)
        .then((t) => { tokensRef.current = t; setTokens(t); return t; })
        .catch(() => { login(); return null; }) // session ended: sign in again
        .finally(() => { inflight.current = null; });
    }
    return inflight.current;
  }, []);

  // Refresh 60s before the access token expires, and when the tab wakes up.
  useEffect(() => {
    if (!tokens?.refresh_token) return;
    const msLeft = () => decode(tokens.access_token).exp * 1000 - Date.now();
    const timer = setTimeout(doRefresh, Math.max(msLeft() - 60000, 5000));
    const onVisible = () => { if (document.visibilityState === 'visible' && msLeft() < 60000) doRefresh(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => { clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [tokens, doRefresh]);

  // Launched apps (e.g. Retail UI) request fresh tokens from this tab. Only known origins are answered.
  useEffect(() => {
    const onMessage = async (e) => {
      if (e.origin !== 'http://localhost:3001' || e.data?.type !== 'token-request') return;
      let t = tokensRef.current;
      if (t && decode(t.access_token).exp * 1000 - Date.now() < 90000) t = await doRefresh();
      if (t) e.source.postMessage({ type: 'token-response', access_token: t.access_token, id_token: t.id_token }, e.origin);
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [doRefresh]);

  if (error) return <div className="center">Error: {error}</div>;
  if (!tokens) return <div className="center">Authenticating…</div>;

  const roles = (dunsRoles[duns] || []).filter((r) => !NON_TILE_ROLES.includes(r));

  return (
    <div className="shell">
      <header className="topnav">
        <div className="brand">Demo Portal</div>
        <div className="spacer" />
        <label className="duns-picker">
          DUNS
          <select value={duns} onChange={(e) => setDuns(e.target.value)}>
            {dunsList.map((d) => (
              <option key={d} value={d}>{d}</option>
            ))}
          </select>
        </label>
        <span className="user">{claims.name || claims.preferred_username}</span>
        <button onClick={() => logout(tokens)}>Log out</button>
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
          {page === 'home' && (
            <>
              <h1>Welcome, {claims.given_name || claims.preferred_username}</h1>
              <p className="muted">
                Showing applications for DUNS <b>{duns}</b> ({roles.length} role{roles.length === 1 ? '' : 's'})
              </p>
              {dunsList.length === 0 && <p>No DUNS assigned to this user.</p>}
              <div className="tiles">
                {roles.map((role) => {
                  const t = tileFor(role);
                  return (
                    <div
                      className="tile"
                      key={`${duns}-${role}`}
                      style={{ borderTopColor: t.color, cursor: t.launchUrl ? 'pointer' : 'default' }}
                      onClick={() => t.launchUrl && launch(t, tokens, duns)}
                    >
                      <div className="tile-icon" style={{ background: t.color }}>{t.icon}</div>
                      <h3>{t.title}</h3>
                      <p>{t.desc}</p>
                      <span className="role-tag">{role}</span>
                      {t.launchUrl && <span className="role-tag" style={{ marginLeft: 6 }}>Launch ↗</span>}
                    </div>
                  );
                })}
              </div>
            </>
          )}

          {page === 'apps' && (
            <>
              <h1>Applications</h1>
              <ul>
                {dunsList.map((d) => (
                  <li key={d}><b>{d}</b>: {dunsRoles[d].filter((r) => !NON_TILE_ROLES.includes(r)).join(', ')}</li>
                ))}
              </ul>
            </>
          )}

          {page === 'profile' && (
            <>
              <h1>Profile &amp; Token</h1>
              <h3>duns_roles claim</h3>
              <pre>{JSON.stringify(claims.duns_roles, null, 2)}</pre>
              <h3>ID token claims</h3>
              <pre>{JSON.stringify(claims, null, 2)}</pre>
            </>
          )}
        </main>
      </div>
    </div>
  );
}
