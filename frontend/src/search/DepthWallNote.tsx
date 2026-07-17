// #245: the depth-wall filter seam (docs/KWAY-PAGING.md §"depth wall is a filter seam").
// Extracted from SearchPage so the render guard is testable in isolation: the note renders
// whenever depthCapped fired — INDEPENDENT of whether any rows accumulated. Previously the
// message also required a last-row startTime, so a depth cap over an empty accumulated set
// rendered an empty .load-more region: no message, no explanation, nothing to act on.
import { formatClock } from '../lib/format'

/** The [what happened] lead — shared by both branches (SPEC §10a message style). */
export const DEPTH_WALL_WHAT = 'Reached the paging depth on at least one engine'

/** The LONG explanation (title) behind the short visible note (SPEC §10a A-copy pattern). */
export const DEPTH_WALL_HINT =
  'deep paging stops at a fixed per-engine depth (the depth wall) — matches past it exist on the engine but "Load more" cannot reach them; narrowing the search (a started-before time bound or tighter filters) starts a fresh, shallower page walk that can'

/**
 * The depth-cap notice + filter-seam CTA under the results grid. When rows have loaded,
 * the last-shown startTime pre-fills the "continue by narrowing" bound ([next move]).
 * When no rows accumulated (lastStartTime undefined) there is no honest bound to offer,
 * so the note explains why the grid is empty-or-thin and points at manual narrowing
 * instead — it must never vanish, because it is the one message the user has to act on
 * to keep paging.
 */
export function DepthWallNote({
  lastStartTime,
  onNarrow,
}: {
  lastStartTime: string | undefined
  onNarrow: (startedBefore: string) => void
}) {
  return (
    <p className="load-more-depthwall" role="note" title={DEPTH_WALL_HINT}>
      {lastStartTime !== undefined ? (
        <>
          {DEPTH_WALL_WHAT}.{' '}
          <button
            type="button"
            className="linklike"
            onClick={() => {
              onNarrow(lastStartTime)
            }}
          >
            Continue by narrowing to started before {formatClock(Date.parse(lastStartTime))}
          </button>
        </>
      ) : (
        <>
          {DEPTH_WALL_WHAT} before any rows could be loaded — matching instances may sit beyond the
          wall. Narrow the search (a started-before time bound or tighter filters) and run it again
          to reach them.
        </>
      )}
    </p>
  )
}
