// R-AUD-08 (usability W3-1): the operations-log CSV export. The BFF streams the SAME
// filters as the JSON log from GET /api/audit/export (formula-escaped server-side per
// R-OPS-08); this module fetches it THROUGH the singleton client (auth/CSRF middleware —
// a bare <a href> would drop the Basic session) and hands it to the browser as a download.
import { api, ApiError } from '../api/client'

export interface LogFilters {
  actor: string
  action: string
  ticketId: string
  since: string
}

/** The export query — the same non-blank filter mapping the JSON log uses. */
export function exportQuery(filters: LogFilters): Record<string, string> {
  const query: Record<string, string> = {}
  if (filters.actor.trim() !== '') query['actor'] = filters.actor.trim()
  if (filters.action.trim() !== '') query['action'] = filters.action.trim()
  if (filters.ticketId.trim() !== '') query['ticketId'] = filters.ticketId.trim()
  if (filters.since.trim() !== '') query['since'] = filters.since.trim()
  return query
}

export async function fetchOperationsCsv(filters: LogFilters): Promise<string> {
  const { data, error, response } = await api.GET('/api/audit/export', {
    params: { query: exportQuery(filters) },
    parseAs: 'text',
  })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

/** Object-URL download of an already-fetched text body (revoked after the click). */
export function saveTextAs(text: string, filename: string, mediaType = 'text/csv'): void {
  const url = URL.createObjectURL(new Blob([text], { type: mediaType }))
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.click()
  } finally {
    URL.revokeObjectURL(url)
  }
}

export async function downloadOperationsCsv(filters: LogFilters): Promise<void> {
  saveTextAs(await fetchOperationsCsv(filters), 'operations-log.csv')
}
