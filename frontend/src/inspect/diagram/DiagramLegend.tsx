import type { MarkerKind } from './markerKinds'

/**
 * Legend for the marker kinds actually on the canvas (SPEC §4): each entry repeats the
 * marker's outline style and glyph next to its words. Nothing on the canvas → no legend.
 */
export function DiagramLegend({ kinds }: { kinds: MarkerKind[] }) {
  if (kinds.length === 0) return null
  return (
    <ul className="diagram-legend" aria-label="diagram legend">
      {kinds.map((kind) => (
        <li key={kind.key} data-kind={kind.key}>
          {kind.stroke !== undefined && (
            <svg className="diagram-legend-swatch" width="22" height="14" aria-hidden="true">
              <rect
                x="2"
                y="2"
                width="18"
                height="10"
                rx="2"
                style={{
                  stroke: kind.stroke.color,
                  strokeWidth: kind.stroke.width,
                  strokeDasharray: kind.stroke.dash,
                  fill: kind.stroke.fill ?? 'none',
                }}
              />
            </svg>
          )}
          {kind.glyph !== undefined && (
            <span className={kind.glyph.className} aria-hidden="true">
              {kind.glyph.text}
            </span>
          )}{' '}
          {kind.legend}
        </li>
      ))}
    </ul>
  )
}
