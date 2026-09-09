import { Bar, BarChart, CartesianGrid, Tooltip, XAxis, YAxis } from 'recharts';
import type { DayBucket } from '../api/types';
import EmptyState from '../components/EmptyState';
import { formatCount, shortDay } from '../util/format';
import ChartCard from './ChartCard';
import { AXIS_TICK, TOOLTIP_STYLE, seriesColor } from './palette';

/** Alerts raised per UTC day: one series, one hue, no legend. */
export default function AlertsPerDayChart({ perDay }: { perDay: DayBucket[] }) {
  const data = perDay.map((d) => ({ day: d.day, label: shortDay(d.day), alerts: d.alerts }));
  const total = data.reduce((n, d) => n + d.alerts, 0);
  const interval = data.length > 14 ? Math.ceil(data.length / 10) - 1 : 0;

  return (
    <ChartCard
      title="Alerts per day"
      description="How many times a rule fired, by day. Quiet is good; a spike is worth a look in the feed."
      empty={total === 0 ? <EmptyState title="No alerts in this window">Nothing tripped a rule. Check the rules page if you expected some.</EmptyState> : undefined}
      table={
        <table>
          <caption className="sr-only">Alerts per day</caption>
          <thead>
            <tr>
              <th scope="col">Day</th>
              <th scope="col" className="num">
                Alerts
              </th>
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.day}>
                <td>{d.label}</td>
                <td className="num">{formatCount(d.alerts)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {(width) => (
        <div role="img" aria-label={`Alerts per day over ${data.length} days: ${total} alerts`}>
          <BarChart width={width} height={200} data={data} margin={{ left: 0, right: 8, top: 8, bottom: 4 }} barCategoryGap="20%">
            <CartesianGrid vertical={false} stroke="var(--grid)" />
            <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} interval={interval} />
            <YAxis allowDecimals={false} width={36} tick={AXIS_TICK} axisLine={false} tickLine={false} />
            <Tooltip cursor={{ fill: 'var(--bg)' }} contentStyle={TOOLTIP_STYLE} formatter={(v: number) => [formatCount(v), 'Alerts']} />
            <Bar dataKey="alerts" fill={seriesColor()} radius={[4, 4, 0, 0]} isAnimationActive={false} />
          </BarChart>
        </div>
      )}
    </ChartCard>
  );
}
