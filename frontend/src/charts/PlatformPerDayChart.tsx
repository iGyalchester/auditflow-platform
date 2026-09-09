import { Bar, BarChart, CartesianGrid, Legend, Tooltip, XAxis, YAxis } from 'recharts';
import type { PlatformStats } from '../api/types';
import EmptyState from '../components/EmptyState';
import { formatCount, shortDay } from '../util/format';
import ChartCard from './ChartCard';
import { AXIS_TICK, CATEGORICAL, TOOLTIP_STYLE, categoricalColor } from './palette';

const MAX_SERIES = CATEGORICAL.light.length;

/**
 * Events per UTC day across the platform, stacked per customer. The
 * customers are names, not an order, so each takes the next categorical
 * slot in fixed order (assigned by rank on the first render of a window
 * and never cycled); past six, the rest fold into "Other". A legend is
 * always present, and the table view names every customer.
 */
export default function PlatformPerDayChart({ stats }: { stats: PlatformStats }) {
  const ranked = stats.topCustomers.map((c) => c.customerId);
  const named = ranked.slice(0, MAX_SERIES);
  const folded = ranked.slice(MAX_SERIES);
  const label = (id: string) => stats.byCustomer[id] ?? id;
  const series = [...named.map((id) => ({ key: id, label: label(id) })), ...(folded.length > 0 ? [{ key: '__other', label: `Other (${folded.length})` }] : [])];

  const data = stats.perDay.map((d) => {
    const row: Record<string, number | string> = { day: d.day, label: shortDay(d.day), events: d.events };
    for (const id of named) row[id] = d.eventsByCustomer[id] ?? 0;
    if (folded.length > 0) row.__other = folded.reduce((n, id) => n + (d.eventsByCustomer[id] ?? 0), 0);
    return row;
  });
  const total = stats.totals.events;
  const interval = data.length > 14 ? Math.ceil(data.length / 10) - 1 : 0;

  return (
    <ChartCard
      title="Events per day, by customer"
      description="Who is sending what, day by day. Six customers are named; the rest fold into Other."
      empty={total === 0 ? <EmptyState title="No events on the platform in this window" /> : undefined}
      table={
        <table>
          <caption className="sr-only">Events per day by customer</caption>
          <thead>
            <tr>
              <th scope="col">Day</th>
              {series.map((s) => (
                <th key={s.key} scope="col" className="num">
                  {s.label}
                </th>
              ))}
              <th scope="col" className="num">
                Total
              </th>
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={String(d.day)}>
                <td>{d.label}</td>
                {series.map((s) => (
                  <td key={s.key} className="num">
                    {formatCount(Number(d[s.key] ?? 0))}
                  </td>
                ))}
                <td className="num">{formatCount(Number(d.events))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {(width) => (
        <div role="img" aria-label={`Events per day by customer over ${data.length} days: ${total} events from ${stats.totals.customers} customers`}>
          <BarChart width={width} height={260} data={data} margin={{ left: 0, right: 8, top: 8, bottom: 4 }} barCategoryGap="20%">
            <CartesianGrid vertical={false} stroke="var(--grid)" />
            <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} interval={interval} />
            <YAxis allowDecimals={false} width={36} tick={AXIS_TICK} axisLine={false} tickLine={false} />
            <Tooltip cursor={{ fill: 'var(--bg)' }} contentStyle={TOOLTIP_STYLE} formatter={(v: number, name: string) => [formatCount(v), name]} />
            <Legend wrapperStyle={{ fontSize: 12, color: 'var(--muted)' }} />
            {series.map((s, i) => (
              <Bar key={s.key} dataKey={s.key} name={s.label} stackId="c" fill={s.key === '__other' ? 'var(--border)' : categoricalColor(i)} stroke="var(--card)" strokeWidth={1} isAnimationActive={false} />
            ))}
          </BarChart>
        </div>
      )}
    </ChartCard>
  );
}
