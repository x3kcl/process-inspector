// The tier-3 destructive modal (SPEC §6): restates the target, enumerates cascade
// victims, wears the environment band, requires a reason (≥10 chars), and on PROD gates
// the confirm button behind the exact target-specific typed token — never a generic
// "yes"/"DELETE". Cancel-focused; Enter never submits (both via ModalShell). The confirm
// button restates the action, never says just "Confirm".
import { useState } from 'react'
import type { ReactNode } from 'react'
import { ModalShell } from '../components/ModalShell'
import { TicketField, ticketValue } from '../components/TicketField'
import { reasonRule } from './catalog'
import type { VerbMeta } from './catalog'
import { isReauthChallenge, problemBanner } from './problem'
import type { ActionProblem } from './problem'
import { ReauthNotice, useReauthStale } from './ReauthNotice'

export interface CascadeState {
  /** Call-activity descendants that die with the target; 'loading' while resolving. */
  victims: string[] | 'loading' | 'unavailable'
}

interface Props {
  meta: VerbMeta
  environment?: string
  engineName: string
  /** Target restatement lines (composite id, definition, status, business key…). */
  target: ReactNode
  cascade: CascadeState
  /** PROD typed-token gate: what must be typed and what to call it ("business key"). */
  expectedToken: string
  tokenName: string
  /** The restating confirm label, e.g. `Terminate order-4711 permanently`. */
  confirmLabel: string
  pending: boolean
  problem?: ActionProblem
  /** ticketId is the optional R-AUD-07 capture — undefined when the field stays blank. */
  onConfirm: (reason: string, ticketId?: string) => void
  onClose: () => void
}

export function DestructiveModal({
  meta,
  environment,
  engineName,
  target,
  cascade,
  expectedToken,
  tokenName,
  confirmLabel,
  pending,
  problem,
  onConfirm,
  onClose,
}: Props) {
  const [reason, setReason] = useState('')
  const [ticket, setTicket] = useState('')
  const [typed, setTyped] = useState('')
  const prod = environment?.toLowerCase() === 'prod'
  const rule = reasonRule(meta.tier, environment)
  const reasonOk = reason.trim().length >= rule.minLength
  const tokenOk = !prod || typed === expectedToken
  // An UNKNOWN outcome means the action may have executed — never allow a resubmit
  // from the same modal (corrective-actions skill §4: no blind client-side retry).
  const dispatchedMaybe = problem !== undefined && problem.outcome === 'unknown'
  // Dangerous-set freshness (IDP-SECURITY.md §5): pre-empt at modal OPEN via the /api/me hint,
  // or react to the BFF's 401 reauth-required challenge — either way the interstitial replaces
  // the plain banner and the confirm button stays disabled until the session is fresh again.
  const reauthNeeded = useReauthStale() || (problem !== undefined && isReauthChallenge(problem))

  const footer = (
    <>
      <button type="button" onClick={onClose}>
        Cancel
      </button>
      <button
        type="button"
        className="danger"
        disabled={!reasonOk || !tokenOk || pending || dispatchedMaybe || reauthNeeded}
        title={
          reauthNeeded
            ? 're-authenticate to enable — your sign-in is too old for this action'
            : !reasonOk
              ? 'a reason of at least 10 characters is required'
              : !tokenOk
                ? `type the ${tokenName} exactly to enable`
                : undefined
        }
        onClick={() => {
          onConfirm(reason.trim(), ticketValue(ticket))
        }}
      >
        {pending ? 'Dispatching…' : confirmLabel}
      </button>
    </>
  )

  return (
    <ModalShell
      title={`${meta.label} — ${meta.plain}`}
      environment={environment}
      onClose={onClose}
      footer={footer}
    >
      <p className="modal-verb-badges">
        <span className={`reversibility rev-${meta.reversibility.toLowerCase()}`}>
          {meta.reversibility}
        </span>{' '}
        {meta.reversibilityNote}
      </p>
      <div className="modal-target">
        <p className="modal-target-heading">
          On <strong>{engineName}</strong>
          {prod && ' — a PRODUCTION engine'}:
        </p>
        {target}
      </div>

      <div className="modal-cascade">
        {cascade.victims === 'loading' && (
          <p className="zero-state">Resolving call-activity children…</p>
        )}
        {cascade.victims === 'unavailable' && (
          <p className="strip-note">
            Cascade check unavailable — call-activity children (if any) will be terminated with the
            parent.
          </p>
        )}
        {Array.isArray(cascade.victims) &&
          (cascade.victims.length === 0 ? (
            <p className="strip-note">No call-activity children — this instance dies alone.</p>
          ) : (
            <>
              <p className="cascade-warning">
                ⚠ Cascades to {cascade.victims.length} child instance
                {cascade.victims.length === 1 ? '' : 's'}:
              </p>
              <ul className="cascade-list">
                {cascade.victims.map((victim) => (
                  <li key={victim}>
                    <code>{victim}</code>
                  </li>
                ))}
              </ul>
            </>
          ))}
      </div>

      <label className="modal-field">
        Reason (required, at least 10 characters — lands in the audit trail)
        <textarea
          value={reason}
          rows={2}
          maxLength={2000}
          onChange={(event) => {
            setReason(event.target.value)
          }}
        />
      </label>

      <TicketField value={ticket} onChange={setTicket} />

      {prod && (
        <label className="modal-field">
          Type the {tokenName} <code>{expectedToken}</code> to enable the confirm button
          <input
            type="text"
            value={typed}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setTyped(event.target.value)
            }}
          />
        </label>
      )}

      {reauthNeeded ? (
        <ReauthNotice />
      ) : (
        problem !== undefined && (
          <div className="error-banner" role="alert">
            {problemBanner(problem)}
          </div>
        )
      )}
    </ModalShell>
  )
}
