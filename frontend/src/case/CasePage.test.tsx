// @vitest-environment jsdom
// Issue #252 (mirrors #248/#251 on InspectPage): a DISABLED-but-registered engine must be
// explained as disabled, never as "unknown" — the CMMN case detail page's backend read surface
// now resolves via EngineRegistry#resolveOrNotFound, and this proves the frontend banner that
// promise depends on actually renders.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EngineDto } from '../api/model'

let enginesData: EngineDto[] | undefined
vi.mock('../api/useEngines', () => ({
  useEngines: () => ({
    data: enginesData,
    isPending: enginesData === undefined,
    isSuccess: enginesData !== undefined,
  }),
}))

// Vitals/diagram/plan-items stay pending for every test here — the banner's presence is
// independent of them, and keeping them unresolved avoids exercising the diagram fetch.
vi.mock('./useCaseQueries', () => ({
  useCaseVitals: () => ({ data: undefined, isPending: true, isError: false }),
  useCaseDiagram: () => ({ data: undefined, isPending: true, isError: false }),
  useCasePlanItems: () => ({ data: undefined, isPending: true, isError: false }),
}))

// CaseDiagramCanvas eagerly imports cmmn-js, whose bundled diagram-js has a broken ESM
// resolution under Vitest (an extensionless `lib/Diagram` require its CJS package.json
// doesn't declare an "exports" map for) — unrelated to this test's concern, and diagram.isPending
// stays true above so the real component never actually renders anyway.
vi.mock('./CaseDiagramCanvas', () => ({ CaseDiagramCanvas: () => null }))

import { CasePage } from './CasePage'

afterEach(() => {
  cleanup()
  enginesData = undefined
})

const KNOWN_ENGINE: EngineDto = { id: 'engine-a', name: 'Engine A', environment: 'dev' }
const DISABLED_ENGINE: EngineDto = {
  id: 'engine-c',
  name: 'Engine C',
  environment: 'test',
  lifecycle: 'disabled',
}

function renderPage(engineId: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/case/${engineId}/case-1`]}>
        <Routes>
          <Route path="/case/:engineId/:caseInstanceId" element={<CasePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CasePage — a DISABLED engine is explained as disabled, never "unknown" (#252)', () => {
  it('shows the disabled-not-removed banner for a registered-but-disabled engine', () => {
    enginesData = [KNOWN_ENGINE, DISABLED_ENGINE]
    renderPage('engine-c')

    const banner = screen.getByRole('status')
    expect(banner.textContent).toContain('disabled, not')
    expect(banner.textContent).toContain('Engine “Engine C” is disabled in the registry')
    expect(screen.queryByText(/Unknown engine/)).toBeNull()
  })

  it('shows no disabled banner for an active engine', () => {
    enginesData = [KNOWN_ENGINE]
    renderPage('engine-a')

    expect(screen.queryByRole('status')).toBeNull()
  })

  it('shows no disabled banner while the engines list is still loading', () => {
    enginesData = undefined
    renderPage('engine-c')

    expect(screen.queryByRole('status')).toBeNull()
  })
})
