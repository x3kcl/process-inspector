// R-BAU-01 pure presentation logic for the acknowledge overlay: how the landing splits
// active vs collapsed groups, the resurface badge wording (register-normative for the
// growth case), and the group-level RBAC gate hint. Rung-1 testable — no React, no IO.
import type { MeDto } from '../api/me'
import { roleOn } from '../api/me'
import { roleAtLeast } from '../actions/catalog'
import type { ErrorGroup, ErrorGroupAcknowledgement } from '../api/model'

/** Collapsed = acknowledged AND not auto-resurfaced. Resurfaced groups rejoin the active list. */
export function isCollapsedAck(group: ErrorGroup): boolean {
  // != null: the DTO is NON_NULL-serialized (absent, not null), but stay tolerant of a
  // null on the wire — a null.resurfaced read here would crash the whole landing.
  return group.acknowledgement != null && group.acknowledgement.resurfaced !== true
}

/** Splits the landing's groups: active cards first, the "Acknowledged (N)" tail separate. */
export function splitAcknowledged(groups: ErrorGroup[]): {
  active: ErrorGroup[]
  acknowledged: ErrorGroup[]
} {
  const active: ErrorGroup[] = []
  const acknowledged: ErrorGroup[] = []
  for (const group of groups) {
    ;(isCollapsedAck(group) ? acknowledged : active).push(group)
  }
  return { active, acknowledged }
}

/**
 * The resurface badge text (R-BAU-01 wording for growth: "GREW SINCE ACK: +n").
 * Null while the group is collapsed or unacknowledged.
 */
export function resurfaceBadge(ack: ErrorGroupAcknowledgement | null | undefined): string | null {
  if (ack == null || ack.resurfaced !== true) return null
  const grownBy = ack.grownBy ?? 0
  switch (ack.resurfaceReason) {
    case 'grew':
      return `GREW SINCE ACK: +${String(grownBy)}`
    case 'new-version':
      // The more specific signal labels the badge; the delta stays visible when present.
      return grownBy > 0 ? `NEW VERSION SINCE ACK · +${String(grownBy)}` : 'NEW VERSION SINCE ACK'
    case 'expired':
      return 'ACK EXPIRED'
    default:
      return 'RESURFACED SINCE ACK'
  }
}

export interface AckGate {
  enabled: boolean
  /** Named gate for the greyed-never-hidden hint (SPEC §10a disabled-with-reason). */
  reason?: string
}

/**
 * Group-level gate mirror of the BFF door: OPERATOR on EVERY engine the group is failing
 * on (the server re-checks — this only shapes the affordance). Unknown role hints fail
 * toward disabled; the server stays the real gate.
 */
export function ackGate(me: MeDto | undefined, engineIds: string[]): AckGate {
  if (engineIds.length === 0) return { enabled: false, reason: 'Group has no engine scope' }
  for (const engineId of [...engineIds].sort()) {
    const role = roleOn(me, engineId)
    if (role === null || !roleAtLeast(role, 'OPERATOR')) {
      return {
        enabled: false,
        reason: `Requires OPERATOR on every engine in this group — missing on ${engineId}`,
      }
    }
  }
  return { enabled: true }
}

/** The most severe environment among the group's engines — for the modal's honesty band. */
export function worstEnvironment(environments: (string | undefined)[]): string | undefined {
  const lower = environments.map((environment) => environment?.toLowerCase())
  if (lower.includes('prod')) return 'prod'
  if (lower.includes('test')) return 'test'
  return lower.find((environment) => environment !== undefined)
}
