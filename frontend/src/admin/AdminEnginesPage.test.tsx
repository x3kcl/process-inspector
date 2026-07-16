// @vitest-environment jsdom
// #169: a real, specific 403 from the four-eyes approve door (proposer-cannot-approve-own,
// EngineRegistryStore.assertEligibleApprover) must surface as a banner — before this fix it
// landed in React Query state and rendered nowhere, so the only signal was the proposal
// silently staying pending.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'

let meData: { username: string; registryAdmin: boolean } | undefined = {
  username: 'admin',
  registryAdmin: true,
}
let mePending = false

vi.mock('../api/me', () => ({
  useMe: () => ({ data: meData, isPending: mePending }),
}))

const approveMutate = vi.fn()
let approveError: ApiError | undefined
let enginesData: Array<{ id: string; name: string; lifecycle: string; mode: string }> = []

vi.mock('./adminEngines', () => ({
  useAdminEngines: () => ({
    data: enginesData,
    isPending: false,
    isSuccess: true,
    isError: false,
    error: undefined,
  }),
  useDrift: () => ({ data: { empty: true } }),
  useEngineProposals: () => ({
    data: [{ id: 1, summary: 'remove engine-b', proposer: 'admin', reason: 'decommission' }],
  }),
  useEngineMutations: () => ({
    add: { mutate: vi.fn(), isPending: false, error: undefined, reset: vi.fn() },
    edit: { mutate: vi.fn(), isPending: false, error: undefined, reset: vi.fn() },
    probe: { mutate: vi.fn(), isPending: false },
    enable: { mutate: vi.fn(), isPending: false, error: undefined, reset: vi.fn() },
    disable: { mutate: vi.fn(), isPending: false, error: undefined, reset: vi.fn() },
    remove: { mutate: vi.fn(), isPending: false, error: undefined, reset: vi.fn() },
    purge: { mutate: vi.fn(), isPending: false, error: undefined, reset: vi.fn() },
    approve: { mutate: approveMutate, isPending: false, error: approveError },
  }),
}))

import { AdminEnginesPage } from './AdminEnginesPage'

afterEach(() => {
  cleanup()
  approveMutate.mockClear()
  approveError = undefined
  meData = { username: 'admin', registryAdmin: true }
  mePending = false
  enginesData = []
})

describe('AdminEnginesPage — fails closed while identity is unresolved (#208)', () => {
  it('shows a neutral loading state, never the privileged table, while `me` is pending', () => {
    mePending = true
    meData = undefined
    const { container } = render(<AdminEnginesPage />)
    expect(screen.getByText('Resolving your access…')).toBeDefined()
    expect(container.querySelector('.error-banner')).toBeNull()
    expect(screen.queryByText('Add engine')).toBeNull()
  })

  it('shows the restricted banner, never the privileged table, once resolved as non-registry-admin', () => {
    mePending = false
    meData = { username: 'plain-admin', registryAdmin: false }
    render(<AdminEnginesPage />)
    expect(screen.getByRole('alert').textContent).toContain('REGISTRY_ADMIN')
    expect(screen.queryByText('Add engine')).toBeNull()
  })
})

describe('AdminEnginesPage — four-eyes approve 403 feedback (#169)', () => {
  it('renders no approve-error banner when the approve mutation has no error', () => {
    const { container } = render(<AdminEnginesPage />)
    expect(container.querySelector('.banner-warn')).toBeNull()
  })

  it('surfaces the exact server refusal as an alert banner when approve is refused', () => {
    approveError = new ApiError(403, { detail: 'the proposer cannot approve their own proposal' })
    const { container } = render(<AdminEnginesPage />)
    const banner = container.querySelector('.banner-warn')
    expect(banner).not.toBeNull()
    expect(banner?.getAttribute('role')).toBe('alert')
    expect(banner?.textContent).toContain('the proposer cannot approve their own proposal')
  })

  it('clicking Approve calls the mutation for the right proposal', () => {
    render(<AdminEnginesPage />)
    screen.getByRole('button', { name: 'Approve' }).click()
    expect(approveMutate).toHaveBeenCalledWith(1, expect.anything())
  })
})

describe('AdminEnginesPage — probe-failed diagnostics (#223)', () => {
  it('gives a probe_failed row an actionable, non-leaking remediation title', () => {
    enginesData = [
      { id: 'engine-c', name: 'Engine C', lifecycle: 'probe_failed', mode: 'read-only' },
    ]
    render(<AdminEnginesPage />)
    const badge = screen.getByText('▲ Probe failed')
    expect(badge.getAttribute('title')).toContain('audit trail')
    // Never leak the raw connect exception into the UI — only a generic next step.
    expect(badge.getAttribute('title')).not.toMatch(/Exception|refused|timeout/i)
  })

  it('other lifecycle states render no such title', () => {
    enginesData = [{ id: 'engine-a', name: 'Engine A', lifecycle: 'active', mode: 'read-write' }]
    render(<AdminEnginesPage />)
    const badge = screen.getByText('✓ Active')
    expect(badge.getAttribute('title')).toBeNull()
  })
})
