// The tier-3 destructive confirm for deleting a CMMN dead-letter job (Case Inspector Phase 3).
// The CMMN sibling of the BPMN DestructiveModal, but scope-honest: a CMMN case has no
// call-activity cascade and — unlike a BPMN process — no change-state rescue in this tool, so the
// blast-radius copy states the true consequence rather than reusing the process-instance text.
// Rails (SPEC §6 / corrective-actions skill): reason ≥ 10 chars, PROD typed-token gate (the job
// id), cancel-focused, Enter never submits (both via ModalShell), and a dispatched-UNKNOWN outcome
// can never be resubmitted from the same modal.
import { reasonRule, VERBS } from '../actions/catalog'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import type { ActionProblem } from '../actions/problem'
import { GuardFields, tokenLabel } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'

const META = VERBS.deleteDeadletter

interface Props {
  environment?: string
  engineName: string
  jobId: string
  elementName?: string
  exceptionMessage?: string
  pending: boolean
  problem?: ActionProblem
  onConfirm: (reason: string) => void
  onClose: () => void
}

export function CaseDeleteModal({
  environment,
  engineName,
  jobId,
  elementName,
  exceptionMessage,
  pending,
  problem,
  onConfirm,
  onClose,
}: Props) {
  const guard = useProdGuard({
    reasonRule: reasonRule(META.tier, environment),
    environment,
    expectedToken: jobId,
  })
  const { reason, reasonOk, tokenOk } = guard
  const prod = guard.prod
  // An UNKNOWN outcome means the delete may already have executed — never allow a resubmit
  // from the same modal (corrective-actions skill §4: no blind client-side retry).
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button
        type="button"
        className="danger"
        disabled={!reasonOk || !tokenOk || pending || dispatchedMaybe}
        title={
          !reasonOk
            ? 'a reason of at least 10 characters is required'
            : !tokenOk
              ? 'type the job id exactly to enable'
              : undefined
        }
        onClick={() => {
          onConfirm(reason.trim())
        }}
      >
        {pending ? 'Dispatching…' : `Delete dead-letter job ${jobId}`}
      </button>
    </>
  )

  return (
    <ModalShell
      title={`${META.label} — ${META.plain}`}
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <p className="modal-verb-badges">
        <span className={`reversibility rev-${META.reversibility.toLowerCase()}`}>
          {META.reversibility}
        </span>{' '}
        the job is discarded; the plan-item execution is orphaned
      </p>
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engineName}</strong>
          {prod && ' — a PRODUCTION engine'}:
        </p>
        <ul className="modal-target-list">
          <li>
            CMMN dead-letter job <code>{jobId}</code>
          </li>
          {elementName !== undefined && <li>Plan item {elementName}</li>}
          {exceptionMessage !== undefined && <li>Exception {firstLine(exceptionMessage)}</li>}
          <li className="cascade-warning">
            ⚠ The plan item is orphaned permanently — the case can never continue past this step on
            its own. Unlike a BPMN process, a CMMN case has no change-state rescue in this tool.
          </li>
        </ul>
      </div>

      <GuardFields
        guard={guard}
        expectedToken={jobId}
        tokenFieldLabel={tokenLabel('job id', jobId)}
      />

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}

/** First non-empty line of a (possibly multi-line) stacktrace, for a one-line target restatement. */
function firstLine(text: string): string {
  const line = text.split('\n', 1)[0]?.trim() ?? ''
  return line.length > 160 ? `${line.slice(0, 157)}…` : line
}
