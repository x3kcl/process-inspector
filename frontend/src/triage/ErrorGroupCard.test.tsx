// @vitest-environment jsdom
// Usability W2 #7 (theme T9): job-lane counts and instance counts LOOK comparable but
// aren't (36+13 jobs vs 46+7 instances) — every count on the triage card carries its
// unit token, so the two families can never be silently cross-summed.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import type { ErrorGroup } from '../api/model'
import { ErrorGroupCard } from './ErrorGroupCard'

afterEach(cleanup)

const group: ErrorGroup = {
  signatureHash: 'sig-1',
  algoVersion: 1,
  exceptionClass: 'java.net.SocketTimeoutException',
  normalizedMessage: 'connect timed out',
  total: 46,
  deadLetterCount: 36,
  retryingCount: 13,
  countsByEngine: {},
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ErrorGroupCard group={group} enginesById={new Map()} lowerBound={false} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ErrorGroupCard count-unit tokens (W2 #7, T9)', () => {
  it('labels the group total as instances and the lane counts as jobs', () => {
    renderCard()
    // The headline drill total counts INSTANCES.
    expect(screen.getByTitle(/error class in the grid/).textContent).toMatch(/46\s*instances/)
    // The lanes count JOBS — a different unit, and it says so.
    expect(screen.getByTitle(/dead-letter jobs/).textContent).toMatch(/36\s*jobs/)
    expect(screen.getByTitle(/retries left/).textContent).toMatch(/13\s*jobs/)
  })
})
