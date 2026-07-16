import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { ActionCurlResponse } from '../api/actions'
import type { Gate } from '../actions/catalog'
import { ActionHint } from './ActionHint'
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
  /**
   * Issue #213: when this preview sits next to a gated action (e.g. the tier-0 inline
   * retry flow), pass that SAME gate so the toggle greys with a reason like its sibling
   * button instead of staying clickable and failing opaquely with a bare 403 once opened.
   * Callers whose CurlPreview only renders inside an already-gated surface (a modal that
   * cannot open without the underlying action being permitted) can omit this.
   */
  gate?: Gate
}

/**
 * "Show as cURL" (v1.x #6; issue #103 extended it to the tier-0 inline retry flows). The
 * command is SERVER-computed — the BFF renders its own endpoint with a PLACEHOLDER
 * credential (never a live token) — and shown VERBATIM, exactly like the search cURL
 * (SearchRail): the UI never re-assembles it. Lazily fetched (nothing hits the BFF until
 * the operator opens the toggle) and re-fetched when queryKey changes, so what is shown
 * always matches what Confirm will send.
 */
export function CurlPreview({ queryKey, fetchCurl, gate }: Props) {
  const [open, setOpen] = useState(false)
  const locked = gate?.enabled === false
  // A gate can flip mid-session (e.g. #208's identity-switch scenario) while the preview
  // is already open — `expanded` is the single source of truth for both the visible label
  // and aria-expanded, so neither can drift from whether the body is actually rendered.
  const expanded = open && !locked
  const query = useQuery({
    queryKey: ['action-curl', ...queryKey],
    queryFn: fetchCurl,
    enabled: expanded,
    staleTime: Infinity,
    retry: false,
  })
  const hintId = `curl-preview-hint-${queryKey.join('-')}`

  return (
    <div className="curl-preview">
      <button
        type="button"
        className="curl-toggle"
        disabled={locked}
        aria-expanded={expanded}
        aria-describedby={locked ? hintId : undefined}
        title={locked ? (gate.detail ?? gate.reason) : undefined}
        onClick={() => {
          setOpen((prev) => !prev)
        }}
      >
        {expanded ? 'Hide cURL' : 'Show as cURL'}
      </button>
      {locked && gate.reason !== undefined && (
        <ActionHint id={hintId} text={gate.reason} tone="gate" />
      )}
      {expanded && (
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
