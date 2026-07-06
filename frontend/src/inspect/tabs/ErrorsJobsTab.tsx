import { useState } from 'react'
import type { InstanceJobs, JobDto, JobLaneId } from '../../api/model'
import { JOB_LANES } from '../../api/model'
import { fetchJobStacktrace } from '../../api/queries'
import { CopyButton } from '../../components/CopyButton'
import { formatDateTime } from '../../lib/format'
import { useInstanceJobs } from '../useInstanceQueries'

interface Props {
  engineId: string
  instanceId: string
}

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
export default function ErrorsJobsTab({ engineId, instanceId }: Props) {
  const query = useInstanceJobs(engineId, instanceId)

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
        />
      ))}
    </div>
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
}: {
  lane: JobLaneId
  rows: JobDto[]
  engineId: string
  instanceId: string
}) {
  const meta = LANE_META[lane]
  return (
    <details className={`job-lane lane-${lane}`} open={rows.length > 0}>
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
            </tr>
          </thead>
          <tbody>
            {rows.map((job, index) => (
              <JobRow
                key={job.id ?? `row-${String(index)}`}
                job={job}
                wireLane={meta.wireLane}
                engineId={engineId}
                instanceId={instanceId}
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
  wireLane,
  engineId,
  instanceId,
}: {
  job: JobDto
  wireLane: string
  engineId: string
  instanceId: string
}) {
  const [stacktrace, setStacktrace] = useState<StacktraceState>()
  const [open, setOpen] = useState(false)

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
      <tr>
        <td className="id-cell">
          <code>{job.id}</code>
          {job.id !== undefined && <CopyButton text={job.id} label="copy" />}
        </td>
        <td>
          {job.elementName ?? job.elementId ?? <span className="value-muted">—</span>}
          {job.elementName !== undefined && job.elementId !== undefined && (
            <code className="element-id"> {job.elementId}</code>
          )}
        </td>
        <td>{formatDateTime(job.createTime)}</td>
        <td>{formatDateTime(job.dueDate)}</td>
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
        </td>
      </tr>
      {open && (
        <tr className="ledger-expansion">
          <td colSpan={6}>
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

function firstLine(message: string): string {
  const newline = message.indexOf('\n')
  return newline < 0 ? message : message.slice(0, newline)
}
