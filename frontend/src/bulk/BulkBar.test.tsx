// @vitest-environment jsdom
// #244: the filter-scope "~N" is each engine's FULL reported match total while the grid
// only fetches capped pages — the scope-count hint must reconcile the two, not just
// explain its own staleness.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SearchRequest } from '../api/model'
import { BulkBar } from './BulkBar'

afterEach(cleanup)

const criteria: SearchRequest = { statuses: ['FAILED'] }

function renderSlimBar(matchTotal: number | undefined = 896) {
  render(
    <BulkBar
      selected={[]}
      failedEngines={[]}
      truncated={false}
      onSubmitted={vi.fn()}
      criteria={criteria}
      matchTotal={matchTotal}
      visibleCount={200}
      engines={[]}
      me={undefined}
    />,
  )
}

describe('BulkBar scope-count hint (#244)', () => {
  it('labels ~N as the engine-reported match total and reconciles it against the grid count', () => {
    renderSlimBar()
    const hint = screen.getByRole('note')
    // [what] ~N actually is…
    expect(hint.textContent).toMatch(/engine-reported match total/)
    // …[why] it can disagree with the rows on screen…
    expect(hint.textContent).toMatch(/differ from the grid count/)
    expect(hint.textContent).toMatch(/grid pages are capped/)
    // …and the pre-existing run-time re-resolution disclosure stays.
    expect(hint.textContent).toMatch(/re-checked at run time/)
  })

  it('still shows the ~N affordance itself (Theme H1: never "all all")', () => {
    renderSlimBar()
    expect(screen.getByText(/Select all ~896 matching filter/)).not.toBeNull()
  })

  it('renders nothing at all when the filter scope is not offered — the hint is never free-floating noise', () => {
    renderSlimBar(0)
    expect(screen.queryByRole('note')).toBeNull()
    expect(screen.queryByRole('toolbar')).toBeNull()
  })
})
