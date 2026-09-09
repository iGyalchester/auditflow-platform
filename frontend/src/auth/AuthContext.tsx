import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { fetchConfig, fetchMe, setAuthHeaders } from '../api/client';
import type { ConsoleConfig, Me } from '../api/types';
import { createOidc, type Oidc, type OidcSession } from './oidc';
import { loadActingAs, loadDevSession, saveActingAs, saveDevSession, type DevSession } from './session';

/**
 * Who is signed in, app-wide, and how. On first load the console asks
 * the gateway for /config.json: auth off means the dev sign-in page
 * (the customer id and roles travel as headers); auth on means Cognito
 * (the ID token travels as a bearer). Either way the answer to "who am
 * I?" comes from GET /api/v1/me, which the gateway decides.
 */
export interface AuthState {
  /** null until /config.json has answered */
  config: ConsoleConfig | null;
  me: Me | null;
  /** true while config or the first /me is in flight */
  loading: boolean;
  /** set when /config.json itself failed */
  configError: string | null;
  /** the Cognito session, when auth is enforced and someone is signed in */
  oidcSession: OidcSession | null;
  signInDev: (session: DevSession) => Promise<void>;
  signInCognito: (returnTo: string) => Promise<void>;
  completeCognitoSignIn: () => Promise<string>;
  signOut: () => Promise<void>;
  /** operators: run every request as this customer (null = yourself) */
  actAs: (customerId: string | null) => Promise<void>;
  /** a 401 arrived: forget the session so the guard redirects */
  sessionEnded: () => void;
  refreshMe: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<ConsoleConfig | null>(null);
  const [configError, setConfigError] = useState<string | null>(null);
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  const [oidcSession, setOidcSession] = useState<OidcSession | null>(null);
  const oidc = useRef<Oidc | null>(null);
  const dev = useRef<DevSession | null>(loadDevSession());
  const acting = useRef<string | null>(loadActingAs());

  // the one place the API learns how to authenticate a request
  useEffect(() => {
    setAuthHeaders(async () => {
      const headers: Record<string, string> = {};
      if (oidc.current) {
        const session = await oidc.current.currentUser();
        if (session) headers.Authorization = `Bearer ${session.idToken}`;
      } else if (dev.current) {
        headers['X-Customer-Id'] = dev.current.customerId;
        if (dev.current.operator) headers['X-Roles'] = 'operator';
      }
      if (acting.current) headers['X-Acting-Customer-Id'] = acting.current;
      return headers;
    });
  }, []);

  const loadMe = useCallback(async () => {
    try {
      setMe(await fetchMe());
    } catch {
      setMe(null);
      // a stale acting-as (the role was dropped, say) must not lock the operator out
      if (acting.current) {
        acting.current = null;
        saveActingAs(null);
      }
    }
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const loaded = await fetchConfig();
        if (!alive) return;
        setConfig(loaded);
        if (loaded.authEnabled) {
          oidc.current = await createOidc(loaded);
          const session = await oidc.current.currentUser();
          if (!alive) return;
          setOidcSession(session);
          if (session) await loadMe();
        } else if (dev.current) {
          await loadMe();
        }
      } catch (e) {
        if (alive) setConfigError(e instanceof Error ? e.message : 'The console could not start.');
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [loadMe]);

  const signInDev = useCallback(
    async (session: DevSession) => {
      dev.current = session;
      saveDevSession(session);
      acting.current = null;
      saveActingAs(null);
      await loadMe();
    },
    [loadMe],
  );

  const signInCognito = useCallback(async (returnTo: string) => {
    if (!oidc.current) throw new Error('Cognito sign-in is not configured');
    await oidc.current.signIn(returnTo);
  }, []);

  const completeCognitoSignIn = useCallback(async () => {
    if (!oidc.current) throw new Error('Cognito sign-in is not configured');
    const returnTo = await oidc.current.completeSignIn();
    setOidcSession(await oidc.current.currentUser());
    await loadMe();
    return returnTo;
  }, [loadMe]);

  const signOut = useCallback(async () => {
    setMe(null);
    acting.current = null;
    saveActingAs(null);
    if (oidc.current) {
      setOidcSession(null);
      await oidc.current.signOut();
    } else {
      dev.current = null;
      saveDevSession(null);
    }
  }, []);

  const actAs = useCallback(
    async (customerId: string | null) => {
      acting.current = customerId;
      saveActingAs(customerId);
      await loadMe();
    },
    [loadMe],
  );

  const sessionEnded = useCallback(() => setMe(null), []);

  const value = useMemo<AuthState>(
    () => ({
      config,
      me,
      loading,
      configError,
      oidcSession,
      signInDev,
      signInCognito,
      completeCognitoSignIn,
      signOut,
      actAs,
      sessionEnded,
      refreshMe: loadMe,
    }),
    [config, me, loading, configError, oidcSession, signInDev, signInCognito, completeCognitoSignIn, signOut, actAs, sessionEnded, loadMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error('useAuth outside AuthProvider');
  return state;
}

/** Route guard: anonymous visitors are sent to sign in, and come back here after. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading, configError } = useAuth();
  const location = useLocation();
  if (configError) {
    return (
      <main className="centered">
        <div className="card narrow" role="alert">
          <h1>AuditFlow</h1>
          <p>{configError}</p>
        </div>
      </main>
    );
  }
  if (loading) {
    return (
      <main className="centered" aria-busy="true">
        <div className="skeleton skeleton-block" aria-label="Loading" />
      </main>
    );
  }
  if (!me) {
    return <Navigate to="/sign-in" replace state={{ returnTo: location.pathname + location.search }} />;
  }
  return <>{children}</>;
}
