// Pure presentation helpers for the sibling-diff Comparison view (SPEC §5.2). Kept out of
// the components so the semantics — glyph mapping (never hue-only), value rendering, timing
// bar scaling — are unit-testable without a DOM.
import type { TimingDelta, VariableChange, VariableDto } from '../../api/model'

/**
 * A stable, non-colour glyph per change kind (SPEC §10a: shape/glyph carries the meaning,
 * colour is only reinforcement). ± for changed, + / − for one-sided, = for same, ~ for the
 * over-cap pair we cannot fully compare.
 */
export function changeGlyph(change: VariableChange): string {
  switch (change) {
    case 'CHANGED':
      return '±'
    case 'ONLY_IN_SUBJECT':
      return '−'
    case 'ONLY_IN_SIBLING':
      return '+'
    case 'DIFFER_BEYOND_PREVIEW':
      return '~'
    case 'SAME':
      return '='
  }
}

export function changeLabel(change: VariableChange): string {
  switch (change) {
    case 'CHANGED':
      return 'changed'
    case 'ONLY_IN_SUBJECT':
      return 'only in this (failed) run'
    case 'ONLY_IN_SIBLING':
      return 'only in the sibling'
    case 'DIFFER_BEYOND_PREVIEW':
      return 'values differ beyond preview'
    case 'SAME':
      return 'same'
  }
}

/** True for the rows worth showing by default — the ones that actually differ. */
export function isDivergent(change: VariableChange): boolean {
  return change !== 'SAME'
}

/**
 * One variable value rendered for the side-by-side pane. The value is already the byte-capped
 * typed projection from the BFF; a truncated row shows its size instead of a value (the full
 * blob was deliberately never fetched — SPEC §5.2).
 */
export function renderValue(variable: VariableDto | undefined): string {
  if (variable === undefined) return '—'
  if (variable.truncated === true) {
    const kib = variable.sizeBytes !== undefined ? Math.round(variable.sizeBytes / 1024) : undefined
    return kib !== undefined ? `(${String(kib)} KiB — over preview cap)` : '(over preview cap)'
  }
  const value = variable.value
  if (value === null || value === undefined) return '(null)'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  // object | array | anything else — the JSON literal is the honest rendering
  return JSON.stringify(value)
}

export interface TimingScale {
  /** Largest completed duration across both sides — the 100% reference for the bars. */
  maxMs: number
}

export function timingScale(timings: TimingDelta[]): TimingScale {
  let maxMs = 1
  for (const t of timings) {
    if (t.subjectMs !== undefined) maxMs = Math.max(maxMs, t.subjectMs)
    if (t.siblingMs !== undefined) maxMs = Math.max(maxMs, t.siblingMs)
  }
  return { maxMs }
}

/** Bar width as a percentage of the scale, floored so a tiny non-zero bar stays visible. */
export function barWidthPct(ms: number | undefined, scale: TimingScale): number {
  if (ms === undefined || ms <= 0) return 0
  return Math.max((ms / scale.maxMs) * 100, 1.5)
}

/**
 * The signed delta phrase for a timing row (subject − sibling). A missing side (or the
 * stalled, never-completed step) has no computable delta and says so honestly.
 */
export function deltaPhrase(t: TimingDelta): string {
  if (t.subjectUnfinished === true && t.subjectMs === undefined) return 'never completed here'
  if (t.deltaMs === undefined) {
    if (t.subjectMs === undefined) return 'not on the failed run'
    if (t.siblingMs === undefined) return 'not on the sibling'
    return ''
  }
  if (t.deltaMs === 0) return 'same duration'
  const slower = t.deltaMs > 0
  const secs = Math.abs(t.deltaMs) / 1000
  const magnitude =
    secs >= 1 ? `${secs.toFixed(secs >= 10 ? 0 : 1)}s` : `${String(Math.abs(t.deltaMs))}ms`
  return `${slower ? 'slower' : 'faster'} by ${magnitude}`
}
