// The confirm-modal frame (SPEC §6): environment color band, cancel-focused, Escape
// closes, and Enter NEVER submits — confirms are explicit button clicks only. Reused by
// the tier-3 destructive modal and the §4a verification modal.
import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'

interface Props {
  title: string
  environment?: string
  onClose: () => void
  children: ReactNode
  /** The action row (cancel + the restating confirm button) — rendered pinned last. */
  footer: ReactNode
}

export function ModalShell({ title, environment, onClose, children, footer }: Props) {
  const cancelTrapRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const host = cancelTrapRef.current
    // Cancel-focused: the first button in the footer is by convention the cancel button.
    const cancel = host?.querySelector<HTMLButtonElement>('.modal-footer button')
    cancel?.focus()
  }, [])

  const env = environment?.toLowerCase() ?? 'unknown'

  return (
    <div
      className="modal-backdrop"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onClose()
        // Enter never submits (SPEC §6): swallow it unless a button is deliberately
        // activated by keyboard focus (native button semantics keep working via Space).
        if (event.key === 'Enter' && !(event.target instanceof HTMLTextAreaElement)) {
          event.preventDefault()
        }
      }}
    >
      <div
        ref={cancelTrapRef}
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
