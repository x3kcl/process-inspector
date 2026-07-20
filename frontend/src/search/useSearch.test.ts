// #273: the deep-paging merge must carry an engine's own `capped` (depth-wall) flag through the
// accumulated chain — sticky once true, and read from the freshest page (never frozen at page 1
// like the other perEngine fields), so a wall discovered on a LATER "Load more" click still
// surfaces even though `total`/`dlqScan`/etc. stay whatever page 1 said.
import { describe, expect, it } from 'vitest'
import type { SearchResponse } from '../api/model'
import { mergeCappedFlag, mergeDeepPages } from './useSearch'

function page(overrides: Partial<SearchResponse>): SearchResponse {
  return { rows: [], perEngine: {}, ...overrides }
}

describe('mergeCappedFlag (#273)', () => {
  it('is false when neither page 1 nor the latest page saw a wall', () => {
    expect(mergeCappedFlag(false, { ok: true, capped: false })).toBe(false)
    expect(mergeCappedFlag(undefined, undefined)).toBe(false)
  })

  it('is true once page 1 already saw the wall, even if the latest page cannot confirm it', () => {
    expect(mergeCappedFlag(true, undefined)).toBe(true)
    expect(mergeCappedFlag(true, { ok: false })).toBe(true)
  })

  it('is true once the latest OK page reports the wall, even though page 1 did not yet see it', () => {
    expect(mergeCappedFlag(false, { ok: true, capped: true })).toBe(true)
  })

  it('never lets a transient per-engine failure on the latest page clear an unknown wall', () => {
    // ok !== true on the latest page means that page's OWN `capped` bit is meaningless (it never
    // ran) — ignore it rather than reading `capped: true` off a failure envelope by accident.
    expect(mergeCappedFlag(false, { ok: false, capped: true })).toBe(false)
  })
})

describe('mergeDeepPages (#273 capped propagation)', () => {
  it('surfaces a wall discovered only on a later page, not frozen at page 1', () => {
    const p1 = page({
      rows: [{ compositeId: 'engine-a:1', engineId: 'engine-a', startTime: '2026-07-19T10:00:00Z' }],
      perEngine: { 'engine-a': { ok: true, fetched: 1, total: 5000, capped: false } },
      nextCursor: 'c1',
    })
    const p2 = page({
      rows: [{ compositeId: 'engine-a:2', engineId: 'engine-a', startTime: '2026-07-19T09:00:00Z' }],
      perEngine: { 'engine-a': { ok: true, fetched: 1, total: 5000, capped: true } },
      nextCursor: undefined,
      depthCapped: true,
    })
    const merged = mergeDeepPages([p1, p2])
    expect(merged?.perEngine?.['engine-a'].capped).toBe(true)
    // #167's existing rule still holds: fetched is recomputed from the accumulated de-duped rows.
    expect(merged?.perEngine?.['engine-a'].fetched).toBe(2)
  })

  it('keeps capped false for an engine that never hit its wall across the whole chain', () => {
    const p1 = page({
      rows: [{ compositeId: 'engine-b:1', engineId: 'engine-b', startTime: '2026-07-19T10:00:00Z' }],
      perEngine: { 'engine-b': { ok: true, fetched: 1, total: 3, capped: false } },
      nextCursor: 'c1',
    })
    const p2 = page({
      rows: [{ compositeId: 'engine-b:2', engineId: 'engine-b', startTime: '2026-07-19T09:00:00Z' }],
      perEngine: { 'engine-b': { ok: true, fetched: 1, total: 3, capped: false } },
      nextCursor: undefined,
    })
    const merged = mergeDeepPages([p1, p2])
    expect(merged?.perEngine?.['engine-b'].capped).toBe(false)
  })

  it('leaves a failed engine envelope untouched (no capped decoration on a failure)', () => {
    const p1 = page({ perEngine: { 'engine-c': { ok: false, error: 'timeout' } } })
    const merged = mergeDeepPages([p1])
    expect(merged?.perEngine?.['engine-c']).toEqual({ ok: false, error: 'timeout' })
  })

  it('returns undefined for an empty page list', () => {
    expect(mergeDeepPages([])).toBeUndefined()
  })
})
