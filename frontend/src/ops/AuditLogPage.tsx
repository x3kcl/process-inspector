// The global operations log (SPEC §9, M4 straggler): every corrective action across all
// engines and instances, Postgres-truth, filterable by actor / action / time window.
// Read-only — the per-instance Audit & Notes tab stays the handover surface.
import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { CustomCellRendererProps } from 'ag-grid-react'
import type { ColDef } from 'ag-grid-community'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api, ApiError } from '../api/client'
import type { AuditEntryDto } from '../api/model'
import { formatDateTime } from '../lib/format'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-quartz.css'

interface Filters {
  actor: string
  action: string
  since: string
}

const EMPTY: Filters = { actor: '', action: '', since: '' }

async function fetchOperationsLog(filters: Filters): Promise<AuditEntryDto[]> {
  const query: Record<string, string | number> = { limit: 500 }
  if (filters.actor.trim() !== '') query['actor'] = filters.actor.trim()
  if (filters.action.trim() !== '') query['action'] = filters.action.trim()
  if (filters.since.trim() !== '') query['since'] = filters.since.trim()
  const { data, error, response } = await api.GET('/api/audit', { params: { query } })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export function AuditLogPage() {
  const [draft, setDraft] = useState<Filters>(EMPTY)
  const [applied, setApplied] = useState<Filters>(EMPTY)
  const log = useQuery({
    queryKey: ['operations-log', applied],
    queryFn: () => fetchOperationsLog(applied),
  })

  const columns = useMemo<ColDef<AuditEntryDto>[]>(
    () => [
      {
        headerName: 'When',
        field: 'ts',
        width: 180,
        sort: 'desc',
        valueFormatter: (p) => formatDateTime(p.value as string | undefined),
        tooltipValueGetter: (p) => p.data?.ts ?? null,
      },
      { headerName: 'Actor', field: 'actor', width: 130 },
      {
        headerName: 'Action',
        field: 'action',
        width: 170,
        cellRenderer: (p: CustomCellRendererProps<AuditEntryDto>) =>
          p.data === undefined ? null : (
            <span>
              <code>{p.data.action}</code>
              {p.data.breakGlass === true && (
                <span className="status-badge" title="executed under break-glass">
                  break-glass
                </span>
              )}
            </span>
          ),
      },
      {
        headerName: 'Target',
        colId: 'target',
        width: 260,
        cellRenderer: (p: CustomCellRendererProps<AuditEntryDto>) => {
          const entry = p.data
          if (entry === undefined) return null
          // Bulk envelope rows carry no instanceId (wire null, typed undefined).
          if (typeof entry.instanceId !== 'string' || entry.instanceId === '') {
            return <code>{entry.engineId}</code>
          }
          return (
            <Link to={`/inspect/${entry.engineId ?? ''}/${encodeURIComponent(entry.instanceId)}?tab=audit`}>
              <code>{`${entry.engineId ?? '?'}:${entry.instanceId}`}</code>
            </Link>
          )
        },
      },
      {
        headerName: 'Outcome',
        field: 'outcome',
        width: 130,
        cellRenderer: (p: CustomCellRendererProps<AuditEntryDto>) =>
          p.data === undefined ? null : (
            <span className={`outcome outcome-${(p.data.outcome ?? 'unknown').toLowerCase()}`}>
              {p.data.outcome ?? 'UNKNOWN'}
              {p.data.httpStatus !== undefined && ` · ${String(p.data.httpStatus)}`}
            </span>
          ),
      },
      { headerName: 'Reason', field: 'reason', flex: 1, minWidth: 200, tooltipField: 'reason' },
      { headerName: 'Ticket', field: 'ticketId', width: 120 },
    ],
    [],
  )

  const apply = (event: FormEvent) => {
    event.preventDefault()
    setApplied(draft)
  }

  return (
    <main className="ops-log-page">
      <header className="ops-log-header">
        <h2>Operations log</h2>
        <form className="ops-log-filters" onSubmit={apply}>
          <label>
            Actor
            <input
              type="text"
              value={draft.actor}
              placeholder="k.meier"
              onChange={(e) => {
                setDraft({ ...draft, actor: e.target.value })
              }}
            />
          </label>
          <label>
            Action
            <input
              type="text"
              value={draft.action}
              placeholder="retry-job / edit-variable / bulk:…"
              onChange={(e) => {
                setDraft({ ...draft, action: e.target.value })
              }}
            />
          </label>
          <label>
            Since (ISO)
            <input
              type="text"
              value={draft.since}
              placeholder="2026-07-06T00:00:00Z"
              onChange={(e) => {
                setDraft({ ...draft, since: e.target.value })
              }}
            />
          </label>
          <button type="submit" className="primary ops-log-apply">
            Apply
          </button>
        </form>
      </header>
      {log.isPending && <div className="zero-state">Loading the operations log…</div>}
      {log.isError && (
        <div className="error-banner" role="alert">
          Operations log unavailable: {log.error.message}
        </div>
      )}
      {log.data !== undefined &&
        (log.data.length === 0 ? (
          <div className="zero-state">No audited actions match these filters.</div>
        ) : (
          <div className="ag-theme-quartz grid-host ops-log-grid">
            <AgGridReact<AuditEntryDto>
              rowData={log.data}
              columnDefs={columns}
              getRowId={(p) => p.data.id ?? `${p.data.ts ?? ''}${p.data.action ?? ''}`}
              tooltipShowDelay={300}
            />
          </div>
        ))}
    </main>
  )
}
