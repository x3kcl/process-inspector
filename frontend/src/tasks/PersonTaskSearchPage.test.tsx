// @vitest-environment jsdom
// Person-centric task search (#99): the load-bearing behaviors are URL-driven search
// (?person=), the zero/partial-results states reusing /search's honesty machinery, and rows
// carrying enough shape (engineId/taskId) to feed the EXISTING reassign modal unchanged.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PersonTaskSearchPage } from './PersonTaskSearchPage'

const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: { GET: (...args: unknown[]) => get(...args) as unknown },
  }
})
vi.mock('../api/useEngines', () => ({
  useEngines: () => ({ data: [{ id: 'engine-a', name: 'Engine A', environment: 'dev' }] }),
}))
vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'me', roles: { 'engine-a': 'OPERATOR' } } }),
  roleOn: () => 'OPERATOR',
}))

afterEach(() => {
  cleanup()
  get.mockReset()
})

function renderPage(initialPath: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <PersonTaskSearchPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PersonTaskSearchPage (#99)', () => {
  it('shows the empty prompt when no person is in the URL, and never calls the search endpoint', () => {
    renderPage('/tasks')
    expect(screen.getByText(/enter a person/i)).not.toBeNull()
    expect(get).not.toHaveBeenCalled()
  })

  it('renders rows for a person named in the URL, feeding the shared reassign/return actions', async () => {
    get.mockResolvedValue({
      data: {
        rows: [
          {
            engineId: 'engine-a',
            processInstanceId: 'pi-1',
            taskId: 't1',
            taskName: 'Approve invoice',
            taskDefinitionKey: 'approveTask',
            processDefinitionKey: 'invoiceApproval',
            assignee: 'bob',
            createTime: '2026-07-10T10:00:00.000+0000',
            dueDate: '2026-07-15T10:00:00.000+0000',
            matchReason: 'ASSIGNED',
          },
        ],
        perEngine: { 'engine-a': { ok: true, fetched: 1, total: 1 } },
      },
      error: undefined,
      response: { status: 200 },
    })

    renderPage('/tasks?person=bob')

    await waitFor(() => {
      expect(screen.getByText('Approve invoice')).not.toBeNull()
    })
    expect(get).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({ params: { query: { person: 'bob', engineIds: undefined } } }),
    )
    expect(screen.getByRole('button', { name: 'Reassign' }).hasAttribute('disabled')).toBe(false)
    expect(screen.getByRole('button', { name: 'Return to team' }).hasAttribute('disabled')).toBe(
      false,
    )
  })

  it('shows the partial-results banner when an engine fails, without hiding the rows that did answer', async () => {
    get.mockResolvedValue({
      data: {
        rows: [
          {
            engineId: 'engine-a',
            processInstanceId: 'pi-1',
            taskId: 't1',
            taskName: 'Approve invoice',
            assignee: 'bob',
            matchReason: 'ASSIGNED',
          },
        ],
        perEngine: {
          'engine-a': { ok: true, fetched: 1, total: 1 },
          'engine-b': { ok: false, error: 'connection refused' },
        },
      },
      error: undefined,
      response: { status: 200 },
    })

    renderPage('/tasks?person=bob')

    await waitFor(() => {
      expect(screen.getByText('Approve invoice')).not.toBeNull()
    })
    expect(screen.getByRole('status').textContent).toMatch(/engines answered/i)
  })

  it('shows the honest zero state when the search succeeds with no matching tasks', async () => {
    get.mockResolvedValue({
      data: { rows: [], perEngine: { 'engine-a': { ok: true, fetched: 0, total: 0 } } },
      error: undefined,
      response: { status: 200 },
    })

    renderPage('/tasks?person=nobody')

    await waitFor(() => {
      expect(
        screen.getByText(/no open tasks assigned to, or claimable by, "nobody"/i),
      ).not.toBeNull()
    })
  })
})
