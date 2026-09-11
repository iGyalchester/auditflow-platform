import { Link, useSearchParams } from 'react-router-dom';
import { fetchAlerts, fetchStats } from '../api/client';
import type { AlertRow, Stats } from '../api/types';
import AlertsPerDayChart from '../charts/AlertsPerDayChart';
import EventsPerDayChart from '../charts/EventsPerDayChart';
import HorizontalBarsChart from '../charts/HorizontalBarsChart';
import EmptyState from '../components/EmptyState';
import ErrorBanner from '../components/ErrorBanner';
import Skeleton from '../components/Skeleton';
import StatCard from '../components/StatCard';
import { useAsync } from '../hooks/useAsync';
import { useTimeRange } from '../hooks/useTimeRange';
import { deltaText, formatCount, frameworkLabel, humanize, relativeTime } from '../util/format';
import { rangeQuery } from '../util/timeRange';

const RECENT_ALERTS = 8;

/**
 * The landing view: five numbers with their change against the previous
 * window, events per day by risk, events by type, alerts per day, which
 * controls the evidence covers, the busiest users and resources, and
 * the latest alerts. Everything comes from GET /api/v1/stats for the
 * window in the URL, plus the alert feed's first page.
 */
export default function DashboardPage() {
  const { range, fromIso, toIso } = useTimeRange();
  const [params] = useSearchParams();
  const link = rangeQuery(params);
  const stats = useAsync<Stats>(() => fetchStats(fromIso, toIso), [fromIso, toIso]);
  const alerts = useAsync<AlertRow[]>(() => fetchAlerts({ from: fromIso, to: toIso, limit: RECENT_ALERTS }), [fromIso, toIso]);

  const s = stats.data;

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">{range.label}, all times UTC.</p>
        </div>
      </div>

      {stats.error && <ErrorBanner message={stats.error} onRetry={stats.reload} />}
      {!stats.error && stats.loading && <Skeleton tiles={5} rows={4} />}

      {s && (
        <>
          <div className="stats stats-5">
            <StatCard label="Events" value={formatCount(s.totals.events)} delta={deltaText(s.totals.events, s.previous.events)} hint="audit events received" />
            <StatCard
              label="Alerts"
              value={formatCount(s.totals.alerts)}
              delta={deltaText(s.totals.alerts, s.previous.alerts)}
              deltaTone={s.totals.alerts > s.previous.alerts ? 'bad' : 'neutral'}
              hint="rules that fired"
            />
            <StatCard
              label="Critical"
              value={formatCount(s.totals.critical)}
              delta={deltaText(s.totals.critical, s.previous.critical)}
              deltaTone={s.totals.critical > s.previous.critical ? 'bad' : 'neutral'}
              hint="events rated critical"
            />
            <StatCard
              label="Anomalous"
              value={formatCount(s.totals.anomalous)}
              delta={deltaText(s.totals.anomalous, s.previous.anomalous)}
              deltaTone={s.totals.anomalous > s.previous.anomalous ? 'bad' : 'neutral'}
              hint="flagged by the anomaly detector"
            />
            <StatCard label="Active users" value={formatCount(s.totals.users)} delta={deltaText(s.totals.users, s.previous.users)} hint="distinct user ids seen" />
          </div>

          {s.totals.events === 0 && (
            <section className="card">
              <EmptyState title="Nothing arrived in this window">
                Widen the time range above, or send an event: the README's "Try the API" section has a one-line curl.
              </EmptyState>
            </section>
          )}

          <EventsPerDayChart perDay={s.perDay} />

          <div className="grid-2">
            <HorizontalBarsChart
              title="Events by type"
              description="What kind of activity the window holds. Names, not an order, so one colour."
              ariaLabel={`Events by type: ${Object.entries(s.byType)
                .map(([k, v]) => `${humanize(k)} ${v}`)
                .join(', ') || 'none'}`}
              rows={Object.entries(s.byType).map(([k, v]) => ({ key: k, label: humanize(k), count: v }))}
              columnLabel="Type"
              emptyTitle="No events to group"
            />
            <AlertsPerDayChart perDay={s.perDay} />
          </div>

          <div className="grid-2">
            <HorizontalBarsChart
              title="Controls coverage"
              description="How many events were classified as evidence for each compliance control."
              ariaLabel={`Controls coverage: ${Object.entries(s.byControl)
                .slice(0, 8)
                .map(([k, v]) => `${k} ${v}`)
                .join(', ') || 'none'}`}
              rows={Object.entries(s.byControl)
                .slice(0, 12)
                .map(([k, v]) => {
                  const [framework, control] = k.split(':');
                  return { key: k, label: `${frameworkLabel(framework)} ${control ?? ''}`.trim(), count: v };
                })}
              columnLabel="Control"
              emptyTitle="No events were mapped to a control"
            />

            <section className="card">
              <div className="card-title">
                <div>
                  <h2>Recent alerts</h2>
                  <p className="muted small">The latest rules that fired in this window.</p>
                </div>
                <Link to={`/alerts${link}`} className="btn btn-ghost">
                  All alerts
                </Link>
              </div>
              {alerts.error && <p className="error small">{alerts.error}</p>}
              {alerts.data && alerts.data.length === 0 && <EmptyState title="No alerts in this window" />}
              {alerts.data && alerts.data.length > 0 && (
                <ul className="plain-list">
                  {alerts.data.map((a) => (
                    <li key={a.alertId} className="row-line">
                      <div>
                        <Link to={`/alerts/${encodeURIComponent(a.alertId)}${link}`}>{a.ruleName ?? 'Deleted rule'}</Link>
                        <div className="muted small">
                          event <code>{a.eventId}</code>
                          {a.notifiedChannels ? ` · sent to ${a.notifiedChannels}` : ' · not delivered'}
                        </div>
                      </div>
                      <span className="muted small nowrap" title={a.triggeredAt}>
                        {relativeTime(a.triggeredAt)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>

          <div className="grid-2">
            <TopTable title="Top users" description="Who generated the most events." column="User" rows={s.topUsers} />
            <TopTable title="Top resources" description="What was touched most often." column="Resource" rows={s.topResources} />
          </div>
        </>
      )}
    </>
  );
}

function TopTable({ title, description, column, rows }: { title: string; description: string; column: string; rows: { name: string; count: number }[] }) {
  return (
    <section className="card">
      <h2>{title}</h2>
      <p className="muted small">{description}</p>
      {rows.length === 0 && <EmptyState title={`No ${column.toLowerCase()}s in this window`} />}
      {rows.length > 0 && (
        <table>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{column}</th>
              <th scope="col" className="num">
                Events
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.name}>
                <td className="truncate">{r.name}</td>
                <td className="num">{formatCount(r.count)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
