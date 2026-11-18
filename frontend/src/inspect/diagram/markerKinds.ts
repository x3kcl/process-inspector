import type { OverlayAttrs } from 'diagram-js/lib/features/overlays/Overlays'

/**
 * The diagram marker vocabulary (SPEC §4, §10a) — ONE table drives both the canvas
 * decoration (DiagramCanvas) and its legend (DiagramLegend), so a new marker kind is one
 * new row here. Meaning is carried by stroke style/width or a glyph, never hue alone.
 */
export interface MarkerKind {
  /** The DiagramCanvas prop (a `markers` field or a picker prop) holding this kind's ids. */
  key: string
  /** CSS class added to the shape (styled in styles.css under `.diagram-canvas`). */
  markerClass: string
  /** Legend swatch outline — mirrors the marker's CSS rule; absent = badge-only kind. */
  stroke?: { color: string; width: number; dash?: string; fill?: string }
  /** Overlay badge: glyph text, its CSS classes and position on the shape. */
  glyph?: { text: string; className: string; position: OverlayAttrs['position'] }
  /** aria-label + title of the overlay badge. */
  label: string
  /** Legend wording (kept distinct from page text such as the Compare tab's own legend). */
  legend: string
}

const INK = 'var(--color-ink)'
const PICKER = '#0a5c36'

export const MARKER_KINDS: MarkerKind[] = [
  {
    key: 'activeActivityIds',
    markerClass: 'marker-active',
    stroke: { color: 'var(--color-success-alt)', width: 2, fill: '#d1f2eb' },
    label: 'holds a token',
    legend: 'holds a token (active)',
  },
  {
    key: 'deadLetterActivityIds',
    markerClass: 'marker-deadletter',
    stroke: { color: 'var(--color-danger)', width: 2, fill: 'var(--color-danger-fill)' },
    glyph: { text: '⚠', className: 'dlq-overlay', position: { top: -10, right: 10 } },
    label: 'dead-letter job on this activity',
    legend: 'dead-letter job',
  },
  {
    key: 'subjectOnlyActivityIds',
    markerClass: 'marker-diverge-subject',
    stroke: { color: INK, width: 4 },
    glyph: {
      text: '▲',
      className: 'diverge-glyph diverge-glyph-subject',
      position: { bottom: -6, left: -6 },
    },
    label: 'only the failed run took this step',
    legend: 'failed run only',
  },
  {
    key: 'siblingOnlyActivityIds',
    markerClass: 'marker-diverge-sibling',
    stroke: { color: INK, width: 2, dash: '5 4' },
    glyph: {
      text: '△',
      className: 'diverge-glyph diverge-glyph-sibling',
      position: { bottom: -6, left: -6 },
    },
    label: 'only the successful sibling took this step',
    legend: 'sibling run only',
  },
  {
    key: 'addedActivityIds',
    markerClass: 'marker-diff-added',
    stroke: { color: 'var(--color-success-alt)', width: 3 },
    glyph: { text: '+', className: 'diff-glyph diff-glyph-added', position: { top: -8, left: -8 } },
    label: 'added in this version',
    legend: 'added (version diff)',
  },
  {
    key: 'changedActivityIds',
    markerClass: 'marker-diff-changed',
    stroke: { color: INK, width: 3, dash: '4 2' },
    glyph: {
      text: '~',
      className: 'diff-glyph diff-glyph-changed',
      position: { top: -8, right: -8 },
    },
    label: 'changed between versions',
    legend: 'changed (version diff)',
  },
  {
    key: 'blockerActivityIds',
    markerClass: 'marker-finding-blocker',
    stroke: { color: 'var(--color-danger)', width: 4, dash: '2 2' },
    glyph: {
      text: '✖',
      className: 'finding-glyph marker-finding-blocker-glyph',
      position: { top: -10, right: 10 },
    },
    label: 'pre-check blocker on this activity',
    legend: 'pre-check blocker',
  },
  {
    key: 'warningActivityIds',
    markerClass: 'marker-finding-warning',
    stroke: { color: INK, width: 3, dash: '8 3' },
    glyph: {
      text: '!',
      className: 'finding-glyph marker-finding-warning-glyph',
      position: { top: -10, right: 10 },
    },
    label: 'pre-check warning on this activity',
    legend: 'pre-check warning',
  },
  {
    key: 'mappedActivityIds',
    markerClass: 'marker-mapped',
    glyph: {
      text: '↦',
      className: 'finding-glyph marker-mapped-glyph',
      position: { bottom: -6, right: 10 },
    },
    label: 'mapped',
    legend: 'mapped to a target',
  },
  {
    key: 'pickerSourceIds',
    markerClass: 'marker-picker-source',
    stroke: { color: PICKER, width: 4 },
    glyph: {
      text: 'S',
      className: 'picker-glyph marker-picker-source-glyph',
      position: { top: -10, left: -10 },
    },
    label: 'picked as the source (token canceled here)',
    legend: 'source — token canceled',
  },
  {
    key: 'pickerTargetIds',
    markerClass: 'marker-picker-target',
    stroke: { color: PICKER, width: 3, dash: '6 3' },
    glyph: {
      text: 'T',
      className: 'picker-glyph marker-picker-target-glyph',
      position: { top: -10, left: -10 },
    },
    label: 'picked as the target (a fresh token starts here)',
    legend: 'target — fresh token',
  },
  // #677 execution overlay (from the timeline): route, completion, armed, durations.
  {
    key: 'completedActivityIds',
    markerClass: 'marker-completed',
    stroke: { color: 'var(--color-success-alt)', width: 2, fill: 'var(--color-success-bg)' },
    // left 8: clear of the picker S/T glyph (left -10, 16 px) on the same node.
    glyph: { text: '✓', className: 'exec-glyph', position: { top: -10, left: 8 } },
    label: 'completed',
    legend: 'completed',
  },
  {
    key: 'armedActivityIds',
    markerClass: 'marker-armed',
    stroke: { color: INK, width: 2, dash: '3 3' },
    label: 'armed, not taken',
    legend: 'armed, not taken',
  },
  {
    key: 'takenFlowIds',
    markerClass: 'marker-taken',
    stroke: { color: 'var(--color-success-alt)', width: 3 },
    label: 'route taken',
    legend: 'route taken',
  },
  {
    key: 'repeatedFlowIds',
    markerClass: 'marker-taken-repeat',
    glyph: { text: '×N', className: 'exec-glyph', position: { top: -10, left: 10 } },
    label: 'times taken',
    legend: 'route taken N times',
  },
  {
    key: 'durationActivityIds',
    markerClass: 'marker-duration',
    glyph: { text: 'ms', className: 'exec-glyph', position: { top: -12, right: 10 } },
    label: 'step duration',
    legend: 'step duration',
  },
  // #785 definition heat: text badge inside the shape (bottom 4, right 4: clear of every glyph
  // above) + a border that differs by tier. The figure and the tier word are in the badge text.
  {
    key: 'heatDominantActivityIds',
    markerClass: 'marker-heat-dominant',
    stroke: { color: INK, width: 4 },
    glyph: {
      text: 'heat',
      className: 'heat-badge heat-badge-dominant',
      position: { bottom: 4, right: 4 },
    },
    label: 'heat, dominant',
    legend: 'heat badge, dominant: ≥ 40 % of measured time (heavy solid border)',
  },
  {
    key: 'heatWarmActivityIds',
    markerClass: 'marker-heat-warm',
    stroke: { color: INK, width: 2 },
    glyph: {
      text: 'heat',
      className: 'heat-badge heat-badge-warm',
      position: { bottom: 4, right: 4 },
    },
    label: 'heat, warm',
    legend: 'heat badge, warm: 10–40 % of measured time (solid border)',
  },
  {
    key: 'heatCoolActivityIds',
    markerClass: 'marker-heat-cool',
    stroke: { color: INK, width: 2, dash: '4 3' },
    glyph: {
      text: 'heat',
      className: 'heat-badge heat-badge-cool',
      position: { bottom: 4, right: 4 },
    },
    label: 'heat, cool',
    legend: 'heat badge, cool: under 10 % of measured time, or armed only (dashed border)',
  },
]

/** Per-id badge text overriding a kind's fixed glyph text (×2, 143 ms), keyed by kind key. */
export type GlyphTexts = Partial<Record<string, Map<string, string>>>

/** Marker ids per kind key, e.g. `{ ...markers, pickerSourceIds, pickerTargetIds }`. */
export type MarkerSets = Partial<Record<string, string[] | undefined>>

/** The kinds with at least one id — what the canvas paints and the legend lists. */
export function presentKinds(sets: MarkerSets): MarkerKind[] {
  return MARKER_KINDS.filter((kind) => (sets[kind.key] ?? []).length > 0)
}
