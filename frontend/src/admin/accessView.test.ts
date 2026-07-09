import { describe, expect, it } from 'vitest'
import type { FleetView, LadderView, Outcome } from './accessAdmin'
import { distinctGroups, outcomeNotice } from './accessView'

describe('distinctGroups', () => {
  it('collects + sorts distinct groups across ladder and fleet, dropping blanks', () => {
    const ladder: LadderView[] = [
      { group: 'orders-l1', role: 'RESPONDER', engineId: 'e', tenantId: 't', source: 'ui' },
      { group: 'orders-l1', role: 'OPERATOR', engineId: 'e', tenantId: 't', source: 'ui' },
      { group: '', role: 'VIEWER', engineId: '*', tenantId: '*', source: 'ui' },
    ]
    const fleet: FleetView[] = [{ group: 'access-admins', grant: 'ACCESS_ADMIN', source: 'ui' }]
    expect(distinctGroups(ladder, fleet)).toEqual(['access-admins', 'orders-l1'])
  })
})

describe('outcomeNotice', () => {
  it('reports an applied single-actor change', () => {
    const o: Outcome = { status: 'applied', summary: 'grant RESPONDER on e/t to group x' }
    expect(outcomeNotice(o)).toContain('Applied: grant RESPONDER')
  })

  it('names the eligible approvers for a proposed (four-eyes) change', () => {
    const o: Outcome = {
      status: 'proposed',
      summary: 'grant fleet ACCESS_ADMIN to group x',
      eligibleApproverGroups: ['access-admins', 'break-glass-approvers'],
    }
    const n = outcomeNotice(o)
    expect(n).toContain('second independent ACCESS_ADMIN')
    expect(n).toContain('access-admins, break-glass-approvers')
  })

  it('points at the file-pin recovery when NO eligible approver exists (never a rotting prompt)', () => {
    const o: Outcome = { status: 'proposed', summary: 's', eligibleApproverGroups: [] }
    expect(outcomeNotice(o)).toContain('file-pin')
    expect(outcomeNotice(o)).toContain('RUNBOOK')
  })
})
