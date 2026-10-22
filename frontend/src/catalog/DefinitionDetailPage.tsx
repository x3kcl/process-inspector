// Definition detail (#483 S2–S3, S6): diagram, XML, subscriptions, job counts,
// version diff, and the existing suspend/activate verbs with an optional schedule.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { parseActionProblem } from '../actions/problem'
import { useProdGuard } from '../actions/guard'
import { ActionError } from '../api/actions'
import { api, ApiError } from '../api/client'
import { roleOn, useMe } from '../api/me'
import type { components } from '../api/schema'
import { useEngines } from '../api/useEngines'
import { ActionHint } from '../components/ActionHint'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { DiagramCanvas } from '../inspect/DiagramCanvas'

type Detail = components['schemas']['DefinitionDetailResponse']
type Xml = components['schemas']['DefinitionXmlResponse']
type Subs = components['schemas']['DefinitionSubscriptionsResponse']
type Jobs = components['schemas']['DefinitionJobsResponse']
type Diff = components['schemas']['BpmnDiffResponse']

export function DefinitionDetailPage() {
  const { engineId = '', definitionId = '' } = useParams()
  const id = decodeURIComponent(definitionId)
  const [tab, setTab] = useState<'diagram' | 'xml' | 'subscriptions' | 'jobs' | 'compare'>(
    'diagram',
  )
  const [against, setAgainst] = useState('')
  const [suspend, setSuspend] = useState<'suspend' | 'activate' | null>(null)
  const detail = useQuery({
    queryKey: ['definition', engineId, id],
    queryFn: async (): Promise<Detail> => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}',
        {
          params: { path: { engineId, definitionId: id } },
        },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const xmlId = tab === 'compare' && against !== '' ? against : id
  const xml = useQuery({
    queryKey: ['definition-xml', engineId, xmlId],
    enabled: tab === 'diagram' || tab === 'xml' || tab === 'compare',
    queryFn: async (): Promise<Xml> => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}/xml',
        {
          params: { path: { engineId, definitionId: xmlId } },
        },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const subs = useQuery({
    queryKey: ['definition-subs', engineId, id],
    enabled: tab === 'subscriptions',
    queryFn: async (): Promise<Subs> => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}/subscriptions',
        { params: { path: { engineId, definitionId: id } } },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const jobs = useQuery({
    queryKey: ['definition-jobs', engineId, id],
    enabled: tab === 'jobs',
    queryFn: async (): Promise<Jobs> => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}/jobs',
        {
          params: { path: { engineId, definitionId: id } },
        },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const diff = useQuery({
    queryKey: ['definition-diff', engineId, id, against],
    enabled: tab === 'compare' && against !== '',
    queryFn: async (): Promise<Diff> => {
      const { data, error, response } = await api.GET(
        '/api/definitions/{engineId}/{definitionId}/diff',
        {
          params: { path: { engineId, definitionId: id }, query: { against } },
        },
      )
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })
  const me = useMe()
  const admin = roleOn(me.data, engineId) === 'ADMIN'
  const row = detail.data
  return (
    <section>
      <p>
        <Link to="/definitions">Definitions</Link>
      </p>
      <h1>
        {row?.key ?? id} {row?.version != null && <>v{row.version}</>}
      </h1>
      {detail.isError && <p role="alert">{detail.error.message}</p>}
      {row != null && (
        <p className="muted">
          {row.kind} on {engineId}
          {row.suspended === true ? ' · suspended' : ' · active'}
          {row.deploymentId != null && (
            <>
              {' · '}
              <Link to={`/deployments/${engineId}/${encodeURIComponent(row.deploymentId)}`}>
                deployment {row.deploymentId}
              </Link>
            </>
          )}
        </p>
      )}
      <p>
        <button
          type="button"
          disabled={!admin}
          onClick={() => {
            setSuspend('suspend')
          }}
        >
          Suspend definition…
        </button>{' '}
        <button
          type="button"
          disabled={!admin}
          onClick={() => {
            setSuspend('activate')
          }}
        >
          Activate definition…
        </button>
        {!admin && (
          <ActionHint text="Suspending or activating a definition needs ADMIN on this engine." />
        )}
      </p>
      <div className="tab-bar" role="tablist">
        {(['diagram', 'xml', 'subscriptions', 'jobs', 'compare'] as const).map((name) => (
          <button
            key={name}
            type="button"
            className="tab-button"
            aria-selected={tab === name}
            onClick={() => {
              setTab(name)
            }}
          >
            {name}
          </button>
        ))}
      </div>
      {tab === 'diagram' && <Diagram xml={xml.data?.xml} added={[]} changed={[]} />}
      {tab === 'xml' && <XmlPanel xml={xml.data?.xml} />}
      {tab === 'subscriptions' && (
        <Subscriptions data={subs.data} engineId={engineId} error={subs.error} />
      )}
      {tab === 'jobs' && <JobLanes data={jobs.data} error={jobs.error} />}
      {tab === 'compare' && (
        <Compare
          versions={row?.versions ?? []}
          current={id}
          against={against}
          onAgainst={setAgainst}
          diff={diff.data}
          xml={xml.data?.xml}
          error={diff.error}
        />
      )}
      {suspend != null && row?.key != null && (
        <ScheduleModal
          engineId={engineId}
          definitionId={id}
          definitionKey={row.key}
          verb={suspend}
          onClose={() => {
            setSuspend(null)
          }}
        />
      )}
    </section>
  )
}

function Diagram({
  xml,
  added,
  changed,
}: {
  xml: string | null | undefined
  added: string[]
  changed: string[]
}) {
  if (xml == null || xml === '') return <p>No BPMN diagram for this definition.</p>
  return (
    <DiagramCanvas
      xml={xml}
      markers={{
        activeActivityIds: [],
        deadLetterActivityIds: [],
        addedActivityIds: added,
        changedActivityIds: changed,
      }}
    />
  )
}

function XmlPanel({ xml }: { xml: string | null | undefined }) {
  if (xml == null) return <p>No model XML.</p>
  return (
    <>
      <button
        type="button"
        onClick={() => {
          void navigator.clipboard.writeText(xml)
        }}
      >
        Copy XML
      </button>
      <pre>{xml}</pre>
    </>
  )
}

function Subscriptions({
  data,
  engineId,
  error,
}: {
  data: Subs | undefined
  engineId: string
  error: Error | null
}) {
  if (error != null) return <p role="alert">{error.message}</p>
  if (data?.scope === 'CMMN')
    return <p>Start subscriptions are a BPMN view. This is a case definition.</p>
  return (
    <>
      {data?.complete === false && <p>Subscription list is a lower bound.</p>}
      <table className="catalog-table versions-table">
        <thead>
          <tr>
            <th>Type</th>
            <th>Name</th>
            <th>Activity</th>
            <th>Instance</th>
          </tr>
        </thead>
        <tbody>
          {(data?.subscriptions ?? []).map((row) => (
            <tr key={row.id}>
              <td>
                {row.eventType}
                {row.startSubscription === true && ' · start'}
              </td>
              <td>{row.eventName ?? '—'}</td>
              <td>{row.activityId ?? '—'}</td>
              <td>
                {row.processInstanceId != null ? (
                  <Link to={`/inspect/${engineId}/${encodeURIComponent(row.processInstanceId)}`}>
                    {row.processInstanceId}
                  </Link>
                ) : (
                  '—'
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  )
}

function JobLanes({ data, error }: { data: Jobs | undefined; error: Error | null }) {
  if (error != null) return <p role="alert">{error.message}</p>
  if (data?.scope === 'CMMN')
    return <p>These four lanes are the BPMN job queues. Case jobs are not listed here.</p>
  return (
    <table className="catalog-table versions-table">
      <thead>
        <tr>
          <th>Lane</th>
          <th>Jobs</th>
        </tr>
      </thead>
      <tbody>
        {(data?.lanes ?? []).map((lane) => (
          <tr key={lane.lane}>
            <td>{lane.lane}</td>
            <td>
              {lane.lowerBound === true ? `≥ ${String(lane.total ?? 0)}` : String(lane.total ?? 0)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Compare({
  versions,
  current,
  against,
  onAgainst,
  diff,
  xml,
  error,
}: {
  versions: NonNullable<Detail['versions']>
  current: string
  against: string
  onAgainst: (id: string) => void
  diff: Diff | undefined
  xml: string | null | undefined
  error: Error | null
}) {
  return (
    <>
      <label>
        Compare with{' '}
        <select
          aria-label="Compare with version"
          value={against}
          onChange={(event) => {
            onAgainst(event.target.value)
          }}
        >
          <option value="">Select a version</option>
          {versions
            .filter((version) => version.definitionId !== current)
            .map((version) => (
              <option key={version.definitionId} value={version.definitionId ?? ''}>
                v{String(version.version ?? '?')}
                {version.latest === true ? ' latest' : ''}
              </option>
            ))}
        </select>
      </label>
      <p className="muted">Element diff of the BPMN model. Not an engine migration check.</p>
      {error != null && <p role="alert">{error.message}</p>}
      {diff != null && (
        <>
          <ChangeList title="Added on the compared version" rows={diff.added ?? []} />
          <ChangeList title="Removed from this version" rows={diff.removed ?? []} />
          <ChangeList title="Changed" rows={diff.changed ?? []} />
          <Diagram
            xml={xml}
            added={(diff.added ?? []).map((row) => row.id ?? '').filter((id) => id !== '')}
            changed={(diff.changed ?? []).map((row) => row.id ?? '').filter((id) => id !== '')}
          />
        </>
      )}
    </>
  )
}

function ChangeList({ title, rows }: { title: string; rows: NonNullable<Diff['added']> }) {
  if (rows.length === 0) return null
  return (
    <>
      <h2>{title}</h2>
      <ul>
        {rows.map((row) => (
          <li key={`${title}-${row.id ?? ''}`}>
            <code>{row.id}</code> {row.type}
            {row.name != null && ` “${row.name}”`} — {row.detail}
          </li>
        ))}
      </ul>
    </>
  )
}

function ScheduleModal({
  engineId,
  definitionId,
  definitionKey,
  verb,
  onClose,
}: {
  engineId: string
  definitionId: string
  definitionKey: string
  verb: 'suspend' | 'activate'
  onClose: () => void
}) {
  const engines = useEngines()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: definitionKey,
  })
  const [includeInstances, setIncludeInstances] = useState(false)
  const [when, setWhen] = useState('')
  const client = useQueryClient()
  const action = useMutation({
    retry: false,
    mutationFn: async () => {
      const { data, error, response } = await api.POST(
        '/api/definitions/{engineId}/{definitionId}/actions/{verb}',
        {
          params: { path: { engineId, definitionId, verb: `${verb}-definition` } },
          body: {
            reason: guard.reason.trim(),
            ticketId: guard.ticket || undefined,
            confirmToken: guard.needsToken ? guard.typed : undefined,
            includeProcessInstances: includeInstances,
            effectiveAt: when === '' ? undefined : new Date(when).toISOString(),
          },
        },
      )
      if (data === undefined) throw new ActionError(parseActionProblem(response.status, error))
      return data
    },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['definition', engineId, definitionId] })
      onClose()
    },
  })
  return (
    <ModalShell
      title={verb === 'suspend' ? 'Suspend this definition' : 'Activate this definition'}
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
            disabled={!guard.reasonOk || !guard.tokenOk || action.isPending}
            onClick={() => {
              action.mutate()
            }}
          >
            {verb === 'suspend' ? 'Suspend definition' : 'Activate definition'}
          </button>
        </>
      }
    >
      <p>
        {verb === 'suspend'
          ? 'Stops new instances. Running instances stay unless you include them.'
          : 'Allows new instances again.'}{' '}
        {when !== '' && 'The engine applies this at the chosen time, not now.'}
      </p>
      <label className="modal-field">
        <input
          type="checkbox"
          checked={includeInstances}
          onChange={(event) => {
            setIncludeInstances(event.target.checked)
          }}
        />{' '}
        Include running instances
      </label>
      <label className="modal-field">
        Schedule (optional, your local time)
        <input
          type="datetime-local"
          value={when}
          onChange={(event) => {
            setWhen(event.target.value)
          }}
        />
      </label>
      <GuardFields guard={guard} expectedToken={definitionKey} />
      {action.isError && <p role="alert">{action.error.message}</p>}
    </ModalShell>
  )
}
