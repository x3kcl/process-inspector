// @vitest-environment jsdom
// #771: a fleet-grant holder (REGISTRY_ADMIN / ACCESS_ADMIN) with NO engine role lands in an
// "admin only" shell, decided from GET /api/me alone: no gated call, no SSE stream, no engine
// strip / omnibox / ops drawer; "/" goes to the first admin page they hold; a data deep link shows
// the short explanation. A fleet grant plus an engine role, and a user with nothing, are unchanged.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { Shell } from './Shell'

const NO_ROLE = {
  username: 'pat',
  role: null,
  engineRoles: {},
  registryAdmin: false,
  accessAdmin: false,
  breakGlass: false,
}
const REG = { ...NO_ROLE, registryAdmin: true }
const VIEWER = { ...NO_ROLE, role: 'VIEWER', engineRoles: { eng1: 'VIEWER' } }

let me: unknown = NO_ROLE
const get = vi.fn((path: string) =>
  Promise.resolve(
    path === '/api/me'
      ? { data: me, response: { status: 200 } }
      : { data: [], error: undefined, response: { status: 200 } },
  ),
)
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return { ...actual, api: { GET: (...a: [string]) => get(...a) } }
})

const streams = vi.fn()
beforeAll(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
  vi.stubGlobal(
    'EventSource',
    class {
      constructor(url: string) {
        streams(url)
      }
      close() {}
      addEventListener() {}
      removeEventListener() {}
    },
  )
})
afterEach(() => {
  cleanup()
  get.mockClear()
  streams.mockClear()
})

describe('Shell admin-only state (#771)', () => {
  function renderAt(path: string) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route element={<Shell />}>
              <Route index element={<p>landing page</p>} />
              <Route path="data" element={<p>data page</p>} />
              <Route path="admin/engines" element={<p>engines admin</p>} />
              <Route path="admin/access" element={<p>access admin</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  it('lands on the registry page with the explanation, calling no gated route', async () => {
    me = { ...REG, accessAdmin: true }
    renderAt('/')

    expect(await screen.findByText('engines admin')).not.toBeNull()
    expect(screen.getByText(/administrative access but no engine role/i)).not.toBeNull()
    expect(screen.queryByText('landing page')).toBeNull()
    expect(screen.queryByRole('heading', { name: 'No access granted yet' })).toBeNull()
    expect(get.mock.calls.map((c) => c[0])).toEqual(['/api/me'])
    expect(streams).not.toHaveBeenCalled()
  })

  it('an ACCESS_ADMIN only lands on the access page and is offered no registry link', async () => {
    me = { ...NO_ROLE, accessAdmin: true }
    renderAt('/')

    expect(await screen.findByText('access admin')).not.toBeNull()
    expect(screen.queryByRole('link', { name: 'Engines' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Definitions' })).toBeNull()
  })

  it('a deep link to a data page shows the explanation, not the page', async () => {
    me = REG
    renderAt('/data')

    expect(await screen.findByText(/administrative access but no engine role/i)).not.toBeNull()
    expect(screen.queryByText('data page')).toBeNull()
    expect(get.mock.calls.map((c) => c[0])).toEqual(['/api/me'])
  })

  it('an unknown extra grant does not break the admin-only decision', async () => {
    me = { ...REG, metricsReader: true }
    renderAt('/')

    expect(await screen.findByText('engines admin')).not.toBeNull()
  })

  it('a fleet grant plus an engine role is the normal shell', async () => {
    me = { ...VIEWER, registryAdmin: true }
    renderAt('/')

    expect(await screen.findByText('landing page')).not.toBeNull()
    expect(screen.queryByText(/administrative access but no engine role/i)).toBeNull()
  })
})
