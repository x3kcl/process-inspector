// Linkify a ticketId with the deploy-configured template (R-AUD-07). The BFF already validates
// the template at boot (http(s) + exactly one {ticketId}); this is defense-in-depth: substitute
// the id (URL-encoded so it can never break out of the URL), then re-verify the result parses to
// an http(s) URL — anything else (or a missing template/id) returns null and the caller renders
// plain text. The id itself is never interpreted as HTML (React text binding; no innerHTML).
export function ticketHref(
  template: string | null | undefined,
  ticketId: string | null | undefined,
): string | null {
  if (template == null || template === '' || ticketId == null || ticketId === '') return null
  const url = template.replaceAll('{ticketId}', encodeURIComponent(ticketId))
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? url : null
  } catch {
    return null
  }
}
