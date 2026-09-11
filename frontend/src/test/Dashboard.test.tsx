import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ALERTS, EMPTY_STATS, OPERATOR, STATS, callsTo, headersOf, jsonResponse, renderApp } from './helpers';

describe('dashboard', () => {
  it('shows the tiles with deltas, every chart, the tables and recent alerts', async () => {
    renderApp('/dashboard?range=7d', {
      'GET /api/v1/stats': () => jsonResponse(STATS),
      'GET /api/v1/alerts': () => jsonResponse(ALERTS),
    });

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();

    const events = await screen.findByText('Events', { selector: '.stat-label' });
    expect(events.parentElement).toHaveTextContent('1,284');
    expect(events.parentElement).toHaveTextContent('+17%');
    const alerts = screen.getByText('Alerts', { selector: '.stat-label' });
    expect(alerts.parentElement).toHaveTextContent('−40%');
    const critical = screen.getByText('Critical', { selector: '.stat-label' });
    expect(critical.parentElement).toHaveTextContent('new');

    expect(screen.getByRole('img', { name: /events per day over 7 days: 910 events, 3 critical/i })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /events by type: Auth event 900, Database query 300, Data export 84/i })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /alerts per day over 7 days: 11 alerts/i })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /controls coverage: SOC2:AC-2 900/i })).toBeInTheDocument();

    expect(screen.getByText('boris@example.com')).toBeInTheDocument();
    expect(screen.getByText('login')).toBeInTheDocument();

    expect(screen.getByRole('link', { name: 'Failed login attempt' })).toHaveAttribute('href', '/alerts/al-1?range=7d');
    expect(screen.getByText('Deleted rule')).toBeInTheDocument();
    expect(screen.getByText(/not delivered/)).toBeInTheDocument();
    expect(screen.queryByText('null')).not.toBeInTheDocument();
    expect(screen.queryByText('undefined')).not.toBeInTheDocument();
  });

  it('asks for the window in the URL and re-asks when a preset changes it', async () => {
    const user = userEvent.setup();
    const { fetchMock } = renderApp('/dashboard?from=2026-09-01T00:00:00.000Z&to=2026-09-08T00:00:00.000Z', {
      'GET /api/v1/stats': () => jsonResponse(STATS),
      'GET /api/v1/alerts': () => jsonResponse(ALERTS),
    });

    await screen.findByRole('heading', { name: 'Dashboard' });
    await waitFor(() => expect(callsTo(fetchMock, 'GET', '/api/v1/stats')).toHaveLength(1));
    const first = callsTo(fetchMock, 'GET', '/api/v1/stats')[0];
    expect(first.searchParams.get('from')).toBe('2026-09-01T00:00:00.000Z');
    expect(first.searchParams.get('to')).toBe('2026-09-08T00:00:00.000Z');
    expect(screen.getByRole('button', { name: /Sep 1 – Sep 8/ })).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByRole('button', { name: '24h' }));

    await waitFor(() => expect(callsTo(fetchMock, 'GET', '/api/v1/stats')).toHaveLength(2));
    const second = callsTo(fetchMock, 'GET', '/api/v1/stats')[1];
    const from = new Date(second.searchParams.get('from')!);
    const to = new Date(second.searchParams.get('to')!);
    expect(to.getTime() - from.getTime()).toBe(24 * 3_600_000);
    expect(screen.getByText('Last 24 hours', { exact: false })).toBeInTheDocument();
    // the sidebar links carry the window along
    expect(screen.getByRole('link', { name: 'Audit log' })).toHaveAttribute('href', '/audit-log?range=24h');
  });

  it('can switch a chart to its table view', async () => {
    const user = userEvent.setup();
    renderApp('/dashboard', {
      'GET /api/v1/stats': () => jsonResponse(STATS),
      'GET /api/v1/alerts': () => jsonResponse([]),
    });

    await screen.findByRole('img', { name: /events per day/i });
    await user.click(screen.getAllByRole('button', { name: 'View as table' })[0]);

    const table = screen.getByRole('table', { name: 'Events per day by risk level' });
    expect(within(table).getByText('Critical')).toBeInTheDocument();
    expect(within(table).getAllByRole('row')).toHaveLength(8);
    expect(screen.queryByRole('img', { name: /events per day/i })).not.toBeInTheDocument();
  });

  it('a quiet window shows the empty states, never a broken chart', async () => {
    renderApp('/dashboard', {
      'GET /api/v1/stats': () => jsonResponse(EMPTY_STATS),
      'GET /api/v1/alerts': () => jsonResponse([]),
    });

    expect(await screen.findByText('Nothing arrived in this window')).toBeInTheDocument();
    expect(screen.getByText('No events in this window')).toBeInTheDocument();
    // once for the chart, once for the recent-alerts card
    expect(screen.getAllByText('No alerts in this window', { selector: '.empty-title' })).toHaveLength(2);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('a failed load shows the gateway message and retries', async () => {
    const user = userEvent.setup();
    let calls = 0;
    renderApp('/dashboard', {
      'GET /api/v1/stats': () => (calls++ === 0 ? jsonResponse({ error: 'bad_request', message: 'window is longer than 366 days; narrow it' }, 400) : jsonResponse(STATS)),
      'GET /api/v1/alerts': () => jsonResponse([]),
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('window is longer than 366 days');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('1,284')).toBeInTheDocument();
  });

  it('an operator acting as a customer sees the banner and every request carries the header', async () => {
    const user = userEvent.setup();
    const acting: (string | undefined)[] = [];
    renderApp(
      '/dashboard',
      {
        'GET /api/v1/me': (_url, init) => jsonResponse({ ...OPERATOR, actingAs: headersOf(init)['x-acting-customer-id'] ?? null, actingAsName: 'Resistance' }),
        'GET /api/v1/stats': (_url, init) => {
          acting.push(headersOf(init)['x-acting-customer-id']);
          return jsonResponse(STATS);
        },
        'GET /api/v1/alerts': () => jsonResponse([]),
      },
      { me: OPERATOR, actingAs: 'resistance' },
    );

    const banner = await screen.findByText(/Viewing/);
    expect(banner).toHaveTextContent('Viewing Resistance as an operator');
    await waitFor(() => expect(acting).toContain('resistance'));

    await user.click(screen.getByRole('button', { name: 'Back to my view' }));
    await waitFor(() => expect(screen.queryByText(/Viewing Resistance/)).not.toBeInTheDocument());
    expect(acting[acting.length - 1]).toBeUndefined();
  });
});
