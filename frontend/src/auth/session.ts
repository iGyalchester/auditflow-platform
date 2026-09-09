/**
 * What the browser remembers between page loads when auth is open (local
 * development): the customer id and whether you asked for the operator
 * role. Cognito sessions are held by oidc-client-ts in sessionStorage
 * and are not touched here. "Acting as" is per tab, so two tabs can look
 * at two customers.
 */

const DEV_KEY = 'auditflow.devSession';
const ACTING_KEY = 'auditflow.actingAs';

export interface DevSession {
  customerId: string;
  operator: boolean;
}

function read<T>(storage: Storage | undefined, key: string): T | null {
  try {
    const raw = storage?.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function write(storage: Storage | undefined, key: string, value: unknown): void {
  try {
    if (value === null || value === undefined) storage?.removeItem(key);
    else storage?.setItem(key, JSON.stringify(value));
  } catch {
    // storage unavailable (private mode, blocked): the session is memory-only
  }
}

function local(): Storage | undefined {
  return typeof localStorage === 'undefined' ? undefined : localStorage;
}

function session(): Storage | undefined {
  return typeof sessionStorage === 'undefined' ? undefined : sessionStorage;
}

export function loadDevSession(): DevSession | null {
  const value = read<DevSession>(local(), DEV_KEY);
  return value && typeof value.customerId === 'string' && value.customerId ? value : null;
}

export function saveDevSession(value: DevSession | null): void {
  write(local(), DEV_KEY, value);
}

export function loadActingAs(): string | null {
  return read<string>(session(), ACTING_KEY);
}

export function saveActingAs(customerId: string | null): void {
  write(session(), ACTING_KEY, customerId);
}
