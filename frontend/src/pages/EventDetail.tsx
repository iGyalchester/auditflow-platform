import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { fetchAuditLog } from '../api/client';
import type { AuditLogDetail } from '../api/types';
import ControlChips from '../components/ControlChips';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import Skeleton from '../components/Skeleton';
import { useAsync } from '../hooks/useAsync';
import { channels, undelivered } from '../util/channels';
import { formatDateTime, humanize, relativeTime } from '../util/format';
import { spelStringLiteral } from '../util/spel';
import { rangeQuery } from '../util/timeRange';

/**
 * One event, every field, and what it caused. "Create rule from this
 * event" opens the rule editor pre-filled with the event's type and risk
 * and a condition on its action: the quickest way from "that should have
 * alerted" to a rule that would.
 */
export default function EventDetail({ eventId }: { eventId: string }) {
  const { data, error, loading, reload } = useAsync<AuditLogDetail>(() => fetchAuditLog(eventId), [eventId]);
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const link = rangeQuery(params);

  if (error) return <ErrorBanner message={error} onRetry={reload} />;
  if (loading || !data) return <Skeleton rows={2} />;
  const e = data.event;

  function createRule() {
    const draft = new URLSearchParams();
    draft.set('new', '1');
    draft.set('eventType', e.eventType);
    if (e.riskLevel) draft.set('riskThreshold', e.riskLevel);
    if (e.action) draft.set('condition', `action == ${spelStringLiteral(e.action)}`);
    draft.set('name', `${humanize(e.action ?? e.eventType)} on ${e.resource ?? 'any resource'}`);
    for (const key of ['range', 'from', 'to']) {
      const v = params.get(key);
      if (v) draft.set(key, v);
    }
    navigate(`/rules?${draft.toString()}`);
  }

  return (
    <>
      <div className="detail-head">
        <RiskBadge level={e.riskLevel} />
        {e.anomalous && <span className="badge badge-anomalous">Anomalous</span>}
        <span className="muted small" title={e.occurredAt}>
          {relativeTime(e.occurredAt)}
        </span>
      </div>
      <dl className="details">
        <dt>Event id</dt>
        <dd>
          <code>{e.eventId}</code>
        </dd>
        <dt>Occurred</dt>
        <dd>{formatDateTime(e.occurredAt)}</dd>
        <dt>Type</dt>
        <dd>{humanize(e.eventType)}</dd>
        <dt>Action</dt>
        <dd>{e.action ?? '—'}</dd>
        <dt>Resource</dt>
        <dd>{e.resource ?? '—'}</dd>
        <dt>User</dt>
        <dd>{e.userId ?? '—'}</dd>
        <dt>Session</dt>
        <dd>{e.sessionId ? <code>{e.sessionId}</code> : '—'}</dd>
        <dt>Controls</dt>
        <dd>
          <ControlChips controls={e.controls} max={12} />
        </dd>
      </dl>

      <h3>Alerts raised</h3>
      {data.alerts.length === 0 && <p className="muted small">No rule fired on this event.</p>}
      {data.alerts.length > 0 && (
        <ul className="plain-list">
          {data.alerts.map((a) => {
            const missed = undelivered(a.ruleChannels, a.notifiedChannels);
            return (
              <li key={a.alertId} className="row-line">
                <div>
                  <Link to={`/alerts/${encodeURIComponent(a.alertId)}${link}`}>{a.ruleName ?? 'Deleted rule'}</Link>
                  <div className="muted small">
                    {channels(a.notifiedChannels).length > 0 ? `sent to ${channels(a.notifiedChannels).join(', ')}` : 'not delivered'}
                    {missed.length > 0 && <span className="badge badge-warn"> not delivered: {missed.join(', ')}</span>}
                  </div>
                </div>
                <span className="muted small nowrap">{relativeTime(a.triggeredAt)}</span>
              </li>
            );
          })}
        </ul>
      )}

      <div className="actions">
        <button type="button" className="btn" onClick={createRule}>
          Create rule from this event
        </button>
      </div>
    </>
  );
}
