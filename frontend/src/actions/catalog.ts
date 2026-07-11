// The single-target verb catalog the UI offers in M4, mirroring the backend's ActionVerb
// enum (tier + role floor) and SPEC §5.0's spec'd — never improvised — plain-language
// labels and reversibility badges. The BFF is the real gate; this module only decides
// what to grey (never hide) and which guard surface a verb gets.
import { isReadOnlyMode } from '../lib/enginePolicy'

export type RoleHint = 'VIEWER' | 'RESPONDER' | 'OPERATOR' | 'ADMIN'

const ROLE_RANK: Record<RoleHint, number> = { VIEWER: 0, RESPONDER: 1, OPERATOR: 2, ADMIN: 3 }

export function roleAtLeast(role: RoleHint, floor: RoleHint): boolean {
  return ROLE_RANK[role] >= ROLE_RANK[floor]
}

export type Reversibility = 'REVERSIBLE' | 'RECOVERABLE' | 'IRREVERSIBLE'

export interface VerbMeta {
  verb: string
  /** Menu label, e.g. "Retry job". */
  label: string
  /** SPEC §5.0 plain-language secondary label. */
  plain: string
  tier: 0 | 1 | 3
  roleFloor: RoleHint
  reversibility: Reversibility
  /** Named compensating verb (REVERSIBLE) or rescue path (RECOVERABLE). */
  reversibilityNote: string
  /** §5.0: verbs firing irreversible EXTERNAL side effects get the two-step inline
   *  confirm on prod; queue-state-only verbs stay single-click. */
  externalSideEffects: boolean
  /** v1.1 flow surgery: the verb targets ENDED instances only (restart-as-new), so the
   *  usual "instance has ended" gate inverts — running instances grey it instead. */
  requiresEnded?: boolean
  /** v1.1: role floor escalation on PROD engines (change-state is OPERATOR everywhere
   *  but ADMIN on prod — mirrors the BFF's scoped check, which stays the real gate). */
  prodRoleFloor?: RoleHint
}

export const VERBS = {
  retryJob: {
    verb: 'retry-job',
    label: 'Retry job',
    plain: 'run the failed step again',
    tier: 0,
    roleFloor: 'RESPONDER',
    reversibility: 'RECOVERABLE',
    reversibilityNote: 'the queue move is reversible; the side effects of the executed job are not',
    externalSideEffects: true,
  },
  triggerTimer: {
    verb: 'trigger-timer',
    label: 'Fire timer now',
    plain: 'stop waiting, continue immediately',
    tier: 0,
    roleFloor: 'RESPONDER',
    reversibility: 'IRREVERSIBLE',
    reversibilityNote: 'the timer fires and the case moves on — there is no way back',
    externalSideEffects: true,
  },
  suspend: {
    verb: 'suspend',
    label: 'Suspend',
    plain: 'pause this case',
    tier: 0,
    roleFloor: 'RESPONDER',
    reversibility: 'REVERSIBLE',
    reversibilityNote: 'compensating verb: activate',
    externalSideEffects: false,
  },
  activate: {
    verb: 'activate',
    label: 'Activate',
    plain: 'resume this case',
    tier: 0,
    roleFloor: 'RESPONDER',
    reversibility: 'REVERSIBLE',
    reversibilityNote: 'compensating verb: suspend',
    externalSideEffects: false,
  },
  editVariable: {
    verb: 'edit-variable',
    label: 'Edit variable',
    plain: 'change a data value on this case',
    tier: 1,
    roleFloor: 'OPERATOR',
    reversibility: 'RECOVERABLE',
    reversibilityNote: 'the old value is kept in the audit trail',
    externalSideEffects: false,
  },
  reassignTask: {
    verb: 'reassign-task',
    label: 'Reassign',
    plain: 'hand this task to a specific person',
    tier: 1,
    roleFloor: 'OPERATOR',
    reversibility: 'REVERSIBLE',
    reversibilityNote: 'compensating verb: reassign again, or return to team',
    externalSideEffects: false,
  },
  unassignTask: {
    verb: 'unassign-task',
    label: 'Return to team',
    plain: 'clear the assignee so the candidate group can pick it up',
    tier: 1,
    roleFloor: 'OPERATOR',
    reversibility: 'REVERSIBLE',
    reversibilityNote: 'compensating verb: reassign to a person',
    externalSideEffects: false,
  },
  terminate: {
    verb: 'terminate-delete',
    label: 'Terminate / delete',
    plain: 'kill this case permanently',
    tier: 3,
    roleFloor: 'ADMIN',
    reversibility: 'IRREVERSIBLE',
    reversibilityNote: 'runtime state is destroyed — there is no undo',
    externalSideEffects: true,
  },
  deleteDeadletter: {
    verb: 'delete-deadletter',
    label: 'Delete dead-letter job',
    plain: 'discard the failed step (the case can never continue past it on its own)',
    tier: 3,
    roleFloor: 'ADMIN',
    reversibility: 'RECOVERABLE',
    reversibilityNote: 'only rescue afterwards: change-state (move the token by hand)',
    externalSideEffects: true,
  },
  changeState: {
    verb: 'change-state',
    label: 'Change state / move token',
    plain: 'cancel the token where it is, start it somewhere else',
    tier: 3,
    roleFloor: 'OPERATOR',
    prodRoleFloor: 'ADMIN',
    reversibility: 'RECOVERABLE',
    reversibilityNote:
      'a wrong move can usually be moved back — side effects of activities that start are not undone',
    externalSideEffects: true,
  },
  migrate: {
    verb: 'migrate-instance',
    label: 'Migrate',
    plain: 'move this case to a newer process version',
    tier: 3,
    roleFloor: 'ADMIN',
    reversibility: 'IRREVERSIBLE',
    reversibilityNote:
      'migrating back is a fresh forward migration to the old version, not an undo — work executed under the new version stands',
    externalSideEffects: true,
  },
  restartAsNew: {
    verb: 'restart',
    label: 'Restart as new instance',
    plain: 'start a fresh copy of this ended case from its variables',
    tier: 3,
    roleFloor: 'OPERATOR',
    requiresEnded: true,
    reversibility: 'RECOVERABLE',
    reversibilityNote:
      'the new instance is a normal running case — it can be suspended or terminated',
    externalSideEffects: true,
  },
} as const satisfies Record<string, VerbMeta>

export interface GateInput {
  meta: VerbMeta
  /** Role decoded from the dev sign-in ladder username; null = unknown (OIDC etc.). */
  roleHint: RoleHint | null
  /** Engine registry mode — a read-only engine greys every verb. */
  engineMode?: string
  /** The instance has ended (COMPLETED) — runtime verbs have no target. */
  instanceEnded?: boolean
  /** The instance is SUSPENDED — token moves refuse pre-flight (409 instance-suspended). */
  instanceSuspended?: boolean
  /** R-SAFE-05: the instance is in the protected registry — below the ADMIN floor EVERY verb
   *  is refused (the BFF's `instance-protected` guard). true = protected, undefined/false =
   *  not (or unknown — stay optimistic; the guard is the real gate, mirroring the role case). */
  instanceProtected?: boolean
  /** Capability probe for this verb on the target engine (§6 capability gating);
   *  undefined = the verb is not capability-gated, false = greyed with the reason. */
  capability?: boolean
  /** Engine environment — needed for verbs with a PROD role-floor escalation. */
  environment?: string
}

export interface Gate {
  enabled: boolean
  /** Greyed-never-hidden (SPEC §6): the SHORT string for the visible ActionHint —
   *  `Requires {FLOOR} — you are {ROLE}` (RBAC) or `Blocked: {offender} {why}` (business
   *  logic). Usability round 1, Theme A-copy. */
  reason?: string
  /** The long explanation — moved to the button's title (secondary layer), never the
   *  first thing an operator reads. */
  detail?: string
}

export function actionGate(input: GateInput): Gate {
  if (isReadOnlyMode(input.engineMode)) {
    return {
      enabled: false,
      reason: 'Blocked: engine is read-only',
      detail:
        'this engine is registered read-only — set by the engine owner (policy, not your role)',
    }
  }
  if (input.capability === false) {
    return {
      enabled: false,
      reason: 'Blocked: engine lacks this capability',
      detail: 'this engine version does not support this operation (capability probe)',
    }
  }
  // R-SAFE-05 (SPEC §2): a protected instance refuses EVERY verb below the ADMIN floor. A
  // known-below-ADMIN role greys with the spec'd reason; an unknown role (null) stays
  // optimistic — the BFF's `instance-protected` guard is the real gate. Placed before the
  // ended/suspended/role checks so the protection is the FIRST thing the operator reads.
  if (
    input.instanceProtected === true &&
    input.roleHint !== null &&
    !roleAtLeast(input.roleHint, 'ADMIN')
  ) {
    return {
      enabled: false,
      reason: 'Protected — L3 action required',
      detail: `this instance is protected (R-SAFE-05) — an L3 (ADMIN) operator must act on it (you are ${input.roleHint})`,
    }
  }
  if (input.meta.requiresEnded === true) {
    if (input.instanceEnded !== true) {
      return {
        enabled: false,
        reason: 'Blocked: instance has not ended',
        detail: 'only an ended (completed or terminated) instance can be restarted as new',
      }
    }
  } else if (input.instanceEnded === true) {
    return {
      enabled: false,
      reason: 'Blocked: instance has ended',
      detail: 'the instance has ended — there is no runtime state to act on',
    }
  } else if (input.instanceSuspended === true) {
    return {
      enabled: false,
      reason: 'Blocked: instance is suspended',
      detail: 'the instance is suspended — activate it first, then move the token',
    }
  }
  // Unknown role: stay optimistic (grey nothing) — the BFF 403 is the real gate.
  const prod = input.environment?.toLowerCase() === 'prod'
  const floor =
    prod &&
    input.meta.prodRoleFloor !== undefined &&
    roleAtLeast(input.meta.prodRoleFloor, input.meta.roleFloor)
      ? input.meta.prodRoleFloor
      : input.meta.roleFloor
  if (input.roleHint !== null && !roleAtLeast(input.roleHint, floor)) {
    const onProd = floor !== input.meta.roleFloor
    return {
      enabled: false,
      reason: `Requires ${floor} — you are ${input.roleHint}`,
      detail: `requires the ${floor} role${onProd ? ' on a production engine' : ''}`,
    }
  }
  return { enabled: true }
}

export interface ReasonRule {
  required: boolean
  /** Backend refuses any present reason under 10 chars, required or not. */
  minLength: number
}

/** SPEC §6 reason ladder: tiers ≥2 always required; tier 1 required on prod. */
export function reasonRule(tier: number, environment: string | undefined): ReasonRule {
  const prod = environment?.toLowerCase() === 'prod'
  return { required: tier >= 2 || (tier === 1 && prod), minLength: 10 }
}

export function reasonValid(reason: string, rule: ReasonRule): boolean {
  const trimmed = reason.trim()
  if (trimmed === '') return !rule.required
  return trimmed.length >= rule.minLength
}

/** §5.0 friction floor: two-step inline confirm on prod for external side effects. */
export function needsTwoStepConfirm(meta: VerbMeta, environment: string | undefined): boolean {
  return meta.externalSideEffects && environment?.toLowerCase() === 'prod'
}
