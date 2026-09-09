import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { callsTo, jsonResponse, renderApp } from './helpers';

const SOC2 = { framework: 'SOC2', from: '2026-09-01T00:00:00Z', to: '2026-09-08T00:00:00Z', events: 42, byControl: { 'AC-2': 40, 'IA-2': 12 }, byRisk: { MEDIUM: 40, CRITICAL: 2 }, byType: { AUTH_EVENT: 42 } };
const GDPR = { ...SOC2, framework: 'GDPR', events: 0, byControl: {}, byRisk: {}, byType: {} };
const REPORT_TEXT = 'SOC 2 Evidence Report\nCustomer: resistance\nPeriod: a - b\nEvents: 42\n' + Array.from({ length: 42 }, (_, i) => `evt-${i} | AUTH_EVENT | controls=AC-2 | risk=MEDIUM`).join('\n');

describe('reports', () => {
  it('shows one card per framework with the summary, and explains an empty one', async () => {
    const { fetchMock } = renderApp('/reports?from=2026-09-01T00:00:00.000Z&to=2026-09-08T00:00:00.000Z', {
      'GET /api/v1/reports': () => jsonResponse(['gdpr', 'hipaa', 'soc2']),
      'GET /api/v1/reports/soc2/summary': () => jsonResponse(SOC2),
      'GET /api/v1/reports/gdpr/summary': () => jsonResponse(GDPR),
      'GET /api/v1/reports/hipaa/summary': () => jsonResponse({ ...GDPR, framework: 'HIPAA' }),
    });

    const soc2 = await screen.findByRole('region', { name: 'SOC 2' });
    expect(await within(soc2).findByText('42', { selector: '.stat-value' })).toBeInTheDocument();
    expect(within(soc2).getByRole('table', { name: 'By control' })).toHaveTextContent('AC-2');
    expect(within(soc2).getByRole('table', { name: 'By risk' })).toHaveTextContent('Critical');
    expect(await screen.findByText('No GDPR evidence in this window')).toBeInTheDocument();
    const summary = callsTo(fetchMock, 'GET', '/api/v1/reports/soc2/summary')[0];
    expect(summary.searchParams.get('from')).toBe('2026-09-01T00:00:00.000Z');
  });

  it('previews the first lines and downloads the file the gateway names', async () => {
    const user = userEvent.setup();
    const createObjectURL = vi.fn(() => 'blob:report');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const clicked: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(this.download);
    });

    renderApp('/reports', {
      'GET /api/v1/reports': () => jsonResponse(['soc2']),
      'GET /api/v1/reports/soc2/summary': () => jsonResponse(SOC2),
      'GET /api/v1/reports/soc2': () =>
        new Response(REPORT_TEXT, { status: 200, headers: { 'Content-Type': 'text/plain', 'Content-Disposition': 'attachment; filename="soc2-resistance-2026-09-01.txt"; filename*=UTF-8\'\'soc2-resistance-2026-09-01.txt' } }),
    });

    const card = await screen.findByRole('region', { name: 'SOC 2' });
    await within(card).findByText('42', { selector: '.stat-value' });
    await user.click(within(card).getByRole('button', { name: 'Preview' }));
    const preview = await screen.findByLabelText('SOC 2 report preview');
    expect(preview.textContent?.split('\n')[0]).toBe('SOC 2 Evidence Report');
    expect(preview).toHaveTextContent('34 more lines');

    await user.click(within(card).getByRole('button', { name: 'Download .txt' }));
    await waitFor(() => expect(clicked).toEqual(['soc2-resistance-2026-09-01.txt']));
    expect(createObjectURL).toHaveBeenCalled();
    click.mockRestore();
  });

  it('a window with too many events is explained, not retried', async () => {
    renderApp('/reports', {
      'GET /api/v1/reports': () => jsonResponse(['soc2']),
      'GET /api/v1/reports/soc2/summary': () => jsonResponse({ error: 'too_many_events', message: 'window has more than 10000 SOC2 events; narrow it' }, 413),
    });

    const card = await screen.findByRole('region', { name: 'SOC 2' });
    expect(await within(card).findByRole('alert')).toHaveTextContent(/Narrow the time range/);
    expect(within(card).getByRole('button', { name: 'Download .txt' })).toBeDisabled();
  });
});
