import type {
  AlertDetail,
  AlertFilter,
  AlertRow,
  AlertRule,
  AlertRuleRequest,
  AuditLogDetail,
  AuditLogFilter,
  AuditLogRow,
  ConsoleConfig,
  DryRun,
  Me,
  OperatorCustomer,
  PlatformStats,
  ReportSummary,
  RuleDraft,
  RuleValidation,
  Stats,
} from './types';

/**
 * Thin fetch wrapper for the gateway's JSON API. Three things every call
 * needs handled once: the auth headers (a Cognito ID token as bearer, or
 * the dev headers when auth is open; the AuthContext supplies them
 * through {@link setAuthHeaders}), the one error shape the gateway
 * answers with ({@code {"error","message","fields"?}}, thrown as
 * ApiError; 401 as UnauthorizedError so the router can send the user to
 * sign in), and the rate limiter (a 429 is waited out for Retry-After
 * and retried once; the remaining budget is reported for the settings
 * page).
 */

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    /** the gateway's machine-readable code, e.g. "validation" */
    public readonly code: string,
    message: string,
    public readonly fields: Record<string, string> = {},
  ) {
    super(message);
  }
}

export class UnauthorizedError extends ApiError {
  constructor() {
    super(401, 'unauthenticated', 'Your session has ended. Sign in again.');
  }
}

type HeaderProvider = () => Promise<Record<string, string>>;

let authHeaders: HeaderProvider = async () => ({});
let onUnauthorized: () => void = () => {};

/** The AuthContext installs the provider; tests install their own. */
export function setAuthHeaders(provider: HeaderProvider): void {
  authHeaders = provider;
}

/**
 * Called on every 401 before the error is thrown, whoever made the
 * request - a page load, a toggle, a download. The AuthContext installs
 * `sessionEnded` here, so the session ends the same way from every call
 * site rather than only from the ones that remembered to check.
 */
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

let rateLimitRemaining: number | null = null;
const rateLimitListeners = new Set<(remaining: number | null) => void>();

export function getRateLimitRemaining(): number | null {
  return rateLimitRemaining;
}

export function onRateLimit(listener: (remaining: number | null) => void): () => void {
  rateLimitListeners.add(listener);
  return () => rateLimitListeners.delete(listener);
}

function noteRateLimit(response: Response): void {
  const header = response.headers.get('X-RateLimit-Remaining');
  if (header !== null) {
    rateLimitRemaining = Number(header);
    rateLimitListeners.forEach((l) => l(rateLimitRemaining));
  }
}

/** The longest a 429 is waited out before giving up on the retry. */
const MAX_RETRY_WAIT_MS = 5000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Turns a failed response into the matching error; never returns. */
async function fail(response: Response): Promise<never> {
  if (response.status === 401) {
    onUnauthorized();
    throw new UnauthorizedError();
  }
  let code = 'request_failed';
  let message = `Request failed (${response.status}).`;
  let fields: Record<string, string> = {};
  try {
    const body = await response.json();
    if (body && typeof body.error === 'string') code = body.error;
    if (body && typeof body.message === 'string') message = body.message;
    if (body && body.fields && typeof body.fields === 'object') fields = body.fields;
  } catch {
    // non-JSON error body (a proxy, say) - keep the generic message
  }
  throw new ApiError(response.status, code, message, fields);
}

async function send(path: string, init: RequestInit, accept: string): Promise<Response> {
  const headers: Record<string, string> = { Accept: accept, ...(await authHeaders()) };
  if (init.body) headers['Content-Type'] = 'application/json';
  let response = await fetch(path, { ...init, headers });
  noteRateLimit(response);
  if (response.status === 429) {
    const retryAfter = Number(response.headers.get('Retry-After') ?? '1');
    const wait = Math.min(Number.isFinite(retryAfter) ? retryAfter * 1000 : 1000, MAX_RETRY_WAIT_MS);
    await sleep(wait);
    response = await fetch(path, { ...init, headers });
    noteRateLimit(response);
  }
  return response;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await send(path, init, 'application/json');
  if (!response.ok) await fail(response);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

/** A text/plain download: the body and the filename the server suggested. */
export async function requestText(path: string): Promise<{ text: string; filename: string | null }> {
  const response = await send(path, {}, 'text/plain, application/json');
  if (!response.ok) await fail(response);
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
  return { text: await response.text(), filename: match ? decodeURIComponent(match[1]) : null };
}

function json(method: 'POST' | 'PUT', body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) };
}

/** Builds "?a=1&b=2", dropping undefined/empty values so the URL stays clean. */
export function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

// --- bootstrap ----------------------------------------------------------

/** Public; read before anyone is signed in, so no auth headers. */
export async function fetchConfig(): Promise<ConsoleConfig> {
  const response = await fetch('/config.json', { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new ApiError(response.status, 'config', 'The console configuration could not be loaded.');
  return response.json() as Promise<ConsoleConfig>;
}

export function fetchMe(): Promise<Me> {
  return request('/api/v1/me');
}

// --- dashboard ----------------------------------------------------------

export function fetchStats(from: string, to: string): Promise<Stats> {
  return request(`/api/v1/stats${query({ from, to })}`);
}

// --- audit logs ---------------------------------------------------------

export function fetchAuditLogs(filter: AuditLogFilter = {}): Promise<AuditLogRow[]> {
  return request(`/api/v1/audit-logs${query({ ...filter })}`);
}

export function fetchAuditLog(eventId: string): Promise<AuditLogDetail> {
  return request(`/api/v1/audit-logs/${encodeURIComponent(eventId)}`);
}

// --- alerts -------------------------------------------------------------

export function fetchAlerts(filter: AlertFilter = {}): Promise<AlertRow[]> {
  return request(`/api/v1/alerts${query({ ...filter })}`);
}

export function fetchAlert(alertId: string): Promise<AlertDetail> {
  return request(`/api/v1/alerts/${encodeURIComponent(alertId)}`);
}

// --- rules --------------------------------------------------------------

export function fetchRules(): Promise<AlertRule[]> {
  return request('/api/v1/alert-rules');
}

export function fetchRule(ruleId: string): Promise<AlertRule> {
  return request(`/api/v1/alert-rules/${encodeURIComponent(ruleId)}`);
}

export function createRule(body: AlertRuleRequest): Promise<AlertRule> {
  return request('/api/v1/alert-rules', json('POST', body));
}

export function updateRule(ruleId: string, body: AlertRuleRequest): Promise<AlertRule> {
  return request(`/api/v1/alert-rules/${encodeURIComponent(ruleId)}`, json('PUT', body));
}

export function deleteRule(ruleId: string): Promise<void> {
  return request(`/api/v1/alert-rules/${encodeURIComponent(ruleId)}`, { method: 'DELETE' });
}

export function validateRule(draft: RuleDraft): Promise<RuleValidation> {
  return request('/api/v1/alert-rules/validate', json('POST', draft));
}

export function dryRunRule(draft: RuleDraft, from: string, to: string): Promise<DryRun> {
  return request(`/api/v1/alert-rules/dry-run${query({ from, to })}`, json('POST', draft));
}

// --- reports ------------------------------------------------------------

export function fetchFrameworks(): Promise<string[]> {
  return request('/api/v1/reports');
}

export function fetchReportSummary(framework: string, from: string, to: string): Promise<ReportSummary> {
  return request(`/api/v1/reports/${encodeURIComponent(framework)}/summary${query({ from, to })}`);
}

export function reportUrl(framework: string, from: string, to: string): string {
  return `/api/v1/reports/${encodeURIComponent(framework)}${query({ from, to })}`;
}

export function fetchReport(framework: string, from: string, to: string): Promise<{ text: string; filename: string | null }> {
  return requestText(reportUrl(framework, from, to));
}

// --- operator -----------------------------------------------------------

export function fetchOperatorCustomers(): Promise<OperatorCustomer[]> {
  return request('/api/v1/operator/customers');
}

export function fetchPlatformStats(from: string, to: string): Promise<PlatformStats> {
  return request(`/api/v1/operator/stats${query({ from, to })}`);
}
