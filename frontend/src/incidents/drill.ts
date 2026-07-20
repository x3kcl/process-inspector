// The incident detail's "Search these instances" deep link (INCIDENT-LEDGER.md §8) — the
// SAME prefiltered /search URL the triage error-group card's group-total link uses
// (triage/drill.ts's groupDrillParams: every engine the class was observed on + FAILED/
// RETRYING + the signature param). Reused verbatim rather than re-derived: an
// IncidentSummary/IncidentDetail's `incident` row carries the identical two fields
// (signatureHash, countsByEngine) groupDrillParams actually reads.
import { groupDrillParams } from '../triage/drill'

export interface IncidentDrillScope {
  signatureHash?: string
  // #279: the incident row carries algoVersion (IncidentSummary/IncidentDetail) — thread it through
  // so the drill link stamps the generation it was built under, same as the triage card's group link.
  algoVersion?: number
  countsByEngine?: Record<string, Record<string, number>>
}

export function incidentSearchParams(incident: IncidentDrillScope): string {
  return groupDrillParams({
    signatureHash: incident.signatureHash,
    algoVersion: incident.algoVersion,
    countsByEngine: incident.countsByEngine,
  })
}
