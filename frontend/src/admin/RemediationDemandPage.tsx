import { RequestIdNote } from '../actions/RequestIdNote'
import { ApiError } from '../api/client'
import { Ts } from '../lib/Ts'
import { useRemediationDemand, type SequenceFinding } from './remediationDemand'

/**
 * `/admin/remediation-demand` — issue #106 S0, the one-time evidence check for the v2
 * remediation-playbooks build trigger (R-GOV-08: "audit analysis over >=3 pilot months
 * shows repeated identical >=2-verb sequences on >=10 instances of one signature"). This
 * page reads a live analysis on every visit; it does not build the playbook feature
 * itself — the verdict here only decides whether that's worth starting.
 */
export function RemediationDemandPage() {
  const analysis = useRemediationDemand()

  if (analysis.error instanceof ApiError && analysis.error.status === 403) {
    return (
      <div className="page">
        <h2>Remediation-demand analysis</h2>
        <p className="muted">
          Requires the <strong>ADMIN</strong> role on at least one engine — the findings surface
          cross-engine operator behavior patterns. You are signed in without it.
        </p>
        {/* R-AUD-04 (#272): the refused read carries a quotable request id — surface it. */}
        <RequestIdNote requestId={analysis.error.requestId} />
      </div>
    )
  }

  return (
    <div className="page">
      <h2>Remediation-demand analysis (#106 S0)</h2>
      <p className="muted">
        R-GOV-08's build trigger for the v2 remediation-playbooks feature: the audit trail must show
        repeated identical 2+-verb sequences on 10+ instances over a 3+ month pilot window before
        that feature is worth building. This mines the audit log live — nothing here is persisted or
        scheduled, and no playbook exists yet regardless of the verdict below.
      </p>
      <p className="strip-note">
        "Sequence" here is the adjacent verb-pair applied to one instance ("signature" isn't
        captured on audit rows today, so this is a verb-pattern proxy, not true error-signature
        grouping — see the analysis service's own doc comment for the full caveat).
      </p>

      {analysis.isPending && <p className="zero-state">Running the analysis…</p>}
      {analysis.isError &&
        !(analysis.error instanceof ApiError && analysis.error.status === 403) && (
          <div className="error-banner" role="alert">
            Analysis unavailable: {analysis.error.message}
          </div>
        )}

      {analysis.data !== undefined && (
        <>
          <div
            className={`banner ${analysis.data.demandTriggerFired === true ? 'banner-warn' : 'banner-info'}`}
            role="status"
          >
            {analysis.data.demandTriggerFired === true ? (
              <>
                <strong>Trigger fired.</strong> The audit trail shows evidence of demand — this is a
                product decision to review, not an automatic build order.
              </>
            ) : (
              <>
                <strong>Trigger not fired.</strong>{' '}
                {analysis.data.spanSufficient !== true
                  ? 'Not enough pilot history yet (under 3 months) — revisit later.'
                  : 'No sequence has crossed the 10-instance threshold yet.'}
              </>
            )}
          </div>

          <table className="ledger-table">
            <tbody>
              <tr>
                <th scope="row">Data span</th>
                <td>
                  {analysis.data.dataSpanStart != null && analysis.data.dataSpanEnd != null ? (
                    <>
                      <Ts iso={analysis.data.dataSpanStart} /> –{' '}
                      <Ts iso={analysis.data.dataSpanEnd} /> ({analysis.data.dataSpanDays ?? 0}{' '}
                      days)
                    </>
                  ) : (
                    'no audit rows yet'
                  )}
                  {analysis.data.spanSufficient !== true && (
                    <span
                      className="count-scoped"
                      title="R-GOV-08 requires >=3 pilot months (~90 days)"
                    >
                      {' '}
                      — under 90 days
                    </span>
                  )}
                </td>
              </tr>
              <tr>
                <th scope="row">Rows scanned</th>
                <td>
                  {analysis.data.scannedRows ?? 0}
                  {analysis.data.truncated === true && (
                    <span
                      className="count-scoped"
                      title="the scan hit its row cap — this picture may be an undercount"
                    >
                      {' '}
                      — truncated, may undercount
                    </span>
                  )}
                </td>
              </tr>
            </tbody>
          </table>

          <h3>Candidate sequences</h3>
          {(analysis.data.sequences?.length ?? 0) === 0 ? (
            <p className="zero-state">No 2+-verb sequence recurred on more than one instance.</p>
          ) : (
            <table className="ledger-table">
              <thead>
                <tr>
                  <th scope="col">Sequence</th>
                  <th scope="col">Instances</th>
                  <th scope="col">Meets threshold (10+)</th>
                  <th scope="col">Sample instances</th>
                </tr>
              </thead>
              <tbody>
                {(analysis.data.sequences ?? []).map((finding: SequenceFinding, i: number) => (
                  <tr
                    key={`${(finding.verbs ?? []).join('→')}-${String(i)}`}
                    className={finding.meetsThreshold === true ? 'item-ok' : undefined}
                  >
                    <td>
                      <code>{(finding.verbs ?? []).join(' → ')}</code>
                    </td>
                    <td>{finding.instanceCount ?? 0}</td>
                    <td>{finding.meetsThreshold === true ? 'yes' : 'no'}</td>
                    <td>
                      <code>{(finding.sampleInstances ?? []).join(', ')}</code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </div>
  )
}
