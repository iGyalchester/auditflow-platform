import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { deleteRule, fetchAlerts, fetchRules, updateRule } from '../api/client';
import type { AlertRow, AlertRule, EventType, RiskLevel } from '../api/types';
import ConfirmDialog from '../components/ConfirmDialog';
import EmptyState from '../components/EmptyState';
import ErrorBanner from '../components/ErrorBanner';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';
import { useAsync } from '../hooks/useAsync';
import { humanize, relativeTime } from '../util/format';
import { rangeQuery } from '../util/timeRange';
import RuleEditor, { EMPTY_DRAFT, toRequest, type RuleDraftState } from './RuleEditor';

const LAST_FIRED_SAMPLE = 500;

/**
 * A customer's alert rules: enable or disable in place, edit in a
 * dialog with live validation and a dry run, delete with a warning that
 * history keeps the alerts the rule raised. "Last fired" comes from the
 * alert feed rather than a per-rule query: the newest few hundred alerts
 * name every rule that has fired recently.
 */
export default function RulesPage() {
  const [params, setParams] = useSearchParams();
  const link = rangeQuery(params);
  const { notify } = useToast();
  const rules = useAsync<AlertRule[]>(fetchRules, []);
  const recent = useAsync<AlertRow[]>(() => fetchAlerts({ limit: LAST_FIRED_SAMPLE }), []);
  const [editing, setEditing] = useState<AlertRule | null>(null);
  const [creating, setCreating] = useState<RuleDraftState | null>(null);
  const [deleting, setDeleting] = useState<AlertRule | null>(null);

  // the explorer's "Create rule from this event" arrives as ?new=1&eventType=…
  useEffect(() => {
    if (params.get('new') !== '1') return;
    setCreating({
      ...EMPTY_DRAFT,
      name: params.get('name') ?? '',
      eventType: (params.get('eventType') as EventType | null) ?? '',
      riskThreshold: (params.get('riskThreshold') as RiskLevel | null) ?? '',
      conditionExpression: params.get('condition') ?? '',
    });
    const next = new URLSearchParams(params);
    for (const key of ['new', 'name', 'eventType', 'riskThreshold', 'condition']) next.delete(key);
    setParams(next, { replace: true });
  }, [params, setParams]);

  const lastFired = useMemo(() => {
    const map = new Map<string, string>();
    for (const a of recent.data ?? []) {
      if (a.ruleId && !map.has(a.ruleId)) map.set(a.ruleId, a.triggeredAt);
    }
    return map;
  }, [recent.data]);

  async function toggle(rule: AlertRule) {
    const flipped = { ...rule, enabled: !rule.enabled };
    rules.setData((list) => (list ? list.map((r) => (r.ruleId === rule.ruleId ? flipped : r)) : list));
    try {
      const saved = await updateRule(rule.ruleId, toRequest({ ...draftOfRule(rule), enabled: !rule.enabled }));
      rules.setData((list) => (list ? list.map((r) => (r.ruleId === rule.ruleId ? saved : r)) : list));
      notify(`${rule.name} ${saved.enabled ? 'enabled' : 'disabled'}`);
    } catch {
      rules.setData((list) => (list ? list.map((r) => (r.ruleId === rule.ruleId ? rule : r)) : list));
      notify(`Could not update ${rule.name}. Try again.`, 'error');
    }
  }

  function onSaved(saved: AlertRule, created: boolean) {
    rules.setData((list) => {
      if (!list) return [saved];
      return created ? [...list, saved].sort((a, b) => a.name.localeCompare(b.name)) : list.map((r) => (r.ruleId === saved.ruleId ? saved : r));
    });
    setEditing(null);
    setCreating(null);
    notify(created ? `${saved.name} created. Alerting picks it up within 30 seconds.` : `${saved.name} saved.`);
  }

  async function remove(rule: AlertRule) {
    await deleteRule(rule.ruleId);
    rules.setData((list) => (list ? list.filter((r) => r.ruleId !== rule.ruleId) : list));
    setDeleting(null);
    notify(`${rule.name} deleted.`);
  }

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Rules</h1>
          <p className="muted">What raises an alert. Changes reach alerting within 30 seconds.</p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating({ ...EMPTY_DRAFT })}>
          New rule
        </button>
      </div>

      {rules.error && <ErrorBanner message={rules.error} onRetry={rules.reload} />}
      {!rules.error && rules.loading && <Skeleton rows={3} />}

      {rules.data && rules.data.length === 0 && (
        <section className="card">
          <EmptyState
            title="No rules yet"
            action={
              <button type="button" className="btn btn-primary" onClick={() => setCreating({ ...EMPTY_DRAFT })}>
                Create the first one
              </button>
            }
          >
            Nothing fires until a rule says what matters. Start with failed logins or exports.
          </EmptyState>
        </section>
      )}

      {rules.data && rules.data.length > 0 && (
        <section className="card table-card">
          <table>
            <caption className="sr-only">Alert rules</caption>
            <thead>
              <tr>
                <th scope="col">Enabled</th>
                <th scope="col">Rule</th>
                <th scope="col">Matches</th>
                <th scope="col">Notify</th>
                <th scope="col">Last fired</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rules.data.map((r) => {
                const fired = lastFired.get(r.ruleId);
                return (
                  <tr key={r.ruleId} className={r.enabled ? '' : 'row-disabled'}>
                    <td>
                      <label className="switch">
                        <input type="checkbox" checked={r.enabled} onChange={() => toggle(r)} aria-label={`${r.name} enabled`} />
                        <span aria-hidden="true" />
                      </label>
                    </td>
                    <td>
                      <div className="rule-name">{r.name}</div>
                      {r.description && <div className="muted small">{r.description}</div>}
                    </td>
                    <td className="small">
                      {r.eventType ? humanize(r.eventType) : 'Any type'}
                      {r.riskThreshold ? `, risk ≥ ${humanize(r.riskThreshold)}` : ''}
                      {r.conditionExpression && (
                        <div>
                          <code className="condition">{r.conditionExpression}</code>
                        </div>
                      )}
                    </td>
                    <td>
                      {r.notificationChannels.length === 0 && <span className="muted small">nobody</span>}
                      {r.notificationChannels.map((c) => (
                        <span key={c} className="chip-static">
                          {c}
                        </span>
                      ))}
                    </td>
                    <td className="nowrap small">
                      {fired ? (
                        <Link to={`/alerts?ruleId=${encodeURIComponent(r.ruleId)}${link.replace('?', '&')}`} title={fired}>
                          {relativeTime(fired)}
                        </Link>
                      ) : (
                        <span className="muted">{recent.loading ? '…' : 'not recently'}</span>
                      )}
                    </td>
                    <td className="row-actions">
                      <button type="button" className="btn btn-ghost" onClick={() => setEditing(r)}>
                        Edit
                      </button>
                      <button type="button" className="btn btn-ghost danger" onClick={() => setDeleting(r)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      )}

      {editing && <RuleEditor rule={editing} onClose={() => setEditing(null)} onSaved={onSaved} />}
      {creating && <RuleEditor rule={null} initial={creating} onClose={() => setCreating(null)} onSaved={onSaved} />}
      {deleting && (
        <ConfirmDialog title="Delete this rule?" confirmLabel="Delete rule" onConfirm={() => remove(deleting)} onClose={() => setDeleting(null)}>
          <p>
            <strong>{deleting.name}</strong> will stop firing within 30 seconds.
          </p>
          <p className="muted small">
            {lastFired.has(deleting.ruleId) ? 'It has fired before. ' : ''}
            Alerts it raised stay in the history as evidence, shown as "Deleted rule".
          </p>
        </ConfirmDialog>
      )}
    </>
  );
}

function draftOfRule(rule: AlertRule): RuleDraftState {
  return {
    name: rule.name,
    description: rule.description ?? '',
    eventType: rule.eventType ?? '',
    riskThreshold: rule.riskThreshold ?? '',
    conditionExpression: rule.conditionExpression ?? '',
    enabled: rule.enabled,
    notificationChannels: rule.notificationChannels,
  };
}
