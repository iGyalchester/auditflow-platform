import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { fetchOperatorCustomers, fetchPlatformStats } from '../api/client';
import type { OperatorCustomer, PlatformStats } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import PlatformPerDayChart from '../charts/PlatformPerDayChart';
import EmptyState from '../components/EmptyState';
import ErrorBanner from '../components/ErrorBanner';
import Skeleton from '../components/Skeleton';
import StatCard from '../components/StatCard';
import { useAsync } from '../hooks/useAsync';
import { useTimeRange } from '../hooks/useTimeRange';
import { formatCount, relativeTime } from '../util/format';
import { rangeQuery } from '../util/timeRange';

type SortKey = 'events7d' | 'events24h' | 'alerts7d' | 'rules' | 'customerId';

/**
 * The platform as a whole, for operators: totals, events per day split
 * per customer, and every tenant anything mentions with "View as", which
 * runs the rest of the console as that customer (the gateway honours the
 * X-Acting-Customer-Id header for operators only). A plain user never
 * gets here: the gateway answers 403 and the page says so.
 */
export default function OperatorPage() {
  const { me, actAs } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { range, fromIso, toIso } = useTimeRange();
  const stats = useAsync<PlatformStats>(() => fetchPlatformStats(fromIso, toIso), [fromIso, toIso]);
  const customers = useAsync<OperatorCustomer[]>(fetchOperatorCustomers, []);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortKey>('events7d');
  const [switching, setSwitching] = useState<string | null>(null);

  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const list = (customers.data ?? []).filter((c) => !needle || c.customerId.toLowerCase().includes(needle) || (c.name ?? '').toLowerCase().includes(needle));
    return [...list].sort((a, b) => (sort === 'customerId' ? a.customerId.localeCompare(b.customerId) : b[sort] - a[sort] || a.customerId.localeCompare(b.customerId)));
  }, [customers.data, search, sort]);

  const forbidden = stats.status === 403 || customers.status === 403;
  if (forbidden || (me && !me.roles.includes('OPERATOR'))) {
    return (
      <>
        <div className="page-title">
          <h1>Operator</h1>
        </div>
        <section className="card">
          <EmptyState title="Not allowed">This page is for platform operators. Your account is scoped to {me?.customerName ?? me?.customerId}.</EmptyState>
        </section>
      </>
    );
  }

  async function viewAs(c: OperatorCustomer) {
    setSwitching(c.customerId);
    try {
      await actAs(c.customerId);
      navigate(`/dashboard${rangeQuery(params)}`);
    } finally {
      setSwitching(null);
    }
  }

  function header(key: SortKey, label: string) {
    const active = sort === key;
    return (
      <th scope="col" className={key === 'customerId' ? '' : 'num'} aria-sort={active ? 'descending' : 'none'}>
        <button type="button" className="sort" onClick={() => setSort(key)} aria-pressed={active}>
          {label}
        </button>
      </th>
    );
  }

  return (
    <>
      <div className="page-title">
        <div>
          <h1>Operator</h1>
          <p className="muted">Every customer on the platform, {range.label.toLowerCase()}.</p>
        </div>
      </div>

      {stats.error && !forbidden && <ErrorBanner message={stats.error} onRetry={stats.reload} />}
      {!stats.error && stats.loading && <Skeleton tiles={3} rows={1} />}
      {stats.data && (
        <>
          <div className="stats stats-3">
            <StatCard label="Events" value={formatCount(stats.data.totals.events)} hint="across every customer" />
            <StatCard label="Alerts" value={formatCount(stats.data.totals.alerts)} hint="rules that fired, all customers" />
            <StatCard label="Active customers" value={formatCount(stats.data.totals.customers)} hint="sent at least one event" />
          </div>
          <PlatformPerDayChart stats={stats.data} />
        </>
      )}

      <section className="card table-card">
        <div className="card-title card-title-pad">
          <div>
            <h2>Customers</h2>
            <p className="muted small">A tenant appears here as soon as anything mentions it, registered or not.</p>
          </div>
          <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Find a customer" aria-label="Find a customer" className="search-inline" />
        </div>
        {customers.error && !forbidden && <ErrorBanner message={customers.error} onRetry={customers.reload} />}
        {!customers.error && customers.loading && <Skeleton rows={2} />}
        {customers.data && rows.length === 0 && <EmptyState title={search ? 'No customer matches' : 'No customers yet'} />}
        {rows.length > 0 && (
          <table>
            <caption className="sr-only">Customers</caption>
            <thead>
              <tr>
                {header('customerId', 'Customer')}
                {header('events24h', 'Events 24h')}
                {header('events7d', 'Events 7d')}
                {header('alerts7d', 'Alerts 7d')}
                {header('rules', 'Rules')}
                <th scope="col">Last event</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.customerId}>
                  <td>
                    <div className="rule-name">{c.name ?? c.customerId}</div>
                    <div className="muted small">
                      {c.name ? c.customerId : 'not registered'}
                      {me?.actingAs === c.customerId && <span className="badge badge-role"> viewing</span>}
                    </div>
                  </td>
                  <td className="num">{formatCount(c.events24h)}</td>
                  <td className="num">{formatCount(c.events7d)}</td>
                  <td className="num">{formatCount(c.alerts7d)}</td>
                  <td className="num">{formatCount(c.rules)}</td>
                  <td className="nowrap small" title={c.lastEventAt ?? undefined}>
                    {c.lastEventAt ? relativeTime(c.lastEventAt) : <span className="muted">never</span>}
                  </td>
                  <td className="row-actions">
                    <button type="button" className="btn btn-ghost" onClick={() => viewAs(c)} disabled={switching !== null || me?.actingAs === c.customerId}>
                      {switching === c.customerId ? 'Switching…' : 'View as'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
