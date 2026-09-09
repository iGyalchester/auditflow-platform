import { useId, useState } from 'react';
import { useTimeRange } from '../hooks/useTimeRange';
import { PRESETS } from '../util/timeRange';

function toInputValue(date: Date): string {
  return date.toISOString().slice(0, 16);
}

/**
 * The global window every page reads: four presets and a custom range,
 * written to the URL so the choice survives navigation and can be
 * shared as a link. Custom dates are entered in UTC, the timezone every
 * timestamp in the console is shown in.
 */
export default function TimeRangePicker() {
  const { range, setPreset, setCustom } = useTimeRange();
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState(toInputValue(range.from));
  const [to, setTo] = useState(toInputValue(range.to));
  const [problem, setProblem] = useState<string | null>(null);
  const fromId = useId();
  const toId = useId();

  function apply() {
    const f = new Date(`${from}:00Z`);
    const t = new Date(`${to}:00Z`);
    if (Number.isNaN(f.getTime()) || Number.isNaN(t.getTime())) {
      setProblem('Enter both dates.');
      return;
    }
    if (f >= t) {
      setProblem('The start must be before the end.');
      return;
    }
    setProblem(null);
    setCustom(f, t);
    setOpen(false);
  }

  return (
    <div className="range" role="group" aria-label="Time range">
      {PRESETS.map((p) => (
        <button
          key={p.key}
          type="button"
          className={`chip${range.key === p.key ? ' active' : ''}`}
          aria-pressed={range.key === p.key}
          onClick={() => {
            setOpen(false);
            setPreset(p.key);
          }}
        >
          {p.key}
        </button>
      ))}
      <button
        type="button"
        className={`chip${range.key === 'custom' ? ' active' : ''}`}
        aria-pressed={range.key === 'custom'}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {range.key === 'custom' ? range.label : 'Custom'}
      </button>
      {open && (
        <form
          className="range-custom card"
          onSubmit={(e) => {
            e.preventDefault();
            apply();
          }}
        >
          <label htmlFor={fromId}>From (UTC)</label>
          <input id={fromId} type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          <label htmlFor={toId}>To (UTC)</label>
          <input id={toId} type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
          {problem && (
            <p className="error small" role="alert">
              {problem}
            </p>
          )}
          <div className="actions">
            <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary">
              Apply
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
