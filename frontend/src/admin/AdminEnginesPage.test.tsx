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
let enginesData: Array<{
  id: string
  name: string
  lifecycle: string
  mode: string
  lastProbeFailureClass?: string
  reachableNow?: boolean | null
}> = []
let enginesError: ApiError | undefined

vi.mock('./adminEngines', () => ({
  useAdminEngines: () => ({
    data: enginesError === undefined ? enginesData : undefined,
    isPending: false,
    isSuccess: enginesError === undefined,
    isError: enginesError !== undefined,
    error: enginesError,
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
  enginesError = undefined
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

  it('surfaces the refused list query’s quotable request id on the grant-block (#272, R-AUD-04)', () => {
    mePending = false
    meData = { username: 'plain-admin', registryAdmin: false }
    // The list query fired and was refused 403 server-side — its ProblemDetail carries the id.
    enginesError = new ApiError(403, { code: 'forbidden', requestId: 'req-reg-42' })
    render(<AdminEnginesPage />)
    expect(screen.getByText('Quote request ID req-reg-42 to support.')).toBeDefined()
  })

  it('omits the request-id line when the refused query carried no id', () => {
    mePending = false
    meData = { username: 'plain-admin', registryAdmin: false }
    enginesError = new ApiError(403, { code: 'forbidden' })
    render(<AdminEnginesPage />)
    expect(screen.queryByText(/Quote request ID/)).toBeNull()
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

describe('AdminEnginesPage — probe-failed diagnostics (#223, extended #275)', () => {
  it('surfaces the failure class as VISIBLE row text, not tooltip-only, with a non-leaking tooltip', () => {
    enginesData = [
      {
        id: 'engine-c',
        name: 'Engine C',
        lifecycle: 'probe_failed',
        mode: 'read-only',
        lastProbeFailureClass: 'unreachable',
      },
    ]
    render(<AdminEnginesPage />)
    // #275 point 1: the reason is readable text in the row, not hidden behind a hover-only title.
    const badge = screen.getByText(/▲ Probe failed —/)
    expect(badge.textContent).toContain('could not reach the engine')
    // Never leak the raw connect exception into the UI — only the coarse, safe class.
    expect(badge.textContent).not.toMatch(/Exception|refused|timeout/i)
    // #275 point 4: the tooltip no longer claims a discoverable UI path that doesn't exist —
    // it still explains the audit trail holds the full text, without over-promising.
    expect(badge.getAttribute('title')).toContain('audit')
    expect(badge.getAttribute('title')).not.toContain('recorded server-side in this')
    expect(badge.getAttribute('title')).not.toMatch(/Exception|refused|timeout/i)
  })

  it('differentiates missing-secret-ref from a generic unreachable failure', () => {
    enginesData = [
      {
        id: 'engine-d',
        name: 'Engine D',
        lifecycle: 'probe_failed',
        mode: 'read-only',
        lastProbeFailureClass: 'missing_secret_ref',
      },
    ]
    render(<AdminEnginesPage />)
    expect(screen.getByText(/▲ Probe failed —/).textContent).toContain('missing credential')
  })

  it('differentiates an SSRF-at-probe rejection from a generic unreachable failure', () => {
    enginesData = [
      {
        id: 'engine-e',
        name: 'Engine E',
        lifecycle: 'probe_failed',
        mode: 'read-only',
        lastProbeFailureClass: 'ssrf_rejected',
      },
    ]
    render(<AdminEnginesPage />)
    expect(screen.getByText(/▲ Probe failed —/).textContent).toContain('rejected before dialing')
  })

  it('falls back to the generic wording for a pre-#275 row with no stored failure class', () => {
    enginesData = [
      { id: 'engine-c', name: 'Engine C', lifecycle: 'probe_failed', mode: 'read-only' },
    ]
    render(<AdminEnginesPage />)
    expect(screen.getByText(/▲ Probe failed —/).textContent).toContain('could not reach the engine')
  })
})

describe('AdminEnginesPage — positive reachability badge (#275)', () => {
  it('shows an explicit reachable badge, distinct from mere "no error seen yet"', () => {
    enginesData = [
      {
        id: 'engine-a',
        name: 'Engine A',
        lifecycle: 'active',
        mode: 'read-write',
        reachableNow: true,
      },
    ]
    render(<AdminEnginesPage />)
    expect(screen.getByText(/✓ Active —/).textContent).toContain('reachable')
  })

  it('flags a currently-unreachable ACTIVE engine distinctly, even though it stays enabled', () => {
    enginesData = [
      {
        id: 'engine-b',
        name: 'Engine B',
        lifecycle: 'active',
        mode: 'read-write',
        reachableNow: false,
      },
    ]
    render(<AdminEnginesPage />)
    expect(screen.getByText(/✓ Active —/).textContent).toContain('unreachable now')
  })

  it('reads as "health pending", never a fabricated healthy/unreachable claim, before the first live check', () => {
    enginesData = [{ id: 'engine-a', name: 'Engine A', lifecycle: 'active', mode: 'read-write' }]
    render(<AdminEnginesPage />)
    const badge = screen.getByText(/✓ Active —/)
    expect(badge.textContent).toContain('health pending')
    expect(badge.getAttribute('title')).toBeNull()
  })

  it('a DISABLED row still reads as a policy decision, unaffected by the reachability badge', () => {
    enginesData = [{ id: 'engine-7', name: 'Engine 7', lifecycle: 'disabled', mode: 'read-only' }]
    render(<AdminEnginesPage />)
    expect(screen.getByText('⏸ Disabled')).toBeDefined()
  })
})
