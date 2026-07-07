import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchActionCurl } from '../api/actions'
import type { ActionRequest } from '../api/actions'
import { CopyButton } from './CopyButton'

interface Props {
  engineId: string
  instanceId: string
  verb: string
  /** The exact body the execute call will POST — the rendered command mirrors it. */
  body: ActionRequest
}

/**
 * "Show as cURL" (v1.x #6). The command is SERVER-computed — the BFF renders its own
 * endpoint with a PLACEHOLDER credential (never a live token) — and shown VERBATIM, exactly
 * like the search cURL (SearchRail): the UI never re-assembles it. Lazily fetched (nothing
 * hits the BFF until the operator opens the toggle) and re-fetched when the pending body
 * changes, so what is shown always matches what Confirm will send.
 */
export function CurlPreview({ engineId, instanceId, verb, body }: Props) {
  const [open, setOpen] = useState(false)
  const query = useQuery({
    queryKey: ['action-curl', engineId, instanceId, verb, JSON.stringify(body)],
    queryFn: () => fetchActionCurl(engineId, instanceId, verb, body),
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
