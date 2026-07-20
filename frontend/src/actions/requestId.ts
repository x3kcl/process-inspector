// The ONE place the quotable-support-id next-move is worded (R-AUD-04, usability W1#6). Every
// user-facing error surface — the generic `ApiError.describe`, the guard-ladder `problemBanner`,
// and the bespoke grant-blocked / route-guard alerts (via `RequestIdNote`) — appends this exact
// sentence, so a user reading only the page (never the network inspector) always finds a
// correlation id to hand support. Never invented client-side: rendered only when the server
// actually stamped the response with an `X-Request-Id` (its ProblemDetail `requestId` property),
// which is also the audit rows' correlationId and the log lines' MDC id.

/** The standalone next-move sentence for a known request id. */
export function requestIdSentence(requestId: string): string {
  return `Quote request ID ${requestId} to support.`
}

/** Append the next-move sentence to an error sentence, or return it unchanged when the server
 *  sent no id (undefined/empty). */
export function withRequestId(sentence: string, requestId: string | undefined): string {
  return requestId === undefined || requestId === ''
    ? sentence
    : `${sentence} ${requestIdSentence(requestId)}`
}
