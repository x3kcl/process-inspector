// The persistent operations drawer (SPEC §7): tracked bulk jobs, hydrated from the BFF —
// job state is SERVER-side, so the drawer survives React Router navigation and browser
// refreshes for free. NO optimistic state: everything rendered here is the persisted
// BulkJobDto/BulkItemDto truth, re-fetched on a poll that tightens only while a job is
// actually live. INTERRUPTED jobs banner a "continue as new job" (no automatic resume).
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ActionError } from '../api/actions'
import { useOpsDrawer } from './drawerState'
import {
  BULK_JOBS_KEY,
  jobActive,
  useBulkJob,
  useBulkJobs,
  useSubmitBulk,
  verifyBulkItem,
  cancelBulk,
} from '../api/bulk'
import type { BulkItemDto, BulkJobDto, BulkTarget } from '../api/bulk'
import { problemBanner } from '../actions/problem'
import { useToast } from '../components/toast'
import { useLive, useLiveEvent } from '../live/live'
import { Ts } from '../lib/Ts'
import { outcomeCellLabel, outcomeClassName, outcomeIsDispatchOnly, outcomeLabel } from './outcome'

export function OpsDrawer() {
  const { open, setOpen, focusJobId, clearFocus } = useOpsDrawer()
  const queryClient = useQueryClient()
  const live = useLive()
  const jobs = useBulkJobs(true, live)
  const activeCount = (jobs.data ?? []).filter(jobActive).length
  const interrupted = (jobs.data ?? []).filter((job) => job.state === 'INTERRUPTED').length

  // v1.x #2 SSE hydration: the stream pushes the JOB ID only — invalidate and refetch
  // our own JSON (debounced in the hook; a 5000-item fan-out bursts one event per item).
  useLiveEvent('bulk-job', (jobId) => {
    void queryClient.invalidateQueries({ queryKey: BULK_JOBS_KEY })
    if (jobId !== '') void queryClient.invalidateQueries({ queryKey: ['bulk-job', jobId] })
  })

  // When a job SETTLES, the landing counts it acted on are stale — invalidate ['triage']
  // so the next look at Stage 0 re-aggregates. Poll-driven, never optimistic: this fires
  // off the persisted job state transition, not off the submit.
  const previouslyActive = useRef<Set<string>>(new Set())
  useEffect(() => {
    const nowActive = new Set((jobs.data ?? []).filter(jobActive).map((job) => job.id ?? ''))
    const settled = [...previouslyActive.current].some((id) => !nowActive.has(id))
    previouslyActive.current = nowActive
    if (settled) {
      void queryClient.invalidateQueries({ queryKey: ['triage'] })
    }
  }, [jobs.data, queryClient])

  if (jobs.data === undefined || jobs.data.length === 0) return null

  return (
    <aside className={`ops-drawer${open ? ' drawer-open' : ''}`} aria-label="Operations drawer">
      <button
        type="button"
        className="drawer-toggle"
        onClick={() => {
          if (open) clearFocus()
          setOpen(!open)
        }}
      >
        Operations {activeCount > 0 && <span className="drawer-live">{activeCount} running</span>}
        {interrupted > 0 && <span className="drawer-interrupted">{interrupted} interrupted</span>}
        <span aria-hidden>{open ? '▾' : '▴'}</span>
      </button>
      {open && (
        <div className="drawer-body">
          {jobs.data.map((job) => (
            <JobCard
              key={job.id}
              job={job}
              focused={job.id !== undefined && job.id === focusJobId}
            />
          ))}
        </div>
      )}
    </aside>
  )
}

function talliesLine(job: BulkJobDto): string {
  const tallies = job.tallies ?? {}
  const total = job.totalItems ?? 0
  const settledOrder = [
    'ok',
    'failed',
    'skipped',
    'skipped_protected',
    'unknown',
    'not_run',
  ] as const
  const dispatched =
    total -
    (tallies['pending'] ?? 0) -
    (tallies['not_run'] ?? 0) -
    (tallies['skipped_protected'] ?? 0)
  const parts = settledOrder
    .filter((key) => (tallies[key] ?? 0) > 0)
    // Theme G: verb-aware — a retry-job "ok" tally reads "re-queued", never "ok".
    .map((key) => `${outcomeLabel(job.verb, key)} ${String(tallies[key] ?? 0)}`)
  return `${String(Math.max(dispatched, 0))} of ${String(total)} dispatched${parts.length > 0 ? ' · ' + parts.join(' · ') : ''}`
}

function JobCard({ job, focused }: { job: BulkJobDto; focused: boolean }) {
  const [expanded, setExpanded] = useState(focused)
  // The dispatch handoff can land while the card is already mounted (drawer open on an
  // earlier job) — a focus arriving later must still expand it.
  useEffect(() => {
    if (focused) setExpanded(true)
  }, [focused])
  return (
    <div
      className={`job-card job-${(job.state ?? '').toLowerCase()}${focused ? ' job-focused' : ''}`}
    >
      <button
        type="button"
        className="job-card-head"
        aria-expanded={expanded}
        onClick={() => {
          setExpanded(!expanded)
        }}
      >
        <span className={`job-state state-${(job.state ?? '').toLowerCase()}`}>{job.state}</span>
        <code>{job.verb}</code>
        {/* Theme E1: scope provenance — the same chip idiom the confirm modal used, so an
            hour later "was that the cluster retry or the filter retry?" reads off the card. */}
        {typeof job.scopeLabel === 'string' && job.scopeLabel !== '' && (
          <code className="criteria-chip">{job.scopeLabel}</code>
        )}
        <span className="job-meta">
          {job.submittedBy} · <Ts iso={job.submittedAt} relative />
        </span>
        <span className="job-tallies">{talliesLine(job)}</span>
        <span aria-hidden>{expanded ? '▾' : '▴'}</span>
      </button>
      {expanded && job.id !== undefined && <JobDetail jobId={job.id} />}
    </div>
  )
}

function JobDetail({ jobId }: { jobId: string }) {
  const toast = useToast()
  const queryClient = useQueryClient()
  const live = useLive()
  const detail = useBulkJob(jobId, live)
  const submit = useSubmitBulk()

  const cancel = useMutation<BulkJobDto, ActionError, string>({
    retry: false,
    mutationFn: cancelBulk,
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['bulk-job', jobId] }),
        queryClient.invalidateQueries({ queryKey: BULK_JOBS_KEY }),
      ])
    },
  })

  const verify = useMutation<BulkItemDto, ActionError, number>({
    retry: false,
    mutationFn: (ordinal) => verifyBulkItem(jobId, ordinal),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: ['bulk-job', jobId] })
    },
    onSuccess: (item) => {
      toast({
        kind: item.state === 'ok' ? 'success' : 'error',
        text: `Verify now — item ${String(item.ordinal ?? '?')}: ${item.detail ?? item.state ?? ''}`,
      })
    },
    onError: (error) => {
      toast({ kind: 'error', text: problemBanner(error.problem) })
    },
  })

  const job = detail.data
  const items = useMemo(() => job?.items ?? [], [job])
  // "Continue as new job" scope (SPEC §7): not_run + failed, never re-firing ok/unknown.
  const continuationTargets = useMemo<BulkTarget[]>(
    () =>
      items
        .filter((item) => item.state === 'not_run' || item.state === 'failed')
        .map((item) => ({
          engineId: item.engineId ?? '',
          instanceId: item.instanceId ?? '',
          jobId: item.jobRef ?? undefined,
        })),
    [items],
  )
  const offerContinuation =
    job !== undefined &&
    (job.state === 'INTERRUPTED' || job.state === 'CANCELLED' || job.state === 'COMPLETED') &&
    continuationTargets.length > 0

  if (detail.isPending) return <p className="zero-state">Loading job items…</p>
  if (detail.isError) {
    return (
      <div className="error-banner" role="alert">
        Job detail unavailable: {detail.error.message}
      </div>
    )
  }
  if (job === undefined) return null

  return (
    <div className="job-detail">
      {typeof job.reason === 'string' && job.reason !== '' && (
        <p className="strip-note">Reason: "{job.reason}"</p>
      )}
      {job.state === 'INTERRUPTED' && (
        <div className="callout callout-amber" role="alert">
          The BFF stopped while this job ran. Nothing was resumed automatically: items in flight are{' '}
          <code>unknown</code> (Verify now), undispatched are <code>not_run</code>.
        </div>
      )}
      <div className="job-detail-toolbar">
        {jobActive(job) && (
          <button
            type="button"
            className="copy-btn action-btn"
            disabled={cancel.isPending}
            title="stops dispatching — items already sent keep their outcome"
            onClick={() => {
              cancel.mutate(jobId)
            }}
          >
            {cancel.isPending ? 'Cancelling…' : 'Cancel (stop dispatching)'}
          </button>
        )}
        {offerContinuation && (
          <button
            type="button"
            className="copy-btn action-btn"
            disabled={submit.isPending}
            title={`submits a NEW tracked job over the ${String(continuationTargets.length)} not-run/failed item${continuationTargets.length === 1 ? '' : 's'}`}
            onClick={() => {
              submit.mutate(
                {
                  verb: job.verb,
                  reason: job.reason ?? undefined,
                  continuedFrom: job.id,
                  items: continuationTargets,
                },
                {
                  onSuccess: (next) => {
                    toast({
                      kind: 'success',
                      text: `Continued as new job ${(next.id ?? '').slice(0, 8)}… over ${String(continuationTargets.length)} item${continuationTargets.length === 1 ? '' : 's'}.`,
                    })
                  },
                  onError: (error) => {
                    toast({ kind: 'error', text: problemBanner(error.problem) })
                  },
                },
              )
            }}
          >
            Continue as new job ({continuationTargets.length} not run / failed)
          </button>
        )}
        {/* Wire nulls: Jackson serializes absent fields as null, the generated type
            says undefined — guard both or a fresh job crashes the drawer. */}
        {typeof job.continuedFrom === 'string' && job.continuedFrom !== '' && (
          <span className="job-meta">continues {job.continuedFrom.slice(0, 8)}…</span>
        )}
      </div>
      {/* Theme T8: the re-queued explainer joins the amber honesty envelope — a dispatch
          state must never read as a success verdict, and each item links its own verify. */}
      {job.verb === 'retry-job' && (
        <div className="callout callout-amber" role="note">
          Re-queued means the failed step will run again — it has not succeeded yet. A step that
          fails the same way will return to FAILED after its retries; use an item's Verify now to
          open its live engine evidence, or check the grid in a few minutes.
        </div>
      )}
      <table className="ledger-table job-items">
        <thead>
          <tr>
            <th scope="col">#</th>
            <th scope="col">Instance</th>
            <th scope="col">Outcome</th>
            <th scope="col">Detail</th>
            <th scope="col">
              <span className="visually-hidden">Actions</span>
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.ordinal} className={`item-${item.state ?? ''}`}>
              <td>{item.ordinal}</td>
              <td>
                <code>{`${item.engineId ?? '?'}:${item.instanceId ?? '?'}`}</code>
              </td>
              <td>
                <span className={`outcome ${outcomeClassName(job.verb, item.state)}`}>
                  {outcomeCellLabel(job.verb, item.state)}
                </span>
              </td>
              <td className="item-detail">{item.detail}</td>
              <td>
                {item.state === 'unknown' && item.ordinal !== undefined && (
                  <button
                    type="button"
                    className="copy-btn"
                    disabled={verify.isPending}
                    title="re-checks the engine state (R-SAFE-09) — never re-fires the action"
                    onClick={() => {
                      verify.mutate(item.ordinal ?? 0)
                    }}
                  >
                    Verify now
                  </button>
                )}
                {/* #105: per-item retry-failed-only — "Continue as new job" above already
                    covers retrying every not_run/failed item together; this covers retrying
                    JUST this one, without pulling in the rest. Same submit mutation, same
                    verb/reason, scoped to a single-item target list. */}
                {item.state === 'failed' &&
                  item.ordinal !== undefined &&
                  typeof item.engineId === 'string' &&
                  typeof item.instanceId === 'string' &&
                  item.instanceId !== '' && (
                    <button
                      type="button"
                      className="copy-btn"
                      disabled={submit.isPending}
                      title="submits a NEW tracked job retrying just this item"
                      onClick={() => {
                        submit.mutate(
                          {
                            verb: job.verb,
                            reason: job.reason ?? undefined,
                            continuedFrom: job.id,
                            items: [
                              {
                                engineId: item.engineId ?? '',
                                instanceId: item.instanceId ?? '',
                                jobId: item.jobRef ?? undefined,
                              },
                            ],
                          },
                          {
                            onSuccess: (next) => {
                              toast({
                                kind: 'success',
                                text: `Retried item ${String(item.ordinal)} as new job ${(next.id ?? '').slice(0, 8)}….`,
                              })
                            },
                            onError: (error) => {
                              toast({ kind: 'error', text: problemBanner(error.problem) })
                            },
                          },
                        )
                      }}
                    >
                      Retry
                    </button>
                  )}
                {/* Theme T8: re-queued is dispatch-only — its Verify now opens the item's
                    live Errors & Jobs evidence (did the step succeed, or is it back in the
                    DLQ?). A read-only link: it never re-fires the action (R-SAFE-09). */}
                {outcomeIsDispatchOnly(job.verb, item.state) &&
                  typeof item.engineId === 'string' &&
                  typeof item.instanceId === 'string' &&
                  item.instanceId !== '' && (
                    <Link
                      className="copy-btn verify-link"
                      title="re-queued is a dispatch state, not a verdict — opens this item's live Errors & Jobs evidence (never re-fires the action)"
                      to={`/inspect/${item.engineId}/${encodeURIComponent(item.instanceId)}?tab=errors-jobs`}
                    >
                      Verify now
                    </Link>
                  )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
