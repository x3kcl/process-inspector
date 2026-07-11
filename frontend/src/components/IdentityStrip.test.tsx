// @vitest-environment jsdom
// Usability W3 dev-ladder surface: the header shows "who am I" and a Sign out that clears the
// client Basic creds, POSTs the CSRF-safe server logout, drops the query cache, and flips the
// explicit sign-out flag (so Shell renders SignIn even before any query 401s).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { clearBasicAuth, postLogout, setSignedOut } = vi.hoisted(() => ({
  clearBasicAuth: vi.fn(),
  postLogout: vi.fn().mockResolvedValue(undefined),
  setSignedOut: vi.fn(),
}))

vi.mock('../api/auth', () => ({ clearBasicAuth }))
vi.mock('../api/client', () => ({ postLogout }))
vi.mock('../api/session', () => ({ setSignedOut }))
vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'admin', role: 'ADMIN' } }),
}))

import { IdentityStrip } from './IdentityStrip'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function renderStrip() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const clearSpy = vi.spyOn(client, 'clear')
  render(
    <QueryClientProvider client={client}>
      <IdentityStrip />
    </QueryClientProvider>,
  )
  return clearSpy
}

describe('IdentityStrip (usability W3)', () => {
  it('shows the signed-in user and role', () => {
    renderStrip()
    const group = screen.getByRole('group', { name: /identity/i })
    expect(group.textContent).toContain('Signed in as')
    expect(group.textContent).toContain('admin')
    expect(group.textContent).toContain('(ADMIN)')
  })

  it('Sign out clears creds, posts logout, clears the cache, and flips the sign-out flag', async () => {
    const clearSpy = renderStrip()
    fireEvent.click(screen.getByRole('button', { name: /Sign out/ }))

    expect(clearBasicAuth).toHaveBeenCalledOnce()
    expect(postLogout).toHaveBeenCalledOnce()
    await waitFor(() => {
      expect(setSignedOut).toHaveBeenCalledWith(true)
    })
    expect(clearSpy).toHaveBeenCalledOnce()
  })
})
