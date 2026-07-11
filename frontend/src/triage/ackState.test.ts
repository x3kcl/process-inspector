// R-BAU-01 (usability W3-2): the acknowledge overlay's pure presentation rules — the
// active/collapsed split (resurfaced groups REJOIN the active list), the register-normative
// badge wording ("GREW SINCE ACK: +n"), and the group-level OPERATOR-on-every-engine gate
// mirror of the BFF door.
import { describe, expect, it } from 'vitest'
import type { MeDto } from '../api/me'
import type { ErrorGroup, ErrorGroupAcknowledgement } from '../api/model'
import { ackGate, isCollapsedAck, resurfaceBadge, splitAcknowledged, worstEnvironment } from './ackState'

function group(hash: string, ack?: ErrorGroupAcknowledgement): ErrorGroup {
  return {
    signatureHash: hash,
    algoVersion: 1,
    total: 10,
    deadLetterCount: 10,
    retryingCount: 0,
    countsByEngine: {},
    acknowledgement: ack,
  }
}

const quietAck: ErrorGroupAcknowledgement = {
  acknowledgedBy: 'op1',
  acknowledgedAt: '2026-07-10T09:00:00Z',
  reason: 'known outage window',
  acknowledgedTotal: 10,
  resurfaced: false,
  grownBy: 0,
}

describe('splitAcknowledged (R-BAU-01 collapse rule)', () => {
  it('collapses quiet acknowledgments and keeps resurfaced groups ACTIVE', () => {
    const resurfaced = group('g2', {
      ...quietAck,
      resurfaced: true,
      resurfaceReason: 'grew',
      grownBy: 45,
    })
    const { active, acknowledged } = splitAcknowledged([
      group('g1'),
      resurfaced,
      group('g3', quietAck),
    ])
    expect(active.map((g) => g.signatureHash)).toEqual(['g1', 'g2'])
    expect(acknowledged.map((g) => g.signatureHash)).toEqual(['g3'])
  })

  it('an unacknowledged group is never collapsed', () => {
    expect(isCollapsedAck(group('g1'))).toBe(false)
    expect(isCollapsedAck(group('g1', quietAck))).toBe(true)
  })

  it('tolerates an explicit null on the wire — degrades to unacknowledged, never crashes', () => {
    // External review (W3-2): the DTO is NON_NULL-serialized, but a null here must not
    // slip past an `!== undefined` check and crash the landing on null.resurfaced.
    const nullAck = group('g1', null as unknown as ErrorGroupAcknowledgement)
    expect(isCollapsedAck(nullAck)).toBe(false)
    expect(resurfaceBadge(null)).toBeNull()
    const { active, acknowledged } = splitAcknowledged([nullAck])
    expect(active).toHaveLength(1)
    expect(acknowledged).toHaveLength(0)
  })
})

describe('resurfaceBadge wording', () => {
  it('renders the register-normative growth badge', () => {
    expect(resurfaceBadge({ ...quietAck, resurfaced: true, resurfaceReason: 'grew', grownBy: 45 })).toBe(
      'GREW SINCE ACK: +45',
    )
  })

  it('labels a new failing version, keeping the delta visible when present', () => {
    expect(
      resurfaceBadge({ ...quietAck, resurfaced: true, resurfaceReason: 'new-version', grownBy: 0 }),
    ).toBe('NEW VERSION SINCE ACK')
    expect(
      resurfaceBadge({ ...quietAck, resurfaced: true, resurfaceReason: 'new-version', grownBy: 7 }),
    ).toBe('NEW VERSION SINCE ACK · +7')
  })

  it('labels a passed expiry', () => {
    expect(
      resurfaceBadge({ ...quietAck, resurfaced: true, resurfaceReason: 'expired', grownBy: 0 }),
    ).toBe('ACK EXPIRED')
  })

  it('is null while collapsed or unacknowledged', () => {
    expect(resurfaceBadge(quietAck)).toBeNull()
    expect(resurfaceBadge(undefined)).toBeNull()
  })
})

describe('ackGate (OPERATOR on EVERY group engine)', () => {
  const me = (role: string, engineRoles?: Record<string, string>): MeDto => ({
    role,
    engineRoles,
  })

  it('enables when the operator covers every engine', () => {
    expect(ackGate(me('OPERATOR'), ['e1', 'e2']).enabled).toBe(true)
  })

  it('disables (with the missing engine named) when one engine is uncovered', () => {
    const gate = ackGate(me('OPERATOR', { e2: 'VIEWER' }), ['e1', 'e2'])
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('e2')
  })

  it('disables below the OPERATOR floor and fails toward disabled with no identity', () => {
    expect(ackGate(me('RESPONDER'), ['e1']).enabled).toBe(false)
    expect(ackGate(undefined, ['e1']).enabled).toBe(false)
  })
})

describe('worstEnvironment (modal honesty band)', () => {
  it('prod outranks everything; unknown falls back to the first named', () => {
    expect(worstEnvironment(['dev', 'PROD'])).toBe('prod')
    expect(worstEnvironment(['dev', 'test'])).toBe('test')
    expect(worstEnvironment(['dev'])).toBe('dev')
    expect(worstEnvironment([undefined])).toBeUndefined()
  })
})
