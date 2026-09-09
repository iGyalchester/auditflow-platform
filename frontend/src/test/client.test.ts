import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, UnauthorizedError, fetchStats, getRateLimitRemaining, query, setAuthHeaders } from '../api/client';
import { jsonResponse } from './helpers';

describe('api client', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('sends the auth headers the context supplies and parses the gateway error shape', async () => {
    setAuthHeaders(async () => ({ Authorization: 'Bearer t' }));
    const fetchMock = vi.fn(async () => jsonResponse({ error: 'forbidden', message: 'only operators may act as another customer' }, 403));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchStats('a', 'b')).rejects.toMatchObject({ status: 403, code: 'forbidden', message: 'only operators may act as another customer' });
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer t');
  });

  it('a 401 is its own error type', async () => {
    setAuthHeaders(async () => ({}));
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ error: 'unauthenticated' }, 401)));
    await expect(fetchStats('a', 'b')).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it('a non-JSON failure still becomes an ApiError with a generic message', async () => {
    setAuthHeaders(async () => ({}));
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>bad gateway</html>', { status: 502 })));
    const error = await fetchStats('a', 'b').catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(502);
    expect(error.message).toMatch(/502/);
  });

  it('waits out a 429 for Retry-After and retries once, tracking the remaining budget', async () => {
    vi.useFakeTimers();
    setAuthHeaders(async () => ({}));
    let calls = 0;
    const fetchMock = vi.fn(async () =>
      calls++ === 0
        ? new Response('', { status: 429, headers: { 'Retry-After': '2', 'X-RateLimit-Remaining': '0' } })
        : jsonResponse({ totals: {} }, 200, { 'X-RateLimit-Remaining': '39' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const pending = fetchStats('a', 'b');
    await vi.advanceTimersByTimeAsync(1999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    await expect(pending).resolves.toEqual({ totals: {} });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(getRateLimitRemaining()).toBe(39);
  });

  it('a second 429 is reported, not retried forever', async () => {
    vi.useFakeTimers();
    setAuthHeaders(async () => ({}));
    const fetchMock = vi.fn(async () => jsonResponse({ error: 'rate_limited', message: 'slow down' }, 429, { 'Retry-After': '1' }));
    vi.stubGlobal('fetch', fetchMock);

    const pending = fetchStats('a', 'b');
    const outcome = pending.catch((e) => e);
    await vi.advanceTimersByTimeAsync(1000);
    expect(await outcome).toMatchObject({ status: 429, code: 'rate_limited' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('query drops empty values and encodes the rest', () => {
    expect(query({ from: '2026-09-01T00:00:00Z', q: '', type: undefined, anomalous: true, limit: 50 })).toBe(
      '?from=2026-09-01T00%3A00%3A00Z&anomalous=true&limit=50',
    );
    expect(query({})).toBe('');
  });
});
