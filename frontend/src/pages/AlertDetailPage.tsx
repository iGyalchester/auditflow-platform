import { Link, useParams, useSearchParams } from 'react-router-dom';
import { fetchAlert } from '../api/client';
import type { AlertDetail } from '../api/types';
import ControlChips from '../components/ControlChips';
import ErrorBanner from '../components/ErrorBanner';
import RiskBadge from '../components/RiskBadge';
import Skeleton from '../components/Skeleton';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime, humanize } from '../util/format';
import { rangeQuery } from '../util/timeRange';

/**
 * One alert: the rule, the event that raised it (or a note that
 * retention has since purged it - the alert is evidence and outlives
 * it), and the delivery picture: configured, reached, and the gap.
 */
export default function AlertDetailPage() {
  const { alertId = '' } = useParams();
  const [params] = useSearchParams();
  const link = rangeQuery(params);
  const { data, error, loading, reload } = useAsync<AlertDetail>(() => fetchAlert(alertId), [alertId]);

  return (
    <>
      <div className="page-title">
        <div>
          <p className="muted small">
            <Link to={`/alerts${link}`}>← All alerts</Link>
          </p>
          <h1>{data?.alert.ruleName ?? (data ? 'Deleted rule' : 'Alert')}</h1>
        </div>
      </div>

      {error && <ErrorBanner message={error} onRetry={reload} />}
      {!error && loading && <Skeleton rows={2} />}

      {data && (
        <div className="grid-2">
          <section className="card">
            <h2>Delivery</h2>
            <dl className="details">
              <dt>Fired</dt>
              <dd>{formatDateTime(data.alert.triggeredAt)}</dd>
              <dt>Rule</dt>
              <dd>{data.alert.ruleId ? <Link to={`/rules${link}`}>{data.alert.ruleName}</Link> : <span className="muted">deleted since</span>}</dd>
              <dt>Configured</dt>
              <dd>{data.configuredChannels.length > 0 ? data.configuredChannels.join(', ') : <span className="muted">none today</span>}</dd>
              <dt>Reached</dt>
              <dd>{data.notifiedChannels.length > 0 ? data.notifiedChannels.join(', ') : <span className="muted">nothing</span>}</dd>
              <dt>Not delivered</dt>
              <dd>{data.undeliveredChannels.length > 0 ? <span className="badge badge-warn">{data.undeliveredChannels.join(', ')}</span> : '—'}</dd>
            </dl>
          </section>

          <section className="card">
            <h2>Event</h2>
            {!data.event && (
              <p className="muted">
                Event <code>{data.alert.eventId}</code> is no longer stored; retention has purged it. The alert stands on its own.
              </p>
            )}
            {data.event && (
              <>
                <div className="detail-head">
                  <RiskBadge level={data.event.riskLevel} />
                  {data.event.anomalous && <span className="badge badge-anomalous">Anomalous</span>}
                </div>
                <dl className="details">
                  <dt>Event id</dt>
                  <dd>
                    <Link to={`/audit-log?event=${encodeURIComponent(data.event.eventId)}${link.replace('?', '&')}`}>
                      <code>{data.event.eventId}</code>
                    </Link>
                  </dd>
                  <dt>Occurred</dt>
                  <dd>{formatDateTime(data.event.occurredAt)}</dd>
                  <dt>Type</dt>
                  <dd>{humanize(data.event.eventType)}</dd>
                  <dt>Action</dt>
                  <dd>{data.event.action ?? '—'}</dd>
                  <dt>Resource</dt>
                  <dd>{data.event.resource ?? '—'}</dd>
                  <dt>User</dt>
                  <dd>{data.event.userId ?? '—'}</dd>
                  <dt>Controls</dt>
                  <dd>
                    <ControlChips controls={data.event.controls} max={12} />
                  </dd>
                </dl>
              </>
            )}
          </section>
        </div>
      )}
    </>
  );
}
