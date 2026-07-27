// @vitest-environment jsdom
// U4 (#89): the auth-gating logic in Shell (SPEC §4 — sign-in appears the moment ANY cached
// query answers 401, OR the dev sign-out ladder fires) and the permanent break-glass/session-
// expiry banners (IDP-SECURITY.md §5/§7) had no test at any layer besides the Playwright
// smoke — this is the cheap component-level rung that would have caught a regression before
// a full browser run.
import { StrictMode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes } from 'react-router'
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
// #168/#335 regression guard: spy on the real restoreRouteFocus while keeping its real
// behavior, so the StrictMode test below can assert useRouteFocus's ref-guard fires it
// exactly ONCE per real pathname change — never once per StrictMode dev double-invoke.
const restoreRouteFocusSpy = vi.fn()
vi.mock('./routeFocus', async () => {
  const actual = await vi.importActual<typeof import('./routeFocus')>('./routeFocus')
  return {
    ...actual,
    restoreRouteFocus: (...args: Parameters<typeof actual.restoreRouteFocus>) => {
      restoreRouteFocusSpy()
      return actual.restoreRouteFocus(...args)
    },
  }
})

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
  restoreRouteFocusSpy.mockClear()
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

describe('Skip-to-main-content link (#168 — bypass the ~11-stop header gauntlet)', () => {
  it('is the FIRST focusable element and targets the id="main-content" landmark', async () => {
    await renderShell({ role: 'RESPONDER', engineRoles: {} })
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /flowable process inspector/i })).not.toBeNull()
    })
    const links = screen.getAllByRole('link')
    // The skip-link must render before every OTHER header link (home, ops log, find tasks…) in
    // DOM order — that's what makes it Tab stop #1 rather than one of the ~11 buried further in.
    expect(links[0].textContent).toBe('Skip to main content')
    expect(links[0].getAttribute('href')).toBe('#main-content')
    // #main-content must exist and be a valid PROGRAMMATIC focus target (tabIndex={-1}) — an
    // href="#id" only moves focus if the target is actually focusable.
    const main = document.getElementById('main-content')
    expect(main).not.toBeNull()
    expect(main?.getAttribute('tabindex')).toBe('-1')
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

describe('useRouteFocus under React 19 StrictMode (#168/#335 regression guard)', () => {
  // Mirrors the production router shape (Shell wraps Outlet; each page's own top-level
  // element is a plain node, never nesting a second <main>) closely enough to exercise
  // useRouteFocus's ref-guard through a REAL pathname change, wrapped in StrictMode so
  // React 19's dev double-invoke of render + effects is actually in play.
  async function renderStrictShellWithRoutes(meData: unknown) {
    const { useMe } = await import('../api/me')
    vi.mocked(useMe).mockReturnValue({ data: meData } as ReturnType<typeof useMe>)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <StrictMode>
        <QueryClientProvider client={client}>
          <MemoryRouter initialEntries={['/']}>
            <Routes>
              <Route path="/" element={<Shell />}>
                <Route
                  index
                  element={
                    <div>
                      <h2>Home</h2>
                      <Link to="/other">Go elsewhere</Link>
                    </div>
                  }
                />
                <Route
                  path="other"
                  element={
                    <div>
                      <h2>Other</h2>
                    </div>
                  }
                />
              </Route>
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </StrictMode>,
    )
  }

  it('never fires on the initial load, even with StrictMode double-invoking the effect', async () => {
    await renderStrictShellWithRoutes({ role: 'RESPONDER', engineRoles: {} })
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Home' })).not.toBeNull()
    })
    // The hook queues restoreRouteFocus onto a macrotask (window.setTimeout(…, 0)) — give it
    // a turn. lastHandledPathname is SEEDED with the pathname at mount, so a genuine first
    // load must be a no-op on BOTH of StrictMode's dev-mode invocations, not just deduped.
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(restoreRouteFocusSpy).not.toHaveBeenCalled()
  })

  it('a real pathname change calls restoreRouteFocus exactly once — never twice from the StrictMode double-invoke', async () => {
    await renderStrictShellWithRoutes({ role: 'RESPONDER', engineRoles: {} })
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Home' })).not.toBeNull()
    })
    fireEvent.click(screen.getByRole('link', { name: 'Go elsewhere' }))
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Other' })).not.toBeNull()
    })
    await waitFor(() => {
      expect(restoreRouteFocusSpy).toHaveBeenCalledTimes(1)
    })
    // Let any stray second invocation surface before asserting the count holds steady —
    // the #168 bug was exactly a fixed "have I run before" flag that a second StrictMode
    // effect run flipped back, silently re-arming a duplicate fire.
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(restoreRouteFocusSpy).toHaveBeenCalledTimes(1)
    expect(document.activeElement?.textContent).toBe('Other')
  })
})
