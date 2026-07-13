// @vitest-environment jsdom
// #169: a real, specific 403 from the four-eyes approve door (proposer-cannot-approve-own,
// EngineRegistryStore.assertEligibleApprover) must surface as a banner — before this fix it
// landed in React Query state and rendered nowhere, so the only signal was the proposal
// silently staying pending.
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'

vi.mock('../api/me', () => ({
  useMe: () => ({ data: { username: 'admin', registryAdmin: true } }),
}))

const approveMutate = vi.fn()
let approveError: ApiError | undefined

vi.mock('./adminEngines', () => ({
  useAdminEngines: () => ({ data: [], isPending: false, isError: false, error: undefined }),
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
