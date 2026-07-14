// R-UXQ-09 (SPEC §10a, issue #104 slice 4/6): the persisted results-grid column-visibility
// store. Mirrors ./density.ts's structure (lazy module-level value hydrated from
// localStorage, a listener Set, get/set/subscribe + a useSyncExternalStore hook) but the
// SHAPE here is a SET of hidden column ids, not a single enum value — so the getter returns
// a ReadonlySet and the setter takes a single (colId, hidden) pair rather than replacing the
// whole value. Unlike density.ts/theme.ts there is no module-load DOM side effect: this store
// never touches document.documentElement, it only feeds ResultsGrid's own column defs.
import { useSyncExternalStore } from 'react'

const STORAGE_KEY = 'inspector.resultsGridHiddenColumns'

// Lazy so the localStorage read happens on first use, not import (testable, SSR-safe) — same
// rationale as density.ts's `density`.
let hiddenColumns: Set<string> | null = null
const listeners = new Set<() => void>()

function storedHiddenColumns(): Set<string> {
  try {
    const raw = globalThis.localStorage.getItem(STORAGE_KEY)
    if (raw === null) return new Set()
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return new Set()
    return new Set(parsed.filter((value): value is string => typeof value === 'string'))
  } catch {
    // No storage (private mode / node) or corrupt JSON — the honest default is "nothing
    // hidden", matching today's behavior exactly (no visual change until the user acts).
    return new Set()
  }
}

function persist(next: Set<string>): void {
  try {
    globalThis.localStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(next)))
  } catch {
    // Persistence unavailable — the in-memory choice still holds for this session.
  }
}

function notify(): void {
  for (const listener of listeners) listener()
}

/** The current hidden-column-id set. Read-only — callers must go through setColumnHidden. */
export function getHiddenColumns(): ReadonlySet<string> {
  hiddenColumns ??= storedHiddenColumns()
  return hiddenColumns
}

/** Toggle a single column's hidden state (identity key = its ColDef colId or field). */
export function setColumnHidden(colId: string, hidden: boolean): void {
  const current = new Set(getHiddenColumns())
  if (hidden) {
    current.add(colId)
  } else {
    current.delete(colId)
  }
  hiddenColumns = current
  persist(current)
  notify()
}

/** Back to the default (empty set = every hideable column visible). */
export function resetColumnVisibility(): void {
  hiddenColumns = new Set()
  persist(hiddenColumns)
  notify()
}

export function subscribeHiddenColumns(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** React binding: re-renders the caller whenever the hidden-column set changes. */
export function useHiddenColumns(): ReadonlySet<string> {
  return useSyncExternalStore(subscribeHiddenColumns, getHiddenColumns, getHiddenColumns)
}
