// Usability round 2 (Theme T8, refresh split-brain): after the user's OWN mutation
// settles, the results grid must disclose that its snapshot may be stale instead of
// silently diverging from the live ops drawer. SPEC §4 snapshot semantics stay intact:
// disclosure only — never a silent re-fetch.
import { MutationObserver, QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import { isMutationSettleEvent, lastSettleSeedFromCache, resultsMayBeStale } from './staleness'

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

describe('lastSettleSeedFromCache — mutations settled while the search surface was unmounted', () => {
  it('returns the latest successful submit time, ignoring failures and in-flight mutations', () => {
    const cache = {
      getAll: () => [
        { state: { status: 'success', submittedAt: 1_000 } },
        { state: { status: 'success', submittedAt: 3_000 } },
        { state: { status: 'error', submittedAt: 9_000 } },
        { state: { status: 'pending', submittedAt: 8_000 } },
      ],
    }
    expect(lastSettleSeedFromCache(cache)).toBe(3_000)
  })

  it('returns null on an empty or never-successful cache', () => {
    expect(lastSettleSeedFromCache({ getAll: () => [] })).toBe(null)
    expect(
      lastSettleSeedFromCache({ getAll: () => [{ state: { status: 'error', submittedAt: 5 } }] }),
    ).toBe(null)
  })

  it('reads a REAL mutation cache: a settled mutation seeds a later-mounted surface', async () => {
    const queryClient = new QueryClient()
    expect(lastSettleSeedFromCache(queryClient.getMutationCache())).toBe(null)
    const ok = new MutationObserver(queryClient, { mutationFn: () => Promise.resolve('done') })
    await ok.mutate()
    const seed = lastSettleSeedFromCache(queryClient.getMutationCache())
    expect(seed).not.toBe(null)
    expect(seed ?? 0).toBeGreaterThan(0)
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
