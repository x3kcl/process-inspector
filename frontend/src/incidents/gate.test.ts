import { describe, expect, it } from 'vitest'
import type { MeDto } from '../api/me'
import { incidentGate } from './gate'

function me(role: string | undefined): MeDto {
  return { role }
}

describe('incidentGate', () => {
  it('enables at exactly the OPERATOR floor and above', () => {
    expect(incidentGate(me('OPERATOR')).enabled).toBe(true)
    expect(incidentGate(me('ADMIN')).enabled).toBe(true)
  })

  it('disables below the floor, naming the actual role in the reason', () => {
    const gate = incidentGate(me('VIEWER'))
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toBe('Requires OPERATOR — you are VIEWER')

    expect(incidentGate(me('RESPONDER')).enabled).toBe(false)
  })

  it('stays optimistic (never greys) on an unresolved role — the BFF 403 is the real gate', () => {
    expect(incidentGate(undefined).enabled).toBe(true)
    expect(incidentGate(me(undefined)).enabled).toBe(true)
    expect(incidentGate(me('not-a-real-role')).enabled).toBe(true)
  })

  it('honors a custom floor', () => {
    expect(incidentGate(me('RESPONDER'), 'RESPONDER').enabled).toBe(true)
    expect(incidentGate(me('VIEWER'), 'RESPONDER').enabled).toBe(false)
  })
})
