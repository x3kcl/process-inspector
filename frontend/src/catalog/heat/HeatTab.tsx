// Heat tab of the definition page (#785, DEFINITION-HEATMAP.md §5): per-version heat badges on
// the model, the node table, the outcome funnel, and the honesty text. One gather per
// (version, N); Refresh asks the server, which throttles — its answer is shown as given.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, ApiError } from '../../api/client'
import { DiagramCanvas } from '../../inspect/DiagramCanvas'
import { formatDateTime, formatDurationMs } from '../../lib/format'
import {
  badgeText,
  HEAT_PRESETS,
  heatMarkers,
  honestyLines,
  refusalMessage,
  type Heat,
  type NodeHeat,
} from './heatModel'

interface Props {
  engineId: string
  currentId: string
  versions: { definitionId?: string; version?: number; latest?: boolean }[]
}

const pct = (value: number | undefined) => `${String(Number((value ?? 0).toFixed(1)))} %`

export function HeatTab({ engineId, currentId, versions }: Props) {
  const [versionId, setVersionId] = useState(currentId)
  const [n, setN] = useState(HEAT_PRESETS[0] ?? 200)
  const [selected, setSelected] = useState<string | undefined>()
  const client = useQueryClient()
  const key = ['definition-heat', engineId, versionId, n]
  const fetchHeat = async (refresh: boolean): Promise<Heat> => {
    const { data, error, response } = await api.GET(
      '/api/definitions/{engineId}/{definitionId}/heat',
      {
        params: {
          path: { engineId, definitionId: versionId },
          query: refresh ? { n, refresh: true } : { n },
        },
      },
    )
    if (data === undefined) throw new ApiError(response.status, error)
    return data
  }
  const heat = useQuery({
    queryKey: key,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
    queryFn: () => fetchHeat(false),
  })
  const refresh = useMutation({
    retry: false,
    mutationFn: () => fetchHeat(true),
    onSuccess: (data) => {
      client.setQueryData(key, data)
    },
  })
  const xml = useQuery({
    queryKey: ['definition-xml', engineId, versionId],
    queryFn: async () => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}/xml',
        { params: { path: { engineId, definitionId: versionId } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const failure = refresh.error ?? heat.error
  const data = heat.data
  const figures = data?.coverage === 'FULL' || data?.coverage === 'NO_FLOW_HISTORY'
  const nodes = data?.nodes ?? []
  const { sets, texts } = heatMarkers(nodes)
  return (
    <div className="heat-tab">
      <div className="heat-controls">
        <label>
          Version{' '}
          <select
            value={versionId}
            onChange={(event) => {
              setVersionId(event.target.value)
              setSelected(undefined)
            }}
          >
            {versions.map((version) => (
              <option key={version.definitionId} value={version.definitionId ?? ''}>
                v{String(version.version ?? '?')}
                {version.latest === true ? ' latest' : ''}
              </option>
            ))}
          </select>
        </label>
        <label>
          Sample size{' '}
          <select
            value={n}
            onChange={(event) => {
              setN(Number(event.target.value))
            }}
          >
            {HEAT_PRESETS.map((preset) => (
              <option key={preset} value={preset}>
                {preset}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          disabled={refresh.isPending}
          onClick={() => {
            refresh.mutate()
          }}
        >
          Refresh
        </button>
      </div>
      {failure != null && <p role="alert">{refusalMessage(failure)}</p>}
      {data?.refreshThrottledUntil != null && (
        <p role="status" className="muted">
          Refresh is throttled by the server — next refresh possible at{' '}
          {formatDateTime(data.refreshThrottledUntil)}.
        </p>
      )}
      {data != null && (
        <div className="heat-honesty">
          {data.partial === true && <span className="heat-partial-badge">partial</span>}
          {honestyLines(data).map((line) => (
            <p key={line} className="muted">
              {line}
            </p>
          ))}
        </div>
      )}
      {data != null && figures && (
        <>
          {xml.data?.xml != null && xml.data.xml !== '' && (
            <DiagramCanvas
              xml={xml.data.xml}
              markers={{ activeActivityIds: [], deadLetterActivityIds: [], ...sets }}
              glyphTexts={texts}
              selectedActivityId={selected}
              onSelectActivity={setSelected}
            />
          )}
          <NodeTable nodes={nodes} selected={selected} onSelect={setSelected} />
          <Funnel data={data} />
        </>
      )}
    </div>
  )
}

function NodeTable({
  nodes,
  selected,
  onSelect,
}: {
  nodes: NodeHeat[]
  selected: string | undefined
  onSelect: (id: string) => void
}) {
  const ms = (value: number | undefined | null) => (value == null ? '—' : formatDurationMs(value))
  return (
    <table className="catalog-table heat-table" aria-label="Node heat">
      <thead>
        <tr>
          <th>Node</th>
          <th>Hit rate</th>
          <th>Average</th>
          <th>p50</th>
          <th>p95</th>
          <th>Armed, not taken</th>
        </tr>
      </thead>
      <tbody>
        {nodes.map((node) => (
          <tr key={node.activityId} aria-selected={selected === node.activityId}>
            <td>
              <button
                type="button"
                className="link-button"
                aria-label={`Show ${node.name ?? node.activityId ?? ''} on diagram`}
                onClick={() => {
                  if (node.activityId !== undefined) onSelect(node.activityId)
                }}
              >
                {node.name ?? node.activityId}
              </button>{' '}
              <span className="muted">{node.type}</span>
            </td>
            <td title={badgeText(node)}>{pct(node.hitPct)}</td>
            <td>{(node.executions ?? 0) > 0 ? ms(node.avgMs) : '—'}</td>
            <td>{(node.executions ?? 0) > 0 ? ms(node.p50Ms) : '—'}</td>
            <td>{(node.executions ?? 0) > 0 ? ms(node.p95Ms) : '—'}</td>
            <td>{String(node.armedNotTaken ?? 0)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Funnel({ data }: { data: Heat }) {
  const rows = data.outcomes ?? []
  const none = data.noEndEventCount ?? 0
  const total = Math.max(1, data.sampledInstances ?? 1)
  const bar = (count: number) => (
    <span
      className="heat-funnel-bar"
      aria-hidden="true"
      style={{ width: `${String((count / total) * 100)}%` }}
    />
  )
  return (
    <>
      <h2>How instances ended</h2>
      <ul className="heat-funnel" aria-label="Outcome funnel">
        {rows.map((row) => (
          <li key={row.endEventId}>
            {row.name ?? row.endEventId} — {row.count} · {pct(row.pct)}
            {bar(row.count ?? 0)}
          </li>
        ))}
        {none > 0 && (
          <li>
            no end event recorded — {none} · {pct((none * 100) / total)}
            {bar(none)}
          </li>
        )}
      </ul>
    </>
  )
}
