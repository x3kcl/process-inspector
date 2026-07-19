// The incident detail's Reopen action (INCIDENT-LEDGER.md §6/§8, OPERATOR) — a human
// "resolved by mistake" undo, distinct from an automatic REGRESSED transition. A small
// confirm: reason ≥10 required (R-AUD-10 — un-claiming a "we fixed this" attestation is a
// moderation act too, same doctrine as triage's un-acknowledge), same guard idiom as
// ResolveIncidentModal/AcknowledgeGroupModal — no prod typed-token (BFF-store-only, no
// engine target).
import type { IncidentSummary } from '../api/model'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { ActionHint } from '../components/ActionHint'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { useToast } from '../components/toast'
import { useReopenIncident } from './useIncidents'

interface Props {
  incident: IncidentSummary
  environment: string | undefined
  onClose: () => void
}

export function ReopenIncidentModal({ incident, environment, onClose }: Props) {
  const toast = useToast()
  const reopen = useReopenIncident(incident.id ?? 0)
  const guard = useProdGuard({ reasonRule: { required: true, minLength: 10 } })
  const { reasonOk } = guard

  const confirm = () => {
    reopen.mutate(
      { reason: guard.reason.trim() },
      {
        onSuccess: () => {
          toast({ kind: 'success', text: 'Incident reopened.' })
          onClose()
        },
      },
    )
  }

  const problem = reopen.error?.problem
  const shortReason = !reasonOk ? 'Reason too short — 10+ characters' : undefined

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <div className="action-slot">
        <button
          type="button"
          disabled={!reasonOk || reopen.isPending}
          aria-describedby={shortReason !== undefined ? 'reopen-incident-hint' : undefined}
          onClick={confirm}
        >
          {reopen.isPending ? 'Reopening…' : 'Reopen incident'}
        </button>
        {shortReason !== undefined && (
          <ActionHint id="reopen-incident-hint" text={shortReason} tone="gate" />
        )}
      </div>
    </>
  )

  return (
    <ModalShell
      title="Reopen incident — undo a resolve"
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <div className="modal-target">
        <p>
          Error class <code>{incident.exceptionClass ?? '(unknown exception)'}</code>
        </p>
        <p className="normalized-message">{incident.normalizedMessage ?? '(no message)'}</p>
        <p className="strip-note">
          Reopens the last episode (its resolve metadata is cleared) and moves the state back to
          OPEN. This is a human undo — it does NOT count as a regression (regression is only ever
          automatic, from fresh failures after a zero-state gate).
        </p>
      </div>

      <GuardFields
        guard={guard}
        reasonLabel="Why are you reopening this? (required, 10+ characters — saved to the audit trail)"
      />

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}
