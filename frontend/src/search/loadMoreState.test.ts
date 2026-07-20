// #273: paging a wide fleet search must land on one of THREE distinguishable terminal
// readings — still paging (button), the depth wall (DepthWallNote), or genuinely drained
// (isFullyDrainedAfterPaging) — never an ambiguous fourth "button stays enabled forever" state.
import { describe, expect, it } from 'vitest'
import { isFullyDrainedAfterPaging, loadMoreRegionVisible } from './loadMoreState'

describe('isFullyDrainedAfterPaging (#273)', () => {
  it('is false for an ordinary search that never overflowed (pageCount 1, no next page)', () => {
    expect(isFullyDrainedAfterPaging(1, false, undefined)).toBe(false)
    expect(isFullyDrainedAfterPaging(1, false, false)).toBe(false)
  })

  it('is false while more pages remain, however many have already loaded', () => {
    expect(isFullyDrainedAfterPaging(5, true, false)).toBe(false)
  })

  it('is true once paging happened at least once and every lane has drained', () => {
    expect(isFullyDrainedAfterPaging(4, false, false)).toBe(true)
    expect(isFullyDrainedAfterPaging(2, false, undefined)).toBe(true)
  })

  it('is false when the drain is actually the depth wall, not a genuine end', () => {
    // The load-bearing distinction (#273): depthCapped=true after paging must escalate via
    // DepthWallNote, never claim "Showing all N" — those are opposite terminal readings.
    expect(isFullyDrainedAfterPaging(6, false, true)).toBe(false)
  })
})

describe('loadMoreRegionVisible (#273)', () => {
  it('stays hidden for an ordinary, never-overflowing search', () => {
    expect(loadMoreRegionVisible(false, undefined, undefined, 1)).toBe(false)
  })

  it('renders while a next page is available', () => {
    expect(loadMoreRegionVisible(true, undefined, false, 1)).toBe(true)
  })

  it('renders for the deep-paged snapshot seam even with no next page', () => {
    expect(loadMoreRegionVisible(false, 'snapshot', false, 3)).toBe(true)
  })

  it('renders for the depth wall even with no next page', () => {
    expect(loadMoreRegionVisible(false, undefined, true, 1)).toBe(true)
  })

  it('renders the positive terminal state once paging has genuinely drained', () => {
    expect(loadMoreRegionVisible(false, undefined, false, 3)).toBe(true)
  })
})
