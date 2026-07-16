// @vitest-environment jsdom
// Usability W2 #7 (theme T9): job-lane counts and instance counts LOOK comparable but
// aren't (36+13 jobs vs 46+7 instances) — every count on the triage card carries its
// unit token, so the two families can never be silently cross-summed.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import type { ErrorGroup } from '../api/model'
import { OpsDrawerProvider } from '../ops/drawerState'
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

function renderCard(g: ErrorGroup = group, asOf?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <OpsDrawerProvider>
          <ErrorGroupCard group={g} enginesById={new Map()} lowerBound={false} asOf={asOf} />
        </OpsDrawerProvider>
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

  it('renders "—" for the DLQ/retrying split when scope-limited (S2, R-SAFE-17)', () => {
    // A partially-visible group under read scoping omits the un-splittable fleet-wide split; the
    // card must show "—" (scope-limited), never a misleading "0 jobs".
    renderCard({ ...group, deadLetterCount: undefined, retryingCount: undefined })
    expect(screen.getByTitle(/dead-letter jobs/).textContent).toContain('—')
    expect(screen.getByTitle(/dead-letter jobs/).textContent).not.toMatch(/\bjobs\b/)
    expect(screen.getByTitle(/retries left/).textContent).toContain('—')
    // The recomputed instance total is still shown truthfully.
    expect(screen.getByTitle(/error class in the grid/).textContent).toMatch(/46\s*instances/)
  })
})

describe('ErrorGroupCard staleness caveat on the headline count (#209)', () => {
  it('shows a visible "as of" caveat next to the count when the aggregation stamp is known', () => {
    renderCard(group, '2026-07-16T10:00:00Z')
    expect(screen.getByText(/as of/).closest('.group-total-asof')).not.toBeNull()
  })

  it('renders no caveat when the aggregation stamp is unknown', () => {
    renderCard(group, undefined)
    expect(screen.queryByText(/as of/)).toBeNull()
  })
})

describe('ErrorGroupCard whole-class retry (#105 remainder)', () => {
  it('offers "Retry group (all versions)" only when more than one version is deployed', () => {
    renderCard({
      ...group,
      countsByEngine: {
        'engine-a': { 'payment:v1': 5, 'payment:v2': 10, 'orders:v1': 3 },
      },
    })
    // payment has two deployed versions — the whole-class door is worth its own button.
    expect(screen.getByRole('button', { name: 'Retry group (all versions)' })).not.toBeNull()
    // orders has exactly one version — the per-version button already covers it; a second,
    // functionally-identical "all versions" button would just be noise.
    expect(screen.queryAllByRole('button', { name: 'Retry group (all versions)' })).toHaveLength(1)
  })

  it('opens the modal scoped to every version, not one defKey:vN slice', () => {
    renderCard({
      ...group,
      countsByEngine: {
        'engine-a': { 'payment:v1': 5, 'payment:v2': 10 },
      },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Retry group (all versions)' }))
    expect(screen.getByRole('heading', { name: /run every failed step/ })).not.toBeNull()
    expect(screen.getByText(/Retry group — payment \(all versions\)/)).not.toBeNull()
    expect(screen.getByText(/every deployed version/)).not.toBeNull()
    // The count context line sums BOTH versions (5 + 10 = 15), not just one slice.
    expect(screen.getByText(/15 failing instances/)).not.toBeNull()
  })
})
