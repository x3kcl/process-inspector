// The Incident Ledger's list-page bucketing (INCIDENT-LEDGER.md §8): REGRESSED (alarm) →
// OPEN (active) → QUIET → RESOLVED (collapsed), current algo generation by default, with an
// "Archived generations (N)" toggle for the rest. Rung-1 pure — no React, no IO, mirroring
// triage/ackState.ts's splitAcknowledged idiom. `state`/`quiet`/`currentGeneration` are all
// OPTIONAL on the wire (generated DTO) — every branch fails toward VISIBLE, never toward
// silently dropping an incident a caller can't otherwise find (honesty over tidiness).
import type { IncidentSummary } from '../api/model'
import { compareIncidentOrder } from './attention'

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

// Risk-ranked ordering (RETRYING-RISK-LANE.md §10, issue #352 scope item 2): "the risk-ranked
// RETRYING view ordered SELF_HEAL_UNLIKELY -> MIXED -> INSUFFICIENT_HISTORY -> LIKELY". The
// design sketch names "Stage 0 / search results" as the target surface; the #351 backend as
// actually built only ever attaches `selfHeal` to IncidentSummary (never to Stage 0's own
// recomputed ErrorGroup triage cards), so the ranked view lands here, on the Incident Ledger's
// own not-yet-resolved sections -- the closest surface the shipped DTO can drive without a
// hand-written parallel fetch. REGRESSED/OPEN/QUIET are still meaningfully "which of these
// needs a look"; RESOLVED/archived stay `byLastSeenDesc` (historical record, no self-heal
// urgency left to rank).
//
// #354 reconciliation (ALARM-COST-MODEL.md §3.1/§11 — see incidents/attention.ts's doc comment
// for the full reasoning): #353 later attached a SERVER-computed attention score to these same
// rows, and that score already folds in the self-heal signal (§11's lane→p_heal map) that
// `compareSelfHealRisk` alone ranks by. Sorting by `compareSelfHealRisk` on top of a
// server-attention-ordered list would double-count self-heal and override the server's order —
// so the three not-yet-resolved sections below now sort by `compareIncidentOrder`, which ranks
// by the server `attention` score when present and falls back to EXACTLY this original
// `compareSelfHealRisk` ordering when it is absent (the flag-off, expected-today case, §7/§11).

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

  regressed.sort(compareIncidentOrder)
  open.sort(compareIncidentOrder)
  quiet.sort(compareIncidentOrder)
  resolved.sort(byLastSeenDesc)
  archived.sort(byLastSeenDesc)

  return { regressed, open, quiet, resolved, archived }
}
