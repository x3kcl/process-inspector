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
import { useMe } from '../api/me'
import { useTicketUrlTemplate } from '../api/meta'
import type { AuditEntryDto } from '../api/model'
import { AttributionCaveat } from '../components/AttributionCaveat'
import { CopyButton } from '../components/CopyButton'
import { Ts } from '../lib/Ts'
import { ticketHref } from '../lib/ticket'
import { downloadOperationsCsv } from './exportCsv'
import { auditOutcomeView } from './outcome'
import { buildShiftReport, shiftStartIso } from './shiftReport'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-quartz.css'

interface Filters {
  actor: string
  action: string
  ticketId: string
  since: string
}

const EMPTY: Filters = { actor: '', action: '', ticketId: '', since: '' }

async function fetchOperationsLog(filters: Filters): Promise<AuditEntryDto[]> {
  const query: Record<string, string | number> = { limit: 500 }
  if (filters.actor.trim() !== '') query['actor'] = filters.actor.trim()
  if (filters.action.trim() !== '') query['action'] = filters.action.trim()
  if (filters.ticketId.trim() !== '') query['ticketId'] = filters.ticketId.trim()
  if (filters.since.trim() !== '') query['since'] = filters.since.trim()
  const { data, error, response } = await api.GET('/api/audit', { params: { query } })
  if (data === undefined) throw new ApiError(response.status, error)
  return data
}

export function AuditLogPage() {
  const [draft, setDraft] = useState<Filters>(EMPTY)
  const [applied, setApplied] = useState<Filters>(EMPTY)
  const [exportError, setExportError] = useState<string | null>(null)
  const ticketTemplate = useTicketUrlTemplate()
  const me = useMe()
  const log = useQuery({
    queryKey: ['operations-log', applied],
    queryFn: () => fetchOperationsLog(applied),
  })

  const columns = useMemo<ColDef<AuditEntryDto>[]>(
    () => [
      // R-UXQ-03: absolute + zone token + relative age; the full UTC ISO rides the
      // native title/aria via <Ts> (which also self-refreshes on the UTC toggle).
      {
        headerName: 'When',
        field: 'ts',
        width: 230,
        sort: 'desc',
        cellRenderer: (p: CustomCellRendererProps<AuditEntryDto>) => (
          <Ts iso={p.data?.ts} relative />
        ),
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
            <Link
              to={`/inspect/${entry.engineId ?? ''}/${encodeURIComponent(entry.instanceId)}?tab=audit`}
            >
              <code>{`${entry.engineId ?? '?'}:${entry.instanceId}`}</code>
            </Link>
          )
        },
      },
      {
        headerName: 'Outcome',
        field: 'outcome',
        width: 150,
        // Theme T8 / R-UXQ-05: a VERDICT WORD from the shared outcome vocabulary — never
        // raw store internals like "ok · null" (httpStatus arrives as a wire null).
        cellRenderer: (p: CustomCellRendererProps<AuditEntryDto>) => {
          if (p.data === undefined) return null
          const view = auditOutcomeView(p.data.action, p.data.outcome, p.data.httpStatus)
          return (
            <span className={`outcome ${view.className}`} title={view.title}>
              {view.label}
            </span>
          )
        },
      },
      { headerName: 'Reason', field: 'reason', flex: 1, minWidth: 200, tooltipField: 'reason' },
      {
        headerName: 'Ticket',
        field: 'ticketId',
        width: 140,
        cellRenderer: (p: CustomCellRendererProps<AuditEntryDto>) => {
          const id = p.data?.ticketId
          if (id === undefined || id === '') return null
          const href = ticketHref(ticketTemplate, id)
          return href !== null ? (
            <a href={href} target="_blank" rel="noopener noreferrer">
              {id}
            </a>
          ) : (
            <span>{id}</span>
          )
        },
      },
    ],
    [ticketTemplate],
  )

  const apply = (event: FormEvent) => {
    event.preventDefault()
    setApplied(draft)
  }

  // R-AUD-05: "my activity, this shift" — the signed-in user since shift start (last 8h).
  const applyMyShift = () => {
    const preset: Filters = { ...EMPTY, actor: me.data?.username ?? '', since: shiftStartIso() }
    setDraft(preset)
    setApplied(preset)
  }

  // R-AUD-08: server-side streaming export of the APPLIED filters (what the grid shows) —
  // through the api client (auth middleware), never a bare href.
  const exportCsv = () => {
    setExportError(null)
    downloadOperationsCsv(applied).catch((cause: unknown) => {
      setExportError(cause instanceof Error ? cause.message : 'export failed')
    })
  }

  return (
    <main className="ops-log-page">
      <header className="ops-log-header">
        <h2>Operations log</h2>
        {/* R-AUD-09: the engine-side history blames the shared service account — warn HERE,
            on the surface people trust for WHO, not only on the per-instance tab. */}
        <AttributionCaveat />
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
            Ticket
            <input
              type="text"
              value={draft.ticketId}
              placeholder="OPS-42"
              onChange={(e) => {
                setDraft({ ...draft, ticketId: e.target.value })
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
          <button
            type="button"
            className="copy-btn"
            title="filter to your own actions since shift start (last 8 hours)"
            onClick={applyMyShift}
          >
            My shift
          </button>
          <button
            type="button"
            className="copy-btn"
            title="download the applied filters as CSV — newest 10,000 rows, formula-escaped"
            onClick={exportCsv}
          >
            Export CSV
          </button>
          {log.data !== undefined && (
            <CopyButton
              label="Copy shift report"
              text={buildShiftReport(log.data, {
                actor: applied.actor !== '' ? applied.actor : (me.data?.username ?? ''),
                sinceIso: applied.since,
                nowMs: Date.now(),
              })}
            />
          )}
        </form>
      </header>
      {exportError !== null && (
        <div className="error-banner" role="alert">
          CSV export failed: {exportError}
        </div>
      )}
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
