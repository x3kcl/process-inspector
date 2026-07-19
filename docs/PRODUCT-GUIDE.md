# 📘 PRODUCT GUIDE — Flowable Process Inspector

The complete user-facing manual for people operating the Inspector UI. Behavior is
normative in [SPECIFICATION.md](SPECIFICATION.md) (cited throughout as SPEC §n); this guide
tells you how to *use* it. Companions: [OPERATOR-QUICK-START.md](OPERATOR-QUICK-START.md)
(ten-minute onboarding), [AUDIT-ATTRIBUTION.md](AUDIT-ATTRIBUTION.md) (read before your
first mutation), and the hands-on [tutorials](tutorials/) (§16). Deploying or operating the
Inspector itself is a different job: [OPERATIONS.md](OPERATIONS.md) + [RUNBOOK.md](RUNBOOK.md).

## 1. What the Inspector is

One web UI over multiple Flowable engines (dev/test/prod) for investigating, troubleshooting
and **fixing** process instances — strictly via each engine's REST API, never its database.
It is built for the on-call engineer under incident pressure (SPEC §1): the loop is
FIND → ORIENT → DIAGNOSE → FIX → VERIFY, plus HANDOVER to the next shift. Two rules color
everything: **do no harm** (every engine call is circuit-broken and bounded — a struggling
engine sheds load and you see a labeled error envelope, never a hung page) and **partial and
labeled beats complete and false** (§2 below). Every instance is addressed by a composite ID
`engineId:processInstanceId`; paste anything — an ID of any kind or a business key — into
the **omnibox** in the header and it resolves across all engines (SPEC §4 "The omnibox").

## 2. How to read every number — the honesty markers

The Inspector never renders a number derived from truncated or partial data without marking
it (SPEC design principle 2, R-SEM-12). Learn these once; they appear on every surface:

| Marker | Meaning |
|---|---|
| **`≥ N` / lower-bound badge** | A scan hit its cap or an engine failed mid-scan. The true number is *at least* N. Trust the badge, not your optimism. |
| **"as of HH:MM"** | The number is a cached/stamped measurement (triage aggregations cache ~20 s; grid snapshots stamp their fetch time). **Refresh** bypasses the cache (rate-limited). |
| **Amber partial banner** | "2 of 3 engines · billing-prod: timeout" — the result covers only the named engines. Any count under it is a lower bound. Bulk over a partial set is blocked until you explicitly acknowledge the exclusion (§10). |
| **"0 shown — … this is NOT a confirmed zero"** | Zero under partial coverage. Distinct from the confirmed zero ("confirmed zero across N engines"), which is only claimed when every engine answered — and names any registered-but-excluded engine in the same sentence. |
| **Count units** | Job-lane numbers say **jobs**; status tiles and drill totals say **instances**. 36 jobs and 46 instances over the same failure are both true — never compare across units. |
| **"loaded so far" / `~N`** | A page-capped grid total vs. the engine-reported uncapped match total (the bulk filter modal's `~N`). Three honestly different measurements are labeled as three, never presented as one. |
| **Derived, not stored** | FAILED and RETRYING are *derived* by the BFF from the job queues (Flowable has no failed-instance state, SPEC §3). Status chips are **flags, not a partition**: a FAILED instance still counts in ACTIVE totals; "SUSPENDED · has dead-letter jobs" is a real collision. Every chip offers **"Explain this status"** — the per-engine-call evidence, re-derived on demand. |
| **Greyed, never hidden** | An action you cannot take stays visible, disabled, with the exact gate in its tooltip: role ("requires ADMIN"), capability ("needs Flowable ≥ 6.8"), state ("no dead-letter jobs"), or engine policy ("registered read-only"). Four different next moves — the tooltip tells you which. |

## 3. Roles — who can do what

Roles are **layered and scoped** (per engine and tenant, SPEC §2): being ADMIN on
`orders-dev` grants nothing on `billing-prod`. Two fleet-level grants sit **outside** the
ladder, and a per-engine ADMIN does *not* confer them.

| Role / grant | Adds | Typical holder |
|---|---|---|
| **VIEWER** | Everything read-only: triage, incidents, search, detail, case detail, task search, ops log, "Explain this status", copy-for-ticket, cURL views | Anyone on the support org |
| **RESPONDER** | Tier-0 verbs (retry job, retry now, trigger timer, suspend/activate instance), unstick event, notes, and the bulk retry doors | L1/L2 on-call |
| **OPERATOR** | Tiers 1–2: edit variable, complete task, reassign/return-to-team, rerun from activity, change state, restart as new; error-group acknowledge; incident resolve/reopen; publish team views | L2/L3 |
| **ADMIN** | Tiers 3–4: suspend definition, terminate/delete, delete dead-letter job, migrate, destructive bulk; protect/unprotect instances and definition keys | L3 / platform |
| **REGISTRY_ADMIN** (fleet) | `/admin/engines` — engine registry CRUD. Orthogonal: an engine ADMIN cannot touch the registry, and a registry admin holds no engine verbs | Platform administrator |
| **ACCESS_ADMIN** (fleet) | `/admin/access` — the group→scope mapping, the most privileged store in the tool. Widening changes need a **second independent ACCESS_ADMIN** (four-eyes) | Platform administrator |
| **Break-glass** | A sealed emergency ADMIN-global session for IdP outages — never a fleet grant. See §14 | Nobody, until a P1 |

Deployments may override the role→verb matrix with per-verb grants; the disabled-action
tooltip cites the missing grant either way. On a **read-only engine** every mutation is
refused regardless of role — a rollout contract with the engine owner, not a bug.
**Protected instances** (🔒 badge) refuse all verbs below the configured floor; bulk
auto-excludes them as `skipped (protected)`.

## 4. Stage 0 — the triage landing (`/`)

**Answers:** "what is broken, how much, where" — in zero keystrokes. The default route.

- **Engine health strip** (header, every page): per engine an environment-colored badge,
  version, reachability, and the four **job-lane counts** — exec / timer / susp / DLQ — with
  two derived alarms: *oldest executable job age* and *overdue timers* (both are
  executor-starvation signals: escalate to the engine owner, don't hunt in instances). The
  lane IS the diagnosis — see the table in [OPERATOR-QUICK-START.md](OPERATOR-QUICK-START.md).
- **"Failures by error class"** — the centerpiece. Dead-letter and retrying jobs grouped by
  normalized exception signature (SPEC §4/R-SEM-03), with per-engine and per-definition-version
  counts. Each count is its own drill: the group total opens *every* FAILED + RETRYING
  instance of the class in the grid (the whole-class scope survives into filter-scope bulk);
  each per-version count opens that slice. **"Retry group"** per version and **"Retry group
  (all versions)"** per definition dispatch the error-class bulk retry (§10) right from the
  card.
- **Acknowledge** (OPERATOR+): mutes a *card*, never engine state. Reason ≥10 chars required,
  optional ticket + expiry. Acknowledged groups collapse into "Acknowledged (N)" — never
  hidden — and **auto-resurface** badged "GREW SINCE ACK: +45" / "NEW VERSION SINCE ACK" /
  "ACK EXPIRED". Ack is noise control for the landing; for "we fixed this", use the incident
  ledger's Resolve (§5 — the distinction matters).
- **Status counts** — instance tiles per status, each a pre-filtered search link.
  FAILED/RETRYING are synthesized from the failure-lane scans and carry lower-bound badges
  under truncation, exactly like the grid.
- **Leak views** — *Active > 30d / > 90d*, *Suspended · started > 7d*, grouped per
  definition: the slow leaks that never enter a failure lane. Age is measured from **start
  time** in every window (Flowable records no suspension timestamp — the label says so,
  R-SEM-05). Every count deep-links to the exact search it measured.
- **Saved views** (system + your own + team-published), **Recent searches**, and **Recent
  operations** (the audit tail) round out the landing.
- The **out-of-scope note** ("≥N CMMN jobs not triaged here") appears when a co-deployed
  CMMN engine shares the job tables; "View jobs" opens the read-only case-scope drawer
  (Flowable 6.8+ only). See §9.

Counts are served from a ~20 s cache — the "as of" stamp shows the age; Refresh bypasses it,
rate-limited. Tutorial: [01 — triage first look](tutorials/01-triage-first-look.md).

## 5. Incidents — the ledger (`/incidents`, "Incidents" in the header)

**Answers:** "what happened, when, and did it come back" — the question Stage 0 cannot,
because its groups are recomputed per poll and vanish the moment a DLQ drains. The ledger
(SPEC §4e, R-BAU-10) persists one **incident** per normalized error signature, fleet-wide:
one root cause = one card, with the per-engine × definition breakdown inside. VIEWER floor.

- **Lifecycle**: `OPEN → RESOLVED → REGRESSED`. **Resolve is strictly human** (OPERATOR+,
  reason ≥10, optional ticket) — it closes the current *episode*. **REGRESSED is automatic**,
  but gated: after a resolve, at least one sampler cycle must observe the class absent/zero
  before a reappearance can regress it — so resolving while the queue is still draining never
  instantly "zombies" back. **Reopen** is the human undo ("resolved by mistake", reason
  required): it re-opens the last episode and does *not* count as a regression.
- **Quiet** is derived at render, never stored: still open, but nothing observed within the
  quiet window (default 24 h) — "worth a second look before you assume it's fixed."
- **List sections**, in order: Regressed (alarm styling) → Open → Quiet → Resolved
  (collapsed) → Archived generations (incidents fingerprinted by an older normalizer
  version — never silently re-bound).
- **Detail view**: the arrival-rate timeline (per-sampler-cycle totals; a truncated sample
  renders as a *floor*, never a dip), per-engine × definition breakdown, the sample raw
  message, the **episode list with per-episode duration** (your MTTR record), and **Recent
  bulk retries** — a read-only join of error-class bulk jobs matching this signature, so
  remediation outcomes are visible in context. "Search these instances" deep-links to the
  live class in the grid. Remediation itself is dispatched from the Stage-0 card's "Retry
  group" buttons, not from the ledger — the ledger never mutates engine state.

### Resolve vs. Acknowledge — the #1 confusion risk

They look similar and are different tools. **Acknowledge** (Stage 0) = "known noise, stop
showing me the card" — per signature × engine × definition, auto-resurfaces on growth,
deleted on un-ack, no history. **Resolve** (ledger) = "we fixed the root cause" — a
fleet-wide, permanent, audited lifecycle claim that arms regression detection and stamps the
episode's time-to-resolution. Resolving does **not** mute the Stage-0 card (the modal says
so); the resolve dialog offers an explicit **"Also acknowledge on the live dashboard"**
checkbox that additionally runs the ack flow per engine × definition as a second,
separately-audited action. Rule of thumb: *fixed it → Resolve; can't fix it yet but tired of
seeing it → Acknowledge.* Tutorial: [03 — incident lifecycle](tutorials/03-incident-lifecycle.md).

## 6. Search (`/search`, Stage 1)

**Answers:** "find me the instances matching X, across every engine."

- **The whole search state lives in the URL** — copy the address bar into a ticket and the
  recipient sees exactly your result set. This is the handover primitive; every saved view is
  just a named URL.
- Filter rail (collapses to chips once a search runs): engines, status flags, definition
  key/version, business key (exact + contains), current activity, error text, started/failure
  time windows, variable predicates (warned as unindexed — narrow by definition). Combination
  rule: **AND between categories, OR within one** — the compiled-criteria echo below the form
  shows exactly what will run, and "As cURL" gives the equivalent `POST /api/search`. An
  all-blank form disables Search with the reason (nothing would be sent).
- **Failure time ≠ start time**: "failed in the last hour" filters on the dead-letter job's
  create time, not on when the instance started. The system view *Failed in the last hour*
  uses it.
- Grid: engine badge, process ID, business key (call-activity children marked "↳ child"),
  status chip + badges, definition + version, start/failure time, activity/error snippet,
  🔒 protection and notes markers. Keyboard-first: Enter opens the focused row.
- **Load more** (deep paging, SPEC §12 / [KWAY-PAGING.md](KWAY-PAGING.md)): past the first
  page the button pages the globally-sorted merged stream — no fake global pagination, no
  silent reorder. It is a point-in-time snapshot ("Loaded more as of HH:MM — newer instances
  won't appear until Refresh"). At the per-engine depth cap you hit the honest **depth wall**:
  "Reached the paging depth on at least one engine" plus a one-click "Continue by narrowing
  to started before HH:MM" — matching rows beyond the wall are *named as possible*, never
  silently dropped.
- **Saved views**: "Save current view…" (per-user, server-backed, re-saving a name replaces
  it) · system views (*Failed (all engines)*, *Failed in the last hour*, *Suspended > 24h (by
  start time)*, *Started in the last hour*) · **team views** ("Publish to team…", OPERATOR+,
  scope-gated; unpublish requires a reason ≥10 from everyone, the author included —
  [SHARED-VIEWS.md](SHARED-VIEWS.md)). Recent searches: your last 10, on the landing and the
  zero state.

Tutorial: [04 — deep search and views](tutorials/04-deep-search-and-views.md).

## 7. Instance detail (`/inspect/{engineId}/{id}`, Stage 2)

**Answers:** "why is THIS one stuck, and what do I do about it." Full-page, deep-linkable,
tab-aware (`?tab=timeline`) — paste the link into a ticket.

- **Vitals header** (always visible): definition + version, engine + environment badge,
  status chip + collision badges (+ "Explain this status"), business key, started/duration,
  current activity — and when failed, the **why-stuck strip**: exception first line, retries
  state ("3/3 exhausted" or "attempt 2 of 3, next retry 14:35"), failing activity, and the
  entry point **"Compare with a sibling — why did this one fail? →"**. An ended-but-terminated
  instance displays a `TERMINATED` chip (reason in the tooltip) instead of a lying
  `COMPLETED`. Header buttons: **copy ID · copy link · copy for ticket** (composite ID,
  definition+version, status, exception, failure time, deep link — one click) and **"open
  logs ↗"** when the engine has a telemetry URL template.
- **Diagram** (read-only bpmn-js, collapsible): token markers on active activities, ⚠ badge
  on dead-lettered ones, selection synchronized with the tabs.
- **Tabs** — Variables · Errors & Jobs · Tasks · Hierarchy · Timeline · Compare ·
  Audit & Notes, each lazy-loaded:
  - **Variables** — a typed ledger, never a raw JSON dump (SPEC §4a): name, plain-language
    type chip, typed preview, scope (process vs. step-local), last-modified; big values are
    byte-capped with "load full value"; "copy raw" is the escape hatch. The **✎ edit** pencil
    (OPERATOR+, tier 1) opens the inline editor: typed widgets (Form) or opt-in Source mode
    for JSON, type locked behind an explicit unlock, explicit "empty text" vs. "no value
    (null)". "Review change…" opens the one verification modal — plain-language sentence,
    old→new diff (JSON as a path diff), exact-request expander — and the confirm button
    *restates the change*, never says "Confirm". Writes are compare-and-set: if someone
    changed the value meanwhile you get the three-value conflict panel and "Start over from
    the current value" — there is no overwrite-anyway button. On success the follow-on
    "Retry the failed job?" is offered, never automatic.
  - **Errors & Jobs** — the four job lanes kept distinct (the lane is the diagnosis), plus
    the read-only **external-worker lane** on Flowable 6.8+ (lock owner / expiration).
    Per-job: retries, exception (stacktrace on expand), and the verbs — inline "Retry job
    8123?" confirm, "Fire timer for job 8123?", Delete (ADMIN, typed job id on prod), each
    with "Show as cURL".
  - **Tasks** — completed AND open user tasks in one ledger, live assignee; Reassign /
    Return to team (OPERATOR+).
  - **Hierarchy** — the call-activity tree, both directions, depth-capped (10) and
    breadth-capped (50/node, "+9,950 more") with exact totals; child failures roll up to the
    parent's chip ("FAILED — in subprocess *chargePayment*" deep-links to the failing child —
    the retry lives on the child, never the parent).
  - **Timeline** — historic activities as duration bars, call-activity sub-lanes nested; a
    dead-lettered async step (whose history row rolled back) is synthesized from the live
    lanes so the failure is never invisible; live badges FAILED/RETRYING.
  - **Compare** — the sibling diff (SPEC §5.2): auto-suggests the most recent successful
    sibling of the same definition version, then three synchronized diffs — variables (on
    capped previews; over-cap pairs say "values differ beyond preview"), path (stroke-style
    overlay on the shared diagram, never hue-only), and per-activity timing. A failed
    auto-suggest is an explicit fetch error, never faked as "no sibling exists".
  - **Audit & Notes** — this instance's action history (who/what/when/outcome, old values,
    reasons) + free-text notes; the handover surface (§12).
- **Action toolbar**: Suspend/Activate (tier 0, `REVERSIBLE` badge on the button), Change
  state / move token, Rerun from activity, Restart as new instance (tier 2), Migrate,
  Terminate / delete (tier 3), 🔒 Protect. Every verb wears its plain-language label and
  reversibility badge (SPEC §5.0); guards escalate with blast radius (§11).
- **Migrate** (ADMIN, tier 3, [INSTANCE-MIGRATION.md](INSTANCE-MIGRATION.md)): pick a target
  version → "Check mapping →" runs the **Inspector pre-check** — honestly bannered "this is
  not a Flowable validation; the engine checks this only when you execute" (the REST API
  exposes no migration validator) → execute with reason (+ typed business key on prod).
  IRREVERSIBLE; a post-dispatch timeout is UNKNOWN + Verify-now, never auto-retried.
- A **disabled** engine's detail still renders (read-only, "disabled, not removed" banner);
  only a genuinely unregistered engine says "Unknown engine".

Tutorial: [05 — instance surgery](tutorials/05-instance-surgery.md).

## 8. Person task search (`/tasks`, "Find tasks")

**Answers:** "what is this person sitting on, across every engine" — the "Bob is on
vacation" query (SPEC §4d). VIEWER floor; state in the URL (`?person=`). One input: the
person's user id. Results: every OPEN task **Assigned** to them plus every one **Claimable**
via a candidate link (deduplicated), with engine, process, created/due. Row actions feed the
existing verbs unchanged: **Reassign** (to a named user) and **Return to team** (clear the
assignee so the candidate group picks it up) — OPERATOR+, active tasks only. Unreachable
engines degrade to a labeled partial envelope, same as search.

## 9. Case (CMMN) detail (`/case/{engineId}/{caseInstanceId}`)

A co-deployed CMMN case gets its own read-mostly Stage-2 route (SPEC §4 "CMMN case detail",
[CMMN-CASE-DETAIL-PHASE-2.md](CMMN-CASE-DETAIL-PHASE-2.md)) — reached from the Stage-0
out-of-scope drawer, the omnibox ("open the read-only case detail"), or a "Called from" link.
Gated to **Flowable 6.8+** (older engines are dead-letter-blind on the CMMN context —
refused, never faked). What differs from BPMN:

- State chip is ACTIVE / COMPLETED / TERMINATED — a case **cannot be suspended**.
- The diagram is cmmn-js; a case deployed without graphical notation shows an explicit
  no-layout state, never a blank box.
- **Plan items** replace the activity timeline — lifecycle states with live 🛑 Failed /
  🔁 Retrying badges. On Flowable 6.8 this is runtime-only: an ended case shows an honest
  "unavailable" note, never a fabricated empty timeline.
- Exactly **two verbs** exist (SPEC §5.3), offered per dead-letter job in the why-stuck
  panel: **Retry job** (tier 0, inline confirm) and **Delete dead-letter job** (tier 3, typed
  job id) — the delete warning is scope-honest: *a CMMN case has no change-state rescue in
  this tool*, so the plan item is orphaned for good. Everything else (suspend, terminate,
  variable edit) is refused for cases.

## 10. Bulk operations & the operations drawer

Every bulk run — whatever door it entered by — is a **persisted tracked job** with per-item
outcomes, visible in the **Operations drawer** (bottom of every page; it survives
navigation, browser refresh, and BFF restart). Reason ≥10 chars is mandatory at every door.
Four doors (SPEC §7):

| Door | Scope | Cap | Floor |
|---|---|---|---|
| **Grid selection** | The rows you ticked; the bulk bar offers the *intersection* of valid actions | 200 | RESPONDER (verb-dependent) |
| **Error class** ("Retry group" on a Stage-0 card) | The group's *coordinates* — signature + definition (+version) — re-resolved server-side at dispatch, never a browser ID list | 200 | RESPONDER |
| **Filter scope** ("Select all ~N matching filter…") | Your current search criteria, re-executed server-side and **paged to exhaustion**; explicit status chips required, COMPLETED not actionable; typed definition key on prod | 5,000 | RESPONDER (verb-dependent) |
| **Destructive** ("Destructive bulk…", terminate-delete only) | Filter scope + the tier-4 wizard: preview enumeration, reason, and on prod **type the resolved count** — a drifted count is refused, never silently re-scoped. **Refuse-unscoped**: status+engine alone is not a narrowing filter | 5,000 | ADMIN |

How to read a job:

- **Per-item outcomes**: `ok / failed / skipped (already resolved) / skipped (protected) /
  unknown / not_run`. A 404/409 on a target means someone else already handled it — enriched
  with "handled by <user> at <ts>" from the audit log. A retry-job `ok` means *the job was
  re-queued*, not that it will now succeed — the drawer says so.
- **UNKNOWN is the outcome that matters**: a timed-out mutation may have happened. It is
  never auto-retried; **Verify now** re-checks the precondition against live engine state and
  reclassifies with evidence. Unresolved UNKNOWNs persist in the drawer and the shift report.
- **Partial-result gate**: bulk over a result set missing an engine is blocked until you tick
  "Proceed anyway — I understand instances outside this result set are NOT included."
- **Circuit-open pause** (SPEC §7): if an engine's breaker opens mid-job, that one item waits
  bounded (~20 s) and retries once safely; past the bound, dispatch to *that engine* pauses —
  held items settle `not_run` and the job finishes **INTERRUPTED**, never burning them as
  failures. Other engines run on independently.
- **INTERRUPTED** (BFF restart or circuit pause): nothing resumes automatically, ever. The
  drawer banners it and offers **"Continue as new job (N not run / failed)"** — a fresh,
  freshly-audited submission pre-scoped to the leftovers.
- Live progress streams over SSE; overlapping a RUNNING job requires an explicit run-anyway
  naming the other job and its owner. No cross-engine transactionality — stated in the UI.

Tutorials: [02 — fix a failure class](tutorials/02-fix-a-failure-class.md),
[06 — bulk with guardrails](tutorials/06-bulk-with-guardrails.md).

## 11. The verb catalog at a glance

Full catalog with preserved-semantics per verb: SPEC §5. Guard ladder: SPEC §6. Every verb
shows a plain-language label ("run the failed step again"), a reversibility badge
(`REVERSIBLE` / `RECOVERABLE` / `IRREVERSIBLE`), and "Show as cURL" (the server-computed
equivalent call — everything the UI does is scriptable).

| Tier | Verbs | Floor | Guard |
|---|---|---|---|
| 0 | Retry job · Retry now · Trigger timer · Suspend/Activate instance | RESPONDER | No modal; outcome toast with an explicit delta + audit link. Irreversible-side-effect verbs (trigger-timer, retry-now) get a two-step inline confirm on prod |
| 1 | Unstick event (RESPONDER) · Edit variable · Complete task with data · Reassign / return to team | OPERATOR | Diff confirm (old→new); reason required on prod; variable edits are compare-and-set |
| 2 | Rerun from activity · Change state / move token · Restart as new | OPERATOR (change-state: ADMIN on prod) | Confirm + required reason + plan-as-a-sentence + raw REST body; change-state preview is a labeled *BFF simulation* (no engine dry-run exists) |
| 3 | Suspend definition · Terminate/delete · Delete dead-letter job · Migrate | ADMIN | Server-fresh target restatement, cascade victims enumerated, env band inside the modal, required reason; on prod a **typed target-specific token** (business key / job id / definition key — never a generic "yes"). Cancel-focused; Enter never submits |
| 4 | Destructive bulk (terminate-delete) | ADMIN | The §10 wizard; on prod **type the count** |

Three outcome classes for any mutation (SPEC §6): *refused pre-flight* (nothing happened),
*engine rejected* (nothing happened; the engine's words quoted), *dispatched — outcome
verification failed* (assume it happened until Verify-now says otherwise; never a generic 500,
never auto-retried).

## 12. Operations log, audit & handover (`/audit`, "Ops log")

Everything anyone does through the Inspector lands in its own append-only audit store —
with the human's name, reason, ticket, payload and outcome. The header caveat states the
one thing to internalize: **"Engine-side history attributes these actions to the shared
service account — this log is the authoritative WHO."** (Full story:
[AUDIT-ATTRIBUTION.md](AUDIT-ATTRIBUTION.md).)

- **Filters**: actor, action, ticket, since. **"My shift"** presets to your own actions in
  the last 8 h. **"Copy shift report"** exports plain text (UTC ISO), UNKNOWN outcomes
  grouped first under NEEDS VERIFICATION — the handover artifact. **"Export CSV"** streams
  the filtered skeleton columns (payload bodies never travel; cells formula-escaped).
- Break-glass actions wear a `break-glass` badge and sort to the top of the shift report.
- **Per-instance**: the Audit & Notes tab answers "what did the last shift try" without
  leaving the instance. **Notes** (RESPONDER+) are the freeform half — "do NOT retry —
  double-books; tax-service fix ETA 9am". The latest note rides along in **copy for ticket**.
- Every UI-surfaced error carries a `requestId`; the same correlation id is on the audit row
  and in the BFF logs — quote it when escalating an Inspector problem.

Tutorial: [08 — handover and audit](tutorials/08-handover-and-audit.md).

## 13. Admin surfaces

Greyed-never-hidden in the header for everyone else, with the missing grant named.

- **`/admin/engines` — "Engines"** (REGISTRY_ADMIN, [REGISTRY-CRUD.md](REGISTRY-CRUD.md)):
  runtime engine lifecycle. A new engine is born **Draft + read-only**, must pass a
  **read-only probe** ("Test connection" — version + capabilities, never a mutating call),
  and only then can be enabled; enabling a prod engine read-write takes a typed engine id
  **and a second independent REGISTRY_ADMIN** (the "Pending proposals (four-eyes)" section).
  Every base URL is **SSRF-validated** against a deploy-config egress allowlist (never
  editable in-app) — a refusal names the rule and the next move. Secrets stay env-refs: the
  page shows `ENGINE_X_PASSWORD: ✓ present / ✗ absent`, never a value. Remove is a soft
  tombstone (audit references keep resolving); Purge is the irreversible hard delete.
- **`/admin/access` — "Access"** (ACCESS_ADMIN, [IDP-SECURITY.md](IDP-SECURITY.md)): the
  group→scope mapping — who gets which role on which engine/tenant, plus the fleet grants.
  Any **widening** change (self-widen, ≥OPERATOR with wildcard scope, any fleet-grant
  create/remove) becomes a pending proposal for a second independent ACCESS_ADMIN. The
  access-review section exports the full effective-grant table (CSV/Markdown) — the
  release-gate artifact. Deployments pinned to `mapping-source: file` show the mapping
  read-only with CRUD disabled — correct behavior, not an error.
- **`/admin/remediation-demand` — "Demand check"** (ADMIN): the measurement gate for the
  future playbooks feature (SPEC §5.1, R-GOV-08) — mines the audit log for repeated
  multi-step fix sequences and reports whether the build trigger fired. Read-only analysis;
  nothing is persisted.

Tutorial: [07 — admin onboarding](tutorials/07-admin-onboarding.md).

## 14. Break-glass (`/break-glass`)

For exactly one scenario: **the IdP is down during a P1** (SPEC §6, RUNBOOK §4). A plain,
JS-free sign-in form the BFF serves itself at a directly-known URL — there is no in-app
link, because the SPA cannot load pre-auth. The sealed account gets a 4 h ADMIN-global
session (protected instances included) but **never** a fleet grant, and it bypasses neither
the guard ladder, nor read-only engine mode, nor the path whitelist, nor audit. Every verb —
tier 0 included — demands a reason; every action is flagged `break-glass`, bannered on every
page, alerted on login, and listed first in the shift report. Afterwards: rotate the
credential. If the page 404s, the deployment has no break-glass configured.

## 15. Watermark & licensing note

The BPMN/CMMN canvases are bpmn.io components; the **"Powered by bpmn.io" watermark is a
license term** (R-GOV-05) — it must stay visible and unmodified, and the frontend build
fails if any code touches it. Don't ask for it to be hidden; it can't be, by design. The
Inspector itself is Apache-2.0 (see the repo README / NOTICE).

## 16. The two playgrounds & the tutorials

- **The demo** — <https://pi.naumann.cloud>: two seeded Flowable 6.8 dev engines
  (`engine-a`, `engine-b`) with the ACME demo catalog, including deliberately-failing
  definitions. Sign-in ladder (password `dev`): `viewer` · `responder` · `operator` ·
  `admin`, plus the fleet accounts `registry-admin` / `registry-admin-2` / `access-admin`.
  Both engines are `dev`-tagged, so prod-only guards (typed tokens, mandatory tier-1
  reasons) won't fire there — each tutorial flags what prod adds. It is shared: expect
  other people's fingerprints in the ops log.
- **The local stack** — the repo's dev compose (`docker compose -f
  docker/docker-compose.dev.yml up -d`, then `bash docker/seed.sh`; README "Quick start"):
  the same two engines on :8081/:8082 plus optional Flowable 7.1 (:8083) and legacy 6.3.1
  (:8084) profiles for capability-gate spelunking. Required for the flows the demo can't
  host (full engine onboarding, OIDC/IdP flows).

| Tutorial | Sign in as | You practice |
|---|---|---|
| [01 — Triage first look](tutorials/01-triage-first-look.md) | `viewer` | Reading Stage 0 honestly; drilling a group into search |
| [02 — Fix a failure class](tutorials/02-fix-a-failure-class.md) | `responder` | Group → Retry group → per-item outcomes |
| [03 — Incident lifecycle](tutorials/03-incident-lifecycle.md) | `operator` | Resolve with reason; quiet/REGRESSED; also-acknowledge vs. plain ack |
| [04 — Deep search & views](tutorials/04-deep-search-and-views.md) | `viewer` | Filters, Load more & the depth wall, saving and sharing views |
| [05 — Instance surgery](tutorials/05-instance-surgery.md) | `operator` | Variable editor, retry, suspend with reason, migrate preview |
| [06 — Bulk with guardrails](tutorials/06-bulk-with-guardrails.md) | `operator` (+ `admin`) | Filter-scope bulk, partial-result gate, destructive tier |
| [07 — Admin onboarding](tutorials/07-admin-onboarding.md) | `registry-admin`, `access-admin` | Engine registration incl. SSRF rails; the grant flow |
| [08 — Handover & audit](tutorials/08-handover-and-audit.md) | `responder` | Notes, copy-for-ticket, shift report, reading the audit trail |
