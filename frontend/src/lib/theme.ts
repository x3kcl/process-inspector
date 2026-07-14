// R-UXQ-08 (SPEC §10a, issue #104 slice 2b): the persisted dark-theme preference — mirrors
// ./format.ts's DisplayZone store exactly (lazy module-level value hydrated from
// localStorage, a listener Set, get/set/subscribe + a useSyncExternalStore hook), with one
// extra responsibility: applying the resolved preference to <html> so styles.css's
// `[data-theme]` selectors can win the cascade over `prefers-color-scheme`.
import { useSyncExternalStore } from 'react'

export type ThemePreference = 'system' | 'light' | 'dark'

const THEME_STORAGE_KEY = 'inspector.theme'

// Lazy so the localStorage read happens on first use, not import (testable, SSR-safe) —
// same rationale as format.ts's `zone`.
let theme: ThemePreference | null = null
const themeListeners = new Set<() => void>()

function storedTheme(): ThemePreference {
  try {
    const raw = globalThis.localStorage.getItem(THEME_STORAGE_KEY)
    return raw === 'light' || raw === 'dark' ? raw : 'system'
  } catch {
    // No storage (private mode / node) — the honest default follows the OS/browser.
    return 'system'
  }
}

/**
 * Reflect the resolved preference onto <html>. 'system' REMOVES data-theme entirely so
 * styles.css's `@media (prefers-color-scheme: dark)` block does the work with zero JS
 * involvement — deliberately no matchMedia listener here, the media query itself updates
 * live with no attribute present. 'light'/'dark' set data-theme, whose CSS overrides win
 * the cascade in both directions regardless of OS preference.
 */
function applyTheme(next: ThemePreference): void {
  // Guards the module-load call at import time in non-DOM test environments (vitest's
  // default `node` environment, mirroring format.ts's SSR-safe rationale) — `typeof` is the
  // safe idiom here since lib.dom.d.ts types `document` as always-present, unlike the actual
  // runtime.
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (next === 'system') {
    root.removeAttribute('data-theme')
  } else {
    root.dataset['theme'] = next
  }
}

export function getThemePreference(): ThemePreference {
  theme ??= storedTheme()
  return theme
}

export function setThemePreference(next: ThemePreference): void {
  theme = next
  applyTheme(next)
  try {
    globalThis.localStorage.setItem(THEME_STORAGE_KEY, next)
  } catch {
    // Persistence unavailable — the in-memory choice (and the DOM it already applied) still
    // hold for this session.
  }
  for (const listener of themeListeners) listener()
}

export function subscribeThemePreference(listener: () => void): () => void {
  themeListeners.add(listener)
  return () => {
    themeListeners.delete(listener)
  }
}

/** React binding: re-renders the caller whenever the theme preference changes. */
export function useThemePreference(): ThemePreference {
  return useSyncExternalStore(subscribeThemePreference, getThemePreference, getThemePreference)
}

// Module-load side effect (imported early in main.tsx, before the first render) so
// data-theme is set before first paint — no flash of the wrong explicit override.
applyTheme(getThemePreference())
