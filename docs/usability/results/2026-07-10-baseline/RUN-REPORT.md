# Usability run report — runId `adhoc` · 2026-07-10

Catalog v1.0 · bffSha/spaSha `f02fba561994d544080b2abd8b9ba363a7248d53` (git HEAD; no
`/api/meta` endpoint) · seedFingerprint `2189fcc1…19da57c` · engines: 2× Flowable 6.8
(engine-a/engine-b registered; engine-7 disabled, engine-legacy 6.3 unregistered).
Companion machine output: `results.jsonl` (60 goal-arc × mission rows).

---

## 1 · GATE VERDICT: **FAIL** (misses beyond the 6 known gaps)

**Gate population** (MUST-v1 ∧ BUILT yes/partial ∧ UI/feasible-staged, expected-fails
excluded from numerator AND denominator):

- **Passed (yes / yes-with-struggle): 27 arcs** — R-SEM-03, R-SEM-02/a/b, R-SEM-04/a/c/e,
  R-SAFE-04, R-SAFE-01/a, R-AUD-06/a/b, R-UXQ-11, R-SEM-19, R-SEM-01, R-L3-03,
  R-UXQ-13/a/b/c, R-SEM-09/a, R-NFR-06, R-SAFE-02, R-SEM-12, R-SEM-10/a, R-SEM-14,
  R-NFR-03, R-GOV-04, R-AUD-05/b, R-AUD-09(who-half), R-UXQ-01, R-SEM-05.
- **Genuine misses (4, NOT among the 6 known gaps) — these fail the gate:**
  1. **R-UXQ-02 keyboard FIND→FIX** — grid rows cannot be opened by keyboard at all
     (double-click-only); arc completed only via out-of-band compensation at 34
     interactions (>15 budget). Sev2.
  2. **R-UXQ-03 time honesty** — no timezone/offset on any triage timestamp, no UTC
     toggle, no copy-as-ISO; catalog says BUILT yes, tester disproved it on the surfaces
     that matter. Sev2.
  3. **R-AUD-04 correlationId** — no trace/request ID on any error surface or in the
     error envelope (bare Spring `{timestamp,status,error,path}` on 403 and 404). Sev2.
  4. **R-NFR-01 cap disclosure (copy sliver)** — no numeric bulk cap or at-scale guidance
     stated anywhere in the bulk UI (F-G3 cohort deferred, so the copy sliver was the
     gradable half — it failed). Sev3.
- **Blocked-by-environment (re-stage & re-run, NOT UX failures): 16 arcs** — all of M8
  (FIXTURE_DRIFT: unresolved placeholders), R-UXQ-04 + R-SEM-06 + R-SEM-20 (M5: F-G7
  never staged — ground truth proves engine-legacy was never registered in the BFF, so
  "poll until legacy reachable" was unsatisfiable; viewer search 403'd), R-SAFE-03 +
  R-SAFE-05 + R-SEM-21(execute) + R-SAFE-02(prod) (M6: F-G2 prod flip never staged per
  ground truth; no protected-instances API exists; the CSRF wall refused every POST for
  every role), R-SEM-09/b, R-SEM-10/b, R-GOV-05 (runner watermark assert missing).
- **Critical-class check:** zero Sev1 findings. No R-SEM-02 mis-triage (M1 task 2 was a
  clean correct escalate-the-FAILED decision). No invisible apply (every mutation showed
  an outcome). No mis-armed destructive confirm (terminate dialogs enumerated the right
  target; M6 terminate never executed — ground-truth-confirmed both twins alive).
- **Hallucination canaries:** none. Every BUILT-no surface got an honest give-up (M7),
  and every claimed fix was corroborated by the ground-truth hooks (M3 edits: engine
  shows amount=300 + note present-null + audit chain matches; M4 bulk: 8/8 genuinely
  re-dead-lettered exactly as the tester reported). **No quiet lies.**
- **Spine coverage:** FIND ✓ ORIENT ✓ DIAGNOSE ✓ FIX ✓ OUTCOME ✓ RECOVER ⚠ (partially —
  the mixed-outcome half of R-SEM-10/a was degraded by the missing protected-instance
  fixture; the retry-failed-only next move was articulated but not driven).

If the 4 genuine misses were fixed and the staging drift resolved, the remaining misses
would be exactly the 6 known gaps → the target verdict is reachable as
`fail-expected-gaps-only` next run.

---

## 2 · Per-mission task tables

### M1 · 3am payments pager (responder) — 7/7 yes
| # | Verdict | Evidence (one line) |
|---|---|---|
| 1 | yes | Error-class card: ArithmeticException/by-zero ×46, demoFailingPayment v1 28/8 split, DLQ 36+13=49=FAILED — full cited reconciliation |
| 2 | yes | Correct escalate-FAILED decision citing "retries exhausted" vs "9 retries left, next attempt 6:10:27 PM" — zero mis-triage |
| 3 | yes | "Currently at: chargePayment" first-viewport; stacktrace toggle → `ArithmeticException: / by zero` at `NumberOperations.mod`, divisor=0 |
| 4 | yes | Retry fired, status flipped FAILED→RETRYING, re-failure honestly tracked via fresh timestamp + DLQ(0)/Timer(1) move |
| 5 | yes | Note persisted on "Operator notes / the handover surface", attributed `responder · 5:21:12 PM` |
| 6 | yes | copy-for-ticket block: composite ID, def+version, status, exception, UTC ISO time, deep link (note/actions summary missing — Sev4) |
| 7 | yes | "not found on any reachable engine" + "resolved against 2 of 2 engines" quoted |

### M2 · Stuck multi-part order (responder) — 5/5 (4 yes, 1 yes-with-struggle)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Parent identified — but only via Hierarchy tab; grid never labels root vs child (Sev3) |
| 2 | yes | Parent alert → child dead-letter row → Retry job → RETRYING with countdown |
| 3 | yes | "+10 more children not rendered (cap 50/node) — count is exact" quoted; 60 stated |
| 4 | yes | per-row "copy raw" verified = engine value `0`; no tab-level raw dump exists (Sev3) |
| 5 | yes-with-struggle | Queue jargon self-defining inline; "in subprocess" and `${amount % divisor}` never explained (gave up on DOM-verified missing tooltip) |

### M3 · Bad data, careful hands (operator) — 6/6 (4 yes, 2 yes-with-struggle) · all edits ground-truth-verified
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Pre-commit sentence quoted; "✓ changed from 100 to 250"; audit row confirmed over REST |
| 2 | yes | Explicit "empty text \| no value (null)" choice; engine returns note PRESENT with value null |
| 3 | yes | Form-mode leaf flip, "1 of 2 fields changes… the other 1 unchanged"; never touched raw JSON |
| 4 | yes | CAS conflict: "Blocked: the server value changed since you loaded it" — nothing overwritten, two ways forward, admin's edit visible in audit; final amount=300 confirmed on engine |
| 5 | yes-with-struggle | "fix" silently disables the button — rule in label but zero inline/ARIA feedback (Sev3); no ticket-capture field, INC-4711 in Reason did NOT populate the Ticket column (expected R-AUD-07 sliver) |
| 6 | yes-with-struggle | Terminate IRREVERSIBLE cited + cancelled; suspend/resume proven reversible — but both fire instantly with NO confirmation (Sev3) |

### M4 · Bad deploy cleanup (operator) — 7/7 (5 yes, 2 yes-with-struggle) · ground truth: 8/8 re-dead-lettered, matches tester exactly
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | 8/28/46 counts all exact-reconciled; dashboard "28" ≠ incident batch (wrong turn); omnibox is exact-match-only |
| 2 | yes | "as of" stamp + "BFF caches ~20s" + Refresh advanced the stamp |
| 3 | yes | "8 instances across 1 engine: engine-a (8)"; reason gate enforced ("🔒 Reason too short — 10+ characters") |
| 4 | yes | Toast + ops drawer live (no refresh); grid stayed stale until re-navigation (Sev3 split-brain) |
| 5 | yes | 0/8 succeeded, honestly derived and dialog-forewarned ("A step that fails the same way will return to FAILED"); "re-queued" ≠ verdict flagged |
| 6 | yes | Report (who/when/reason/per-item) survived F5; frozen at "re-queued" — final verdict needs re-search |
| 7 | yes-with-struggle | Honest answer, but the product states NO cap/limit/at-scale guidance anywhere → R-NFR-01 copy-sliver FAIL |

### M5 · Half the fleet is dark (viewer) — FIXTURE_DRIFT; 3 tasks salvaged, 1 genuine miss
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Both engines healthy per /api-cards; engine-7 ghost found via Ops log; no down/decommissioned/never-existed distinction (Sev3) |
| 2 | (arc blocked) | "I cannot know right now" — reasonable vs. hedged copy, but engine-down was never actually staged (legacy never registered — ground truth) |
| 3 | **no** | R-AUD-04 miss: bare Spring error shape, no traceId/X-Request-Id anywhere (Sev2) |
| 4 | yes-with-struggle | DLQ 36+13=49=FAILED; 46+7=53=FAILED+RETRYING — internally consistent; job-counts vs instance-counts unlabeled (Sev3) |
| 5 | no (blocked) | "Search failed: Forbidden" ×3 for a fresh viewer cookie-session — no grant named, no next move (theme 1) |

### M6 · Prod with the safety on (operator→admin) — 1/5 gradable; CSRF wall + staging drift · ground truth: zero mutations, tester fully honest
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked-by-env | No prod engine existed (engine-b env never flipped — ground truth); "Fire timer now" carries no lock badge yet refused (Sev3) |
| 2 | blocked-by-env | PROTECTED_ID never protected — no protected-instances route exists in the BFF |
| 3 | yes-with-struggle | Read-only-as-policy proven ONLY via the Ops-log flip row; no read-only surfacing at point of action; refusal identical to RBAC denial (Sev2) |
| 4 | blocked-by-env | Honesty copy quoted ("Nothing is migrated in this step…"); even the documented no-op Check-mapping refused → wrong-reason 403 evidence |
| 5 | blocked-by-env | Terminate dialog enumerated the right target + "dies alone" + reason≥10; never executed; both twins alive (ground truth) |

### M7 · Morning handover (operator→admin) — 8/8 answered; 4 honest expected-gap NOs with located expectations
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Full night inventory from /audit; disproved night shift's "fix is deployed" via re-search — "ok · null" outcome confusing |
| 2 | yes-with-struggle | operator×4 + admin×1 — attribution ground-truth-verified; caveat absent (expected gap #6); "colleague simulation" reason is fixture noise |
| 3 | no (expected #4) | 3 strategies exhausted; expected "Export CSV" next to the Ops-log filters — absent |
| 4 | no (expected #3) | Expected shift-handover widget near Ops drawer; only per-instance notes exist (whose placeholder masquerades as handover) |
| 5 | no (expected #1) | Expected mute/ack on error-class card; only levers (Retry/Suspend) mutate real state |
| 6 | yes | Generic-filter sweep (Status=Active + Started-Before) → "confirmed zero across 2 engines"; no dedicated leak views (expected gap #2 stands) |
| 7 | yes (chip-half no, expected #6→L3-01) | Chip non-interactive; evidence reachable only via Errors & Jobs ("this is the FAILED evidence") |
| 8 | yes-with-struggle | Save→publish→consume loop proven; wildcard publish RBAC'd with named reason; unpublish demands NOTHING (Sev3); "⚠ null (scope unavailable)" render bug |

### M8 · Platform day — **blocked-by-environment (FIXTURE_DRIFT)**: unresolved brief placeholders; 0 arcs run. Re-stage and re-run.

### M9 · Hands off the mouse (responder) — 4 tasks; 2 rubric FAILs
| # | Verdict | Evidence |
|---|---|---|
| 1 | **no** (rubric) | Grid row-open is double-click-ONLY (Enter ×3 cells + Shift+F10 dead); completed only via ID-paste reroute at 34 interactions (>15 budget); tablist no arrow-roving; focus→body on every route change |
| 2 | yes | "Currently at: chargePayment" + alert sentence = textual twin of the diagram; no picture needed |
| 3 | **no** (rubric) | "Jul 10, 2026, 5:44:37 PM" — no zone/offset/title/aria anywhere; no UTC toggle; timezone determined out-of-band |
| 4 | yes | "DEV — a development engine. Low stakes." literal text on every engine; registry gate as inert text |

---

## 3 · Themes ranked by severity (root-cause clusters)

1. **Sev2 · Form-login sessions: every POST 403s with a wrong-reason refusal** —
   sessionStorage-Basic sessions work (M1/M2/M9, M4-after-fix), but cookie/form-login
   sessions (BFF :8085 form, M5/M6, mid-M7) send **no `X-XSRF-TOKEN` header despite a
   valid `XSRF-TOKEN` cookie**; POST `/api/search` and every action → 403. The UI then
   lies about the cause: *"✗ Your role does not permit this action — the BFF refused it.
   Nothing happened."* shown to viewer, operator AND admin — even on the documented
   non-mutating Migrate "Check mapping" (*"Nothing is migrated in this step"*), which
   cannot legitimately need a tier. *"Search failed: Forbidden"* names no grant and no
   next move. ≥5 surfaces (search grid, saved-view links, job actions, variable edits,
   migrate preview, terminate), 4 missions. Wrecked M6 entirely and blocked M5/M7 legs.
2. **Sev2 · No sign-in/sign-out surface; silent default identity** — no login form
   renders in the SPA (`/login` 404s); storage-clear does not sign out (browser-cached
   Basic); anonymous requests silently succeed as a real `responder` account; `/logout`
   404s in-app; identity switching required sessionStorage surgery or origin tricks
   (127.0.0.1). Cited: *"GET /api/me → 200 {username: responder}"* with no Authorization
   header. 5 missions burned setup budget on this; a naive user has NO legitimate path.
   (Dev-profile scoped — but the dev ladder is the product's own testing story.)
3. **Sev2 · Time-display honesty absent (R-UXQ-03 FAIL)** — no timezone/offset/zone
   abbreviation on any triage timestamp (*"Jul 10, 2026, 5:44:37 PM"* bare), no UTC
   toggle, no copy-as-ISO; Recent-searches aria-labels carry full ISO while the
   failure/created/due cells that matter carry nothing. 4 surfaces (detail vitals,
   Errors & Jobs table, audit log, dashboard). Only copy-for-ticket emits UTC ISO.
4. **Sev2 · Keyboard operability broken on the primary surface (R-UXQ-02 FAIL)** —
   AG Grid rows print *"Press Space to toggle row selection"* but row-open is
   double-click-only with zero keyboard binding or hint; ARIA tablist without arrow
   roving; focus resets to `<body>` after every route change and after mutations (3×).
   3 surfaces.
5. **Sev2 · correlationId missing from every error surface (R-AUD-04 FAIL)** — 403/404
   bodies are bare Spring `{timestamp,status,error,path}`; no header, no UI echo, nothing
   to hand support. 3 surfaces (search banner, instance not-found, envelope).
6. **Sev2 · Engine policy/lifecycle states invisible at point of action** — read-only
   mode exists ONLY as an Ops-log row (*"M6 stage F-G2 scripted flip: set engine-7 mode
   to read-only"*); refusals on a read-only engine are byte-identical to RBAC denials;
   dashboard silently omits disabled/unregistered engines with no
   down/decommissioned/never-existed distinction (*"Unknown engine 'engine-7'"* was the
   only honest trace). 3 surfaces.
7. **Sev3 · RBAC affordance inconsistency: silently dead controls** — edit-variable
   buttons disabled with NO lock note for responder while sibling buttons show
   *"🔒 Requires OPERATOR — you are RESPONDER"*; *"Fire timer now"* carries no lock badge
   yet refuses; search 403 names no grant. 3+ surfaces (M1, M6, M5).
8. **Sev3 · Outcome language overstates completion / refresh split-brain** — ops-drawer
   Outcome *"re-queued"* frozen forever reads like success; audit outcome renders
   *"ok · null"*; the grid stays stale while the drawer live-updates; outer variables
   table shows stale value mid-CAS-recovery. 4 surfaces (M4, M7, M3).
9. **Sev3 · Count/row semantics unlabeled** — DLQ job-lane counts vs instance counts
   look comparable but aren't (36+13 vs 46+7); grid never marks root vs child; identical
   same-business-key top-levels on two engines undistinguished; 60 identically-named
   children. 4 surfaces (M5, M2).
10. **Sev3 · Validation-feedback inconsistency (reason ≥10)** — edit-variable dialog:
    silent disabled button, no aria-invalid, no error text; bulk dialog: proper
    *"🔒 Reason too short — 10+ characters"*. Same rule, two behaviors. 2 surfaces.
11. **Sev3 · Friction-floor inconsistency** — Suspend/Activate execute instantly with no
    confirm/reason (vs full verify dialogs on every variable edit); team-view publish and
    unpublish demand no confirmation and no reason (R-SAFE-16 rubric expects
    reason-with-unpublish). 3 surfaces (M3, M7).
12. **Sev3 · Honesty-copy inconsistency between resolve surfaces + render bugs** —
    omnibox *"resolved against 2 of 2 engines"* (definitive) vs instance page *"…or the
    engine may be unreachable"* (hedged) for the same ID; published wildcard view renders
    *"⚠ null / (scope unavailable)"*; bulk cap never disclosed (R-NFR-01). 3 surfaces.

Sev4 residue (report-only): copy-for-ticket lacks note+actions summary; hierarchy child
labels carry no index; stale-pane during CAS recovery; omnibox dropdown intercepts
pointer after Escape; *"Case data (process scope)"* phrasing; *"colleague simulation
INC-9999"* fixture wording undermined the tester's attribution confidence (fixture
hygiene, not product).

---

## 4 · The 6 known-gap evidence paragraphs (MUST-v1, excluded from the gate — plan fuel)

1. **R-BAU-01 error-group acknowledge** — M7 exercised every element of the
   ArithmeticException card (count link, per-engine Retry-group buttons, hover), then
   selected all 59 matching rows to enumerate the full bulk toolbar (Retry/Suspend/
   Activate only). Expected the affordance as a **mute/ack/snooze toggle directly on the
   "Failures by error class" card, next to the exception name and count** — "the same way
   monitoring tools silence a known alert". Correctly warned that the workaround users
   will reach for (Suspend) mutates real engine state and is not equivalent to muting.
2. **R-BAU-02 leak views** — M7 found no dedicated "Active > 30 days" per-definition
   views; composed the sweep from generic filters (Status=Active + Started-Before
   now−30d) and got an honest *"No matching instances — confirmed zero across 2
   engines."* The generic-filter path works and is one-click-saveable — the gap is the
   curated, definition-grouped leak view, not the primitive.
3. **R-AUD-05 shift report (/a)** — M7 expected a **shift-level handover widget on the
   dashboard near the Operations drawer / Saved-views panel**. Found only per-instance
   notes, whose own placeholder (*"do NOT retry — double-books; tax-service fix ETA
   9am"*) reads exactly like a handover note and thereby masquerades as the missing
   feature — an active mislead worth fixing with the feature. /b (consume) passed
   yes-with-struggle from the Ops log, including catching the night shift's false
   "fix is deployed" belief.
4. **R-AUD-08 audit CSV export** — M7 tried three strategies: AG-Grid-style right-click
   export (the audit grid is a plain table — no menu), select-all hoping for a bulk
   toolbar export, and a rendered-HTML text search for export/csv/download/spreadsheet
   (zero matches). Expected the affordance as an **"Export CSV" button in the Ops-log
   toolbar, next to the Actor/Action/Ticket/Since filters and Apply** — by analogy with
   "Save current view…" occupying that slot on the search page.
5. **R-AUD-09 attribution caveat** — the who-question was answered and ground-truth
   verified (operator×4, admin×1 on the touched case), but no service-account caveat
   text exists anywhere on the audit tab; the tester's residual doubt attached to
   fixture wording ("colleague simulation") rather than to the engine-side-blames-the-
   service-account trap the caveat must warn about — i.e., exactly the un-warned user
   the requirement predicts.
6. **R-L3-01 explain-this-status** — M7 clicked the FAILED chip expecting a
   tooltip/detail popover: it is a plain non-interactive span with no title attribute.
   The evidence exists — but only by knowing to open Errors & Jobs, where *"Dead-letter
   (1) … this is the FAILED evidence"* + timestamps + stacktrace live under the engine
   header. The chip→evidence path (which calls, what came back, truncation) is absent,
   as predicted. Expected affordance: on the chip itself.

Plus the two known slivers, both confirmed: **R-AUD-07** — Ticket column renders and
filters, but no capture field exists in any confirm; INC-4711 written into Reason did
not populate it. **R-SAFE-05** — deeper than catalogued: not only is the row-badge/verb-
reason UI absent, there is **no protected-instances API route at all** (only
`/api/admin/legal-holds`), so even the fixture could not be staged.

---

## 5 · Rubric-corpus verdict (R-UXQ-05/06)

**R-UXQ-05 (what/why/next-move triple):** violations found, none Sev1 — rubric gate
(zero Sev1) technically holds, but two Sev2-backed messages need fixing:
- *"Search failed: Forbidden"* — bare code, no why, no next move (M4, M5, M7).
- *"✗ Your role does not permit this action — the BFF refused it. Nothing happened."* —
  states a cause that was demonstrably false (admin refused; no-op preview refused);
  wrong-reason refusal (M6).
- *"ok · null"* audit outcome — raw internals where a verdict belongs (M7).
- *"Team view · by admin · all engines ⚠ null / (scope unavailable)"* — leaked null (M7).
- Silent disabled commit button on short reason — a refusal with no message at all (M3).
- Bare *"Jul 10, 2026, 5:44:37 PM"* timestamps; undefined *"in subprocess"*; untranslated
  `${amount % divisor}` (M2/M9).

**Positive corpus** (the house style at its best, keep as exemplars): *"retries
exhausted — this is the FAILED evidence; retry moves it back to executable"*,
*"Re-queued means the failed step will run again — it has not succeeded yet…"*,
*"Blocked: the server value changed since you loaded it."*, *"~ = the count when this
page loaded. The real list is re-checked at run time."*, *"IRREVERSIBLE runtime state is
destroyed — there is no undo"*, *"🔒 Requires OPERATOR — you are RESPONDER"*,
*"No matching instances — confirmed zero across 2 engines."*

**R-UXQ-06 (notification budget): zero violations** — no unsolicited modals, no stacked
banners; every outcome that appeared in a toast was also durably visible (status field,
ops drawer, audit row).

---

## 6 · Protocol violations (flagged, per tester)

- **M4 (operator):** direct `fetch()` calls to the app's own API (/api/logout,
  /api/auth/logout, /api/session, /api/me) during the setup-lockout diagnosis —
  explicit forbidden move; disclosed, stopped, setup-scoped (task verdicts themselves
  were UI-driven afterwards). Run accepted with the flag; the lockout it was diagnosing
  is itself theme 2.
- **M9 (responder):** one diagnostic click + one double-click in a click-blocked mission
  (disclosed, excluded from task accounting) — and task 1 ran to 34 interactions past
  the 15-interaction give-up line. Both feed the R-UXQ-02 **fail** grading rather than a
  pass: the completion was compensation, not product ergonomics.
- **M3/M4/M6/M7 (setup):** sessionStorage identity writes / BFF-origin form login /
  origin-switch tricks — reconstructions of the missing sign-in mechanism, accepted as
  setup necessity and recorded as theme 2 evidence, not tester misconduct.
- **M5:** clipboard/JS workarounds and network-tab reads — borderline; accepted because
  the R-AUD-04 task explicitly demands an identifier the UI never surfaces.
- **Hallucination canaries: zero.** No tester claimed success on any BUILT-no surface.

---

## 7 · Environment & staging notes

**Degraded (carried into grading):**
- "No protected-instances route exists in the BFF: grep of backend/src/main/java/io/inspector/api/ found only /api/admin/legal-holds (LegalHoldController — audit-range legal holds by engine/tenant/timestamp, not per-instance protection). PROTECTED_ID (uxrun-m4-8) is therefore left unprotected as instructed." → R-SAFE-05 blocked; R-SEM-10/a mixed-cohort half degraded.
- "BFF /api/engines registers only engine-a and engine-b — engine-7 (:8083) and engine-legacy (:8084) are not in the BFF registry, so LEGACY_ONLY_ID is only reachable via raw engine REST, not through the inspector UI/BFF." → M5's F-G7 zero-under-partial arc was unsatisfiable; R-UXQ-04/R-SEM-06/R-SEM-20 blocked.
- "No ACME instance carried a json/structured-typed variable, so JSON_ID uses the documented fallback (json var 'config' created on ACTIVE_ID) rather than an ACME instance." → R-UXQ-13/c graded on the fallback fixture.

**FIXTURE_DRIFT (harness defects, re-stage + re-run once then escalate):**
- **M8**: unresolved brief placeholders — mission never ran.
- **M6**: F-G2 prod flip never applied (ground truth: engine-b env `dev`,
  updated_at==created_at); only the engine-7 read-only flip was real, and it has been
  restored (mode=read-write, lifecycle=disabled verified).
- **M5**: engine-legacy container was never registered/stopped (it was "Up 4 days",
  healthy 6.3.1 on :8084 the whole time) — the stage script's registration step never
  ran.
- **Cross-cutting**: the CSRF/session desync for cookie-form-login sessions (theme 1)
  is part environment (identity-injection experiments) but the zero-X-XSRF-TOKEN-header
  observation under a clean :8085 form login points at a real SPA defect — needs a
  targeted repro before the next run (it will otherwise sink wave 3 again).

**Restoration:** ground-truth hooks confirm restoration complete — engine-7 back to
yml-seed state (read-write/disabled), engine-b untouched, legacy engine healthy, zero
unexpected mutations in the audit chain, M3/M4 fixtures in their expected post-mission
states.

**Ground-truth cross-check summary:** all UI-claimed successes corroborated over engine
REST (M3 amount=300/note=null/config flip + full audit chain; M4 8/8 re-dead-lettered on
`chargePayment` with fresh createTime; M6 zero mutations as honestly reported). **Zero
Sev1 quiet-lie findings.**
