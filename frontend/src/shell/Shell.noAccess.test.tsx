// @vitest-environment jsdom
// #758: a signed-in user with NO role on any engine (an unmapped OIDC user) lands on a clear
// "no access granted yet" state — a real heading, the ask-an-admin text, Sign out still there —
// and the SPA never calls the gated routes (no fleet reads, no SSE stream) to find that out:
// GET /api/me alone tells it. A role holder still gets the routed page.
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

function renderShell() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Routes>
          <Route element={<Shell />}>
            <Route index element={<p>landing page</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Shell no-access state (#758)', () => {
  it('a signed-in user with no role sees the no-access state and no gated call is made', async () => {
    me = NO_ROLE
    renderShell()

    expect(await screen.findByRole('heading', { name: 'No access granted yet' })).not.toBeNull()
    expect(screen.getByText(/ask an Inspector admin/i)).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Sign out' })).not.toBeNull()
    expect(screen.queryByText('landing page')).toBeNull()
    expect(get.mock.calls.map((c) => c[0])).toEqual(['/api/me'])
    expect(streams).not.toHaveBeenCalled()
  })

  it('a role holder gets the routed page, not the no-access state', async () => {
    me = VIEWER
    renderShell()

    expect(await screen.findByText('landing page')).not.toBeNull()
    expect(screen.queryByRole('heading', { name: 'No access granted yet' })).toBeNull()
  })
})
