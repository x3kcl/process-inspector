# Usability re-test — 2026-07-11 (after W1 + W2, against main @ 15cf2ed)

Gate: **FAIL** — but the composition shifted decisively vs the 2026-07-10 baseline.
Missions run: M9, M3, M4, M7 (M4 tester died on an API session limit → blocked-by-env;
its engine ground truth was still captured). The staged M5/M6/M8 legs run separately
(user-authorized fleet staging). This file is a hand-persisted summary — the live run's
RUN-REPORT wrote into a since-removed worktree.

## What the fixes bought
- **M1 (from a prior cached pass) and the core spine hold**: FIND→ORIENT→DIAGNOSE→FIX→
  OUTCOME→handover pass with citations, zero mis-triage.
- **R-UXQ-03 time display**: clean (timestamps + env text labels graded clean in M9).
- **R-UXQ-02 keyboard**: row-open + landing drill reachable by Tab+Enter (FIND leg now
  keyboard-navigable — though see the new datetime-trap finding).
- **M3 variable edits**: all three engine-verified true; CAS conflict blocked cleanly
  (nothing overwritten).

## Regression introduced by W2 (must fix now)
- **Sev1 quiet-lie — suspend outcome toast**: the delta statement asserts "its jobs moved
  to the suspended lane" but a suspended instance holding a dead-letter job shows
  Dead-letter(1)/Suspended(0) after hard reload and Retry stays enabled. Suspending an
  instance does NOT move an already-dead-lettered job to a suspended lane; the W2 static
  delta over-claims. Fix: the outcome copy must not assert an unverifiable lane move
  (keep the reversibility framing; drop/condition the lane claim). Filed + fixed.

## Confirmed gaps (W3 addresses; some in flight)
- R-BAU-01 acknowledge, R-AUD-05 shift report, R-AUD-08 CSV — honest expected-fail
  evidence again (W3-1 shipped CSV+caveat+shift-report+ticket after this run's main pin;
  W3-2 acknowledge in flight; re-test will re-measure).
- R-L3-01 explain-status, R-AUD-09 caveat — gap evidence (W3-1 shipped caveat; slice 4
  will add explain-status).
- R-L3-03 view-level raw JSON export graded NO in M2 (MUST-v1 miss, not in the original 6
  — new plan candidate).

## New findings to file as issues (outside the 6 known gaps)
1. Sev2 — RESPONDER scoped drill-through 403: a definitionKey+engines-scoped search 403s
   for responder while a broad FAILED search returns rows; breaks the no-ID triage spine;
   bare "Search failed: Forbidden" (no grant named, no requestId).
2. Sev2 — post-login stale-CSRF 403 on the FIRST request after cross-origin sign-in
   (reload fixes silently) — W1#1 added the XSRF echo but the first request races the
   cookie.
3. Sev2 — keyboard traps: datetime-local filter traps Tab; Operations drawer overlay
   intercepts pointer events over Search/variable cells; health cards unfocusable; bpmn
   SVG failure markers lack an ARIA text twin (R-UXQ-02 residue beyond the grid).
4. Sev2 — search-error banner + bare-Spring engine-down bodies carry no requestId
   (W1#6 scoped to problemBanner/ApiError.describe; the search Forbidden path was missed).
5. Sev2 — status-derivation contradictions: grid parent ACTIVE vs detail FAILED "in
   subprocess" (qualifier unexplained); a terminated instance rendered COMPLETED.
6. Sev3 — bulk-retry outcome optimism persists (per-instance path is honest; the bulk
   drawer path still reads success while all 8 re-failed to DLQ ~17s later — W1#2 fixed
   the re-queued label but the bulk aggregate + audit reason "fix is live" still over-read).
7. Sev3 — wildcard-scope publish gate misfires on a single-engine-scoped view.
8. Sev3 — CAS conflict screen omits WHO/WHEN (W1 didn't touch this; original R-SEM-09
   rubric half still unmet).

## Verdict
The fixes are working where they landed (M1 clean, time/keyboard/engine-policy). The gate
stays red on: one new Sev1 regression (fixing now), the in-flight W3 gaps, staging misses
(M4 died, M8 drift, M5/M6 fleet staged separately), and a cluster of newly-surfaced Sev2s
now filed as issues. Next re-test after the Sev1 fix + W3 slices 2–4 + the staged run.
