import { useEffect } from 'react'
import { useRouteError } from 'react-router'
import { glossTechnicalMessage } from '../lib/plainFailure'
import { chunkErrorRecoveryOutcome, isChunkLoadError, markChunkReloadAttempted } from './chunkErrorRecovery'

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  if (typeof error === 'string') return error
  return 'unknown error'
}

/**
 * #265: route-level `errorElement` for every lazy-loaded page (main.tsx). A tab left open
 * across a redeploy still references pre-deploy hashed chunk URLs; nginx now 404s those
 * cleanly (frontend/nginx.conf) instead of masquerading as index.html, so the browser's
 * dynamic import() rejects with "error loading dynamically imported module" (Firefox) or
 * "Failed to fetch dynamically imported module" (Chrome/V8) — react-router re-throws that
 * on render and this errorElement catches it, replacing the default "Hey developer" screen.
 *
 * First sighting this session: reload once — a fresh index.html carries the new manifest,
 * so the reload IS the fix. Second sighting (sessionStorage-guarded, see
 * chunkErrorRecovery.ts): the deploy is genuinely broken, not just stale — fall back to the
 * app's standard `.error-banner` panel instead of looping reloads forever.
 *
 * The reload is deliberately fired from an effect, not during render, to keep this
 * component's render body pure; `chunkErrorRecoveryOutcome` only READS the session flag.
 */
export function ChunkErrorBoundary() {
  const error = useRouteError()
  const outcome = chunkErrorRecoveryOutcome(error)

  useEffect(() => {
    if (outcome === 'reload') {
      markChunkReloadAttempted()
      window.location.reload()
    }
  }, [outcome])

  if (outcome === 'reload') {
    return <p className="muted">Reloading…</p>
  }

  const message = isChunkLoadError(error)
    ? 'This page could not be loaded after a recent update, even after reloading.'
    : glossTechnicalMessage(errorMessage(error))

  return (
    <div className="error-banner" role="alert">
      <p>
        {message}{' '}
        <button
          type="button"
          onClick={() => {
            window.location.reload()
          }}
        >
          Reload
        </button>
      </p>
      <details>
        <summary>Technical detail</summary>
        {errorMessage(error)}
      </details>
    </div>
  )
}
