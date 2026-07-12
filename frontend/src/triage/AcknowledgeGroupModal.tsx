// The R-BAU-01 acknowledge / un-acknowledge confirm (SPEC §4 Stage 0). BFF-store-only:
// acknowledging mutes the triage card — it NEVER touches engine state (the honest
// alternative to the Suspend workaround, which does). Coordinates only cross the wire;
// the server resolves the baselines and re-checks OPERATOR per engine. The reason (≥10)
// is mandatory BOTH ways — un-muting a class the shift relies on is a moderation act too.
import { useState } from 'react'
import type { ErrorGroup } from '../api/model'
import { useAcknowledgeErrorGroup, useUnacknowledgeErrorGroup } from '../api/ack'
import { useProdGuard } from '../actions/guard'
import { problemBanner } from '../actions/problem'
import { ActionHint } from '../components/ActionHint'
import { GuardFields } from '../components/GuardFields'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { useToast } from '../components/toast'

/** Relative expiry presets — timezone-honest by construction (computed as an instant). */
const EXPIRY_PRESETS = [
  { id: 'none', label: 'No expiry — until un-acknowledged or auto-resurfaced', hours: null },
  { id: '4h', label: '4 hours (rest of this shift)', hours: 4 },
  { id: '24h', label: '24 hours', hours: 24 },
  { id: '3d', label: '3 days', hours: 72 },
  { id: '7d', label: '7 days', hours: 168 },
  { id: '30d', label: '30 days', hours: 720 },
] as const

type ExpiryId = (typeof EXPIRY_PRESETS)[number]['id']

interface Props {
  group: ErrorGroup
  mode: 'acknowledge' | 'unacknowledge'
  /** The group engines' most severe environment (honesty band on the shell). */
  environment: string | undefined
  onClose: () => void
}

export function AcknowledgeGroupModal({ group, mode, environment, onClose }: Props) {
  const toast = useToast()
  const acknowledge = useAcknowledgeErrorGroup()
  const unacknowledge = useUnacknowledgeErrorGroup()
  const [expiry, setExpiry] = useState<ExpiryId>('none')
  // Reason ≥10 for BOTH verbs (the BFF refuses under 10 unconditionally on these doors) — a
  // literal always-required rule, not tier/environment-driven (this never touches an engine).
  const guard = useProdGuard({ reasonRule: { required: true, minLength: 10 } })
  const { reasonOk } = guard

  const acking = mode === 'acknowledge'
  const submitting = acknowledge.isPending || unacknowledge.isPending
  const problem = (acking ? acknowledge : unacknowledge).error?.problem
  const coordinatesOk = group.signatureHash != null && group.algoVersion != null

  const confirm = () => {
    if (acking) {
      const preset = EXPIRY_PRESETS.find((candidate) => candidate.id === expiry)
      const expiresAt =
        preset?.hours != null
          ? new Date(Date.now() + preset.hours * 3_600_000).toISOString()
          : undefined
      acknowledge.mutate(
        {
          signatureHash: group.signatureHash,
          algoVersion: group.algoVersion,
          reason: guard.reason.trim(),
          ticketId: ticketValue(guard.ticket),
          expiresAt,
        },
        {
          onSuccess: () => {
            toast({
              kind: 'success',
              text:
                'Group acknowledged — it collapses into the "Acknowledged" section and ' +
                'auto-resurfaces on growth, a new failing version, or expiry.',
            })
            onClose()
          },
        },
      )
    } else {
      unacknowledge.mutate(
        {
          signatureHash: group.signatureHash,
          algoVersion: group.algoVersion,
          reason: guard.reason.trim(),
        },
        {
          onSuccess: () => {
            toast({ kind: 'success', text: 'Acknowledgment removed — the group is active again.' })
            onClose()
          },
        },
      )
    }
  }

  const shortReason = !coordinatesOk
    ? 'Group stale — refresh the landing'
    : !reasonOk
      ? 'Reason too short — 10+ characters'
      : undefined

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <div className="action-slot">
        <button
          type="button"
          disabled={!reasonOk || !coordinatesOk || submitting}
          aria-describedby={shortReason !== undefined ? 'ack-submit-hint' : undefined}
          onClick={confirm}
        >
          {submitting ? 'Saving…' : acking ? 'Acknowledge group' : 'Un-acknowledge group'}
        </button>
        {shortReason !== undefined && (
          <ActionHint id="ack-submit-hint" text={shortReason} tone="gate" />
        )}
      </div>
    </>
  )

  return (
    <ModalShell
      title={
        acking
          ? 'Acknowledge group — collapse this known error class on the landing'
          : 'Un-acknowledge group — return this error class to the active list'
      }
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <div className="modal-target">
        <p>
          Error class <code>{group.exceptionClass ?? '(unknown exception)'}</code>
        </p>
        <p className="normalized-message">{group.normalizedMessage ?? '(no message)'}</p>
        <p className="strip-note">
          {acking
            ? 'Acknowledging mutes the CARD only — nothing changes on any engine, and the ' +
              'group stays visible under "Acknowledged (N)". It resurfaces automatically ' +
              'when the member count grows past the acknowledged baseline, a new definition ' +
              'version starts failing, or the expiry passes.'
            : 'Removes the acknowledgment for everyone — the group returns to the active ' +
              'failure list on the next landing refresh.'}
        </p>
      </div>

      <GuardFields
        guard={guard}
        reasonLabel="Why? (required, 10+ characters — recorded in the operations log)"
      />

      {acking && (
        <>
          <TicketField value={guard.ticket} onChange={guard.setTicket} />
          <label className="modal-field">
            Expiry (optional — the acknowledgment resurfaces the group when it passes)
            <select
              value={expiry}
              onChange={(event) => {
                setExpiry(event.target.value as ExpiryId)
              }}
            >
              {EXPIRY_PRESETS.map((preset) => (
                <option key={preset.id} value={preset.id}>
                  {preset.label}
                </option>
              ))}
            </select>
          </label>
        </>
      )}

      {problem !== undefined && (
        <div className="error-banner" role="alert">
          {problemBanner(problem)}
        </div>
      )}
    </ModalShell>
  )
}
