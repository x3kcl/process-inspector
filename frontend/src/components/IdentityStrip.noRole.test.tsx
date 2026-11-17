// @vitest-environment jsdom
// #758: a signed-in user with no role (GET /api/me answers `role: null`) shows their name and
// Sign out — no empty "()" where the role would be.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'pat', role: null, engineRoles: {} } }),
}))

import { IdentityStrip } from './IdentityStrip'

afterEach(cleanup)

describe('IdentityStrip, no role (#758)', () => {
  it('shows the user without an empty role badge', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <IdentityStrip />
      </QueryClientProvider>,
    )
    const group = screen.getByRole('group', { name: /identity/i })
    expect(group.textContent).toContain('pat')
    expect(group.textContent).not.toContain('()')
  })
})
