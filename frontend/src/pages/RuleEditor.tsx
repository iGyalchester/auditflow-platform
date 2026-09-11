import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { ApiError, createRule, dryRunRule, updateRule, validateRule } from '../api/client';
import { EVENT_TYPES, RISK_LEVELS, type AlertRule, type AlertRuleRequest, type DryRun, type EventType, type RiskLevel } from '../api/types';
import Dialog from '../components/Dialog';
import RiskBadge from '../components/RiskBadge';
import { useTimeRange } from '../hooks/useTimeRange';
import { formatCount, humanize, relativeTime } from '../util/format';

export const CHANNELS = ['slack', 'email'] as const;

export interface RuleDraftState {
  name: string;
  description: string;
  eventType: EventType | '';
  riskThreshold: RiskLevel | '';
  conditionExpression: string;
  enabled: boolean;
  notificationChannels: string[];
}

export const EMPTY_DRAFT: RuleDraftState = {
  name: '',
  description: '',
  eventType: '',
  riskThreshold: '',
  conditionExpression: '',
  enabled: true,
  notificationChannels: ['slack'],
};

export function draftOf(rule: AlertRule): RuleDraftState {
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

export function toRequest(draft: RuleDraftState): AlertRuleRequest {
  return {
    name: draft.name.trim(),
    description: draft.description.trim() || null,
    eventType: draft.eventType || null,
    riskThreshold: draft.riskThreshold || null,
    conditionExpression: draft.conditionExpression.trim() || null,
    enabled: draft.enabled,
    notificationChannels: draft.notificationChannels,
  };
}

/** What a condition can see, straight from AuditEvent and the evaluator's allow-list. */
const FIELDS: { name: string; type: string; note: string }[] = [
  { name: 'type', type: 'EventType', note: "type.name() == 'DATA_EXPORT'" },
  { name: 'riskLevel', type: 'RiskLevel', note: "riskLevel.name() == 'HIGH'" },
  { name: 'anomalous', type: 'boolean', note: 'anomalous' },
  { name: 'action', type: 'string', note: "action == 'LOGIN_FAILURE'" },
  { name: 'resource', type: 'string', note: "resource.startsWith('customers')" },
  { name: 'userId', type: 'string', note: "userId != null && userId.endsWith('@example.com')" },
  { name: 'sessionId', type: 'string', note: 'sessionId == null' },
  { name: 'ipAddress', type: 'string', note: "ipAddress != null && !ipAddress.startsWith('10.')" },
  { name: 'userAgent', type: 'string', note: "userAgent.contains('curl')" },
  { name: 'location', type: 'string', note: "location != 'office'" },
  { name: 'query', type: 'string', note: "query.toLowerCase().contains('drop')" },
  { name: 'controls', type: 'list', note: 'controls.size() > 0' },
  { name: 'tags', type: 'map', note: "tags.containsKey('pii')" },
];

const METHODS = 'startsWith, endsWith, contains, equals, equalsIgnoreCase, isEmpty, isBlank, length, toLowerCase, toUpperCase, trim; lists: contains, isEmpty, size; maps: containsKey, get, isEmpty, size; enums: name';

const VALIDATE_DEBOUNCE_MS = 400;

/**
 * The rule editor. The condition is validated by the gateway on every
 * pause in typing (the same sandboxed evaluator alerting-service runs,
 * so what passes here runs there), and "Dry run" evaluates the draft
 * over the customer's real events in the current window before anything
 * is saved: "would have matched 12 of 1,284 events" is the difference
 * between a rule and a pager that never stops.
 */
export default function RuleEditor({
  rule,
  initial,
  onClose,
  onSaved,
}: {
  rule: AlertRule | null;
  initial?: RuleDraftState;
  onClose: () => void;
  onSaved: (saved: AlertRule, created: boolean) => void;
}) {
  const { fromIso, toIso, range } = useTimeRange();
  const [draft, setDraft] = useState<RuleDraftState>(initial ?? (rule ? draftOf(rule) : EMPTY_DRAFT));
  const [check, setCheck] = useState<{ valid: boolean; error: string | null } | 'checking' | null>(null);
  const [dryRun, setDryRun] = useState<DryRun | null>(null);
  const [dryRunning, setDryRunning] = useState(false);
  const [dryRunError, setDryRunError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const ids = { name: useId(), description: useId(), type: useId(), risk: useId(), condition: useId(), enabled: useId() };
  const latest = useRef(0);

  // live validation of the condition, debounced; a blank condition is valid
  useEffect(() => {
    const expression = draft.conditionExpression.trim();
    if (!expression) {
      setCheck(null);
      return;
    }
    setCheck('checking');
    const token = ++latest.current;
    const timer = setTimeout(async () => {
      try {
        const result = await validateRule({ conditionExpression: expression });
        if (token === latest.current) setCheck({ valid: result.valid, error: result.error ?? null });
      } catch (e) {
        if (token === latest.current) setCheck({ valid: false, error: e instanceof Error ? e.message : 'Could not validate.' });
      }
    }, VALIDATE_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [draft.conditionExpression]);

  // a change to the criteria invalidates the last dry run
  useEffect(() => {
    setDryRun(null);
  }, [draft.eventType, draft.riskThreshold, draft.conditionExpression]);

  function update<K extends keyof RuleDraftState>(key: K, value: RuleDraftState[K]) {
    setDraft((d) => ({ ...d, [key]: value }));
  }

  function toggleChannel(channel: string) {
    setDraft((d) => ({
      ...d,
      notificationChannels: d.notificationChannels.includes(channel) ? d.notificationChannels.filter((c) => c !== channel) : [...d.notificationChannels, channel],
    }));
  }

  async function runDry() {
    setDryRunning(true);
    setDryRunError(null);
    try {
      setDryRun(await dryRunRule({ eventType: draft.eventType || null, riskThreshold: draft.riskThreshold || null, conditionExpression: draft.conditionExpression.trim() || null }, fromIso, toIso));
    } catch (e) {
      setDryRunError(e instanceof ApiError && e.status === 413 ? `${e.message} (pick a shorter time range at the top of the page)` : e instanceof Error ? e.message : 'Dry run failed.');
    } finally {
      setDryRunning(false);
    }
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    setFieldErrors({});
    if (!draft.name.trim()) {
      setFieldErrors({ name: 'Give the rule a name.' });
      return;
    }
    if (check !== null && check !== 'checking' && !check.valid) {
      setProblem('Fix the condition first.');
      return;
    }
    setSaving(true);
    try {
      const body = toRequest(draft);
      const saved = rule ? await updateRule(rule.ruleId, body) : await createRule(body);
      onSaved(saved, !rule);
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(err.fields);
        setProblem(err.message);
      } else {
        setProblem('Could not save the rule.');
      }
    } finally {
      setSaving(false);
    }
  }

  const conditionState = check === null ? null : check === 'checking' ? 'checking' : check.valid ? 'ok' : 'bad';

  return (
    <Dialog title={rule ? 'Edit rule' : 'New rule'} onClose={onClose} wide>
      <form onSubmit={save} noValidate className="rule-form">
        <div className="rule-main">
          <div className={fieldErrors.name ? 'field field-invalid' : 'field'}>
            <label htmlFor={ids.name}>Name</label>
            <input id={ids.name} value={draft.name} onChange={(e) => update('name', e.target.value)} maxLength={255} autoFocus />
            {fieldErrors.name && (
              <p className="error small" role="alert">
                {fieldErrors.name}
              </p>
            )}
          </div>
          <div className="field">
            <label htmlFor={ids.description}>Description</label>
            <input id={ids.description} value={draft.description} onChange={(e) => update('description', e.target.value)} placeholder="What this catches and who cares" />
          </div>
          <div className="grid-2 tight">
            <div className="field">
              <label htmlFor={ids.type}>Event type</label>
              <select id={ids.type} value={draft.eventType} onChange={(e) => update('eventType', e.target.value as EventType | '')}>
                <option value="">Any type</option>
                {EVENT_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {humanize(t)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor={ids.risk}>Risk at least</label>
              <select id={ids.risk} value={draft.riskThreshold} onChange={(e) => update('riskThreshold', e.target.value as RiskLevel | '')}>
                <option value="">Any risk</option>
                {RISK_LEVELS.map((r) => (
                  <option key={r} value={r}>
                    {humanize(r)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className={`field${conditionState === 'bad' ? ' field-invalid' : ''}`}>
            <label htmlFor={ids.condition}>Condition (optional)</label>
            <textarea
              id={ids.condition}
              rows={3}
              value={draft.conditionExpression}
              onChange={(e) => update('conditionExpression', e.target.value)}
              placeholder="anomalous && resource == 'customers_table'"
              maxLength={512}
              spellCheck={false}
              aria-describedby={`${ids.condition}-state`}
            />
            <p id={`${ids.condition}-state`} className={`small condition-state condition-${conditionState ?? 'idle'}`} aria-live="polite">
              {conditionState === null && 'No condition: the type and risk above decide on their own.'}
              {conditionState === 'checking' && 'Checking…'}
              {conditionState === 'ok' && 'Condition is valid.'}
              {conditionState === 'bad' && check !== null && check !== 'checking' && check.error}
            </p>
          </div>
          <fieldset className="field">
            <legend>Notify</legend>
            {CHANNELS.map((c) => (
              <label key={c} className="check inline" htmlFor={`${ids.enabled}-${c}`}>
                <input id={`${ids.enabled}-${c}`} type="checkbox" checked={draft.notificationChannels.includes(c)} onChange={() => toggleChannel(c)} />
                {c}
              </label>
            ))}
            {fieldErrors.notificationChannels && <p className="error small">{fieldErrors.notificationChannels}</p>}
          </fieldset>
          <label className="check" htmlFor={ids.enabled}>
            <input id={ids.enabled} type="checkbox" checked={draft.enabled} onChange={(e) => update('enabled', e.target.checked)} />
            Enabled
          </label>

          <section className="dry-run">
            <div className="card-title">
              <div>
                <h3>Dry run</h3>
                <p className="muted small">Evaluate this draft over your events in {range.label.toLowerCase()} before saving it.</p>
              </div>
              <button type="button" className="btn" onClick={runDry} disabled={dryRunning || conditionState === 'bad' || conditionState === 'checking'}>
                {dryRunning ? 'Running…' : 'Dry run'}
              </button>
            </div>
            {dryRunError && (
              <p className="error small" role="alert">
                {dryRunError}
              </p>
            )}
            {dryRun && (
              <div role="status">
                <p className="dry-run-result">
                  Would have matched <strong>{formatCount(dryRun.matched)}</strong> of {formatCount(dryRun.scanned)} events.
                  {dryRun.matched === 0 && dryRun.scanned > 0 && <span className="muted"> Nothing: loosen a criterion, or that is the answer.</span>}
                  {dryRun.matched > 0 && dryRun.matched === dryRun.scanned && <span className="muted"> Everything: this rule would page on every event.</span>}
                </p>
                {dryRun.sample.length > 0 && (
                  <table className="sample">
                    <caption className="sr-only">Sample of matching events</caption>
                    <thead>
                      <tr>
                        <th scope="col">When</th>
                        <th scope="col">Action</th>
                        <th scope="col">Resource</th>
                        <th scope="col">Risk</th>
                      </tr>
                    </thead>
                    <tbody>
                      {dryRun.sample.map((s) => (
                        <tr key={s.eventId}>
                          <td className="nowrap" title={s.occurredAt}>
                            {relativeTime(s.occurredAt)}
                          </td>
                          <td>{s.action ?? '—'}</td>
                          <td className="truncate">{s.resource ?? '—'}</td>
                          <td>
                            <RiskBadge level={s.riskLevel} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}
          </section>

          {problem && (
            <p className="error" role="alert">
              {problem}
            </p>
          )}
          <div className="actions">
            <button type="button" className="btn" onClick={onClose} disabled={saving}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={saving || conditionState === 'checking'}>
              {saving ? 'Saving…' : rule ? 'Save changes' : 'Create rule'}
            </button>
          </div>
        </div>

        <aside className="rule-help" aria-label="Condition reference">
          <h3>What a condition can see</h3>
          <p className="muted small">A true/false expression over the event. Read-only: no assignments, constructors or types.</p>
          <dl className="fields-ref">
            {FIELDS.map((f) => (
              <div key={f.name}>
                <dt>
                  <code>{f.name}</code> <span className="muted small">{f.type}</span>
                </dt>
                <dd>
                  <code>{f.note}</code>
                </dd>
              </div>
            ))}
          </dl>
          <h3>Methods allowed</h3>
          <p className="muted small">{METHODS}. Anything else is refused when you save, and here as you type.</p>
        </aside>
      </form>
    </Dialog>
  );
}
