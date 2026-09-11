import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';
import App from '../App';
import { setAuthHeaders } from '../api/client';
import type { AlertRow, ConsoleConfig, Me, Stats } from '../api/types';
import { AuthProvider } from '../auth/AuthContext';
import { saveActingAs, saveDevSession } from '../auth/session';

export const DEV_CONFIG: ConsoleConfig = { authEnabled: false };
export const COGNITO_CONFIG: ConsoleConfig = {
  authEnabled: true,
  issuerUri: 'https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST',
  clientId: 'client-1',
  hostedUiDomain: 'https://auditflow-test.auth.us-east-1.amazoncognito.com',
};

export const RESISTANCE: Me = { customerId: 'resistance', customerName: 'Resistance', subject: null, roles: ['USER'] };
export const OPERATOR: Me = { customerId: 'platform', customerName: 'AuditFlow', subject: null, roles: ['USER', 'OPERATOR'] };

const days = ['2026-09-01', '2026-09-02', '2026-09-03', '2026-09-04', '2026-09-05', '2026-09-06', '2026-09-07'];

export const STATS: Stats = {
  window: { from: '2026-09-01T00:00:00Z', to: '2026-09-08T00:00:00Z' },
  totals: { events: 1284, alerts: 12, critical: 3, anomalous: 7, users: 41 },
  previous: { events: 1100, alerts: 20, critical: 0, anomalous: 7, users: 38 },
  perDay: days.map((day, i) => ({
    day,
    events: 100 + i * 10,
    alerts: i === 3 ? 5 : 1,
    byRisk: { LOW: 60 + i * 10, MEDIUM: 30, HIGH: 9, CRITICAL: i === 3 ? 3 : 0 },
  })),
  byType: { AUTH_EVENT: 900, DATABASE_QUERY: 300, DATA_EXPORT: 84 },
  byRisk: { LOW: 900, MEDIUM: 300, HIGH: 81, CRITICAL: 3 },
  byControl: { 'SOC2:AC-2': 900, 'SOC2:IA-2': 850, 'GDPR:Art-30': 84 },
  topUsers: [
    { name: 'boris@example.com', count: 402 },
    { name: 'dana', count: 120 },
  ],
  topResources: [{ name: 'login', count: 900 }],
};

export const EMPTY_STATS: Stats = {
  ...STATS,
  totals: { events: 0, alerts: 0, critical: 0, anomalous: 0, users: 0 },
  previous: { events: 0, alerts: 0, critical: 0, anomalous: 0, users: 0 },
  perDay: days.map((day) => ({ day, events: 0, alerts: 0, byRisk: { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 } })),
  byType: {},
  byRisk: { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 },
  byControl: {},
  topUsers: [],
  topResources: [],
};

export const ALERTS: AlertRow[] = [
  {
    alertId: 'al-1',
    ruleId: 'r-1',
    ruleName: 'Failed login attempt',
    eventId: 'evt-9',
    triggeredAt: '2026-09-04T10:00:05Z',
    notifiedChannels: 'slack',
    ruleChannels: 'slack,email',
  },
  { alertId: 'al-2', ruleId: null, ruleName: null, eventId: 'evt-8', triggeredAt: '2026-09-03T10:00:05Z', notifiedChannels: null, ruleChannels: null },
];

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

export function noContent(): Response {
  return new Response(null, { status: 204 });
}

export type Handler = (url: URL, init?: RequestInit) => Response | Promise<Response>;

/**
 * Stubs fetch with a per-endpoint table keyed "METHOD /path" (the query
 * string is stripped for matching and handed to the handler as a URL so
 * a test can assert on it) and renders the app at the given route. A
 * dev session for `me` is stored first, the way a returning visitor
 * would have one; pass `me: null` to arrive anonymous. Anything not in
 * the table answers 404 in the gateway's error shape.
 */
export function renderApp(
  path: string,
  routes: Record<string, Handler> = {},
  options: { me?: Me | null; config?: ConsoleConfig; operator?: boolean; actingAs?: string | null } = {},
) {
  const { me = RESISTANCE, config = DEV_CONFIG, operator = false, actingAs = null } = options;
  saveDevSession(me ? { customerId: me.customerId, operator: operator || me.roles.includes('OPERATOR') } : null);
  saveActingAs(actingAs);
  setAuthHeaders(async () => ({}));
  const table: Record<string, Handler> = {
    'GET /config.json': () => jsonResponse(config),
    ...(me ? { 'GET /api/v1/me': () => jsonResponse(me) } : {}),
    ...routes,
  };
  const fetchMock = vi.fn(async (input: unknown, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost');
    const key = `${init?.method ?? 'GET'} ${url.pathname}`;
    const handler = table[key];
    return handler ? handler(url, init) : jsonResponse({ error: 'not_found', message: `no stub for ${key}` }, 404);
  });
  vi.stubGlobal('fetch', fetchMock);

  const view = render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
  return { view, fetchMock };
}

/** The JSON body a stubbed handler was called with. */
export function bodyOf(init?: RequestInit): unknown {
  return init?.body ? JSON.parse(String(init.body)) : undefined;
}

/** The headers a stubbed handler was called with, lower-cased. */
export function headersOf(init?: RequestInit): Record<string, string> {
  const out: Record<string, string> = {};
  const headers = init?.headers as Record<string, string> | undefined;
  for (const [k, v] of Object.entries(headers ?? {})) out[k.toLowerCase()] = v;
  return out;
}

/** The URLs fetch was called with for a given method + path, in order. */
export function callsTo(fetchMock: ReturnType<typeof vi.fn>, method: string, pathname: string): URL[] {
  return fetchMock.mock.calls
    .map(([input, init]) => ({ url: new URL(String(input), 'http://localhost'), init: init as RequestInit | undefined }))
    .filter(({ url, init }) => (init?.method ?? 'GET') === method && url.pathname === pathname)
    .map(({ url }) => url);
}
