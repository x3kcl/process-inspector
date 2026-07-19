// The incident detail's Resolve action (INCIDENT-LEDGER.md §6/§8, OPERATOR). Mirrors
// triage/AcknowledgeGroupModal.tsx's guard idiom exactly — this never touches an engine
// either (a BFF-store-only config event, per IncidentController's own javadoc), so the SAME
// always-required reason≥10 rule applies with no prod typed-token. The one thing this modal
// alone needs: an explicit, UNCHECKED-by-default "also acknowledge on the live dashboard"
// checkbox (§6's opt-in `alsoAcknowledge`) with a one-sentence explanation that resolve ≠
// ack — and, when checked, the per-slice ack outcomes the response carries back are shown
// in place before the modal closes (a bare toast can't hold a per-engine×definition list).
import { useState } from 'react'
import type { IncidentSummary } from '../api/model'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { ActionHint } from '../components/ActionHint'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'
import { useResolveIncident, type IncidentResolution } from './useIncidents'

interface Props {
  incident: IncidentSummary
  environment: string | undefined
  onClose: () => void
}

export function ResolveIncidentModal({ incident, environment, onClose }: Props) {
  const toast = useToast()
  const resolve = useResolveIncident(incident.id ?? 0)
  const guard = useProdGuard({ reasonRule: { required: true, minLength: 10 } })
  const [alsoAcknowledge, setAlsoAcknowledge] = useState(false)
  const [result, setResult] = useState<IncidentResolution | null>(null)
  const { reasonOk } = guard

  const confirm = () => {
    resolve.mutate(
      {
        reason: guard.reason.trim(),
        ticketId: ticketValue(guard.ticket),
        alsoAcknowledge,
      },
      {
        onSuccess: (resolution) => {
          // With also-acknowledge opted in, the per-slice outcomes are worth reading before
          // the modal disappears — hold it open and show them instead of an immediate close.
          if (alsoAcknowledge && (resolution.acknowledgements?.length ?? 0) > 0) {
            setResult(resolution)
            return
          }
          toast({ kind: 'success', text: 'Incident resolved.' })
          onClose()
        },
      },
    )
  }

  const problem = resolve.error?.problem
  const shortReason = !reasonOk ? 'Reason too short — 10+ characters' : undefined

  if (result !== null) {
    return (
      <ModalShell
        title="Incident resolved"
        environment={environment}
        onClose={onClose}
        footer={
          <button type="button" onClick={onClose}>
            Close
          </button>
        }
      >
        <p>Incident resolved. Also-acknowledge outcomes, per engine × definition:</p>
        <ul className="resolve-ack-outcomes">
          {(result.acknowledgements ?? []).map((outcome, index) => (
            <li
              key={`${outcome.engineId ?? ''}:${outcome.definitionKey ?? ''}:${String(index)}`}
              className={outcome.acknowledged === true ? 'resolve-ack-ok' : 'resolve-ack-failed'}
            >
              {outcome.engineId ?? '(unknown engine)'} ·{' '}
              {outcome.definitionKey ?? '(all definitions)'}
              {' — '}
              {outcome.acknowledged === true ? 'acknowledged' : 'not acknowledged'}
              {outcome.message !== undefined && outcome.message !== '' && <> ({outcome.message})</>}
            </li>
          ))}
        </ul>
      </ModalShell>
    )
  }

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <div className="action-slot">
        <button
          type="button"
          disabled={!reasonOk || resolve.isPending}
          aria-describedby={shortReason !== undefined ? 'resolve-incident-hint' : undefined}
          onClick={confirm}
        >
          {resolve.isPending ? 'Resolving…' : 'Resolve incident'}
        </button>
        {shortReason !== undefined && (
          <ActionHint id="resolve-incident-hint" text={shortReason} tone="gate" />
        )}
      </div>
    </>
  )

  return (
    <ModalShell
      title="Resolve incident — close the current episode"
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
          Closes the current episode and moves this incident to RESOLVED. If it starts failing again
          later, a new episode opens and it moves to REGRESSED — this is history, never deleted.
        </p>
      </div>

      <GuardFields
        guard={guard}
        reasonLabel="Why are you resolving this? (required, 10+ characters — saved to the audit trail)"
      />

      <TicketField value={guard.ticket} onChange={guard.setTicket} />

      <label className="modal-field checkbox-field">
        <input
          type="checkbox"
          checked={alsoAcknowledge}
          onChange={(event) => {
            setAlsoAcknowledge(event.target.checked)
          }}
        />
        Also acknowledge on the live dashboard
      </label>
      <p className="strip-note">
        Resolving here does NOT mute the Stage-0 triage card by itself — acknowledging is a
        separate, per-engine action. Check this to also acknowledge every engine × definition this
        incident currently touches, in one step.
      </p>

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}
