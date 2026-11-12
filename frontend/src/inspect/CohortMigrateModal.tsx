// Cohort migrate (#482). Count → analyze (the §14 estimate per instance) → one bulk job.
// The job migrates the analysis's executable set one instance at a time; blockers are never
// sent and each item re-checks the digest the analysis recorded. By-definition batch is not
// used — it cannot filter.
import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { ApiError, api } from '../api/client'
import { useSubmitBulkMigrate } from '../api/bulk'
import type { components } from '../api/schema'
import { useEngines } from '../api/useEngines'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'

type Preview = components['schemas']['CohortPreviewResponse']
type Analysis = components['schemas']['CohortAnalysisView']
type Version = { definitionId?: string; version?: number; latest?: boolean }

interface Props {
  engineId: string
  fromDefinitionId: string
  versions: Version[]
  onClose: () => void
}

export function CohortMigrateModal({ engineId, fromDefinitionId, versions, onClose }: Props) {
  const engines = useEngines()
  const toast = useToast()
  const drawer = useOpsDrawer()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const targets = versions.filter((version) => version.definitionId !== fromDefinitionId)
  const [toDefinitionId, setToDefinitionId] = useState(
    targets.find((v) => v.latest)?.definitionId ?? targets[0]?.definitionId ?? '',
  )
  const [businessKey, setBusinessKey] = useState('')
  const [contains, setContains] = useState(false)
  const [preview, setPreview] = useState<Preview | undefined>(undefined)
  const [analysisId, setAnalysisId] = useState<string | undefined>(undefined)

  const filter = {
    fromDefinitionId,
    toDefinitionId,
    businessKey: businessKey.trim() === '' ? undefined : businessKey.trim(),
    businessKeyContains: contains,
  }
  const reset = () => {
    setPreview(undefined)
    setAnalysisId(undefined)
  }

  const runPreview = useMutation({
    retry: false,
    mutationFn: async (): Promise<Preview> => {
      const { data, error, response } = await api.POST('/api/migration/cohorts/preview', {
        params: { query: { engineId } },
        body: filter,
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    onSuccess: setPreview,
  })

  const startAnalysis = useMutation({
    retry: false,
    mutationFn: async (): Promise<Analysis> => {
      const { data, error, response } = await api.POST('/api/migration/analyses', {
        params: { query: { engineId } },
        body: filter,
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    onSuccess: (data) => {
      setAnalysisId(data.id)
    },
  })

  // The analysis runs in the background; poll the stored row until it settles.
  const analysis = useQuery({
    queryKey: ['migration-analysis', analysisId],
    enabled: analysisId !== undefined,
    queryFn: async (): Promise<Analysis> => {
      const { data, error, response } = await api.GET('/api/migration/analyses/{id}', {
        params: { path: { id: analysisId ?? '' } },
      })
      if (data === undefined) throw new ApiError(response.status, error)
      return data
    },
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? 1000 : false),
  })

  const done = analysis.data?.status === 'COMPLETED' ? analysis.data : undefined
  const executable = done?.executableIds?.length ?? 0
  const partial = done?.lowerBound === true
  const guard = useProdGuard({
    reasonRule: { required: true, minLength: 10 },
    environment,
    expectedToken: done !== undefined ? String(executable) : undefined,
  })

  const submit = useSubmitBulkMigrate()
  const migrate = () => {
    if (done?.id === undefined) return
    submit.mutate(
      {
        analysisIds: [done.id],
        reason: guard.reason.trim(),
        ticketId: ticketValue(guard.ticket),
        confirmedCount: guard.needsToken ? Number(guard.typed) : undefined,
      },
      {
        onSuccess: (job) => {
          toast({
            kind: 'success',
            text: `Migration job started over ${String(job.totalItems ?? 0)} instance${
              job.totalItems === 1 ? '' : 's'
            }. Progress in the operations drawer.`,
          })
          if (job.id !== undefined) drawer.focusJob(job.id)
          onClose()
        },
      },
    )
  }

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
              reset()
              runPreview.mutate()
            }}
          >
            Preview count
          </button>
          <button
            type="button"
            disabled={preview == null || blocked || startAnalysis.isPending || analysisId != null}
            onClick={() => {
              startAnalysis.mutate()
            }}
          >
            Analyze
          </button>
          <button
            type="button"
            className="primary"
            disabled={
              done === undefined ||
              partial ||
              executable === 0 ||
              !guard.reasonOk ||
              !guard.tokenOk ||
              submit.isPending
            }
            onClick={migrate}
          >
            Migrate {executable > 0 ? String(executable) : ''}
          </button>
        </>
      }
    >
      <p>
        Each running instance of this version is migrated on its own, as a tracked bulk job.
        Instances with a blocker are never sent; protected instances are skipped; an instance that
        changed since the analysis is skipped.
      </p>
      <label className="modal-field">
        Target version
        <select
          aria-label="Target version"
          value={toDefinitionId}
          onChange={(event) => {
            setToDefinitionId(event.target.value)
            reset()
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
            reset()
          }}
        />
      </label>
      <label className="modal-field">
        <input
          type="checkbox"
          checked={contains}
          onChange={(event) => {
            setContains(event.target.checked)
            reset()
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
          {blocked && ` · more than ${String(preview.cap ?? '')} — narrow the filter`}
        </p>
      )}
      {startAnalysis.isError && <p role="alert">{startAnalysis.error.message}</p>}
      {analysis.isError && <p role="alert">{analysis.error.message}</p>}
      {analysis.data?.status === 'RUNNING' && <p role="status">Analyzing…</p>}
      {analysis.data?.status === 'FAILED' && (
        <p role="alert">Analysis failed: {analysis.data.failure ?? 'unknown error'}</p>
      )}
      {done !== undefined && (
        <div role="status">
          <p>
            {String(executable)} executable · {String(done.blockerCount ?? 0)} with a blocker (never
            sent) · {String(done.warningCount ?? 0)} with warnings
          </p>
          {partial && <p role="alert">{done.boundLabel} — narrow the filter and analyze again.</p>}
          <p className="strip-note">{done.banner}</p>
        </div>
      )}
      <GuardFields
        guard={guard}
        expectedToken={done !== undefined ? String(executable) : undefined}
      />
      <TicketField value={guard.ticket} onChange={guard.setTicket} />
      {submit.isError && <p role="alert">{problemBanner(submit.error.problem)}</p>}
    </ModalShell>
  )
}
