import { Bar, BarChart, LabelList, Tooltip, XAxis, YAxis } from 'recharts';
import EmptyState from '../components/EmptyState';
import { formatCount } from '../util/format';
import ChartCard from './ChartCard';
import { AXIS_TICK, TOOLTIP_STYLE, seriesColor } from './palette';

interface Row {
  key: string;
  label: string;
  count: number;
}

/**
 * Nominal categories ranked by count: one hue (the categories are names,
 * not an order, so colour has nothing to add over bar length), direct
 * value labels, and no legend because a single series is named by the
 * title.
 */
export default function HorizontalBarsChart({
  title,
  description,
  ariaLabel,
  rows,
  columnLabel,
  emptyTitle,
}: {
  title: string;
  description: string;
  ariaLabel: string;
  rows: Row[];
  columnLabel: string;
  emptyTitle: string;
}) {
  const height = Math.max(120, rows.length * 34 + 24);
  return (
    <ChartCard
      title={title}
      description={description}
      empty={rows.length === 0 ? <EmptyState title={emptyTitle} /> : undefined}
      table={
        <table>
          <caption className="sr-only">{title}</caption>
          <thead>
            <tr>
              <th scope="col">{columnLabel}</th>
              <th scope="col" className="num">
                Events
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.key}>
                <td>{r.label}</td>
                <td className="num">{formatCount(r.count)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {(width) => (
        <div role="img" aria-label={ariaLabel}>
          <BarChart layout="vertical" width={width} height={height} data={rows} margin={{ left: 8, right: 48, top: 4, bottom: 4 }} barCategoryGap="25%">
            <XAxis type="number" hide allowDecimals={false} />
            <YAxis type="category" dataKey="label" width={Math.min(180, Math.floor(width * 0.35))} tick={AXIS_TICK} axisLine={false} tickLine={false} interval={0} />
            <Tooltip cursor={{ fill: 'var(--bg)' }} contentStyle={TOOLTIP_STYLE} formatter={(v: number) => [formatCount(v), 'Events']} />
            <Bar dataKey="count" fill={seriesColor()} radius={[0, 4, 4, 0]} isAnimationActive={false} maxBarSize={22}>
              <LabelList dataKey="count" position="right" formatter={(v: number) => formatCount(v)} style={{ fill: 'var(--text)', fontSize: 12 }} />
            </Bar>
          </BarChart>
        </div>
      )}
    </ChartCard>
  );
}
