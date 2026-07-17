// @vitest-environment jsdom
// #237 skip-to-retry, tab half: once the lane data is on screen, an armed
// focusDeadLetterAction request lands focus on the dead-letter Retry button and reports
// itself handled — the completion of the inspect page's one-Tab skip command.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InstanceJobs } from '../../api/model'

vi.mock('../useInstanceQueries', () => ({
  useInstanceJobs: () => ({
    data: {
      deadLetter: [
        {
          id: 'job-1',
          elementId: 'callBilling',
          elementName: 'Call billing',
          retries: 0,
          exceptionMessage: 'boom',
        },
      ],
      executable: [],
      timer: [],
      suspended: [],
    } satisfies InstanceJobs,
    isPending: false,
    isError: false,
  }),
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

function renderTab(props: { focusDeadLetterAction?: boolean; onFocused?: () => void }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  render(
    <QueryClientProvider client={client}>
      <ErrorsJobsTab
        engineId="engine-a"
        instanceId="pi-1"
        focusDeadLetterAction={props.focusDeadLetterAction}
        onDeadLetterActionFocused={props.onFocused}
      />
    </QueryClientProvider>,
  )
}

describe('ErrorsJobsTab skip-to-retry completion (#237)', () => {
  it('focuses the dead-letter Retry button and reports the request handled', async () => {
    const onFocused = vi.fn()
    renderTab({ focusDeadLetterAction: true, onFocused })

    const retry = screen.getByRole('button', { name: 'Retry job' })
    await waitFor(() => {
      expect(document.activeElement).toBe(retry)
    })
    expect(onFocused).toHaveBeenCalled()
  })

  it('leaves focus alone when no skip was requested', () => {
    renderTab({})
    expect(screen.getByRole('button', { name: 'Retry job' })).toBeTruthy()
    expect(document.activeElement).toBe(document.body)
  })
})
