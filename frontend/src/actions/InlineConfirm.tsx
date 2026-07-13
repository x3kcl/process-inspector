// Tier-0 action button (SPEC §5.0/§6): no modal ever. On prod, verbs with irreversible
// external side effects get the two-step inline confirm (click → "Fire timer for job
// 8123?" → click, sub-second); queue-state-only verbs stay single-click. The armed state
// disarms itself after a beat so a stray first click cannot linger as a landmine.
import { useEffect, useRef, useState } from 'react'
import { ActionHint } from '../components/ActionHint'
import type { Gate, VerbMeta } from './catalog'

const DISARM_MS = 5000

interface Props {
  meta: VerbMeta
  gate: Gate
  /** The restating question shown when armed, e.g. `Fire timer for job 8123?`. */
  confirmText: string
  twoStep: boolean
  pending: boolean
  /** W2 #2 (SPEC §5.0): render the verb's reversibility badge VISIBLY next to the button —
   *  for the header-level suspend/activate pair; per-row job tables keep it in the title. */
  showReversibility?: boolean
  onConfirm: () => void
}

export function InlineConfirm({
  meta,
  gate,
  confirmText,
  twoStep,
  pending,
  showReversibility = false,
  onConfirm,
}: Props) {
  const [armed, setArmed] = useState(false)
  const timer = useRef<number>()
  // #168: the armed and base branches render structurally different JSX at the same tree
  // position, so React unmounts the (currently-focused) armed button and mounts a fresh base
  // button on confirm/cancel — the browser's default for a focused node leaving the DOM is
  // to drop focus to <body>, silently, with no indication anything happened. Explicit
  // user action (confirm or cancel — NOT the auto-disarm timeout, which must never steal
  // focus from wherever the user has since moved on to) re-focuses the surviving control.
  const baseButtonRef = useRef<HTMLButtonElement>(null)
  const refocusBaseButton = useRef(false)

  useEffect(
    () => () => {
      window.clearTimeout(timer.current)
    },
    [],
  )

  useEffect(() => {
    if (!armed && refocusBaseButton.current) {
      refocusBaseButton.current = false
      baseButtonRef.current?.focus()
    }
  }, [armed])

  const disabled = !gate.enabled || pending
  const title = !gate.enabled
    ? (gate.detail ?? gate.reason)
    : `${meta.plain} · ${meta.reversibility}: ${meta.reversibilityNote}`
  const hintId = `inline-confirm-hint-${meta.verb}`

  if (armed && twoStep) {
    return (
      <span className="inline-confirm">
        <button
          type="button"
          className="copy-btn confirm-armed"
          disabled={pending}
          onClick={() => {
            refocusBaseButton.current = true
            setArmed(false)
            window.clearTimeout(timer.current)
            onConfirm()
          }}
        >
          {confirmText}
        </button>
        <button
          type="button"
          className="copy-btn"
          aria-label="cancel"
          onClick={() => {
            refocusBaseButton.current = true
            setArmed(false)
            window.clearTimeout(timer.current)
          }}
        >
          ×
        </button>
      </span>
    )
  }

  return (
    <span className="action-slot">
      <button
        ref={baseButtonRef}
        type="button"
        className="copy-btn action-btn"
        disabled={disabled}
        title={title}
        aria-describedby={!gate.enabled ? hintId : undefined}
        onClick={() => {
          if (!twoStep) {
            onConfirm()
            return
          }
          setArmed(true)
          window.clearTimeout(timer.current)
          timer.current = window.setTimeout(() => {
            setArmed(false)
          }, DISARM_MS)
        }}
      >
        {pending ? `${meta.label}…` : meta.label}
      </button>
      {showReversibility && (
        <span
          className={`reversibility rev-${meta.reversibility.toLowerCase()}`}
          title={meta.reversibilityNote}
        >
          {meta.reversibility}
        </span>
      )}
      {!gate.enabled && gate.reason !== undefined && (
        <ActionHint id={hintId} text={gate.reason} tone="gate" />
      )}
    </span>
  )
}
