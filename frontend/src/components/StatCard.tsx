/**
 * One headline number with its label, a delta against the previous
 * window, and a one-line explanation. The delta is text, never colour
 * alone; whether "up" is good depends on the metric, so the caller says.
 */
export default function StatCard({
  label,
  value,
  delta,
  hint,
  deltaTone = 'neutral',
}: {
  label: string;
  value: string;
  delta?: string | null;
  hint: string;
  deltaTone?: 'good' | 'bad' | 'neutral';
}) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-row">
        <div className="stat-value">{value}</div>
        {delta && (
          <span className={`delta delta-${deltaTone}`} title="Compared with the previous window">
            {delta}
          </span>
        )}
      </div>
      <div className="muted small">{hint}</div>
    </div>
  );
}
