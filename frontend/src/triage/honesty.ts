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
}

export function deriveHonesty(
  perEngine: Record<string, PerEngineTriage> | undefined,
): TriageHonesty {
  const failedEngines: TriageHonesty['failedEngines'] = []
  const truncatedScans: TriageHonesty['truncatedScans'] = []
  for (const [engineId, result] of Object.entries(perEngine ?? {})) {
    if (result.ok !== true) {
      failedEngines.push({ engineId, error: result.error ?? 'aggregation failed' })
    } else if (result.dlqScan !== undefined && result.dlqScan.startsWith('truncated')) {
      truncatedScans.push({ engineId, marker: result.dlqScan })
    }
  }
  failedEngines.sort((a, b) => a.engineId.localeCompare(b.engineId))
  truncatedScans.sort((a, b) => a.engineId.localeCompare(b.engineId))
  return { failedEngines, truncatedScans }
}

/** Status tiles are lower bounds only when an engine is missing from the aggregate. */
export function statusCountsAreLowerBound(honesty: TriageHonesty): boolean {
  return honesty.failedEngines.length > 0
}

/** Group counts are lower bounds when an engine is missing OR its DLQ scan truncated. */
export function groupCountsAreLowerBound(honesty: TriageHonesty): boolean {
  return honesty.failedEngines.length > 0 || honesty.truncatedScans.length > 0
}
