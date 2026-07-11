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

/** Wall-clock time of the last successfully settled mutation in this session — every
 *  mutation in this app's single QueryClient is the user's own action. */
export function useLastMutationSettledAt(): number | null {
  const queryClient = useQueryClient()
  const [settledAt, setSettledAt] = useState<number | null>(null)
  useEffect(
    () =>
      queryClient.getMutationCache().subscribe((event) => {
        if (isMutationSettleEvent(event)) setSettledAt(Date.now())
      }),
    [queryClient],
  )
  return settledAt
}
