import { useEffect, useState } from 'react';

/** What the gateway publishes at /config.json before anyone signs in. */
export interface ConsoleConfig {
  authEnabled: boolean;
  issuerUri?: string;
  clientId?: string;
  hostedUiDomain?: string;
}

/**
 * Placeholder shell: proves the gateway serves the bundle and that the
 * browser can reach the runtime config. The real console (sign-in, shell,
 * dashboard) replaces this in the next slices of docs/plans/CONSOLE.md.
 */
export default function App() {
  const [config, setConfig] = useState<ConsoleConfig | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch('/config.json')
      .then((r) => (r.ok ? (r.json() as Promise<ConsoleConfig>) : Promise.reject(new Error(String(r.status)))))
      .then((c) => {
        if (!cancelled) setConfig(c);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main className="placeholder">
      <p className="eyebrow">AuditFlow</p>
      <h1>Console</h1>
      <p className="lede">
        Continuous compliance monitoring: audit events, alerts, rules and evidence reports, in one place.
        The console is being built slice by slice; the API it will use is live at <code>/api/v1</code>.
      </p>
      <p className="status" aria-live="polite">
        {failed && 'Runtime config could not be loaded.'}
        {config && (config.authEnabled ? 'Sign-in: Cognito' : 'Sign-in: local development (no auth)')}
      </p>
    </main>
  );
}
