import { useId, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { loadDevSession } from '../auth/session';

interface LocationState {
  returnTo?: string;
}

/**
 * Two doors, chosen by what the gateway said in /config.json. With auth
 * enforced there is one button: Cognito's hosted UI does the rest and
 * returns to /callback. With auth open (local development) you type the
 * customer id you want to be and tick "operator" if you want the
 * platform view; the console sends those as the headers the open
 * gateway honours. Nothing here is a real credential.
 */
export default function SignInPage() {
  const { config, me, loading, configError, signInDev, signInCognito } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const returnTo = (location.state as LocationState | null)?.returnTo ?? '/dashboard';
  const remembered = loadDevSession();
  const [customerId, setCustomerId] = useState(remembered?.customerId ?? '');
  const [operator, setOperator] = useState(remembered?.operator ?? false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const idField = useId();
  const opField = useId();

  if (me) return <Navigate to={returnTo} replace />;

  async function onDevSubmit(e: FormEvent) {
    e.preventDefault();
    const id = customerId.trim();
    if (!id) {
      setProblem('Enter a customer id.');
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await signInDev({ customerId: id, operator });
      navigate(returnTo, { replace: true });
    } catch {
      setProblem('The gateway did not accept that. Is it running on :8080?');
    } finally {
      setBusy(false);
    }
  }

  async function onCognito() {
    setBusy(true);
    setProblem(null);
    try {
      await signInCognito(returnTo);
    } catch (e) {
      setProblem(e instanceof Error ? e.message : 'Could not start sign-in.');
      setBusy(false);
    }
  }

  return (
    <main className="centered">
      <div className="card narrow sign-in">
        <div className="brand brand-large">
          <span className="brand-mark" aria-hidden="true" />
          <span>AuditFlow</span>
        </div>
        <p className="muted">Continuous compliance monitoring: audit events, alerts, rules and evidence reports.</p>

        {configError && (
          <p className="error" role="alert">
            {configError}
          </p>
        )}
        {!configError && loading && <div className="skeleton skeleton-row" aria-label="Loading" role="status" />}

        {config?.authEnabled && (
          <>
            <button type="button" className="btn btn-primary wide" onClick={onCognito} disabled={busy}>
              Sign in with Cognito
            </button>
            <p className="muted small">You will be sent to the hosted sign-in page and brought back here.</p>
          </>
        )}

        {config && !config.authEnabled && (
          <form onSubmit={onDevSubmit} noValidate>
            <p className="dev-note">
              <strong>Local development.</strong> The gateway is running with auth open, so pick who to be.
            </p>
            <label htmlFor={idField}>Customer id</label>
            <input
              id={idField}
              value={customerId}
              onChange={(e) => setCustomerId(e.target.value)}
              placeholder="resistance"
              autoComplete="off"
              autoFocus
            />
            <label className="check" htmlFor={opField}>
              <input id={opField} type="checkbox" checked={operator} onChange={(e) => setOperator(e.target.checked)} />
              Platform operator (see every customer)
            </label>
            {problem && (
              <p className="error small" role="alert">
                {problem}
              </p>
            )}
            <button type="submit" className="btn btn-primary wide" disabled={busy}>
              Continue
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
