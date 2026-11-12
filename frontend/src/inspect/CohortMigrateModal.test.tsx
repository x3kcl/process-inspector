// @vitest-environment jsdom
// Cohort migrate (#496): count → analyze → ONE bulk job over the analysis. The browser sends
// the analysis id, never an instance list; prod needs the executable count typed.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OpsDrawerProvider } from '../ops/drawerState'
import { CohortMigrateModal } from './CohortMigrateModal'

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

afterEach(() => {
  cleanup()
  post.mockReset()
  get.mockReset()
})

const ok = (data: unknown) => Promise.resolve({ data, error: undefined, response: { status: 200 } })

function renderModal(environment: string) {
  get.mockImplementation((path: string) => {
    if (path === '/api/engines') return ok([{ id: 'engine-a', name: 'A', environment }])
    return ok({
      id: 'an-1',
      status: 'COMPLETED',
      executableIds: ['i1', 'i2'],
      blockerCount: 1,
      warningCount: 0,
      lowerBound: false,
      banner: 'Inspector estimate — the engine is the only ground truth at execute',
    })
  })
  post.mockImplementation((path: string) => {
    if (path === '/api/migration/cohorts/preview')
      return ok({ count: 3, truncated: false, cap: 500 })
    if (path === '/api/migration/analyses') return ok({ id: 'an-1', status: 'RUNNING' })
    return ok({ id: 'job-1', totalItems: 2 })
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <OpsDrawerProvider>
        <CohortMigrateModal
          engineId="engine-a"
          fromDefinitionId="demo:1:x"
          versions={[
            { definitionId: 'demo:1:x', version: 1 },
            { definitionId: 'demo:2:y', version: 2, latest: true },
          ]}
          onClose={vi.fn()}
        />
      </OpsDrawerProvider>
    </QueryClientProvider>,
  )
}

async function analyze() {
  fireEvent.click(screen.getByRole('button', { name: 'Preview count' }))
  await screen.findByText(/3 running instances/)
  fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
  await screen.findByText(/2 executable · 1 with a blocker/)
  fireEvent.change(screen.getByLabelText(/Reason/), {
    target: { value: 'move the cohort off the bad deploy' },
  })
}

describe('CohortMigrateModal (#496)', () => {
  it('submits one bulk job over the analysis id, never an instance list', async () => {
    renderModal('dev')
    await analyze()
    fireEvent.click(screen.getByRole('button', { name: /Migrate 2/ }))

    await waitFor(() => {
      expect(post.mock.calls.some(([path]) => path === '/api/bulk/migrate')).toBe(true)
    })
    const call = post.mock.calls.find(([path]) => path === '/api/bulk/migrate')
    expect(call?.[1]).toEqual({
      body: {
        analysisIds: ['an-1'],
        reason: 'move the cohort off the bad deploy',
        ticketId: undefined,
        confirmedCount: undefined,
      },
    })
  })

  it('on prod, the executable count must be typed before Migrate enables', async () => {
    renderModal('prod')
    await analyze()
    const migrate = screen.getByRole('button', { name: /Migrate 2/ })
    expect((migrate as HTMLButtonElement).disabled).toBe(true)

    fireEvent.change(screen.getByLabelText(/Type the/), { target: { value: '2' } })
    expect((migrate as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(migrate)

    await waitFor(() => {
      const call = post.mock.calls.find(([path]) => path === '/api/bulk/migrate')
      expect(call?.[1]).toMatchObject({ body: { confirmedCount: 2 } })
    })
  })
})
