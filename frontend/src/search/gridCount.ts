// #244: the results-toolbar headline is literally rows.length — the rows FETCHED so far,
// not the match total (the BFF caps every page per engine, and deep paging adds a depth
// wall). Once either cap is in play the bare number silently undercounts, so the label
// says what it actually measures ("loaded so far") — the same label-the-number doctrine
// as the triage as-of stamps (#209/#244) and coverage disclosure (#236). When the result
// set is complete the qualifier stays OFF: an exact count needs no hedge.
import { formatCount } from '../lib/format'

/**
 * True when the grid's row count is known NOT to cover every match — more pages remain
 * behind "Load more", or at least one engine hit the paging depth wall. Only then does
 * the "loaded so far" qualifier render.
 */
export function gridCountIsPartial(
  hasNextPage: boolean,
  depthCapped: boolean | undefined,
): boolean {
  return hasNextPage || depthCapped === true
}

/**
 * The headline count label: exact ("680 instances") when the set is complete, qualified
 * ("680 instances loaded so far") when it is a capped prefix — never a bare undercount.
 * Keeps the W2 #7 unit token: these are INSTANCES.
 */
export function gridCountLabel(
  count: number,
  hasNextPage: boolean,
  depthCapped: boolean | undefined,
): string {
  const base = `${formatCount(count)} instances`
  return gridCountIsPartial(hasNextPage, depthCapped) ? `${base} loaded so far` : base
}

/** The LONG explanation (title) behind the "loaded so far" qualifier (SPEC A-copy). */
export const GRID_COUNT_PARTIAL_HINT =
  'more instances match this search than the grid has fetched — results load in capped pages per engine; Load more (below the grid) extends the list, and the bulk filter-scope ~N is the engine-reported total across all matches'
