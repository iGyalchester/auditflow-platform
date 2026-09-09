import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { resolveRange, withCustomRange, withPreset, type PresetKey, type TimeRange } from '../util/timeRange';

/** The global window from the URL, and the two ways to change it. */
export function useTimeRange(): {
  range: TimeRange;
  fromIso: string;
  toIso: string;
  setPreset: (key: PresetKey) => void;
  setCustom: (from: Date, to: Date) => void;
} {
  const [params, setParams] = useSearchParams();
  const rangeKey = `${params.get('range')}|${params.get('from')}|${params.get('to')}`;
  // recomputed only when the URL's range changes, so "now" stays fixed
  // across renders and the dashboard does not refetch on every keystroke
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const range = useMemo(() => resolveRange(params), [rangeKey]);
  return {
    range,
    fromIso: range.from.toISOString(),
    toIso: range.to.toISOString(),
    setPreset: (key) => setParams(withPreset(params, key)),
    setCustom: (from, to) => setParams(withCustomRange(params, from, to)),
  };
}
