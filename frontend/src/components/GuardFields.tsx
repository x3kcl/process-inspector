// U5 (#88): the reason textarea + conditional prod typed-token input every confirm modal
// repeats verbatim. Presentational only — state/validity comes from useProdGuard
// (../actions/guard.ts). The ticket field stays a separate composed sibling (<TicketField>) —
// several guarded modals omit it, so it isn't folded in here.
import type { ReactNode } from 'react'
import type { ProdGuard } from '../actions/guard'

/** The standard "Type the {name} X to enable the {verb} button" phrasing. */
export function tokenLabel(tokenName: string, expectedToken: string, verb = 'confirm'): ReactNode {
  return (
    <>
      Type the {tokenName} <code>{expectedToken}</code> to enable the {verb} button
    </>
  )
}

interface Props {
  guard: ProdGuard
  /** Defaults to the SPEC §6 standard copy; override for a shorter/action-specific prompt. */
  reasonLabel?: string
  expectedToken?: string
  /** Rendered only when `guard.needsToken`. Defaults to `tokenLabel(tokenName, expectedToken)` —
   *  pass a custom node for copy that doesn't fit that template (e.g. no "the {name}" prefix). */
  tokenFieldLabel?: ReactNode
  /** aria-invalid on the reason field; default true only mirrors the newer modals' a11y wiring
   *  (older modals never set it — opting in is always safe, so this defaults on). */
  reasonAriaInvalid?: boolean
}

export function GuardFields({
  guard,
  reasonLabel = 'Reason (required, at least 10 characters — lands in the audit trail)',
  expectedToken,
  tokenFieldLabel,
  reasonAriaInvalid = true,
}: Props) {
  return (
    <>
      <label className="modal-field">
        {reasonLabel}
        <textarea
          value={guard.reason}
          rows={2}
          maxLength={2000}
          aria-invalid={reasonAriaInvalid ? !guard.reasonOk : undefined}
          onChange={(event) => {
            guard.setReason(event.target.value)
          }}
        />
      </label>

      {guard.needsToken && (
        <label className="modal-field">
          {tokenFieldLabel ?? tokenLabel(expectedToken ?? '', expectedToken ?? '')}
          <input
            type="text"
            value={guard.typed}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={!guard.tokenOk}
            onChange={(event) => {
              guard.setTyped(event.target.value)
            }}
          />
        </label>
      )}
    </>
  )
}
