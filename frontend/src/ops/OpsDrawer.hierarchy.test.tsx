// @vitest-environment jsdom
// #497: a hierarchy migration job shows its per-level progress in the operations drawer —
// deepest level first, the parents a failed child left not run counted on their own level.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OpsDrawerProvider } from './drawerState'
import { OpsDrawer } from './OpsDrawer'
import type { BulkItemDto, BulkJobDto } from '../api/bulk'

const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: {
      POST: vi.fn(),
      GET: (...args: unknown[]) => get(...args) as unknown,
    },
  }
})

const JOB: BulkJobDto = {
  id: 'job-h',
  verb: 'migrate-instance',
  state: 'COMPLETED',
  submittedBy: 'admin',
  submittedAt: '2026-11-12T00:00:00Z',
  totalItems: 3,
  tallies: { failed: 1, not_run: 2 },
}

const hierarchyItems: BulkItemDto[] = [
  {
    ordinal: 0,
    engineId: 'engine-a',
    instanceId: 'leaf',
    jobRef: 'analysis-b',
    state: 'failed',
    detail: 'instance-suspended: activate it first',
    level: 2,
    parentInstanceId: 'mid',
  },
  {
    ordinal: 1,
    engineId: 'engine-a',
    instanceId: 'mid',
    jobRef: 'analysis-a',
    state: 'not_run',
    detail: 'blocked by child engine-a:leaf (failed)',
    level: 1,
    parentInstanceId: 'root',
  },
  {
    ordinal: 2,
    engineId: 'engine-a',
    instanceId: 'root',
    jobRef: 'analysis-root',
    state: 'not_run',
    detail: 'blocked by child engine-a:mid (not_run)',
    level: 0,
  },
]

afterEach(() => {
  cleanup()
  get.mockReset()
})

async function openJob(items: BulkItemDto[]) {
  get.mockImplementation((path: string) => {
    if (path === '/api/bulk') {
      return Promise.resolve({ data: [JOB], error: undefined, response: { status: 200 } })
    }
    if (path === '/api/bulk/{id}') {
      return Promise.resolve({
        data: { ...JOB, items },
        error: undefined,
        response: { status: 200 },
      })
    }
    return Promise.resolve({ data: undefined, error: 'not found', response: { status: 404 } })
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <OpsDrawerProvider>
        <OpsDrawer />
      </OpsDrawerProvider>
    </QueryClientProvider>,
  )
  fireEvent.click(await screen.findByRole('button', { name: /Operations/ }))
  fireEvent.click(await screen.findByText('migrate-instance'))
  await screen.findByText(items[0]?.detail ?? '')
}

describe('OpsDrawer hierarchy migration progress (#497)', () => {
  it('lists the levels deepest first with what each one settled', async () => {
    await openJob(hierarchyItems)

    const lines = [...screen.getByTestId('hierarchy-levels').querySelectorAll('li')].map(
      (li) => li.textContent,
    )
    expect(lines).toEqual([
      'level 2 — 0 of 1 migrated · 1 failed',
      'level 1 — 0 of 1 migrated · 1 not run',
      'level 0 — 0 of 1 migrated · 1 not run',
    ])
    expect(screen.getByRole('columnheader', { name: 'Level' })).toBeTruthy()
    expect(screen.getByText('blocked by child engine-a:leaf (failed)')).toBeTruthy()
  })

  it('shows no level block or column for a cohort migrate job', async () => {
    await openJob(
      hierarchyItems.map((item) => ({ ...item, level: undefined, parentInstanceId: undefined })),
    )

    expect(screen.queryByTestId('hierarchy-levels')).toBeNull()
    expect(screen.queryByRole('columnheader', { name: 'Level' })).toBeNull()
  })
})
