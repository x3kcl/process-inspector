import { describe, expect, it } from 'vitest'
import {
  VERBS,
  actionGate,
  needsTwoStepConfirm,
  reasonRule,
  reasonValid,
  roleAtLeast,
} from './catalog'

describe('actionGate — greyed never hidden, with the gate named', () => {
  it('greys everything on a read-only engine', () => {
    const gate = actionGate({ meta: VERBS.retryJob, roleHint: 'ADMIN', engineMode: 'READ_ONLY' })
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('read-only')
  })

  it('greys runtime verbs on an ended instance', () => {
    const gate = actionGate({ meta: VERBS.suspend, roleHint: 'ADMIN', instanceEnded: true })
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('ended')
  })

  it('greys below the role floor and names the required role', () => {
    const gate = actionGate({ meta: VERBS.terminate, roleHint: 'OPERATOR' })
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('ADMIN')
  })

  it('stays optimistic when the role is unknown — the BFF 403 is the real gate', () => {
    expect(actionGate({ meta: VERBS.terminate, roleHint: null }).enabled).toBe(true)
  })

  it('lets a RESPONDER run tier-0 verbs', () => {
    expect(actionGate({ meta: VERBS.retryJob, roleHint: 'RESPONDER' }).enabled).toBe(true)
  })
})

describe('reasonRule — the SPEC §6 reason ladder', () => {
  it('tier 0 never requires a reason', () => {
    expect(reasonRule(0, 'prod').required).toBe(false)
  })
  it('tier 1 requires a reason on prod only', () => {
    expect(reasonRule(1, 'prod').required).toBe(true)
    expect(reasonRule(1, 'test').required).toBe(false)
    expect(reasonRule(1, undefined).required).toBe(false)
  })
  it('tier 3 always requires a reason', () => {
    expect(reasonRule(3, 'dev').required).toBe(true)
  })
  it('a present reason must always clear 10 chars, required or not', () => {
    const optional = reasonRule(0, 'test')
    expect(reasonValid('', optional)).toBe(true)
    expect(reasonValid('too short', optional)).toBe(false)
    expect(reasonValid('long enough now', optional)).toBe(true)
    const required = reasonRule(3, 'prod')
    expect(reasonValid('', required)).toBe(false)
    expect(reasonValid('   padded    ', required)).toBe(false)
  })
})

describe('needsTwoStepConfirm — the §5.0 prod friction floor', () => {
  it('applies to external-side-effect verbs on prod only', () => {
    expect(needsTwoStepConfirm(VERBS.triggerTimer, 'prod')).toBe(true)
    expect(needsTwoStepConfirm(VERBS.triggerTimer, 'test')).toBe(false)
  })
  it('never applies to queue-state-only verbs', () => {
    expect(needsTwoStepConfirm(VERBS.suspend, 'prod')).toBe(false)
    expect(needsTwoStepConfirm(VERBS.activate, 'prod')).toBe(false)
  })
})

describe('roleAtLeast', () => {
  it('is a layered ladder — higher covers lower', () => {
    expect(roleAtLeast('ADMIN', 'RESPONDER')).toBe(true)
    expect(roleAtLeast('VIEWER', 'RESPONDER')).toBe(false)
    expect(roleAtLeast('OPERATOR', 'OPERATOR')).toBe(true)
  })
})
