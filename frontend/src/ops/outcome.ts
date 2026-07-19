// Verb-aware outcome labeling (usability round 1, Theme G): a "successful" retry-job item
// has NOT succeeded — the failed step has only been re-queued, and it can fail again on
// its next attempt. Every other outcome keeps its existing label unchanged.
export function outcomeLabel(verb: string | undefined, state: string | undefined): string {
  if (state === 'ok') return verb === 'retry-job' ? 're-queued' : 'done'
  if (state === 'skipped_protected') return 'skipped (protected)'
  if (state === 'not_run') return 'not run'
  return state ?? ''
}

/** The per-item Outcome cell's CSS modifier — re-queued gets its OWN amber class, never
 *  the green "ok" family (it has not succeeded yet). */
export function outcomeClassName(verb: string | undefined, state: string | undefined): string {
  if (state === 'ok' && verb === 'retry-job') return 'outcome-requeued'
  return `outcome-${(state ?? '').replace('_', '-')}`
}

/** Usability round 2, Theme T8: a re-queued item is a DISPATCH state — the engine
 *  accepted the re-queue, nothing has succeeded yet. Dispatch states must never read as
 *  a success verdict, so they carry an inline disclaimer and a verify affordance. */
export function outcomeIsDispatchOnly(
  verb: string | undefined,
  state: string | undefined,
): boolean {
  return state === 'ok' && verb === 'retry-job'
}

/** The Outcome CELL text: dispatch-only outcomes get the not-a-verdict disclaimer inline
 *  (the short {@link outcomeLabel} stays as-is for the tallies line). */
export function outcomeCellLabel(verb: string | undefined, state: string | undefined): string {
  const label = outcomeLabel(verb, state)
  return outcomeIsDispatchOnly(verb, state) ? `${label} — not yet succeeded; verify` : label
}

export interface AuditOutcomeView {
  label: string
  className: string
  title: string
}

/** Audit action strings record bulk fan-outs as "bulk:<verb>" (BulkJobService). */
function auditVerb(action: string | undefined): string | undefined {
  return action !== undefined && action.startsWith('bulk:') ? action.slice('bulk:'.length) : action
}

/**
 * Audit rows reach the UI as raw store literals (SPEC §6 R-SEM-18: PENDING → ok | failed
 * | unknown) plus a NULLABLE httpStatus — rendered verbatim they read "ok · null", raw
 * internals where a verdict belongs (usability round 2, Theme T8 / R-UXQ-05). Map them to
 * the existing outcome vocabulary instead: verb-aware verdict word in the cell (retry
 * honesty included — an ok retry-job is "re-queued", amber), HTTP status appended only
 * where it is evidence (non-success verdicts), raw internals demoted to the tooltip.
 */
export function auditOutcomeView(
  action: string | undefined,
  outcome: string | undefined,
  httpStatus: number | null | undefined,
): AuditOutcomeView {
  const verb = auditVerb(action)
  // A missing outcome is exactly the "unknown" verdict: possibly dispatched, unverified.
  const normalized = outcome === undefined || outcome === '' ? 'unknown' : outcome
  const pending = normalized === 'PENDING'
  const label = pending ? 'pending' : outcomeLabel(verb, normalized)
  const className = pending ? 'outcome-pending' : outcomeClassName(verb, normalized)
  const isSuccessFamily = label === 'done' || label === 're-queued'
  const statusEvidence = typeof httpStatus === 'number' ? `HTTP ${String(httpStatus)}` : ''
  return {
    label: !isSuccessFamily && statusEvidence !== '' ? `${label} · ${statusEvidence}` : label,
    className,
    title: `audit outcome "${normalized}" · ${statusEvidence !== '' ? statusEvidence : 'no HTTP status recorded'}`,
  }
}

/** The minimal job shape {@link talliesLine} needs — satisfied by both the ops drawer's
 *  `BulkJobDto` and the incident detail's `RelatedBulkJob` (the same list-item contract). */
export interface TalliedJob {
  verb?: string
  totalItems?: number
  tallies?: Record<string, number>
}

/**
 * The "N of M dispatched · ok X · failed Y" honesty line (R-SEM-11) — extracted from the
 * ops drawer (S5) so the incident detail's related-bulk-retries section renders the SAME
 * summary vocabulary instead of a parallel one. Verb-aware via {@link outcomeLabel}
 * (a retry-job "ok" tally reads "re-queued", never "ok" — Theme G).
 */
export function talliesLine(job: TalliedJob): string {
  const tallies = job.tallies ?? {}
  const total = job.totalItems ?? 0
  const settledOrder = [
    'ok',
    'failed',
    'skipped',
    'skipped_protected',
    'unknown',
    'not_run',
  ] as const
  const dispatched =
    total -
    (tallies['pending'] ?? 0) -
    (tallies['not_run'] ?? 0) -
    (tallies['skipped_protected'] ?? 0)
  const parts = settledOrder
    .filter((key) => (tallies[key] ?? 0) > 0)
    .map((key) => `${outcomeLabel(job.verb, key)} ${String(tallies[key] ?? 0)}`)
  return `${String(Math.max(dispatched, 0))} of ${String(total)} dispatched${parts.length > 0 ? ' · ' + parts.join(' · ') : ''}`
}
