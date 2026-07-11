// @vitest-environment jsdom
// Stage-0 leak views (R-BAU-02): per-definition grouping + count labels + deep links, honest
// per R-SEM-05 (the SUSPENDED chip's link rides startedBefore, and its label says "started > 7
// days ago", never time-since-suspension).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { LeakViewsResponse } from '../api/model'
import { decodeSearch } from '../search/urlState'
import { LeakViewsSection } from './LeakViewsSection'

const fetchLeakViews = vi.hoisted(() => vi.fn<() => Promise<LeakViewsResponse>>())
vi.mock('../api/queries', () => ({ fetchLeakViews }))

afterEach(cleanup)

const WINDOWS = {
  activeOver30d: '2026-06-11T12:00:00Z',
  activeOver90d: '2026-04-12T12:00:00Z',
  suspendedStartedOver7d: '2026-07-04T12:00:00Z',
}

function renderSection(response: LeakViewsResponse) {
  fetchLeakViews.mockResolvedValue(response)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <LeakViewsSection />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The Stage-1 search a chip links to, decoded back from its href. */
function linkSearch(link: HTMLAnchorElement) {
  const query = link.getAttribute('href')?.split('?')[1] ?? ''
  const request = decodeSearch(new URLSearchParams(query))
  if (request === null) throw new Error('leak chip link carried no search')
  return request
}

describe('LeakViewsSection (R-BAU-02, honest per R-SEM-05)', () => {
  it('groups per definition and shows count · window chips that deep-link to Stage 1', async () => {
    renderSection({
      asOf: '2026-07-11T12:00:00Z',
      windows: WINDOWS,
      lowerBound: false,
      unavailableEngines: [],
      definitions: [
        { definitionKey: 'vacationRequest', activeOver30d: 212, activeOver90d: 40, suspendedStartedOver7d: 3 },
        { definitionKey: 'loanApproval', activeOver30d: 12, activeOver90d: 0, suspendedStartedOver7d: 0 },
      ],
    })

    const vr = (await screen.findByText('vacationRequest')).closest('li') as HTMLElement
    // "vacationRequest: 212 > 30d" — the count carried by the 30-day active chip.
    const chip30 = within(vr).getByText('> 30d').closest('a') as HTMLAnchorElement
    expect(chip30.textContent).toContain('212')
    const req30 = linkSearch(chip30)
    expect(req30.processDefinitionKey).toBe('vacationRequest')
    expect(req30.statuses).toEqual(['ACTIVE'])
    expect(req30.startedBefore).toBe(WINDOWS.activeOver30d)

    // loanApproval has only the 30-day active leak — zero windows render no chip.
    const loan = (await screen.findByText('loanApproval')).closest('li') as HTMLElement
    expect(within(loan).queryByText('> 90d')).toBeNull()
    expect(within(loan).queryByText('suspended · started > 7d')).toBeNull()
  })

  it('the SUSPENDED chip links to startedBefore and never implies time-since-suspension', async () => {
    renderSection({
      asOf: '2026-07-11T12:00:00Z',
      windows: WINDOWS,
      lowerBound: false,
      unavailableEngines: [],
      definitions: [
        { definitionKey: 'vacationRequest', activeOver30d: 0, activeOver90d: 0, suspendedStartedOver7d: 3 },
      ],
    })

    const chip = (await screen.findByText('suspended · started > 7d')).closest('a') as HTMLAnchorElement
    const request = linkSearch(chip)
    expect(request.statuses).toEqual(['SUSPENDED'])
    expect(request.startedBefore).toBe(WINDOWS.suspendedStartedOver7d)
    expect(request.startedAfter).toBeUndefined()
    // The honest predicate is stated to the operator; nothing implies a suspension duration.
    expect(chip.getAttribute('title')).toContain('started')
    expect(chip.getAttribute('title')).toContain('no suspension timestamp')
    expect(chip.getAttribute('title')).not.toMatch(/suspended (for|>|more than) *\d/i)
  })

  it('carries the lower-bound badge (≥) and names unreachable engines', async () => {
    renderSection({
      asOf: '2026-07-11T12:00:00Z',
      windows: WINDOWS,
      lowerBound: true,
      unavailableEngines: ['engine-b'],
      definitions: [
        { definitionKey: 'vacationRequest', activeOver30d: 212, activeOver90d: 0, suspendedStartedOver7d: 0 },
      ],
    })

    expect((await screen.findByRole('alert')).textContent).toContain('engine-b')
    const chip = screen.getByText('> 30d').closest('a') as HTMLElement
    expect(chip.textContent).toContain('≥212')
  })

  it('renders an honest zero-state when nothing leaks', async () => {
    renderSection({
      asOf: '2026-07-11T12:00:00Z',
      windows: WINDOWS,
      lowerBound: false,
      unavailableEngines: [],
      definitions: [],
    })

    expect(await screen.findByText(/No leaks/i)).toBeTruthy()
  })
})
