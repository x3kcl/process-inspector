// Derivations over the /api/search envelope: partial-results banner model, lower-bound
// labeling, zero states (SPEC §4 Stage 1, §10a) and secondary status badges (ARCH §2.3).
import type {
  EngineResult,
  InstanceStatus,
  InstanceStatusFlags,
  SearchResponse,
} from '../api/model'

export interface EngineFailure {
  engineId: string
  error: string
}

export interface EngineTruncation {
  engineId: string
  /** e.g. "dead-letter scan truncated@5000" */
  detail: string
}

export interface EngineOverflow {
  engineId: string
  fetched: number
  total: number
}

export interface PartialSummary {
  totalEngines: number
  okEngines: number
  failed: EngineFailure[]
  truncated: EngineTruncation[]
  /** fetched < total on an ok engine — the page cap bit, not an error. */
  overflowing: EngineOverflow[]
  /** Any count derived from this response is a lower bound (iron rule: badge it). */
  lowerBound: boolean
}

export function summarizePartials(
  perEngine: Record<string, EngineResult> | undefined,
): PartialSummary {
  const entries = Object.entries(perEngine ?? {})
  const failed: EngineFailure[] = []
  const truncated: EngineTruncation[] = []
  const overflowing: EngineOverflow[] = []
  for (const [engineId, result] of entries) {
    if (result.ok !== true) {
      failed.push({ engineId, error: result.error ?? 'unknown error' })
      continue
    }
    if (result.dlqScan !== undefined) {
      truncated.push({ engineId, detail: `dead-letter scan ${result.dlqScan}` })
    }
    if (result.failingScan !== undefined) {
      truncated.push({ engineId, detail: `failing-job scan ${result.failingScan}` })
    }
    const fetched = result.fetched ?? 0
    const total = result.total ?? 0
    if (total > fetched) {
      overflowing.push({ engineId, fetched, total })
    }
  }
  return {
    totalEngines: entries.length,
    okEngines: entries.length - failed.length,
    failed,
    truncated,
    overflowing,
    lowerBound: failed.length > 0 || truncated.length > 0 || overflowing.length > 0,
  }
}

/**
 * SPEC §10a: the empty grid has DISTINCT states — a calm "no matches" while an engine is
 * down would be a lie. Null when rows exist.
 */
export type ZeroState = 'all-engines-failed' | 'zero-under-partial-coverage' | 'true-zero'

export function zeroState(response: SearchResponse): ZeroState | null {
  if ((response.rows ?? []).length > 0) return null
  const summary = summarizePartials(response.perEngine)
  if (summary.totalEngines > 0 && summary.okEngines === 0) return 'all-engines-failed'
  if (summary.lowerBound) return 'zero-under-partial-coverage'
  return 'true-zero'
}

/**
 * Secondary badges next to the primary status chip: the flags that the precedence ladder
 * (COMPLETED → FAILED → RETRYING → SUSPENDED → ACTIVE) hid. Text always — never color-only.
 */
export function secondaryBadges(
  status: InstanceStatus | undefined,
  flags: InstanceStatusFlags | undefined,
): string[] {
  if (flags === undefined) return []
  const badges: string[] = []
  if (flags.failedInSubprocess === true) badges.push('in subprocess')
  if (flags.suspended === true && status !== 'SUSPENDED') badges.push('suspended')
  if (flags.hasDeadLetterJobs === true && status !== 'FAILED') badges.push('dead-letter jobs')
  if (flags.hasFailingJobs === true && status !== 'RETRYING') badges.push('retrying')
  return badges
}
