// The confirm-modal frame (SPEC §6): environment color band, cancel-focused, Escape
// closes, and Enter NEVER submits — confirms are explicit button clicks only. Reused by
// the tier-3 destructive modal and the §4a verification modal.
//
// U2 (#88): a real FOCUS TRAP — Tab (and Shift+Tab) cycle within the modal instead of
// escaping to the page behind it (an accessibility + mis-click hazard on a destructive
// dialog), and focus is RESTORED to whatever opened the modal when it closes. Applied here,
// in the shared shell, so every modal that uses it inherits the trap at once.
import { useEffect, useRef } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'

interface Props {
  title: string
  environment?: string
  onClose: () => void
  children: ReactNode
  /** The action row (cancel + the restating confirm button) — rendered pinned last. */
  footer: ReactNode
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export function ModalShell({ title, environment, onClose, children, footer }: Props) {
  const cardRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const card = cardRef.current
    // Remember what had focus so it can be handed back when the modal closes (focus restore).
    const previouslyFocused =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    // Cancel-focused: the first button in the footer is by convention the cancel button.
    card?.querySelector<HTMLButtonElement>('.modal-footer button')?.focus()
    return () => {
      previouslyFocused?.focus()
    }
  }, [])

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      onClose()
      return
    }
    // Enter never submits (SPEC §6): swallow it unless a textarea is deliberately in play
    // (native button semantics keep working via Space / click).
    if (event.key === 'Enter' && !(event.target instanceof HTMLTextAreaElement)) {
      event.preventDefault()
      return
    }
    // Focus trap (U2): keep Tab within the modal card — wrap last→first, first→last.
    if (event.key === 'Tab') {
      const card = cardRef.current
      if (card === null) return
      const focusables = Array.from(card.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (focusables.length === 0) {
        event.preventDefault() // nothing focusable inside → never let Tab leak to the page
        return
      }
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement
      if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }
  }

  const env = environment?.toLowerCase() ?? 'unknown'

  return (
    <div className="modal-backdrop" onKeyDown={onKeyDown}>
      <div
        ref={cardRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`modal-card env-band-${env}`}
      >
        <header className="modal-header">
          <span className={`env-badge env-${env}`}>{env.toUpperCase()}</span>
          <h3>{title}</h3>
        </header>
        <div className="modal-body">{children}</div>
        <footer className="modal-footer">{footer}</footer>
      </div>
    </div>
  )
}
