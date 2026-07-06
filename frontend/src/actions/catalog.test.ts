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

describe('actionGate — v1.1 flow surgery gates', () => {
  it('greys change-state when the engine lacks the capability, naming the probe', () => {
    const gate = actionGate({ meta: VERBS.changeState, roleHint: 'ADMIN', capability: false })
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('capability')
  })

  it('stays open when the verb is not capability-gated (undefined probe)', () => {
    expect(actionGate({ meta: VERBS.changeState, roleHint: 'ADMIN' }).enabled).toBe(true)
  })

  it('greys change-state on a SUSPENDED instance and points at activate', () => {
    const gate = actionGate({
      meta: VERBS.changeState,
      roleHint: 'ADMIN',
      instanceSuspended: true,
    })
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('suspended')
  })

  it('escalates change-state to ADMIN on prod, but stays OPERATOR elsewhere', () => {
    const onProd = actionGate({
      meta: VERBS.changeState,
      roleHint: 'OPERATOR',
      environment: 'prod',
    })
    expect(onProd.enabled).toBe(false)
    expect(onProd.reason).toContain('ADMIN')
    expect(onProd.reason).toContain('production')
    expect(
      actionGate({ meta: VERBS.changeState, roleHint: 'OPERATOR', environment: 'test' }).enabled,
    ).toBe(true)
    expect(
      actionGate({ meta: VERBS.changeState, roleHint: 'ADMIN', environment: 'prod' }).enabled,
    ).toBe(true)
  })

  it('inverts the ended gate for restart-as-new: running greys, ended enables', () => {
    const running = actionGate({ meta: VERBS.restartAsNew, roleHint: 'OPERATOR' })
    expect(running.enabled).toBe(false)
    expect(running.reason).toContain('ended')
    expect(
      actionGate({ meta: VERBS.restartAsNew, roleHint: 'OPERATOR', instanceEnded: true }).enabled,
    ).toBe(true)
  })

  it('restart-as-new keeps the OPERATOR floor even on prod (no escalation)', () => {
    expect(
      actionGate({
        meta: VERBS.restartAsNew,
        roleHint: 'OPERATOR',
        instanceEnded: true,
        environment: 'prod',
      }).enabled,
    ).toBe(true)
    const viewer = actionGate({
      meta: VERBS.restartAsNew,
      roleHint: 'VIEWER',
      instanceEnded: true,
    })
    expect(viewer.enabled).toBe(false)
    expect(viewer.reason).toContain('OPERATOR')
  })

  it('read-only engine still greys surgery verbs first', () => {
    const gate = actionGate({
      meta: VERBS.restartAsNew,
      roleHint: 'ADMIN',
      instanceEnded: true,
      engineMode: 'READ_ONLY',
    })
    expect(gate.enabled).toBe(false)
    expect(gate.reason).toContain('read-only')
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
