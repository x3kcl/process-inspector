# 🛠️ USABILITY IMPLEMENTATION PLAN — from the 2026-07-10 baseline run

Input: `results/2026-07-10-baseline/RUN-REPORT.md` (gate **FAIL** — 4 genuine MUST-v1
misses + 12 root-cause themes) reconciled by a two-seat fix panel (lead dev: artifact
triage + file-level fixes; UX: value ranking + consistency rulings). Follow-ups are to be
filed as GitHub issues on merge (team convention since 2026-07-10); this doc is the
source they are cut from.

## Verdict summary
- Missions M1/M2/M3/M4/M7 largely PASSED with ground-truth-verified fixes and zero
  mis-triage (the FIND→ORIENT→DIAGNOSE→FIX core holds).
- Gate fails on 4 genuine MUST-v1 misses: **R-UXQ-02** (keyboard row-open), **R-UXQ-03**
  (time display honesty), **R-AUD-04** (correlationId on errors), plus the Sev2
  wrong-reason-403 / outcome-overstatement trust themes.
- All 6 known MUST-v1 gaps confirmed with located-expectation evidence (testers searched
  where the feature should live): R-BAU-01/02, R-AUD-05/08/09, R-L3-01.
- 3 missions degraded by harness staging (M5/M6/M8) — re-stage, no product signal lost
  where arcs were salvaged.

## W0 — harness fixes (before any re-run; no product code)
See RUNBOOK "Learnings from the 2026-07-10 baseline run": F-G7 must register+stop a
registered engine with the human's explicit authorization; F-G2 prod-flip needs a
config-source prod-tagged engine (API refusal is correct product behavior); M8
placeholder validation; tester sign-in via SPA form only.

## W1 — pre-pilot product fixes (the 3am-danger tier; one PR each, S/M effort)
| # | Fix | Files (extend, don't fork) | Effort |
|---|---|---|---|
| 1 | **Kill the wrong-reason 403**: code-less 403 must never render RBAC copy ("Your role does not permit") — honest default + CSRF hint; SPA sends `X-XSRF-TOKEN` on cookie sessions | `frontend/src/actions/problem.ts:39` (delete `'forbidden'` fallback), `frontend/src/api/client.ts` (XSRF middleware) | S |
| 2 | **Outcome honesty**: "re-queued" renders amber "not yet succeeded — verify" + Verify-now link; `ok · null` renders a verdict word; post-mutation grid gets the stale-results chip | ops drawer, `AuditLogPage.tsx`, existing amber envelope + R-SAFE-09 affordance | S |
| 3 | **Time display (R-UXQ-03)**: shared formatter gains `timeZoneName` + ISO title/aria + one-click persisted UTC toggle; copy-as-ISO via `CopyButton` | `frontend/src/lib/format.ts` (single blast radius by design) + call-site sweep | S/M |
| 4 | **Engine policy at point of action**: `mode`/`lifecycle` on engines DTO, second token in `EnvBadge`, distinct `engine-read-only` banner case; read-only refusal never byte-identical to RBAC | `EnginesController`, `EnvBadge.tsx`, `problem.ts`; `npm run gen:api` | M |
| 5 | **Keyboard (R-UXQ-02)**: Enter opens focused grid row (+ visible hint), tablist arrow roving, route-change focus restore (never `<body>`) | `ResultsGrid.tsx` (`onCellKeyDown` → same handler as `onRowDoubleClicked`), router layout | M |
| 6 | **correlationId (R-AUD-04)**: request-id filter + MDC, `ProblemDetail` property + `ErrorAttributes` customizer (also fixes bare-Spring 403/404 shape), surfaced in banners as "quote this ID to support" | new `OncePerRequestFilter`, `ActionExceptionHandler`, `problem.ts`; additive `requestId` → gen:api | M |

## W2 — consistency batch (Sev3; one combined PR)
- Reason-≥10 feedback: converge on the bulk dialog's inline gate copy (+`aria-invalid`);
  the edit dialog's silent disable loses (§10a: a refusal with no message).
- Friction floor: suspend/activate stay single-click (§5.0 queue-state doctrine — do NOT
  add a confirm) but gain the mandatory §6 outcome toast + reversibility badge;
  team-view **unpublish keeps a required reason** (R-SAFE-16).
- Honesty copy: converge on coverage-parameterized copy — definitive "not found —
  resolved against N of N" when all engines answered; hedge only under real partial
  coverage. Fix `⚠ null (scope unavailable)` render; state the numeric bulk caps
  (single `BULK_CAP` constant) in bulk UI copy (R-NFR-01).
- RBAC affordances: apply `ActionHint` (tone `gate`) to every disabled control
  (edit-variable pencils, "Fire timer now"); server names the missing grant.
- Count/row semantics: label job-lane vs instance counts; root-vs-child marker in the
  grid (R-UXQ-12 half).

## W3 — the six known MUST-v1 gaps (sequenced; all SPEC §12 v1 scope, outrank every v1.x fast-follow)
1. ✅ **Audit-surface slice** (one PR — LANDED): R-AUD-08 CSV export (`GET /api/audit/export`,
   the streaming `text/csv` sibling of `operationsLog` over the SAME filters) + R-AUD-09
   attribution caveat (static `ActionHint` tone-info on the audit tab AND the ops-log
   header) + R-AUD-05 shift report ("My shift" preset + "Copy shift report", UNKNOWNs
   first under NEEDS VERIFICATION) + R-AUD-07 ticket-capture field in the reason-bearing
   confirm modals.
2. ✅ **R-BAU-01 acknowledge** (LANDED) — persisted `error_group_ack` store (V15, keyed
   signature × engine × definition key with server-resolved baselines),
   `POST /api/triage/error-groups/acknowledge` + `…/unacknowledge` under the full
   corrective-actions rails (OPERATOR floor + per-engine re-check, reason ≥10, fail-closed
   config-event audit with compensation), render-time ack join (engine cache untouched),
   card affordance + "Acknowledged (N)" section + "GREW SINCE ACK: +n" auto-resurface
   (+20% default / new failing version / expiry).
3. ✅ **R-BAU-02 leak views** (LANDED — usability W3-3): curated definition-grouped presets
   riding the shipped saved/system-view machinery (predicate-honest names per R-SEM-05 — the
   *Suspended · started > 7 days ago* view is `startedBefore`, never time-since-suspension).
   New `GET /api/triage/leak-views` count-only aggregation (`LeakViewService`) → per-definition
   `LeakViewsSection` on the landing, each count a lower-bound-badged deep link into Stage 1.
4. **R-L3-01 explain-status** — `StatusChip` becomes interactive: derivation-ladder
   popover (ARCH §2.3 evidence, re-derived + labeled) + deep link to Errors & Jobs. M.
Also from the run: **R-SAFE-05 sliver** (protected badge + per-verb reason in
`InstanceActions.tsx` — backend enforcement exists) and the dev-ladder sign-in/sign-out
surface (`HeaderStrip` identity + sign-out; `SignIn` on explicit sign-out, not only 401).

## Re-test to close the gate (same scripts that found the defects)
After W1+W2: re-run **M9 full, M5 re-staged, M6 re-staged, M8 full, M4 full, M3 tasks
5–6, M7 tasks 1+8** (M1/M2 stay green, no re-run). Exit target for that run:
`fail-expected-gaps-only`. After W3: full nightly gate → `pass`.

## Spec-sync obligations (when W-items land)
W1#4 read-only visibility and W2 friction-floor/outcome-toast copy touch SPEC §6/§5.0
wording examples; W3 items discharge SPEC §12 v1 bullets — each landing PR updates
SPECIFICATION/ARCHITECTURE/IMPLEMENTATION-PLAN per the `spec-sync` skill.
