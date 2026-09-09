import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ALERTS, callsTo, jsonResponse, renderApp } from './helpers';

const RULES = [
  { ruleId: 'r-1', customerId: 'resistance', name: 'Failed login attempt', description: null, eventType: 'AUTH_EVENT', riskThreshold: null, conditionExpression: null, enabled: true, notificationChannels: ['slack', 'email'] },
];

describe('alerts feed', () => {
  it('lists the window with what was delivered and what was not', async () => {
    renderApp('/alerts?range=7d', {
      'GET /api/v1/alerts': () => jsonResponse(ALERTS),
      'GET /api/v1/alert-rules': () => jsonResponse(RULES),
    });

    const table = await screen.findByRole('table', { name: 'Alerts, newest first' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByText('Failed login attempt')).toBeInTheDocument();
    expect(within(rows[1]).getByText('slack')).toBeInTheDocument();
    expect(within(rows[1]).getByText('not delivered: email')).toBeInTheDocument();
    expect(within(rows[1]).getByRole('link', { name: 'evt-9' })).toHaveAttribute('href', '/audit-log?event=evt-9&range=7d');
    expect(within(rows[2]).getByText('Deleted rule')).toBeInTheDocument();
    expect(within(rows[2]).getByText('nothing')).toBeInTheDocument();
    expect(within(rows[2]).queryByText(/not delivered:/)).not.toBeInTheDocument();
  });

  it('filters by rule through the URL', async () => {
    const user = userEvent.setup();
    const { fetchMock } = renderApp('/alerts', {
      'GET /api/v1/alerts': () => jsonResponse(ALERTS),
      'GET /api/v1/alert-rules': () => jsonResponse(RULES),
    });

    await screen.findByRole('table');
    await user.selectOptions(await screen.findByLabelText('Rule'), 'r-1');
    await waitFor(() => {
      const calls = callsTo(fetchMock, 'GET', '/api/v1/alerts');
      expect(calls[calls.length - 1].searchParams.get('ruleId')).toBe('r-1');
    });
  });

  it('the detail page shows the delivery picture and the event, or that it was purged', async () => {
    renderApp('/alerts/al-1?range=7d', {
      'GET /api/v1/alerts/al-1': () =>
        jsonResponse({
          alert: ALERTS[0],
          event: { eventId: 'evt-9', userId: 'boris', sessionId: null, occurredAt: '2026-09-04T10:00:00Z', eventType: 'AUTH_EVENT', resource: 'login', action: 'LOGIN_FAILURE', riskLevel: 'MEDIUM', anomalous: false, controls: 'SOC2:AC-2' },
          configuredChannels: ['slack', 'email'],
          notifiedChannels: ['slack'],
          undeliveredChannels: ['email'],
        }),
    });

    expect(await screen.findByRole('heading', { name: 'Failed login attempt' })).toBeInTheDocument();
    expect(screen.getByText('slack, email')).toBeInTheDocument();
    expect(screen.getByText('email', { selector: '.badge-warn' })).toBeInTheDocument();
    expect(screen.getByText('LOGIN_FAILURE')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'evt-9' })).toHaveAttribute('href', '/audit-log?event=evt-9&range=7d');
    expect(screen.getByRole('link', { name: '← All alerts' })).toHaveAttribute('href', '/alerts?range=7d');
  });

  it('a purged event and an unknown alert are both handled', async () => {
    renderApp('/alerts/al-2', {
      'GET /api/v1/alerts/al-2': () => jsonResponse({ alert: ALERTS[1], event: null, configuredChannels: [], notifiedChannels: [], undeliveredChannels: [] }),
    });
    expect(await screen.findByText(/retention has purged it/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Deleted rule' })).toBeInTheDocument();
  });

  it('an unknown alert is a 404 message', async () => {
    renderApp('/alerts/nope', {
      'GET /api/v1/alerts/nope': () => jsonResponse({ error: 'not_found', message: 'no such alert' }, 404),
    });
    expect(await screen.findByRole('alert')).toHaveTextContent('no such alert');
  });
});
