import { describe, expect, it } from 'vitest'
import type { EngineWriteOutcome } from './adminEngines'
import { engineOutcomeNotice } from './adminEnginesView'

describe('engineOutcomeNotice', () => {
  it('reports an applied single-actor change', () => {
    const o: EngineWriteOutcome = { status: 'applied', summary: "enable engine 'e' (read-write)" }
    expect(engineOutcomeNotice(o)).toContain("Applied: enable engine 'e'")
  })

  it('names the eligible approvers for a proposed (four-eyes) change', () => {
    const o: EngineWriteOutcome = {
      status: 'proposed',
      summary: "enable read-write on engine 'e'",
      eligibleApproverGroups: ['registry-admins', 'ops-oncall'],
    }
    const n = engineOutcomeNotice(o)
    expect(n).toContain('second independent REGISTRY_ADMIN')
    expect(n).toContain('registry-admins, ops-oncall')
  })

  it('points at the recovery move when NO eligible approver exists (never a rotting prompt)', () => {
    const o: EngineWriteOutcome = { status: 'proposed', summary: 's', eligibleApproverGroups: [] }
    expect(engineOutcomeNotice(o)).toContain('add a second independent REGISTRY_ADMIN')
  })
})
