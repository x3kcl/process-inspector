// @vitest-environment jsdom
// #272 (R-AUD-04): a tab-level error banner must carry the quotable request id, same as the
// problemBanner path. The Errors & Jobs lane renders `{query.error.message}`, and a real
// `ApiError` already folds "Quote request ID … to support." into `.message` (shared
// requestId.ts wording) — this pins that so the tab surface can never silently drop it.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'

const lanesError = new ApiError(503, {
  detail: 'Job lanes could not be read.',
  code: 'engine-unreachable',
  requestId: 'req-tab-9f3',
})

vi.mock('../useInstanceQueries', () => ({
  useInstanceJobs: () => ({ data: undefined, isPending: false, isError: true, error: lanesError }),
  useInstanceExternalWorkerJobs: () => ({ data: [], isPending: false, isError: false }),
}))
vi.mock('../../api/useEngines', () => ({
  useEngines: () => ({ data: [{ id: 'engine-a', name: 'Engine A', environment: 'dev' }] }),
}))
vi.mock('../../api/me', () => ({
  useMe: () => ({ data: { role: 'ADMIN' } }),
  roleOn: () => 'ADMIN',
}))
vi.mock('../../api/actions', () => ({
  fetchActionCurl: vi.fn(),
  useInstanceAction: () => ({ mutate: vi.fn(), isPending: false, reset: vi.fn(), error: null }),
}))

import ErrorsJobsTab from './ErrorsJobsTab'

afterEach(cleanup)

describe('ErrorsJobsTab — quotable request id on the lane-error banner (#272, R-AUD-04)', () => {
  it('renders the request id in the error banner a user reads on the page', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <ErrorsJobsTab engineId="engine-a" instanceId="pi-1" />
      </QueryClientProvider>,
    )
    const banner = screen.getByRole('alert')
    expect(banner.textContent).toContain('Job lanes unavailable')
    expect(banner.textContent).toContain('Quote request ID req-tab-9f3 to support.')
  })
})
