import { frameworkLabel, parseControls } from '../util/format';

/** "SOC2:AC-2,GDPR:Art-30" as labelled chips; nothing when there are none. */
export default function ControlChips({ controls, max = 4 }: { controls: string | null | undefined; max?: number }) {
  const parsed = parseControls(controls);
  if (parsed.length === 0) return <span className="muted small">none</span>;
  const shown = parsed.slice(0, max);
  const rest = parsed.length - shown.length;
  return (
    <span className="chips-inline">
      {shown.map((c) => (
        <span key={`${c.framework}:${c.control}`} className="chip-static" title={`${frameworkLabel(c.framework)} control ${c.control}`}>
          {frameworkLabel(c.framework)} {c.control}
        </span>
      ))}
      {rest > 0 && <span className="muted small">+{rest}</span>}
    </span>
  );
}
