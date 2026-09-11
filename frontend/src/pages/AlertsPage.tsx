import { useEffect, useId, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { fetchAlerts, fetchRules } from '../api/client';
import type { AlertRow, AlertRule } from '../api/types';
import EmptyState from '../components/EmptyState';
import ErrorBanner from '../components/ErrorBanner';
import Skeleton from '../components/Skeleton';
import { useAsync } from '../hooks/useAsync';
import { useTimeRange } from '../hooks/useTimeRange';
import { channels, undelivered } from '../util/channels';
import { formatDateTime, relativeTime } from '../util/format';
import { rangeQuery } from '../util/timeRange';

const PAGE_SIZE = 50;

/**
 * Every rule that fired in the window, newest first, with where the
 * notification actually went. A rule that names two channels and reached
 * one is the interesting case, so the missing channel is called out on
 * the row rather than left for someone to notice in the detail.
 */
export default function AlertsPage() {
  const [params, setParams] = useSearchParams();
  const { range, fromIso, toIso } = useTimeRange();
  const ruleId = params.get('ruleId') ?? '';
  const link = rangeQuery(params);
  const ruleField = useId();
  const [older, setOlder] = useState<AlertRow[]>([]);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [exhausted, setExhausted] = useState(false);

  const key = `${ruleId}|${fromIso}|${toIso}`;
  const first = useAsync<AlertRow[]>(() => fetchAlerts({ ruleId: ruleId || undefined, from: fromIso, to: toIso, limit: PAGE_SIZE }), [key]);
  const rules = useAsync<AlertRule[]>(fetchRules, []);

  useEffect(() => {
    setOlder([]);
    setExhausted(false);
  }, [key]);

  const rows = [...(first.data ?? []), ...older];
  const pageFull = (first.data?.length ?? 0) >= PAGE_SIZE;

  function setRule(value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set('ruleId', value);
    else next.delete('ruleId');
    setParams(next);
  }

  async function loadOlder() {
    const oldest = rows[rows.length - 1];
    if (!oldest) return;
    setLoadingOlder(true);
    try {
      const page = await fetchAlerts({ ruleId: ruleId || undefined, from: fromIso, to: oldest.triggeredAt, limit: PAGE_SIZE });
      setOlder((o) => [...o, ...page]);
      if (page.length < PAGE_SIZE) setExhausted(true);
    } finally {
      setLoadingOlder(false);
    }
  }

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Alerts</h1>
          <p className="muted">{range.label}, newest first.</p>
        </div>
      </div>

      <div className="toolbar card">
        <div className="field-inline">
          <label htmlFor={ruleField}>Rule</label>
          <select id={ruleField} value={ruleId} onChange={(e) => setRule(e.target.value)}>
            <option value="">Any rule</option>
            {(rules.data ?? []).map((r) => (
              <option key={r.ruleId} value={r.ruleId}>
                {r.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {first.error && <ErrorBanner message={first.error} onRetry={first.reload} />}
      {!first.error && first.loading && <Skeleton rows={3} />}

      {first.data && rows.length === 0 && (
        <section className="card">
          <EmptyState title="No alerts in this window">{ruleId ? 'This rule did not fire. Try a wider window or another rule.' : 'Nothing tripped a rule. Quiet is good.'}</EmptyState>
        </section>
      )}

      {rows.length > 0 && (
        <section className="card table-card">
          <table>
            <caption className="sr-only">Alerts, newest first</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Rule</th>
                <th scope="col">Event</th>
                <th scope="col">Delivered to</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => {
                const reached = channels(a.notifiedChannels);
                const missed = undelivered(a.ruleChannels, a.notifiedChannels);
                return (
                  <tr key={a.alertId}>
                    <td className="nowrap" title={formatDateTime(a.triggeredAt)}>
                      <Link to={`/alerts/${encodeURIComponent(a.alertId)}${link}`}>{relativeTime(a.triggeredAt)}</Link>
                    </td>
                    <td>{a.ruleName ?? <span className="muted">Deleted rule</span>}</td>
                    <td>
                      <Link to={`/audit-log?event=${encodeURIComponent(a.eventId)}${link.replace('?', '&')}`}>
                        <code>{a.eventId}</code>
                      </Link>
                    </td>
                    <td>
                      {reached.length === 0 && <span className="muted">nothing</span>}
                      {reached.map((c) => (
                        <span key={c} className="chip-static">
                          {c}
                        </span>
                      ))}
                      {missed.length > 0 && <span className="badge badge-warn">not delivered: {missed.join(', ')}</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="table-foot">
            <span className="muted small">
              {rows.length} alert{rows.length === 1 ? '' : 's'} shown
            </span>
            {pageFull && !exhausted && (
              <button type="button" className="btn" onClick={loadOlder} disabled={loadingOlder}>
                {loadingOlder ? 'Loading…' : 'Load older'}
              </button>
            )}
          </div>
        </section>
      )}
    </>
  );
}
