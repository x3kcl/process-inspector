import { useQuery } from '@tanstack/react-query'
import { CMMN_STATUSES, type CmmnStatus, type CmmnLaneCounts } from '../api/model'
import { fetchCmmnScope } from '../api/queries'
import { useEngines } from '../api/useEngines'
import { ModalShell } from '../components/ModalShell'

/**
 * Case Inspector Phase 1 (R-SEM-20): the scope-typed view of the co-deployed CMMN engine behind
 * the Stage-0 "≥N CMMN jobs not triaged here" note. It shows the CMMN case lane counts
 * (ACTIVE / FAILED / COMPLETED / TERMINATED — no SUSPENDED, cases can't suspend) so an operator
 * sees the shape of what's running on the other engine, then drills the FAILED lane into the
 * specific dead-letter jobs (the same ones the BPMN join excludes). Read-only — CMMN corrective
 * actions are Phase 3.
 *
 * The FAILED count and the job list carry the SAME truncation honesty: when the bounded scan hit
 * the cap they are a labeled lower bound (≥), never a silently-exact subset. Other lanes that
 * fail to load render "—" (unknown), never a misleading 0.
 */
export function CmmnScopeDrawer({ engineId, onClose }: { engineId: string; onClose: () => void }) {
  const query = useQuery({
    queryKey: ['cmmn-scope', engineId],
    queryFn: () => fetchCmmnScope(engineId),
    staleTime: 15_000,
  })

  // The drill lives on ONE engine — carry its real risk tier onto the modal band so the header
  // reads DEV/PROD like every other card, not the bare "UNKNOWN" fallback (usability Finding #3).
  const engines = useEngines()
  const environment = engines.data?.find((engine) => engine.id === engineId)?.environment

  const lanes = query.data?.lanes
  const jobs = query.data?.deadletters?.jobs ?? []
  const truncated = query.data?.deadletters?.truncated === true

  // Drive the tiles off CMMN_STATUSES (never the BPMN ALL_STATUSES — that hardcodes SUSPENDED
  // and drops TERMINATED; §7 M4 hazard). FAILED is a lower bound under a truncated scan.
  const laneValue = (lane: CmmnStatus): number | null | undefined => {
    if (!lanes) return undefined
    const key = lane.toLowerCase() as keyof CmmnLaneCounts
    return lanes[key]
  }

  return (
    <ModalShell
      title={`CMMN scope — ${engineId}`}
      environment={environment}
      onClose={onClose}
      footer={
        <button type="button" onClick={onClose}>
          Close
        </button>
      }
    >
      <div className="cmmn-scope-drawer" aria-live="polite">
        <p className="cmmn-scope-lead">
          These cases run on a CMMN engine sharing this engine&apos;s tables — excluded from the
          BPMN process failures. This is the read-only scope view: the case lanes, then the FAILED
          lane&apos;s dead-letter jobs (no corrective actions on CMMN yet).
        </p>

        {query.isPending && <p className="muted">Loading…</p>}

        {query.isError && (
          <p className="error-note" role="alert">
            Could not load CMMN scope: {query.error.message}
          </p>
        )}

        {query.isSuccess && (
          <ul className="cmmn-scope-lanes" aria-label="CMMN case lanes">
            {CMMN_STATUSES.map((lane) => {
              const value = laneValue(lane)
              const lower = lane === 'FAILED' && truncated
              return (
                <li key={lane} className={`cmmn-scope-lane cmmn-scope-lane--${lane.toLowerCase()}`}>
                  <span className="cmmn-scope-lane-count">
                    {value == null ? '—' : `${lower ? '≥' : ''}${String(value)}`}
                  </span>
                  <span className="cmmn-scope-lane-label">{lane}</span>
                </li>
              )
            })}
          </ul>
        )}

        {query.isSuccess && (
          <div className="cmmn-scope-failed">
            <h3 className="cmmn-scope-subhead">
              FAILED — dead-letter jobs
              {jobs.length > 0 && (
                <span className="cmmn-scope-count">
                  {' '}
                  ({truncated ? '≥' : ''}
                  {jobs.length}
                  {truncated && ' — lower bound, more lie past the scan cap'})
                </span>
              )}
            </h3>

            {jobs.length === 0 ? (
              <p className="muted">No out-of-scope dead-letters on this engine right now.</p>
            ) : (
              <ul className="cmmn-scope-list">
                {jobs.map((job) => (
                  <li key={job.id} className="cmmn-scope-row">
                    <div className="cmmn-scope-casetype">
                      {job.caseDefinitionName ?? job.caseDefinitionKey ?? 'Unknown case type'}
                      {job.caseDefinitionKey && job.caseDefinitionName && (
                        <span className="cmmn-scope-casekey"> ({job.caseDefinitionKey})</span>
                      )}
                    </div>
                    <div className="cmmn-scope-element">
                      {job.elementName ?? job.elementId ?? '(unnamed element)'}
                    </div>
                    {job.exceptionMessage && (
                      <div className="cmmn-scope-exception">{job.exceptionMessage}</div>
                    )}
                    <dl className="cmmn-scope-meta">
                      {job.caseInstanceId && (
                        <>
                          <dt>Case</dt>
                          <dd className="mono">{job.caseInstanceId}</dd>
                        </>
                      )}
                      {typeof job.retries === 'number' && (
                        <>
                          <dt>Retries</dt>
                          <dd>{job.retries}</dd>
                        </>
                      )}
                      {job.createTime && (
                        <>
                          <dt>Failed</dt>
                          <dd>{job.createTime}</dd>
                        </>
                      )}
                    </dl>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </ModalShell>
  )
}
