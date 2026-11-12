// @vitest-environment jsdom
// Case migration on-ramp (#500). The by-definition doors need Flowable 7.x: on an older engine
// they are greyed WITH the reason (never hidden), while the cohort door stays available.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OpsDrawerProvider } from '../ops/drawerState'

let role = 'ADMIN'
vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'someone' } }),
  roleOn: () => role,
}))

const post = vi.fn()
const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      POST: (...args: unknown[]) => post(...args) as unknown,
      GET: (...args: unknown[]) => get(...args) as unknown,
    },
  }
})

import { CaseVersionsPage } from './CaseVersionsPage'

afterEach(() => {
  cleanup()
  post.mockReset()
  get.mockReset()
  role = 'ADMIN'
})

const ok = (data: unknown) => Promise.resolve({ data, error: undefined, response: { status: 200 } })

const VERSIONS = [
  { caseDefinitionId: 'def-2', version: 2, name: 'Demo', latest: true, runningCaseCount: 0 },
  {
    caseDefinitionId: 'def-1',
    version: 1,
    name: 'Demo',
    latest: false,
    runningCaseCount: 3,
    endedCaseCount: 2,
  },
]
const GATE = "needs Flowable 7.x (engine 'engine-a' is 6.8.0)"

function renderPage(gate: string | undefined, environment = 'dev') {
  get.mockImplementation((path: string) => {
    if (path === '/api/engines') return ok([{ id: 'engine-a', name: 'A', environment }])
    if (path === '/api/case-definitions/{engineId}/{key}/versions')
      return ok({
        engineId: 'engine-a',
        key: 'demoCase',
        latestVersion: 2,
        totalVersions: 2,
        complete: true,
        versions: VERSIONS,
        definitionRoutesGate: gate,
      })
    if (path === '/api/case-definitions/{engineId}/{caseDefinitionId}/migration-batches')
      return ok([
        {
          batchId: 'b-1',
          status: 'completed',
          createTime: '2026-11-12T11:00:00Z',
          completeTime: '2026-11-12T11:00:12Z',
          toCaseDefinitionId: 'def-2',
          parts: 3,
          partsByStatus: { fail: 1, success: 2 },
          notSucceededCaseIds: ['case-9'],
        },
      ])
    return ok({
      id: 'an-1',
      status: 'COMPLETED',
      executableIds: ['c1', 'c2'],
      blockerCount: 1,
      byPlanItem: [{ planItemDefinitionId: 'optionalNote', cases: 1 }],
      lowerBound: false,
      banner: 'Inspector estimate — the engine is the only ground truth at execute',
    })
  })
  post.mockImplementation((path: string) => {
    if (path === '/api/case-migration/cohorts/preview')
      return ok({ count: 3, truncated: false, cap: 500 })
    if (path === '/api/case-migration/analyses') return ok({ id: 'an-1', status: 'RUNNING' })
    if (path === '/api/bulk/migrate-cases') return ok({ id: 'job-1', totalItems: 2 })
    return ok({ outcome: 'ok', deltaStatement: 'The engine accepted a batch migration.' })
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <OpsDrawerProvider>
        <MemoryRouter initialEntries={['/case-definitions/engine-a/demoCase/versions']}>
          <Routes>
            <Route
              path="/case-definitions/:engineId/:key/versions"
              element={<CaseVersionsPage />}
            />
          </Routes>
        </MemoryRouter>
      </OpsDrawerProvider>
    </QueryClientProvider>,
  )
}

const button = (name: RegExp | string, index = 0) =>
  screen.getAllByRole('button', { name })[index] as HTMLButtonElement

describe('CaseVersionsPage (#500)', () => {
  it('greys the 7.x-only doors with the reason on a 6.8 engine and keeps the cohort door', async () => {
    renderPage(GATE)
    await screen.findAllByRole('button', { name: /Migrate cases/ })

    // Row order is newest first: index 1 is v1, the version that has cases.
    const batch = button(/Batch-migrate all running/, 1)
    const historic = button(/Migrate ended cases/, 1)
    expect(batch.disabled).toBe(true)
    expect(historic.disabled).toBe(true)
    expect(batch.getAttribute('aria-describedby')).toBe('case-definition-routes-gate')
    expect(batch.title).toBe(GATE)
    expect(document.getElementById('case-definition-routes-gate')?.textContent).toContain(GATE)
    expect(screen.queryByRole('button', { name: 'Batches' })).toBeNull()

    expect(button(/Migrate cases/, 1).disabled).toBe(false)
    // v2 has no running case: nothing to migrate from it.
    expect(button(/Migrate cases/, 0).disabled).toBe(true)
    expect(post).not.toHaveBeenCalled()
  })

  it('runs the cohort as one bulk job over the analysis id, with contains greyed on 6.8', async () => {
    renderPage(GATE)
    await screen.findAllByRole('button', { name: /Migrate cases/ })
    fireEvent.click(button(/Migrate cases/, 1))

    const contains = screen.getByRole<HTMLInputElement>('checkbox')
    expect(contains.disabled).toBe(true)
    expect(document.getElementById('case-contains-gate')?.textContent).toContain(GATE)

    fireEvent.click(screen.getByRole('button', { name: 'Preview count' }))
    await screen.findByText(/3 running cases/)
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
    await screen.findByText(/2 executable · 1 with a blocker/)
    expect(screen.getByText(/is not in the target model/).textContent).toContain('optionalNote')
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'move the cases off the bad deploy' },
    })
    fireEvent.click(screen.getByRole('button', { name: /Migrate 2/ }))

    await waitFor(() => {
      expect(post.mock.calls.some(([path]) => path === '/api/bulk/migrate-cases')).toBe(true)
    })
    expect(post.mock.calls.find(([path]) => path === '/api/bulk/migrate-cases')?.[1]).toEqual({
      body: {
        analysisIds: ['an-1'],
        reason: 'move the cases off the bad deploy',
        ticketId: undefined,
        confirmedCount: undefined,
      },
    })
    expect(post.mock.calls.find(([path]) => path === '/api/case-migration/analyses')?.[1]).toEqual({
      params: { query: { engineId: 'engine-a' } },
      body: {
        fromCaseDefinitionId: 'def-1',
        toCaseDefinitionId: 'def-2',
        businessKey: undefined,
        businessKeyContains: false,
      },
    })
  })

  it('on a 7.x prod engine the batch door needs the typed running count', async () => {
    renderPage(undefined, 'prod')
    await screen.findAllByRole('button', { name: /Migrate cases/ })
    expect(document.getElementById('case-definition-routes-gate')).toBeNull()

    fireEvent.click(button(/Batch-migrate all running/, 1))
    const start = screen.getByRole<HTMLButtonElement>('button', { name: /Start batch \(3\)/ })
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'move the cases off the bad deploy' },
    })
    // The engine list (prod) arrives after the modal opens: the typed count appears with it.
    const typed = await screen.findByLabelText(/Type the number of running cases/)
    expect(start.disabled).toBe(true)
    fireEvent.change(typed, {
      target: { value: '3' },
    })
    expect(start.disabled).toBe(false)
    fireEvent.click(start)

    const path = '/api/case-definitions/{engineId}/{caseDefinitionId}/migrate-batch'
    await waitFor(() => {
      expect(post.mock.calls.some(([called]) => called === path)).toBe(true)
    })
    expect(post.mock.calls.find(([called]) => called === path)?.[1]).toEqual({
      params: { path: { engineId: 'engine-a', caseDefinitionId: 'def-1' } },
      body: {
        toCaseDefinitionId: 'def-2',
        reason: 'move the cases off the bad deploy',
        ticketId: undefined,
        confirmedCount: 3,
      },
    })
  })

  it('sends the historic door for ended cases and lists the engine batches with their parts', async () => {
    renderPage(undefined)
    await screen.findAllByRole('button', { name: /Migrate cases/ })

    fireEvent.click(button(/Migrate ended cases/, 1))
    fireEvent.change(screen.getByLabelText(/Reason/), {
      target: { value: 'history follows the fixed model' },
    })
    fireEvent.click(screen.getByRole('button', { name: /Migrate history \(2\)/ }))
    const path = '/api/case-definitions/{engineId}/{caseDefinitionId}/migrate-historic'
    await waitFor(() => {
      expect(post.mock.calls.some(([called]) => called === path)).toBe(true)
    })

    fireEvent.click(button('Batches', 1))
    const table = await screen.findByRole('table', { name: 'Case migration batches' })
    expect(table.textContent).toContain('fail 1 · success 2')
    expect(table.textContent).toContain('not succeeded: case-9')
  })

  it('greys every door for a non-admin', async () => {
    role = 'OPERATOR'
    renderPage(undefined)
    await screen.findAllByRole('button', { name: /Migrate cases/ })
    expect(button(/Migrate cases/, 1).disabled).toBe(true)
    expect(button(/Batch-migrate all running/, 1).disabled).toBe(true)
    expect(button(/Migrate ended cases/, 1).disabled).toBe(true)
    expect(screen.getByText(/Case migration needs ADMIN/)).toBeTruthy()
  })
})
