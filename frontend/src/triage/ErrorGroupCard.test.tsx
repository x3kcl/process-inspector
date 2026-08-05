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

describe('ErrorGroupCard per-version drill link (#233)', () => {
  it('the per-version count links to a search scoped to exactly that version', () => {
    renderCard({
      ...group,
      countsByEngine: {
        'engine-a': { 'payment:v42': 35 },
      },
    })
    const link = screen.getByRole('link', { name: /v42:\s*35/ })
    const href = link.getAttribute('href') ?? ''
    const params = new URLSearchParams(href.split('?')[1] ?? '')
    expect(params.get('definitionKey')).toBe('payment')
    expect(params.get('version')).toBe('42')
    expect(link.getAttribute('title')).toContain('payment v42')
  })

  it('a versionless "all" chip links without a version filter and says so', () => {
    renderCard({
      ...group,
      countsByEngine: {
        'engine-a': { payment: 4 },
      },
    })
    const link = screen.getByRole('link', { name: /all:\s*4/ })
    const href = link.getAttribute('href') ?? ''
    const params = new URLSearchParams(href.split('?')[1] ?? '')
    expect(params.get('definitionKey')).toBe('payment')
    expect(params.has('version')).toBe(false)
    expect(link.getAttribute('title')).toContain('all versions')
  })
})

describe('ErrorGroupCard attention badge wiring (ALARM-COST-MODEL.md §4.3/§11, #354)', () => {
  // The badge itself is fully covered by components/AttentionBadge.test.tsx; this only proves
  // the Stage-0 card actually renders it off `group.attention`, absent or present — the server
  // already reorders `errorGroups` itself (AttentionOrdering, §11), so the card renders in
  // whatever order it receives without any client-side re-sort.
  it('renders no attention badge when the group carries no server score (the expected-today case)', () => {
    renderCard()
    expect(screen.queryByText('ranked by attention')).toBeNull()
  })

  it('renders the attention badge with the server rationale as VISIBLE text when the score is present (#374)', () => {
    renderCard({
      ...group,
      attention: { score: 4.2, rationale: '46 failing · last seen 2 min ago' },
    })
    expect(screen.getByText('ranked by attention')).not.toBeNull()
    expect(screen.getByText('46 failing · last seen 2 min ago')).not.toBeNull()
  })
})

// #374 (ALARM-COST-MODEL.md §12.1): the measured usability run staged exactly this shape — a
// 15-instance class the server ranks ABOVE a 34-instance class, because the 15-instance class
// resolves far faster on average. Every raw number visible on the CARD FACE (instances, DLQ,
// retrying) is larger on the 34-card, so only visible reasoning — not a hover — can tell a
// non-hovering reader why the smaller card outranks the bigger one. This reproduces that pair
// and asserts the reconciling text is real page content, not title-only.
describe('ErrorGroupCard reconciles the visible face with the attention order (#374 repro)', () => {
  it('shows why the smaller, costlier class outranks the bigger, cheaper one — without a hover', () => {
    const costlySmall: ErrorGroup = {
      ...group,
      signatureHash: 'sig-costly-15',
      exceptionClass: 'org.acme.MethodNotFoundException',
      normalizedMessage: 'zoo method not found',
      total: 15,
      deadLetterCount: 15,
      retryingCount: 0,
      attention: {
        score: 8.0,
        rationale:
          '15 failing · last seen just now · typically takes 4 min to resolve · no self-heal history.',
      },
    }
    const bigButCheap: ErrorGroup = {
      ...group,
      signatureHash: 'sig-big-34',
      exceptionClass: 'java.lang.ArithmeticException',
      normalizedMessage: 'division by zero',
      total: 34,
      deadLetterCount: 34,
      retryingCount: 0,
      attention: {
        score: 5.13,
        rationale:
          '34 failing · last seen 12 min ago · typically takes 3 h to resolve · no self-heal history.',
      },
    }
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, enabled: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <OpsDrawerProvider>
            <ErrorGroupCard
              group={costlySmall}
              enginesById={new Map()}
              lowerBound={false}
              asOf={undefined}
            />
            <ErrorGroupCard
              group={bigButCheap}
              enginesById={new Map()}
              lowerBound={false}
              asOf={undefined}
            />
          </OpsDrawerProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    // The raw numbers on the card face point the WRONG way (34 > 15 instances and DLQ jobs) —
    // exactly the §8.8 measured contradiction. The visible rationale text is the only thing
    // that can reconcile it for a reader who never hovers; `getByText` only matches rendered
    // content, so a hover-only regression fails this exactly as it fails on the real UI.
    expect(
      screen.getByText(
        '15 failing · last seen just now · typically takes 4 min to resolve · no self-heal history.',
      ),
    ).not.toBeNull()
    expect(
      screen.getByText(
        '34 failing · last seen 12 min ago · typically takes 3 h to resolve · no self-heal history.',
      ),
    ).not.toBeNull()
    // Sanity: the raw counts genuinely contradict the order (34 > 15), so the assertions above
    // are reconciling a real contradiction, not a vacuous one.
    expect(bigButCheap.total ?? 0).toBeGreaterThan(costlySmall.total ?? 0)
    expect((bigButCheap.attention?.score ?? 0) < (costlySmall.attention?.score ?? 0)).toBe(true)
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
