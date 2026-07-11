// Usability round 2 (Theme T8, refresh split-brain): the results grid keeps SPEC §4
// snapshot semantics — it NEVER silently re-fetches — but once one of the user's OWN
// mutations settles, the snapshot header must disclose that the grid may have diverged
// from the live ops drawer, instead of letting a stale grid masquerade as current state.
// Disclosure only: Refresh stays the single, explicit re-run.
import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'

/** Structural view of a TanStack mutation-cache notify event — only what we filter on. */
interface MutationCacheEventLike {
  type: string
  action?: { type: string }
}

/** True for the mutation-cache event that marks a mutation as settled SUCCESSFULLY.
 *  Errors are excluded on purpose: a refused corrective action changed nothing
 *  ("Nothing happened" doctrine), so it cannot have outdated the grid. */
export function isMutationSettleEvent(event: MutationCacheEventLike): boolean {
  return event.type === 'updated' && event.action?.type === 'success'
}

/** Should the snapshot header disclose post-mutation staleness? Pure so the honesty rule
 *  is unit-testable: only an already-rendered snapshot OLDER than the latest settled
 *  mutation gets the chip. Slightly over-cautious by design — a read-only "Verify now"
 *  also counts — because "may be stale" over-disclosed is honest, silence is not. */
export function resultsMayBeStale(
  hasResults: boolean,
  dataUpdatedAt: number,
  lastMutationSettledAt: number | null,
): boolean {
  return hasResults && lastMutationSettledAt !== null && lastMutationSettledAt > dataUpdatedAt
}

/** Structural view of the mutation cache — only what the mount-time seed reads. */
interface MutationCacheLike {
  getAll: () => { state: { status: string; submittedAt: number } }[]
}

/** Mount-time seed (external review, round 2): a mutation that settled while this
 *  surface was UNMOUNTED — act from the drawer on another page, navigate back to the
 *  still-cached snapshot — must still raise the chip. The cache keeps settled mutations
 *  (gcTime), so scan it; submittedAt is the conservative lower bound for the settle
 *  time (a success can only settle at or after its submit). */
export function lastSettleSeedFromCache(cache: MutationCacheLike): number | null {
  let latest: number | null = null
  for (const mutation of cache.getAll()) {
    const { status, submittedAt } = mutation.state
    if (status === 'success' && submittedAt > 0 && (latest === null || submittedAt > latest)) {
      latest = submittedAt
    }
  }
  return latest
}

/** Wall-clock time of the last successfully settled mutation in this session — every
 *  mutation in this app's single QueryClient is the user's own action. */
export function useLastMutationSettledAt(): number | null {
  const queryClient = useQueryClient()
  const [settledAt, setSettledAt] = useState<number | null>(() =>
    lastSettleSeedFromCache(queryClient.getMutationCache()),
  )
  useEffect(
    () =>
      queryClient.getMutationCache().subscribe((event) => {
        if (isMutationSettleEvent(event)) setSettledAt(Date.now())
      }),
    [queryClient],
  )
  return settledAt
}
