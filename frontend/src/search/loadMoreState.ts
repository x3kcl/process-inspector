// #273: the two TERMINAL futures of the deep-paging "Load more" chain must never read (or
// render) identically — a lane that simply has no more pages left (genuine exhaustion) vs. one
// where at least one engine hit its own depth wall (DepthWallNote's territory). Extracted from
// SearchPage so the render-guard decisions are testable in isolation, matching gridCount.ts /
// DepthWallNote.tsx's existing pattern of pulling pure logic out of the page component.

/**
 * True once this search has paged at least once (pageCount > 1, i.e. "Load more" was clicked
 * at least once) AND every lane has since drained — no next page, and it is a GENUINE end, not
 * the depth wall (depthCapped === true is DepthWallNote's distinct escalation, never this note).
 * Gates both the "Showing all N results" note and the disappearance of the Load-more button.
 *
 * Deliberately false when pageCount is 1: an ordinary search that never overflowed has nothing
 * to declare "drained" about — the bare grid-count headline already says "N instances" with no
 * qualifier (gridCount.ts #244), and adding a redundant note here would be noise on ~95% of
 * searches that never touch deep paging at all.
 */
export function isFullyDrainedAfterPaging(
  pageCount: number,
  hasNextPage: boolean,
  depthCapped: boolean | undefined,
): boolean {
  return pageCount > 1 && !hasNextPage && depthCapped !== true
}

/**
 * Whether the "Load more" region should render at all: unchanged from the pre-#273 guard
 * (hasNextPage, a deep-paged snapshot seam, or the depth wall) PLUS the new positive terminal
 * case — so the region does not just silently vanish once the LAST page loads, it explicitly
 * confirms completion instead.
 */
export function loadMoreRegionVisible(
  hasNextPage: boolean,
  pagingCoherence: string | undefined,
  depthCapped: boolean | undefined,
  pageCount: number,
): boolean {
  return (
    hasNextPage ||
    pagingCoherence === 'snapshot' ||
    depthCapped === true ||
    isFullyDrainedAfterPaging(pageCount, hasNextPage, depthCapped)
  )
}
