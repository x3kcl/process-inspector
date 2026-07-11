// R-AUD-05 (usability W3-1): the plain-text shift report — UNKNOWN outcomes grouped FIRST
// under "NEEDS VERIFICATION", then the rest chronologically. Machine-facing text carries
// UTC ISO timestamps only (R-UXQ-07: copies stay raw UTC, never the display zone).
import { describe, expect, it } from 'vitest'
import type { AuditEntryDto } from '../api/model'
import { buildShiftReport, shiftStartIso, SHIFT_HOURS } from './shiftReport'

function entry(partial: Partial<AuditEntryDto>): AuditEntryDto {
  return {
    actor: 'k.meier',
    action: 'retry-job',
    engineId: 'engine-a',
    instanceId: 'pi-1',
    outcome: 'ok',
    ...partial,
  }
}

describe('shiftStartIso (R-AUD-05 default shift window)', () => {
  it('is exactly SHIFT_HOURS ago, in UTC ISO', () => {
    const now = Date.parse('2026-07-11T06:00:00.000Z')
    expect(SHIFT_HOURS).toBe(8)
    expect(shiftStartIso(now)).toBe('2026-07-10T22:00:00.000Z')
  })
})

describe('buildShiftReport (R-AUD-05 register wording)', () => {
  const rows: AuditEntryDto[] = [
    entry({ action: 'suspend', ts: '2026-07-11T03:00:00Z', outcome: 'ok', instanceId: 'pi-3' }),
    entry({
      action: 'retry-job',
      ts: '2026-07-11T05:00:00Z',
      outcome: 'unknown',
      instanceId: 'pi-9',
      reason: 'stuck after hotfix',
    }),
    entry({
      action: 'edit-variable',
      ts: '2026-07-11T01:00:00Z',
      outcome: 'failed',
      httpStatus: 409,
      instanceId: 'pi-2',
      ticketId: 'OPS-7',
    }),
    entry({
      action: 'terminate',
      ts: '2026-07-11T04:00:00Z',
      outcome: 'PENDING',
      instanceId: 'pi-4',
    }),
  ]

  const report = buildShiftReport(rows, {
    actor: 'k.meier',
    sinceIso: '2026-07-10T22:00:00Z',
    nowMs: Date.parse('2026-07-11T06:00:00.000Z'),
  })

  it('groups UNKNOWN (and still-PENDING) outcomes FIRST under NEEDS VERIFICATION', () => {
    const verificationAt = report.indexOf('NEEDS VERIFICATION')
    const restAt = report.indexOf('Other actions (chronological)')
    expect(verificationAt).toBeGreaterThan(-1)
    expect(restAt).toBeGreaterThan(verificationAt)
    const verificationBlock = report.slice(verificationAt, restAt)
    expect(verificationBlock).toContain('pi-9')
    expect(verificationBlock).toContain('pi-4')
    expect(verificationBlock).not.toContain('pi-2')
    expect(verificationBlock).not.toContain('pi-3')
  })

  it('lists the rest chronologically (ascending ts)', () => {
    expect(report.indexOf('pi-2')).toBeLessThan(report.indexOf('pi-3'))
  })

  it('uses UTC ISO timestamps and names the shift window', () => {
    expect(report).toContain('2026-07-11T05:00:00.000Z')
    expect(report).toContain('2026-07-10T22:00:00.000Z → 2026-07-11T06:00:00.000Z')
    expect(report).toContain('k.meier')
  })

  it('carries reason, ticket and a verdict word per line — never raw "ok · null"', () => {
    expect(report).toContain('stuck after hotfix')
    expect(report).toContain('OPS-7')
    expect(report).toContain('done')
    expect(report).not.toContain('ok · null')
  })

  it('renders an explicit (none) when nothing needs verification', () => {
    const clean = buildShiftReport([rows[0]], {
      actor: 'k.meier',
      sinceIso: '2026-07-10T22:00:00Z',
      nowMs: Date.parse('2026-07-11T06:00:00.000Z'),
    })
    expect(clean).toContain('NEEDS VERIFICATION')
    expect(clean).toContain('(none)')
  })
})
