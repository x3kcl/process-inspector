// @vitest-environment jsdom
// #663: the ordering rule was only the `title` of the small "ranked by attention" pill, and
// "no … history" on the cards read as a warning. One VISIBLE line above the cards states both.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, expect, it } from 'vitest'
import type { ErrorGroup } from '../api/model'
import { ErrorGroupSections } from './ErrorGroupSections'

afterEach(cleanup)

const scored: ErrorGroup = {
  signatureHash: 'g1',
  algoVersion: 1,
  exceptionClass: 'com.acme.Boom',
  normalizedMessage: 'boom',
  total: 3,
  deadLetterCount: 3,
  retryingCount: 0,
  countsByEngine: {},
  attention: {
    score: 1,
    rationale: '3 failing · last seen just now · no resolve-time history · no self-heal history.',
  },
}

function renderSections(groups: ErrorGroup[]) {
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { enabled: false } } })}
    >
      <MemoryRouter>
        <ErrorGroupSections
          groups={groups}
          enginesById={new Map()}
          lowerBound={false}
          asOf={undefined}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

it('states the ordering rule and the "no … history" gloss as visible text above the cards', () => {
  // Given / When
  renderSections([scored])

  // Then
  expect(screen.getByText(/^Ordered by the expected cost of waiting:/).textContent).toContain(
    '“No … history” on a card is the normal state for most classes and does not raise or lower its rank.',
  )
})

it('shows no ordering line when no card carries a server score (flag off)', () => {
  renderSections([{ ...scored, attention: undefined }])

  expect(screen.queryByText(/Ordered by the expected cost of waiting/)).toBeNull()
})
