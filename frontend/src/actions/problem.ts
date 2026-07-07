// Guard-ladder failure decoding (SPEC §6). The BFF's ActionExceptionHandler answers every
// action failure as an RFC-7807 ProblemDetail with machine-readable `code` + `outcome`
// properties. The UI's job is to keep the three-way distinction visible in plain language:
// refused pre-flight (nothing happened) / engine rejected (nothing happened, engine's
// words quoted) / dispatched-unverified (assume it happened until verified).

export type ProblemOutcome = 'refused' | 'failed' | 'unknown'

export interface ActionProblem {
  status: number
  /** Machine-readable code: cas-conflict, audit-unavailable, reason-required,
   *  confirm-token-mismatch, job-gone, engine-rejected, outcome-unknown, … */
  code: string
  title: string
  detail: string
  outcome: ProblemOutcome
  auditId?: string
  /** cas-conflict only: the value the engine holds right now. */
  currentValue?: unknown
  /** cas-conflict only: the value the edit expected to replace. */
  expectedOldValue?: unknown
  engineStatus?: number
  engineBody?: string
}

function str(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key]
  return typeof value === 'string' ? value : undefined
}

export function parseActionProblem(status: number, body: unknown): ActionProblem {
  const source: Record<string, unknown> =
    body !== null && typeof body === 'object' ? (body as Record<string, unknown>) : {}
  const rawOutcome = str(source, 'outcome')
  const outcome: ProblemOutcome =
    rawOutcome === 'failed' || rawOutcome === 'unknown' ? rawOutcome : 'refused'
  return {
    status,
    code: str(source, 'code') ?? (status === 403 ? 'forbidden' : 'unexpected'),
    title: str(source, 'title') ?? `HTTP ${String(status)}`,
    detail: str(source, 'detail') ?? '',
    outcome,
    auditId: str(source, 'auditId'),
    currentValue: 'currentValue' in source ? source['currentValue'] : undefined,
    expectedOldValue: 'expectedOldValue' in source ? source['expectedOldValue'] : undefined,
    engineStatus: typeof source['engineStatus'] === 'number' ? source['engineStatus'] : undefined,
    engineBody: str(source, 'engineBody'),
  }
}

/** True when the action may have reached the engine — resubmitting could double-fire. */
export function mayHaveExecuted(problem: ActionProblem): boolean {
  return problem.outcome === 'unknown'
}

/**
 * The operator-facing banner sentence per failure class (SPEC §6 error-copy rule:
 * never a generic 500 for a dispatched mutation).
 */
export function problemBanner(problem: ActionProblem): string {
  switch (problem.code) {
    case 'audit-unavailable':
      return 'Refused fail-closed: the audit store is unavailable, so the action was NOT sent to the engine. Nothing happened. Retry once audit is back.'
    case 'cas-conflict':
      return 'The value changed on the engine since you loaded it — nothing was overwritten. Start over from the current value.'
    case 'engine-rejected':
      return `The engine rejected the action — nothing happened. Engine said: ${problem.engineBody !== undefined && problem.engineBody !== '' ? problem.engineBody : problem.detail}`
    case 'outcome-unknown':
      return 'The action was dispatched but the engine never answered — it MAY have executed. Do not resubmit; re-check the instance state and the audit trail.'
    case 'outcome-verification-failed':
      return 'The action was dispatched and likely executed, but recording the outcome failed. Do not resubmit; the audit row stays "unknown" until verified.'
    case 'forbidden':
      return 'Your role does not permit this action — the BFF refused it. Nothing happened.'
    default:
      // Every other guard refusal (reason-required, confirm-token-mismatch, job-gone…)
      // already carries an exact operator sentence from the BFF.
      return problem.detail !== ''
        ? problem.detail
        : `The request was refused before anything ran — nothing happened. (Technical detail: HTTP ${String(problem.status)}.)`
  }
}
