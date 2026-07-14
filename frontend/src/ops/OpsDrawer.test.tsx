// @vitest-environment jsdom
// #105 remainder: per-item retry-failed-only affordance. The load-bearing behavior is that
// a `failed` item gets its own "Retry" button (scoped to just that item, not the whole
// job's not_run/failed set — that's what "Continue as new job" already covers), while an
// `ok` item gets none.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OpsDrawerProvider } from './drawerState'
import { OpsDrawer } from './OpsDrawer'
import type { BulkItemDto, BulkJobDto } from '../api/bulk'

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

const JOB: BulkJobDto = {
  id: 'job-1',
  verb: 'suspend',
  state: 'COMPLETED',
  submittedBy: 'admin',
  submittedAt: '2026-07-01T00:00:00Z',
  totalItems: 2,
  tallies: { ok: 1, failed: 1 },
}

const ITEMS: BulkItemDto[] = [
  {
    ordinal: 1,
    engineId: 'dev-engine',
    instanceId: 'proc-ok',
    state: 'ok',
    detail: 'suspended cleanly',
  },
  {
    ordinal: 2,
    engineId: 'dev-engine',
    instanceId: 'proc-failed',
    jobRef: 'job-ref-2',
    state: 'failed',
    detail: 'boom',
  },
]

afterEach(() => {
  cleanup()
  post.mockReset()
  get.mockReset()
})

function renderDrawer() {
  get.mockImplementation((path: string, opts?: { params?: { path?: { id?: string } } }) => {
    if (path === '/api/bulk') {
      return Promise.resolve({ data: [JOB], error: undefined, response: { status: 200 } })
    }
    if (path === '/api/bulk/{id}' && opts?.params?.path?.id === 'job-1') {
      return Promise.resolve({
        data: { ...JOB, items: ITEMS },
        error: undefined,
        response: { status: 200 },
      })
    }
    return Promise.resolve({ data: undefined, error: 'not found', response: { status: 404 } })
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <OpsDrawerProvider>
        <OpsDrawer />
      </OpsDrawerProvider>
    </QueryClientProvider>,
  )
}

async function openExpandedJob() {
  renderDrawer()
  fireEvent.click(await screen.findByRole('button', { name: /Operations/ }))
  fireEvent.click(await screen.findByText('suspend'))
  await screen.findByText('boom')
}

describe('OpsDrawer per-item retry (#105)', () => {
  it('shows a Retry button only on the failed item, not the ok item', async () => {
    await openExpandedJob()

    const retryButtons = screen.getAllByRole('button', { name: 'Retry' })
    expect(retryButtons).toHaveLength(1)

    const failedRow = screen.getByText('boom').closest('tr')
    const okRow = screen.getByText('suspended cleanly').closest('tr')
    expect(failedRow).not.toBeNull()
    expect(okRow).not.toBeNull()
    expect(failedRow?.contains(retryButtons[0])).toBe(true)
    expect(okRow?.querySelector('button')).toBeNull()
  })

  it('retrying an item submits a new job scoped to just that item', async () => {
    await openExpandedJob()
    post.mockResolvedValue({
      data: { id: 'job-2', totalItems: 1 },
      error: undefined,
      response: { status: 200 },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => {
      expect(post.mock.calls.some(([path]) => path === '/api/bulk')).toBe(true)
    })
    const call = post.mock.calls.find(([path]) => path === '/api/bulk')
    expect(call?.[1]).toMatchObject({
      body: {
        verb: 'suspend',
        continuedFrom: 'job-1',
        items: [{ engineId: 'dev-engine', instanceId: 'proc-failed', jobId: 'job-ref-2' }],
      },
    })
  })
})
