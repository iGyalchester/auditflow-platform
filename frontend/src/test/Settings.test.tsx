import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { OPERATOR, STATS, jsonResponse, renderApp } from './helpers';

describe('settings and shortcuts', () => {
  it('shows who you are, the sign-in mode and the rate budget from the last response', async () => {
    renderApp(
      '/settings',
      {
        'GET /api/v1/me': () => jsonResponse({ ...OPERATOR, actingAs: null }, 200, { 'X-RateLimit-Remaining': '37' }),
      },
      { me: OPERATOR },
    );

    expect(await screen.findByRole('heading', { name: 'Settings' })).toBeInTheDocument();
    expect(screen.getByText('AuditFlow', { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText('User, Operator')).toBeInTheDocument();
    expect(screen.getByText(/local development headers/)).toBeInTheDocument();
    expect(screen.getByText('37 requests left in the current burst')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Console plan' })).toHaveAttribute('href', expect.stringContaining('CONSOLE.md'));
  });

  it('"?" shows the shortcuts and "g d" jumps to the dashboard with the range', async () => {
    const user = userEvent.setup();
    renderApp('/settings?range=90d', {
      'GET /api/v1/stats': () => jsonResponse(STATS),
      'GET /api/v1/alerts': () => jsonResponse([]),
    });

    await screen.findByRole('heading', { name: 'Settings' });
    await user.keyboard('?');
    expect(await screen.findByRole('dialog', { name: 'Keyboard shortcuts' })).toBeInTheDocument();
    await user.keyboard('{Escape}');

    await user.keyboard('g');
    await user.keyboard('d');
    expect(await screen.findByRole('heading', { name: 'Dashboard' })).toBeInTheDocument();
    expect(screen.getByText('Last 90 days', { exact: false })).toBeInTheDocument();
  });

  it('"/" goes to the audit log and focuses the search box, but not while typing', async () => {
    const user = userEvent.setup();
    renderApp('/settings', {
      'GET /api/v1/audit-logs': () => jsonResponse([]),
    });

    await screen.findByRole('heading', { name: 'Settings' });
    await user.keyboard('/');
    expect(await screen.findByRole('heading', { name: 'Audit log' })).toBeInTheDocument();
    const box = screen.getByLabelText('Search');
    expect(box).toHaveFocus();
    await user.keyboard('g');
    await user.keyboard('d');
    expect(screen.getByRole('heading', { name: 'Audit log' })).toBeInTheDocument();
    expect(box).toHaveValue('gd');
  });
});
