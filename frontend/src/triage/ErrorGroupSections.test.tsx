// @vitest-environment jsdom
// R-BAU-01 (usability W3-2): acknowledged groups collapse into a labeled
// "Acknowledged (N)" <details> — visible-but-folded, NEVER hidden — while resurfaced
// groups rejoin the active list wearing the "GREW SINCE ACK: +n" badge (baseline run M7
// task 5 expected the mute affordance and warned Suspend is the workaround users reach
// for; this section is the honest alternative's landing shape).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import type { ErrorGroup } from '../api/model'
import { ErrorGroupSections } from './ErrorGroupSections'

afterEach(cleanup)

function group(hash: string, overrides: Partial<ErrorGroup> = {}): ErrorGroup {
  return {
    signatureHash: hash,
    algoVersion: 1,
    exceptionClass: `com.acme.Boom${hash}`,
    normalizedMessage: `boom ${hash}`,
    total: 10,
    deadLetterCount: 10,
    retryingCount: 0,
    countsByEngine: {},
    ...overrides,
  }
}

function renderSections(groups: ErrorGroup[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, enabled: false } } })
  render(
    <QueryClientProvider client={client}>
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

const quietAck = {
  acknowledgedBy: 'op1',
  acknowledgedAt: '2026-07-10T09:00:00Z',
  reason: 'known outage window',
  acknowledgedTotal: 10,
  resurfaced: false,
  grownBy: 0,
}

describe('ErrorGroupSections (R-BAU-01)', () => {
  it('collapses acknowledged groups into a labeled, counted, never-hidden section', () => {
    renderSections([
      group('g1'),
      group('g2', { acknowledgement: quietAck }),
      group('g3', { acknowledgement: quietAck }),
    ])

    // The section exists, carries the count, and its content is in the DOM (folded, not gone).
    const summary = screen.getByText('Acknowledged')
    expect(summary.textContent).toMatch(/\(2\)/)
    expect(screen.getByText('boom g2')).toBeTruthy()
    expect(screen.getByText('boom g3')).toBeTruthy()
    // Collapsed by default — the details element is not open.
    expect(summary.closest('details')?.open).toBe(false)
    // The ack provenance renders on the collapsed card.
    expect(screen.getAllByText(/Acknowledged by/)[0].textContent).toContain('op1')
  })

  it('renders no acknowledged section when nothing is acknowledged', () => {
    renderSections([group('g1')])
    expect(screen.queryByText('Acknowledged')).toBeNull()
  })

  it('a resurfaced group stays in the ACTIVE list and wears the growth badge', () => {
    renderSections([
      group('g1', {
        acknowledgement: { ...quietAck, resurfaced: true, resurfaceReason: 'grew', grownBy: 45 },
      }),
    ])
    expect(screen.queryByText('Acknowledged')).toBeNull() // no collapsed section
    expect(screen.getByText('GREW SINCE ACK: +45')).toBeTruthy()
  })

  it('says so honestly when every group is acknowledged (never a fake "all clean")', () => {
    renderSections([group('g1', { acknowledgement: quietAck })])
    expect(screen.getByText(/Every failure group is acknowledged/).textContent).toContain(
      'auto-resurface',
    )
  })
})
