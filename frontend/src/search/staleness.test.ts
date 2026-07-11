// Usability round 2 (Theme T8, refresh split-brain): after the user's OWN mutation
// settles, the results grid must disclose that its snapshot may be stale instead of
// silently diverging from the live ops drawer. SPEC §4 snapshot semantics stay intact:
// disclosure only — never a silent re-fetch.
import { MutationObserver, QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { isMutationSettleEvent, resultsMayBeStale } from './staleness'

describe('isMutationSettleEvent', () => {
  it('matches only a mutation UPDATE that settled successfully', () => {
    expect(isMutationSettleEvent({ type: 'updated', action: { type: 'success' } })).toBe(true)
  })

  it('ignores errors (a refused action changed nothing), pendings and cache membership events', () => {
    expect(isMutationSettleEvent({ type: 'updated', action: { type: 'error' } })).toBe(false)
    expect(isMutationSettleEvent({ type: 'updated', action: { type: 'pending' } })).toBe(false)
    expect(isMutationSettleEvent({ type: 'added' })).toBe(false)
    expect(isMutationSettleEvent({ type: 'removed' })).toBe(false)
  })

  it('fires through a REAL mutation cache on success, and not on failure', async () => {
    const queryClient = new QueryClient()
    let settles = 0
    const unsubscribe = queryClient.getMutationCache().subscribe((event) => {
      if (isMutationSettleEvent(event)) settles += 1
    })
    const ok = new MutationObserver(queryClient, { mutationFn: () => Promise.resolve('done') })
    await ok.mutate()
    expect(settles).toBe(1)
    const failing = new MutationObserver(queryClient, {
      retry: false,
      mutationFn: () => Promise.reject(new Error('refused')),
    })
    await failing.mutate().catch(() => undefined)
    expect(settles).toBe(1)
    unsubscribe()
  })
})

describe('resultsMayBeStale', () => {
  it('discloses staleness once a mutation settled AFTER the snapshot was taken', () => {
    expect(resultsMayBeStale(true, 1_000, 2_000)).toBe(true)
  })

  it('stays silent with no rendered snapshot, no settled mutation, or a fresher snapshot', () => {
    expect(resultsMayBeStale(false, 1_000, 2_000)).toBe(false)
    expect(resultsMayBeStale(true, 1_000, null)).toBe(false)
    expect(resultsMayBeStale(true, 3_000, 2_000)).toBe(false)
    expect(resultsMayBeStale(true, 2_000, 2_000)).toBe(false)
  })
})
