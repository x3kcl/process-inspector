// Drawer state lifted out of OpsDrawer (v1.x #1): any surface that dispatches a bulk job
// (BulkBar, the triage retry-group) must be able to open the drawer focused on the fresh
// job — the 202 handoff. Only UI state lives here; job DATA stays server-side (['bulk-jobs']
// polling), so this context carries no optimistic anything.
import { createContext, useCallback, useContext, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

interface OpsDrawerState {
  open: boolean
  /** The job to auto-expand + highlight after a dispatch handoff; null = none. */
  focusJobId: string | null
  setOpen: (open: boolean) => void
  /** The dispatch handoff: opens the drawer focused on the freshly minted job. */
  focusJob: (jobId: string) => void
  clearFocus: () => void
}

const OpsDrawerContext = createContext<OpsDrawerState | null>(null)

export function OpsDrawerProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const [focusJobId, setFocusJobId] = useState<string | null>(null)
  const focusJob = useCallback((jobId: string) => {
    setFocusJobId(jobId)
    setOpen(true)
  }, [])
  const clearFocus = useCallback(() => {
    setFocusJobId(null)
  }, [])
  const value = useMemo<OpsDrawerState>(
    () => ({ open, focusJobId, setOpen, focusJob, clearFocus }),
    [open, focusJobId, focusJob, clearFocus],
  )
  return <OpsDrawerContext.Provider value={value}>{children}</OpsDrawerContext.Provider>
}

export function useOpsDrawer(): OpsDrawerState {
  const ctx = useContext(OpsDrawerContext)
  if (ctx === null) throw new Error('useOpsDrawer requires an <OpsDrawerProvider> ancestor')
  return ctx
}
