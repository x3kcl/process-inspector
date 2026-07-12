// Shared axe-core scan helper for hermetic Playwright specs (R4, TEST-STRATEGY §1: "axe
// accessibility checks hard-fail"). Deliberately explicit-call, not an autouse fixture that
// scans on every navigation — a blanket scan would fire mid-transition (route change,
// modal-open animation, lazy-tab fetch) against a transient/incomplete DOM and produce
// flaky false positives. Specs call scanA11y() at each settled state they already assert
// against. `scripts/check-e2e-a11y-coverage.mjs` (run in CI) fails the build if a spec file
// never calls it at all, so a spec can't silently ship with zero a11y coverage.
import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

/**
 * Runs an axe-core scan against the page's current (settled) state and fails the test with
 * a readable rule-id + selector list if any violation is found. `label` names the state being
 * scanned (e.g. "grant modal open") for the failure message and has no effect on the scan.
 */
export async function scanA11y(page: Page, label: string): Promise<void> {
  const results = await new AxeBuilder({ page })
    // AG Grid Community's header viewport is a horizontally-scrollable div with tabindex="-1"
    // header cells — by design (the WAI-ARIA "grid" pattern), keyboard access is via arrow-key
    // roving-tabindex cell navigation, not native per-element Tab-focusability. axe's
    // scrollable-region-focusable rule doesn't recognize managed-focus widgets and false-positives
    // on this vendor-internal element; excluded here rather than patched (patching AG Grid's own
    // DOM would fight its keyboard-nav model and is coupled to an internal class that can rename
    // on a version bump).
    .exclude('.ag-header-viewport')
    .analyze()
  const description = results.violations
    .map(
      (v) =>
        `${v.id} (${v.impact ?? 'unknown'}): ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`,
    )
    .join('\n')
  expect(results.violations, `axe violations at "${label}":\n${description}`).toEqual([])
}
