// Cohort migrate (#482). Preview the running count, then migrate each instance.
// By-definition batch is not used — it cannot filter. Cap is the server's.
import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useProdGuard } from '../actions/guard'
import { ApiError, api } from '../api/client'
import type { components } from '../api/schema'
import { useEngines } from '../api/useEngines'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'

type Preview = components['schemas']['CohortPreviewResponse']
type Executed = components['schemas']['CohortExecuteResponse']
type Version = { definitionId?: string; version?: number; latest?: boolean }

interface Props {
  engineId: string
  fromDefinitionId: string
  versions: Version[]
  onClose: () => void
}

export function CohortMigrateModal({ engineId, fromDefinitionId, versions, onClose }: Props) {
  const engines = useEngines()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const targets = versions.filter((version) => version.definitionId !== fromDefinitionId)
  const [toDefinitionId, setToDefinitionId] = useState(
    targets.find((v) => v.latest)?.definitionId ?? targets[0]?.definitionId ?? '',
  )
  const [businessKey, setBusinessKey] = useState('')
  const [contains, setContains] = useState(false)
  const [preview, setPreview] = useState<Preview | undefined>(undefined)
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: preview?.count != null ? String(preview.count) : undefined,
  })

  const runPreview = useMutation({
    retry: false,
    mutationFn: async (): Promise<Preview> => {
      const { data, error, response } = await api.POST('/api/migration/cohorts/preview', {
        params: { query: { engineId } },
        body: {
          fromDefinitionId,
          toDefinitionId,
          businessKey: businessKey.trim() === '' ? undefined : businessKey.trim(),
          businessKeyContains: contains,
        },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    onSuccess: (data) => {
      setPreview(data)
    },
  })

  const runExecute = useMutation({
    retry: false,
    mutationFn: async (): Promise<Executed> => {
      const { data, error, response } = await api.POST('/api/migration/cohorts/execute', {
        params: { query: { engineId } },
        body: {
          cohort: {
            fromDefinitionId,
            toDefinitionId,
            businessKey: businessKey.trim() === '' ? undefined : businessKey.trim(),
            businessKeyContains: contains,
          },
          reason: guard.reason.trim(),
          confirmToken: guard.needsToken ? guard.typed : undefined,
          expectedCount: preview?.count,
        },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
  })

  const blocked = preview?.truncated === true
  return (
    <ModalShell
      title="Migrate running instances"
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            disabled={toDefinitionId === '' || runPreview.isPending}
            onClick={() => {
              setPreview(undefined)
              runPreview.mutate()
            }}
          >
            Preview count
          </button>
          <button
            type="button"
            className="primary"
            disabled={
              preview == null ||
              blocked ||
              !guard.reasonOk ||
              !guard.tokenOk ||
              runExecute.isPending
            }
            onClick={() => {
              runExecute.mutate()
            }}
          >
            Migrate
          </button>
        </>
      }
    >
      <p>
        Each running instance of this version is migrated on its own. Protected instances are
        skipped. This is an inspector estimate until the engine accepts the call.
      </p>
      <label className="modal-field">
        Target version
        <select
          aria-label="Target version"
          value={toDefinitionId}
          onChange={(event) => {
            setToDefinitionId(event.target.value)
            setPreview(undefined)
          }}
        >
          {targets.map((version) => (
            <option key={version.definitionId} value={version.definitionId}>
              v{String(version.version ?? '?')}
              {version.latest === true ? ' latest' : ''}
            </option>
          ))}
        </select>
      </label>
      <label className="modal-field">
        Business key (optional)
        <input
          aria-label="Business key"
          value={businessKey}
          onChange={(event) => {
            setBusinessKey(event.target.value)
            setPreview(undefined)
          }}
        />
      </label>
      <label className="modal-field">
        <input
          type="checkbox"
          checked={contains}
          onChange={(event) => {
            setContains(event.target.checked)
            setPreview(undefined)
          }}
        />{' '}
        Key contains this text
      </label>
      {runPreview.isError && <p role="alert">{runPreview.error.message}</p>}
      {preview != null && (
        <p role="status">
          {String(preview.count ?? 0)} running instance{(preview.count ?? 0) === 1 ? '' : 's'}
          {(preview.protectedCount ?? 0) > 0 &&
            ` · ${String(preview.protectedCount)} protected (skipped)`}
          {blocked && ' · too many for this door — narrow the filter'}
        </p>
      )}
      <GuardFields
        guard={guard}
        expectedToken={preview?.count != null ? String(preview.count) : undefined}
      />
      {runExecute.isError && <p role="alert">{runExecute.error.message}</p>}
      {runExecute.data != null && (
        <ul>
          {(runExecute.data.items ?? []).map((item) => (
            <li key={item.instanceId}>
              <code>{item.instanceId}</code> {item.outcome}
              {item.detail != null && item.detail !== '' ? ` — ${item.detail}` : ''}
            </li>
          ))}
        </ul>
      )}
    </ModalShell>
  )
}
