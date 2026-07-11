// @vitest-environment jsdom
// R-SAFE-05 point-of-action visibility (usability W3 sliver): a protected instance shows the
// "Protected" badge in the action toolbar AND greys every verb below the ADMIN floor with the
// spec §2 reason. Enforcement is server-side; this proves the operator SEES it where they act.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstanceDetail } from '../api/model'

// A non-admin identity: the gate must grey against a KNOWN below-ADMIN role.
vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'operator', role: 'OPERATOR' } }),
  roleOn: () => 'OPERATOR',
}))
// Idle mutation stub — the toolbar never fires an action in this render.
vi.mock('../api/actions', () => ({
  useInstanceAction: () => ({
    mutate: vi.fn(),
    isPending: false,
    reset: vi.fn(),
    error: undefined,
  }),
}))

import { InstanceActions } from './InstanceActions'

afterEach(cleanup)

const PROTECTED_VITALS: InstanceDetail = {
  compositeId: 'engine-a:pi-1',
  engineId: 'engine-a',
  processInstanceId: 'pi-1',
  status: 'ACTIVE',
  flags: { ended: false, suspended: false },
  protectedInstance: true,
  protectionReason: 'litigation hold — do not touch',
}

function renderActions(vitals: InstanceDetail) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <InstanceActions
          engineId="engine-a"
          instanceId="pi-1"
          vitals={vitals}
          engine={{ id: 'engine-a', environment: 'dev', mode: 'read-write' }}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('InstanceActions — R-SAFE-05 badge + per-verb reason', () => {
  it('shows the protected badge with the reason on its title', () => {
    renderActions(PROTECTED_VITALS)
    const badge = screen.getByText('🔒 Protected')
    expect(badge.getAttribute('title')).toContain('litigation hold — do not touch')
    expect(badge.getAttribute('title')).toContain('L3 (ADMIN)')
  })

  it('greys the destructive verbs and names the protection reason', () => {
    renderActions(PROTECTED_VITALS)
    // Terminate is disabled for the OPERATOR because the instance is protected.
    const terminate = screen.getByRole('button', { name: /Terminate/ })
    expect(terminate).toHaveProperty('disabled', true)
    // The spec §2 reason appears next to the greyed verbs (one 🔒 hint per verb; the ActionHint
    // splits the padlock and the text into sibling nodes, so match on the note's full text).
    const protectedHints = screen
      .getAllByRole('note')
      .filter((note) => note.textContent.includes('Protected — L3 action required'))
    expect(protectedHints.length).toBeGreaterThan(0)
  })

  it('shows no protected badge when the instance is not protected', () => {
    renderActions({ ...PROTECTED_VITALS, protectedInstance: false, protectionReason: undefined })
    expect(screen.queryByText('🔒 Protected')).toBeNull()
    expect(screen.queryByText('Protected — L3 action required')).toBeNull()
  })
})
