import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useCaseAction } from '../api/caseActions'
import { useEngines } from '../api/useEngines'
import { roleOn, useMe } from '../api/me'
import { InlineConfirm } from '../actions/InlineConfirm'
import { VERBS, actionGate, needsTwoStepConfirm } from '../actions/catalog'
import { problemBanner } from '../actions/problem'
import { CopyButton } from '../components/CopyButton'
import { EnvBadge } from '../components/EnvBadge'
import { useToast } from '../components/toast'
import { formatDateTime } from '../lib/format'
import { CaseDiagramCanvas } from './CaseDiagramCanvas'
import { PlanItemTimeline } from './PlanItemTimeline'
import { useCaseDiagram, useCasePlanItems, useCaseVitals } from './useCaseQueries'

/**
 * Case Inspector Phase 2 (R-SEM-20): the read-only, polymorphic Stage-2 detail of one CMMN case —
 * the CMMN sibling of {@code InstancePage}. Vitals header (no SUSPENDED — cases can't suspend), a
 * cmmn-js diagram on top (or an honest no-layout state), and the plan-item state-machine timeline
 * below. Mostly read-only; Phase 3 adds ONE corrective action — dead-letter retry (tier 0 /
 * RESPONDER) — offered per dead-letter job in the "why stuck" panel under the full
 * corrective-actions rails (audit, RBAC, capability gate, no auto-retry).
 *
 * Gated 6.8+ server-side: a pre-6.8 engine (or an engine that has not probed) refuses the vitals
 * call with a ProblemDetail, surfaced here as an honest error, never a fabricated page.
 */
export function CasePage() {
  const { engineId = '', caseInstanceId = '' } = useParams()
  const engines = useEngines()
  const vitals = useCaseVitals(engineId, caseInstanceId)
  const diagram = useCaseDiagram(engineId, caseInstanceId)
  const planItems = useCasePlanItems(engineId, caseInstanceId)
  const [selectedElementId, setSelectedElementId] = useState<string | undefined>(undefined)

  const me = useMe()
  const toast = useToast()
  const action = useCaseAction(engineId, caseInstanceId)

  const engine = useMemo(
    () => (engines.data ?? []).find((candidate) => candidate.id === engineId),
    [engines.data, engineId],
  )
  const compositeId = `${engineId}:${caseInstanceId}`
  const roleHint = roleOn(me.data, engineId)

  // Phase 3: the ONLY CMMN corrective action today is dead-letter retry (tier 0 / RESPONDER),
  // capability-gated on scopeType exactly as the backend gate — greyed, never hidden.
  const retryGate = actionGate({
    meta: VERBS.retryJob,
    roleHint,
    engineMode: engine?.mode,
    capability: engine?.capabilities?.scopeType,
  })
  const retryJob = (jobId: string) => {
    action.mutate(
      { verb: VERBS.retryJob.verb, body: { jobId } },
      {
        onSuccess: (result) => {
          toast({ kind: 'success', text: result.deltaStatement ?? `Retried job ${jobId}.` })
        },
        onError: (error) => {
          toast({ kind: 'error', text: problemBanner(error.problem) })
        },
      },
    )
  }

  return (
    <div className="case-page">
      <header className="case-header">
        <div className="case-header-row">
          <Link to="/" className="case-back">
            ← Triage
          </Link>
          <EnvBadge environment={engine?.environment} accentColor={engine?.accentColor} />
          <span className="case-kind" title="This is a CMMN case, not a BPMN process">
            CMMN case
          </span>
        </div>

        {vitals.isPending && <p className="muted">Loading case…</p>}
        {vitals.isError && (
          <p className="error-note" role="alert">
            Could not load this case: {vitals.error.message}
          </p>
        )}
        {vitals.isSuccess && (
          <>
            <h1 className="case-title">
              {vitals.data.caseDefinitionName ?? vitals.data.caseDefinitionKey ?? 'CMMN case'}
              {vitals.data.state && (
                <span className={`case-state case-state--${vitals.data.state.toLowerCase()}`}>
                  {vitals.data.state}
                </span>
              )}
            </h1>
            <dl className="case-vitals">
              {vitals.data.caseDefinitionKey && (
                <>
                  <dt>Case type</dt>
                  <dd className="mono">
                    {vitals.data.caseDefinitionKey}
                    {vitals.data.caseDefinitionVersion != null &&
                      ` v${String(vitals.data.caseDefinitionVersion)}`}
                  </dd>
                </>
              )}
              {vitals.data.businessKey && (
                <>
                  <dt>Business key</dt>
                  <dd>{vitals.data.businessKey}</dd>
                </>
              )}
              <dt>Case id</dt>
              <dd className="mono case-id-row">
                {caseInstanceId}
                <CopyButton text={compositeId} label="Copy engine:id" />
              </dd>
              <dt>Started</dt>
              <dd>{formatDateTime(vitals.data.startTime)}</dd>
              {vitals.data.ended && (
                <>
                  <dt>Ended</dt>
                  <dd>{formatDateTime(vitals.data.endTime)}</dd>
                </>
              )}
              {vitals.data.superProcessInstanceId && (
                <>
                  <dt>Called from</dt>
                  <dd
                    className="mono"
                    title="a BPMN process on the paired engine started this case"
                  >
                    {vitals.data.superProcessInstanceId}
                  </dd>
                </>
              )}
            </dl>

            {vitals.data.failing && (
              <div className="case-why-stuck" role="note">
                <p>
                  <strong>Why stuck:</strong> {vitals.data.failing.deadLetterJobCount} dead-letter
                  {vitals.data.failing.deadLetterJobCount === 1 ? ' job' : ' jobs'}
                  {vitals.data.failing.failingElementName &&
                    ` on “${vitals.data.failing.failingElementName}”`}
                  {vitals.data.failing.firstException && (
                    <span className="case-exception"> — {vitals.data.failing.firstException}</span>
                  )}
                </p>
                {vitals.data.failing.jobs && vitals.data.failing.jobs.length > 0 && (
                  <ul className="case-deadletter-actions" aria-label="Dead-letter jobs">
                    {vitals.data.failing.jobs.map((job) => (
                      <li key={job.id} className="case-deadletter-row">
                        <code className="mono">{job.id}</code>
                        {job.elementName && <span className="value-muted">{job.elementName}</span>}
                        {/* Retry moves the job back to the executable queue (RECOVERABLE): the
                            queue move is reversible, the side effects of the re-run job are not. */}
                        <InlineConfirm
                          meta={VERBS.retryJob}
                          gate={retryGate}
                          confirmText={`Retry job ${job.id ?? '?'}?`}
                          twoStep={needsTwoStepConfirm(VERBS.retryJob, engine?.environment)}
                          pending={action.isPending}
                          onConfirm={() => {
                            retryJob(job.id ?? '')
                          }}
                        />
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </>
        )}
      </header>

      <section className="case-diagram-section" aria-label="Case diagram">
        {diagram.isPending && <p className="muted">Loading diagram…</p>}
        {diagram.isError && (
          <p className="error-note" role="alert">
            Could not load the case diagram: {diagram.error.message}
          </p>
        )}
        {diagram.isSuccess && (
          <CaseDiagramCanvas
            xml={diagram.data.xml ?? ''}
            graphicalNotationDefined={diagram.data.graphicalNotationDefined ?? false}
            markers={{
              activePlanItemElementIds: diagram.data.activePlanItemElementIds ?? [],
              failedPlanItemElementIds: diagram.data.failedPlanItemElementIds ?? [],
            }}
            onSelectElement={setSelectedElementId}
            selectedElementId={selectedElementId}
          />
        )}
      </section>

      <section className="case-timeline-section" aria-label="Plan-item timeline">
        <h2 className="case-section-title">Plan items</h2>
        {planItems.isPending && <p className="muted">Loading plan items…</p>}
        {planItems.isError && (
          <p className="error-note" role="alert">
            Could not load plan items: {planItems.error.message}
          </p>
        )}
        {planItems.isSuccess && <PlanItemTimeline data={planItems.data} />}
      </section>
    </div>
  )
}
