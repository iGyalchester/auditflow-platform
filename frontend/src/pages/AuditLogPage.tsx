import { useEffect, useId, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { fetchAuditLogs } from '../api/client';
import { EVENT_TYPES, RISK_LEVELS, type AuditLogRow } from '../api/types';
import ControlChips from '../components/ControlChips';
import Drawer from '../components/Drawer';
import EmptyState from '../components/EmptyState';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import Skeleton from '../components/Skeleton';
import { useAsync } from '../hooks/useAsync';
import { useTimeRange } from '../hooks/useTimeRange';
import { auditLogCsv, downloadText } from '../util/csv';
import { humanize, relativeTime } from '../util/format';
import EventDetail from './EventDetail';

export const PAGE_SIZE = 50;

/**
 * Every event, newest first, with the filters in the URL so a filtered
 * view can be bookmarked or sent to a colleague. Paging is "Load older":
 * the next request asks for events before the oldest one on screen (the
 * gateway's keyset cursor), so the list grows without an offset that
 * would cost the database the whole walk each time. The drawer opens
 * from ?event=<id>, so a deep link to one event works too.
 */
export default function AuditLogPage() {
  const [params, setParams] = useSearchParams();
  const { range, fromIso, toIso } = useTimeRange();
  const type = params.get('type') ?? '';
  const riskLevel = params.get('riskLevel') ?? '';
  const userId = params.get('userId') ?? '';
  const q = params.get('q') ?? '';
  const anomalous = params.get('anomalous') === 'true';
  const openEvent = params.get('event');
  const [draftUser, setDraftUser] = useState(userId);
  const [draftQ, setDraftQ] = useState(q);
  const [older, setOlder] = useState<AuditLogRow[]>([]);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [olderError, setOlderError] = useState<string | null>(null);
  const [exhausted, setExhausted] = useState(false);
  const searchId = useId();
  const userField = useId();
  const typeField = useId();
  const riskField = useId();
  const anomalousField = useId();

  const filterKey = `${type}|${riskLevel}|${userId}|${q}|${anomalous}|${fromIso}|${toIso}`;
  const first = useAsync<AuditLogRow[]>(
    () => fetchAuditLogs({ type, riskLevel, userId, q, anomalous: anomalous || undefined, from: fromIso, to: toIso, limit: PAGE_SIZE }),
    [filterKey],
  );

  // a new filter or window starts the list over
  useEffect(() => {
    setOlder([]);
    setExhausted(false);
    setOlderError(null);
  }, [filterKey]);
  useEffect(() => {
    setDraftUser(userId);
    setDraftQ(q);
  }, [userId, q]);

  const rows = [...(first.data ?? []), ...older];
  const pageFull = (first.data?.length ?? 0) >= PAGE_SIZE;

  function set(key: string, value: string | null) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    // changing a filter closes the drawer: the event may no longer be in the list
    if (key !== 'event') next.delete('event');
    setParams(next);
  }

  function applyText(e: FormEvent) {
    e.preventDefault();
    const next = new URLSearchParams(params);
    if (draftUser.trim()) next.set('userId', draftUser.trim());
    else next.delete('userId');
    if (draftQ.trim()) next.set('q', draftQ.trim());
    else next.delete('q');
    next.delete('event');
    setParams(next);
  }

  function clearAll() {
    const next = new URLSearchParams(params);
    for (const key of ['type', 'riskLevel', 'userId', 'q', 'anomalous', 'event']) next.delete(key);
    setParams(next);
  }

  async function loadOlder() {
    const oldest = rows[rows.length - 1];
    if (!oldest) return;
    setLoadingOlder(true);
    setOlderError(null);
    try {
      const page = await fetchAuditLogs({ type, riskLevel, userId, q, anomalous: anomalous || undefined, from: fromIso, to: oldest.occurredAt, limit: PAGE_SIZE });
      setOlder((o) => [...o, ...page]);
      if (page.length < PAGE_SIZE) setExhausted(true);
    } catch (e) {
      setOlderError(e instanceof Error ? e.message : 'Could not load older events.');
    } finally {
      setLoadingOlder(false);
    }
  }

  function exportCsv() {
    downloadText(`audit-log-${range.from.toISOString().slice(0, 10)}-${range.to.toISOString().slice(0, 10)}.csv`, auditLogCsv(rows));
  }

  const filtered = type || riskLevel || userId || q || anomalous;

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Audit log</h1>
          <p className="muted">{range.label}, newest first.</p>
        </div>
        <button type="button" className="btn" onClick={exportCsv} disabled={rows.length === 0}>
          Export CSV
        </button>
      </div>

      <form className="toolbar card" onSubmit={applyText} role="search" aria-label="Filters">
        <div className="field-inline">
          <label htmlFor={typeField}>Type</label>
          <select id={typeField} value={type} onChange={(e) => set('type', e.target.value)}>
            <option value="">Any type</option>
            {EVENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {humanize(t)}
              </option>
            ))}
          </select>
        </div>
        <div className="field-inline">
          <label htmlFor={riskField}>Risk</label>
          <select id={riskField} value={riskLevel} onChange={(e) => set('riskLevel', e.target.value)}>
            <option value="">Any risk</option>
            {RISK_LEVELS.map((r) => (
              <option key={r} value={r}>
                {humanize(r)}
              </option>
            ))}
          </select>
        </div>
        <div className="field-inline">
          <label htmlFor={userField}>User</label>
          <input id={userField} value={draftUser} onChange={(e) => setDraftUser(e.target.value)} placeholder="user id" />
        </div>
        <div className="field-inline grow">
          <label htmlFor={searchId}>Search</label>
          <input id={searchId} type="search" value={draftQ} onChange={(e) => setDraftQ(e.target.value)} placeholder="resource or action" maxLength={100} />
        </div>
        <label className="check" htmlFor={anomalousField}>
          <input id={anomalousField} type="checkbox" checked={anomalous} onChange={(e) => set('anomalous', e.target.checked ? 'true' : null)} />
          Anomalous only
        </label>
        <div className="toolbar-actions">
          <button type="submit" className="btn btn-primary">
            Apply
          </button>
          {filtered && (
            <button type="button" className="btn btn-ghost" onClick={clearAll}>
              Clear
            </button>
          )}
        </div>
      </form>

      {first.error && <ErrorBanner message={first.error} onRetry={first.reload} />}
      {!first.error && first.loading && <Skeleton rows={3} />}

      {first.data && rows.length === 0 && (
        <section className="card">
          <EmptyState title={filtered ? 'No events match these filters' : 'No events in this window'}>
            {filtered ? 'Loosen a filter or widen the time range.' : 'Widen the time range, or wait for a source to send some.'}
          </EmptyState>
        </section>
      )}

      {rows.length > 0 && (
        <section className="card table-card">
          <table className="events">
            <caption className="sr-only">Audit events, newest first</caption>
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Type</th>
                <th scope="col">Action</th>
                <th scope="col">Resource</th>
                <th scope="col">User</th>
                <th scope="col">Risk</th>
                <th scope="col">Controls</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.eventId}>
                  <td className="nowrap">
                    <button type="button" className="link" onClick={() => set('event', r.eventId)} title={r.occurredAt} aria-label={`Open event ${r.eventId}`}>
                      {relativeTime(r.occurredAt)}
                    </button>
                  </td>
                  <td>
                    <span className="chip-static">{humanize(r.eventType)}</span>
                  </td>
                  <td>
                    {r.action ?? '—'}
                    {r.anomalous && (
                      <span className="badge badge-anomalous" title="Flagged by the anomaly detector">
                        Anomalous
                      </span>
                    )}
                  </td>
                  <td className="truncate">{r.resource ?? '—'}</td>
                  <td className="truncate">{r.userId ?? '—'}</td>
                  <td>
                    <RiskBadge level={r.riskLevel} />
                  </td>
                  <td>
                    <ControlChips controls={r.controls} max={3} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="table-foot">
            <span className="muted small">
              {rows.length} event{rows.length === 1 ? '' : 's'} shown
            </span>
            {olderError && <span className="error small">{olderError}</span>}
            {pageFull && !exhausted && (
              <button type="button" className="btn" onClick={loadOlder} disabled={loadingOlder}>
                {loadingOlder ? 'Loading…' : 'Load older'}
              </button>
            )}
            {(exhausted || (!pageFull && rows.length > 0)) && <span className="muted small">That is everything in this window.</span>}
          </div>
        </section>
      )}

      {openEvent && (
        <Drawer title="Event" onClose={() => set('event', null)}>
          <EventDetail eventId={openEvent} />
        </Drawer>
      )}
    </>
  );
}
