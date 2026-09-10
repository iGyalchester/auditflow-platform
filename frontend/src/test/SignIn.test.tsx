import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { COGNITO_CONFIG, RESISTANCE, STATS, callsTo, headersOf, jsonResponse, renderApp } from './helpers';

describe('sign-in', () => {
  it('sends an anonymous visitor to the dev sign-in page and back after', async () => {
    const user = userEvent.setup();
    let meCalls = 0;
    const { fetchMock } = renderApp(
      '/dashboard?range=30d',
      {
        'GET /api/v1/me': (_url, init) => {
          meCalls++;
          // the first call is the guard; the dev headers must be on it
          expect(headersOf(init)['x-customer-id']).toBe('resistance');
          expect(headersOf(init)['x-roles']).toBeUndefined();
          return jsonResponse(RESISTANCE);
        },
        'GET /api/v1/stats': () => jsonResponse(STATS),
        'GET /api/v1/alerts': () => jsonResponse([]),
      },
      { me: null },
    );

    expect(await screen.findByRole('button', { name: 'Continue' })).toBeInTheDocument();
    expect(screen.getByText(/local development/i)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Customer id'), 'resistance');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(meCalls).toBeGreaterThan(0);
    // the range in the URL survived the round trip through sign-in
    expect(callsTo(fetchMock, 'GET', '/api/v1/stats')[0]).toBeTruthy();
    expect(screen.getByText('Last 30 days', { exact: false })).toBeInTheDocument();
  });

  it('the operator tick adds the role header', async () => {
    const user = userEvent.setup();
    const seen: string[] = [];
    renderApp(
      '/dashboard',
      {
        'GET /api/v1/me': (_url, init) => {
          seen.push(headersOf(init)['x-roles'] ?? '');
          return jsonResponse({ ...RESISTANCE, roles: ['USER', 'OPERATOR'] });
        },
        'GET /api/v1/stats': () => jsonResponse(STATS),
        'GET /api/v1/alerts': () => jsonResponse([]),
      },
      { me: null },
    );

    await screen.findByLabelText('Customer id');
    await user.type(screen.getByLabelText('Customer id'), 'platform');
    await user.click(screen.getByLabelText(/platform operator/i));
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await screen.findByRole('heading', { name: 'Dashboard' });
    expect(seen).toContain('operator');
    expect(screen.getByRole('link', { name: 'Operator' })).toBeInTheDocument();
  });

  it('a blank customer id is refused without a request', async () => {
    const user = userEvent.setup();
    const { fetchMock } = renderApp('/sign-in', {}, { me: null });
    await user.click(await screen.findByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a customer id');
    expect(callsTo(fetchMock, 'GET', '/api/v1/me')).toHaveLength(0);
  });

  it('shows the Cognito door when the gateway enforces auth', async () => {
    renderApp('/dashboard', {}, { me: null, config: COGNITO_CONFIG });
    expect(await screen.findByRole('button', { name: 'Sign in with Cognito' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Customer id')).not.toBeInTheDocument();
  });

  it('a 401 from the API ends the session', async () => {
    renderApp('/dashboard', {
      'GET /api/v1/stats': () => jsonResponse({ error: 'unauthenticated', message: 'expired' }, 401),
    });
    expect(await screen.findByLabelText('Customer id')).toBeInTheDocument();
  });

  it('a stale view-as that now answers 403 is dropped instead of ending the session', async () => {
    let calls = 0;
    renderApp(
      '/dashboard',
      {
        'GET /api/v1/me': (_url, init) => {
          calls++;
          return headersOf(init)['x-acting-customer-id']
            ? jsonResponse({ error: 'forbidden', message: 'only operators may act as another customer' }, 403)
            : jsonResponse(RESISTANCE);
        },
        'GET /api/v1/stats': () => jsonResponse(STATS),
        'GET /api/v1/alerts': () => jsonResponse([]),
      },
      { actingAs: 'acme' },
    );

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(calls).toBe(2);
    expect(sessionStorage.getItem('auditflow.actingAs')).toBeNull();
  });

  it('sign out forgets the dev session', async () => {
    const user = userEvent.setup();
    renderApp('/dashboard', {
      'GET /api/v1/stats': () => jsonResponse(STATS),
      'GET /api/v1/alerts': () => jsonResponse([]),
    });
    await screen.findByRole('heading', { name: 'Dashboard' });
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(screen.getByLabelText('Customer id')).toHaveValue(''));
    expect(localStorage.getItem('auditflow.devSession')).toBeNull();
    vi.useRealTimers();
  });
});
