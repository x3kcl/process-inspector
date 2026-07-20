import { requestIdSentence } from './requestId'

/**
 * The quotable-support-id next move (R-AUD-04, usability W1#6) rendered as its own alert line,
 * for the error surfaces whose copy is bespoke JSX rather than an `ApiError`/`problemBanner`
 * STRING (grant-blocked route alerts, route-guard denials). Renders nothing when the server sent
 * no id — the id is never invented client-side, only surfaced when it was on the wire. The
 * wording is the exact same sentence `withRequestId` appends everywhere else, so support hears
 * one phrase regardless of which surface the user hit.
 */
export function RequestIdNote({ requestId }: { requestId: string | undefined }) {
  if (requestId === undefined || requestId === '') return null
  return <p className="request-id-note">{requestIdSentence(requestId)}</p>
}
