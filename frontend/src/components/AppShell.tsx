import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { rangeQuery } from '../util/timeRange';
import ActingBanner from './ActingBanner';
import Shortcuts from './Shortcuts';
import TimeRangePicker from './TimeRangePicker';
import { ToastProvider } from './Toast';

const NAV = [
  { to: '/dashboard', label: 'Dashboard', icon: '◔' },
  { to: '/audit-log', label: 'Audit log', icon: '≡' },
  { to: '/alerts', label: 'Alerts', icon: '⚑' },
  { to: '/rules', label: 'Rules', icon: '⚙' },
  { to: '/reports', label: 'Reports', icon: '▤' },
] as const;

/**
 * The frame around every signed-in page: a sidebar with the brand, the
 * navigation and who you are; a slim top bar with the global time range.
 * Links carry the current range so the window follows you between pages.
 * The Operator entry exists only for operators - the server enforces
 * that too, this just avoids a dead link.
 */
export default function AppShell() {
  const { me, signOut, config } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const [navOpen, setNavOpen] = useState(false);
  const range = rangeQuery(params);
  const operator = me?.roles.includes('OPERATOR') ?? false;

  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  async function onSignOut() {
    await signOut();
    navigate('/sign-in', { replace: true });
  }

  return (
    <ToastProvider>
      <div className={`shell${navOpen ? ' nav-open' : ''}`}>
        <a className="skip" href="#main">
          Skip to content
        </a>
        <aside className="sidebar">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            <span>AuditFlow</span>
          </div>
          <nav aria-label="Main">
            {NAV.map((item) => (
              <NavLink key={item.to} to={`${item.to}${range}`}>
                <span className="nav-icon" aria-hidden="true">
                  {item.icon}
                </span>
                {item.label}
              </NavLink>
            ))}
            {operator && (
              <NavLink to={`/operator${range}`}>
                <span className="nav-icon" aria-hidden="true">
                  ◎
                </span>
                Operator
              </NavLink>
            )}
            <NavLink to="/settings">
              <span className="nav-icon" aria-hidden="true">
                ⋯
              </span>
              Settings
            </NavLink>
          </nav>
          <div className="sidebar-foot">
            <div className="who">
              <div className="who-name">{me?.customerName ?? me?.customerId}</div>
              <div className="muted small">
                {me?.customerName ? me.customerId : config?.authEnabled ? me?.subject : 'local development'}
                {operator && <span className="badge badge-role">Operator</span>}
              </div>
            </div>
            <button type="button" className="btn btn-ghost" onClick={onSignOut}>
              Sign out
            </button>
          </div>
        </aside>
        <div className="content">
          <header className="topbar">
            <button
              type="button"
              className="btn btn-ghost nav-toggle"
              aria-label={navOpen ? 'Hide navigation' : 'Show navigation'}
              aria-expanded={navOpen}
              onClick={() => setNavOpen((v) => !v)}
            >
              ☰
            </button>
            <TimeRangePicker />
          </header>
          <main id="main" className="page">
            <ActingBanner />
            <Outlet />
          </main>
        </div>
        <Shortcuts />
      </div>
    </ToastProvider>
  );
}
