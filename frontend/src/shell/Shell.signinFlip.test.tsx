// @vitest-environment jsdom
// #610 per-PR guard: with the REAL useMe (Shell.test.tsx mocks it and so can never see this), the
// 401 -> authenticated flip mounts IdentityStrip, whose useQuery builds/updates the cache DURING
// RENDER. If Shell's useAnyAuthError forwards those render-time cache events, React warns
// "Cannot update a component (`Shell`) while rendering a different component".
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { Shell } from './Shell'

let meStatus = 401
const get = vi.fn((path: string) =>
  Promise.resolve(
    path === '/api/me'
      ? meStatus === 200
        ? {
            data: { role: 'VIEWER', engineRoles: {}, username: 'viewer' },
            response: { status: 200 },
          }
        : { data: undefined, error: {}, response: { status: 401 } }
      : { data: [], error: undefined, response: { status: 200 } },
  ),
)
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return { ...actual, api: { GET: (...a: [string]) => get(...a) } }
})

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
      close() {}
      addEventListener() {}
      removeEventListener() {}
    },
  )
})
afterEach(cleanup)

describe('Shell sign-in flip (#610)', () => {
  it('raises no cross-component update warning when /api/me flips 401 -> 200', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <Shell />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('button', { name: /sign in/i })).not.toBeNull()
    meStatus = 200
    await client.invalidateQueries({ queryKey: ['me'] })
    await waitFor(() => expect(screen.queryByRole('button', { name: /sign in/i })).toBeNull())
    const warned = errors.mock.calls.filter((c) => /Cannot update a component/.test(String(c[0])))
    errors.mockRestore()
    expect(warned).toEqual([])
  })
})
