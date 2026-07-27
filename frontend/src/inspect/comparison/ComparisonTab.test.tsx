// @vitest-environment jsdom
// The sibling picker's candidate dropdown (SPEC §5.2): the nearest-sibling scan already
// returns every recent completed sibling, so the picker offers them as a selection instead
// of demanding a pasted id. Mirrors the mock-hook pattern (ResolveIncidentModal.test.tsx):
// mock the query-hook module, render through a real router so ?sibling= behavior is proven
// end-to-end, and pin that the manual paste input NEVER disappears (it is the escape hatch
// for older runs, cross-definition compares, and a failed auto-suggest).
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { NearestSiblingResponse } from '../../api/model'

type QueryShape<T> = {
  data: T | undefined
  isPending: boolean
  isError: boolean
  error: Error | undefined
}

function idle<T>(data: T | undefined): QueryShape<T> {
  return { data, isPending: false, isError: false, error: undefined }
}

let nearestState: QueryShape<NearestSiblingResponse> = idle<NearestSiblingResponse>(undefined)
vi.mock('../useInstanceQueries', () => ({
  useNearestSibling: () => nearestState,
  // The diff itself is not under test — keep it forever pending so the pane stays simple.
  useSiblingDiff: () => ({ data: undefined, isPending: true, isError: false, error: undefined }),
}))

import ComparisonTab from './ComparisonTab'

afterEach(() => {
  cleanup()
  nearestState = idle<NearestSiblingResponse>(undefined)
})

const TWO_CANDIDATES: NearestSiblingResponse = {
  found: true,
  sibling: { processInstanceId: 'good-42', businessKey: 'ORD-42', endTime: '2026-07-07T08:00:00Z' },
  candidates: [
    {
      processInstanceId: 'good-42',
      businessKey: 'ORD-42',
      endTime: '2026-07-07T08:00:00Z',
      durationMs: 12_000,
    },
    { processInstanceId: 'good-41', businessKey: 'ORD-41', endTime: '2026-07-07T07:00:00Z' },
  ],
  candidatesScanned: 3,
}

function renderTab() {
  render(
    <MemoryRouter initialEntries={['/inspect']}>
      <ComparisonTab engineId="engine-a" instanceId="subject-1" />
    </MemoryRouter>,
  )
}

function siblingSelect() {
  return screen.getByRole<HTMLSelectElement>('combobox', { name: /recent completed siblings/ })
}

function pasteInput() {
  return screen.getByRole('textbox', { name: /sibling process instance id/ })
}

describe('ComparisonTab sibling candidate dropdown', () => {
  it('offers every scan candidate as an option, labelled by business key, with the suggestion selected', () => {
    nearestState = idle(TWO_CANDIDATES)
    renderTab()

    const select = siblingSelect()
    // placeholder + the two candidates
    expect(select.options).toHaveLength(3)
    expect(select.options[1].value).toBe('good-42')
    expect(select.options[1].textContent).toContain('key ORD-42')
    expect(select.options[1].textContent).toContain('took')
    expect(select.options[2].value).toBe('good-41')
    // the auto-suggestion (head of the list) reads as the current selection
    expect(select.value).toBe('good-42')
    expect(screen.getByTitle(/most recent completed run/)).toBeTruthy()
  })

  it('selecting a candidate pins it via ?sibling= — the chip drops because it is now explicit', () => {
    nearestState = idle(TWO_CANDIDATES)
    renderTab()

    fireEvent.change(siblingSelect(), { target: { value: 'good-41' } })

    expect(siblingSelect().value).toBe('good-41')
    expect(screen.getByText('good-41', { selector: 'code' })).toBeTruthy()
    expect(screen.queryByTitle(/most recent completed run/)).toBeNull()
  })

  it('keeps the manual paste input alongside the dropdown — the escape hatch never disappears', () => {
    nearestState = idle(TWO_CANDIDATES)
    renderTab()
    expect(pasteInput()).toBeTruthy()
    expect(screen.getByText('Or paste any sibling id')).toBeTruthy()
  })

  it('renders no dropdown (paste-only, original label) when there are no candidates', () => {
    nearestState = idle({ found: false, candidates: [], candidatesScanned: 0 })
    renderTab()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(pasteInput()).toBeTruthy()
    expect(screen.getByText('Compare with a different sibling')).toBeTruthy()
  })
})
