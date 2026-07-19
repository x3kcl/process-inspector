import { describe, expect, it } from 'vitest'
import type { IncidentSummary } from '../api/model'
import { bucketIncidents } from './sections'

function incident(overrides: Partial<IncidentSummary> & { id: number }): IncidentSummary {
  return {
    signatureHash: `sig-${String(overrides.id)}`,
    lastSeen: '2026-07-18T00:00:00Z',
    currentGeneration: true,
    ...overrides,
  }
}

describe('bucketIncidents', () => {
  it('sorts current-generation incidents into REGRESSED / OPEN / QUIET / RESOLVED', () => {
    const regressed = incident({ id: 1, state: 'REGRESSED' })
    const open = incident({ id: 2, state: 'OPEN', quiet: false })
    const quiet = incident({ id: 3, state: 'OPEN', quiet: true })
    const resolved = incident({ id: 4, state: 'RESOLVED' })

    const result = bucketIncidents([regressed, open, quiet, resolved])
    expect(result.regressed).toEqual([regressed])
    expect(result.open).toEqual([open])
    expect(result.quiet).toEqual([quiet])
    expect(result.resolved).toEqual([resolved])
    expect(result.archived).toEqual([])
  })

  it('sends any non-current-generation incident to archived, regardless of state', () => {
    const archivedRegressed = incident({ id: 1, state: 'REGRESSED', currentGeneration: false })
    const archivedResolved = incident({ id: 2, state: 'RESOLVED', currentGeneration: false })

    const result = bucketIncidents([archivedRegressed, archivedResolved])
    expect(result.archived).toEqual(
      [archivedRegressed, archivedResolved].sort((a, b) =>
        (b.lastSeen ?? '').localeCompare(a.lastSeen ?? ''),
      ),
    )
    expect(result.regressed).toEqual([])
    expect(result.resolved).toEqual([])
  })

  it('defaults a missing currentGeneration to current (visible), never archived', () => {
    const noFlag = incident({ id: 1, state: 'OPEN' })
    delete noFlag.currentGeneration
    expect(bucketIncidents([noFlag]).open).toEqual([noFlag])
    expect(bucketIncidents([noFlag]).archived).toEqual([])
  })

  it('defaults an unrecognized or missing state to OPEN, never dropping the row', () => {
    const noState = incident({ id: 1 })
    delete noState.state
    const weirdState = incident({ id: 2, state: 'SOMETHING_NEW' })
    const result = bucketIncidents([noState, weirdState])
    expect(result.open).toContainEqual(noState)
    expect(result.open).toContainEqual(weirdState)
  })

  it('orders each bucket by lastSeen descending', () => {
    const older = incident({ id: 1, state: 'OPEN', lastSeen: '2026-07-01T00:00:00Z' })
    const newer = incident({ id: 2, state: 'OPEN', lastSeen: '2026-07-15T00:00:00Z' })
    expect(bucketIncidents([older, newer]).open).toEqual([newer, older])
  })
})
