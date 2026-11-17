// @vitest-environment jsdom
// Issue #740: with read-scope enforcement on, a deep link to an engine outside the caller's scope
// answers the BFF's scoped 403. The page shows the scoped-refusal state, not the raw error or a crash.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EngineSystemPage } from './EngineSystemPage'

const get = vi.fn()
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: { GET: (...args: unknown[]) => get(...args) as unknown },
  }
})

afterEach(() => {
  cleanup()
  get.mockReset()
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/engines/engine-b/system']}>
        <Routes>
          <Route path="/admin/engines/:id/system" element={<EngineSystemPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('EngineSystemPage out of scope (#740)', () => {
  it('shows the scoped-refusal state on a 403 deep link', async () => {
    get.mockResolvedValue({
      data: undefined,
      error: { status: 403, code: 'forbidden', title: 'Forbidden', detail: 'Access Denied' },
      response: { status: 403 },
    })

    renderPage()

    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('engine-b is outside your access scope.')
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText(/Version:/)).toBeNull()
  })
})
