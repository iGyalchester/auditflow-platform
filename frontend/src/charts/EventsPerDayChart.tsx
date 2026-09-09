import { Bar, BarChart, CartesianGrid, Legend, Tooltip, XAxis, YAxis } from 'recharts';
import type { DayBucket } from '../api/types';
import { formatCount, humanize, shortDay } from '../util/format';
import ChartCard from './ChartCard';
import { AXIS_TICK, RISK_ORDER, TOOLTIP_STYLE, riskColor } from './palette';
import EmptyState from '../components/EmptyState';

/**
 * Events per UTC day, stacked by risk level. Risk is ordered, so it takes
 * a one-hue ramp (light = low, dark = critical) rather than four unrelated
 * colours; the legend and the table name each level.
 */
export default function EventsPerDayChart({ perDay }: { perDay: DayBucket[] }) {
  type Row = { day: string; label: string; events: number } & Record<(typeof RISK_ORDER)[number], number>;
  const data: Row[] = perDay.map((d) => ({
    day: d.day,
    label: shortDay(d.day),
    events: d.events,
    LOW: d.byRisk.LOW ?? 0,
    MEDIUM: d.byRisk.MEDIUM ?? 0,
    HIGH: d.byRisk.HIGH ?? 0,
    CRITICAL: d.byRisk.CRITICAL ?? 0,
  }));
  const total = data.reduce((n, d) => n + d.events, 0);
  const critical = data.reduce((n, d) => n + d.CRITICAL, 0);
  const interval = data.length > 14 ? Math.ceil(data.length / 10) - 1 : 0;

  return (
    <ChartCard
      title="Events per day"
      description="Every audit event in the window, by the day it happened (UTC), stacked by risk level."
      empty={total === 0 ? <EmptyState title="No events in this window">Widen the range, or wait for a source to send some.</EmptyState> : undefined}
      table={
        <table>
          <caption className="sr-only">Events per day by risk level</caption>
          <thead>
            <tr>
              <th scope="col">Day</th>
              {RISK_ORDER.map((r) => (
                <th key={r} scope="col" className="num">
                  {humanize(r)}
                </th>
              ))}
              <th scope="col" className="num">
                Total
              </th>
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.day}>
                <td>{d.label}</td>
                {RISK_ORDER.map((r) => (
                  <td key={r} className="num">
                    {formatCount(d[r])}
                  </td>
                ))}
                <td className="num">{formatCount(d.events)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {(width) => (
        <div role="img" aria-label={`Events per day over ${data.length} days: ${total} events, ${critical} critical`}>
          <BarChart width={width} height={260} data={data} margin={{ left: 0, right: 8, top: 8, bottom: 4 }} barCategoryGap="20%">
            <CartesianGrid vertical={false} stroke="var(--grid)" />
            <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} interval={interval} />
            <YAxis allowDecimals={false} width={36} tick={AXIS_TICK} axisLine={false} tickLine={false} />
            <Tooltip cursor={{ fill: 'var(--bg)' }} contentStyle={TOOLTIP_STYLE} formatter={(v: number, name: string) => [formatCount(v), humanize(name)]} />
            <Legend wrapperStyle={{ fontSize: 12, color: 'var(--muted)' }} formatter={(v: string) => humanize(v)} />
            {RISK_ORDER.map((r, i) => (
              <Bar
                key={r}
                dataKey={r}
                stackId="risk"
                fill={riskColor(r)}
                stroke="var(--card)"
                strokeWidth={1}
                radius={i === RISK_ORDER.length - 1 ? [4, 4, 0, 0] : 0}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </div>
      )}
    </ChartCard>
  );
}
