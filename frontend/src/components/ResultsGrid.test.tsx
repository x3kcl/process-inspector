// @vitest-environment jsdom
// R-UXQ-02 (usability W1#5): row-open must never be mouse-only. Enter on a focused
// grid cell opens the instance detail through the SAME handler as double-click, and
// the affordance is visibly hinted next to the selection hint.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { EngineDto, ProcessInstanceRow, SearchResponse } from '../api/model'
import { ResultsGrid } from './ResultsGrid'

beforeAll(() => {
  // jsdom ships no ResizeObserver; AG Grid needs one to size the viewport.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
})

afterEach(cleanup)

const row: ProcessInstanceRow = {
  compositeId: 'engine-a:pi-1',
  engineId: 'engine-a',
  engineName: 'Engine A',
  processInstanceId: 'pi-1',
  businessKey: 'ORDER-77',
  status: 'FAILED',
  startTime: '2026-07-09T10:00:00Z',
}

const response: SearchResponse = {
  rows: [row],
  perEngine: { 'engine-a': { ok: true, total: 1 } },
}

const enginesById = new Map<string, EngineDto>([
  ['engine-a', { id: 'engine-a', name: 'Engine A', environment: 'TEST' }],
])

async function renderGrid(onOpenDetails: (opened: ProcessInstanceRow) => void) {
  render(
    <ResultsGrid response={response} enginesById={enginesById} onOpenDetails={onOpenDetails} />,
  )
  // AG Grid renders rows asynchronously — wait for the business-key cell.
  await waitFor(() => screen.getByText('ORDER-77'))
}

describe('ResultsGrid keyboard row-open (R-UXQ-02)', () => {
  it('Enter on a focused cell opens the instance detail — and only Enter does', async () => {
    const onOpenDetails = vi.fn()
    await renderGrid(onOpenDetails)
    const cell = screen.getByText('ORDER-77').closest('.ag-cell')
    expect(cell).not.toBeNull()
    // Non-Enter keys first: if any of them (wrongly) opened the row, the call count
    // below would exceed 1. ag-grid-react delivers grid callbacks asynchronously,
    // hence the waitFor.
    fireEvent.keyDown(cell as Element, { key: ' ' })
    fireEvent.keyDown(cell as Element, { key: 'ArrowDown' })
    fireEvent.keyDown(cell as Element, { key: 'Enter' })
    await waitFor(() => {
      expect(onOpenDetails).toHaveBeenCalled()
    })
    expect(onOpenDetails).toHaveBeenCalledTimes(1)
    expect(onOpenDetails.mock.calls[0][0]).toMatchObject({ processInstanceId: 'pi-1' })
  })

  it('shows a visible keyboard hint alongside the selection hint', async () => {
    await renderGrid(vi.fn())
    const hint = screen.getByText(/opens the focused row/i)
    expect(hint.textContent).toMatch(/Enter/)
    expect(hint.textContent).toMatch(/Space/)
  })
})

describe('ResultsGrid root-vs-child marker (W2 #7, R-UXQ-12)', () => {
  it('a call-activity child row wears the ↳ child marker; a root row does not', async () => {
    const child: ProcessInstanceRow = {
      ...row,
      compositeId: 'engine-a:pi-2',
      processInstanceId: 'pi-2',
      businessKey: 'ORDER-77', // same tree business key as the root — the marker disambiguates
      superProcessInstanceId: 'pi-1',
    }
    render(
      <ResultsGrid
        response={{ rows: [row, child], perEngine: { 'engine-a': { ok: true, total: 2 } } }}
        enginesById={enginesById}
        onOpenDetails={vi.fn()}
      />,
    )
    await waitFor(() => screen.getAllByText('ORDER-77'))
    const markers = screen.getAllByText(/↳ child/)
    expect(markers).toHaveLength(1)
    // The marker explains itself: parent id in the title, for the identically-keyed tree case.
    expect(markers[0].getAttribute('title')).toContain('pi-1')
  })
})
