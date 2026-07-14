// R-UXQ-09 (SPEC §10a, issue #104 slice 3/6): the persisted grid-density preference —
// mirrors ./theme.ts's ThemePreference store exactly (lazy module-level value hydrated from
// localStorage, a listener Set, get/set/subscribe + a useSyncExternalStore hook, a
// module-load side effect applying the value to <html> before first paint). Applies to BOTH
// AG Grid instances (ResultsGrid, AuditLogPage) via styles.css's `[data-density='compact']
// .ag-theme-quartz` scoping — a single global preference, not per-grid state.
import { useSyncExternalStore } from 'react'

export type Density = 'comfortable' | 'compact'

const DENSITY_STORAGE_KEY = 'inspector.density'

// Lazy so the localStorage read happens on first use, not import (testable, SSR-safe) —
// same rationale as theme.ts's `theme`.
let density: Density | null = null
const densityListeners = new Set<() => void>()

function storedDensity(): Density {
  try {
    const raw = globalThis.localStorage.getItem(DENSITY_STORAGE_KEY)
    return raw === 'compact' ? 'compact' : 'comfortable'
  } catch {
    // No storage (private mode / node) — the honest default is the roomier layout.
    return 'comfortable'
  }
}

/**
 * Reflect the preference onto <html>. 'comfortable' REMOVES data-density entirely — it is
 * the CSS default (no `[data-density]` selector needed), mirroring theme.ts's 'system' case —
 * so only the 'compact' override needs a selector at all in styles.css.
 */
function applyDensity(next: Density): void {
  // Guards the module-load call at import time in non-DOM test environments (vitest's
  // default `node` environment), mirroring theme.ts's `typeof document` rationale.
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (next === 'comfortable') {
    root.removeAttribute('data-density')
  } else {
    root.dataset['density'] = next
  }
}

export function getDensity(): Density {
  density ??= storedDensity()
  return density
}

export function setDensity(next: Density): void {
  density = next
  applyDensity(next)
  try {
    globalThis.localStorage.setItem(DENSITY_STORAGE_KEY, next)
  } catch {
    // Persistence unavailable — the in-memory choice (and the DOM it already applied) still
    // hold for this session.
  }
  for (const listener of densityListeners) listener()
}

export function subscribeDensity(listener: () => void): () => void {
  densityListeners.add(listener)
  return () => {
    densityListeners.delete(listener)
  }
}

/** React binding: re-renders the caller whenever the density preference changes. */
export function useDensity(): Density {
  return useSyncExternalStore(subscribeDensity, getDensity, getDensity)
}

// Module-load side effect (imported early in main.tsx, before the first render) so
// data-density is set before first paint — no flash of the wrong layout.
applyDensity(getDensity())
