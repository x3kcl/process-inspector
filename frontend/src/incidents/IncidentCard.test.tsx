// @vitest-environment jsdom
// The Incident Ledger card wires in the shared AttentionBadge (ALARM-COST-MODEL.md §4.3/§11,
// issue #354) — the badge itself is fully covered by components/AttentionBadge.test.tsx; this
// only proves the card actually renders it off `incident.attention`, absent or present.
import { MemoryRouter } from 'react-router'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { IncidentSummary } from '../api/model'
import { IncidentCard } from './IncidentCard'

afterEach(cleanup)

const incident: IncidentSummary = {
  id: 1,
  signatureHash: 'sig-1',
  exceptionClass: 'java.net.UnknownHostException',
  normalizedMessage: 'acme api outage',
  lastTotal: 8,
  firstSeen: '2026-07-19T00:00:00Z',
  lastSeen: '2026-08-04T00:00:00Z',
  countsByEngine: {},
}

function renderCard(i: IncidentSummary) {
  render(
    <MemoryRouter>
      <IncidentCard incident={i} enginesById={new Map()} variant="open" />
    </MemoryRouter>,
  )
}

describe('IncidentCard attention badge wiring (#354)', () => {
  it('renders no attention badge when the incident carries no server score (the expected-today case)', () => {
    renderCard(incident)
    expect(screen.queryByText('ranked by attention')).toBeNull()
  })

  it('renders the attention badge with the server rationale as VISIBLE text when the score is present (#374)', () => {
    renderCard({
      ...incident,
      attention: { score: 4.2, rationale: '8 failing · last seen just now' },
    })
    expect(screen.getByText('ranked by attention')).not.toBeNull()
    expect(screen.getByText('8 failing · last seen just now')).not.toBeNull()
  })
})
