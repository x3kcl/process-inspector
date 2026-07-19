// The Incident Ledger's list-page bucketing (INCIDENT-LEDGER.md §8): REGRESSED (alarm) →
// OPEN (active) → QUIET → RESOLVED (collapsed), current algo generation by default, with an
// "Archived generations (N)" toggle for the rest. Rung-1 pure — no React, no IO, mirroring
// triage/ackState.ts's splitAcknowledged idiom. `state`/`quiet`/`currentGeneration` are all
// OPTIONAL on the wire (generated DTO) — every branch fails toward VISIBLE, never toward
// silently dropping an incident a caller can't otherwise find (honesty over tidiness).
import type { IncidentSummary } from '../api/model'

export interface IncidentSections {
  regressed: IncidentSummary[]
  open: IncidentSummary[]
  quiet: IncidentSummary[]
  resolved: IncidentSummary[]
  /** Older ALGO_VERSION generations — collapsed behind the page's own toggle, never hidden. */
  archived: IncidentSummary[]
}

/** Newest-`lastSeen`-first — the same "most recently active first" ordering as the ledger's
 *  own DB index (`idx_incident_state (state, last_seen DESC)`). */
function byLastSeenDesc(a: IncidentSummary, b: IncidentSummary): number {
  return (b.lastSeen ?? '').localeCompare(a.lastSeen ?? '')
}

export function bucketIncidents(incidents: IncidentSummary[]): IncidentSections {
  const regressed: IncidentSummary[] = []
  const open: IncidentSummary[] = []
  const quiet: IncidentSummary[] = []
  const resolved: IncidentSummary[] = []
  const archived: IncidentSummary[] = []

  for (const incident of incidents) {
    // A missing flag must never silently HIDE an incident behind the archived toggle —
    // absent ⇒ treat as the current generation (visible by default).
    const isCurrentGeneration = incident.currentGeneration ?? true
    if (!isCurrentGeneration) {
      archived.push(incident)
      continue
    }
    if (incident.state === 'REGRESSED') {
      regressed.push(incident)
    } else if (incident.state === 'RESOLVED') {
      resolved.push(incident)
    } else if (incident.quiet === true) {
      quiet.push(incident)
    } else {
      // Covers state === 'OPEN' AND any unrecognized/missing state — fail toward the most
      // visible (non-collapsed, non-archived) section rather than dropping the row.
      open.push(incident)
    }
  }

  regressed.sort(byLastSeenDesc)
  open.sort(byLastSeenDesc)
  quiet.sort(byLastSeenDesc)
  resolved.sort(byLastSeenDesc)
  archived.sort(byLastSeenDesc)

  return { regressed, open, quiet, resolved, archived }
}
