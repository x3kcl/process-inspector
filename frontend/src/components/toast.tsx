// Outcome toasts (SPEC §6 tier 0): every success states an explicit delta ("Job 8123
// moved to executable queue; retries reset to 3") plus an audit link — never a bare
// "success". Kept deliberately tiny: a context, a queue, an aria-live region.
import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'

export interface ToastInput {
  kind: 'success' | 'error'
  text: string
  /** Deep-links "view audit" to the instance's Audit & Notes tab. */
  auditPath?: string
}

interface Toast extends ToastInput {
  id: number
}

const DISMISS_MS = 8000

const ToastContext = createContext<(toast: ToastInput) => void>(() => undefined)

export function useToast(): (toast: ToastInput) => void {
  return useContext(ToastContext)
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(0)

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id))
  }, [])

  const push = useCallback(
    (input: ToastInput) => {
      const id = nextId.current++
      setToasts((current) => [...current, { ...input, id }])
      window.setTimeout(() => {
        dismiss(id)
      }, DISMISS_MS)
    },
    [dismiss],
  )

  const value = useMemo(() => push, [push])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast-host" aria-live="polite">
        {toasts.map((toast) => (
          <ToastCard key={toast.id} toast={toast} onDismiss={dismiss} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}

function ToastCard({ toast, onDismiss }: { toast: Toast; onDismiss: (id: number) => void }) {
  const location = useLocation()
  const onAuditTab = toast.auditPath !== undefined && location.search.includes('tab=audit')
  return (
    <div className={`toast toast-${toast.kind}`} role="status">
      <span className="toast-glyph" aria-hidden>
        {toast.kind === 'success' ? '✓' : '✗'}
      </span>
      <span className="toast-text">{toast.text}</span>
      {toast.auditPath !== undefined && !onAuditTab && (
        <Link className="toast-audit-link" to={toast.auditPath}>
          view audit
        </Link>
      )}
      <button
        type="button"
        className="toast-close"
        aria-label="dismiss notification"
        onClick={() => {
          onDismiss(toast.id)
        }}
      >
        ×
      </button>
    </div>
  )
}
