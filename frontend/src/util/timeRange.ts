/**
 * The global time range, kept in the URL (?range=7d, or ?from=…&to=…) so
 * every page and every link shares it and a URL pasted to a colleague
 * shows the same window. Windows are half-open [from, to) in UTC, the
 * same convention the gateway uses.
 */

export const PRESETS = [
  { key: '24h', label: 'Last 24 hours', hours: 24 },
  { key: '7d', label: 'Last 7 days', hours: 24 * 7 },
  { key: '30d', label: 'Last 30 days', hours: 24 * 30 },
  { key: '90d', label: 'Last 90 days', hours: 24 * 90 },
] as const;

export type PresetKey = (typeof PRESETS)[number]['key'];

export const DEFAULT_PRESET: PresetKey = '7d';

export interface TimeRange {
  from: Date;
  to: Date;
  /** the preset key, or "custom" */
  key: PresetKey | 'custom';
  label: string;
}

function isPreset(value: string | null): value is PresetKey {
  return PRESETS.some((p) => p.key === value);
}

/** Round "now" down to the minute so a page does not re-fetch on every render. */
function nowRounded(now: Date): Date {
  return new Date(Math.floor(now.getTime() / 60_000) * 60_000);
}

export function resolveRange(params: URLSearchParams, now: Date = new Date()): TimeRange {
  const from = params.get('from');
  const to = params.get('to');
  if (from && to) {
    const f = new Date(from);
    const t = new Date(to);
    if (!Number.isNaN(f.getTime()) && !Number.isNaN(t.getTime()) && f < t) {
      return { from: f, to: t, key: 'custom', label: `${shortDate(f)} – ${shortDate(t)}` };
    }
  }
  const key = isPreset(params.get('range')) ? (params.get('range') as PresetKey) : DEFAULT_PRESET;
  const preset = PRESETS.find((p) => p.key === key)!;
  const end = nowRounded(now);
  return { from: new Date(end.getTime() - preset.hours * 3_600_000), to: end, key, label: preset.label };
}

/** The same URL with the range parameters replaced; other parameters kept. */
export function withPreset(params: URLSearchParams, key: PresetKey): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete('from');
  next.delete('to');
  next.set('range', key);
  return next;
}

export function withCustomRange(params: URLSearchParams, from: Date, to: Date): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete('range');
  next.set('from', from.toISOString());
  next.set('to', to.toISOString());
  return next;
}

/** The range part of a query string, to carry the window into a link. */
export function rangeQuery(params: URLSearchParams): string {
  const kept = new URLSearchParams();
  for (const key of ['range', 'from', 'to']) {
    const value = params.get(key);
    if (value) kept.set(key, value);
  }
  const s = kept.toString();
  return s ? `?${s}` : '';
}

export function shortDate(date: Date): string {
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
}
