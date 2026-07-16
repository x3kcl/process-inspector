// @vitest-environment jsdom
// Issue #212: the "Unknown engine ... no longer registered" banner and the vitals-error
// banner's "The engine answered ... confirmed not-found" copy were independently gated —
// both fire for an unregistered engineId (the vitals fetch 404s for the exact same reason
// the engines list doesn't contain it), producing two contradictory claims about the same
// request in one screen. The fix suppresses the second banner once the first already
// explains the root cause.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import type { EngineDto } from '../api/model'

let enginesData: EngineDto[] | undefined
vi.mock('../api/useEngines', () => ({
  useEngines: () => ({
    data: enginesData,
    isPending: enginesData === undefined,
    isSuccess: enginesData !== undefined,
  }),
}))

let vitalsError: ApiError | undefined
vi.mock('./useInstanceQueries', async () => {
  const actual =
    await vi.importActual<typeof import('./useInstanceQueries')>('./useInstanceQueries')
  return {
    ...actual,
    useInstanceVitals: () => ({
      data: undefined,
      isPending: vitalsError === undefined,
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
})

const KNOWN_ENGINE: EngineDto = { id: 'engine-a', name: 'Engine A', environment: 'dev' }

function renderPage(engineId: string) {
  // The default tab (VariablesTab) lazy-loads AFTER this synchronous render and isn't
  // awaited by these assertions, but wrap in a real provider with every query disabled
  // as a safety net: if it (or anything else in the tree) ever does mount within a test's
  // lifetime, it finds a provider instead of crashing, and `enabled: false` means none of
  // its own queries actually fire a fetch.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/inspect/${engineId}/pi-1`]}>
        <Routes>
          <Route path="/inspect/:engineId/:instanceId" element={<InspectPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
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
