import { useEffect, useState } from 'react';

const PORTAL_URL = 'http://localhost:3000/';
const COMPANY = { '111111111': 'Toy Shop', '222222222': 'Flower Shop' };

const Notice = ({ children }) => <div className="notice">{children}</div>;

export default function App() {
  const [state, setState] = useState({ status: 'loading' });
  const [err, setErr] = useState(null);

  useEffect(() => {
    (async () => {
      try {
        // Entry requires a credential in the URL fragment: either the portal's OIDC access token
        // (validated server-side) or a one-time ticket from the CRM's "Switch DUNS" link.
        // Nothing is shown from a leftover cookie alone, and the DUNS list is never kept in the browser.
        const h = new URLSearchParams(window.location.hash.slice(1));
        const token = h.get('access_token'), ticket = h.get('ticket');
        window.history.replaceState({}, '', window.location.pathname);
        if (!token && !ticket) return setState({ status: 'none' });
        const r = await fetch(token ? '/shim/api/session' : '/shim/api/ticket', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(token ? { access_token: token } : { ticket }),
        });
        const body = await r.json().catch(() => ({}));
        if (r.status === 401 && ticket) return setState({ status: 'none' });
        if (!r.ok) return setState({ status: 'invalid', error: body.error || 'Token rejected' });
        setState({ status: 'ok', ...body });
      } catch (e) {
        setState({ status: 'invalid', error: e.message });
      }
    })();
  }, []);

  async function pick(duns) {
    setErr(null);
    const r = await fetch('/shim/api/select', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ duns }),
    });
    if (r.ok) window.location.assign((await r.json()).url);
    else setErr((await r.json().catch(() => ({}))).error || 'Could not select DUNS');
  }

  async function signOut() {
    await fetch('/shim/api/session', { method: 'DELETE' });
    window.location.assign(PORTAL_URL);
  }

  if (state.status === 'loading') return <div className="center">Validating token…</div>;
  if (state.status === 'none')
    return (
      <Notice>
        <h2>Launch from the portal</h2>
        <p>CRM is only available when launched from the portal.</p>
        <a href={PORTAL_URL}>Go to portal →</a>
      </Notice>
    );
  if (state.status === 'invalid')
    return (
      <Notice>
        <h2>Access denied</h2>
        <p>Token validation failed: <b>{state.error}</b></p>
        <a href={PORTAL_URL}>Back to portal →</a>
      </Notice>
    );

  return (
    <div className="shell">
      <header className="topnav">
        <div className="brand">CRM Gateway</div>
        <div className="spacer" />
        <span className="user">{state.name || state.user}</span>
        <a className="link" href={PORTAL_URL}>← Portal</a>
        <button onClick={signOut}>Sign out</button>
      </header>
      <main className="pick">
        <h1>Select a company</h1>
        <p className="muted">Choose the DUNS to open in CRM.</p>
        <div className="duns-tiles">
          {state.duns.map((d) => (
            <button key={d.duns} className="duns-tile" disabled={!d.crm} onClick={() => pick(d.duns)}>
              <div className="num">{d.duns}</div>
              <div className="co">{COMPANY[d.duns] || 'Company'}{d.crm ? '' : ' — no CRM access'}</div>
              {d.roles.map((r) => <span className="role-tag" key={r}>{r}</span>)}
            </button>
          ))}
        </div>
        {err && <p className="err">{err}</p>}
      </main>
    </div>
  );
}
