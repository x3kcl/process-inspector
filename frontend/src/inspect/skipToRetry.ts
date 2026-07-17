/**
 * Issue #237 (keyboard-only FIND→FIX efficiency): the dead-letter retry verb lives in the
 * Errors & Jobs tab's dead-letter lane, behind the full vitals-header action gauntlet, the
 * diagram toggle, the tablist and several per-row controls — ~20 Tab presses from the top
 * of the instance page. The fix reuses the #168 skip-link convention: a page-local skip
 * control (first focusable on the inspect page, visible on focus) that opens the Errors &
 * Jobs tab and hands focus directly to the first dead-letter job action.
 *
 * This module is the focus half, extracted for direct unit testing (same shape as
 * shell/routeFocus.ts): find the first ENABLED action button in the dead-letter lane and
 * focus it, falling back to the job-lanes container when no action is actionable (all
 * verbs gated, or the job vanished between vitals and the lane fetch).
 *
 * R-UXQ-06 (nothing steals focus uninvited) still applies — the lane data loads async, so
 * by the time this runs the user may have Tab'd elsewhere. Focus only moves if it still
 * sits where the command left it: on the skip control itself (marked with
 * DATA_SKIP_SOURCE) or dropped to <body>.
 */

/** Marks the skip control so an async completion can tell "user is still waiting on me". */
export const DATA_SKIP_SOURCE = 'data-skip-to-retry-source'

/** The first enabled per-job action in the dead-letter lane — in practice, Retry job. */
const DEAD_LETTER_ACTION_SELECTOR = '.lane-deadLetter .job-actions button:not(:disabled)'

/** Fallback focus target when no dead-letter action is enabled/present. */
const LANES_CONTAINER_SELECTOR = '.job-lanes-tab'

/**
 * Move focus to the first enabled dead-letter job action (or the lanes container as the
 * nearest survivor). Returns the element focused, or null when focus was left alone —
 * either because the user has moved on, or because no target exists yet.
 */
export function focusDeadLetterAction(doc: Document = document): HTMLElement | null {
  const active = doc.activeElement
  const stillWaiting =
    active === null || active === doc.body || active.hasAttribute(DATA_SKIP_SOURCE)
  if (!stillWaiting) return null // the user Tab'd elsewhere meanwhile — never steal focus

  const target =
    doc.querySelector<HTMLElement>(DEAD_LETTER_ACTION_SELECTOR) ??
    doc.querySelector<HTMLElement>(LANES_CONTAINER_SELECTOR)
  if (target === null) return null
  // The container fallback is not natively focusable — grant programmatic focusability
  // without adding it to the Tab order. Never clobber an authored tabindex, and never
  // demote something already focusable (tabIndex >= 0 covers button/a[href]/input/…,
  // should the fallback selector ever match one).
  if (!target.hasAttribute('tabindex') && target.tabIndex < 0) {
    target.setAttribute('tabindex', '-1')
  }
  target.focus()
  return doc.activeElement === target ? target : null
}
