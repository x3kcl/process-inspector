// Guard-ladder failure decoding (SPEC §6). The BFF's ActionExceptionHandler answers every
// action failure as an RFC-7807 ProblemDetail with machine-readable `code` + `outcome`
// properties. The UI's job is to keep the three-way distinction visible in plain language:
// refused pre-flight (nothing happened) / engine rejected (nothing happened, engine's
// words quoted) / dispatched-unverified (assume it happened until verified).

import { withRequestId } from './requestId'

export type ProblemOutcome = 'refused' | 'failed' | 'unknown'

export interface ActionProblem {
  status: number
  /** Machine-readable code: cas-conflict, audit-unavailable, reason-required,
   *  confirm-token-mismatch, job-gone, engine-rejected, engine-read-only,
   *  outcome-unknown, … */
  code: string
  title: string
  detail: string
  outcome: ProblemOutcome
  auditId?: string
  /** cas-conflict only: the value the engine holds right now. */
  currentValue?: unknown
  /** cas-conflict only: the value the edit expected to replace. */
  expectedOldValue?: unknown
  /** bulk-count-drift only (tier-4 destructive-bulk wizard, issue #100): what the operator typed. */
  confirmedCount?: number
  /** bulk-count-drift only: the FRESH resolved count the typed count no longer matches. */
  actualCount?: number
  engineStatus?: number
  engineBody?: string
  /** The quotable support id (usability W1#6, R-AUD-04): the BFF stamps every ProblemDetail
   *  body — one contract everywhere (issue #87 — F4) — with the request's X-Request-Id,
   *  which is also the audit rows' correlationId and the log lines' MDC id. */
  requestId?: string
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
  const code = str(source, 'code')
  return {
    status,
    // No 403→'forbidden' GUESS here: 'forbidden' only appears when the BFF itself sent it
    // (ProblemCodes.fromStatus, the container-/error fallback) — the UI never invents an
    // RBAC verdict the server didn't give (usability W1#1 — wrong-reason 403).
    code: code ?? 'unexpected',
    title: str(source, 'title') ?? `HTTP ${String(status)}`,
    detail: str(source, 'detail') ?? '',
    outcome,
    auditId: str(source, 'auditId'),
    currentValue: 'currentValue' in source ? source['currentValue'] : undefined,
    expectedOldValue: 'expectedOldValue' in source ? source['expectedOldValue'] : undefined,
    confirmedCount:
      typeof source['confirmedCount'] === 'number' ? source['confirmedCount'] : undefined,
    actualCount: typeof source['actualCount'] === 'number' ? source['actualCount'] : undefined,
    engineStatus: typeof source['engineStatus'] === 'number' ? source['engineStatus'] : undefined,
    engineBody: str(source, 'engineBody'),
    requestId: str(source, 'requestId'),
  }
}

/** True when the action may have reached the engine — resubmitting could double-fire. */
export function mayHaveExecuted(problem: ActionProblem): boolean {
  return problem.outcome === 'unknown'
}

/**
 * The dangerous-set freshness challenge (IDP-SECURITY.md §5): the session is authenticated but
 * too stale for this verb — the remedy is a full-page re-auth, not a different input. The UI
 * renders the re-auth call-to-action (ReauthNotice) instead of a plain error banner.
 */
export function isReauthChallenge(problem: ActionProblem): boolean {
  return problem.code === 'reauth-required'
}

/**
 * The operator-facing banner sentence per failure class (SPEC §6 error-copy rule:
 * never a generic 500 for a dispatched mutation). Always ends with the quotable-id
 * next move when the server sent one (W1#6, R-AUD-04): quoting that id gives support
 * the request's log lines AND its audit rows (same value end to end). Never invented
 * client-side.
 */
export function problemBanner(problem: ActionProblem): string {
  return withRequestId(bannerSentence(problem), problem.requestId)
}

function bannerSentence(problem: ActionProblem): string {
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
    case 'engine-read-only':
      // W1#4 (theme T6): a 403 from a read-only engine is OWNER POLICY, not a role
      // verdict — the copy must never be byte-identical to (or read like) rbac-denied.
      return `Refused: this engine is registered read-only — set by the engine owner. That is engine policy, not your role; nothing happened.${problem.detail !== '' ? ` (${problem.detail})` : ''}`
    case 'rbac-denied':
      // The ONLY code that renders an RBAC verdict — a plain 'forbidden' (below) takes the
      // honest, role-silent copy instead (usability W1#1).
      return `Your role does not permit this action — the BFF refused it. Nothing happened.${problem.detail !== '' ? ` (${problem.detail})` : ''}`
    case 'forbidden':
      // Refused before the BFF's own action handlers ran (Spring Security's own 403 —
      // @PreAuthorize or the container /error fallback, ProblemCodes.fromStatus). On a
      // cookie session that is almost always a missing CSRF token, and a fresh sign-in mints
      // one; say only what is known, never an RBAC verdict the server didn't give.
      return 'The server refused this request before anything ran — HTTP 403. The session may be missing a CSRF token — sign out and back in, then retry.'
    case 'reauth-required':
      return 'Your sign-in is too old for this action — re-authenticate and try again. Nothing happened.'
    default:
      // Every other guard refusal (reason-required, confirm-token-mismatch, job-gone…)
      // already carries an exact operator sentence from the BFF; every OTHER response now
      // carries a real `detail` too (one error contract, issue #87 — F4), so this is the
      // universal fallback rather than a special case for any one shape.
      return problem.detail !== ''
        ? problem.detail
        : `The request was refused before anything ran — nothing happened. (Technical detail: HTTP ${String(problem.status)}.)`
  }
}
