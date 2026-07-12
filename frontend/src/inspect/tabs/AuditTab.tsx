import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { useTicketUrlTemplate } from '../../api/meta'
import { createInstanceNote, fetchInstanceAudit, fetchInstanceNotes } from '../../api/queries'
import { AttributionCaveat } from '../../components/AttributionCaveat'
import { Ts } from '../../lib/Ts'
import { ticketHref } from '../../lib/ticket'
import { auditOutcomeView } from '../../ops/outcome'
import { RawJsonExport } from '../RawJsonExport'

interface Props {
  engineId: string
  instanceId: string
}

/**
 * Audit & Notes (SPEC §4): this instance's action history + free-text handover notes.
 * Lazy like every tab — these queries mount (and therefore fire) only when the tab opens.
 */
export default function AuditTab({ engineId, instanceId }: Props) {
  return (
    <div className="audit-tab">
      {/* R-AUD-09: static on every render — engine-side (ACT_HI_*) history blames the
          shared service account; investigations of WHO start on THIS surface. */}
      <AttributionCaveat />
      <AuditLog engineId={engineId} instanceId={instanceId} />
      <Notes engineId={engineId} instanceId={instanceId} />
    </div>
  )
}

function TicketRef({ template, ticketId }: { template: string | undefined; ticketId: string }) {
  const href = ticketHref(template, ticketId)
  return href !== null ? (
    <a className="ticket" href={href} target="_blank" rel="noopener noreferrer">
      {ticketId}
    </a>
  ) : (
    <code className="ticket"> {ticketId}</code>
  )
}

function AuditLog({ engineId, instanceId }: Props) {
  const ticketTemplate = useTicketUrlTemplate()
  const audit = useQuery({
    queryKey: ['audit', engineId, instanceId],
    queryFn: () => fetchInstanceAudit(engineId, instanceId),
  })
  if (audit.isPending) return <div className="zero-state">Loading action history…</div>
  if (audit.isError) {
    return (
      <div className="error-banner" role="alert">
        Audit history failed: {audit.error.message}
      </div>
    )
  }
  if (audit.data.length === 0) {
    return (
      <>
        <RawJsonExport data={audit.data} filename={`${engineId}-${instanceId}-audit.json`} />
        <div className="zero-state">No corrective actions recorded for this instance.</div>
      </>
    )
  }
  return (
    <>
      <RawJsonExport data={audit.data} filename={`${engineId}-${instanceId}-audit.json`} />
      <table className="ledger-table audit-table">
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Actor</th>
            <th scope="col">Action</th>
            <th scope="col">Outcome</th>
            <th scope="col">Reason</th>
          </tr>
        </thead>
        <tbody>
          {audit.data.map((entry) => {
            // Theme T8 / R-UXQ-05: same verdict-word mapping as the global ops log —
            // never raw "ok · null" internals (httpStatus arrives as a wire null).
            const outcome = auditOutcomeView(entry.action, entry.outcome, entry.httpStatus)
            return (
              <tr key={entry.id ?? `${entry.ts ?? ''}${entry.action ?? ''}`}>
                <td>
                  <Ts iso={entry.ts} relative />
                </td>
                <td>{entry.actor}</td>
                <td>
                  <code>{entry.action}</code>
                  {entry.breakGlass === true && (
                    <span className="status-badge" title="executed under break-glass">
                      break-glass
                    </span>
                  )}
                </td>
                <td>
                  <span className={`outcome ${outcome.className}`} title={outcome.title}>
                    {outcome.label}
                  </span>
                </td>
                <td className="audit-reason">
                  {entry.reason}
                  {entry.ticketId !== undefined && entry.ticketId !== '' && (
                    <TicketRef template={ticketTemplate} ticketId={entry.ticketId} />
                  )}
                  {entry.payload !== undefined && entry.payload !== '' && (
                    // The handover detail (SPEC §9): full request payload incl. old values
                    // for variable edits — collapsed, the row stays scannable.
                    <details className="audit-payload">
                      <summary>payload</summary>
                      <pre className="value-body">{prettyPayload(entry.payload)}</pre>
                    </details>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </>
  )
}

/** Audit payloads arrive as JSON strings; render them indented when they parse. */
function prettyPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

function Notes({ engineId, instanceId }: Props) {
  const queryClient = useQueryClient()
  const notes = useQuery({
    queryKey: ['notes', engineId, instanceId],
    queryFn: () => fetchInstanceNotes(engineId, instanceId),
  })
  const [draft, setDraft] = useState('')
  const create = useMutation({
    mutationFn: (body: string) => createInstanceNote(engineId, instanceId, body),
    onSuccess: async () => {
      setDraft('')
      await queryClient.invalidateQueries({ queryKey: ['notes', engineId, instanceId] })
    },
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const body = draft.trim()
    if (body !== '') create.mutate(body)
  }

  return (
    <section className="notes" aria-label="Operator notes">
      <h3>Notes</h3>
      <RawJsonExport data={notes.data} filename={`${engineId}-${instanceId}-notes.json`} />
      {notes.isError && (
        <div className="error-banner" role="alert">
          Notes failed: {notes.error.message}
        </div>
      )}
      {notes.data !== undefined && notes.data.length === 0 && (
        <p className="placeholder">No notes yet — the handover surface is empty.</p>
      )}
      <ul className="notes-list">
        {notes.data?.map((note) => (
          <li key={note.id ?? `${note.ts ?? ''}${note.author ?? ''}`}>
            <span className="note-meta">
              {note.author} · <Ts iso={note.ts} relative />
            </span>
            <p className="note-body">{note.body}</p>
          </li>
        ))}
      </ul>
      <form onSubmit={submit}>
        <textarea
          value={draft}
          maxLength={10000}
          placeholder="e.g. do NOT retry — double-books; tax-service fix ETA 9am"
          aria-label="new note"
          onChange={(event) => {
            setDraft(event.target.value)
          }}
        />
        {create.isError && (
          <p className="signin-error" role="alert">
            {create.error instanceof ApiError && create.error.status === 403
              ? 'Adding notes requires the RESPONDER role on this engine.'
              : create.error.message}
          </p>
        )}
        <button type="submit" disabled={create.isPending || draft.trim() === ''}>
          {create.isPending ? 'Adding…' : 'Add note'}
        </button>
      </form>
    </section>
  )
}
