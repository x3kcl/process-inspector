// #244: the grid headline count is rows.length — a capped prefix once deep paging is in
// play. The label must say "loaded so far" exactly then, and must stay a bare exact count
// (no hedge noise) when the result set is complete.
import { describe, expect, it } from 'vitest'
import { GRID_COUNT_PARTIAL_HINT, gridCountIsPartial, gridCountLabel } from './gridCount'

describe('gridCountIsPartial (#244)', () => {
  it('is partial when more pages exist behind Load more', () => {
    expect(gridCountIsPartial(true, undefined)).toBe(true)
  })

  it('is partial when an engine hit the paging depth wall', () => {
    expect(gridCountIsPartial(false, true)).toBe(true)
  })

  it('is complete when there is no next page and no depth cap — including depthCapped: false', () => {
    expect(gridCountIsPartial(false, undefined)).toBe(false)
    expect(gridCountIsPartial(false, false)).toBe(false)
  })
})

describe('gridCountLabel (#244)', () => {
  it('qualifies a capped count as "loaded so far"', () => {
    expect(gridCountLabel(680, true, undefined)).toBe('680 instances loaded so far')
    expect(gridCountLabel(680, false, true)).toBe('680 instances loaded so far')
  })

  it('renders a complete count bare — the qualifier never appears as noise', () => {
    expect(gridCountLabel(680, false, undefined)).toBe('680 instances')
    expect(gridCountLabel(680, false, false)).toBe('680 instances')
  })

  it('keeps the W2 #7 unit token in both forms', () => {
    expect(gridCountLabel(1, true, undefined)).toContain('instances')
    expect(gridCountLabel(1, false, undefined)).toContain('instances')
  })
})

describe('GRID_COUNT_PARTIAL_HINT (#244)', () => {
  it('explains the cap and names the next move plus the sibling ~N count', () => {
    expect(GRID_COUNT_PARTIAL_HINT).toMatch(/capped pages per engine/)
    expect(GRID_COUNT_PARTIAL_HINT).toMatch(/Load more/)
    expect(GRID_COUNT_PARTIAL_HINT).toMatch(/~N/)
  })
})
