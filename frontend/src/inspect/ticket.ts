// "Copy for ticket" (SPEC §4 Stage 2): composite ID, definition+version, status,
// exception first line, failure time, deep link — one click, plain text. Timestamps stay
// as the wire ISO strings: a ticket crosses timezones, so ambiguity is a bug.
import type { InstanceDetail } from '../api/model'

export function buildTicketText(
  vitals: InstanceDetail,
  compositeId: string,
  deepLink: string,
): string {
  const lines: string[] = [`Instance: ${compositeId}`]

  const definition = vitals.definitionName ?? vitals.definitionKey ?? vitals.processDefinitionId
  if (definition !== undefined) {
    const version =
      vitals.definitionVersion !== undefined ? ` v${String(vitals.definitionVersion)}` : ''
    lines.push(`Definition: ${definition}${version}`)
  }

  if (vitals.status !== undefined) lines.push(`Status: ${vitals.status}`)

  if (vitals.businessKey !== undefined && vitals.businessKey !== '') {
    lines.push(`Business key: ${vitals.businessKey}`)
  }

  const exception = vitals.whyStuck?.exceptionFirstLine
  if (exception !== undefined) lines.push(`Exception: ${firstLine(exception)}`)

  const failureTime = vitals.whyStuck?.failureTime
  if (failureTime !== undefined) lines.push(`Last failure: ${failureTime}`)

  lines.push(`Link: ${deepLink}`)
  return lines.join('\n')
}

function firstLine(text: string): string {
  const newline = text.indexOf('\n')
  return newline < 0 ? text : text.slice(0, newline)
}

/**
 * Copy-for-ticket when the page itself errored (usability W1#6, R-AUD-04): there are no vitals
 * to quote, but the composite id, the error sentence — which carries the quotable request ID,
 * appended by ApiError — and the deep link are exactly what support needs.
 */
export function buildErrorTicketText(
  compositeId: string,
  errorMessage: string,
  deepLink: string,
): string {
  return [`Instance: ${compositeId}`, `Error: ${errorMessage}`, `Link: ${deepLink}`].join('\n')
}
