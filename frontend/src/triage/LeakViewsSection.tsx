import { Link } from 'react-router'
import type { LeakDefinitionCount, LeakWindows } from '../api/model'
import { formatCount } from '../lib/format'
import { LEAK_VIEWS, type LeakWindowId } from '../views/leakViews'
import { useLeakViews } from './useTriage'

/**
 * Stage 0 "Leak views" (SPEC §4, R-BAU-02): the slow leaks that never enter a failure lane —
 * long-RUNNING and long-SUSPENDED instances, grouped PER DEFINITION with count-only Stage-0
 * totals ("vacationRequest: 212 > 30d"). Each count is a plain deep link into Stage 1: clicking
 * replays the exact URL search the count was measured against (URL primacy), riding the same
 * saved/system-view machinery.
 *
 * Honesty (R-SEM-05): the SUSPENDED window is defined against startTime — Flowable records no
 * suspension timestamp — and its chip label says "started > 7 days ago", never time-since-
 * suspension. When an engine is unreachable or a definition list truncated, every count wears
 * the "≥" lower-bound badge and the panel names the excluded engines (R-SEM-12), matching the grid.
 */
export function LeakViewsSection() {
  const { data, isPending, isError } = useLeakViews()

  // Secondary chrome: a failed/absent fetch simply hides the panel — it must never block the
  // failure signal above it.
  if (isPending || isError) return null

  const definitions = data.definitions ?? []
  const windows = data.windows ?? {}
  const lowerBound = data.lowerBound === true
  const unavailable = data.unavailableEngines ?? []

  return (
    <section className="leak-views" aria-label="Leak views">
      <h2>Leak views</h2>
      <p className="leak-views-caption">
        Long-running and long-suspended instances by definition — the slow leaks that never enter a
        failure lane. Age is measured from start time.
      </p>
      {lowerBound && (
        <div className="partial-banner" role="alert">
          {unavailable.length > 0
            ? `${unavailable.join(', ')}: unreachable or capped — every count below is a lower bound (≥).`
            : 'A definition list was truncated — every count below is a lower bound (≥).'}
        </div>
      )}
      {definitions.length === 0 ? (
        <div className="zero-state">
          No leaks — every reachable engine is clean of long-running and long-suspended instances in
          these windows.
        </div>
      ) : (
        <ul className="leak-definition-list">
          {definitions.map((def) => (
            <LeakDefinitionRow
              key={def.definitionKey ?? ''}
              def={def}
              windows={windows}
              lowerBound={lowerBound}
            />
          ))}
        </ul>
      )}
    </section>
  )
}

function LeakDefinitionRow({
  def,
  windows,
  lowerBound,
}: {
  def: LeakDefinitionCount
  windows: LeakWindows
  lowerBound: boolean
}) {
  const definitionKey = def.definitionKey ?? ''
  return (
    <li className="leak-definition">
      <span className="leak-definition-key" title={definitionKey}>
        {definitionKey}
      </span>
      <span className="leak-chips">
        {LEAK_VIEWS.map((view) => {
          const count = countOf(def, view.id)
          const startedBefore = boundaryOf(windows, view.id)
          if (count <= 0 || startedBefore === '') return null
          const base = view.note ? `${view.label} — ${view.note}` : view.label
          // Convey the lower-bound state to the accessible name too, not just the visual "≥".
          const title = lowerBound ? `at least ${formatCount(count)} — ${base}` : base
          return (
            <Link
              key={view.id}
              className="leak-chip view-chip"
              title={title}
              to={`/search?${view.search(definitionKey, startedBefore)}`}
            >
              <span className="leak-count">
                {lowerBound ? '≥' : ''}
                {formatCount(count)}
              </span>{' '}
              <span className="leak-window">{view.short}</span>
            </Link>
          )
        })}
      </span>
    </li>
  )
}

function countOf(def: LeakDefinitionCount, id: LeakWindowId): number {
  return def[id] ?? 0
}

function boundaryOf(windows: LeakWindows, id: LeakWindowId): string {
  return windows[id] ?? ''
}
