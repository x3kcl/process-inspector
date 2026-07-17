# 🎯 USABILITY GOAL CATALOG — one goal per register requirement

Status: **v1.0** (post-panel: support-lead + UX-research + product seats, plus external
Gemini/Copilot reviews, 2026-07-10) · Companion to
`.claude/skills/usability-testing/SKILL.md` and `docs/REQUIREMENTS-REGISTER.md`.
Every requirement in the register gets a **goal-based task arc** a naive tester agent
(cheaper model tier, first-time operator persona) can aim for — or an explicit statement
of why it is not a usability-run target.

> ⚠️ **This file is EVALUATOR-ONLY.** It is the rubric registry: GOAL/SUCCESS lines name
> expected copy, routes and widgets, and BUILT states — all of which would prime a
> pattern-matching LLM tester into false passes (or false give-ups on BUILT-no goals).
> Testers receive ONLY the mission briefs in `docs/usability/MISSIONS.md`; the harness
> must never feed this file (or BUILT flags) into a tester's context. A tester reporting
> success on a BUILT-no goal is a hallucination canary that invalidates that tester's run.

## Format contract (machine-parsed by the harness)

```
### R-XXX-nn · short name
PRIO / CLASS / BUILT / GOAL / ENTRY / SUCCESS / FIXTURE
```

- **CLASS** — how the goal is exercised:
  - `UI` — a naive tester attempts it against the rendered UI as-is after `docker/seed.sh`.
  - `UI-STAGED` — testable in the UI but the runner must stage a condition first
    (stop an engine, flip an env tag, mutate out-of-band over REST, two-actor script).
  - `INDIRECT` — the requirement is backend/contract-shaped; the tester can only observe a
    UI side effect; primary verification lives in CI (cited).
  - `NOT-UI` — governance/ops/test-process requirement; no usability goal is meaningful.
    Listed with a one-line rationale so "each requirement" stays auditably covered.
- **BUILT** — repo state at catalog time (`yes` / `partial` / `no` / `n/a`). `no` on a
  MUST-v1 surface means the tester SHOULD still attempt the goal — a naive "I could not
  find any way to…" verdict is the evidence the implementation plan needs. `no` on
  SHOULD/COULD surfaces: skipped in the run, routed straight to the plan.
- **GOAL** — always an outcome arc ("a first-time on-call engineer must be able to ___ and
  KNOW it worked, without a manual"), never a widget checklist.
- **ENTRY** — route + signed-in dev-ladder user (`viewer`/`responder`/`operator`/`admin`/
  `registry-admin`/`access-admin`, pw `dev`). Evaluator-side: the tester brief starts at
  `/` or the mission's stated route — never at the destination tab/widget under test.
- **SUCCESS** — the observable signal (feeds the verdict). **Citation-or-nothing scoring**:
  a semantically correct answer with no on-screen text cited scores `unsupported` — the
  rubric grades the citation, not the answer (Sonnet already knows Flowable jargon).
- **FIXTURE** — seed data / staging required (references `docker/seed.sh` unless noted).

Multi-arc goals carry stable **arc sub-IDs** (`R-SEM-04/a`…) so nightly verdicts are
comparable per arc. Personas: default is the **3am first-time on-call engineer**; day-shift
/BAU and platform-admin personas named in GOAL.

---

## GOV — Product governance

### R-GOV-01 · KPI instrumentation

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a
GOAL: n/a — KPIs are computed from BFF audit data; no operator-facing arc. The usability
run itself feeds the "time-to-first-fix" intuition but does not verify the metric.

### R-GOV-02 · v1 release gate

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a
GOAL: n/a — release-gate checklist (security sign-off, perf budget, onboarding doc).
The R-TEST-08-style run this catalog powers is one artifact ON that checklist.

### R-GOV-03 · Stakeholder table / onboarding checklists

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a
GOAL: n/a — organizational precondition.

### R-GOV-04 · Read-only engine mode

PRIO MUST-v1 · CLASS UI-STAGED · BUILT yes
GOAL: An operator working an instance on an engine registered read-only must understand,
without filing a bug, that no mutating verb will work on this engine, why, and that this
is policy (rollout ramp) rather than breakage.
ENTRY: `/inspect/...` on a read-only engine · user `operator`.
SUCCESS: tester states "every action is greyed with 'engine registered read-only'; this is
an onboarding policy, not an outage" and does NOT report it as a defect.
FIXTURE: runner flips one engine to `mode: read-only` via `/admin/engines` (restore after).

### R-GOV-05 · AG Grid Community + bpmn.io watermark

PRIO MUST-v1 · CLASS UI (sliver) · BUILT yes
GOAL: The diagram canvas must carry the visible bpmn.io watermark (license term) and the
grid must be workable (selection count, copy IDs) without Enterprise affordances.
ENTRY: `/inspect/...` diagram + `/search` grid · user `viewer`.
SUCCESS: watermark visibly present; tester can select rows, read a selection count, copy an
ID. (License/CI half verified by `check-bpmn-watermark.mjs`, not the tester.)
FIXTURE: standard seed.

### R-GOV-06 · IdP contract (OIDC)

PRIO MUST-v1 · CLASS NOT-UI (in dev run) · BUILT partial (dev=Basic)
GOAL: n/a for the dev-profile run — dev ladder uses Basic-behind-a-form. OIDC login
legibility (groups-overage reject-legibly) needs the prod-like Keycloak leg (R-OPS-16).

### R-GOV-07 · Release re-cut discipline

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a
GOAL: n/a — scope governance.

### R-GOV-08 · Playbook build trigger

PRIO COULD-v2 · CLASS NOT-UI · BUILT n/a
GOAL: n/a — audit-analysis trigger for a v2 feature.

## NFR — Service levels & envelope

### R-NFR-01 · Caps (fan-out, bulk 200/5000, preview 8 KiB…)

PRIO MUST-v1 · CLASS UI (sliver) + INDIRECT · BUILT yes
GOAL: When an operator crosses a cap (bulk over 200 via grid, over-cap filter bulk), the
refusal must name the cap and the sanctioned alternative — never a silent trim.
ENTRY: `/search` FAILED results, attempt an over-cap bulk · user `operator`.
SUCCESS: refusal states the numeric cap + next move ("use the filter-scope bulk" / "narrow
the filter"). Numeric enforcement itself is CI (`R-NFR-*` test assertions).
FIXTURE: needs a >200-member FAILED cohort to trip the grid cap honestly — see FIXTURE
GAPS (F-G3); without it, the tester verifies the cap is _stated_ in the bulk UI copy.

### R-NFR-02 · Latency budgets

PRIO MUST-v1 · CLASS INDIRECT (perception sliver) · BUILT yes
GOAL: No search/landing interaction leaves the tester without feedback long enough to
wonder whether the app is broken (perceived responsiveness; numbers are CI's job).
ENTRY: `/` and `/search` · user `viewer`.
SUCCESS: tester never reports "is it hung?"; loading states visible. CI: R-NFR-02 gates.

### R-NFR-03 · Triage cache TTL + refresh rate limit

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: An engineer must be able to tell how old the triage numbers are, force a refresh,
and — when refreshing too eagerly — get a legible "slow down" rather than a mystery error.
ENTRY: `/` triage landing · user `viewer`.
SUCCESS: tester states the data age from the "as of" stamp; after spamming Refresh, the
429 renders as plain language with retry-after, not a toast dump.
FIXTURE: standard seed.

### R-NFR-04 · Health-strip alarms (executor starvation)

PRIO MUST-v1 · CLASS UI-STAGED (hard) · BUILT yes
GOAL: A day-shift engineer glancing at the health strip must spot an executor-starvation
signal (oldest executable job age / overdue timers) without opening any engine.
ENTRY: `/` health strip · user `viewer`.
SUCCESS: tester identifies which engine is unhealthy and which signal fired.
FIXTURE: staging executor starvation on a healthy dockerized engine is impractical —
DEFERRED from the standard run; verify alarm _presence/copy_ only when it fires in CI.

### R-NFR-05 · Operating envelope + history-level gating

PRIO MUST-v1 · CLASS UI-STAGED · BUILT partial
GOAL: On an engine with history below `activity`, Timeline/sibling-diff must be greyed
with the reason (probed), never broken or silently empty.
ENTRY: `/inspect/...?tab=timeline` on a dialed-down engine · user `viewer`.
SUCCESS: tester reads the gate reason and knows the feature isn't broken.
FIXTURE: needs an engine configured `history-level: none` — see FIXTURE GAPS (F-G5).

### R-NFR-06 · Reason ≥10 chars

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: A tired operator entering a lazy reason ("fix") must be corrected inline, before
submit, with the rule stated — never a post-submit failure.
ENTRY: any tier-2/3 confirm (e.g. terminate on dev engine) · user `admin`.
SUCCESS: inline rejection names the ≥10-char rule; the operator recovers without losing
the modal state.
FIXTURE: standard seed.

### R-NFR-07 · write-ms timeout

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes
GOAL: n/a directly — surfaces only through the UNKNOWN outcome copy (see R-SAFE-09).

### R-NFR-08 · Deep-paging envelope

PRIO COULD-v2 · CLASS UI · BUILT yes
GOAL: An engineer browsing a time-sorted result set past the first pages must always know
whether "the end" is truly the end or the depth cap, and what to do next (narrow).
ENTRY: `/search` broad MIXED search, repeated Load-more · user `viewer`.
SUCCESS: tester distinguishes end-of-results from `depthCapped` and can state the next
move; no duplicate/skipped-row confusion goes unlabeled.
FIXTURE: standard seed (ACME suite provides volume); config-lowered cap optional.

## SEM — Semantics & contracts

### R-SEM-01 · Glossary linkage

PRIO MUST-v1 · CLASS UI · BUILT partial
GOAL: A first-time engineer hitting an engine term ("dead-letter job", "process instance")
must reach its plain-language definition without leaving the app or opening docs.
ENTRY: anywhere the terms render (chips, lanes) · user `viewer`.
SUCCESS: tester locates a tooltip/link that defines the term; correctly restates FAILED vs
RETRYING semantics afterwards.
FIXTURE: standard seed.

### R-SEM-02 · FAILED vs RETRYING mis-triage guard

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL (/a triage decision): Shown one FAILED and one RETRYING instance (brief gives two
bare IDs, "you can escalate exactly one tonight — which?"), a first-time engineer must
correctly decide which needs action NOW and which will self-heal.
GOAL (/b ORIENT spine check): opening the FAILED instance's detail, the tester must state
what the case is doing and WHY it is stuck from the first viewport (screenshot-first,
zero clicks) — the ~10s ORIENT bar, translated to agent terms.
SUCCESS: /a picks FAILED as the actionable one citing the chip copy ("needs action" vs
"auto"); zero mis-triage. /b names the failing activity + exception first line from the
vitals/why-stuck strip, citing on-screen text.
FIXTURE: standard seed (`demoFailingPayment` FAILED, `demoFailingRetry` RETRYING).

### R-SEM-03 · Error-signature grouping

PRIO MUST-v1 · CLASS UI (comprehension) + INDIRECT (contract) · BUILT yes
GOAL: An engineer must grasp that the landing groups failures by _error class_ — N
instances, one card — and that the count-by-version tells them which deploy broke.
ENTRY: `/` error groups · user `viewer`.
SUCCESS: tester states "one bug, N instances, concentrated in version X" from a card.
Normalization contract itself: golden-corpus CI (TEST-STRATEGY §4).
FIXTURE: standard seed (`acmeApiOutage` produces a deterministic class).

### R-SEM-04 · Omnibox resolution semantics

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: With only "something from a log" (an instance ID / job ID / business key), an
engineer must land on the right instance across engines — and get an honest, engine-naming
answer when nothing matches. Briefs hand over bare strings as ticket evidence, never the
word "omnibox"; entry is `/`.
SUCCESS: (/a) unique ID → detail page directly; (/b) business key → pre-filtered search;
(/c) garbage → explicit "not found on any reachable engine" naming engines, cited; (/d)
ambiguous → disambiguation list, never auto-navigate; (/e) **no-ID symptom find**: from
only a definition-shaped symptom and rough time window ("expense approvals failing since
last night, no IDs"), reach a failing instance via definition/status/time search.
FIXTURE: standard seed; runner supplies fresh IDs from seed output.

### R-SEM-05 · Curated-view predicate honesty

PRIO MUST-v1 · CLASS UI · BUILT partial (system views: yes; leak-view variants: no)
GOAL: A curated view's name must not promise what its predicate can't deliver — the tester
using "Suspended > 24h (by start time)" must come away knowing the window is start-based.
ENTRY: `/` saved views · user `viewer`.
SUCCESS: tester restates the predicate correctly after using the view.
FIXTURE: standard seed (suspended `demoUserTask` instance exists).

### R-SEM-06 · Error-text search scope

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Searching failures by error text must find the seeded failure AND make the scan's
truncation/scope limits visible; using error-text without a failure predicate must be
refused with a reason, not silently ignored.
ENTRY: `/search` · user `viewer`.
SUCCESS: tester finds `demoFailingPayment` by "divide" / "zero"; the refusal case reads as
a rule, not a bug.
FIXTURE: standard seed.

### R-SEM-07 · Requirement-ID discipline

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — ticket/test naming convention.

### R-SEM-08 · engineId slug / composite split

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — startup validation; CI.

### R-SEM-09 · CAS + concurrent operators

PRIO MUST-v1 · CLASS UI-STAGED · BUILT yes
GOAL (/a CAS conflict): When someone else changed the variable under them, the operator
must end up overwriting nothing, understanding WHO changed it and WHEN, and seeing exactly
two ways forward (start over from current value / cancel) — with no overwrite-anyway path.
GOAL (/b already-resolved verb): an operator retrying/terminating an instance a colleague
resolved moments ago must learn it was already handled and by whom — the double-terminate
guard, `skipped (already resolved), handled by <user>`.
ENTRY: variable editor / action on `demoUserTask` ACTIVE instance · user `operator`.
SUCCESS: /a tester triggers the conflict (runner mutates the variable out-of-band over
REST mid-edit), reads the three-value screen, correctly answers "was anything
overwritten?" (no) and names the other actor. /b outcome names the prior resolution, no
double mutation fires.
FIXTURE: standard seed + runner out-of-band REST mutation (R-TEST-07 helper pattern).

### R-SEM-10 · Bulk = persisted tracked job

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL (/a mixed-outcome report): After submitting a small bulk retry over a cohort that
CANNOT all succeed, the operator must find the per-item outcome report — including after
a full browser refresh — correctly separate ok / failed / skipped, and find the
retry-failed-only path (RECOVER spine).
GOAL (/b panic cancel): mid-bulk, "the lead says STOP" — the operator must cancel the
running job NOW and state exactly which items already ran (CANCELLED state, per-item
truth).
ENTRY: `/search` FAILED rows → grid bulk retry · user `operator`.
SUCCESS: /a per-item classes read correctly, report survives refresh, aggregate "N of M"
quoted; /b cancel found fast, already-ran items enumerated from the report.
FIXTURE: **F-G9** mixed cohort — bulk target includes ≥1 item that fails again or is
skipped (e.g. one protected member or one already-resolved member) so the report is
never all-green; /b needs a cohort big enough to still be RUNNING when cancel is hit.

### R-SEM-11 · Circuit-open mid-bulk pause

PRIO MUST-v1 · CLASS UI-STAGED (hard) · BUILT yes
GOAL: A bulk interrupted by a tripping engine must read as INTERRUPTED with `not_run`
items — never as failures — and offer continue-as-new scoped to the remainder.
ENTRY: operations drawer during a bulk · user `operator`.
SUCCESS: tester distinguishes not_run from failed and finds "continue as new job".
FIXTURE: runner stops an engine container mid-bulk — timing-fragile; run as a single
scripted probe, not a naive-tester arc. CI: R-SEM-11 tests.

### R-SEM-12 · Truncation badges + drill echo

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Numbers the operator anchors on (landing counts) must carry their honesty labels,
and drilling a per-version count must echo the filter it is about to apply.
ENTRY: `/` → click a per-version count · user `viewer`.
SUCCESS: tester can say whether a count is exact or a lower bound, and states what filter
the drill landed them in (echo seen before/at commit).
FIXTURE: standard seed.

### R-SEM-13 · Demoted retry under data-fix annotation

PRIO SHOULD-v1.x · CLASS UI · BUILT no (annotations absent)
GOAL: (plan-route) When a group's annotation implies "fix data first", the one-click group
retry must warn/demote. Not attemptable until R-BAU-03 exists.

### R-SEM-14 · SSE liveness for bulk

PRIO MUST-v1(decision)/SHOULD-v1.x · CLASS UI (observational) · BUILT yes
GOAL: During a running bulk, progress must move on screen without any manual refresh.
ENTRY: operations drawer during bulk · user `operator`.
SUCCESS: tester observes live progression; no F5 needed.
FIXTURE: standard seed.

### R-SEM-15 · OpenAPI codegen

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — contract tooling.

### R-SEM-16 · SPA/BFF version skew

PRIO SHOULD-v1.x · CLASS UI-STAGED (hard) · BUILT no (repo audit: no skew banner/header)
GOAL: (deferred) skew banner + dynamic-import failure → reload prompt. Needs a
mid-session redeploy stage; not in the standard run.

### R-SEM-17 · Removed-engine references

PRIO SHOULD-v1.x · CLASS UI-STAGED · BUILT partial
GOAL: Audit rows / saved views naming a removed engine must render "removed engine <id>"
— never crash or silently vanish.
ENTRY: `/audit` after runner removes a registry entry · user `admin`.
SUCCESS: tester finds the removed-engine label and is not misled.
FIXTURE: runner adds + removes a scratch engine via `/admin/engines`.

### R-SEM-18 · Dual-write UNKNOWN copy

PRIO MUST-v1 · CLASS INDIRECT · BUILT yes
GOAL: (CI-owned: R-TEST-10 audit-integrity suite) The UI arc — "dispatched — outcome
verification failed", never a bare 500 — is only stageable by killing Postgres mid-write;
excluded from the naive run.

### R-SEM-19 · Hierarchy breadth cap

PRIO MUST-v1 · CLASS UI-STAGED · BUILT yes
GOAL: Opening the Hierarchy tab of a parent with dozens of children must stay readable and
responsive, show the first 50 with an explicit "+N more [load next 50]", and never lie
about totals.
ENTRY: `/inspect/...?tab=hierarchy` on the wide-parent fixture · user `viewer`.
SUCCESS: tester states the true child count and pages further without browser lockup.
FIXTURE: **F-G1 (new)** — a multi-instance call-activity parent spawning 60+ children.

### R-SEM-20 · CMMN out-of-scope dead-letters

PRIO SHOULD-v1.x · CLASS UI · BUILT yes
GOAL: An engineer reconciling the health strip's dead-letter lane with the FAILED chip
must understand the "≥N CMMN jobs not triaged here" note and drill it to the scope-typed
CMMN view instead of concluding numbers don't add up.
ENTRY: `/` health strip on engine-a · user `viewer`.
SUCCESS: tester explains the gap and reaches the CMMN drawer (lanes + FAILED drill).
FIXTURE: standard seed (`demo-failing-case.cmmn` on 6.8 engines).

### R-SEM-21 · Migration honest pre-check

PRIO COULD-v2 (built) · CLASS UI · BUILT yes
GOAL: Migrating an instance v1→v2, the operator must (a) find the cohort entry point,
(b) understand the pre-check is an Inspector estimate — NOT a Flowable validation, (c)
complete the migration and see the new version reflected.
ENTRY: `/inspect/...` on `demoMigration` v1 instance → MigrateModal · user `admin`.
SUCCESS: migration completes; tester restates the honesty banner in their own words.
FIXTURE: standard seed (`demo-migration-v1/v2`); verify a live v1 instance exists.

### R-SEM-22 · Deep-paging cursor contract

PRIO COULD-v2 · CLASS UI · BUILT yes — covered by R-NFR-08's goal (same arc).

### R-SEM-23 · Deterministic total order

PRIO SHOULD-v1.x · CLASS INDIRECT · BUILT yes
GOAL: (CI-owned) Weak UI probe only: re-running the identical search must not visibly
reshuffle equal-timestamp rows. Golden regressions are authoritative.

### R-SEM-24 · Team/shared views

PRIO COULD-v2 (designed/landed) · CLASS UI · BUILT yes
GOAL: An operator must publish a useful filter as team canon, and a colleague must find
it, distinguish it from private/system views, and replay exactly the same search.
ENTRY: `/search` save → publish as `operator`; consume as `viewer`.
SUCCESS: two-actor arc completes; Team badge understood; replay state identical.
FIXTURE: standard seed.

## SAFE — Operator safety & RBAC

### R-SAFE-01 · RESPONDER tier + grant tooltips

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: A responder must retry a failed job unaided, AND when they hit a wall (variable
edit), the wall must name the missing grant so they know who to call — never a dead icon.
ENTRY: `/inspect/...` FAILED instance · user `responder`.
SUCCESS: retry succeeds with outcome; edit-variable greyed with role reason quoted.
FIXTURE: standard seed.

### R-SAFE-02 · Reversibility badges

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Before committing any destructive verb, the operator must be able to answer "can I
undo this?" from the UI alone — including retry's "queue move reversible, side effects
are not" nuance.
ENTRY: action menus + confirms · user `admin`.
SUCCESS: tester classifies terminate (irreversible), suspend (reversible), deadletter-
delete (recoverable — rescue named) correctly, citing on-screen text.
FIXTURE: standard seed.

### R-SAFE-03 · Tier-0 friction floor on prod

PRIO MUST-v1 · CLASS UI-STAGED · BUILT yes (`frontend/src/actions/InlineConfirm.tsx`)
GOAL: On a prod engine, firing an irreversible tier-0 verb (trigger-timer) must take two
deliberate clicks; on dev, one — and the operator must perceive the difference as
environment-driven.
ENTRY: `demoTimerWait` instance on a prod-tagged engine · user `operator`.
SUCCESS: two-step inline confirm observed on prod; single-click on dev.
FIXTURE: **F-G2 (staging)** — flip one engine's env tag to `prod` via `/admin/engines`.

### R-SAFE-04 · Plain-language verb labels

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Given intents in plain words ("stop waiting and continue", "run the failed step
again", "kill this case"), a first-time engineer must pick the right verb every time.
ENTRY: action menus on suitable instances · user `operator`.
SUCCESS: 3/3 intent→verb matches, citing the secondary labels.
FIXTURE: standard seed (`demoTimerWait`, `demoFailingPayment`).

### R-SAFE-05 · Protected instances

PRIO MUST-v1 · CLASS UI-STAGED · BUILT yes
GOAL: On a protected instance, every verb must read intentionally-locked with the L3
floor named; a bulk sweeping it must report it `skipped (protected)` — never silently act.
ENTRY: `/inspect/...` protected instance · user `operator`; bulk as `operator`.
SUCCESS: tester explains why they can't act + who can; bulk report shows the skip class.
FIXTURE: runner marks one instance protected via the admin write path (see note).
BUILT NOTE (corrected 2026-07-17 by the `2026-07-16-post-fix-full-recert-v2` usability
run — supersedes the 2026-07-13 "BUILT partial, no write path" note above it): the
protected-instance milestone (#165/#172/#184) shipped 2026-07-14, adding the admin
mark/unmark write path this note previously said was missing. This run's M3
(protected-instance skip inside a bulk job) and M6 (protected-instance block on every
verb) both cleanly exercised a live, correctly-marked protected instance with zero
quiet lies — the read/enforcement/display side (verified 2026-07-13) and the write side
(verified 2026-07-17) are now both confirmed BUILT.

### R-SAFE-06 · Break-glass account

PRIO MUST-v1 · CLASS UI-STAGED (oidc only) · BUILT partial
GOAL: (deferred to prod-like leg) Dev profile has no IdP to lose; break-glass door +
bannering is exercised in the Keycloak prod-like run (R-OPS-16), not here.

### R-SAFE-07 · Session lifecycle / forced re-auth

PRIO MUST-v1 (built v2) · CLASS UI-STAGED (oidc only) · BUILT yes
GOAL: (deferred to prod-like leg) The dev Basic chain is exempt from the re-auth gate by
design; the challenge→re-auth→replay arc and warn-before-guillotine need the OIDC profile.

### R-SAFE-08 · Four-eyes approval

PRIO SHOULD-v1.x (hooks MUST) · CLASS UI-STAGED · BUILT partial (access-mapping writes)
GOAL: An access-admin widening a grant must experience the second-approver stop as
protection with a clear path (who can approve, what happens next), not as a dead end.
ENTRY: `/admin/access` grant-widening write · users `access-admin` + second approver.
SUCCESS: proposer sees pending state + reason; approver flow completes; both identities
in audit.
FIXTURE: needs a second ACCESS_ADMIN identity — see FIXTURE GAPS (F-G4).

### R-SAFE-09 · UNKNOWN "Verify now"

PRIO SHOULD-v1.x · CLASS UI-STAGED (hard) · BUILT **yes** (`OpsDrawer.tsx` verifyBulkItem,
`BulkController`) — repo audit corrected the draft's "unknown/deferred".
GOAL: An UNKNOWN bulk item must offer Verify-now and reclassify with evidence the
operator can read. Producing a true UNKNOWN still requires a latch-gated stub
(R-TEST-07), so this stays a scripted probe, not a naive arc — but it is a REAL surface.

### R-SAFE-10 · Pre-action evidence snapshot

PRIO SHOULD-v1.x · CLASS INDIRECT · BUILT no (repo audit: zero hits)
GOAL: (sliver) The tier-3 confirm states an evidence snapshot is being attached; tester
merely confirms the statement exists. Audit content is CI's job.

### R-SAFE-11 · Break-glass semantics

PRIO MUST-v1 · CLASS UI-STAGED (oidc only) · BUILT partial — see R-SAFE-06 deferral.

### R-SAFE-12 · Mapping storage (file→DB)

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — storage mechanics; UI arcs live under R-SAFE-14.

### R-SAFE-13 · Registry CRUD governance

PRIO COULD-v2 (designed/landed) · CLASS UI · BUILT yes
GOAL: A platform admin onboarding a new engine must experience earned trust as a
guided ladder: born DRAFT read-only → probe → human-confirmed ACTIVE; a missing secret
env must fail BEFORE enablement with the ref named; per-engine ADMIN without
REGISTRY_ADMIN must find the surface greyed-with-reason, not hidden.
ENTRY: `/admin/engines` · users `registry-admin` (can) and `admin` (cannot).
SUCCESS: lifecycle completed on a scratch entry; the `admin` user quotes the gate reason.
FIXTURE: scratch engine entry pointing at an existing engine URL; delete after.

### R-SAFE-14 · ACCESS_ADMIN apex + fail-closed gate

PRIO COULD-v2 (designed/landed) · CLASS UI · BUILT yes
GOAL: The access surface must communicate its apex nature: a plain `admin` is refused
with the reason; an `access-admin` sees current grants legibly (who can do what, where)
and every write lands in audit.
ENTRY: `/admin/access` · users `admin` (refused) and `access-admin`.
SUCCESS: refusal names ACCESS_ADMIN; grants table readable; write audited.
FIXTURE: standard dev ladder.

### R-SAFE-15 · Break-glass built (door + degraded audit)

PRIO COULD-v2 · CLASS UI-STAGED (oidc only) · BUILT yes — deferred with R-SAFE-06.

### R-SAFE-16 · Shared-view governance

PRIO COULD-v2 (built) · CLASS UI · BUILT yes
GOAL: A scope-ADMIN moderating another's team view must find UNPUBLISH as the default
verb (reversible), be required to give a reason, and the author's private copy must
survive — hijack/overwrite paths must simply not exist.
ENTRY: team views picker/moderation · users `operator` (author) + `admin` (moderator).
SUCCESS: unpublish-with-reason completes; author's view intact; no overwrite path found.
FIXTURE: a published team view from the R-SEM-24 arc.

### R-SAFE-17 · Scope-filtered reads

PRIO MUST-v1 (built) · CLASS UI-STAGED (oidc only) · BUILT yes
GOAL: (deferred to prod-like leg) A per-engine/tenant VIEWER must never see another
engine/tenant's failing instances, exception text, or business keys through the
search/triage fan-outs by naming that engine explicitly — and an implicit "all engines"
request must narrow silently to what they can see, never leaking which engines exist
beyond their scope. Not attemptable in the dev run: `scope-reads-enforced` is OFF by
default under `!oidc` (a documented no-op there), and every dev-ladder user
(`viewer`/`responder`/`operator`/`admin`/`registry-admin`/`access-admin`) is
global-scoped — there is no NARROWER-scoped identity to observe an exclusion against,
the same gap R-SAFE-08 already has (F-G4). Needs the Keycloak prod-like leg (R-OPS-16)
with a real per-engine-scoped grant, mirroring R-SAFE-06/07/11/15's deferral.
FIXTURE: n/a in the dev run — see R-OPS-16.

## AUD — Audit & handover

### R-AUD-01 · Fail-closed audit

PRIO MUST-v1 · CLASS INDIRECT · BUILT yes — CI-owned (R-TEST-10); no naive-run stage.

### R-AUD-02 · Audit schema

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — schema contract; CI.

### R-AUD-03 · Data protection / retention

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — ops policy; CI + OPERATIONS.md.

### R-AUD-04 · correlationId everywhere

PRIO MUST-v1 · CLASS UI (sliver) · BUILT yes
GOAL: When something fails, the operator must be able to hand support a correlation ID
without hunting — it rides the error envelope and copy-for-ticket.
ENTRY: any error state (staged engine-down search is fine) · user `viewer`.
SUCCESS: tester locates/copies a traceId from the error surface.
FIXTURE: piggybacks on the R-UXQ-04 engine-down stage.

### R-AUD-05 · Shift report

PRIO MUST-v1 · CLASS UI · BUILT yes (/a with a nuance)
GOAL (/a produce): At end of shift, an operator must produce a handover artifact — "my
activity this shift, UNKNOWNs first" — in one or two clicks from the audit surface.
GOAL (/b consume): the 7am engineer coming ON shift must answer, from the app: what did
night shift do, what is still unresolved/UNKNOWN, did anyone touch instance X?
ENTRY: `/audit` · user `operator` (after other missions created audit rows).
SUCCESS: /b RE-CONFIRMED BUILT — a full night-shift inventory including flagged
unresolved items is producible from `/audit` filters. /a RE-CONFIRMED BUILT with a
nuance: "Copy shift report" on `/audit` produces a structured report, but no dedicated
fleet-wide free-text "my shift note" composition surface exists beyond it — genuine
partial-coverage evidence, not a regression (2026-07-16-post-fix-full-recert-v2).
FIXTURE: audit rows from earlier missions (run this mission last).

### R-AUD-06 · Copy-for-ticket

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL (/a ticket): From a failed instance, one click must yield ticket-pasteable plain
text carrying composite ID, definition+version, status, exception first line, failure
time, deep link — and the latest note + actions summary.
GOAL (/b note for next shift): the responder must leave a "do NOT retry — double-books"
note where the NEXT person opening this instance will actually meet it.
ENTRY: `/inspect/...` FAILED instance · user `responder`.
SUCCESS: /a pasted text contains the six facts; deep link resolves back to the same page.
/b note saved AND visible on re-open (marker on the row/tab, not buried).
FIXTURE: standard seed.

### R-AUD-07 · ticketId on reasons

PRIO SHOULD-v1.x (column MUST) · CLASS UI · BUILT yes
GOAL: A reason prompt must accept a ticket ID that later renders linkified in audit.
ENTRY: any reason-bearing confirm · user `operator`.
SUCCESS: tester finds and uses the ticket-capture field; the value renders linkified in audit.
FIXTURE: standard seed.
BUILT NOTE (corrected 2026-07-17 by the `2026-07-16-post-fix-full-recert-v2` usability
run — the "capture half is dark in the UI" text above was already stale relative to
this file's own aggregate note below, and is now also contradicted by fresh live
evidence): a "Ticket ID (optional — recorded with the audit row and linked in the
operations log)" field was independently found and used by two separate testers in two
separate missions (M3 task 5, M4 task 3) with identical verbatim copy — strong
corroboration of a real, shipped capture field, not confabulation.

### R-AUD-08 · Audit CSV export

PRIO MUST-v1 (CSV) · CLASS UI · BUILT yes (RE-CONFIRMED: "Export CSV" on `/audit`
downloads a genuine `operations-log.csv` with correct headers, 2026-07-16-post-fix-full-recert-v2)
GOAL: An auditor asked "what did operator X do to engine-b this week?" must get the
answer out of the app and into a spreadsheet without a developer.
ENTRY: `/audit` · user `admin`.
SUCCESS: tester downloads a real CSV with correct content-type/content-disposition headers.
FIXTURE: audit rows from earlier arcs.

### R-AUD-09 · Attribution caveat

PRIO MUST-v1 · CLASS UI · BUILT yes (RE-CONFIRMED: the exact caveat text is present on
the per-instance Audit & Notes tab, 2026-07-16-post-fix-full-recert-v2)
GOAL: An engineer asking "WHO did this?" must land on the BFF audit answer and see the
warning that engine-side history blames the service account.
ENTRY: `/inspect/...?tab=audit` · user `viewer`.
SUCCESS: who-question answerable from the tab; the caveat text ("Engine-side history
attributes these actions to the shared service account — this log is the authoritative
WHO") is present verbatim.
FIXTURE: prior actions from other arcs.

### R-AUD-10 · Config-event audit primitive

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — ledger mechanics; CI.

## OPS — Operating the inspector

### R-OPS-01 · Liveness/readiness split

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — probe semantics; CI/ops.

### R-OPS-02 · Prometheus metric set

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — metrics contract.

### R-OPS-03 · Alert rules

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — shipped rules; ops drill.

### R-OPS-04 · RTO/RPO

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — restore drill.

### R-OPS-05 · Graceful shutdown / drain

PRIO MUST-v1/SHOULD · CLASS NOT-UI · BUILT yes — ops.

### R-OPS-06 · CI gate table

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — CI governance.

### R-OPS-07 · Threat model / egress

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — security architecture.

### R-OPS-08 · Injection defenses

PRIO MUST-v1 · CLASS UI (sliver) + INDIRECT · BUILT yes
GOAL: Hostile engine text (a business key or error message containing HTML/CSV formulas)
must render as inert data everywhere the tester meets it.
ENTRY: instance seeded with a hostile business key · user `viewer`.
SUCCESS: markup renders literally; no layout break. CI owns the corpus.
FIXTURE: **F-G6 (new)** — one seeded instance with `<img onerror>`-style business key and
`=SUM()`-style variable value.

### R-OPS-09 · Image hygiene

PRIO SHOULD-v1.x · CLASS NOT-UI · BUILT yes — CI.

### R-OPS-10 · prod-like profile / secret rotation

PRIO SHOULD-v1.x · CLASS UI (sliver) · BUILT partial
GOAL: (sliver) A 401-from-engine must render as "credential rejected" — a different
health state than unreachable — so rotation mistakes are diagnosable from the strip.
ENTRY: `/` health strip · staged bad credential.
SUCCESS: tester distinguishes credential-rejected from down.
FIXTURE: staged via registry edit to a wrong `password-ref` — optional probe, not core.

### R-OPS-11 · Clock-skew badge

PRIO SHOULD-v1.x · CLASS UI-STAGED (hard) · BUILT unknown — deferred; needs a skewed
engine container.

### R-OPS-12 · Capability cache invalidation

PRIO SHOULD-v1.x · CLASS NOT-UI · BUILT unknown — probe mechanics; CI.

### R-OPS-13 · Registry SSRF rails

PRIO COULD-v2 (built) · CLASS UI · BUILT yes
GOAL: A registry admin fat-fingering a base URL (metadata IP, loopback, http-on-prod)
must get a rejection that names the violated rule and the next move — specific enough to
fix, never an oracle for probing.
ENTRY: `/admin/engines` add-engine form · user `registry-admin`.
SUCCESS: `http://169.254.169.254/` and `http://localhost:1/` rejected with named rules;
tester can state what to change.
FIXTURE: none (rejection path only; nothing persisted).

### R-OPS-14 · /api/diag

PRIO SHOULD-v1.x · CLASS NOT-UI · BUILT no (repo audit: no diag controller) — ADMIN API,
no UI arc.

### R-OPS-15 · Registry hot reload

PRIO COULD-v2 (built) · CLASS INDIRECT · BUILT yes
GOAL: (rides R-SAFE-13 arc) After a registry edit, the fleet reflects it without restart —
tester simply observes the strip updating post-edit.

### R-OPS-16 · Transport/header posture

PRIO COULD-v2 (built) · CLASS NOT-UI · BUILT yes — headers; CI.

## UXQ — UX quality

### R-UXQ-01 · Color never alone

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Every environment/status/diff meaning must survive with color ignored: env bands
carry PROD/TEST/DEV text, chips carry labels, diagram failure markers carry glyphs.
ENTRY: all stages · user `viewer` (instructed to cite the non-color channel).
SUCCESS: for each meaning encountered, tester names the textual/shape channel. axe CI
owns contrast.
FIXTURE: standard seed.

### R-UXQ-02 · Keyboard & SR paths

PRIO MUST-v1 · CLASS UI · BUILT partial
GOAL: An engineer must complete FIND→FIX (search, open detail, retry the job) entirely
keyboard-only, and every diagram-borne fact must have a focusable textual twin.
ENTRY: `/search` → `/inspect` · user `responder`, mouse forbidden.
SUCCESS: full arc keyboard-only; textual twin of the failing activity found.
FIXTURE: standard seed.

### R-UXQ-03 · Time display honesty

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: The operator must be able to state exactly WHEN a failure happened — absolute +
offset + relative — and flip the display to UTC in one action; ticket text is always UTC.
ENTRY: FAILED instance vitals + copy-for-ticket · user `viewer`.
SUCCESS: tester reads the timestamp unambiguously; UTC toggle found; ticket text UTC.
FIXTURE: standard seed.

### R-UXQ-04 · Zero-state catalog

PRIO MUST-v1 · CLASS UI-STAGED · BUILT partial
GOAL: "0 results with an engine down" must never read as a confirmed zero: the tester
must correctly answer "does this instance exist?" with "unknown — billing engine was
unreachable", quoting the amber state.
ENTRY: `/search` with one engine stopped · user `viewer`.
SUCCESS: zero-under-partial distinguished from true zero (which must read positive).
FIXTURE: **F-G7 (staging)** — runner stops one engine container for the arc, restarts after.

### R-UXQ-05 · Message style

PRIO MUST-v1 · CLASS UI (cross-cutting rubric) · BUILT yes
GOAL: Every message a tester meets must parse as [what happened][why/gate][next move] with
concrete object names; engine text visibly quoted, never blended into BFF prose.
ENTRY: all arcs (rubric applied by every tester) · all users.
SUCCESS: testers flag any message failing the triple or any bare Success/Failed/HTTP-code.
FIXTURE: n/a — scoring rubric across arcs.

### R-UXQ-06 · Notification budget

PRIO MUST-v1 · CLASS UI (observational) · BUILT yes
GOAL: Nothing steals focus uninvited: modals only user-initiated, one banner per scope,
toasts only for own actions and never the sole record of an outcome.
ENTRY: all arcs · all users.
SUCCESS: testers flag any unsolicited modal, stacked banner, or outcome that lived only
in a vanished toast.
FIXTURE: n/a — rubric.

### R-UXQ-07 · en-only + one formatter

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — statement/mechanics.

### R-UXQ-08 · Dark theme

PRIO SHOULD-v1.x · CLASS UI · BUILT no (per v1.x #3 partial: saved views only)
GOAL: (plan-route + 1-min probe) A dark-preference OS must not get a blinding UI; probe
records current behavior.

### R-UXQ-09 · Column chooser / density / layout persist

PRIO SHOULD-v1.x · CLASS UI · BUILT no
GOAL: (plan-route + probe) Operator hides noise columns; layout survives reload; honesty
columns not hideable.

### R-UXQ-10 · Print / export variants

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route.

### R-UXQ-11 · Subprocess roll-up deep-link

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Landing on the FAILED parent (`demoParent`), the engineer must reach the failing
CHILD's Errors & Jobs tab in one click from the roll-up badge — the parent must never be
a dead end where the retry can't be found.
ENTRY: `/search` → `demoParent` instance · user `responder`.
SUCCESS: tester retries the child's dead-letter job, having navigated via the badge.
FIXTURE: standard seed.

### R-UXQ-12 · Root-vs-child markers + shortcuts

PRIO SHOULD-v1.x · CLASS UI · BUILT partial
GOAL: Searching a business key that names a tree, the engineer must tell root from child
rows; `/` must focus the omnibox from anywhere.
ENTRY: `/search` by `demoParent` business key · user `viewer`.
SUCCESS: root/child distinguished; `/` shortcut works.
FIXTURE: standard seed.

### R-UXQ-13 · Variable editor (form-first)

PRIO MUST-v1 · CLASS UI · BUILT yes
GOAL: Three arcs on one surface: (a) a number edit shows the parsed echo and verifies as
a generated sentence the tester can read back; (b) clearing a text value forces the
explicit empty-vs-null choice; (c) a JSON leaf flip never shows the operator a brace
(form mode), with the structural path diff at verify.
ENTRY: variables tab, ACTIVE `demoUserTask` / ACME instance · user `operator`.
SUCCESS: all three arcs complete with the tester quoting the verification sentence;
no raw-JSON-primary surface met.
FIXTURE: standard seed (ACME instances carry structured variables).

## BAU — Day-shift features

### R-BAU-01 · Error-group acknowledge

PRIO MUST-v1 · CLASS UI · BUILT yes (RE-CONFIRMED: group mutes into "Acknowledged (N)"
without hiding data, dialog states the auto-resurface guarantee pre-commit,
2026-07-16-post-fix-full-recert-v2). Built "acknowledge" only, NOT "annotate" — annotate
is the separate, still-open R-BAU-03.
GOAL: A day-shift engineer triaging a known-noisy error group must acknowledge it (who +
reason + expiry) so it collapses — labeled, never hidden — and trust it will resurface on
growth.
ENTRY: `/` error groups · user `operator`.
SUCCESS: group collapses labeled (not hidden); resurface guarantee (growth / new version
/ expiry) stated before commit.
FIXTURE: standard seed.

### R-BAU-02 · Leak views

PRIO MUST-v1 · CLASS UI · BUILT yes (RE-CONFIRMED with a nuance,
2026-07-16-post-fix-full-recert-v2)
GOAL: A day-shift engineer hunting slow leaks must find "Active > 30 days" style views
grouped per definition.
ENTRY: `/` · user `viewer`.
SUCCESS: "Active > 30 days" is answerable via Search's Status+Started-before filters
(cross-engine-confirmed). Nuance: the purpose-built one-click home-page "Leak views"
widget itself covers SUSPENDED only, despite its name/description implying ACTIVE
coverage too — worth a copy fix or a companion ACTIVE-leak link (tracked in results).
FIXTURE: standard seed.

### R-BAU-03 · Error-class annotations

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route (with R-SEM-13).

### R-BAU-04 · Person-centric task search

PRIO SHOULD-v1.x · CLASS UI · BUILT no (reassign landed; search half unscheduled)
GOAL: (probe) "Find everything assigned to k.meier" — record the gap; reassign-from-
instance itself is covered under R-SAFE-01/04 arcs.

### R-BAU-05 · Watchlist

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route.

### R-BAU-06 · Suspend reason/review-by

PRIO SHOULD-v1.x · CLASS UI · BUILT no
GOAL: (probe) Suspending an instance, does anything ask why/until-when? Record gap.

### R-BAU-07 · Copy business summary

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route.

### R-BAU-08 · Timers-due-in-window

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route.

### R-BAU-09 · Training profile + certification

PRIO SHOULD-v1.x · CLASS NOT-UI · BUILT partial
GOAL: n/a as tester arc — THIS harness (seed.sh + goal catalog + scripted reset) is the
seed of the R-BAU-09 training profile; noted in the reuse doc.

## L3 — Deep support

### R-L3-01 · "Explain this status"

PRIO MUST-v1 · CLASS UI · BUILT yes (RE-CONFIRMED: full falsifiable evidence trail
reached from the chip itself, 2026-07-16-post-fix-full-recert-v2)
GOAL: A skeptical L3 must be able to falsify a status chip: reach the per-leg evidence
(which calls, what came back, what was truncated) from the chip itself.
ENTRY: FAILED instance status chip · user `admin`.
SUCCESS: "Explain this status" surfaces a named Plan, per-engine-call evidence
(URL/status/latency/timestamp), and a flag-by-flag verdict table.
FIXTURE: standard seed.

### R-L3-02 · cURL parity

PRIO MUST-v1 · CLASS UI · BUILT partial (BFF cURL landed; engine cURL v2)
GOAL: An L3 must turn any search and any tier-1+ action into a runnable command without
reverse-engineering the network tab.
ENTRY: `/search` cURL echo + an action modal's "Show as cURL" · user `operator`.
SUCCESS: both cURLs copied; search cURL replays against the BFF (runner verifies).
FIXTURE: standard seed.

### R-L3-03 · Raw JSON escape hatch

PRIO MUST-v1 (links) · CLASS UI · BUILT partial
GOAL: From any detail tab, the L3 must download the raw JSON behind the rendered view —
raw is the escape hatch, never the presentation.
ENTRY: variables/jobs tabs · user `operator`.
SUCCESS: raw download per tab found and non-empty.
FIXTURE: standard seed.

### R-L3-04 · Forensic passthrough

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route.

### R-L3-05 · Stacktrace ergonomics

PRIO SHOULD-v1.x · CLASS UI · BUILT partial
GOAL: Facing a long stacktrace, the engineer must reach the root cause fast (root-cause-
first folding / find-in-trace) and copy both raw and normalized forms.
ENTRY: dead-letter job stacktrace on `acmeApiOutage` · user `responder`.
SUCCESS: root cause stated in <60s of opening the trace; copy affordances found (or gaps
recorded).
FIXTURE: standard seed.

### R-L3-06 · Engine advisories

PRIO SHOULD-v1.x · CLASS UI · BUILT no — plan-route.

## TEST — Test governance

### R-TEST-01 · Risk-ranked coverage floors

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — CI governance (status-join/RBAC/bulk floors).

### R-TEST-02 · Milestone entry/exit gates

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — gate process.

### R-TEST-03 · Defect taxonomy (quiet lie = Sev1)

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — but its taxonomy IS this run's severity rubric:
any quiet lie / guard bypass / wrong-target / invisible apply found by a tester is Sev1.

### R-TEST-04 · Fixture catalog discipline

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — this catalog's FIXTURE GAPS table feeds it.

### R-TEST-05 · Performance scenarios

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — perf CI (R-NFR-02 defers here).

### R-TEST-06 · Security test plan

PRIO MUST-v1 · CLASS NOT-UI · BUILT n/a — security CI (R-OPS-08/13 slivers defer here).

### R-TEST-07 · Testability hooks

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — load-bearing for THIS harness: the out-of-band
mutation helper is R-SEM-09's FIXTURE; `R1/PT1S`/`R10/PT1H` cycles are the seed's
FAILED/RETRYING mechanics.

### R-TEST-08 · UAT with practicing engineers

PRIO SHOULD-v1.x · CLASS meta · BUILT partial
GOAL: This catalog + the agent run IS the standing rehearsal for R-TEST-08 — scripted
incident scenarios, unassisted completion scoring (≥80% bar), trust-breaking observations
filed Sev1/Sev2. The human UAT still runs at M6; agents keep it honest between rounds.
N/N agent pass is WEAK evidence for humans (correlated testers) — de-risk, don't validate.

### R-TEST-09 · Soak/fault-injection

PRIO COULD-v2 · CLASS NOT-UI · BUILT n/a.

### R-TEST-10 · Audit-integrity suite

PRIO MUST-v1 · CLASS NOT-UI · BUILT yes — the CI owner of R-AUD-01/R-SEM-18 deferrals.

### R-TEST-08 · UAT with practicing engineers

PRIO SHOULD-v1.x · CLASS meta · BUILT partial
GOAL: This catalog + the Sonnet-agent run IS the standing rehearsal for R-TEST-08 —
scripted incident scenarios, unassisted completion scoring (≥80% target), trust-breaking
observations filed Sev1/Sev2. The human UAT still runs at M6; the agents keep it honest
between rounds.

---

## FIXTURE GAPS (new test data / staging this catalog needs)

| ID    | Need                                       | Serves                                                                 | Shape                                                                                                                                                                                                                                                                                                                                                                                    | Status                              |
| ----- | ------------------------------------------ | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| F-G1  | Wide MI parent (60 children)               | R-SEM-19 breadth cap, timeline sub-lanes                               | `demo-wide-parent.bpmn20.xml` + `demo-wide-child.bpmn20.xml` (parallel MI call activity ×60, child parks on user task); 1 instance, businessKey `ORD-BATCH-2107`                                                                                                                                                                                                                         | **DEPLOYED** (engine-a, in seed.sh) |
| F-G2  | Prod-tagged engine                         | R-SAFE-03 friction floor, typed prod tokens                            | staging: flip `engine-b` env tag to `prod` via registry for the safety wave; restore after                                                                                                                                                                                                                                                                                               | run-time stage                      |
| F-G3  | >200-member FAILED cohort                  | R-NFR-01 grid-bulk cap refusal                                         | OPTIONAL (~201 fast-fail instances, `R1/PT1S`); else verify cap copy only                                                                                                                                                                                                                                                                                                                | deferred                            |
| F-G4  | Second ACCESS_ADMIN identity               | R-SAFE-08 four-eyes                                                    | dev-ladder gap: single `access-admin` today — the arc records the "no approver available" zero-state instead (itself R-UXQ-04-adjacent)                                                                                                                                                                                                                                                  | accepted gap                        |
| F-G5  | History-dialed-down engine                 | R-NFR-05 history gating                                                | compose override `history-level=none` — DEFERRED unless cheap                                                                                                                                                                                                                                                                                                                            | deferred                            |
| F-G6  | Hostile-text instance                      | R-OPS-08 injection rendering                                           | `demoUserTask` instance, businessKey `<img src=x onerror=alert(1)>`, vars `=HYPERLINK(...)` + `<script>`                                                                                                                                                                                                                                                                                 | **SEEDED** (engine-a, in seed.sh)   |
| F-G7  | Engine-down stage                          | R-UXQ-04 zero-under-partial, R-AUD-04 traceId                          | runner stops `engine-legacy` container during the honesty wave; restarts + verifies after                                                                                                                                                                                                                                                                                                | run-time stage                      |
| F-G8  | Leak-window config                         | R-BAU-02 (when built)                                                  | config-lowered leak thresholds                                                                                                                                                                                                                                                                                                                                                           | blocked on feature                  |
| F-G9  | Mixed-outcome bulk cohort                  | R-SEM-10/a RECOVER report                                              | bulk target includes ≥1 protected or already-resolved member so the report is never all-green                                                                                                                                                                                                                                                                                            | run-time stage                      |
| F-G10 | Sacrificial destructive cohort             | terminate / deadletter-delete / migrate arcs; wrong-instance near-miss | runner seeds instances with businessKey `uxrun-<mission>-<n>` immediately before the arc; testers may only destroy instances carrying their tag — a near-identical untagged twin sits beside each (the near-miss probe)                                                                                                                                                                  | run-time stage                      |
| F-G11 | Ambiguous-match pair (shared business key) | R-SEM-04/d disambiguation                                              | standard seed's `demoUserTask` instances carry no businessKey and `acmeOrderOrchestrator` keys are timestamp-unique — no natural duplicate exists today. Runner starts two fresh instances (any definition, e.g. `acmeOrderOrchestrator`) via out-of-band REST sharing one explicit businessKey `uxrun-dup-<runId>` immediately before the arc (mirrors R-SEM-09's OOB-mutation pattern) | run-time stage                      |

## Known-absent surfaces (evaluator-only; reconciliation separates "not built" from "broken")

**MUST-v1 gaps as of 2026-07-10 (issue #97): 7 of 8 confirmed BUILT (repo-verified
2026-07-13, issue #97 remainder; live-run-verified 2026-07-13, issue #98).** R-BAU-01
acknowledge (2026-07-11) · R-BAU-02 leak views (2026-07-11) · R-AUD-05 shift report
(2026-07-11) · R-AUD-08 audit CSV export (2026-07-11) · R-AUD-09 attribution caveat
(2026-07-11) · R-L3-01 explain-status (2026-07-11) · R-AUD-07 ticket-capture field
sliver (2026-07-11) — all 7 exercised successfully by real testers in the issue #98
usability run, ground-truth-verified, zero quiet lies. **R-SAFE-05 was the exception at
the time this note was written — since resolved: the protected-instance milestone
(#165/#172/#184) shipped 2026-07-14, adding the write path this note originally said
was missing, and the `2026-07-16-post-fix-full-recert-v2` usability run (2026-07-17)
confirmed the write side live end-to-end (see its own entry above). All 8 of the
original MUST-v1 gaps are now BUILT.** Note: R-BAU-01 built "acknowledge" only, NOT "annotate" — that's the separate,
still-open R-BAU-03 below; issue #97's own text conflated the two when it described
R-BAU-01 as blocking R-SEM-13 (R-SEM-13 is gated on R-BAU-03, not R-BAU-01).
SHOULD/COULD gaps (plan-route, not tested): R-BAU-03/04/05/06/07/08 · R-UXQ-08/09/10 ·
R-L3-04/06 · R-SEM-13 · R-SEM-16 · R-SAFE-10. R-OPS-14 flipped to built BACKEND-ONLY
(`GET /api/diag`, issue #96, 2026-07-13) — no frontend diagnostics page exists yet.

---

## RUN PROTOCOL (panel-locked, 2026-07-10)

**Missions, not tours.** Testers run the 11 mission narratives in
`docs/usability/MISSIONS.md` (each bundles 4–8 goal arcs into one incident story); the
1:1 catalog above is the evaluator's coverage map. Wave discipline (shared stack, one
browser):

- **Wave 1 — parallel-safe, read-only** (missions M1 payments-pager, M2 stuck-parent,
  M9 keyboard-only re-run, M10 digging-past-page-one, M11 routine-sweep): viewer/
  responder/operator users; notes + the one out-of-band OOB-staged pair for M11's
  disambiguation arc (runner-side, not a tester mutation) are the only mutations.
- **Wave 2 — mutating, strictly serialized** (M3 bad-data, M4 bad-deploy-cleanup,
  M7 morning-handover LAST — it consumes the others' audit rows): definition-namespace
  ownership (M3 owns `demoUserTask`+ACME vars; M4 owns the `acmeApiOutage` cohort);
  bulk arcs ≤10 items, filter-scoped to their cohort.
- **Wave 3 — fleet-staging, exclusive** (M5 half-the-fleet-dark, M6 prod-with-safety-on,
  M8 platform-admin): F-G7/F-G2/read-only flips and registry/access writes change reality
  for everyone — nothing else runs; runner verifies restoration before declaring the run
  done (reseed checkpoint between waves; `docker/seed.sh` is idempotent).
- **Destructive verbs ONLY against F-G10 sacrificial fixtures** (tag rule doubles as the
  wrong-instance near-miss probe). Least-privilege login per mission.

**Tester protocol (fed to every tester verbatim, enforced by the evaluator):**

- Pre-registered intent: before every interaction, state "I expect clicking <element>
  will <Y>"; then act; then note observed. Wrong turns and backtracks are data, not shame.
- Quote verbatim every message you actually meet, tagged confusing/fine (feeds the
  R-UXQ-05/06 corpus — no separate audit tour).
- **Give-up rule** (hard): a task ends `canComplete=no` at the FIRST of: 15 UI
  interactions · 3 distinct strategies exhausted · 2 consecutive interactions yielding
  nothing new · the same element tried twice with the same result. On give-up, record
  your last hypothesis and WHERE you expected the affordance to live.
- **Forbidden moves** (protocol violation, flagged): hand-editing URLs, guessing routes,
  reading page source/JS, calling the API directly, and re-submitting any MUTATING action
  (a second click on a mutation is auto-recorded as an invisible-apply finding).
- Glance-class goals: answer from the FIRST viewport screenshot before any a11y snapshot;
  budgets are counted in snapshots/interactions, never wall-clock seconds.
- Verdict enum: `yes` / `yes-with-struggle` / `no` / `blocked-by-environment`.

**Evaluator protocol:**

- Citation-or-nothing; BUILT-no canaries invalidate a hallucinating tester's whole run.
- **Ground truth check**: every claimed fix is re-verified over REST by the harness (a
  "Success" toast is the UI's optimism, not the engine's reality); mismatch = Sev1
  invisible-lie finding.
- **Pre-flight fixture check**: before each mission, assert fixtures are in their
  intended state; abort `blocked-by-environment` (FIXTURE_DRIFT) rather than let a tester
  "succeed" on a dead instance. `blocked-by-environment` re-stages and re-runs once, then
  escalates as a harness defect — never counted as a UX failure.
- Look for **agent compensatory behavior** (re-scanning, tree-grepping workarounds) —
  a pass achieved through compensation is a friction finding.
- Cross-model option: grade traces with a non-Anthropic critic (gemini/copilot MCP) to
  break the Sonnet-grades-Sonnet mirror; minimum bar is a separate evaluator agent that
  never saw the tester's reasoning, only its trace.

**Exit gate (this run and every nightly):**

- Gate population: PRIO MUST-v1 ∧ BUILT yes/partial ∧ CLASS UI or feasible UI-STAGED.
- Pass bar: verdict ∈ {yes, yes-with-struggle} from the (N of) tester(s), zero protocol
  violations behind it; every spine step (FIND→ORIENT→DIAGNOSE→FIX→OUTCOME→RECOVER)
  covered by ≥1 passing goal; zero open critical-class findings (invisible apply,
  mis-armed destructive confirm, any R-SEM-02 mis-triage — one mis-triage anywhere fails
  the gate).
- Expected-fails (the 6 MUST-v1 gaps): excluded from numerator AND denominator; their
  pass condition is evidence quality (plausible search, honest give-up, located
  expectation). They gate the implementation plan, not the run.
- SHOULD/COULD BUILT-yes goals: report-only. Deferred/hard-staged: listed with reason.
- Rubric gate: zero Sev1 R-UXQ-05/06 violations in the pooled message corpus.

**Nightly statistics:** single-tester passes are flukes with stochastic agents. Nightly
mode runs N≥3 testers per gate mission (N=5 weekly), tracks per-arc verdict distribution
AND step-count (a 4-step task becoming 9 steps is a regression even while green);
baseline = last green run on the SAME catalog version; any yes→no flip or new Sev1/Sev2
fails the night. Every register ID emits exactly one row per run (incl. NOT-UI waivers).

## RESULT SCHEMA (one JSON line per goal-arc × tester)

```json
{
  "runId": "",
  "date": "",
  "catalogVersion": "1.0",
  "appVersion": { "bffSha": "", "spaSha": "" },
  "seedFingerprint": "",
  "engineMatrix": ["6.3", "6.8", "7.1"],
  "goalId": "R-SEM-04",
  "arcId": "b",
  "mission": "M1",
  "persona": "",
  "user": "viewer",
  "modelTier": "sonnet",
  "verdict": "yes | yes-with-struggle | no | blocked-by-environment | waived(NOT-UI) | blocked-not-built",
  "interactions": 0,
  "snapshots": 0,
  "hintsUsed": 0,
  "firstSignalElement": "",
  "confidenceStatement": "",
  "wrongTurns": [],
  "confusionPoints": [{ "step": 0, "quote": "", "surface": "" }],
  "findings": [
    {
      "sev": "Sev1",
      "surface": "",
      "elementCite": "",
      "quote": "",
      "theme": ""
    }
  ],
  "rubricViolations": { "R-UXQ-05": 0, "R-UXQ-06": 0 },
  "protocolViolations": [],
  "groundTruthVerified": true,
  "evidence": [".playwright-mcp/..."]
}
```
