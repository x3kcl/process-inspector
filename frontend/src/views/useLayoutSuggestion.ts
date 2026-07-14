// #197 (docs/SHARED-VIEWS.md §8): the confirm-prompt that replaces a blind "views always
// win" or "the viewer always wins" precedence rule — neither survived adversarial review.
// Opening a view whose search string carries a `cols` suggestion different from the
// viewer's current columns offers it once; the viewer's own answer (or their own edit via
// ColumnChooser) is remembered per (search-identity, suggested-layout) pair, never re-asked.
import { useEffect, useMemo, useRef, useState } from 'react'
import { HIDEABLE_COLUMNS } from '../components/ColumnChooser'
import { setHiddenColumns, useHiddenColumns } from '../lib/columnVisibility'
import { decisionKey, getLayoutDecision, recordLayoutDecision } from '../lib/viewLayoutDecisions'
import { decodeHiddenColumns } from '../search/urlState'

const HIDEABLE_IDS = new Set(HIDEABLE_COLUMNS.map((column) => column.id))

function setsEqual(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) return false
  for (const value of a) if (!b.has(value)) return false
  return true
}

function searchIdentity(paramsKey: string): string {
  const params = new URLSearchParams(paramsKey)
  params.delete('cols')
  params.sort()
  return params.toString()
}

export interface LayoutSuggestion {
  /** Non-null only while a suggestion is genuinely pending a viewer decision. */
  cols: ReadonlySet<string> | null
  apply: () => void
  dismiss: () => void
}

export function useLayoutSuggestion(paramsKey: string): LayoutSuggestion {
  const current = useHiddenColumns()
  const [dismissedKey, setDismissedKey] = useState<string | null>(null)

  const suggested = useMemo(() => {
    const raw = decodeHiddenColumns(new URLSearchParams(paramsKey))
    if (raw === null) return null
    const valid = new Set([...raw].filter((id) => HIDEABLE_IDS.has(id)))
    return valid
  }, [paramsKey])

  const key = useMemo(
    () => (suggested !== null ? decisionKey(searchIdentity(paramsKey), suggested) : null),
    [paramsKey, suggested],
  )

  const pending =
    suggested !== null &&
    key !== null &&
    !setsEqual(suggested, current) &&
    dismissedKey !== key &&
    getLayoutDecision(key) === undefined

  // A manual ColumnChooser edit while a suggestion is pending counts as "keep mine" — the
  // viewer is visibly making their own choice, so the banner must clear even if their edit
  // happens NOT to land on the exact suggested set (the common case: pending is driven by
  // suggested-vs-current inequality, which a manual edit doesn't necessarily resolve on its
  // own). One consolidated effect, not two independent ones — splitting "notice a change"
  // from "notice a new suggestion" into separate effects let a stale `pending` closure
  // re-arm shownKeyRef in the same pass it was just cleared in (caught by the hook's own
  // test suite before this ever shipped). apply() marks its own change as self-inflicted so
  // it isn't mistaken for a manual edit and double-recorded.
  const shownKeyRef = useRef<string | null>(null)
  const appliedByMeRef = useRef(false)
  const prevCurrentRef = useRef(current)
  useEffect(() => {
    const currentChanged = prevCurrentRef.current !== current
    prevCurrentRef.current = current
    if (currentChanged && shownKeyRef.current !== null && !appliedByMeRef.current) {
      const shownKey = shownKeyRef.current
      if (getLayoutDecision(shownKey) === undefined) {
        recordLayoutDecision(shownKey, 'dismissed')
        setDismissedKey(shownKey)
      }
      shownKeyRef.current = null
      appliedByMeRef.current = false
      return // don't also re-arm below on this pass — `pending` here predates the dismiss;
      // the NEXT render (triggered by setDismissedKey) recomputes it correctly.
    }
    appliedByMeRef.current = false
    // pending's own definition already requires key !== null.
    if (pending) shownKeyRef.current = key
  }, [current, pending, key])

  return {
    cols: pending ? suggested : null,
    apply: () => {
      if (suggested === null || key === null) return
      appliedByMeRef.current = true
      shownKeyRef.current = null
      setHiddenColumns(suggested)
      recordLayoutDecision(key, 'applied')
    },
    dismiss: () => {
      if (key === null) return
      shownKeyRef.current = null
      setDismissedKey(key)
      recordLayoutDecision(key, 'dismissed')
    },
  }
}
