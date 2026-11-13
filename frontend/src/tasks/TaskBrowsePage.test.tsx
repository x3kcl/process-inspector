// @vitest-environment jsdom
// Task browser (#628): opens with Overdue OFF (so tasks show when tasks exist) and an empty
// result names the active filters with a one-click clear.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TaskBrowsePage } from './TaskBrowsePage'

const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return { ...actual, api: { GET: (...args: unknown[]) => get(...args) as unknown } }
})
vi.mock('../components/toast', () => ({ useToast: () => vi.fn() }))

afterEach(() => {
  cleanup()
  get.mockReset()
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <TaskBrowsePage />
    </QueryClientProvider>,
  )
}

const lastQuery = () =>
  (get.mock.calls.at(-1) as [string, { params: { query: Record<string, unknown> } }])[1].params
    .query

describe('TaskBrowsePage (#628)', () => {
  it('opens with Overdue unchecked and queries overdue=false', async () => {
    get.mockResolvedValue({ data: { rows: [] }, response: { status: 200 } })
    renderPage()
    expect(screen.getByLabelText<HTMLInputElement>('Overdue').checked).toBe(false)
    await waitFor(() => {
      expect(get).toHaveBeenCalled()
    })
    expect(lastQuery().overdue).toBe(false)
  })

  it('names the active filters on an empty result and clears them in one click', async () => {
    get.mockResolvedValue({ data: { rows: [] }, response: { status: 200 } })
    renderPage()
    fireEvent.click(screen.getByLabelText('Overdue'))
    fireEvent.change(screen.getByLabelText('Assignee'), { target: { value: 'kermit' } })
    const status = await screen.findByText(/no open tasks match the active filters/i)
    expect(status.textContent).toContain('assignee "kermit"')
    expect(status.textContent).toContain('overdue')
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(screen.getByLabelText<HTMLInputElement>('Overdue').checked).toBe(false)
    expect(screen.getByLabelText<HTMLInputElement>('Assignee').value).toBe('')
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull()
    })
  })
})
