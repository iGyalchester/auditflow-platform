import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { OperatorCustomer, PlatformStats } from '../api/types';
import { OPERATOR, RESISTANCE, STATS, headersOf, jsonResponse, renderApp } from './helpers';

const CUSTOMERS: OperatorCustomer[] = [
  { customerId: 'acme', name: 'Acme Corp', events24h: 3, events7d: 40, alerts7d: 2, rules: 5, lastEventAt: '2026-09-08T10:00:00Z' },
  { customerId: 'resistance', name: null, events24h: 9, events7d: 7, alerts7d: 1, rules: 1, lastEventAt: '2026-09-08T09:00:00Z' },
  { customerId: 'quiet', name: 'Quiet Co', events24h: 0, events7d: 0, alerts7d: 0, rules: 0, lastEventAt: null },
];

const PLATFORM: PlatformStats = {
  window: { from: '2026-09-01T00:00:00Z', to: '2026-09-08T00:00:00Z' },
  totals: { events: 47, alerts: 3, customers: 2 },
  perDay: ['2026-09-01', '2026-09-02'].map((day, i) => ({ day, events: i === 0 ? 40 : 7, alerts: i, eventsByCustomer: (i === 0 ? { acme: 40 } : { resistance: 7 }) as Record<string, number> })),
  topCustomers: [
    { customerId: 'acme', name: 'Acme Corp', events: 40, alerts: 2 },
    { customerId: 'resistance', name: null, events: 7, alerts: 1 },
  ],
  byCustomer: { acme: 'Acme Corp', resistance: null },
};

describe('operator', () => {
  it('shows the platform and every customer, sortable and searchable', async () => {
    const user = userEvent.setup();
    renderApp(
      '/operator?range=7d',
      {
        'GET /api/v1/operator/stats': () => jsonResponse(PLATFORM),
        'GET /api/v1/operator/customers': () => jsonResponse(CUSTOMERS),
      },
      { me: OPERATOR },
    );

    expect(await screen.findByRole('heading', { name: 'Operator' })).toBeInTheDocument();
    expect((await screen.findByText('Active customers', { selector: '.stat-label' })).parentElement).toHaveTextContent('2');
    expect(screen.getByRole('img', { name: /events per day by customer over 2 days: 47 events from 2 customers/i })).toBeInTheDocument();

    const table = await screen.findByRole('table', { name: 'Customers' });
    let rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(4);
    expect(within(rows[1]).getByText('Acme Corp')).toBeInTheDocument();
    expect(within(rows[2]).getByText('not registered')).toBeInTheDocument();
    expect(within(rows[3]).getByText('never')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Events 24h' }));
    rows = within(table).getAllByRole('row');
    expect(within(rows[1]).getByText('resistance', { selector: '.rule-name' })).toBeInTheDocument();

    await user.type(screen.getByLabelText('Find a customer'), 'quiet');
    rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(2);
    expect(within(rows[1]).getByText('Quiet Co')).toBeInTheDocument();
  });

  it('"view as" switches the console to that customer and lands on their dashboard', async () => {
    const user = userEvent.setup();
    const acting: (string | undefined)[] = [];
    renderApp(
      '/operator?range=30d',
      {
        'GET /api/v1/me': (_url, init) => jsonResponse({ ...OPERATOR, actingAs: headersOf(init)['x-acting-customer-id'] ?? null, actingAsName: headersOf(init)['x-acting-customer-id'] ? 'Acme Corp' : null }),
        'GET /api/v1/operator/stats': () => jsonResponse(PLATFORM),
        'GET /api/v1/operator/customers': () => jsonResponse(CUSTOMERS),
        'GET /api/v1/stats': (_url, init) => {
          acting.push(headersOf(init)['x-acting-customer-id']);
          return jsonResponse(STATS);
        },
        'GET /api/v1/alerts': () => jsonResponse([]),
      },
      { me: OPERATOR },
    );

    await screen.findByRole('table', { name: 'Customers' });
    await user.click(screen.getAllByRole('button', { name: 'View as' })[0]);

    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(await screen.findByText(/Viewing/)).toHaveTextContent('Viewing Acme Corp as an operator');
    await waitFor(() => expect(acting).toContain('acme'));
    expect(screen.getByText('Last 30 days', { exact: false })).toBeInTheDocument();
    expect(sessionStorage.getItem('auditflow.actingAs')).toBe('"acme"');
  });

  it('a plain user is told the page is not for them', async () => {
    renderApp('/operator', {}, { me: RESISTANCE });
    expect(await screen.findByText('Not allowed')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Operator' })).not.toBeInTheDocument();
  });
});
