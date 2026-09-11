import { useEffect, useState } from 'react';
import { getRateLimitRemaining, onRateLimit } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { formatDateTime } from '../util/format';

const REPO = 'https://github.com/iGyalchester/auditflow-platform';

function countdown(expiresAt: number | null, now: number): string {
  if (!expiresAt) return '—';
  const seconds = Math.max(0, Math.floor((expiresAt - now) / 1000));
  if (seconds === 0) return 'expired';
  const minutes = Math.floor(seconds / 60);
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min ${seconds % 60} s`;
}

/**
 * Who you are as the gateway sees it, how long the session has left, how
 * much of the API's rate budget the last request left you, and the
 * keyboard shortcuts. Nothing here is editable: identity comes from
 * Cognito (or the dev headers), and the rate limit is the gateway's.
 */
export default function SettingsPage() {
  const { me, config, oidcSession, signOut } = useAuth();
  const [remaining, setRemaining] = useState<number | null>(getRateLimitRemaining());
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => onRateLimit(setRemaining), []);
  useEffect(() => {
    if (!oidcSession?.expiresAt) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [oidcSession?.expiresAt]);

  const expiring = oidcSession?.expiresAt ? oidcSession.expiresAt - now < 5 * 60_000 : false;

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Settings</h1>
          <p className="muted">Your session, as the gateway sees it.</p>
        </div>
      </div>

      <div className="grid-2">
        <section className="card">
          <h2>Who you are</h2>
          <dl className="details">
            <dt>Customer</dt>
            <dd>
              {me?.customerName ?? me?.customerId}
              {me?.customerName && (
                <>
                  {' '}
                  <code>{me.customerId}</code>
                </>
              )}
            </dd>
            <dt>Roles</dt>
            <dd>{me?.roles.map((r) => (r === 'OPERATOR' ? 'Operator' : 'User')).join(', ')}</dd>
            {me?.actingAs && (
              <>
                <dt>Acting as</dt>
                <dd>
                  {me.actingAsName ?? me.actingAs} <code>{me.actingAs}</code>
                </dd>
              </>
            )}
            <dt>Signed in with</dt>
            <dd>{config?.authEnabled ? 'Cognito' : 'local development headers (no auth)'}</dd>
            {config?.authEnabled && (
              <>
                <dt>Subject</dt>
                <dd>
                  <code>{me?.subject ?? oidcSession?.subject ?? '—'}</code>
                </dd>
                <dt>Email</dt>
                <dd>{oidcSession?.email ?? '—'}</dd>
                <dt>Token expires</dt>
                <dd>
                  <span className={expiring ? 'error' : ''}>{countdown(oidcSession?.expiresAt ?? null, now)}</span>
                  {oidcSession?.expiresAt && <span className="muted small"> ({formatDateTime(new Date(oidcSession.expiresAt).toISOString())})</span>}
                  {expiring && <p className="small error">Sign out and in again to get a fresh token; the console renews silently while the Cognito session lasts.</p>}
                </dd>
              </>
            )}
          </dl>
          <div className="actions">
            <button type="button" className="btn" onClick={() => signOut()}>
              Sign out
            </button>
          </div>
        </section>

        <section className="card">
          <h2>The API</h2>
          <dl className="details">
            <dt>Rate budget</dt>
            <dd>
              {remaining === null ? <span className="muted">no request yet</span> : `${remaining} requests left in the current burst`}
              <p className="muted small">20 per second sustained, bursts of 40, per client address. A 429 is waited out and retried once.</p>
            </dd>
            <dt>Time zone</dt>
            <dd>Every timestamp in the console is UTC, the clock the sources stamp events with.</dd>
            <dt>Docs</dt>
            <dd>
              <a href={`${REPO}#readme`}>README</a> · <a href={`${REPO}/blob/develop/docs/CODE-TOUR.md`}>Code tour</a> · <a href={`${REPO}/blob/develop/docs/plans/CONSOLE.md`}>Console plan</a>
            </dd>
          </dl>
        </section>
      </div>

      <section className="card">
        <h2>Keyboard</h2>
        <dl className="details shortcuts">
          <dt>
            <kbd>/</kbd>
          </dt>
          <dd>Focus the search box on the audit log</dd>
          <dt>
            <kbd>?</kbd>
          </dt>
          <dd>Show the shortcuts</dd>
          <dt>
            <kbd>g</kbd> then <kbd>d</kbd> / <kbd>l</kbd> / <kbd>a</kbd> / <kbd>r</kbd> / <kbd>p</kbd>
          </dt>
          <dd>Go to the dashboard / audit log / alerts / rules / reports</dd>
          <dt>
            <kbd>Esc</kbd>
          </dt>
          <dd>Close a drawer or dialog</dd>
        </dl>
      </section>
    </>
  );
}
