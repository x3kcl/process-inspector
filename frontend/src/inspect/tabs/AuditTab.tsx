import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { createInstanceNote, fetchInstanceAudit, fetchInstanceNotes } from '../../api/queries'
import { formatDateTime } from '../../lib/format'

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
      <AuditLog engineId={engineId} instanceId={instanceId} />
      <Notes engineId={engineId} instanceId={instanceId} />
    </div>
  )
}

function AuditLog({ engineId, instanceId }: Props) {
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
    return <div className="zero-state">No corrective actions recorded for this instance.</div>
  }
  return (
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
        {audit.data.map((entry) => (
          <tr key={entry.id ?? `${entry.ts ?? ''}${entry.action ?? ''}`}>
            <td>{formatDateTime(entry.ts)}</td>
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
              <span className={`outcome outcome-${(entry.outcome ?? 'unknown').toLowerCase()}`}>
                {entry.outcome ?? 'UNKNOWN'}
              </span>
              {entry.httpStatus !== undefined && ` · ${String(entry.httpStatus)}`}
            </td>
            <td className="audit-reason">
              {entry.reason}
              {entry.ticketId !== undefined && <code className="ticket"> {entry.ticketId}</code>}
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
        ))}
      </tbody>
    </table>
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
              {note.author} · {formatDateTime(note.ts)}
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
