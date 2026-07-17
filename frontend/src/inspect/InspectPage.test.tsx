// @vitest-environment jsdom
// Issue #212: the "Unknown engine ... no longer registered" banner and the vitals-error
// banner's "The engine answered ... confirmed not-found" copy were independently gated —
// both fire for an unregistered engineId (the vitals fetch 404s for the exact same reason
// the engines list doesn't contain it), producing two contradictory claims about the same
// request in one screen. The fix suppresses the second banner once the first already
// explains the root cause.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { EngineDto, InstanceDetail } from '../api/model'
import { DATA_SKIP_SOURCE } from './skipToRetry'

let enginesData: EngineDto[] | undefined
vi.mock('../api/useEngines', () => ({
  useEngines: () => ({
    data: enginesData,
    isPending: enginesData === undefined,
    isSuccess: enginesData !== undefined,
  }),
}))

let vitalsError: ApiError | undefined
let vitalsData: InstanceDetail | undefined
vi.mock('./useInstanceQueries', async () => {
  const actual =
    await vi.importActual<typeof import('./useInstanceQueries')>('./useInstanceQueries')
  return {
    ...actual,
    useInstanceVitals: () => ({
      data: vitalsData,
      isPending: vitalsError === undefined && vitalsData === undefined,
      isError: vitalsError !== undefined,
      error: vitalsError,
    }),
    // DiagramSlot renders unconditionally alongside the vitals header; keep it a harmless
    // "no diagram" zero-state rather than pulling in bpmn-js/DiagramCanvas for this test.
    useInstanceDiagram: () => ({
      data: undefined,
      isPending: false,
      isError: true,
      error: new Error('not needed for this test'),
    }),
  }
})

import { InspectPage } from './InspectPage'

afterEach(() => {
  cleanup()
  enginesData = undefined
  vitalsError = undefined
  vitalsData = undefined
})

const KNOWN_ENGINE: EngineDto = { id: 'engine-a', name: 'Engine A', environment: 'dev' }

function renderPage(engineId: string) {
  // The default tab (VariablesTab) lazy-loads AFTER this synchronous render and isn't
  // awaited by these assertions, but wrap in a real provider with every query disabled
  // as a safety net: if it (or anything else in the tree) ever does mount within a test's
  // lifetime, it finds a provider instead of crashing, and `enabled: false` means none of
  // its own queries actually fire a fetch.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  // The <main> wrapper mirrors the Shell's landmark — restoreRouteFocus (used by the
  // skip-to-retry unmount guard below) targets `main h1, main h2`. makeTree builds a FRESH
  // element each time: rerendering a referentially-identical element bails out of the
  // re-render entirely, so the module-level mock data would never be re-read.
  const makeTree = () => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/inspect/${engineId}/pi-1`]}>
        <main>
          <Routes>
            <Route path="/inspect/:engineId/:instanceId" element={<InspectPage />} />
          </Routes>
        </main>
      </MemoryRouter>
    </QueryClientProvider>
  )
  return { ...render(makeTree()), makeTree }
}

describe('InspectPage — unknown-engine banner never contradicts the vitals-error banner (#212)', () => {
  it('shows ONLY the "Unknown engine" banner when the engineId is not in the registry', () => {
    enginesData = [KNOWN_ENGINE]
    vitalsError = new ApiError(404, { detail: 'Unknown engine: engine-zzz' })
    renderPage('engine-zzz')

    expect(screen.getByText(/Unknown engine/)).toBeTruthy()
    // The contradictory "The engine answered ... confirmed not-found" framing must NOT
    // also render — it wrongly implies engine-zzz was reached and answered.
    expect(screen.queryByText(/The engine answered/)).toBeNull()
    expect(screen.queryByText(/confirmed not-found/)).toBeNull()
  })

  it('still shows the definitive "confirmed not-found" copy for a REAL engine with no such instance', () => {
    enginesData = [KNOWN_ENGINE]
    vitalsError = new ApiError(404, { detail: 'no such instance' })
    renderPage('engine-a')

    expect(screen.queryByText(/Unknown engine/)).toBeNull()
    expect(screen.getByText(/The engine answered/)).toBeTruthy()
    expect(screen.getByText(/confirmed not-found/i)).toBeTruthy()
  })

  it('still shows the vitals-error banner while the engines list itself is still loading', () => {
    enginesData = undefined
    vitalsError = new ApiError(404, { detail: 'no such instance' })
    renderPage('engine-a')

    // engines.isSuccess is false while pending, so the unknown-engine gate never suppresses
    // the vitals banner just because the registry hasn't answered yet.
    expect(screen.queryByText(/Unknown engine/)).toBeNull()
    expect(screen.getByText(/The engine answered/)).toBeTruthy()
  })
})

const STUCK_VITALS: InstanceDetail = {
  engineId: 'engine-a',
  processInstanceId: 'pi-1',
  status: 'FAILED',
  startTime: '2026-07-01T10:00:00Z',
  whyStuck: { deadLetterJobs: 2, exceptionFirstLine: 'boom', failingActivityId: 'callBilling' },
}

describe('skip-to-retry control (#237 — keyboard-only FIND→FIX efficiency)', () => {
  it("renders as the page's FIRST focusable, ahead of the vitals-header gauntlet, when dead-letter jobs exist", () => {
    enginesData = [KNOWN_ENGINE]
    vitalsData = STUCK_VITALS
    renderPage('engine-a')

    const skip = screen.getByRole('button', {
      name: /skip to failed job — retry \(2 dead-letter\)/i,
    })
    // The never-steal-focus guard in skipToRetry recognises the command source by this marker.
    expect(skip.getAttribute(DATA_SKIP_SOURCE)).toBe('true')
    // It must precede every vitals-header control in DOM order — that's what makes it Tab
    // stop #1 on the page (same doctrine as the shell's #168 skip-link).
    const copyId = screen.getByRole('button', { name: 'copy ID' })
    expect(skip.compareDocumentPosition(copyId) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('activating it opens the Errors & Jobs tab', () => {
    enginesData = [KNOWN_ENGINE]
    vitalsData = STUCK_VITALS
    renderPage('engine-a')

    fireEvent.click(screen.getByRole('button', { name: /skip to failed job/i }))
    const panel = document.querySelector('[role="tabpanel"]')
    expect(panel?.getAttribute('aria-label')).toBe('Errors & Jobs')
    expect(screen.getByRole('tab', { name: 'Errors & Jobs' }).getAttribute('aria-selected')).toBe(
      'true',
    )
  })

  it('is absent when the instance has no dead-letter evidence — never a dead skip target', () => {
    enginesData = [KNOWN_ENGINE]
    vitalsData = { engineId: 'engine-a', processInstanceId: 'pi-1', status: 'ACTIVE' }
    renderPage('engine-a')

    expect(screen.getByRole('button', { name: 'copy ID' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /skip to failed job/i })).toBeNull()
  })

  it('hands focus to the route heading — never <body> — if a data refresh unmounts it while focused', () => {
    enginesData = [KNOWN_ENGINE]
    vitalsData = STUCK_VITALS
    const view = renderPage('engine-a')

    const skip = screen.getByRole('button', { name: /skip to failed job/i })
    skip.focus()
    expect(document.activeElement).toBe(skip)

    // A background vitals refetch clears the dead-letter evidence → the control unmounts.
    // The browser's default would drop focus to <body> silently; the unmount guard hands
    // it to the route heading instead (same survivor doctrine as restoreRouteFocus).
    vitalsData = { ...STUCK_VITALS, whyStuck: undefined }
    view.rerender(view.makeTree())

    expect(screen.queryByRole('button', { name: /skip to failed job/i })).toBeNull()
    expect(document.activeElement?.textContent).toBe('Instance detail')
  })
})
