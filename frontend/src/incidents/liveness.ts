// R-NFR-08 honesty for the incident detail's count strip (#270).
//
// `incident.lastTotal` is the LEDGER's last observed sample. The detail also asks for a live
// Stage-0 join (`detail.live`), which the BFF omits when the class is not failing right now,
// when its generation is retired, or when its live slice falls outside the caller's scope
// (IncidentDetail DTO contract).
//
// Labelling `lastTotal` "Live total" unconditionally was a quiet lie in exactly the case that
// matters: the owner hit an incident reading "Live total 8 instances" whose "Search these
// instances" drill returned 0. The v2 signature fix removes the #270 cause of that particular
// mismatch, but the honest-label problem is independent and outlives it — a RESOLVED or quiet
// incident legitimately has a stale count and an empty drill. Say which number this is, and
// warn before the click rather than letting an empty result read as a broken tool.
export interface CountStripLabel {
  /** Leading label for the count. */
  label: 'Live total' | 'Last observed'
  /** True when the drill may legitimately return nothing, so the UI should say so up front. */
  drillMayBeEmpty: boolean
}

export function countStripLabel(hasLiveJoin: boolean): CountStripLabel {
  return hasLiveJoin
    ? { label: 'Live total', drillMayBeEmpty: false }
    : { label: 'Last observed', drillMayBeEmpty: true }
}
