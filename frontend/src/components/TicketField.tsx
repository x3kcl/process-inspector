// R-AUD-07 (usability W3-1): the optional Ticket ID input on every reason-bearing confirm
// surface. It feeds ActionRequest/Bulk*Request.ticketId — validated server-side by
// TicketPolicy (regex + length; prod deployments may REQUIRE it) and linkified in the
// audit surfaces via ticket-url-template. UI stays permissive: the BFF guard is the gate.
interface Props {
  value: string
  onChange: (value: string) => void
}

/** Blank input → undefined (the wire field is absent, never an empty string). */
export function ticketValue(raw: string): string | undefined {
  const trimmed = raw.trim()
  return trimmed === '' ? undefined : trimmed
}

export function TicketField({ value, onChange }: Props) {
  return (
    <label className="modal-field">
      Ticket ID (optional — recorded with the audit row and linked in the operations log)
      <input
        type="text"
        value={value}
        maxLength={200}
        placeholder="OPS-42"
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => {
          onChange(event.target.value)
        }}
      />
    </label>
  )
}
