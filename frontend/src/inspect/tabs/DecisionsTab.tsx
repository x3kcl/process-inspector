import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../../api/client'
import type { components } from '../../api/schema'
import { useEngines } from '../../api/useEngines'

type Page = components['schemas']['DecisionPage']

interface Props {
  engineId: string
  instanceId: string
}

export default function DecisionsTab({ engineId, instanceId }: Props) {
  const engines = useEngines()
  const engine = (engines.data ?? []).find((candidate) => candidate.id === engineId)
  const dmn = engine?.capabilities?.dmnHistory === true
  const query = useQuery({
    queryKey: ['instance', engineId, instanceId, 'decisions'],
    enabled: dmn,
    queryFn: async (): Promise<Page> => {
      const { data, error, response } = await api.GET(
        '/api/instances/{engineId}/{instanceId}/decisions',
        { params: { path: { engineId, instanceId } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  if (!dmn) {
    return <div className="zero-state">No DMN engine on this REST app.</div>
  }
  if (query.isPending) return <div className="zero-state">Loading decisions…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Decisions unavailable: {query.error.message}
      </div>
    )
  }
  if (query.data.available === false) {
    return <div className="zero-state">No DMN engine on this REST app.</div>
  }
  const rows = query.data.rows ?? []
  if (rows.length === 0) return <div className="zero-state">No decisions fired on this case.</div>
  return (
    <div>
      {query.data.capped && <p>Lower bound. More decisions exist than this page shows.</p>}
      <table>
        <thead>
          <tr>
            <th>Decision</th>
            <th>Activity</th>
            <th>Failed</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <DecisionRow
              key={row.id}
              engineId={engineId}
              instanceId={instanceId}
              id={row.id ?? ''}
              decisionKey={row.decisionKey}
              activityId={row.activityId}
              failed={row.failed === true}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DecisionRow({
  engineId,
  instanceId,
  id,
  decisionKey,
  activityId,
  failed,
}: {
  engineId: string
  instanceId: string
  id: string
  decisionKey?: string
  activityId?: string
  failed: boolean
}) {
  const [open, setOpen] = useState(false)
  const audit = useQuery({
    queryKey: ['instance', engineId, instanceId, 'decision-audit', id],
    enabled: open && id !== '',
    queryFn: async () => {
      const { data, error, response } = await api.GET(
        '/api/instances/{engineId}/{instanceId}/decisions/{decisionExecutionId}/audit',
        { params: { path: { engineId, instanceId, decisionExecutionId: id } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  return (
    <>
      <tr>
        <td>{decisionKey ?? id}</td>
        <td>{activityId}</td>
        <td>{failed ? 'failed' : ''}</td>
        <td>
          <button
            type="button"
            onClick={() => {
              setOpen((current) => !current)
            }}
          >
            Audit
          </button>
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={4}>
            <pre>{audit.data === undefined ? 'Loading…' : JSON.stringify(audit.data, null, 2)}</pre>
          </td>
        </tr>
      )}
    </>
  )
}
