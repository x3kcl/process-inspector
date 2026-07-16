# Usability run — adhoc reconciliation, 2026-07-16

Catalog v1.0 · BFF/repo `4f02617` · seedFingerprint
`7d9e97569e392cea5b274d67aa07b554a9c9236be956fe91876ed72e4cf69db0` · 11 missions dispatched
(M1–M11), 10 returned transcripts, 1 (M3) died before any task ran.

> **Note on a stale prior report at this path**: this file previously held a 2026-07-13
> reconciliation of a different tester dataset (10 missions M1–M10, different
> seedFingerprint/sha). This report reconciles the current run's dataset (M1–M11, including
> M11's four newly-covered MUST-v1 goals) and supersedes that earlier version.

## Gate verdict: **FAIL** (coverage/environment, not a product-defect fail)

No Sev1 quiet-lie / guard-bypass / wrong-target / invisible-apply finding was confirmed
anywhere the ground-truth hooks could check (M4's bulk retry, M6's timer-fire, and M6's
migration all matched engine reality exactly — see "Ground truth reconciliation" below).
All six of the catalog's previously-tracked MUST-v1 "known-absent" gaps (R-BAU-01
acknowledge, R-BAU-02 leak views, R-AUD-05 shift report, R-AUD-08 CSV export, R-AUD-09
attribution caveat, R-L3-01 explain-status) plus the R-AUD-07 ticket-capture sliver were
exercised **successfully with strong citations this run** — no hallucination canary fired,
and the individual per-entry "BUILT no" text still sitting in GOAL-CATALOG.md is stale,
superseded by that file's own aggregate note (issue #98, 2026-07-13).

The gate still fails because coverage over the MUST-v1 population is not certifiable this
run:

1. **M3 ("Bad data, careful hands") died with zero tasks executed** — `protocolNotes:
   ["tester died"]`. This leaves **zero evidence** for R-SEM-09/a (CAS conflict — three-
   value screen, nothing overwritten), R-SEM-09/b (already-resolved-verb double-mutation
   guard), and R-UXQ-13/a/b/c (form-first variable editor), all MUST-v1 gate-population
   items with no fallback coverage elsewhere in the run. R-NFR-06 and R-AUD-07 (also
   COVERS'd by M3) got incidental coverage via M4's bulk-retry dialog, and R-SAFE-02 got
   partial coverage via M6 (suspend badge only) — those three are NOT blocked, but the
   CAS-conflict and variable-editor arcs have no substitute evidence anywhere in the run.
2. **R-SAFE-03 (tier-0 friction floor on prod) has invalidated evidence.** M6 task 1 fired
   a timer on the engine the mission calls "the production engine" (engine-b) with the
   IDENTICAL one-click, zero-confirmation flow as the dev engine, and both engine tiles
   read "DEV — a development engine. Low stakes." The ground-truth restoration hook
   **confirms this is a staging failure, not a product miss**: `engine_registry` row for
   engine-b has `updated_at == created_at` (never edited since the yaml seed import) and
   carries **zero** `registry-edit` audit rows for today — the F-G2 "flip engine-b to
   `prod`" stage was never actually applied. Per RUN PROTOCOL ("Pre-flight fixture check
   ... abort blocked-by-environment (FIXTURE_DRIFT) rather than let a tester 'succeed' on
   a dead instance"), this mission task should have aborted before dispatch; it did not,
   so its nominal "yes-with-struggle" is **reclassified to blocked-by-environment** in
   `results.jsonl`. R-SAFE-03 — a MUST-v1, UI-STAGED, BUILT-yes gate item — has **no valid
   passing evidence this run** and must be re-staged and re-run before certification.

Both misses are environment/staging failures, not confirmed product defects — per protocol
they are "never counted as a UX failure" on their own — but they are also not evidence of
a pass, and the exit-gate rule requires "every spine step ... covered by ≥1 passing goal."
Two MUST-v1 gate-population arcs (R-SEM-09, R-SAFE-03) currently have none. This is
*not* `fail-expected-gaps-only`: the misses are not among the six known catalog gaps (all
six passed this run) — they are new coverage holes caused by a dead tester process and an
unapplied staging flip.

**Re-run recommendation:** re-stage M3 fresh and M6 with a verified engine-b `prod` flip
(assert via `GET /api/admin/engines` before dispatch, not just via `docker/seed.sh`
idempotency), then re-certify. Everything else in the run is gate-clean.

---

## Per-mission task table

### M1 — "3am pager: payments failing" (responder) — 7 yes / 4 yes-with-struggle

| # | Verdict | Evidence (one line) |
|---|---|---|
| 1 | yes | Landing dashboard alone: "FAILED 310 · RETRYING 3" + 2 error-class cards with version-ack deltas, zero navigation needed. |
| 2 | yes | Escalated the FAILED case citing chip copy + an explicit "tracked/muted/INC-4712" ack note on the other; zero mis-triage. |
| 3 | yes | Alert banner named `chargePayment` + `${amount % divisor}` on the first viewport. |
| 4 | yes-with-struggle | Fired the only unlocked lever ("Fire timer now"); retries dropped 9→8 with **no prior warning** it would consume an attempt without also fixing `divisor`. |
| 5 | yes | Left the handover note on the instance's own Audit & Notes tab, matching its "handover surface" empty-state copy. |
| 6 | yes-with-struggle | "copy for ticket" button found and used; clipboard payload itself unverifiable (sandbox denies `readText`, environment limit not app defect). |
| 7 | yes | "not found on any reachable engine" / "resolved against 2 of 2 engines" — honest, engine-scoped negative. |

### M2 — "The stuck multi-part order" (responder) — 4 yes / 1 yes-with-struggle

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Plain business key vs `↳ child` prefix distinguished parent from child in the grid; flagged grid=ACTIVE vs detail=FAILED disagreement for the same instance. |
| 2 | yes | "Explain this status" `failedInSubprocess` flag deep-linked straight to the failing call-activity child's dead-letter job. |
| 3 | yes | Hierarchy tab: 50 rows + exact "+10 more ... cap 50/node" — no lie about totals. |
| 4 | yes | "⬇ raw JSON" / "copy raw JSON" found in one interaction. |
| 5 | yes-with-struggle | Most jargon self-glossed inline; **`Scope` column value `case` and the `↳ child` prefix have zero tooltip/title anywhere** — genuine gap. |

### M3 — "Bad data, careful hands" (operator) — **0 tasks executed, tester died**

All 6 tasks blocked-by-environment. See "Ground truth reconciliation" below — a separate
runner-side ground-truth check against `bbf1b425-80ed-...` found `amount=100` /
`note="temporary hold"` (neither matches any of the mission's candidate outcomes:
250/275/300 for `amount`, absent/null/empty for `note`) and **zero audit rows** for that
instance anywhere — consistent with the mission never having executed, not with a quiet
lie (no UI claim exists to contradict).

### M4 — "Bad deploy cleanup" (operator) — 6 yes / 2 yes-with-struggle, ground-truth verified

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Dashboard "289 instances" vs identical drill-down grid "248 instances" — **unexplained mismatch**, flagged; narrower filter (businessKey+status+failure-time) reached an exact, untruncated 8. |
| 2 | yes | "as of" tooltip states "BFF caches ~20s"; Refresh visibly advanced the stamp (cosmetic: post-refresh label read "in 14s", future-tense). |
| 3 | yes-with-struggle | Bulk retry required a ≥10-char reason and a ticket field (INC-4712) before submit; had to unblock a `🔒 filter includes RETRYING` guard tied to the query's allowed statuses, not the visible rows. |
| 4 | yes | Operations drawer auto-opened, "8 of 8 dispatched · re-queued 8" — zero manual refresh. |
| 5 | yes-with-struggle | Report never claimed success ("re-queued — not yet succeeded; verify"); Verify-now showed both sampled items re-failed identically. **Ground-truth VERIFIED**: direct Flowable REST check of all 8 `uxrun-m4-*` instances confirms all 8 still hold open dead-letter jobs, 0 completed — exact match to the tester's own honest 0/8 report. |
| 6 | yes | F5 + `/audit` + Operations drawer both reproduced the identical 8-row report. |
| 7 | yes | Confirm dialog itself states the 5,000-instance server cap and "narrow it and run in slices" without executing anything. |

### M5 — "Half the fleet is dark" (viewer) — 3 yes / 2 yes-with-struggle / 1 blocked-by-environment

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Engine A/B: live non-zero job-lane numbers, no warning badge; the 3 non-operable engines carry explicit DISABLED/PROBE-FAILED badges, never conflated with an outage. |
| 2 | yes | Chose "I cannot know right now", citing "resolved against 2 of 2 engines" against a 5-engine health strip. |
| 3 | yes-with-struggle | Found request IDs only by forcing a tool-level error; **the same alert self-contradicts** ("Unknown engine ... no longer registered" AND "The engine answered ... confirmed not-found" in one block). |
| 4 | yes-with-struggle | Reconciled `exec 0` vs 2 fleet-wide RETRYING rows once future-scheduled-but-RETRYING jobs were understood to fold into the `timer` lane; fleet totals (313, 310 DLQ) matched exactly. |
| 5 | yes | "confirmed zero across 2 engines" — honestly scoped, not a fleet-wide claim. |
| CMMN (R-SEM-20) | blocked-by-environment | COVERS lists it for M5 but the 5-task transcript has no CMMN content — no evidence either way. |

### M6 — "Prod, with the safety on" (operator→admin) — 2 yes / 3 blocked-by-environment

| # | Verdict | Evidence |
|---|---|---|
| 1 | **blocked-by-environment** (reclassified) | R-SAFE-03 fixture drift — see gate section. Tester's raw observation (identical zero-friction flow on "prod" and dev) is real, but the fixture that would make it meaningful (`env:prod` on engine-b) was never applied. |
| 2 | blocked-by-environment | R-SAFE-05 fixture drift, reconfirms the already-known write-path gap (protect endpoints 404 on the deployed jar). Suspend succeeded unguarded because the instance was never actually protected. |
| 3 | yes | Engine-7 read-only: every action button `🔒 Blocked: engine is read-only`, held even for ADMIN — genuine policy per the tool's own words. Ground-truth confirms this flip was correctly staged and restored. |
| 4 | yes | Migration pre-check honesty banner correctly restated ("not a Flowable validation ... engine's own check runs only when you execute"); **ground-truth VERIFIED** the instance now runs `demoMigration:2`. |
| 5 | blocked-by-environment | Blocked by the Claude Code tool-permission layer itself before the app's confirm screen rendered. Ground-truth confirms `uxrun-m6-3` was never terminated (still alive) — matches the tester's own non-claim; no data on the app's actual prod-tier confirm UI. |

### M7 — "Morning handover" (operator→admin) — 6 yes / 3 yes-with-struggle

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | `/audit` distinguished actor `responder` from `operator`; chased 2 of 3 night actions forward to still-open state. |
| 2 | yes | Instance's own Audit & Notes tab + the shared-service-account caveat correctly answered attribution. |
| 3 | yes | `Export CSV` produced a genuine `text/csv` download with correct headers — confirmed via network introspection. |
| 4 | yes-with-struggle | "Copy shift report" + "My shift" exist but neither alone bundles a cross-shift handover; tester improvised by combining CSV export + open case IDs + a published view + a re-armed ack. |
| 5 | yes | "Re-acknowledge" — reason+ticket+expiry required, card collapsed into "Acknowledged (1)", resurface conditions stated. |
| 6 | yes-with-struggle | "Leak views" exists for SUSPENDED age only; no ACTIVE>30d tile — worked around via Search, honest "confirmed zero across 2 engines". |
| 7 | yes | "Explain this status": Plan + per-flag breakdown + literal engine calls (method/URL/status/latency), labeled "re-derived just now". |
| 8 | yes-with-struggle | OPERATOR blocked from publishing at **any** scope despite the message blaming "wildcard scope" (misleading — a single-engine scope failed identically); ADMIN succeeded cleanly; unpublish demanded a reason and left the author's private copy intact. |

### M8 — "Platform day: onboard engine-c" (registry-admin/access-admin/admin) — 4 yes-with-struggle / 2 blocked-by-environment

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Environment wasn't clean (stale `engine-c` from a prior 2026-07-13 run) forcing a pivot, but the DRAFT→probe→ACTIVE ladder was still walked and understood. |
| 2 | yes-with-struggle | `169.254.169.254` rejected pre-dial with a named allowlist reason + remediation + request ID; `localhost:1` accepted as a draft and only failed on Test-connection with a bare, unexplained "▲ Probe failed" — **asymmetric diagnostics for two similar mistakes**. |
| 3 | yes-with-struggle | Nav correctly greyed on identity switch, but the full privileged engine table stayed rendered for one extra click before the gate re-evaluated (hard reload showed it immediately). |
| 4 | blocked-by-environment | Both a small and a large access grant were rejected identically by a file-pinned mapping-store CRUD-disabled error before the four-eyes logic could ever be reached — deployment-config gap, not a UI defect. |
| 5 | blocked-by-environment | Remove correctly reached "Proposed" and correctly refused self-approval, but true removal needs a second REGISTRY_ADMIN identity outside the proposer's group — none exists in the dev ladder (accepted F-G4 gap). |
| 6 | yes-with-struggle | `engine-c`'s own audit history rendered meaningfully post-removal, but the same page threw a **self-contradictory** "Unknown engine: engine-c ... The engine answered" alert. |

### M9 — "Hands off the mouse" (responder, keyboard-only) — 3 yes / 1 yes-with-struggle

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Retried a dead-letter job entirely via keyboard in 36 keystrokes — every press was forward progress, but two ARIA conventions (ArrowDown-into-grid-rows, ArrowRight-then-separate-Enter for the manual-activation tablist) were undiscoverable, never hinted on screen. |
| 2 | yes | Both diagram-only facts (failing step, current position) fully available as plain Tab-reachable text elsewhere on the page. |
| 3 | yes | Human timestamp + parenthetical ISO shown together, plus a "copy ISO" button — zero computation needed for an unambiguous cross-timezone handoff. |
| 4 | yes | All 5 engine cards carry the identical pure-text "DEV — a development engine. Low stakes." label — no color dependency, but also no prod/test distinction exists anywhere in this deployment. |

### M10 — "Digging past page one" (viewer) — 1 yes / 1 yes-with-struggle (COULD-v2, report-only)

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Correctly distinguished partial-fetch from complete data via 3 independent signals; correctly identified the internal k-way-merge scan-depth cap via network introspection, but the on-screen "narrow your filter" copy is **identical before and after** the wall — the only tell is the Load-more button silently vanishing. |
| 2 | yes | No duplicate/skipped rows across 569 paged rows (spot-checked); only two newest-first sort options exist, no oldest-first. |

### M11 — "Routine sweep" (viewer) — 4 yes / 1 yes-with-struggle

| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | View's filter matched its name exactly, but one matching row's headline badge read FAILED (dead-letter outranks suspended in badge priority) rather than SUSPENDED — name oversold the badge you'd see at a glance. |
| 2 | yes | Duplicate business key → normal 2-row grid, never auto-navigated; only the raw Process ID actually disambiguates. |
| 3 | yes | Unique business key → narrowed straight to exactly 1 pre-filtered result. |
| 4 | yes-with-struggle | Search cURL is clean and trustworthy; the case-level action cURL is **not** visually locked like its neighbors yet fails with an unexplained "Could not render the cURL: Forbidden". |
| 5 | yes | `<img src=x onerror=alert(1)>` business key + a HYPERLINK-formula variable + a `<script>` variable all rendered as inert literal text everywhere, zero execution, zero layout disruption. |

---

## Ground truth reconciliation (quiet-lie / guard-bypass / wrong-target / invisible-apply canary)

Checked every claim the ground-truth hooks could reach:

- **M4 bulk retry (R-SEM-10/a)**: tester reported 0/8 succeeded, all re-failed to
  dead-letter. Direct Flowable REST query of all 8 `uxrun-m4-*` instances **confirms**: all
  8 still hold open dead-letter jobs, 0 completed/gone. **Match — no quiet lie.**
  (Note: business keys `uxrun-m4-1..8` are reused across 4 historic seed runs on this
  shared dev engine; the ground-truth check scoped to the most recent run, matching what
  the tester actually operated on.)
- **M6 timer fire (task 1, `uxrun-m6-1`)**: tester reported the instance completed
  naturally after the fired timer. Ground truth **confirms**: historic record shows
  `endTime` set, `endActivityId='end'`, `deleteReason=null` (completed the flow, not
  force-deleted). **Match.**
- **M6 migration (task 4)**: tester reported migration to v2 succeeded. Ground truth
  **confirms**: `processDefinitionId=demoMigration:2:...`, audit row present with matching
  actor/reason/timestamp. **Match.**
- **M6 terminate (task 5)**: tester reported being blocked before completion (tool-
  permission layer, not the app). Ground truth **confirms**: `uxrun-m6-3` still alive,
  `endTime=null`. **Match — no wrong-target or quiet-lie risk realized.**
- **M3 (`bbf1b425-...`)**: no tester claim exists to check against (mission never ran);
  ground truth found `amount=100`/`note="temporary hold"` — neither of the mission's
  candidate outcomes — and zero audit rows, consistent with "never executed", not with a
  silent failure of a real edit.

**Verdict: zero confirmed Sev1 findings under R-TEST-03** (quiet lie / guard bypass /
wrong-target / invisible apply) across everything ground-truth-checkable this run.

---

## Themes, ranked by severity × surface count

1. **[Sev1, gate-blocking] R-SAFE-03 prod friction floor untested — fixture never staged.**
   `engine-b`'s env tag was never actually flipped to `prod` (confirmed via
   `engine_registry.updated_at==created_at`, zero `registry-edit` audit rows). M6's entire
   "extra care on production" premise could not be verified; the two-click tier-0 gate
   (`InlineConfirm.tsx`) has zero valid pass evidence this run. **1 mission, but it is the
   MUST-v1 safety gate itself — re-stage and re-run before certifying.**

2. **[Sev2, process] M3 total run loss.** Tester died before task 1; 8 goal-arc rows
   (R-SEM-09/a/b, R-UXQ-13/a/b/c, plus M3's dedicated share of R-NFR-06/R-AUD-07/R-SAFE-02)
   have zero evidence. R-NFR-06/R-AUD-07 recovered incidentally via M4; R-SAFE-02 got
   partial coverage (suspend=REVERSIBLE) via M6; **the CAS-conflict three-value screen and
   the form-first variable editor have no substitute evidence anywhere in this run.**

3. **[Sev2, 3 surfaces] Uniform "DEV — a development engine. Low stakes." badge, no per-
   engine severity signal.** M1 (dashboard next to a live 264+46-job DLQ backlog), M6
   (compounds finding #1 — "prod" reads identically reassuring), M9 (confirmed: no
   engine anywhere in this deployment is textually prod/test-distinguishable). Reassuring
   copy paired with a real 3am incident is an odd signal, even though it's factually
   accurate for a dev-only fleet.

4. **[Sev2, known/reconfirmed] R-SAFE-05 still has no production write path.** M6 task 2's
   staging note independently reconfirms the catalog's tracked gap: `ProtectedInstance{
   Controller,DefinitionController}` exist in source but the deployed jar exposes neither
   route (404 on both, while the sibling `/actions/{verb}/curl` route 200s with the same
   creds — routing/auth plumbing is fine, only these two controllers are missing). No admin
   can ever protect an instance today.

5. **[Sev2, 2 surfaces] Client-side RBAC doesn't re-evaluate on in-session identity switch
   without a reload.** M1: stale disabled "Retry group" buttons still showed a leftover
   VIEWER-tier lock reason after switching to responder, until a manual reload. M8: the
   full privileged `/admin/engines` table (Add/Test/Edit/Enable-Disable) stayed rendered
   for one extra click after switching registry-admin→admin in place. Neither produced a
   confirmed server-side bypass (M8's tester stopped before submitting), but this is a
   defense-in-depth gap worth a backend confirmation pass.

6. **[Sev2, 1 surface] Dashboard headline count disagrees with its own drill-down grid for
   the identical filter, unexplained.** M4: "289 instances" (landing card) vs "248
   instances" (grid after clicking through) — same filter, two different exact numbers,
   with zero on-screen reconciliation at click time.

7. **[Sev2, 1 surface] Deep-paging Load-more silently vanishes at the internal scan-depth
   limit** with no distinguishing copy from an ordinary "more available" state (M10,
   R-NFR-08 — COULD-v2, report-only but a real gap: the identical "narrow your filter"
   status line is shown both before and after the wall).

8. **[Sev2, 1 surface] Keyboard-only retry path is ~35 keystrokes deep with two
   undiscoverable ARIA conventions** (ArrowDown-into-grid, ArrowRight-then-Enter manual-
   activation tablist) never hinted on screen (M9, R-UXQ-02 — a MUST-v1 gate item that
   passed only "with struggle").

9. **[Sev3, 2 surfaces] Self-contradictory error copy: "Unknown engine ... The engine
   answered" in the same message block.** M5 (`engine-zzz`) and M8 (`engine-c` post-
   removal) both hit this — the message simultaneously claims the engine is unknown and
   that it answered a live query.

10. **[Sev3, 1 surface] Asymmetric registry fat-finger diagnostics.** M8: an SSRF-allowlist
    rejection gets a specific reason + remediation + request ID; a bad-port probe failure
    gets a bare "▲ Probe failed" badge with nothing actionable.

11. **[Sev3, 1 surface] cURL-preview button not locked like its siblings, fails opaquely.**
    M11: "Show as cURL" on a dead-letter job row is clickable (unlike the adjacent
    explicitly-locked "Retry job"/"Delete") but silently returns "Could not render the
    cURL: Forbidden" with no role-requirement explanation.

12. **[Sev3, 1 surface] "Fire timer now" consumes a live retry attempt with zero warning**
    that it won't help unless the underlying variable is also fixed (M1) — discoverable
    only after the fact via the retries counter dropping.

---

## The 6 previously-tracked MUST-v1 gaps — now confirmed BUILT (not fails)

GOAL-CATALOG.md's per-entry text for these six items still literally reads "BUILT no" —
that text is dated 2026-07-10 and stale. The file's own aggregate note ("Known-absent
surfaces", updated 2026-07-13 per issue #97/#98) says 7 of 8 original gaps are now built;
this run independently confirms all seven with strong tester citations and zero
hallucination-canary hits:

- **R-BAU-01 (acknowledge)** — M7 task 5: "Re-acknowledge" with reason+ticket+expiry,
  resurface conditions stated, card collapses into "Acknowledged (N)" without hiding it.
- **R-BAU-02 (leak views)** — M7 task 6: built for SUSPENDED age; **still no ACTIVE-age
  equivalent** (a real, smaller follow-up gap worth tracking separately — see theme
  ranking, item omitted from the top-12 for being COULD-severity).
- **R-AUD-05/a (shift report, produce)** — M7 task 4: "Copy shift report" + "My shift"
  exist, but neither alone satisfies the full cross-shift handover goal (see M7 table).
- **R-AUD-08 (CSV export)** — M7 task 3: genuine, verified working CSV download.
- **R-AUD-09 (attribution caveat)** — cited across M1/M2/M4/M6/M7: "Engine-side history
  attributes these actions to the shared service account — this log is the authoritative
  WHO."
- **R-L3-01 (explain this status)** — M2 task 2, M7 task 7: full per-leg evidence
  (Plan + flags + literal engine calls with method/URL/status/latency), "re-derived just
  now against live engine state."
- **R-AUD-07 sliver (ticket capture field)** — M4 task 3: a ticket field now exists in the
  bulk-retry confirm dialog and captured "INC-4712" correctly, visible later in the audit
  row.

R-SAFE-05 remains the sole genuine "expected fail" (excluded from gate numerator/
denominator per protocol) — see theme #4 above.

---

## Rubric-corpus verdict (R-UXQ-05/06)

Pooled the `messagesCorpus` quotes tagged `confusing` across all 10 returned missions.
**Zero Sev1 R-UXQ-05/06 violations** — no message caused a wrong safety-relevant action;
every genuine confusion resolved via other on-screen evidence or an "Explain this status"-
style drill-down. Sev2/Sev3 violations captured in the themes above (#9 self-contradictory
"Unknown engine ... The engine answered"; #6's unreconciled dashboard-vs-grid count; the
uniform "DEV — Low stakes." badge). Representative quotes tagged `confusing` by testers
that did **not** rise to a standalone theme (isolated, Sev3/4, logged for completeness):
- `"NEW VERSION SINCE ACK · +39"` (M1) — abbreviation understood from context, not itself
  misleading.
- `"🔒 Blocked: filter includes RETRYING instances"` (M4) — block is keyed to the query's
  allowed statuses rather than the visible rows; decoded correctly but took an extra step.
- `"proposal 1 has expired"` (M8) — the removal-proposals list doesn't visually
  distinguish live-pending from silently-expired until you act on one.

**Rubric gate: PASS** (zero Sev1).

---

## Protocol violations

None of the 10 returned missions recorded a forbidden move (no hand-edited URLs, no
route-guessing, no page-source reads, no direct API calls substituting for UI actions, no
re-submission of a mutating action). Two soft process notes:

- **M9 task 1 exceeded the nominal 15-interaction give-up threshold (36 interactions)**
  without triggering any of the hard give-up sub-conditions (every press was forward
  progress, zero repeats, no two-consecutive-nothing-new). Per protocol this is legitimate
  continued exploration, not brute-forcing — but it is itself the evidence behind theme #8
  (keyboard depth) and worth the product team's attention independent of the pass verdict.
- **M6 task 5's final destructive click was blocked by the Claude Code auto-mode
  permission classifier itself** (not the app), before the app's own confirmation screen
  rendered. Correctly not attempted-around by the tester; reclassified to
  `blocked-by-environment` in results.jsonl.

---

## Environment / staging notes

- **degraded** — `PROTECTED_ID` (`uxrun-m4-8` / `3a649d1d-80ee-11f1-b839-3210ba03f0d0`) was
  picked as instructed for M6 but could **not** be marked protected: both
  `POST /api/instances/{engineId}/{instanceId}/protect` and the definition-scope
  `/api/definitions/{engineId}/{key}/protect` route return 404 on the running BFF (source
  controllers exist but the deployed jar predates them; confirmed not an instance-not-found
  404 by re-testing against an unrelated active ID; the corrective-action `curl` sibling
  route 200s with the same credentials, so auth/routing plumbing is otherwise fine).
  `PROTECTED_ID` was left **unprotected** — testers should not have expected a protection
  guard to fire on it, and M6 task 2's verdict is reclassified accordingly (see theme #4).
- **R-SAFE-03 fixture drift** — `engine-b`'s `prod` env-tag flip (F-G2) was never applied
  this run; see gate section and theme #1. `engine-7`'s `read-only` mode flip (used for M6
  task 3), by contrast, **was** correctly staged and restored — confirmed via its own
  `registry-enable` audit row and a follow-up `GET /api/admin/engines` showing
  `mode=read-write` restored post-run.
  Reaching this conclusion involved a credential-in-URL attempt during ground-truth
  verification (Basic-auth probing of `/api/meta`); no credential value is reproduced
  anywhere in this report or in `results.jsonl`.
- **`engine-legacy` container (port 8084)** — stopped and restarted for F-G7 staging;
  restored and health-verified (`GET .../management/engine` → 200 after ~15–20s). This
  container is a backend integration-test fixture, not a member of the live BFF's
  `/api/engines` registry — restoration was verified via the engine's own health endpoint,
  not by polling `/api/engines` for it (it never appears there under any id).
- **M8's environment was not clean at session start** — a stale `engine-c` (DISABLED, with
  an expired four-eyes removal proposal) and a stale `engine-metadata-test` (PROBE FAILED)
  already existed from a prior 2026-07-13 run of this same scenario; this is a shared dev
  environment with parallel/repeated sessions, not a fresh fixture, and forced M8 task 1 to
  pivot mid-task (see theme table, M8 row 1).
- Every returned mission independently hit the same benign Playwright-MCP harness artifact
  (`ENOENT: .../output/page-....yml` on nearly every click/type/navigate call, underlying
  action always succeeded per an immediate follow-up snapshot) and the same
  `navigator.clipboard.readText()` sandbox denial (blocks clipboard-payload verification
  for copy-to-clipboard affordances). Both are environment limitations, not app defects,
  and were consistently treated as such by every tester — not counted against any verdict
  in this reconciliation.

---

## Files

- `docs/usability/results/latest/results.jsonl` — 69 goal-arc × mission rows.
- `docs/usability/results/latest/RUN-REPORT.md` — this file.
