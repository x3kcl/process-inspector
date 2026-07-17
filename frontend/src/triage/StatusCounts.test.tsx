// @vitest-environment jsdom
// #244: the status tiles are a CACHED Stage 0 aggregation while each tile drills into a
// LIVE Stage 1 search — the section carries a visible "as of" stamp (same doctrine as the
// error-group totals, #209) so the two counts never read as the same measurement.
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { StatusCounts } from './StatusCounts'

afterEach(cleanup)

function renderCounts(asOf?: string) {
  render(
    <MemoryRouter>
      <StatusCounts counts={{ FAILED: 46, RUNNING: 660 }} lowerBound={false} asOf={asOf} />
    </MemoryRouter>,
  )
}

describe('StatusCounts aggregation stamp (#244)', () => {
  it('shows a visible "as of" stamp explaining the tiles are a cached snapshot', () => {
    renderCounts('2026-07-17T11:00:00Z')
    const stamp = screen.getByText(/as of/).closest('.status-summary-asof')
    expect(stamp).not.toBeNull()
    // The LONG explanation names both halves: the cache, and the live drill-through.
    expect(stamp?.getAttribute('title')).toMatch(/caches ~20s/)
    expect(stamp?.getAttribute('title')).toMatch(/live search/)
  })

  it('renders no stamp when the aggregation stamp is unknown or empty', () => {
    renderCounts(undefined)
    expect(screen.queryByText(/as of/)).toBeNull()
    cleanup()
    renderCounts('')
    expect(screen.queryByText(/as of/)).toBeNull()
  })

  it('keeps the W2 #7 unit token on every tile alongside the stamp', () => {
    renderCounts('2026-07-17T11:00:00Z')
    expect(screen.getAllByText(/instances/).length).toBeGreaterThan(0)
  })
})
