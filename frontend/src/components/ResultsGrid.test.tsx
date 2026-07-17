// @vitest-environment jsdom
// R-UXQ-02 (usability W1#5): row-open must never be mouse-only. Enter on a focused
// grid cell opens the instance detail through the SAME handler as double-click, and
// the affordance is visibly hinted next to the selection hint.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
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
    <MemoryRouter>
      <ResultsGrid response={response} enginesById={enginesById} onOpenDetails={onOpenDetails} />
    </MemoryRouter>,
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

  it('tells a keyboard-only user how to get focus onto a row in the first place (#211)', async () => {
    await renderGrid(vi.fn())
    const hint = screen.getByText(/opens the focused row/i)
    expect(hint.textContent).toMatch(/moves into rows/i)
  })

  it('renders a visible Open link to the instance detail (U1) so row-open is not mouse-only', async () => {
    await renderGrid(vi.fn())
    const link = screen.getByRole('link', { name: /open/i })
    // Same target onOpenDetails navigates to (double-click / Enter), now discoverable + focusable.
    expect(link.getAttribute('href')).toBe('/inspect/engine-a/pi-1')
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
      <MemoryRouter>
        <ResultsGrid
          response={{ rows: [row, child], perEngine: { 'engine-a': { ok: true, total: 2 } } }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    await waitFor(() => screen.getAllByText('ORDER-77'))
    const markers = screen.getAllByText(/↳ child/)
    expect(markers).toHaveLength(1)
    // The marker explains itself: parent id in the title, for the identically-keyed tree case.
    expect(markers[0].getAttribute('title')).toContain('pi-1')
  })
})

describe('ResultsGrid protected-instance badge (R-SAFE-05, issue #97 remainder)', () => {
  it('a protected row wears the 🔒 badge; an unprotected row does not', async () => {
    const protectedRow: ProcessInstanceRow = {
      ...row,
      compositeId: 'engine-a:pi-2',
      processInstanceId: 'pi-2',
      businessKey: 'ORDER-88',
      protectedInstance: true,
    }
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{
            rows: [row, protectedRow],
            perEngine: { 'engine-a': { ok: true, total: 2 } },
          }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    await waitFor(() => screen.getAllByText('ORDER-88'))
    const badges = screen.getAllByText('🔒 Protected')
    expect(badges).toHaveLength(1)
  })

  it('protectedInstance undefined/false renders no badge at all', async () => {
    await renderGrid(vi.fn())
    expect(screen.queryByText('🔒 Protected')).toBeNull()
  })
})

describe('ResultsGrid status honesty (#166 — grid must not read COMPLETED for a terminated instance)', () => {
  it('a row carrying terminationReason renders the TERMINATED chip, not COMPLETED', async () => {
    const terminatedRow: ProcessInstanceRow = {
      ...row,
      compositeId: 'engine-a:pi-2',
      processInstanceId: 'pi-2',
      businessKey: 'ORDER-99',
      status: 'COMPLETED',
      terminationReason: 'customer requested cancellation',
    }
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{
            rows: [row, terminatedRow],
            perEngine: { 'engine-a': { ok: true, total: 2 } },
          }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    await waitFor(() => screen.getAllByText('ORDER-99'))
    expect(screen.getByText('TERMINATED')).toBeTruthy()
  })

  it('a row without terminationReason still renders its plain status (no regression)', async () => {
    await renderGrid(vi.fn())
    expect(screen.getByText('FAILED')).toBeTruthy()
    expect(screen.queryByText('TERMINATED')).toBeNull()
  })
})

describe('ResultsGrid zero states (SPEC §10a — U4/#89)', () => {
  it('no response yet: a neutral prompt, not an empty grid', () => {
    render(
      <MemoryRouter>
        <ResultsGrid response={undefined} enginesById={enginesById} onOpenDetails={vi.fn()} />
      </MemoryRouter>,
    )
    expect(screen.getByText(/run a search to see process instances/i)).not.toBeNull()
  })

  it('every engine failed: a loud alert, never a calm "no matches"', () => {
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{ rows: [], perEngine: { 'engine-a': { ok: false, error: 'timeout' } } }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toMatch(/all 1 engines? failed/i)
    expect(alert.textContent).toContain('engine-a: timeout')
    expect(alert.className).toContain('zero-error')
  })

  it('zero under partial coverage: a warned "not a confirmed zero", not a bare zero', () => {
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{
            rows: [],
            perEngine: {
              'engine-a': { ok: true, total: 0, fetched: 0 },
              'engine-b': { ok: false, error: 'unreachable' },
            },
          }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    const alert = screen.getByRole('alert')
    expect(alert.textContent).toMatch(/not a confirmed zero/i)
    expect(alert.textContent).toContain('engine-b unreachable')
    expect(alert.className).toContain('zero-warn')
  })

  it('confirmed zero: every engine answered ok with nothing — no role="alert"', () => {
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{ rows: [], perEngine: { 'engine-a': { ok: true, total: 0, fetched: 0 } } }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText(/confirmed zero across 1 engine/i)).not.toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  // #236: "confirmed zero across N engines" must name — in the same sentence — the
  // registered engines the search never covered, or the zero reads exhaustive when it isn't.
  it('confirmed zero names the registered engines the search never covered (#236)', () => {
    const registry = new Map<string, EngineDto>([
      ['engine-a', { id: 'engine-a', name: 'Engine A', lifecycle: 'active', reachable: true }],
      ['engine-b', { id: 'engine-b', name: 'Engine B', lifecycle: 'disabled' }],
      [
        'engine-c',
        { id: 'engine-c', name: 'Engine C', lifecycle: 'probe_failed', reachable: false },
      ],
    ])
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{ rows: [], perEngine: { 'engine-a': { ok: true, total: 0, fetched: 0 } } }}
          enginesById={registry}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    const zero = screen.getByText(/confirmed zero across 1 engine/i)
    expect(zero.textContent).toContain(
      '(2 more registered engines not searched: engine-b [disabled], engine-c [probe failed] — see Engines)',
    )
  })

  it('a genuinely exhaustive confirmed zero carries no spurious "more excluded" clause (#236)', () => {
    render(
      <MemoryRouter>
        <ResultsGrid
          response={{ rows: [], perEngine: { 'engine-a': { ok: true, total: 0, fetched: 0 } } }}
          enginesById={enginesById}
          onOpenDetails={vi.fn()}
        />
      </MemoryRouter>,
    )
    expect(screen.getByText(/confirmed zero across 1 engine/i).textContent).not.toContain(
      'more registered',
    )
  })
})
