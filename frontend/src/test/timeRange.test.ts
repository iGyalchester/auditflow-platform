import { describe, expect, it } from 'vitest';
import { deltaText, formatCount, humanize, parseControls, relativeTime } from '../util/format';
import { rangeQuery, resolveRange, withCustomRange, withPreset } from '../util/timeRange';

const NOW = new Date('2026-09-08T10:31:45Z');

describe('time range', () => {
  it('defaults to the last seven days ending now, rounded to the minute', () => {
    const range = resolveRange(new URLSearchParams(), NOW);
    expect(range.key).toBe('7d');
    expect(range.to.toISOString()).toBe('2026-09-08T10:31:00.000Z');
    expect(range.from.toISOString()).toBe('2026-09-01T10:31:00.000Z');
  });

  it('reads a preset and an explicit custom window from the URL', () => {
    expect(resolveRange(new URLSearchParams('range=24h'), NOW).from.toISOString()).toBe('2026-09-07T10:31:00.000Z');
    const custom = resolveRange(new URLSearchParams('from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z'), NOW);
    expect(custom.key).toBe('custom');
    expect(custom.label).toBe('Sep 1 – Sep 2');
  });

  it('falls back to the default for garbage or an inverted window', () => {
    expect(resolveRange(new URLSearchParams('range=1y'), NOW).key).toBe('7d');
    expect(resolveRange(new URLSearchParams('from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z'), NOW).key).toBe('7d');
    expect(resolveRange(new URLSearchParams('from=yesterday&to=today'), NOW).key).toBe('7d');
  });

  it('switching presets and custom windows keeps the other parameters', () => {
    const params = new URLSearchParams('type=AUTH_EVENT&from=a&to=b');
    expect(withPreset(params, '30d').toString()).toBe('type=AUTH_EVENT&range=30d');
    const custom = withCustomRange(new URLSearchParams('type=AUTH_EVENT&range=7d'), new Date('2026-09-01T00:00:00Z'), new Date('2026-09-02T00:00:00Z'));
    expect(custom.get('range')).toBeNull();
    expect(custom.get('from')).toBe('2026-09-01T00:00:00.000Z');
    expect(rangeQuery(new URLSearchParams('type=AUTH_EVENT&range=7d'))).toBe('?range=7d');
    expect(rangeQuery(new URLSearchParams('type=AUTH_EVENT'))).toBe('');
  });
});

describe('format', () => {
  it('counts, deltas, relative times, labels and controls', () => {
    expect(formatCount(1284)).toBe('1,284');
    expect(formatCount(12_900)).toBe('12.9K');
    expect(formatCount(null)).toBe('—');
    expect(deltaText(1284, 1100)).toBe('+17%');
    expect(deltaText(12, 20)).toBe('−40%');
    expect(deltaText(3, 0)).toBe('new');
    expect(deltaText(0, 0)).toBeNull();
    expect(deltaText(5, 5)).toBe('±0%');
    expect(relativeTime('2026-09-08T10:31:00Z', NOW)).toBe('just now');
    expect(relativeTime('2026-09-08T09:31:00Z', NOW)).toBe('1 h ago');
    expect(relativeTime('2026-09-06T09:31:00Z', NOW)).toBe('2 days ago');
    expect(humanize('LOGIN_FAILURE')).toBe('Login failure');
    expect(parseControls('SOC2:AC-2, GDPR:Art-30,junk')).toEqual([
      { framework: 'SOC2', control: 'AC-2' },
      { framework: 'GDPR', control: 'Art-30' },
    ]);
  });
});
