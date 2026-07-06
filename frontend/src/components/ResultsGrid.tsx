import { useMemo } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { ColDef, SelectionChangedEvent } from 'ag-grid-community'
import type { ProcessInstanceRow, SearchResponse } from '../types'
import 'ag-grid-community/styles/ag-grid.css'
import 'ag-grid-community/styles/ag-theme-quartz.css'

interface Props {
  result: SearchResponse | null
  onSelectionChanged: (rows: ProcessInstanceRow[]) => void
  onOpenDetails: (row: ProcessInstanceRow) => void
}

/** Spec §3.B — snapshot grid with engine badge, status chip, bulk checkboxes. */
export function ResultsGrid({ result, onSelectionChanged, onOpenDetails }: Props) {
  const columns = useMemo<ColDef<ProcessInstanceRow>[]>(
    () => [
      { checkboxSelection: true, headerCheckboxSelection: true, width: 46, pinned: 'left' },
      {
        headerName: 'Engine',
        field: 'engineName',
        width: 170,
        cellRenderer: (p: { data?: ProcessInstanceRow }) =>
          p.data ? (
            <span>
              <span className="engine-dot" style={{ background: p.data.engineColor }} /> {p.data.engineName}
            </span>
          ) : null,
      },
      { headerName: 'Process ID', field: 'processInstanceId', width: 140 },
      { headerName: 'Business Key', field: 'businessKey', width: 160 },
      {
        headerName: 'Status',
        field: 'status',
        width: 120,
        cellRenderer: (p: { value?: string }) =>
          p.value ? <span className={`status-chip ${p.value.toLowerCase()}`}>{p.value}</span> : null,
      },
      { headerName: 'Definition', field: 'processDefinitionKey', width: 160 },
      { headerName: 'Start Time', field: 'startTime', width: 190, sort: 'desc' },
      {
        headerName: 'Current Activity / Error',
        field: 'currentActivityOrError',
        flex: 1,
        tooltipField: 'currentActivityOrError',
      },
    ],
    [],
  )

  const failedEngines = Object.entries(result?.perEngine ?? {}).filter(([, r]) => !r.ok)

  return (
    <div className="results-panel">
      {failedEngines.length > 0 && (
        <div className="engine-errors">
          ⚠ Partial results — unreachable:{' '}
          {failedEngines.map(([id, r]) => `${id} (${r.error})`).join(', ')}
        </div>
      )}
      <div className="ag-theme-quartz" style={{ height: '100%', width: '100%' }}>
        <AgGridReact<ProcessInstanceRow>
          rowData={result?.rows ?? []}
          columnDefs={columns}
          rowSelection="multiple"
          suppressRowClickSelection
          getRowId={(p) => p.data.compositeId}
          onSelectionChanged={(e: SelectionChangedEvent<ProcessInstanceRow>) =>
            onSelectionChanged(e.api.getSelectedRows())
          }
          onRowDoubleClicked={(e) => e.data && onOpenDetails(e.data)}
        />
      </div>
    </div>
  )
}
