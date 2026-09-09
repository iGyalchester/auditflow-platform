/** Display helpers shared by the pages. Dates arrive as ISO strings from the API. */

const compact = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const plain = new Intl.NumberFormat();

/** 1,284 stays exact; 12,900 becomes 12.9K; the tooltip shows the exact value. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value < 10_000 ? plain.format(value) : compact.format(value);
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone: 'UTC',
    timeZoneName: 'short',
  });
}

/** "just now", "4 min ago", "3 h ago", "2 days ago". */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return '';
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return '';
  const seconds = Math.max(0, Math.floor((now.getTime() - then.getTime()) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

/** "Sep 3" for a YYYY-MM-DD day. */
export function shortDay(day: string): string {
  const date = new Date(`${day}T00:00:00Z`);
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

/** "+12%" / "−8%" / "new" (previous was zero) / null when both are zero. */
export function deltaText(current: number, previous: number): string | null {
  if (current === 0 && previous === 0) return null;
  if (previous === 0) return 'new';
  const pct = Math.round(((current - previous) / previous) * 100);
  if (pct === 0) return '±0%';
  return pct > 0 ? `+${pct}%` : `−${Math.abs(pct)}%`;
}

/** AUTH_EVENT -> "Auth event"; LOGIN_FAILURE -> "Login failure". */
export function humanize(value: string | null | undefined): string {
  if (!value) return '—';
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** "SOC2:AC-2,GDPR:Art-30" -> [{framework: "SOC2", control: "AC-2"}, ...] */
export function parseControls(encoded: string | null | undefined): { framework: string; control: string }[] {
  if (!encoded) return [];
  return encoded
    .split(',')
    .map((pair) => pair.trim())
    .filter((pair) => pair.includes(':'))
    .map((pair) => {
      const i = pair.indexOf(':');
      return { framework: pair.slice(0, i), control: pair.slice(i + 1) };
    });
}

export const FRAMEWORK_LABELS: Record<string, string> = { SOC2: 'SOC 2', GDPR: 'GDPR', HIPAA: 'HIPAA' };

export function frameworkLabel(framework: string): string {
  return FRAMEWORK_LABELS[framework.toUpperCase()] ?? framework;
}
