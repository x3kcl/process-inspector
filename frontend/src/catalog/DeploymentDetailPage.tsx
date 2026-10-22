// Deployment detail and the tier-3 delete verb. Cascade stays off unless the
// operator sets it. A count that could not be proved refuses the delete server-side.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useProdGuard } from '../actions/guard'
import { api, ApiError } from '../api/client'
import { roleOn, useMe } from '../api/me'
import type { components } from '../api/schema'
import { useEngines } from '../api/useEngines'
import { ActionHint } from '../components/ActionHint'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'

type Detail = components['schemas']['DeploymentDetailResponse']

export function DeploymentDetailPage() {
  const { engineId = '', deploymentId = '' } = useParams()
  const id = decodeURIComponent(deploymentId)
  const [open, setOpen] = useState(false)
  const me = useMe()
  const admin = roleOn(me.data, engineId) === 'ADMIN'
  const detail = useQuery({
    queryKey: ['deployment', engineId, id],
    queryFn: async (): Promise<Detail> => {
      const { data, error, response } = await api.GET(
        '/api/deployments/{engineId}/{deploymentId}',
        {
          params: { path: { engineId, deploymentId: id } },
        },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const row = detail.data
  const count =
    row == null
      ? ''
      : row.runningCountBounded === true
        ? `at least ${String(row.runningCount ?? 0)}`
        : String(row.runningCount ?? 0)
  return (
    <section>
      <p>
        <Link to="/deployments">Deployments</Link>
      </p>
      <h1>{row?.name ?? id}</h1>
      {detail.isError && <p role="alert">{detail.error.message}</p>}
      {row != null && (
        <>
          <p className="muted">
            {engineId} · {row.deploymentTime ?? 'time unknown'} · {count} running instance
            {row.runningCount === 1 ? '' : 's'}
            {row.runningCountBounded === true &&
              ' (lower bound — delete will refuse until the count is proved)'}
          </p>
          <p>
            <button
              type="button"
              disabled={!admin}
              onClick={() => {
                setOpen(true)
              }}
            >
              Delete deployment…
            </button>
            {!admin && <ActionHint text="Deleting a deployment needs ADMIN on this engine." />}
          </p>
          <h2>Definitions</h2>
          <table className="catalog-table versions-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Version</th>
                <th>Running</th>
              </tr>
            </thead>
            <tbody>
              {(row.definitions ?? []).map((definition) => (
                <tr key={definition.definitionId}>
                  <td>
                    <Link
                      to={`/definitions/${engineId}/${encodeURIComponent(definition.definitionId ?? '')}`}
                    >
                      {definition.key}
                    </Link>
                  </td>
                  <td>v{String(definition.version ?? '?')}</td>
                  <td>
                    {definition.runningCount == null ? 'unknown' : String(definition.runningCount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {(row.resources ?? []).length > 0 && (
            <>
              <h2>Resources</h2>
              <ul>
                {(row.resources ?? []).map((resource) => (
                  <li key={resource}>
                    <code>{resource}</code>
                  </li>
                ))}
              </ul>
            </>
          )}
        </>
      )}
      {open && (
        <DeleteModal
          engineId={engineId}
          deploymentId={id}
          running={row?.runningCount ?? 0}
          bounded={row?.runningCountBounded === true}
          onClose={() => {
            setOpen(false)
          }}
        />
      )}
    </section>
  )
}

function DeleteModal({
  engineId,
  deploymentId,
  running,
  bounded,
  onClose,
}: {
  engineId: string
  deploymentId: string
  running: number
  bounded: boolean
  onClose: () => void
}) {
  const engines = useEngines()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: deploymentId,
  })
  const [cascade, setCascade] = useState(false)
  const client = useQueryClient()
  const action = useMutation({
    retry: false,
    mutationFn: async () => {
      const { data, error, response } = await api.POST(
        '/api/deployments/{engineId}/{deploymentId}/delete',
        {
          params: { path: { engineId, deploymentId } },
          body: {
            reason: guard.reason.trim(),
            ticketId: guard.ticket || undefined,
            confirmToken: guard.needsToken ? guard.typed : undefined,
            cascade,
          },
        },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['deployments'] })
      await client.invalidateQueries({ queryKey: ['deployment', engineId, deploymentId] })
      onClose()
    },
  })
  return (
    <ModalShell
      title="Delete this deployment"
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            className="primary"
            disabled={!guard.reasonOk || !guard.tokenOk || action.isPending || bounded}
            onClick={() => {
              action.mutate()
            }}
          >
            Delete deployment
          </button>
        </>
      }
    >
      <p>
        {bounded
          ? 'The running-instance count could not be proved. Delete stays disabled.'
          : running > 0
            ? `This deployment still has ${String(running)} running instance(s). Cascade is off, so the server will refuse unless you turn it on. Cascade also deletes those instances. Irreversible.`
            : 'No running instances were counted. Deleting the deployment is irreversible.'}
      </p>
      <label className="modal-field">
        <input
          type="checkbox"
          checked={cascade}
          onChange={(event) => {
            setCascade(event.target.checked)
          }}
        />{' '}
        Cascade — also delete running instances
      </label>
      <GuardFields guard={guard} expectedToken={deploymentId} />
      {action.isError && <p role="alert">{action.error.message}</p>}
      {action.data?.outcome === 'unknown' && <p role="alert">{action.data.detail}</p>}
    </ModalShell>
  )
}
