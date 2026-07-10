// Dangerous-set re-authentication (IDP-SECURITY.md §5, R-SAFE-07) — the SPA half of the
// challenge → full-page re-auth → replay protocol. The BFF refuses a stale OIDC session on a
// dangerous entry (tier-3 verb / bulk submit / mapping write) with a 401 whose ProblemDetail
// carries code `reauth-required`; the /api/me `reauth` hint lets the UI pre-empt at modal OPEN so
// the operator re-authenticates BEFORE typing the reason + confirm token — never after.
//
// The redirect is a real top-level navigation (an XHR cannot follow the IdP 302), so all
// in-memory UI state dies; we checkpoint the current route to sessionStorage and restore it when
// the app boots again on the post-login landing. Only the route is preserved (R-SEM-14) — a
// dirty modal draft is warn-before-guillotine territory (a later slice); the challenge fires at
// verb INTENT, before anything is typed.
import type { components } from '../api/schema'

export type ReauthHint = components['schemas']['ReauthHint']

/** The oidc chain's re-auth entry: the S5a resolver sees `reauth` and injects max_age + prompt=login. */
export const REAUTH_LOGIN_PATH = '/oauth2/authorization/oidc?reauth=true'

const RESUME_KEY = 'inspector.reauth.resume'
/** A checkpoint older than this is stale — the operator wandered off mid-login; land on '/'. */
const RESUME_TTL_MS = 10 * 60 * 1000

/**
 * Is the session too stale for a dangerous verb RIGHT NOW? Pure — pass Date.now().
 * `required` is the server's verdict at /api/me fetch time; `freshUntil` extends it client-side
 * so a modal opened long after the (staleTime: Infinity) me-fetch still pre-empts correctly.
 * No hint (dev chain, break-glass, me not loaded) = optimistic false — the BFF gate answers.
 */
export function reauthStale(hint: ReauthHint | undefined, nowMs: number): boolean {
  if (hint === undefined) return false
  if (hint.required === true) return true
  if (hint.freshUntil !== undefined) {
    const until = Date.parse(hint.freshUntil)
    return Number.isFinite(until) && nowMs > until
  }
  return false
}

/** The re-auth window in whole minutes, for the interstitial copy. */
export function reauthWindowMinutes(hint: ReauthHint | undefined): number {
  const seconds = hint?.windowSeconds ?? 900
  return Math.max(1, Math.round(seconds / 60))
}

/** Warn-before-guillotine threshold: the countdown banner shows inside this window (R-SAFE-07). */
export const GUILLOTINE_WARN_MS = 30 * 60 * 1000

/**
 * The warn-before-guillotine decision (IDP-SECURITY.md §5, R-SAFE-07): how the SPA reads
 * {@code me.sessionExpiresAt}. Pure — pass Date.now(); the banner re-evaluates on a timer.
 * {@code show} inside the warn window while the session still lives; {@code minutesLeft} is
 * ceil'd so the copy never says "0 minutes" while requests still work. After expiry the next
 * API call answers 401 and the normal sign-in path takes over — the banner's job is done.
 */
export function sessionExpiryState(
  expiresAt: string | undefined,
  nowMs: number,
): { show: boolean; minutesLeft: number } {
  if (expiresAt === undefined) return { show: false, minutesLeft: 0 }
  const until = Date.parse(expiresAt)
  if (!Number.isFinite(until)) return { show: false, minutesLeft: 0 }
  const remaining = until - nowMs
  if (remaining <= 0 || remaining > GUILLOTINE_WARN_MS) return { show: false, minutesLeft: 0 }
  return { show: true, minutesLeft: Math.max(1, Math.ceil(remaining / 60_000)) }
}

/**
 * Is this (unknown-typed) error body the BFF's 401 freshness challenge? Non-action endpoints
 * (the mapping admin writes) surface it as a raw ProblemDetail on ApiError.body — same
 * machine-readable `code` the action paths parse into ActionProblem.
 */
export function isReauthBody(body: unknown): boolean {
  return (
    body !== null &&
    typeof body === 'object' &&
    (body as Record<string, unknown>)['code'] === 'reauth-required'
  )
}

/** Serialized checkpoint — exported for tests; only ever stored in sessionStorage. */
export function encodeResume(href: string, nowMs: number): string {
  return JSON.stringify({ href, ts: nowMs })
}

/**
 * Decode + validate a checkpoint. Returns the same-origin route to restore, or null when the
 * checkpoint is missing/expired/malformed. Only a leading-slash path is honoured (never an
 * absolute URL — a poisoned checkpoint must not become an open redirect).
 */
export function decodeResume(raw: string | null, nowMs: number): string | null {
  if (raw === null) return null
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed === null || typeof parsed !== 'object') return null
    const record = parsed as Record<string, unknown>
    const href = record['href']
    const ts = record['ts']
    if (typeof href !== 'string' || typeof ts !== 'number') return null
    // Same-origin path ONLY: no absolute URLs, no protocol-relative '//', and no backslashes —
    // browsers normalise '/\evil.com' to '//evil.com' (Copilot review), so '\' is rejected outright.
    if (!href.startsWith('/') || href.startsWith('//') || href.includes('\\')) return null
    if (nowMs - ts > RESUME_TTL_MS || nowMs < ts) return null
    return href
  } catch {
    return null
  }
}

/**
 * Checkpoint the current route and hand the tab to the IdP. On return the app boots fresh;
 * {@link consumeResume} restores the route (Shell mounts it once).
 */
export function checkpointAndReauth(): void {
  try {
    sessionStorage.setItem(
      RESUME_KEY,
      encodeResume(
        window.location.pathname + window.location.search + window.location.hash,
        Date.now(),
      ),
    )
  } catch {
    // Best-effort: sessionStorage full/blocked → the operator lands on '/' after re-auth.
  }
  window.location.assign(REAUTH_LOGIN_PATH)
}

/** Pop the checkpoint (single-shot). Null when there is nothing valid to restore. */
export function consumeResume(): string | null {
  const raw = sessionStorage.getItem(RESUME_KEY)
  sessionStorage.removeItem(RESUME_KEY)
  return decodeResume(raw, Date.now())
}
