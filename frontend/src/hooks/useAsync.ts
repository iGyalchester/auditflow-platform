import { useCallback, useEffect, useState } from 'react';
import { ApiError, UnauthorizedError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

interface AsyncState<T> {
  data: T | null;
  error: string | null;
  /** the gateway's error code when it answered (403 "forbidden" matters to the operator page) */
  code: string | null;
  status: number | null;
  loading: boolean;
}

/**
 * Load-on-mount for a page: runs the fetch, exposes data/error/loading,
 * a reload() for after a mutation and setData() for optimistic updates.
 * A 401 anywhere ends the session (RequireAuth then redirects); any
 * other failure becomes a message the page can show. The customer the
 * requests run as (an operator's "view as") is part of every load's
 * dependencies, so switching it refetches every page rather than
 * leaving another tenant's numbers on screen.
 */
export function useAsync<T>(load: () => Promise<T>, deps: unknown[] = []) {
  const [state, setState] = useState<AsyncState<T>>({ data: null, error: null, code: null, status: null, loading: true });
  const [tick, setTick] = useState(0);
  const { sessionEnded, me } = useAuth();
  const scope = me?.actingAs ?? me?.customerId ?? '';

  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    load()
      .then((data) => alive && setState({ data, error: null, code: null, status: null, loading: false }))
      .catch((e) => {
        if (!alive) return;
        if (e instanceof UnauthorizedError) {
          sessionEnded();
        } else {
          setState({
            data: null,
            error: e instanceof ApiError ? e.message : 'Could not load this page. Refresh to try again.',
            code: e instanceof ApiError ? e.code : null,
            status: e instanceof ApiError ? e.status : null,
            loading: false,
          });
        }
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, scope, sessionEnded]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const setData = useCallback(
    (update: T | null | ((current: T | null) => T | null)) =>
      setState((s) => ({ ...s, data: typeof update === 'function' ? (update as (c: T | null) => T | null)(s.data) : update })),
    [],
  );

  return { ...state, reload, setData };
}
