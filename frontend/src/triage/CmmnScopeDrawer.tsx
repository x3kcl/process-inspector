import { useQuery } from '@tanstack/react-query'
import { fetchOutOfScopeDeadLetters } from '../api/queries'
import { ModalShell } from '../components/ModalShell'

/**
 * Case Inspector Phase 1 (R-SEM-20): the drill behind the Stage-0 "≥N CMMN jobs not triaged
 * here" note. Read-only enumeration of the out-of-scope (CMMN) dead-letter jobs on ONE engine
 * — the same dead-letters the BPMN join excludes, now with their case attribution so an
 * operator can see WHAT is failing on the co-deployed CMMN engine (they can't act on them here
 * — CMMN corrective actions are Phase 3).
 *
 * The list carries the SAME truncation honesty as the count: when the bounded scan hit the
 * cap, the rows are a labeled lower bound (≥), never a silently-exact subset.
 */
export function CmmnScopeDrawer({ engineId, onClose }: { engineId: string; onClose: () => void }) {
  const query = useQuery({
    queryKey: ['out-of-scope-deadletters', engineId],
    queryFn: () => fetchOutOfScopeDeadLetters(engineId),
    staleTime: 15_000,
  })

  const jobs = query.data?.jobs ?? []
  const truncated = query.data?.truncated === true

  return (
    <ModalShell
      title={`Out-of-scope dead-letters — ${engineId}`}
      onClose={onClose}
      footer={
        <button type="button" onClick={onClose}>
          Close
        </button>
      }
    >
      <div className="cmmn-scope-drawer" aria-live="polite">
        <p className="cmmn-scope-lead">
          These dead-letter jobs belong to a CMMN engine sharing this engine&apos;s job tables. They
          sit in the raw dead-letter lane but are excluded from the process failures below — this is
          the read-only drill (no corrective actions on CMMN jobs yet).
        </p>

        {query.isPending && <p className="muted">Loading…</p>}

        {query.isError && (
          <p className="error-note" role="alert">
            Could not load out-of-scope dead-letters: {query.error.message}
          </p>
        )}

        {query.isSuccess && jobs.length === 0 && (
          <p className="muted">No out-of-scope dead-letters on this engine right now.</p>
        )}

        {jobs.length > 0 && (
          <>
            <p className="cmmn-scope-count">
              {truncated ? '≥' : ''}
              {jobs.length} CMMN dead-letter job{jobs.length === 1 ? '' : 's'}
              {truncated && ' — lower bound (more lie past the scan cap)'}
            </p>
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
          </>
        )}
      </div>
    </ModalShell>
  )
}
