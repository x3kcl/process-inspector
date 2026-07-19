// Resolve/reopen RBAC gate (INCIDENT-LEDGER.md §6). Unlike triage's ackGate (per-engine
// OPERATOR — mirrors the ack door's own per-engine re-check), IncidentController's javadoc is
// explicit that resolve/reopen are "config-events... needs plain OPERATOR fleet-wide": the
// door is `@rbac.atLeast(authentication, 'OPERATOR')`, a single GLOBAL role check, because the
// verb has no engine target of its own (the ledger row is fleet-wide identity; only the
// opt-in `alsoAcknowledge` composition re-does the per-engine ack check, server-side). This
// mirrors that exact door, not the per-engine one — greyed-never-hidden, same as every other
// RBAC mirror in the app (unknown role stays optimistic; the BFF 403 is the real gate).
import { roleAtLeast, type RoleHint } from '../actions/catalog'
import type { MeDto } from '../api/me'

export interface IncidentGate {
  enabled: boolean
  reason?: string
}

const ROLES: readonly RoleHint[] = ['VIEWER', 'RESPONDER', 'OPERATOR', 'ADMIN']

function asRole(value: string | undefined): RoleHint | null {
  return value !== undefined && (ROLES as readonly string[]).includes(value)
    ? (value as RoleHint)
    : null
}

export function incidentGate(me: MeDto | undefined, floor: RoleHint = 'OPERATOR'): IncidentGate {
  const role = asRole(me?.role)
  // Unknown role (me not loaded yet, or an OIDC session whose fleet role never resolved):
  // stay optimistic like every other RBAC mirror — the BFF 403 answers for real.
  if (role === null) return { enabled: true }
  if (!roleAtLeast(role, floor)) {
    return { enabled: false, reason: `Requires ${floor} — you are ${role}` }
  }
  return { enabled: true }
}
