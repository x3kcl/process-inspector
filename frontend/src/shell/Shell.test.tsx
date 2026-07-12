// @vitest-environment jsdom
// U4 (#89): the auth-gating logic in Shell (SPEC §4 — sign-in appears the moment ANY cached
// query answers 401, OR the dev sign-out ladder fires) and the permanent break-glass/session-
// expiry banners (IDP-SECURITY.md §5/§7) had no test at any layer besides the Playwright
// smoke — this is the cheap component-level rung that would have caught a regression before
// a full browser run.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { setSignedOut } from '../api/session'
import { Shell } from './Shell'

const emptyGet = vi
  .fn()
  .mockResolvedValue({ data: [], error: undefined, response: { status: 200 } })
vi.mock('../api/client', async () => {
  const actual = await vi.importActual<typeof import('../api/client')>('../api/client')
  return {
    ...actual,
    api: { GET: (...args: unknown[]) => emptyGet(...args) as unknown },
  }
})
vi.mock('../api/me', () => ({
  useMe: vi.fn(),
}))

beforeAll(() => {
  // jsdom ships neither — Shell's child tree touches both (AG-grid-adjacent widgets,
  // LiveProvider's SSE channel) once authRequired is false.
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

afterEach(() => {
  cleanup()
  setSignedOut(false) // module-level state — never leak into the next test
  emptyGet.mockClear()
})

async function renderShell(meData: unknown) {
  const { useMe } = await import('../api/me')
  vi.mocked(useMe).mockReturnValue({ data: meData } as ReturnType<typeof useMe>)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Shell />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}

describe('Shell auth gating (SPEC §4)', () => {
  it('the explicit dev sign-out forces the SignIn form, even with no cached 401', async () => {
    setSignedOut(true)
    await renderShell(undefined)
    expect(await screen.findByRole('button', { name: /sign in/i })).not.toBeNull()
  })

  it('a cached 401 (not a reauth-freshness challenge) forces the SignIn form', async () => {
    const client = await renderShell(undefined)
    // Simulate what a real query would leave behind on an unauthenticated 401 — the
    // mechanism useAnyAuthError actually scans, not just the sign-out escape hatch above.
    client
      .getQueryCache()
      .build(client, { queryKey: ['probe'] })
      .setState({
        status: 'error',
        error: new ApiError(401, {}),
        fetchStatus: 'idle',
      })
    expect(await screen.findByRole('button', { name: /sign in/i })).not.toBeNull()
  })

  it('a 401 shaped as a reauth-freshness challenge does NOT force sign-out', async () => {
    const client = await renderShell({ role: 'RESPONDER', engineRoles: {} })
    client
      .getQueryCache()
      .build(client, { queryKey: ['probe'] })
      .setState({
        status: 'error',
        error: new ApiError(401, { code: 'reauth-required' }),
        fetchStatus: 'idle',
      })
    // The topbar (never hidden while merely re-auth-challenged) proves Shell rendered
    // the normal app frame, not the sign-in gate.
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /flowable process inspector/i })).not.toBeNull()
    })
    expect(screen.queryByRole('button', { name: /sign in/i })).toBeNull()
  })

  it('no auth problem renders the normal app frame, with neither banner', async () => {
    await renderShell({ role: 'RESPONDER', engineRoles: {} })
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /flowable process inspector/i })).not.toBeNull()
    })
    expect(screen.queryByRole('button', { name: /sign in/i })).toBeNull()
    expect(screen.queryByText(/break-glass session/i)).toBeNull()
    expect(screen.queryByText(/session expires in/i)).toBeNull()
  })
})

describe('BreakGlassBanner (IDP-SECURITY.md §7, R-SAFE-11)', () => {
  it('is permanently visible for a break-glass session', async () => {
    await renderShell({ role: 'ADMIN', engineRoles: {}, breakGlass: true })
    expect(await screen.findByText(/break-glass session/i)).not.toBeNull()
  })

  it('is absent for a normal session', async () => {
    await renderShell({ role: 'ADMIN', engineRoles: {}, breakGlass: false })
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /flowable process inspector/i })).not.toBeNull()
    })
    expect(screen.queryByText(/break-glass session/i)).toBeNull()
  })
})

describe('SessionExpiryBanner (IDP-SECURITY.md §5, R-SAFE-07 warn-before-guillotine)', () => {
  it('shows the countdown once the absolute cap is inside the 30-minute warn window', async () => {
    const soon = new Date(Date.now() + 5 * 60_000).toISOString()
    await renderShell({ role: 'RESPONDER', engineRoles: {}, sessionExpiresAt: soon })
    expect(await screen.findByText(/session expires in/i)).not.toBeNull()
  })

  it('stays silent while the cap is far away', async () => {
    const later = new Date(Date.now() + 3 * 60 * 60_000).toISOString()
    await renderShell({ role: 'RESPONDER', engineRoles: {}, sessionExpiresAt: later })
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /flowable process inspector/i })).not.toBeNull()
    })
    expect(screen.queryByText(/session expires in/i)).toBeNull()
  })
})
