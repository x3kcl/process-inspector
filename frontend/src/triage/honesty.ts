// R-SEM-12 on Stage 0: the first number an operator anchors on gets the same honesty
// guarantee as the grid. A failed engine is EXCLUDED from every aggregate (counts become
// lower bounds everywhere); a truncated DLQ scan makes the error-group counts lower bounds
// for that engine while the status tiles (size=1 query totals) stay exact.
import type { PerEngineTriage } from '../api/model'

export interface TriageHonesty {
  /** Engines whose aggregation failed outright — all Stage 0 counts are ≥ lower bounds. */
  failedEngines: { engineId: string; error: string }[]
  /** Engines whose failure-lane scan hit the cap — error-GROUP counts are lower bounds. */
  truncatedScans: { engineId: string; marker: string }[]
  /**
   * Engines whose dead-letter lane holds jobs from ANOTHER engine sharing the job tables
   * (CMMN): counted, not counted as process failures. Reconciles the health strip's raw
   * dead-letter count with the process-scoped FAILED count. Only present on engines new
   * enough to discriminate scope (6.8+); a null/zero count contributes nothing. {@code floor}
   * marks the count a lower bound — the DEADLETTER lane's own scan hit the cap, so more
   * orphans may lie past it (rendered ≥N).
   */
  outOfScope: { engineId: string; count: number; floor: boolean }[]
}

export function deriveHonesty(
  perEngine: Record<string, PerEngineTriage> | undefined,
): TriageHonesty {
  const failedEngines: TriageHonesty['failedEngines'] = []
  const truncatedScans: TriageHonesty['truncatedScans'] = []
  const outOfScope: TriageHonesty['outOfScope'] = []
  for (const [engineId, result] of Object.entries(perEngine ?? {})) {
    if (result.ok !== true) {
      failedEngines.push({ engineId, error: result.error ?? 'aggregation failed' })
    } else if (result.dlqScan !== undefined && result.dlqScan.startsWith('truncated')) {
      truncatedScans.push({ engineId, marker: result.dlqScan })
    }
    // Independent of the group-count truncation channel: a healthy engine can still project
    // out-of-scope orphans. The count is a lower bound when the DEADLETTER lane's OWN scan
    // hit the cap (deadletterTruncated) — not merely when the unified dlqScan marker tripped.
    if (result.ok === true && (result.outOfScopeDeadletters ?? 0) > 0) {
      outOfScope.push({
        engineId,
        count: result.outOfScopeDeadletters ?? 0,
        floor: result.deadletterTruncated === true,
      })
    }
  }
  failedEngines.sort((a, b) => a.engineId.localeCompare(b.engineId))
  truncatedScans.sort((a, b) => a.engineId.localeCompare(b.engineId))
  outOfScope.sort((a, b) => a.engineId.localeCompare(b.engineId))
  return { failedEngines, truncatedScans, outOfScope }
}

/** Status tiles are lower bounds only when an engine is missing from the aggregate. */
export function statusCountsAreLowerBound(honesty: TriageHonesty): boolean {
  return honesty.failedEngines.length > 0
}

/** Group counts are lower bounds when an engine is missing OR its DLQ scan truncated. */
export function groupCountsAreLowerBound(honesty: TriageHonesty): boolean {
  return honesty.failedEngines.length > 0 || honesty.truncatedScans.length > 0
}
