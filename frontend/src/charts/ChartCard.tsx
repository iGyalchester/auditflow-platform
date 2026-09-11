import { useEffect, useRef, useState, type ReactNode } from 'react';

interface Props {
  title: string;
  description: string;
  /** rendered when the user asks for the table view */
  table: ReactNode;
  children: (width: number) => ReactNode;
  /** shown instead of the chart when there is nothing to plot */
  empty?: ReactNode;
}

/**
 * The frame every chart lives in: a title, one plain-language sentence
 * about what it shows, the chart itself sized to the card, and a "View
 * as table" toggle so the numbers are never only available as pixels.
 * Width comes from a ResizeObserver where the browser has one; the test
 * DOM does not, so a sensible default keeps the chart rendering there.
 */
export default function ChartCard({ title, description, table, children, empty }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(600);
  const [asTable, setAsTable] = useState(false);

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver((entries) => {
      const w = Math.floor(entries[0].contentRect.width);
      if (w > 0) setWidth(w);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return (
    <section className="card chart-card">
      <div className="card-title">
        <div>
          <h2>{title}</h2>
          <p className="muted small">{description}</p>
        </div>
        {!empty && (
          <button type="button" className="btn btn-ghost" onClick={() => setAsTable((v) => !v)} aria-pressed={asTable}>
            {asTable ? 'View as chart' : 'View as table'}
          </button>
        )}
      </div>
      <div ref={ref} className="chart-body">
        {empty ? empty : asTable ? table : children(width)}
      </div>
    </section>
  );
}
