import { RawJsonExport } from '../RawJsonExport'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ExternalWorkerJobDto, InstanceJobs, JobDto, JobLaneId } from '../../api/model'
import { JOB_LANES } from '../../api/model'
import { fetchActionCurl, useInstanceAction } from '../../api/actions'
import type { ActionRequest } from '../../api/actions'
import { fetchJobStacktrace } from '../../api/queries'
import { useEngines } from '../../api/useEngines'
import { externalWorkerLaneVisible } from '../externalWorker'
import { DestructiveModal } from '../../actions/DestructiveModal'
import { InlineConfirm } from '../../actions/InlineConfirm'
import { VERBS, actionGate, needsTwoStepConfirm } from '../../actions/catalog'
import type { Gate } from '../../actions/catalog'
import { problemBanner } from '../../actions/problem'
import { ActionHint } from '../../components/ActionHint'
import { CopyButton } from '../../components/CopyButton'
import { CurlPreview } from '../../components/CurlPreview'
import { useToast } from '../../components/toast'
import { Ts } from '../../lib/Ts'
import { roleOn, useMe } from '../../api/me'
import type { TabProps } from '../InspectPage'
import { useInstanceExternalWorkerJobs, useInstanceJobs } from '../useInstanceQueries'

/** Lane metadata (SPEC §4): the lane IS the diagnosis, so each one explains itself. */
const LANE_META: Record<JobLaneId, { title: string; diagnosis: string; wireLane: string }> = {
  deadLetter: {
    title: 'Dead-letter',
    diagnosis: 'retries exhausted — this is the FAILED evidence; retry moves it back to executable',
    wireLane: 'DEADLETTER',
  },
  executable: {
    title: 'Executable',
    diagnosis:
      'ready or running right now; a row with an exception is a failing job between attempts',
    wireLane: 'EXECUTABLE',
  },
  timer: {
    title: 'Timer',
    diagnosis: 'scheduled for later — including a failing job parked here between retry attempts',
    wireLane: 'TIMER',
  },
  suspended: {
    title: 'Suspended',
    diagnosis: 'paused with the instance; activate the instance to release them',
    wireLane: 'SUSPENDED',
  },
}

/** Four Flowable job lanes kept distinct (SPEC §4); stacktraces fetch on expand only. */
export default function ErrorsJobsTab({
  engineId,
  instanceId,
  selectedActivityId,
  onShowOnDiagram,
}: TabProps) {
  const query = useInstanceJobs(engineId, instanceId)
  const engines = useEngines()
  const engine = (engines.data ?? []).find((candidate) => candidate.id === engineId)
  // Capability gate (v1.x #7): the fifth lane exists ONLY on a Flowable ≥ 6.8 engine. On a
  // pre-6.8 engine it is never rendered and its query never fires — true graceful degradation.
  const showExternalWorker = externalWorkerLaneVisible(engine)

  if (query.isPending) return <div className="zero-state">Loading job lanes…</div>
  if (query.isError) {
    return (
      <div className="error-banner" role="alert">
        Job lanes unavailable: {query.error.message}
      </div>
    )
  }
  const jobs = query.data
  const empty = JOB_LANES.every((lane) => laneRows(jobs, lane).length === 0)
  return (
    <div className="job-lanes-tab">
      <RawJsonExport data={jobs} filename={`${engineId}-${instanceId}-jobs.json`} />
      {empty && (
        <p className="zero-state">
          No jobs in any lane — this instance is not failing, retrying, scheduled, or suspended at
          the job level.
        </p>
      )}
      {JOB_LANES.map((lane) => (
        <JobLaneSection
          key={lane}
          lane={lane}
          rows={laneRows(jobs, lane)}
          engineId={engineId}
          instanceId={instanceId}
          selectedActivityId={selectedActivityId}
          onShowOnDiagram={onShowOnDiagram}
        />
      ))}
      {showExternalWorker && (
        <ExternalWorkerLane
          engineId={engineId}
          instanceId={instanceId}
          onShowOnDiagram={onShowOnDiagram}
        />
      )}
    </div>
  )
}

/**
 * The fifth queue (v1.x #7) — external-worker jobs, READ-ONLY. Only mounted on a capable
 * engine (the parent gates on `EngineDto.capabilities.externalWorkerJobs`), so its query runs
 * unconditionally here. The lock owner is the crux: a job held by a worker whose lock keeps
 * sliding is a worker that acquired but never completes.
 */
function ExternalWorkerLane({
  engineId,
  instanceId,
  onShowOnDiagram,
}: {
  engineId: string
  instanceId: string
  onShowOnDiagram?: (activityId: string) => void
}) {
  const query = useInstanceExternalWorkerJobs(engineId, instanceId, true)
  const rows: ExternalWorkerJobDto[] = query.data ?? []
  return (
    <details className="job-lane lane-external-worker" open={rows.length > 0}>
      <summary>
        External worker <span className="group-count">({query.isPending ? '…' : rows.length})</span>
        <span className="lane-diagnosis">
          Flowable&rsquo;s fifth queue — steps handed to an external worker; a job whose lock owner
          keeps sliding is a worker that acquired but never completes
        </span>
      </summary>
      {query.isError ? (
        <div className="error-banner" role="alert">
          External-worker jobs unavailable: {query.error.message}
        </div>
      ) : query.isPending ? (
        <p className="zero-state">Loading external-worker jobs…</p>
      ) : rows.length === 0 ? (
        <p className="zero-state">No external-worker jobs on this instance.</p>
      ) : (
        <table className="ledger-table">
          <thead>
            <tr>
              <th scope="col">Job</th>
              <th scope="col">Activity</th>
              <th scope="col">Lock owner</th>
              <th scope="col">Lock expiration</th>
              <th scope="col">Retries</th>
              <th scope="col">Exception</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((job, index) => (
              <tr key={job.id ?? `ew-${String(index)}`}>
                <td className="id-cell">
                  <code>{job.id}</code>
                  {job.id !== undefined && <CopyButton text={job.id} label="copy" />}
                </td>
                <td>
                  {job.elementName ?? job.elementId ?? <span className="value-muted">—</span>}
                  {job.elementName !== undefined && job.elementId !== undefined && (
                    <code className="element-id"> {job.elementId}</code>
                  )}
                  {job.elementId !== undefined && onShowOnDiagram !== undefined && (
                    <button
                      type="button"
                      className="copy-btn"
                      title="highlight this activity on the diagram"
                      onClick={() => {
                        onShowOnDiagram(job.elementId ?? '')
                      }}
                    >
                      show on diagram
                    </button>
                  )}
                </td>
                <td>
                  {job.lockOwner ?? (
                    <span className="value-muted">unacquired — no worker holds it</span>
                  )}
                </td>
                <td>
                  <Ts iso={job.lockExpirationTime} relative />
                </td>
                <td>{job.retries ?? <span className="value-muted">—</span>}</td>
                <td className="exception-cell">
                  {job.exceptionMessage !== undefined ? (
                    <span className="exception-first-line">{firstLine(job.exceptionMessage)}</span>
                  ) : (
                    <span className="value-muted">none</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </details>
  )
}

function laneRows(jobs: InstanceJobs, lane: JobLaneId): JobDto[] {
  return jobs[lane] ?? []
}

function JobLaneSection({
  lane,
  rows,
  engineId,
  instanceId,
  selectedActivityId,
  onShowOnDiagram,
}: {
  lane: JobLaneId
  rows: JobDto[]
  engineId: string
  instanceId: string
  selectedActivityId?: string
  onShowOnDiagram?: (activityId: string) => void
}) {
  const meta = LANE_META[lane]
  // Diagram→tab sync: a selected activity with rows in this lane forces the lane open.
  const holdsSelection =
    selectedActivityId !== undefined && rows.some((job) => job.elementId === selectedActivityId)
  return (
    <details className={`job-lane lane-${lane}`} open={rows.length > 0 || holdsSelection}>
      <summary>
        {meta.title} <span className="group-count">({rows.length})</span>
        <span className="lane-diagnosis">{meta.diagnosis}</span>
      </summary>
      {rows.length === 0 ? (
        <p className="zero-state">Empty lane.</p>
      ) : (
        <table className="ledger-table">
          <thead>
            <tr>
              <th scope="col">Job</th>
              <th scope="col">Activity</th>
              <th scope="col">Created</th>
              <th scope="col">Due</th>
              <th scope="col">Retries</th>
              <th scope="col">Exception</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((job, index) => (
              <JobRow
                key={job.id ?? `row-${String(index)}`}
                job={job}
                lane={lane}
                wireLane={meta.wireLane}
                engineId={engineId}
                instanceId={instanceId}
                selected={selectedActivityId !== undefined && job.elementId === selectedActivityId}
                onShowOnDiagram={onShowOnDiagram}
              />
            ))}
          </tbody>
        </table>
      )}
    </details>
  )
}

type StacktraceState =
  { phase: 'loading' } | { phase: 'loaded'; text: string } | { phase: 'error'; message: string }

function JobRow({
  job,
  lane,
  wireLane,
  engineId,
  instanceId,
  selected,
  onShowOnDiagram,
}: {
  job: JobDto
  lane: JobLaneId
  wireLane: string
  engineId: string
  instanceId: string
  selected: boolean
  onShowOnDiagram?: (activityId: string) => void
}) {
  const [stacktrace, setStacktrace] = useState<StacktraceState>()
  const [open, setOpen] = useState(false)
  const rowRef = useRef<HTMLTableRowElement | null>(null)

  // Diagram→tab sync: the first row of the selected activity scrolls into view.
  useEffect(() => {
    if (selected) rowRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [selected])

  const telemetryUrl = jobTelemetryUrl(job)

  const toggleStacktrace = () => {
    if (open) {
      setOpen(false)
      return
    }
    setOpen(true)
    if (stacktrace !== undefined && stacktrace.phase === 'loaded') return
    setStacktrace({ phase: 'loading' })
    fetchJobStacktrace({ engineId, instanceId }, job.id ?? '', wireLane)
      .then((text) => {
        setStacktrace({ phase: 'loaded', text })
      })
      .catch((error: unknown) => {
        setStacktrace({
          phase: 'error',
          message: error instanceof Error ? error.message : String(error),
        })
      })
  }

  return (
    <>
      <tr ref={rowRef} className={selected ? 'row-selected' : undefined}>
        <td className="id-cell">
          <code>{job.id}</code>
          {job.id !== undefined && <CopyButton text={job.id} label="copy" />}
        </td>
        <td>
          {job.elementName ?? job.elementId ?? <span className="value-muted">—</span>}
          {job.elementName !== undefined && job.elementId !== undefined && (
            <code className="element-id"> {job.elementId}</code>
          )}
          {job.elementId !== undefined && onShowOnDiagram !== undefined && (
            <button
              type="button"
              className="copy-btn"
              title="highlight this activity on the diagram"
              onClick={() => {
                onShowOnDiagram(job.elementId ?? '')
              }}
            >
              show on diagram
            </button>
          )}
        </td>
        <td>
          <Ts iso={job.createTime} relative />
        </td>
        <td>
          <Ts iso={job.dueDate} relative />
        </td>
        <td>{job.retries ?? <span className="value-muted">—</span>}</td>
        <td className="exception-cell">
          {job.exceptionMessage !== undefined ? (
            <>
              <span className="exception-first-line">{firstLine(job.exceptionMessage)}</span>
              <button type="button" className="copy-btn" onClick={toggleStacktrace}>
                {open ? 'hide stacktrace' : 'stacktrace'}
              </button>
            </>
          ) : (
            <span className="value-muted">none</span>
          )}
          {telemetryUrl !== undefined && (
            <a className="open-logs" href={telemetryUrl} target="_blank" rel="noreferrer">
              open logs ↗
            </a>
          )}
        </td>
        <td className="job-actions">
          <JobActions job={job} lane={lane} engineId={engineId} instanceId={instanceId} />
        </td>
      </tr>
      {open && (
        <tr className="ledger-expansion">
          <td colSpan={7}>
            {stacktrace === undefined || stacktrace.phase === 'loading' ? (
              <p className="zero-state">Fetching stacktrace…</p>
            ) : stacktrace.phase === 'error' ? (
              <div className="error-banner" role="alert">
                No stacktrace: {stacktrace.message} (the job may have been retried or deleted since)
              </div>
            ) : (
              <pre className="value-body stacktrace">{stacktrace.text}</pre>
            )}
          </td>
        </tr>
      )}
    </>
  )
}

/** The per-job verb set (SPEC §5): retry + delete on dead-letter, fire-now on timers. */
function JobActions({
  job,
  lane,
  engineId,
  instanceId,
}: {
  job: JobDto
  lane: JobLaneId
  engineId: string
  instanceId: string
}) {
  const toast = useToast()
  const engines = useEngines()
  const action = useInstanceAction(engineId, instanceId)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const engine = useMemo(
    () => (engines.data ?? []).find((candidate) => candidate.id === engineId),
    [engines.data, engineId],
  )
  const me = useMe()
  const roleHint = roleOn(me.data, engineId)
  const environment = engine?.environment
  const auditPath = `/inspect/${engineId}/${encodeURIComponent(instanceId)}?tab=audit`

  const gateFor = (meta: (typeof VERBS)[keyof typeof VERBS]): Gate =>
    actionGate({ meta, roleHint, engineMode: engine?.mode })

  // `extra` carries the tier-gated body fields the modal collects — the required reason (tier ≥ 2)
  // and, on a prod engine, the typed confirm token (= the job id). Tier-0 callers (retry, fire
  // timer) pass nothing. Without this, a tier-3 delete-deadletter POSTs no reason and the BFF
  // refuses it `reason-required` — the delete never happens.
  const run = (verb: string, onDone?: () => void, extra?: Partial<ActionRequest>) => {
    action.mutate(
      { verb, body: { jobId: job.id, ...extra } },
      {
        onSuccess: (result) => {
          onDone?.()
          toast({
            kind: 'success',
            text: result.deltaStatement ?? `${verb} on job ${job.id ?? '?'} completed`,
            auditPath,
          })
        },
        onError: (error) => {
          if (!deleteOpen) toast({ kind: 'error', text: problemBanner(error.problem) })
        },
      },
    )
  }

  if (lane === 'deadLetter') {
    const deleteGate = gateFor(VERBS.deleteDeadletter)
    return (
      <>
        <InlineConfirm
          meta={VERBS.retryJob}
          gate={gateFor(VERBS.retryJob)}
          confirmText={`Retry job ${job.id ?? '?'}?`}
          twoStep={needsTwoStepConfirm(VERBS.retryJob, environment)}
          pending={action.isPending && !deleteOpen}
          onConfirm={() => {
            run(VERBS.retryJob.verb)
          }}
        />
        {/* Issue #103: the tier-0 inline retry flow gets the same server-computed "Show as
            cURL" every modal-based verb already carries — never client-generated. */}
        <CurlPreview
          queryKey={['instance', engineId, instanceId, VERBS.retryJob.verb, job.id ?? '']}
          fetchCurl={() =>
            fetchActionCurl(engineId, instanceId, VERBS.retryJob.verb, { jobId: job.id })
          }
        />
        {/* W2 #6 (T7): the disabled Delete carries the visible ActionHint gate — the same
            pattern as its InlineConfirm sibling; never a title-only dead control. */}
        <span className="action-slot">
          <button
            type="button"
            className="copy-btn action-btn action-danger"
            disabled={!deleteGate.enabled}
            aria-describedby={deleteGate.enabled ? undefined : `delete-dlq-hint-${job.id ?? ''}`}
            title={
              deleteGate.enabled
                ? `${VERBS.deleteDeadletter.plain} · ${VERBS.deleteDeadletter.reversibilityNote}`
                : (deleteGate.detail ?? deleteGate.reason)
            }
            onClick={() => {
              action.reset()
              setDeleteOpen(true)
            }}
          >
            Delete
          </button>
          {!deleteGate.enabled && deleteGate.reason !== undefined && (
            <ActionHint
              id={`delete-dlq-hint-${job.id ?? ''}`}
              text={deleteGate.reason}
              tone="gate"
            />
          )}
        </span>
        {deleteOpen && (
          <DestructiveModal
            meta={VERBS.deleteDeadletter}
            environment={environment}
            engineName={engine?.name ?? engineId}
            target={
              <ul className="modal-target-list">
                <li>
                  Dead-letter job <code>{job.id}</code>
                </li>
                <li>
                  Activity {job.elementName ?? job.elementId ?? '?'}
                  {job.elementId !== undefined && (
                    <code className="element-id"> {job.elementId}</code>
                  )}
                </li>
                {job.exceptionMessage !== undefined && (
                  <li>Exception {firstLine(job.exceptionMessage)}</li>
                )}
                <li className="cascade-warning">
                  ⚠ The execution is orphaned permanently — the case can never continue past this
                  step on its own; the only rescue afterwards is change-state.
                </li>
              </ul>
            }
            cascade={{ victims: [] }}
            expectedToken={job.id ?? ''}
            tokenName="job id"
            confirmLabel={`Delete dead-letter job ${job.id ?? '?'}`}
            pending={action.isPending}
            problem={action.error?.problem}
            onConfirm={(reason, ticketId) => {
              // Tier-3: thread the modal's reason, optional ticket (R-AUD-07) and, on prod,
              // the typed token (= the job id, which the modal already required to be typed).
              run(
                VERBS.deleteDeadletter.verb,
                () => {
                  setDeleteOpen(false)
                },
                {
                  reason,
                  ticketId,
                  confirmToken:
                    environment?.toLowerCase() === 'prod' ? (job.id ?? undefined) : undefined,
                },
              )
            }}
            onClose={() => {
              setDeleteOpen(false)
            }}
          />
        )}
      </>
    )
  }

  if (lane === 'timer') {
    return (
      <span className="action-slot">
        <InlineConfirm
          meta={VERBS.triggerTimer}
          gate={gateFor(VERBS.triggerTimer)}
          confirmText={`Fire timer for job ${job.id ?? '?'}?`}
          twoStep={needsTwoStepConfirm(VERBS.triggerTimer, environment)}
          pending={action.isPending}
          onConfirm={() => {
            run(VERBS.triggerTimer.verb)
          }}
        />
        {/* Issue #214: firing the timer only advances the retry schedule — it does nothing
            about whatever is making the retry fail, and it's IRREVERSIBLE (no way to give
            the attempt back). The reversibility note already says "no way back" in the
            button's title, but that's hover-only; this is the same warning made visible
            BEFORE the click, not discoverable only from the retries counter dropping after. */}
        <ActionHint
          tone="info"
          text="Only advances the retry schedule — it won't fix whatever made this job fail, and the attempt can't be given back."
        />
      </span>
    )
  }

  if (lane === 'suspended') {
    return (
      <span className="value-muted" title="suspended jobs release when the instance is activated">
        activate the instance
      </span>
    )
  }

  return <span className="value-muted">—</span>
}

/**
 * Per-job telemetry deep link (SPEC §4). The generated JobDto does not carry the field
 * yet; this runtime probe lights the link up the moment the backend adds it to the
 * contract — and renders nothing until then (never a broken guess).
 */
function jobTelemetryUrl(job: JobDto): string | undefined {
  const candidate = (job as Record<string, unknown>)['telemetryUrl']
  return typeof candidate === 'string' && candidate !== '' ? candidate : undefined
}

function firstLine(message: string): string {
  const newline = message.indexOf('\n')
  return newline < 0 ? message : message.slice(0, newline)
}
