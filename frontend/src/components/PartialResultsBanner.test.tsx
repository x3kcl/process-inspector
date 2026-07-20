// @vitest-environment jsdom
// #273: the "N of M fetched" per-engine overflow line must never read identically for two
// different terminal futures — a lane that merely has more pages left to fetch via "Load more"
// (routine) vs. a lane that hit its OWN deep-paging depth wall and will never grow past its
// current fetched count no matter how many more times "Load more" is clicked (capped).
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PartialSummary } from '../search/partials'
import { PartialResultsBanner } from './PartialResultsBanner'

afterEach(cleanup)

function summary(overrides: Partial<PartialSummary>): PartialSummary {
  return {
    totalEngines: 1,
    okEngines: 1,
    failed: [],
    truncated: [],
    overflowing: [],
    lowerBound: true,
    ...overrides,
  }
}

describe('PartialResultsBanner — capped vs routine overflow (#273)', () => {
  it('renders nothing when the summary carries no lower bound', () => {
    const { container } = render(
      <PartialResultsBanner summary={summary({ lowerBound: false })} onRetry={vi.fn()} />,
    )
    expect(container.firstChild).toBeNull()
  })

  it('badges a walled lane with the depth-wall escalation, never the routine hint', () => {
    render(
      <PartialResultsBanner
        summary={summary({
          overflowing: [{ engineId: 'engine-a', fetched: 642, total: 1850, capped: true }],
        })}
        onRetry={vi.fn()}
      />,
    )
    const line = screen.getByText(/engine-a 642 of 1,850 fetched/)
    expect(line.className).toBe('engine-overflow-capped')
    expect(line.textContent).toContain('reached the paging depth on this engine')
    expect(line.textContent).toContain('narrow your filter')
    // The routine "Load more … fetches the rest" phrasing must NOT appear on a capped lane —
    // it would tell the user an action will work when it structurally cannot.
    expect(line.textContent).not.toContain('fetches the rest')
  })

  it('points a routine overflowing lane at Load more, never at narrowing the filter', () => {
    render(
      <PartialResultsBanner
        summary={summary({
          overflowing: [{ engineId: 'engine-b', fetched: 900, total: 1200, capped: false }],
        })}
        onRetry={vi.fn()}
      />,
    )
    const line = screen.getByText(/engine-b 900 of 1,200 fetched/)
    expect(line.className).toBe('engine-overflow-routine')
    expect(line.textContent).toContain('Load more')
    expect(line.textContent).toContain('fetches the rest')
    // The capped-only "reached the paging depth" / "narrow your filter" escalation must not leak
    // onto a lane that is simply mid-stream.
    expect(line.textContent).not.toContain('reached the paging depth')
    expect(line.textContent).not.toContain('narrow your filter')
  })

  it('renders one capped and one routine lane side by side with distinct classes', () => {
    render(
      <PartialResultsBanner
        summary={summary({
          overflowing: [
            { engineId: 'engine-a', fetched: 642, total: 1850, capped: true },
            { engineId: 'engine-b', fetched: 900, total: 1200, capped: false },
          ],
        })}
        onRetry={vi.fn()}
      />,
    )
    expect(screen.getByText(/engine-a 642 of 1,850 fetched/).className).toBe(
      'engine-overflow-capped',
    )
    expect(screen.getByText(/engine-b 900 of 1,200 fetched/).className).toBe(
      'engine-overflow-routine',
    )
  })
})
