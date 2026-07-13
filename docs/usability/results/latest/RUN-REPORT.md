# Usability run report — runId `adhoc` · 2026-07-13

Catalog v1.0 · bffSha/spaSha `5f09a4f52468df29cab32a5f4a19588789334f54` (git HEAD; no live
`/api/meta` response — single-checkout monorepo, bffSha == spaSha) · seedFingerprint
`57d839667f6596026ca7d24df93578c9dcabc618d880d7a142bd8c7959a038f0` · engines: 2× Flowable
6.8 (engine-a/engine-b registered; engine-7 disabled; engine-legacy unregistered this run).
Companion machine output: `results.jsonl` (60 goal-arc × mission rows). 10 missions
reconciled (M1–M10); 4 ground-truth/restoration hooks cross-checked.

> **Note on a stale prior stub in this path**: an earlier write here recorded a total
> pre-flight failure for M8 ("empty task list, FIXTURE_DRIFT"). The tester dataset
> reconciled in THIS report contains a full, well-cited M8 trace (5 tasks, real
> interactions) — this report reconciles that full dataset and supersedes any earlier stub.

---

## 1 · GATE VERDICT: **FAIL** (misses beyond the 6 known gaps)

**Gate population** (MUST-v1 ∧ BUILT yes/partial ∧ UI/feasible-staged). Note up front: the
catalog's "Known-absent surfaces" section (dated 2026-07-13, same day) states the former 6
MUST-v1 gaps + 2 slivers are now **ALL BUILT** — repo-verified, even though the individual
`R-AUD-05`/`R-AUD-08`/`R-AUD-09`/`R-BAU-01`/`R-BAU-02`/`R-L3-01` entries earlier in the same
document are stale and still say `BUILT no`. This run's evidence agrees with the newer note
(see §4) — all 6 were found and used successfully — so, unlike the historical framing, they
are **not** excluded from the gate population this run; they are ordinary MUST-v1 passes.
That is genuinely good news, but it also means a `fail-expected-gaps-only` verdict does not
apply: the misses below are different ones, not the catalog's named 6.

- **Passed (yes / yes-with-struggle): 38 arcs** — R-SEM-03, R-SEM-04/a/c/e, R-SEM-02/a/b,
  R-SAFE-01/a, R-SAFE-04, R-AUD-06/a/b, R-UXQ-11, R-SEM-19, R-SEM-01, R-L3-03,
  R-UXQ-13/a/b/c, R-SEM-09/a, R-NFR-06, R-SAFE-02, R-SEM-12, R-NFR-03, R-SEM-10/a,
  R-SEM-14, R-NFR-01, R-AUD-04, R-SEM-06, R-GOV-04, R-AUD-05/a/b, R-AUD-08, R-AUD-09,
  R-BAU-01, R-BAU-02, R-L3-01, R-UXQ-02/b, R-UXQ-01, R-UXQ-03.
- **Genuine misses (2, NOT among the 6 known gaps) — these fail the gate:**
  1. **R-SEM-20 (M5)** — the tester found a real, unexplained FAILED-count discrepancy
     (engine-a alone: 273 > fleet-wide tile's 265) but never reached or cited the CMMN
     out-of-scope-dead-letters reconciliation note this goal expects; ended on an
     unconfirmed guess instead of the tool's own explanation. Sev3, evidence-quality miss.
  2. **R-UXQ-02/a keyboard FIND→FIX (M9)** — completed, but only at **46 interactions**,
     roughly 3× the RUN PROTOCOL's hard give-up budget (15). Per protocol this should have
     ended `canComplete=no` well before completion; the pass is compensation (persistence
     past the give-up line), not product ergonomics, so the reconciler downgrades it to
     `no` for gate purposes over the tester's self-reported `yes-with-struggle`. Real
     findings survive regardless: first Tab from page load skips ~11 header-region
     focusable elements (lands on "Refresh"); focus drops to `<body>` with zero
     focus-adjacent confirmation after the mutating Retry click. Sev2.
- **Blocked-by-environment (re-stage & re-run, NOT counted as UX failures): 5 arcs** —
  **R-UXQ-04 + R-SAFE-03 + R-SAFE-05** (M5/M6: F-G7/F-G2/protected-instance fixtures never
  actually applied this run — ground-truth-confirmed, see §7), **R-SEM-09/b + R-SEM-10/b**
  (not driven by any task in this run's M3/M4 tester transcripts). This is a **repeat**
  finding — the prior baseline report already flagged F-G2/F-G7 staging as needing a
  targeted fix "or it will sink wave 3 again," and it did, again.
- **Critical-class check: zero Sev1 findings.** No R-SEM-02 mis-triage anywhere (M1 task 2
  was a clean FAILED-over-RETRYING escalation). No guard bypass (RBAC, CAS, SSRF, four-eyes
  self-approval, and reason-length gates all held under adversarial-shaped probing). No
  wrong-target destructive action (M6's twin-instance kill target was correctly identified,
  ground-truth-confirmed both twins in the right state). No invisible apply — every
  mutation showed *some* durable evidence (badge/audit row/report), though §3 theme 6
  flags a *silent rejection* (a guard that correctly blocked something with zero UI
  feedback) as a related, lower-severity pattern worth fixing before it becomes a real
  invisible-apply bug.
- **Hallucination canaries: none.** Every claimed fix is corroborated by the ground-truth
  hooks (M3: engine shows amount=300 + note=null + a 5-row audit chain matching exactly;
  M4: all 8 uxrun-m4-* instances genuinely re-dead-lettered after the "COMPLETED" bulk
  dispatch, exactly as the tester reported — a good example of the tool's own copy
  correctly preventing a false-positive read). **No quiet lies.**
- **Spine coverage:** FIND ✓ ORIENT ✓ DIAGNOSE ✓ FIX ✓ OUTCOME ✓ RECOVER ✓ — all six spine
  steps have ≥1 clean passing goal (M1 tasks 1–4, M3 task 4 CAS-recover, M4 task 5 RECOVER
  language). RECOVER is no longer degraded the way a prior run found it.

If R-SEM-20's citation gap were fixed, the M9 keyboard task were re-scored against a
tighter first-tab-order fix (so it lands under budget), and F-G2/F-G7/protected-instance
staging were repaired and re-run clean, this run's target verdict is `pass` — the product
surface itself is close; the two live misses are narrow and the 5 blocked arcs are a
harness problem, not a code problem.

---

## 2 · Per-mission task tables

### M1 · 3am payments pager (responder) — 7/7 yes (2 with-struggle)
| # | Verdict | Evidence (one line) |
|---|---|---|
| 1 | yes | 'Failures by error class' card: 19 UnknownHostException, 10/9 split, acmeApiOutage v1 — full cited reconciliation, no instance ID needed |
| 2 | yes | Correct escalate-FAILED decision citing "retries exhausted" vs "7 retries left, next attempt ... in 9m" — zero mis-triage |
| 3 | yes | "Currently at → chargePayment" first-viewport; stacktrace → `ArithmeticException: / by zero`, divisor=0 |
| 4 | yes-with-struggle | Retry fired (FAILED→RETRYING), re-failed honestly (fresh timestamp, DLQ 0→Timer 1); no distinct re-fail styling (Sev4) |
| 5 | yes | Operator-notes handover warning saved, timestamped, attributed to responder |
| 6 | yes-with-struggle | "copy for ticket" fires but gives zero on-screen confirmation — content unverifiable in this sandbox |
| 7 | yes | "not found on any reachable engine" + "resolved against 2 of 2 engines" quoted |

### M2 · Stuck multi-part order (responder) — 5/5 yes
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Business-key indent arrow (`↳ child...`) + detail-page "Called by" field distinguish root/child |
| 2 | yes | Roll-up "hasDeadLetterJobs" flag → child's Errors & Jobs → Retry job → RETRYING |
| 3 | yes | "+10 more children not rendered (cap 50/node) — count is exact" — 60 stated honestly |
| 4 | yes | "⬇ raw JSON" download verified engine-shaped (source/processVariables/executionScopes) |
| 5 | yes | Every jargon term self-defined inline; two residual jargon-on-jargon spots noted (Sev4) |

*Protocol note: this tester used a handful of direct URL navigations ("equivalent to
clicking an already-validated link") — a forbidden move per RUN PROTOCOL even when argued
as equivalent; flagged in §6, does not change any task's verdict since each also had an
independent on-screen citation.*

### M3 · Bad data, careful hands (operator) — 6/6 (5 yes, 1 yes-with-struggle) · all edits ground-truth-verified
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Pre-commit sentence quoted; committed 300→250; ground truth confirms engine amount |
| 2 | yes | Explicit "empty text \| no value (null)" choice; ground truth confirms note is explicit null |
| 3 | yes | Form-mode leaf flip only; raw JSON/Source mode never touched |
| 4 | yes | CAS conflict: "Blocked: the server value changed since you loaded it" — nothing overwritten, colleague's edit visible in its own audit row; ground truth's 5-row audit chain matches exactly |
| 5 | yes | "🔒 Reason too short — 10+ characters" rejected "fix" pre-submit, zero work lost; Ticket ID field captured INC-4711 and rendered linkified — the R-AUD-07 capture sliver is now BUILT |
| 6 | yes-with-struggle | Pause/resume reversibility correctly read from tooltip text before any click, then executed; kill dialog unreachable under OPERATOR (ADMIN-only) so open-then-cancel could not be performed this run |

### M4 · Bad deploy cleanup (operator) — 7/7 yes (2 with-struggle) · ground truth: 8/8 re-dead-lettered, matches tester exactly
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | "16 instances" headline vs "engine-a 16 of 24 fetched — narrow your filter" underneath — floor correctly identified, not exact until status=FAILED added |
| 2 | yes | "as of" stamp + "BFF caches ~20s" tooltip; Refresh advanced it |
| 3 | yes | "Select all ~16 matching filter…" → bulk retry with INC-4712; reason-gate enforced |
| 4 | yes | Ops drawer auto-opened and updated live, zero manual refresh needed |
| 5 | yes | 0/8 succeeded, honestly derived ("re-queued — not yet succeeded; verify"); ground truth confirms all 8 still hold fresh dead-letter jobs |
| 6 | yes | Report (who/when/reason/per-item) survived a full reload |
| 7 | yes | "Capped at 5,000 instances per bulk job (server-enforced)... narrow it and run in slices" — cap AND alternative both stated unprompted |

### M5 · Half the fleet is dark (viewer) — FIXTURE_DRIFT on the mission's core premise; 3/5 gradable, 1 genuine miss
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked-by-env | Tester correctly self-diagnosed the gap ("a fourth, unregistered host would be invisible here") — but `engine-legacy` was never registered this run (ground truth: tombstoned by an earlier session), so the intended registered-but-down zero-state was never staged |
| 2 | blocked-by-env | Same drift — "I cannot know right now" was the honest answer to a search that hit the not-found path, not the intended engine-down path |
| 3 | yes | Per-panel "Quote request ID ... to support" UUIDs found unprompted — closes a prior run's R-AUD-04 miss |
| 4 | **no** | Genuine discrepancy found (273 vs 265) but the CMMN-reconciliation explanation this goal expects was never located; ended on an unconfirmed guess |
| 5 | yes | "0 shown — result scans were truncated; this is NOT a confirmed zero" vs a genuinely "confirmed zero across 2 engines" once scoped |

### M6 · Prod with the safety on (operator→admin) — FIXTURE_DRIFT on 3/5; 2/5 cleanly gradable
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked-by-env | Ground truth confirms engine-b's registry row was never touched (created_at==updated_at) — F-G2 prod-flip never applied; tester correctly reported "no engine in this environment is actually labeled production" rather than fabricating a contrast |
| 2 | blocked-by-env | The PROTECTED_ID target is confirmed (by this run's own staging note) to be a plain unprotected instance — no protected-instance guard rail was ever exercised, though the tester's "not a bug" read of what WAS on screen is reasonable |
| 3 | yes | Engine-7 policy-vs-breakage proven: "disabled in the registry by the engine owner — not an operable target" |
| 4 | blocked-by-env / no | Migration target was already on v2 before this run started (ground truth: an earlier same-day session's audit row); mission's "execute and prove" could not be driven; dialog self-contradiction found as a bonus (Sev4) |
| 5 | yes-with-struggle | Sacrificial target was already terminated pre-run (ground truth); tester still opened-then-canceled the surviving twin's real confirmation dialog, correctly quoting IRREVERSIBLE wording + reason-gate; genuine grid-vs-detail status-label bug found as a bonus (Sev3) |

### M7 · Morning handover (operator→admin) — 8/8 answered, 0 expected-gap NOs (all 6 former gaps now BUILT)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Full night inventory from /audit; independently disproved the night shift's "fix is deployed" belief via a live re-search |
| 2 | yes | operator×4 + admin×1 attribution, ground-truth-verified; service-account caveat text now present (closes a prior run's R-AUD-09 miss) |
| 3 | yes | "Export CSV" downloaded a real 156-row file with the audit schema's columns (closes a prior run's R-AUD-08 miss) |
| 4 | yes-with-struggle | "Copy shift report" now exists (closes a prior run's R-AUD-05/a NOT-FOUND); clipboard content unverifiable in this sandbox |
| 5 | yes | "Acknowledge" on the error-class card, reason+ticket+expiry, collapsed to "Acknowledged (N)" (closes a prior run's R-BAU-01 miss) |
| 6 | yes | Home page "Leak views" panel + generic-filter cross-check both report zero (closes a prior run's R-BAU-02 miss) |
| 7 | yes | "explain this status" chip opens a full live evidence dialog incl. raw REST call table (closes a prior run's R-L3-01 miss) |
| 8 | yes-with-struggle | Publish/unpublish two-actor loop completed; OPERATOR block fires even on a non-wildcard scope (misleading reason text, Sev3) |

### M8 · Platform day (registry-admin → admin → access-admin) — 3/5 clean yes, 2/5 blocked-by-environment
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Full onboarding lifecycle (Draft→Probed→Active read-only) self-explanatory at every stage |
| 2 | yes-with-struggle | Metadata-IP rejection actionable; plausible-wrong-URL ("localhost:1/x") probe failure gives zero diagnostic detail |
| 3 | yes | Plain admin cleanly refused with the named missing grant on fresh load; brief stale-render on in-SPA role switch (self-correcting) |
| 4 | blocked-by-env | Access-grant CRUD blocked entirely by an unrelated "mapping is file-pinned" gate before any four-eyes logic could engage — masks whether R-SAFE-08's real widening-comparison behavior works |
| 5 | yes-with-struggle | Registry-removal four-eyes zero-state genuinely reached (self-approval correctly 403'd) — but silently, no UI feedback; historical trace mostly survives with one mis-routed reference found |

### M9 · Hands off the mouse (responder, keyboard-only) — 2/4 clean yes, 1 reconciler-downgraded no, 1 yes-with-struggle
| # | Verdict | Evidence |
|---|---|---|
| 1 | **no** (reconciler downgrade) | Completed the retry entirely via keyboard, but at 46 interactions — 3× the 15-interaction give-up budget — so scored as compensation, not ergonomics; real gaps found: header Tab-order skip, no focus-adjacent mutation confirmation |
| 2 | yes | "Currently at → chargePayment" + alert text = full textual twin of the diagram; only the SVG itself carries no accessible name |
| 3 | yes-with-struggle | "UTC" suffix + parenthetical ISO + "copy ISO" all present (closes a prior run's R-UXQ-03 miss); only Local-vs-UTC, no arbitrary offset preview |
| 4 | yes-with-struggle | "DEV — a development engine. Low stakes." literal text badge on every engine; full-registry admin page gated non-interactive so hidden-prod-engines can't be ruled out |

### M10 · Digging past page one (viewer) — 2/2 arcs, 1 yes-with-struggle, 1 yes
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | "200 of 1,284 fetched" + "~1598 matching" + "Load more" correctly read as first-page; ceiling hit at ~513/1,598 (reproduced twice) with a stale fetch-progress line and no "why stopped" message at the exact moment Load-more vanished |
| 1 (dup-check) | yes | Full scripted scroll-through: 513/513 unique IDs, zero gaps, stable sort order across every Load-More seam checked |

---

## 3 · Themes ranked by severity (root-cause clusters)

1. **[Harness/process] FIXTURE_DRIFT recurring across consecutive runs — 5 gate arcs
   unresolved** — F-G2 (engine-b never flipped to prod), F-G7 (`engine-legacy` never
   registered this run, tombstoned by an earlier session), and the protected-instance mark
   (PROTECTED_ID confirmed plain/unprotected by this run's own staging note) were all
   *supposed* to be applied before M5/M6 ran and were not; M6's migration and kill targets
   were additionally already at their post-mission end state from an earlier same-day
   session. This is a **repeat** of a prior report's explicit warning that unresolved
   F-G2/F-G7 staging "will sink wave 3 again" — it did, again. Not a product defect —
   every tester correctly self-diagnosed the drift rather than fabricating results — but it
   blocks certification of R-SAFE-03, R-UXQ-04, and R-SAFE-05 for a second consecutive
   run. **Recommend: a pre-flight fixture assertion step that hard-fails the run before any
   tester starts, per RUN PROTOCOL's own "Pre-flight fixture check," rather than
   discovering the gap post-hoc via ground truth.**
2. **[Sev2] Catalog BUILT-status overclaim on R-SAFE-05** — the catalog's own BUILT NOTE
   claims full delivery (`ProtectedInstance*`, results-grid row badge, per-verb
   disabled-with-reason, repo-verified 2026-07-13) but this run's staging note found "No
   protected-instances (mark/hold) route exists anywhere under
   `backend/src/main/java/io/inspector/api/`." Either the repo audit is aspirational or the
   route is unreachable from this deployment profile — worth a direct route-level check
   before the next run, independent of the fixture-staging issue in theme 1.
3. **[Sev2] Grid vs detail status-label mismatch (M6)** — the same instance
   (`engine-b:afdf9ae9-...`) reads **COMPLETED** in the search-results grid but
   **TERMINATED** on its own detail page. Ground truth confirms the detail page is correct
   (`deleteReason` present, absent from runtime) — the grid's status derivation is wrong
   for at least this instance class. Not a mis-triage of FAILED/RETRYING (so it doesn't
   trip the R-SEM-02 canary), but it is a real, ground-truth-confirmed correctness bug.
4. **[Sev2] Stale/frozen "fetched" progress counters, 2 missions** — "engine-a 200 of
   1,284 fetched" (M10) and "engine-a 16 of 24 fetched" (M4) never update as Load-more
   grows the loaded set, and in M10 the tool eventually stops paging (~513/1,598,
   reproduced twice) with **no message at the exact moment it stops** explaining that an
   internal ceiling — not the true end — was hit; the only text present at that moment is
   an unrelated staleness note ("newer instances won't appear until Refresh"). Hits both
   R-SEM-12 (honesty labels) and R-NFR-08 (depth-cap legibility) goals.
5. **[Sev2] Keyboard accessibility gaps (M9, 3 sub-findings on one mission)** — first Tab
   from page load skips ~11 header-region focusable elements, landing on "Refresh"; focus
   drops to `<body>` with zero focus-adjacent confirmation after a mutating action; the
   bpmn-js diagram SVG tab-stop carries no accessible role/name (mitigated — the same facts
   exist as redundant plain text elsewhere on the page).
6. **[Sev2] Silent rejection with no UI feedback (M8)** — an access-admin's self-approval
   click on their own registry-removal proposal correctly 403s server-side ("the proposer
   cannot approve their own proposal") but the SPA renders no error/toast/banner at all.
   The guard worked; the feedback didn't. Structurally adjacent to — but distinct from — an
   invisible-apply Sev1 (nothing applied here), still worth fixing before a future guard
   regression makes the silence dangerous instead of just confusing.
7. **[Sev3] "Copy X" actions give zero on-screen confirmation, 3 missions** —
   copy-for-ticket (M1), copy raw JSON (M2), copy shift report (M7) all fire with no
   toast/label-change. Compounded by (but independent of) a Playwright clipboard-read
   sandbox limitation noted in nearly every mission's protocol notes — the *lack of a
   toast* is a real, sandbox-independent R-UXQ-06 gap.
8. **[Sev3] Fixture/session reset discipline — 6 missions show pre-existing residue** —
   Recent-search history, operator notes, and audit rows from earlier sessions/runs are
   visible before a "first-time" tester acts in M1, M2, M3, M6, M7, M8; in M3 and M6 the
   residue is the mission's own EXPECTED end state, already present before the tester
   touched anything. This risks contaminating a fresh evaluation (a less careful tester
   could credit itself for pre-existing state) and is the likely root mechanism behind
   theme 1's M6 pre-executed fixtures.
9. **[Sev3] R-OPS-13 diagnostic-detail inconsistency (M8)** — the disallow-listed metadata
   IP gets a (garbled but) actionable rejection reason; a plausible-but-unreachable URL
   (`localhost:1/x`) gets only "Probe failed" with zero detail in the UI or the underlying
   API response body — the goal's "specific enough to fix" bar is only half met.
10. **[Sev3] Message-style: stated reason doesn't match the actual gate (M7)** —
    publishing a saved view scoped to a single exact business key (not remotely a
    wildcard) as OPERATOR is refused with "publishing a wildcard scope needs ADMIN" — the
    real rule is "OPERATOR can never publish," mislabeled as a scope check.
11. **[Sev4] Migrate dialog self-contradiction (M6)** — titled "move this case to a newer
    process version" but for an already-latest instance offers only an OLDER version as
    the sole selectable target.
12. **[Sev4] Removed-engine reference partially mis-rendered (M8)** — the audit-trail link
    for a registry action opens `/inspect/<engineId>/<engineId>`, which Instance Detail
    tries to resolve as a process instance and shows a self-contradictory "Unknown engine…
    The engine answered" banner, even though the correct history renders fine right below
    it on the same Audit & Notes tab.

---

## 4 · The 6 known-gap (+2 sliver) evidence paragraphs — now closed, not excluded

Per the catalog's own 2026-07-13 "Known-absent surfaces" note, all 6 former MUST-v1 gaps
plus 2 slivers are now repo-verified BUILT. This run's evidence agrees on every one — a
genuinely positive result, and a departure from the historical "expected NOT-FOUND"
framing these arcs used to carry:

1. **R-AUD-05/a produce shift report** — M7 found "Copy shift report" sitting directly
   next to "Export CSV" in the Ops-log toolbar, exactly where a prior report predicted it
   should live. Fired without error; clipboard content itself could not be independently
   verified in this sandbox (an environment limitation, not an app gap).
2. **R-AUD-08 audit CSV export** — M7's "Export CSV" button produced a real 156-row
   `operations-log.csv` with the audit schema's columns (ts/actor/action/engineId/
   tenantId/instanceId/outcome/httpStatus/reason/ticketId/correlationId/breakGlass),
   verified on disk including the break-glass row.
3. **R-AUD-09 attribution caveat** — M7's Audit & Notes tab now carries "Engine-side
   history attributes these actions to the shared service account — this log is the
   authoritative WHO," and the who-question (operator×4, admin×1) is ground-truth-verified
   correct against the same real usernames.
4. **R-BAU-01 error-group acknowledge** — M7 filled reason+ticket+24h expiry on the
   UnknownHostException card's "Acknowledge" button; the group collapsed into an
   "Acknowledged (N)" section that explicitly states its own auto-resurface conditions.
5. **R-BAU-02 leak views** — M7 found a dedicated home-page "Leak views" panel reporting
   "No leaks — every reachable engine is clean…", independently corroborated via a
   generic Status=Active + Started-before filter landing on the same zero.
6. **R-L3-01 explain-this-status** — clicked from any FAILED chip across nearly every
   mission this run, opening a titled "Explain this status" dialog with a live-recomputed
   flag table AND an "Engine calls made (N)" table listing the raw REST calls (URL,
   status, latency, timestamp) behind the derivation — the deepest evidence surface found
   in the whole run.
7. **R-AUD-07 ticket-capture sliver** — M3/M4/M6 all found and used a dedicated
   "Ticket ID (optional…)" field in the same confirm dialog as the reason box, rendering as
   a linkified chip in Audit & Notes afterward.
8. **R-SAFE-05 row-badge/verb-reason sliver** — **not confirmed this run** (unlike the
   other 7). M6's staging note for this exact run states the intended PROTECTED_ID target
   is "a plain unprotected demoFailingPayment instance" — the fixture was never applied,
   so no badge/lock-reason/bulk-skip evidence could be gathered either way. See theme 2 for
   the further wrinkle that the catalog's BUILT-yes claim for this item cannot currently be
   substantiated by any tester evidence.

---

## 5 · Rubric-corpus verdict (R-UXQ-05/06)

**R-UXQ-05 (what/why/next-move triple): zero Sev1 violations — rubric gate holds.**
Sev2/3-backed message issues worth fixing:
- "publishing a wildcard scope needs ADMIN" stated as the reason for a non-wildcard-scope
  refusal (M7) — reason given does not match the actual gate.
- "▲ Probe failed" with no detail anywhere, UI or API body (M8).
- Stale "X of Y fetched" lines that silently stop describing reality after Load-more (M4,
  M10).
- Self-approval 403 with a good server-side message that never reaches the UI at all (M8).

**Positive corpus** (house style at its best, keep as exemplars): *"retries exhausted —
this is the FAILED evidence; retry moves it back to executable"*, *"Blocked: the server
value changed since you loaded it."*, *"~ = the count when this page loaded. The real list
is re-checked at run time."*, *"IRREVERSIBLE runtime state is destroyed — there is no
undo"*, *"🔒 Requires OPERATOR — you are RESPONDER"*, *"No matching instances — confirmed
zero across 2 engines."*, *"Capped at 5,000 instances per bulk job (server-enforced) —
narrow it and run in slices."*, *"A step that fails the same way will return to FAILED
after its retries; use an item's Verify now…"*.

**R-UXQ-06 (notification budget):** no unsolicited modals, no stacked banners found — but
**3 confirmed violations of "never the sole record of an outcome" via its mirror image**:
copy-for-ticket (M1), copy raw JSON (M2), and copy shift report (M7) all produce *zero*
record of their outcome anywhere on screen (no toast, no label change), which is arguably
worse than an outcome living only in a vanished toast — here it never lived anywhere.

---

## 6 · Protocol violations (flagged, per tester)

- **M2 (responder):** direct URL navigation ("hand-editing URLs") used for "a few"
  navigation steps, self-justified as equivalent to clicking an already-validated link — a
  move explicitly forbidden by RUN PROTOCOL regardless of the equivalence claim. Does not
  change any M2 task's verdict since each also carried an independent on-screen citation,
  but flagged as a protocol adherence gap.
- **M9 (responder, keyboard-only):** task 1 ran to 46 interactions against a 15-interaction
  hard give-up budget — reconciler downgrades this arc's verdict to `no` for gate purposes
  (see §1).
- **M4 / M10 (operator / viewer):** `browser_evaluate(button.click())` used for several
  repeat "Load more" clicks (M4 progress-watching, M10 duplicate/skip verification) instead
  of the click tool, plus scripted grid-viewport scrolling in M10 (no documented UI
  scroll affordance existed). Both self-disclosed by the testers as compensatory
  verification layered on top of real prior clicks, not a substitute for them — accepted
  per RUN PROTOCOL's "agent compensatory behavior" guidance, but flagged as a friction
  finding (M10's Q4 duplicate/skip conclusion still stands on its own scripted evidence).
- **M2 / M5:** `browser_network_request` used to read response bodies of requests the UI
  itself had already fired (never to issue independent calls) — a defensible verification
  technique, not a forbidden direct-API call, but noted for completeness.
- **Hallucination canaries: zero.** No tester claimed success on a genuinely BUILT-no
  surface.

---

## 7 · Environment & staging notes

**Degraded (carried into grading, verbatim from this run's staging):**
- "No protected-instances (mark/hold) route exists anywhere under
  `backend/src/main/java/io/inspector/api/` — PROTECTED_ID (uxrun-m4-8,
  28abf3eb-7e7f-11f1-b839-3210ba03f0d0) is a plain unprotected demoFailingPayment instance;
  any mission expecting a 'regulatory hold' badge or protected-instance guard rail on it
  will not see one." → R-SAFE-05 blocked (M6 task 2); directly contradicts the catalog's
  own BUILT-yes note for this item (theme 2).

**FIXTURE_DRIFT (harness defects, re-stage + re-run once then escalate — repeat of a prior
run's finding):**
- **M5**: `engine-legacy` was never registered this run — ground truth shows the
  `engine-legacy-ux` registry row was disabled and registry-removed/tombstoned by an
  **earlier session** (same-day audit trail, reason "M5 staging cleanup: remove scratch
  UX-staging engine after recovery"). The container itself was also stopped and had to be
  separately `docker start`-ed by the restoration hook. F-G7's registered-but-unreachable
  zero-state was never staged; the tester's search instead hit the ordinary
  not-registered-at-all path.
- **M6**: F-G2 (flip `engine-b` env tag to `prod`) was never applied — ground truth
  confirms the registry row's `created_at == updated_at` exactly, i.e. untouched since the
  original yaml-seed. The migration case (`uxrun-m6-mig`) was already on v2 and the kill
  target (`uxrun-m6-3`) was already TERMINATED, both via an **earlier same-day session**
  (audit seq 127/128, ~08:02–08:07 UTC, well before this tester's run) — the whole mission
  was executed against a fixture that had already reached its own end state.
- **M8 task 4**: access-grant CRUD is blocked entirely by an unrelated "mapping is
  file-pinned (mapping-source: file)" condition that disables ALL grant writes regardless
  of size — this masked whether R-SAFE-08's real small-grant-vs-wildcard-grant comparison
  behaves differently; only the registry-removal four-eyes path (task 5) reached genuine
  evidence for the underlying approval mechanism.
- **Cross-cutting**: 6 of 9 missions encountered pre-existing session/fixture residue
  (recent searches, notes, audit rows) predating the tester's own actions — see theme 8.
  This is the most likely root mechanism behind M6's pre-executed fixtures above and should
  be treated as the same underlying reset-discipline gap.

**Restoration:** the container-start restoration hook for `engine-legacy` succeeded (raw
health check 200 on first poll), but explicitly could NOT restore full BFF-visible
reachability, because the registry-level removal was itself a deliberate, reasoned action
by a prior session (not damage) — restoring that would require a fresh, audited
registry-admin re-add, which was correctly *not* done silently. M6's engine-7 read-only
flip (the one staging step that WAS genuinely applied for this run) was confirmed restored
to its yaml-seed state (mode=read-write, lifecycle=disabled) by the same hook.

**Ground-truth cross-check summary:** all UI-claimed successes corroborated over engine
REST and the BFF's own audit table — M3 (amount=300 / note=null / config flip + full
5-row audit chain), M4 (8/8 genuinely re-dead-lettered on `chargePayment` with fresh
createTime, matching the tester's "0 succeeded" report exactly), M5/M6 restoration hooks
(confirming the fixture-drift findings above rather than any tester error). **Zero Sev1
quiet-lie findings.**
