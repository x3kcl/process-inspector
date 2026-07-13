import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { ActionCurlResponse } from '../api/actions'
import { CopyButton } from './CopyButton'

interface Props {
  /**
   * Uniquely identifies this call for react-query's cache — mirror every input that
   * would change the rendered command (scope, engine/instance/case id, verb, body).
   */
  queryKey: unknown[]
  /**
   * Issue #103: scope-agnostic — the instance and case routes both render this the SAME
   * way, only which BFF endpoint is called differs. Callers pass fetchActionCurl or
   * fetchCaseActionCurl bound to their own path params.
   */
  fetchCurl: () => Promise<ActionCurlResponse>
}

/**
 * "Show as cURL" (v1.x #6; issue #103 extended it to the tier-0 inline retry flows). The
 * command is SERVER-computed — the BFF renders its own endpoint with a PLACEHOLDER
 * credential (never a live token) — and shown VERBATIM, exactly like the search cURL
 * (SearchRail): the UI never re-assembles it. Lazily fetched (nothing hits the BFF until
 * the operator opens the toggle) and re-fetched when queryKey changes, so what is shown
 * always matches what Confirm will send.
 */
export function CurlPreview({ queryKey, fetchCurl }: Props) {
  const [open, setOpen] = useState(false)
  const query = useQuery({
    queryKey: ['action-curl', ...queryKey],
    queryFn: fetchCurl,
    enabled: open,
    staleTime: Infinity,
    retry: false,
  })

  return (
    <div className="curl-preview">
      <button
        type="button"
        className="curl-toggle"
        aria-expanded={open}
        onClick={() => {
          setOpen((prev) => !prev)
        }}
      >
        {open ? 'Hide cURL' : 'Show as cURL'}
      </button>
      {open && (
        <div className="curl-preview-body">
          {query.isPending && <p className="zero-state">Rendering the request…</p>}
          {query.isError && (
            <p className="strip-note">Could not render the cURL: {query.error.message}</p>
          )}
          {query.data !== undefined && (
            <>
              <div className="curl-preview-head">
                <span>The exact call this BFF will make — fill in your own credentials:</span>
                <CopyButton text={query.data.curl ?? ''} />
              </div>
              <pre className="curl-block">
                <code>{query.data.curl}</code>
              </pre>
            </>
          )}
        </div>
      )}
    </div>
  )
}
