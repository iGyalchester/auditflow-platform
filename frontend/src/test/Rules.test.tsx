import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { AlertRule } from '../api/types';
import { ALERTS, bodyOf, jsonResponse, noContent, renderApp } from './helpers';

const LOGIN: AlertRule = {
  ruleId: 'r-1',
  customerId: 'resistance',
  name: 'Failed login attempt',
  description: 'Someone could not sign in',
  eventType: 'AUTH_EVENT',
  riskThreshold: 'MEDIUM',
  conditionExpression: "action == 'LOGIN_FAILURE'",
  enabled: true,
  notificationChannels: ['slack', 'email'],
};
const EXPORTS: AlertRule = { ...LOGIN, ruleId: 'r-2', name: 'Exports', description: null, eventType: null, riskThreshold: null, conditionExpression: null, enabled: false, notificationChannels: [] };

describe('rules', () => {
  it('lists the rules with their criteria, channels and last firing', async () => {
    renderApp('/rules?range=7d', {
      'GET /api/v1/alert-rules': () => jsonResponse([LOGIN, EXPORTS]),
      'GET /api/v1/alerts': () => jsonResponse(ALERTS),
    });

    const table = await screen.findByRole('table', { name: 'Alert rules' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByText('Failed login attempt')).toBeInTheDocument();
    expect(within(rows[1]).getByText(/Auth event, risk ≥ Medium/)).toBeInTheDocument();
    expect(within(rows[1]).getByText("action == 'LOGIN_FAILURE'")).toBeInTheDocument();
    expect(within(rows[1]).getByText('slack')).toBeInTheDocument();
    expect(within(rows[1]).getByRole('link', { name: /ago|yesterday|just now/ })).toHaveAttribute('href', '/alerts?ruleId=r-1&range=7d');
    expect(within(rows[1]).getByLabelText('Failed login attempt enabled')).toBeChecked();
    expect(within(rows[2]).getByText('Any type')).toBeInTheDocument();
    expect(within(rows[2]).getByText('nobody')).toBeInTheDocument();
    expect(within(rows[2]).getByText('not recently')).toBeInTheDocument();
    expect(within(rows[2]).getByLabelText('Exports enabled')).not.toBeChecked();
  });

  it('toggles a rule in place with the full rule in the PUT, and rolls back when it fails', async () => {
    const user = userEvent.setup();
    let sent: unknown = null;
    let fail = false;
    renderApp('/rules', {
      'GET /api/v1/alert-rules': () => jsonResponse([LOGIN]),
      'GET /api/v1/alerts': () => jsonResponse([]),
      'PUT /api/v1/alert-rules/r-1': (_url, init) => {
        sent = bodyOf(init);
        return fail ? jsonResponse({ error: 'internal', message: 'boom' }, 500) : jsonResponse({ ...LOGIN, enabled: false });
      },
    });

    const toggle = await screen.findByLabelText('Failed login attempt enabled');
    await user.click(toggle);
    await waitFor(() =>
      expect(sent).toEqual({
        name: 'Failed login attempt',
        description: 'Someone could not sign in',
        eventType: 'AUTH_EVENT',
        riskThreshold: 'MEDIUM',
        conditionExpression: "action == 'LOGIN_FAILURE'",
        enabled: false,
        notificationChannels: ['slack', 'email'],
      }),
    );
    expect(await screen.findByText('Failed login attempt disabled')).toBeInTheDocument();
    expect(screen.getByLabelText('Failed login attempt enabled')).not.toBeChecked();

    fail = true;
    await user.click(screen.getByLabelText('Failed login attempt enabled'));
    expect(await screen.findByText(/Could not update Failed login attempt/)).toBeInTheDocument();
    expect(screen.getByLabelText('Failed login attempt enabled')).not.toBeChecked();
  });

  it('the editor validates the condition as you type, dry-runs it, and saves the exact request', async () => {
    const user = userEvent.setup();
    let created: unknown = null;
    const validations: string[] = [];
    renderApp('/rules?range=7d', {
      'GET /api/v1/alert-rules': () => jsonResponse([]),
      'GET /api/v1/alerts': () => jsonResponse([]),
      'POST /api/v1/alert-rules/validate': (_url, init) => {
        const body = bodyOf(init) as { conditionExpression: string };
        validations.push(body.conditionExpression);
        return body.conditionExpression.includes('T(') ? jsonResponse({ valid: false, error: 'condition is not a valid expression over an event: type references are not allowed' }) : jsonResponse({ valid: true });
      },
      'POST /api/v1/alert-rules/dry-run': (url) =>
        jsonResponse({
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          scanned: 1284,
          matched: 12,
          sample: [{ eventId: 'e-1', userId: 'boris', sessionId: null, occurredAt: '2026-09-07T10:00:00Z', eventType: 'AUTH_EVENT', resource: 'login', action: 'LOGIN_FAILURE', riskLevel: 'HIGH', anomalous: false, controls: null }],
        }),
      'POST /api/v1/alert-rules': (_url, init) => {
        created = bodyOf(init);
        return jsonResponse({ ...LOGIN, ruleId: 'r-new', name: 'Bad logins', description: null, notificationChannels: ['email'] }, 201);
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Create the first one' }));
    const dialog = await screen.findByRole('dialog', { name: 'New rule' });
    await user.type(within(dialog).getByLabelText('Name'), 'Bad logins');
    await user.selectOptions(within(dialog).getByLabelText('Event type'), 'AUTH_EVENT');
    await user.selectOptions(within(dialog).getByLabelText('Risk at least'), 'HIGH');

    const condition = within(dialog).getByLabelText('Condition (optional)');
    await user.type(condition, 'T(java.lang.Runtime)');
    expect(await within(dialog).findByText(/type references are not allowed/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Dry run' })).toBeDisabled();

    await user.clear(condition);
    await user.type(condition, "action == 'LOGIN_FAILURE'");
    expect(await within(dialog).findByText('Condition is valid.')).toBeInTheDocument();
    // debounced: one request per pause, not per keystroke
    expect(validations.length).toBeLessThan(10);

    await user.click(within(dialog).getByRole('button', { name: 'Dry run' }));
    expect(await within(dialog).findByText(/Would have matched/)).toHaveTextContent('Would have matched 12 of 1,284 events.');
    expect(within(dialog).getByRole('table', { name: 'Sample of matching events' })).toBeInTheDocument();
    expect(within(dialog).getByText('LOGIN_FAILURE')).toBeInTheDocument();

    await user.click(within(dialog).getByLabelText('slack'));
    await user.click(within(dialog).getByLabelText('email'));
    await user.click(within(dialog).getByRole('button', { name: 'Create rule' }));

    await waitFor(() =>
      expect(created).toEqual({
        name: 'Bad logins',
        description: null,
        eventType: 'AUTH_EVENT',
        riskThreshold: 'HIGH',
        conditionExpression: "action == 'LOGIN_FAILURE'",
        enabled: true,
        notificationChannels: ['email'],
      }),
    );
    expect(await screen.findByText(/Bad logins created/)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Alert rules' })).toHaveTextContent('Bad logins');
  });

  it('server-side validation lands on the field, and a name is required', async () => {
    const user = userEvent.setup();
    renderApp('/rules', {
      'GET /api/v1/alert-rules': () => jsonResponse([]),
      'GET /api/v1/alerts': () => jsonResponse([]),
      'POST /api/v1/alert-rules': () => jsonResponse({ error: 'validation', message: 'some fields are invalid', fields: { name: 'size must be between 0 and 255' } }, 400),
    });

    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    const dialog = await screen.findByRole('dialog', { name: 'New rule' });
    await user.click(within(dialog).getByRole('button', { name: 'Create rule' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Give the rule a name.');

    await user.type(within(dialog).getByLabelText('Name'), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Create rule' }));
    expect(await within(dialog).findByText('size must be between 0 and 255')).toBeInTheDocument();
    expect(within(dialog).getByText('some fields are invalid')).toBeInTheDocument();
  });

  it('opens pre-filled from the explorer, and edits send a PUT', async () => {
    const user = userEvent.setup();
    let updated: unknown = null;
    renderApp("/rules?new=1&eventType=DATA_EXPORT&riskThreshold=CRITICAL&condition=action%20%3D%3D%20'EXPORT'&name=Export%20on%20customers_table&range=30d", {
      'GET /api/v1/alert-rules': () => jsonResponse([LOGIN]),
      'GET /api/v1/alerts': () => jsonResponse([]),
      'POST /api/v1/alert-rules/validate': () => jsonResponse({ valid: true }),
      'PUT /api/v1/alert-rules/r-1': (_url, init) => {
        updated = bodyOf(init);
        return jsonResponse({ ...LOGIN, name: 'Failed login attempt (renamed)' });
      },
    });

    const dialog = await screen.findByRole('dialog', { name: 'New rule' });
    expect(within(dialog).getByLabelText('Name')).toHaveValue('Export on customers_table');
    expect(within(dialog).getByLabelText('Event type')).toHaveValue('DATA_EXPORT');
    expect(within(dialog).getByLabelText('Risk at least')).toHaveValue('CRITICAL');
    expect(within(dialog).getByLabelText('Condition (optional)')).toHaveValue("action == 'EXPORT'");
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Edit' }));
    const edit = await screen.findByRole('dialog', { name: 'Edit rule' });
    await user.type(within(edit).getByLabelText('Name'), ' (renamed)');
    // the existing condition is re-checked on open; Save waits for the verdict
    expect(await within(edit).findByText('Condition is valid.')).toBeInTheDocument();
    await user.click(within(edit).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect((updated as { name: string }).name).toBe('Failed login attempt (renamed)'));
    expect(await screen.findByText('Failed login attempt (renamed) saved.')).toBeInTheDocument();
  });

  it('delete asks first and explains that history keeps the alerts', async () => {
    const user = userEvent.setup();
    let deleted = false;
    renderApp('/rules', {
      'GET /api/v1/alert-rules': () => jsonResponse([LOGIN]),
      'GET /api/v1/alerts': () => jsonResponse(ALERTS),
      'DELETE /api/v1/alert-rules/r-1': () => {
        deleted = true;
        return noContent();
      },
    });

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete this rule?' });
    expect(within(dialog).getByText(/It has fired before/)).toBeInTheDocument();
    expect(within(dialog).getByText(/stay in the history as evidence/)).toBeInTheDocument();
    expect(deleted).toBe(false);
    await user.click(within(dialog).getByRole('button', { name: 'Delete rule' }));
    await waitFor(() => expect(deleted).toBe(true));
    expect(await screen.findByText('No rules yet')).toBeInTheDocument();
  });
});
