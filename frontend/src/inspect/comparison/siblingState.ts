// The honesty core of the sibling-diff pane (SPEC §5.2, R-SEM-12). The Compare tab must
// never let an API/network failure masquerade as "no comparable sibling exists" — a false
// negative here makes an operator trust that a failed run had no known-good precedent when
// in truth the query never reached the engine. This module is the single pure decision that
// keeps that distinction explicit, and — like deriveHonesty — is unit-tested in isolation
// from React so the branch that separates infra failure from a real domain empty state can
// be pinned down without a rendered tree.

/**
 * A sibling fetch either failed because the operator's input is wrong (a 400/404 for a
 * non-existent / wrong-engine process-instance id — fixable by pasting a different id) or
 * because the API/network is unhealthy (anything else: 5xx, a missing route, a downed
 * proxy, a raw fetch reject). The two demand different UI copy and, per the doctrine, must
 * never both silently degrade to an empty state.
 */
export type SiblingErrorKind = 'not-found' | 'infra'

/** Duck-typed so this module needn't import the openapi-fetch client (and its side-effecting
 *  module load) — an ApiError carries a numeric `status`; a raw network reject carries none. */
function httpStatusOf(error: unknown): number | undefined {
  if (error !== null && typeof error === 'object' && 'status' in error) {
    const { status } = error
    if (typeof status === 'number') return status
  }
  return undefined
}

export function classifySiblingError(error: unknown): SiblingErrorKind {
  const status = httpStatusOf(error)
  // A 400 (malformed id) or 404 (no such instance on this engine) is operator-fixable input.
  // Everything else — 5xx, missing route, proxy down, opaque network reject — is infra.
  return status === 400 || status === 404 ? 'not-found' : 'infra'
}

/** What the Compare pane should render, once a sibling has (or has not) been resolved. */
export type ComparePane =
  | { kind: 'nearest-pending' }
  /** Theme 2: the auto-suggest query FAILED. This is NOT proof that no sibling exists. */
  | { kind: 'nearest-error' }
  /** The backend explicitly answered found=false — the only true domain empty state. */
  | { kind: 'no-sibling' }
  | { kind: 'diff-pending' }
  | { kind: 'diff-error'; errorKind: SiblingErrorKind }
  | { kind: 'diff-ready' }

export interface QueryFacet {
  isPending: boolean
  isError: boolean
}

/**
 * Pick the pane from the two queries' states. The nearest-sibling branch only matters while
 * no sibling is selected: once the operator has pasted an id (or accepted the suggestion),
 * the diff query owns the surface and a stale auto-suggest failure is irrelevant.
 */
export function selectComparePane(input: {
  siblingSelected: boolean
  nearest: QueryFacet & { found: boolean | undefined }
  diff: QueryFacet & { error: unknown }
}): ComparePane {
  if (!input.siblingSelected) {
    if (input.nearest.isPending) return { kind: 'nearest-pending' }
    // Honesty doctrine: a failed auto-suggest is an infra failure, never "no sibling". Only
    // an actual found=false answer is allowed to render the domain empty state below.
    if (input.nearest.isError) return { kind: 'nearest-error' }
    return { kind: 'no-sibling' }
  }
  if (input.diff.isPending) return { kind: 'diff-pending' }
  if (input.diff.isError)
    return { kind: 'diff-error', errorKind: classifySiblingError(input.diff.error) }
  return { kind: 'diff-ready' }
}
