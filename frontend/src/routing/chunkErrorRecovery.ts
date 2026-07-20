// #265: a tab opened before a redeploy still holds route components as
// React.lazy(() => import('./Page')) closures pointing at the OLD hashed chunk URL
// (main.tsx). Once nginx answers a clean 404 for a dead chunk (frontend/nginx.conf, the
// server half of this fix), the browser's dynamic import() rejects with one of two
// messages depending on engine — Firefox: "error loading dynamically imported module: …";
// Chrome/Edge (V8): "Failed to fetch dynamically imported module: …". React re-throws that
// rejection on the next render pass, where it's caught by the route's errorElement
// (ChunkErrorBoundary.tsx). This module is the pure/testable half of the recovery decision;
// the DOM-touching half (sessionStorage, window.location) stays a thin wrapper per the
// reauth.ts convention — encode/decode logic unit-tested, storage/navigation calls kept
// minimal and best-effort.

/** Dot-namespaced per this repo's sessionStorage convention (auth.ts, reauth.ts). */
export const CHUNK_RELOAD_FLAG_KEY = 'inspector.chunk-reload-attempted'

// Both known browser phrasings for a dynamic import() that 404s or otherwise fails to
// fetch, case-insensitive (a browser vendor could ship either capitalization).
const CHUNK_LOAD_ERROR_PATTERNS = [
  /error loading dynamically imported module/i,
  /failed to fetch dynamically imported module/i,
]

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  if (typeof error === 'string') return error
  return ''
}

/** Pure: does this thrown/rejected value look like a stale-chunk dynamic import failure? */
export function isChunkLoadError(error: unknown): boolean {
  const message = errorMessage(error)
  return CHUNK_LOAD_ERROR_PATTERNS.some((pattern) => pattern.test(message))
}

export type ChunkErrorRecoveryOutcome = 'reload' | 'fallback' | 'ignore'

/**
 * Pure decision, given whether an auto-reload was already attempted THIS session
 * (sessionStorage — a tab reload/redeploy-recovery cycle, not persisted across tabs or
 * browser restarts, which is exactly the "still broken after one try" guard we want).
 * - not a chunk-load error at all -> 'ignore' (let the caller render its normal error UI)
 * - first sighting -> 'reload' (the fix IS a fresh index.html + fresh manifest)
 * - already tried once and STILL failing -> 'fallback' (the deploy is genuinely broken;
 *   reloading forever would strand the operator in a refresh loop instead of showing them
 *   an error they can act on)
 */
export function decideChunkErrorRecovery(
  error: unknown,
  alreadyAttempted: boolean,
): ChunkErrorRecoveryOutcome {
  if (!isChunkLoadError(error)) return 'ignore'
  return alreadyAttempted ? 'fallback' : 'reload'
}

/** Best-effort read — a blocked/private-mode sessionStorage degrades to "never attempted". */
export function hasAttemptedChunkReload(): boolean {
  try {
    return sessionStorage.getItem(CHUNK_RELOAD_FLAG_KEY) === '1'
  } catch {
    return false
  }
}

/** Best-effort write — if storage is blocked, the reload still happens once (this render),
 *  it just can't be guarded against a second loop; the fallback panel's manual Reload
 *  button remains available either way. */
export function markChunkReloadAttempted(): void {
  try {
    sessionStorage.setItem(CHUNK_RELOAD_FLAG_KEY, '1')
  } catch {
    // best-effort, see doc comment above
  }
}

/**
 * The DOM-touching half of the decision: reads the session flag via
 * {@link hasAttemptedChunkReload} and folds it into {@link decideChunkErrorRecovery}.
 * Does NOT set the flag or reload the page itself — callers (ChunkErrorBoundary) do that
 * as an explicit side effect once they've decided to act on a 'reload' outcome, keeping
 * this function safe to call from a React render body.
 */
export function chunkErrorRecoveryOutcome(error: unknown): ChunkErrorRecoveryOutcome {
  return decideChunkErrorRecovery(error, hasAttemptedChunkReload())
}
