import { useEffect, useState } from 'react';

const SHIM_URL = '/shim/switch';
const PORTAL_URL = 'http://localhost:3000/';
const NAV = [
  { id: 'dashboard', label: 'Dashboard', icon: '📈' },
  { id: 'customers', label: 'Customers', icon: '👥' },
  { id: 'snoop', label: 'Snoop', icon: '🔍' },
];

const get = async (path) => {
  const res = await fetch(path, { cache: 'no-store', headers: { 'X-CRM-Client': 'crm-ui' } });
  if (!res.ok) throw new Error(String(res.status));
  return res.json();
};

const Notice = ({ children }) => <div className="notice">{children}</div>;

function Snoop({ remoteUser }) {
  const [data, setData] = useState(null);
  useEffect(() => { get('/crm/api/snoop').then(setData); }, []);
  if (!data) return <p>Loading…</p>;
  return (
    <>
      <h1>Snoop</h1>
      <p className="muted">
        Every HTTP header the CRM backend received for this request. The shim injects
        <b> REMOTE_USER</b> (<code>{remoteUser}</code>) and strips any value sent by the browser.
      </p>
      <h3>Request</h3>
      <table className="data">
        <tbody>
          {Object.entries(data.request).map(([k, v]) => (
            <tr key={k}><th>{k}</th><td className="mono">{String(v)}</td></tr>
          ))}
        </tbody>
      </table>
      <h3>Headers ({data.headers.length})</h3>
      <table className="data">
        <thead><tr><th>Name</th><th>Value</th></tr></thead>
        <tbody>
          {data.headers.map((h, i) => (
            <tr key={i} className={h.name.toLowerCase() === 'remote_user' ? 'hl' : ''}>
              <td className="mono">{h.name}</td><td className="mono">{h.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

export default function App() {
  const [me, setMe] = useState(null);
  const [err, setErr] = useState(null);
  const [customers, setCustomers] = useState([]);
  const [page, setPage] = useState('dashboard');

  useEffect(() => {
    window.history.replaceState({}, '', window.location.pathname); // drop the one-time ?launch= ticket
    Promise.all([get('/crm/api/me'), get('/crm/api/customers')])
      .then(([m, c]) => { setMe(m); setCustomers(c); })
      .catch(() => setErr(true));
  }, []);

  if (err)
    return (
      <Notice>
        <h2>Launch from the portal</h2>
        <p>CRM is only available when launched from the portal.</p>
        <a href={PORTAL_URL}>Go to portal →</a>
      </Notice>
    );
  if (!me) return <div className="center">Loading…</div>;

  const tiers = customers.reduce((a, c) => ({ ...a, [c.tier]: (a[c.tier] || 0) + 1 }), {});

  return (
    <div className="shell">
      <header className="topnav">
        <div className="brand">CRM · {me.company}</div>
        <div className="spacer" />
        <span className="duns-badge">DUNS {me.duns}</span>
        <span className="user">{me.user}</span>
        <a className="link" href={SHIM_URL}>Switch DUNS</a>
        <a className="link" href={PORTAL_URL}>← Portal</a>
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
          {page === 'dashboard' && (
            <>
              <h1>{me.company} — Dashboard</h1>
              <p className="muted">Signed in as <b>{me.user}</b> for DUNS <b>{me.duns}</b></p>
              <div className="stats">
                <div className="stat"><b>{customers.length}</b>Customers</div>
                {Object.entries(tiers).map(([t, n]) => <div className="stat" key={t}><b>{n}</b>{t} tier</div>)}
              </div>
            </>
          )}
          {page === 'customers' && (
            <>
              <h1>Customers — {me.company}</h1>
              <table className="data">
                <thead><tr><th>Name</th><th>Email</th><th>Tier</th><th>Last order</th></tr></thead>
                <tbody>
                  {customers.map((c) => (
                    <tr key={c.id}><td>{c.name}</td><td>{c.email}</td><td>{c.tier}</td><td>{c.lastOrder}</td></tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          {page === 'snoop' && <Snoop remoteUser={me.remoteUser} />}
        </main>
      </div>
    </div>
  );
}
