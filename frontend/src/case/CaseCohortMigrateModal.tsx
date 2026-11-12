// Case cohort migrate (#500). Count → analyze (the single-case pre-check per case) → one bulk
// job. The job migrates the analysis's executable set one case at a time; a case holding a live
// plan item the target model lacks is a blocker and is never sent, and each item re-checks the
// definition and plan-item state the analysis recorded.
import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import type { ActionError } from '../api/actions'
import {
  fetchCaseAnalysis,
  previewCaseCohort,
  startCaseAnalysis,
  useSubmitBulkMigrateCases,
} from '../api/caseMigration'
import type {
  CaseCohortAnalysisView,
  CaseCohortPreviewResponse,
  CaseDefinitionVersion,
} from '../api/caseMigration'
import type { ApiError } from '../api/client'
import { useEngines } from '../api/useEngines'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'
import { useOpsDrawer } from '../ops/drawerState'

interface Props {
  engineId: string
  fromCaseDefinitionId: string
  versions: CaseDefinitionVersion[]
  /** Set on a pre-7.x engine: the business-key "contains" filter is greyed with this reason. */
  containsGate?: string
  onClose: () => void
}

export function CaseCohortMigrateModal({
  engineId,
  fromCaseDefinitionId,
  versions,
  containsGate,
  onClose,
}: Props) {
  const engines = useEngines()
  const toast = useToast()
  const drawer = useOpsDrawer()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment
  const targets = versions.filter((version) => version.caseDefinitionId !== fromCaseDefinitionId)
  const [toCaseDefinitionId, setToCaseDefinitionId] = useState(
    targets.find((v) => v.latest)?.caseDefinitionId ?? targets[0]?.caseDefinitionId ?? '',
  )
  const [businessKey, setBusinessKey] = useState('')
  const [contains, setContains] = useState(false)
  const [preview, setPreview] = useState<CaseCohortPreviewResponse | undefined>(undefined)
  const [analysisId, setAnalysisId] = useState<string | undefined>(undefined)

  const filter = {
    fromCaseDefinitionId,
    toCaseDefinitionId,
    businessKey: businessKey.trim() === '' ? undefined : businessKey.trim(),
    businessKeyContains: contains,
  }
  const reset = () => {
    setPreview(undefined)
    setAnalysisId(undefined)
  }

  const runPreview = useMutation<CaseCohortPreviewResponse, ActionError>({
    retry: false,
    mutationFn: () => previewCaseCohort(engineId, filter),
    onSuccess: setPreview,
  })
  const startAnalysis = useMutation<CaseCohortAnalysisView, ActionError>({
    retry: false,
    mutationFn: () => startCaseAnalysis(engineId, filter),
    onSuccess: (data) => {
      setAnalysisId(data.id)
    },
  })
  // The analysis runs in the background; poll the stored row until it settles.
  const analysis = useQuery<CaseCohortAnalysisView, ApiError>({
    queryKey: ['case-migration-analysis', analysisId],
    enabled: analysisId !== undefined,
    queryFn: () => fetchCaseAnalysis(analysisId ?? ''),
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

  const submit = useSubmitBulkMigrateCases()
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
            text: `Case migration job started over ${String(job.totalItems ?? 0)} case${
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
      title="Migrate running cases"
      environment={environment}
      onClose={onClose}
      footer={
        <>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button
            type="button"
            disabled={toCaseDefinitionId === '' || runPreview.isPending}
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
        Each running case of this version is migrated on its own, as a tracked bulk job. A case
        holding a live plan item that the target model does not define is a blocker and is never
        sent; a case that changed since the analysis is skipped.
      </p>
      <label className="modal-field">
        Target version
        <select
          aria-label="Target version"
          value={toCaseDefinitionId}
          onChange={(event) => {
            setToCaseDefinitionId(event.target.value)
            reset()
          }}
        >
          {targets.map((version) => (
            <option key={version.caseDefinitionId} value={version.caseDefinitionId}>
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
          disabled={containsGate !== undefined}
          aria-describedby={containsGate !== undefined ? 'case-contains-gate' : undefined}
          onChange={(event) => {
            setContains(event.target.checked)
            reset()
          }}
        />{' '}
        Key contains this text
      </label>
      {containsGate !== undefined && (
        <p id="case-contains-gate" className="strip-note">
          &quot;Contains&quot; {containsGate}. The exact key works on this engine.
        </p>
      )}
      {runPreview.isError && <p role="alert">{problemBanner(runPreview.error.problem)}</p>}
      {preview != null && (
        <p role="status">
          {String(preview.count ?? 0)} running case{(preview.count ?? 0) === 1 ? '' : 's'}
          {blocked && ` · more than ${String(preview.cap ?? '')} — narrow the filter`}
        </p>
      )}
      {startAnalysis.isError && <p role="alert">{problemBanner(startAnalysis.error.problem)}</p>}
      {analysis.isError && <p role="alert">{analysis.error.message}</p>}
      {analysis.data?.status === 'RUNNING' && <p role="status">Analyzing…</p>}
      {analysis.data?.status === 'FAILED' && (
        <p role="alert">Analysis failed: {analysis.data.failure ?? 'unknown error'}</p>
      )}
      {done !== undefined && (
        <div role="status">
          <p>
            {String(executable)} executable · {String(done.blockerCount ?? 0)} with a blocker (never
            sent)
          </p>
          {(done.byPlanItem ?? []).length > 0 && (
            <ul>
              {(done.byPlanItem ?? []).map((row) => (
                <li key={row.planItemDefinitionId}>
                  <code>{row.planItemDefinitionId}</code> is not in the target model ·{' '}
                  {String(row.cases ?? 0)} case{row.cases === 1 ? '' : 's'}
                </li>
              ))}
            </ul>
          )}
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
