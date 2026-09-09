/**
 * Chart colours, from the dataviz reference palette and validated with
 * the skill's validator against this console's real surfaces
 * (light card #ffffff, dark card #161d1c):
 *
 *   node validate_palette.js "#2a78d6,#eb6834,#1baf7a,#eda100,#e87ba4,#008300" --mode light --surface "#ffffff"
 *     -> lightness band, chroma floor, CVD (worst adjacent ΔE 9.1), normal-vision (19.6): PASS;
 *        contrast WARN on aqua/yellow/magenta (relief: every chart has direct labels or its table view)
 *   node validate_palette.js "#3987e5,#d95926,#199e70,#c98500,#d55181,#008300" --mode dark --surface "#161d1c"
 *     -> ALL CHECKS PASS (worst adjacent CVD ΔE 8.4, normal-vision 19.3, all >= 3:1)
 *   node validate_palette.js "#86b6ef,#5598e7,#256abf,#104281" --ordinal --mode light --surface "#ffffff"
 *     -> ALL CHECKS PASS (light end 2.11:1)
 *   node validate_palette.js "#9ec5f4,#6da7ec,#3987e5,#184f95" --ordinal --mode dark --surface "#161d1c"
 *     -> ALL CHECKS PASS (dark end 2.11:1)
 *
 * Text on charts always uses the app's ink tokens, never a series colour.
 */

/** Slot 1 of the categorical palette: every single-series chart uses it. */
export const SERIES = { light: '#2a78d6', dark: '#3987e5' };

/**
 * Categorical slots in fixed order, for the one chart with several
 * nominal series (the operator's per-customer stack). Assigned in order,
 * never cycled: past six, the rest fold into "Other".
 */
export const CATEGORICAL = {
  light: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300'],
  dark: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300'],
};

/** One-hue ordinal ramp for risk, LOW -> CRITICAL, light -> dark. */
export const RISK_RAMP = {
  light: ['#86b6ef', '#5598e7', '#256abf', '#104281'],
  dark: ['#9ec5f4', '#6da7ec', '#3987e5', '#184f95'],
};

export const RISK_ORDER = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;

export function isDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
}

export function seriesColor(): string {
  return isDark() ? SERIES.dark : SERIES.light;
}

export function riskColor(level: string): string {
  const ramp = isDark() ? RISK_RAMP.dark : RISK_RAMP.light;
  const index = RISK_ORDER.indexOf(level as (typeof RISK_ORDER)[number]);
  return ramp[index < 0 ? 0 : index];
}

export function categoricalColor(index: number): string {
  const slots = isDark() ? CATEGORICAL.dark : CATEGORICAL.light;
  return slots[Math.min(index, slots.length - 1)];
}

/** Recharts styling shared by every chart: recessive grid and axes, ink from the tokens. */
export const AXIS_TICK = { fill: 'var(--muted)', fontSize: 11 };
export const TOOLTIP_STYLE = { background: 'var(--card)', border: '1px solid var(--border)', color: 'var(--text)', borderRadius: 8 };
