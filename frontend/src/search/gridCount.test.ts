// #244: the grid headline count is rows.length — a capped prefix once deep paging is in
// play. The label must say "loaded so far" exactly then, and must stay a bare exact count
// (no hedge noise) when the result set is complete.
import { describe, expect, it } from 'vitest'
import {
  GRID_COUNT_PARTIAL_HINT,
  failedSplitLabel,
  gridCountIsPartial,
  gridCountLabel,
} from './gridCount'

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

describe('failedSplitLabel (#630)', () => {
  const own = { flags: { hasDeadLetterJobs: true, failedInSubprocess: false } }
  const parent = { flags: { hasDeadLetterJobs: false, failedInSubprocess: true } }
  const both = { flags: { hasDeadLetterJobs: true, failedInSubprocess: true } }
  const active = { flags: { hasDeadLetterJobs: false, failedInSubprocess: false } }

  it('splits own dead-letters from parents failed in subprocess (tile 50 vs grid 62)', () => {
    const rows = [...Array<typeof own>(50).fill(own), ...Array<typeof parent>(12).fill(parent)]
    expect(failedSplitLabel(rows)).toBe(
      'FAILED: 50 with a dead-letter job + 12 parents failed in subprocess',
    )
  })

  it('counts an instance with its own dead-letter once, on the tile side, even if a child also fails', () => {
    expect(failedSplitLabel([both, parent, active])).toBe(
      'FAILED: 1 with a dead-letter job + 1 parent failed in subprocess',
    )
  })

  it('renders nothing when no row is FAILED only in subprocess — grid and tile agree', () => {
    expect(failedSplitLabel([own, both, active, { flags: null }])).toBeNull()
    expect(failedSplitLabel([])).toBeNull()
  })
})
