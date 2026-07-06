# 📄 SPECIFICATION — Flowable Multi-Instance Process Inspector

Status: **v3.2** · Owner: workflow platform team ·
Inspired by IBM BAW Process Inspector; refined against Camunda Operate/Cockpit, Temporal,
Flowable Control, Conductor/Orkes, Airflow and Step Functions, and a four-seat design review
(workflow-engine expert, senior support engineer, lead developer, UX expert — see
[DESIGN-REVIEW.md](DESIGN-REVIEW.md)).

## 0. Glossary (normative — UI copy links to these definitions)

- **Engine** — one registered Flowable REST endpoint (a registry entry). Never used for the
  Flowable runtime concept in UI copy.
- **Process instance** — one run of a process definition. UI copy never says bare "instance".
- **FAILED** — the instance holds ≥1 dead-letter job: retries exhausted, it will NOT run
  again without operator action. Chip copy: "FAILED — needs action".
- **RETRYING** — a job has failed ≥1 time but retries remain; the engine will retry
  automatically; it may self-heal. Chip copy: "RETRYING (n/m, auto)". *(Internal flag name
  `hasFailingJobs` is unchanged; the display term was renamed from "FAILING" because "-ing"
  reads as more urgent than "-ed" and drove mis-triage in walkthroughs.)*
- **Job lanes** — Flowable's four job queues: executable, timer, suspended, dead-letter.
  The lane a job sits in IS the diagnosis.
- **Error class / signature** — the normalized, versioned exception signature (§4) that
  groups failures; the binding key for acknowledgments, annotations and playbooks.
- **Verb** — one corrective action from the §5 catalog.
- **Composite ID** — `engineId:processInstanceId`; split on the FIRST `:`.
- **Sibling** — another instance of the same definition version, used for comparison (§5.2).

## 1. System overview

The Process Inspector is a centralized administrative tool used by support teams and workflow
administrators to investigate, troubleshoot, and **fix** runtime problems with process
instances across **multiple Flowable environments** from one UI — strictly via the Flowable
V6 REST API.

**The primary user** is an on-call support engineer under incident pressure — an intermittent
user of this tool who lives in tickets and logs. Every design decision is judged against the
incident loop: **FIND → ORIENT → DIAGNOSE → FIX → VERIFY**, plus **HANDOVER** to the next
shift. Secondary personas with stated requirements (register R-BAU-*, §9): the **day-shift
BAU engineer** (sweeps, watchlists, hygiene), the **platform administrator** (registry,
secrets, claim mapping — registry validation errors fail fast and name the offending entry),
the **auditor** (export + read access, §9), and the **shift lead** (operations log, reports).

**Stakeholders** (R-GOV-03): product sponsor (pays, approves scope) · per-engine owner
(approves machine account + fencing, signs read-write enablement) · support team lead (pilot
owner) · security reviewer (release gate). v1 success precondition: **≥3 engines registered
(≥1 prod) at pilot start with signed onboarding checklists** (the ARCH §6 recipe is that
checklist).

### Design principles (locked)
1. **Degrade, don't blank** — an unreachable engine yields labeled partial results, never a
   failed search.
2. **Partial and labeled beats complete and false** — never render a status, count, or result
   set derived from truncated data without marking it. A truncated dead-letter scan must never
   let a failed instance impersonate a healthy one.
3. **Friction proportional to blast radius** — a graduated guard ladder, from zero-modal retry
   to typed, target-specific confirmation; unscoped destructive bulk is refused outright.
4. **Every verb says what it preserves** — retry/rerun/restart/change-state each state, in the
   UI, what happens to history, variables, instance ID, and definition version.
5. **The UI is an affordance over the API** — every search and action can show its equivalent
   REST call ("copy as cURL"); everything the UI does is scriptable.
6. **Honest capabilities** — features the target engine version (or the REST API itself)
   cannot support are greyed with the reason, never hidden and never faked.

## 2. Core architecture & multi-instance handling

- **Engine Registry** — configuration of multiple Flowable REST endpoints with per-engine
  auth (env-ref secrets), environment tag (`dev|test|prod`), timeouts, optional `tenant-id`,
  and probed runtime state (version, reachability, capability flags). Each entry stores the
  **full base URL including the context path** (`…/flowable-rest/service` on the standalone
  image, `…/process-api` on an embedded engine) — the BFF appends only the standard resource
  paths and **never assumes a path shape** outside config.
- **Engine-side fencing (target architecture for embedded engines)** — engines that embed
  Flowable expose the API via `flowable-spring-boot-starter-rest` mounted at a dedicated
  path (e.g. `/process-api/**`), guarded by a **stateless** Spring Security filter chain
  requiring Basic auth from a dedicated machine account (`inspector-svc` holding
  `access-rest-api`) **and** network-scoped strictly to the Inspector BFF's IP space.
  Recipe, proxy/ingress caveats and the attribution interceptor: ARCHITECTURE §6.
- **API aggregation** — the backend is a BFF: one origin, one session; searches fan out in
  parallel with per-engine timeouts and a partial-result envelope.
- **Integration** — strictly Flowable V6 REST (`/query/*`, `/history/*`, `/runtime/*`,
  `/management/*`, `/repository/*`). No engine-DB access, no embedded engines. The BFF
  **whitelists** engine paths — never a blind proxy (flowable-rest authorization is binary,
  so BFF RBAC is the only real permission layer).
- **Persistence** — one small PostgreSQL owned by the BFF (from M4): audit log, instance
  notes, bulk-job state + per-item reports, saved views (v1.x), registry CRUD (v2).
- **Resiliency & circuit breaking (the "do no harm" rule)** — the engines are often already
  under severe load or DB contention *exactly when this tool is in use*. The BFF wraps all
  outbound engine calls in **circuit breakers (Resilience4j)** with strict timeouts: a
  struggling engine must not cause thread starvation in the BFF, and an open circuit must
  not be tipped over by aggressive fan-out (DLQ scans, triage aggregations). An open
  circuit renders as the standard per-engine error envelope ("circuit open — engine
  shedding load"), never a failed search.
- **Audit golden master (the service-account problem)** — because all API mutations arrive
  at the engine under the shared service account, Flowable's native history tables
  (`ACT_HI_IDENTITYLINK`, `ACT_HI_DETAIL`, `ACT_HI_TASKINST`) record *the service account*
  as the actor for every task completion, variable edit and state change. **The BFF's
  Postgres audit log (§9) is therefore the definitive system of record for human
  accountability** — not a dashboard nicety. The UI states this where it matters (the Audit
  tab notes "engine-side history attributes these actions to the service account"), and
  operator onboarding must teach it: querying the Flowable DB will no longer tell you which
  engineer moved the token. Where we control the engine deployment (e.g. flap, ARCHITECTURE
  §6), the BFF additionally sends `X-Forwarded-User` for an engine-side interceptor to
  attribute natively — optional, per-engine, never relied upon.
- **Tenant-scoped RBAC** — when OIDC is used, roles map to **(role, engineId, tenantId)
  scopes**, not global grants: ADMIN on `orders-tenant-A` must not authorize actions on
  `orders-tenant-B` or on another engine. Enforced in the BFF guard layer; the UI greys
  out-of-scope actions with the RBAC reason. Roles are **layered, not binary** (R-SAFE-01):
  `VIEWER` (read-only) → **`RESPONDER`** (tier-0 verbs + unstick + notes — no variable
  writes, no token moves; the L1/L2 runbook tier) → `OPERATOR` (adds tiers 1–2) → `ADMIN`
  (adds tiers 3–4). Deployments MAY override the role→verb matrix with per-verb grants
  `(role, verb, engineId, tenantId)`; tooltips cite the missing grant. OIDC delivers
  identity + coarse groups only; the group→scope mapping is BFF-owned, change-audited
  config (ADR-003 names the pilot IdP and its claim contract). **Mapping storage decided
  (R-SAFE-12):** the mapping lives in a **separately mounted, hot-reloaded file** (same
  mechanism as `password-file` refs: re-read on a ≤60 s TTL, content-hash change logged as
  an audited config event) — NOT inside `application.yml`. Adding an engineer to a scope
  mid-incident is a mounted-file edit applied within a minute, no pipeline run, no restart;
  the platform-administrator SLA is stated in the runbook ("scope grant effective ≤5 min").
  If even that path is unavailable, break-glass (§6) is the fallback. A CRUD UI over the
  same store is v2, with its own guard tier.
- **Protected instances** (R-SAFE-05) — L3+ may mark a composite ID or definition key
  `protected` (reason required; setting/removing is tier-3, audited). Below the configured
  role floor, ALL verbs are disabled-with-reason ("protected — L3 action required");
  protection badge on rows, vitals header, and inside every confirm; bulk and group
  operations auto-exclude protected members, reported as `skipped (protected)`.
- **Read-only engine mode** (R-GOV-04) — registry `mode: read-write | read-only`; the BFF
  rejects every mutating verb against a read-only engine (greyed: "engine registered
  read-only"). This is the rollout ramp: prod engines onboard read-only first; mutation
  rights are enabled per engine on the owning team's written sign-off.
- **Quantified service levels** (R-NFR-01..07) — the numbers behind every "bounded/capped"
  in this spec are normative config defaults and CI test assertions: ≤10 engines per
  fan-out; grid-bulk cap 200 / query-bulk 5,000; per-engine dispatch concurrency 4 +
  250 ms stagger; variable preview 8 KiB (full ≤5 MiB); retry-now timeout 30 s → UNKNOWN;
  triage cache TTL 20 s, Refresh bypass 1/10 s/user; search P95 ≤3 s (5 engines) /
  FAILED-only ≤5 s at 5k DLQ / landing warm ≤500 ms / omnibox ≤2 s; reasons ≥10 chars;
  mutations governed by registry `write-ms`. Operating envelope: ≤5M historic / 100k
  active instances per engine; DLQ fully handled ≤5k, labeled-functional ≤50k; history
  level ≥ `activity` probed and required for Timeline/sibling-diff.

Full topology, composite-ID rule (`engineId:processInstanceId`), and the search-plan joins:
[ARCHITECTURE.md](ARCHITECTURE.md).

## 3. Status model (the FAILED join, corrected)

Flowable has no FAILED instance state; failure lives in job queues. Status is derived in the
BFF — as **flags, not a single value** (statuses collide in reality: a suspended instance can
hold dead-letter jobs):

| Flag | Source |
|---|---|
| `ended` | historic instance `endTime != null` |
| `suspended` | runtime instance `suspended` field (per-row enrichment) |
| `hasDeadLetterJobs` | dead-letter scan (paged to exhaustion, or DLQ-driven plan) |
| `hasFailingJobs` | **FAILING tier**: `/management/jobs?withException=true` + `/management/timer-jobs?withException=true` — failing but retries remaining (retrying jobs park in the timer table between attempts) |
| `failedInSubprocess` | dead-letter job in a call-activity **child**; resolved up the `superProcessInstanceId` chain to the searched root |

The UI renders a primary chip (`COMPLETED / FAILED / RETRYING / SUSPENDED / ACTIVE`, in that
precedence — display terms per the §0 glossary; "FAILED — needs action" vs "RETRYING (n/m,
auto)") **plus secondary badges** for collisions ("SUSPENDED · has dead-letter jobs") and
subprocess roll-up ("FAILED — in subprocess *chargePayment*", which **deep-links to the
failing child's Errors & Jobs tab** — the retry lives on the child; the parent must never be
a dead end, R-UXQ-11). Search filters operate on flag predicates. CMMN-scoped jobs (null
`processInstanceId`) are filtered out of every join; row DTOs carry `scopeType` from day one
for future CMMN support. Multi-tenant engines thread `tenantId` through **every** query leg.

**The derivation is falsifiable** (R-L3-01): every status chip offers **"Explain this
status"** — the per-leg evidence (plan shape chosen and why; each engine call's URL, body,
status, duration, `asOf`, cache-hit vs live, truncation; per-flag provenance "hasFailingJobs
⇐ timer-jobs leg, job 8123"). Evidence is re-derived on demand and labeled with both
timestamps — never pretending the original bytes were retained.

## 4. UI structure — three stages, not three panes

The v1.0 IDE-style fixed split (Search | Results | Details) starved the detail surface where
incident time is spent, and landed users on an empty search form (IBM's documented mistake).
Replaced by:

### Stage 0 — Triage landing (the default route)
Answers "what is broken, how much, where" in zero keystrokes:
- **Engine health strip** — per engine: badge (environment-colored), version, reachability,
  and job-lane counts (executable / timer / suspended / dead-letter) with two derived alarms:
  *oldest executable job age* and *overdue timers* (executor-starvation signals).
- **Failures grouped by error class** — dead-letter (and RETRYING-tier) jobs grouped by
  **normalized exception signature**, with counts per engine and per definition version
  ("NPE in TaxCalculator — 312 · orders-prod · v47: 312, v46: 0"). **The signature is a
  normative, versioned contract** (R-SEM-03): outermost non-wrapper exception class (unwrap
  one level) + message with UUIDs, hex ≥8, digit runs, quoted literals and ISO timestamps
  replaced by `#`, whitespace collapsed, 200-char cap; persisted as `(algoVersion, sha256,
  sampleRawMessage)`. Acknowledgments, annotations and playbook bindings store the
  algoVersion; a normalizer change bumps it, flags bindings "needs re-binding" (never
  silently rebinds), and must pass the golden corpus (TEST-STRATEGY §4).
  **Drill-through is scope-explicit** (R-SEM-12): each per-version count is its own click
  target, and the resulting filter state is echoed before commit. Each group offers
  bulk-retry-the-group — demoted/warned when the group's annotation implies a data fix
  first (R-SEM-13). This is the triage centerpiece.
- **Acknowledge** (R-BAU-01): a group can be acknowledged (who + required reason + optional
  expiry, keyed signature × engine × definition, audited). Acknowledged groups collapse into
  a labeled "Acknowledged (N)" section — never hidden — and **auto-resurface** when the
  member count grows past a threshold or a new definition version appears ("GREW SINCE ACK:
  +45"). Without this the landing rots into alarm fatigue within weeks.
- **Annotations** (R-BAU-03, v1.x): OPERATOR+ may attach per-signature guidance (≤200 chars
  + runbook URL + optionally one **endorsed verb with conditions** — "Retry, but only after
  15:00"). Rendered on the group card and every member's why-stuck strip; the endorsed verb
  is the highlighted action, others demoted; author/updated-at/expiry; audited.
- **Status counts** per engine × status (from query totals — no row fetch). **All Stage 0
  counts carry the same truncation/lower-bound badges as the grid** (R-SEM-12) — the first
  number an operator anchors on gets the same honesty guarantee as the last.
- **Leak views** (R-BAU-02): curated views *Active > 30 days*, *Active > 90 days*,
  *Suspended > 7 days*, grouped per definition ("vacationRequest: 212 > 30d") — the slow
  leaks that never enter a failure lane. Curated-view honesty rule (R-SEM-05): **no system
  view may ship whose predicate the REST API cannot evaluate faithfully** (there is no
  suspension timestamp — "suspended too long" views are defined against audit/notes
  activity and labeled as such).
- **Alarm thresholds** (R-NFR-04, per-engine overridable): oldest executable job >5 min
  warn / >15 min crit; overdue timer = past due >60 s, any = warn, >100 = crit; probe 30 s
  with 2-fail/1-success flap damping.
- **Recent operations** — tail of the audit log.
- **Saved views** — curated system views ship with the product: *Failed (all engines)*,
  *Failed in the last hour*, *Suspended > 24h*, *Started in the last hour*.
- **Short-lived cache (thundering-herd protection)**: the triage aggregations (job-lane
  counts, error groups, status counts) are cached at the BFF for **~15–30 seconds** —
  ten engineers opening the dashboard during a P1 must produce one round of engine
  queries, not ten. The "as of" stamp shows the cache age; Refresh bypasses it (rate-limited).

### Stage 1 — Search + results
- Collapsible filter rail; collapses to chips once a search runs; the results grid gets the
  full width. The **entire search state is URL-encoded** — shareable links are an incident
  primitive, not a hardening feature.
- Below the form: the **compiled-criteria echo** + **"copy as cURL"** against `/api/search`
  (makes the AND-between-categories / OR-within rule visible; teaches the API; replaces a
  query language, which Flowable's query REST cannot honestly execute — rejected, §11).
- Grid: AG Grid; columns Engine (env-colored badge), Process ID, Business Key, Status
  (chip + badges), Definition + **version**, Start Time, **Failure Time**, Current Activity /
  Error snippet, notes marker. Snapshot header: "as of 14:32:05 · Refresh"; auto-refresh is
  opt-in and **suspends while rows are selected**.
- Partial results: slim amber banner ("2 of 3 engines · billing-prod: timeout [Retry]");
  per-engine fetched/total chips ("orders-prod 138 of 2,410 — narrow your filter"); any count
  produced under an engine error or truncation is labeled a **lower bound**.

### Stage 2 — Instance detail: a full-page, deep-linkable route
`/inspect/{engineId}/{id}` from M3, not M6. **Deep links are tab-aware**
(`?tab=timeline`, plus tab-specific anchors like a selected job) — "look at the timeline
gap here: [link]" is the ticket-handover primitive.
- **Vitals header** (no tab, no click): definition + version, engine badge (env color),
  status chip + badges, business key, started/duration, current activity — and when
  FAILED/FAILING, the **"why stuck" strip**: exception first line, retries state ("3/3
  exhausted" or "attempt 2 of 3, next retry 14:35"), failing activity, stacktrace expander.
  When waiting: **what it waits for** (message/signal subscription name, timer due date).
- **Diagram first** (top half, read-only bpmn-js from M3): token markers on active
  activities, red badge on dead-letter activities, synchronized selection with the tabs.
- **Tabs**: Variables · Errors & Jobs · Tasks · Hierarchy · Timeline · Audit & Notes —
  each lazy-loaded (IBM's slow-detail lesson).
  - **Variables** — type-aware inline edit with old→new diff confirm; serializable Java
    objects read-only with an explaining tooltip (REST cannot round-trip them safely);
    execution-local variables shown per node in the execution tree (multi-instance loop
    variables live there). **Size safeguards**: variable values are capped at a byte
    threshold in list responses (truncated preview + "load full value" on demand) — a huge
    JSON payload or base64 blob must crash neither the browser nor the engine; the same cap
    applies to the sibling diff (§5.2), which diffs the truncated projections and flags
    "values differ beyond preview" rather than fetching two blobs.
  - **Errors & Jobs** — Flowable's **four job lanes kept distinct** (executable / timer /
    suspended / dead-letter): the lane IS the diagnosis. Per-job: retries, create time,
    exception (stacktrace fetched on expand), and the verbs (§5).
  - **Hierarchy** — call-activity parent/child tree (both directions); child failures
    surface here and on the parent's status. Tree resolution has a **max-depth limit**
    (default 10) with an explicit "depth limit reached — expand further" affordance —
    a deeply nested or looping call-activity structure must not recurse the BFF or the UI
    into the ground. **Breadth is capped too** (R-SEM-19): max 50 children rendered per
    node ("+9,950 more — not shown [load next 50]") — a parallel multi-instance call
    activity can spawn 10,000 children, and a 10,000-node tree locks the browser thread.
    The BFF's roll-up scan uses count-only queries beyond the render cap; counts stay
    exact, rendering stays bounded, and capped breadth carries the standard lower-bound
    labeling on any derived aggregate.
  - **Timeline** — historic activity instances as duration bars with call-activity
    sub-lanes; failing activity annotated with live job state. (Per-retry gaps are not
    reconstructable from Flowable history — not promised.)
  - **Audit & Notes** — this instance's action history (who/what/when/outcome, old values
    for variable edits, reasons) + free-text notes ("do NOT retry — double-books; tax-service
    fix ETA 9am"). The handover surface.
- **"Copy for ticket"** button: composite ID, definition+version, status, exception first
  line, failure time, deep link — one click, plain text.
- **External telemetry links**: workflow state is half the incident picture; APM/logs are
  the other half. Each engine's registry entry may configure a **telemetry URL template**
  (Kibana/Datadog/Splunk/Grafana) with `{processInstanceId}`, `{executionId}`,
  `{businessKey}`, `{failureTime}` placeholders; the vitals header and the Errors & Jobs tab
  render "open logs" deep links from it. Absent template → no link (never a broken guess).
- **Compare with sibling** (v1.x, §5.2): from a failed instance, one click diffs it against
  a successful instance of the same definition version.

### The omnibox
A global input **pinned in the header on every stage** that accepts a paste of anything:
process-instance ID, execution ID, task ID, job ID, composite `engine:id`, or business key —
resolved in that order across all engines. The most common 3am entry ("I have *something*
from a log") gets one box. **Resolution semantics** (R-SEM-04): exactly one match → navigate
to detail; more than one (any mix of kinds/engines — IDs are only engine-unique) →
disambiguation list (kind, engine badge, status chip), never auto-navigate; business key →
always a pre-filtered search (hierarchy-aware; root vs child rows marked); any engine
unreachable → "resolved against N of M engines" banner; zero hits → explicit "not found on
any reachable engine" naming the engines.

## 5. Corrective actions — the verb catalog

Every verb states what is preserved. Guard tiers per §6. All calls in
[ARCHITECTURE.md §4](ARCHITECTURE.md); Flowable mappings in the `flowable-rest` skill,
operator-facing engine-call parity in the generated **REST Parity Appendix** (R-L3-02 —
built from the same code as the path whitelist, CI-failing on drift; the UI offers both
"show BFF cURL" and **"copy as engine cURL"** with a `$ENGINE_CRED` placeholder).

### 5.0 Language safety & reversibility (R-SAFE-02/03/04)
- Every verb renders a **plain-language secondary label**, spec'd here, not improvised:
  Retry job — *"run the failed step again"* · Retry now — *"run it right now and watch the
  result"* · Trigger timer — *"stop waiting, continue immediately"* · Unstick — *"deliver
  the message/signal this step is waiting for"* · Suspend/activate — *"pause / resume this
  case"* · Edit variable — *"change a data value on this case"* · Complete task — *"finish
  this task on the user's behalf"* · Rerun from activity — *"go back and redo from a chosen
  step"* · Change state — *"move the case to a different step (cancels where it is now)"* ·
  Restart — *"start a fresh copy of this case"* · Suspend definition — *"stop this process
  type for everyone"* · Terminate — *"kill this case permanently"* · Delete dead-letter job
  — *"discard the failed step (the case can never continue past it on its own)"* · Migrate
  — *"move this case to a newer process version"*. Engine terms carry glossary tooltips (§0).
- Every verb carries a **reversibility badge** in menus and confirms: `REVERSIBLE`
  (compensating verb named — suspend↔activate) / `RECOVERABLE` (no undo, rescue path named —
  deadletter-delete → change-state) / `IRREVERSIBLE` (terminate, trigger-timer, retry of a
  non-idempotent job). Retry verbs carry the honesty note: *"the queue move is reversible;
  the side effects of the executed job are not."*
- **Tier-0 friction floor on prod** for verbs that fire irreversible external side effects
  (trigger-timer, retry-now): a two-step inline button confirm (click → "Fire timer for job
  8123?" → click) — sub-second, no modal. Queue-state-only verbs stay single-click.

| Verb | Semantics (UI copy states this) | Tier |
|---|---|---|
| **Retry job** | Dead-letter job moves back to the executable queue; history & variables kept; engine-default retries restored | 0 |
| **Retry now (diagnostic)** | Executes the job synchronously; success or the live exception shown immediately (hard timeout; long jobs blocked) | 0 |
| **Trigger timer now** | Timer fires immediately, takes its normal path | 0 |
| **Unstick event wait** | Deliver message / signal / trigger to a waiting execution (event subscriptions made visible first) | 1 |
| **Suspend / activate instance** | Execution pauses/resumes; jobs move to/from the suspended lane | 0 |
| **Edit variable** | Typed old→new diff; scope-aware (process vs execution) | 1 |
| **Complete task with data** | Task closes with overridden output; warning: a skipped/forced task never writes its own outputs — edit them here | 1 |
| **Reassign task** *(v1.x)* | Assignee changes; task state otherwise untouched | 1 |
| **Rerun from activity (with overrides)** | Guided composite: variable edits first, then change-state; token re-enters the chosen activity; history append-only | 2 |
| **Change state / move token** | Cancels ALL executions at the source activity, starts at target; guardrails: blocked on multi-instance bodies, parallel-join warning, suspended-check (offer activate-first), preview labeled *BFF simulation* (engine has no dry-run) + exact REST body shown | 2 |
| **Restart as new instance** | Completed/terminated instance re-launched with copied historic variables; **explicit fork: pin original definition version vs latest**; new instance ID | 2 |
| **Suspend process definition** | One call stops new AND (optionally) running instances of a definition — the real "bad deploy" brake (replaces bulk instance-suspend) | 3 |
| **Terminate / delete instance** | Irreversible; runtime state destroyed; **cascade to call-activity children enumerated in the confirm** | 3 |
| **Delete dead-letter job** | ⚠ Orphans the execution permanently (only rescue afterwards: change-state). ADMIN-only, explicit warning | 3 |
| **Migrate instance** *(v2)* | Move instance to another definition version; **server-side validate** (a real dry-run) before apply; single first, batch later | 3 |

Explicitly **not offered** (API honesty, §11): timer *reschedule-to-later* (not exposed by
open-source REST — the change-state workaround is documented instead), Temporal-style
reset-to-history-point (impossible on an append-only history), engine-verified change-state
preview (no dry-run exists).

### 5.1 Remediation playbooks — fix once, apply to the class *(v2 headline)*

Real incident fixes are rarely one verb; they are a sequence (edit the poisoned variable,
*then* retry the dead-letter job). The playbook feature turns the audit log from
retrospective compliance into forward-acting automation:

1. The operator repairs **one exemplar instance** interactively; every step is already
   audited with full payloads and old→new values.
2. Once the exemplar reaches a healthy state, the tool offers: *"Replay this N-step fix on
   the other members of this error group?"* — the recorded verb sequence becomes a
   **playbook** bound to the error-class signature.
3. Replay runs through the standard bulk-job machinery (§7): per-item **precondition
   recheck before each step** (is the variable still the bad value? is the job still
   dead-lettered?), `ok/failed/skipped/unknown` per item **per step**, stop-on-first-failure
   per item, cancel, stagger.

**v1 discipline (where the design leadership lives):** replay supports **literal values and
verb sequences only** — the dominant incident shape is one bad config/reference value
poisoning N instances identically. Per-item expressions/parameterization are a later
extension, never a silent default. A playbook is only offered when the exemplar
demonstrably transitioned FAILED → healthy after the recorded sequence (the evidence is
shown in the replay wizard). Guard tier 4 applies (scope enumeration, reason, type the
count); playbooks are stored, named, and re-runnable against future occurrences of the same
error class — reviewable like the audit rows they came from.

No competitor ships this: Camunda bulk-retries an incident group, Conductor reruns with
overrides, Step Functions redrives — all single-verb. Recorded multi-step remediation
applied to an error class is the differentiator.

### 5.2 Sibling diff — "why did this one fail when 9,999 succeeded?" *(v1.x)*

From a failed instance's detail page: **Compare with a sibling** — a completed instance of
the same definition version (auto-suggested: nearest-in-time successful sibling; or pick
any instance). Rendered as three synchronized diffs, all from historic queries (read-only,
cheap):
- **Variables** — value differences at comparable points (start snapshot + final/current).
- **Path** — the activity sequence each instance took, divergence highlighted on the shared
  diagram (the failed one's tokens red, the sibling's path green).
- **Timing** — per-activity duration bars side by side; where the failed one stalled.

The second-most-asked 3am question after "why is it stuck" gets a one-click answer.

## 6. The guard ladder

| Tier | Guard |
|---|---|
| **0** — reversible-ish, single target | No modal. In-flight row state → **outcome toast with an explicit delta statement** ("Job 8123 moved to executable queue; retries reset to 3") + audit link. Never a bare "success". |
| **1** — data mutation, single target | Diff confirm (old → new, name, scope). Reason optional on dev/test, **required on prod**. |
| **2** — flow surgery, single target | Confirm + **required reason** + plan-as-a-sentence + raw REST body preview. |
| **3** — destructive, single target | Modal restates the target **server-fresh** (warns if state changed since the grid snapshot), enumerates cascade victims, environment color band. Required reason. On prod: **typed token = the business key** (target-specific — never a generic "yes"/"DELETE"). Cancel-focused; Enter never submits. |
| **4** — destructive, bulk | Wizard: scope enumeration (count, per-engine split, expandable list; filter-based selections re-resolved at submit with drift shown) → required reason → prod: **type the count** → async tracked job. **Refuse-unscoped**: no destructive bulk without at least one narrowing filter. |

**Synchronous mutations inherit the §7 UNKNOWN discipline — the dual-write rule
(R-SEM-18).** Fail-closed (§9) covers pre-flight; the post-flight leg has its own failure
mode: audit row written `PENDING` → engine call succeeds → the outcome UPDATE to Postgres
fails. The token *did* move. The UI must therefore never render a generic 500 for a
dispatched mutation: it renders **"Action dispatched — outcome verification failed"**, the
audit row stays in outcome `unknown`, and the standard **Verify now** affordance (R-SAFE-09)
applies to single-target actions exactly as to bulk items. Audit row lifecycle is normative:
`PENDING → ok | failed | unknown`; a PENDING row older than `write-ms` + grace is swept to
`unknown` by the startup/periodic reconciler (same sweep as bulk INTERRUPTED, §7). The
guard-ladder error copy distinguishes the three cases: refused pre-flight (nothing
happened), engine rejected (nothing happened, engine's words quoted), dispatched-unverified
(assume it happened until verified).

**Break-glass — normative semantics (R-SAFE-11).** The break-glass account (OPERATIONS §7)
exists for one scenario: the IdP is down during a P1. It bypasses **authentication and the
group→scope mapping only** — the session gets ADMIN-global scope, including protected
instances. It does **not** bypass: the guard ladder (typed tokens, server-fresh
restatement), read-only engine mode (an engine-owner contract, not an emergency lever), the
path whitelist, or audit (fail-closed applies). Under break-glass, a **reason ≥10 chars is
mandatory on EVERY verb including tier 0**, and any `require-second-approval` requirement
is waived (the second approver may be locked out too) — waived approvals are flagged on the
audit row. Every break-glass action carries `breakGlass: true`, appears first in the shift
report, banners every page, and fires the alert channel on login.

Cross-cutting: environment (`dev|test|prod`) drives a consistent color band on badges,
headers, and inside every confirm modal (freeform per-engine colors are demoted to a subtle
accent — color encodes environment, not identity). Disabled actions are greyed **never
hidden**, with a tooltip naming the gate — capability ("needs Flowable ≥ 6.4; billing runs
6.3"), role ("requires ADMIN"), or state ("no dead-letter jobs") — three different next moves
for the operator.

## 7. Bulk operations

- **v1**: grid-selection bulk (intersection of valid actions), cap 200 items — executed as
  a **persisted tracked job from day one** (R-SEM-10; resolves the earlier §7↔ARCH §4
  ambiguity: one machinery, restart-safe), with per-item result report (successful IDs vs
  id→error table, Conductor `BulkResponse` style) and an **aggregate readout** "N of M
  dispatched · ok/failed/skipped/unknown" (R-SEM-11).
- **Job state machine** (normative): job `PENDING → RUNNING → (COMPLETED | CANCELLED |
  INTERRUPTED)`; item `pending → dispatched → (ok | failed | skipped | skipped (protected) |
  unknown | not_run)`. On BFF startup a reconciliation sweep marks RUNNING → INTERRUPTED;
  the item in flight at crash becomes `unknown` (never re-fired); undispatched become
  `not_run`. No automatic resume, ever: the operations drawer banners INTERRUPTED jobs on
  next login and offers "continue as new job" pre-scoped to `not_run` + `failed`.
- **Circuit-open mid-job** (R-SEM-11): dispatch to a tripped engine **pauses** — undispatched
  items stay `pending`, never burned as failures; a breaker fast-fail on an already-dispatched
  item is `failed` (clean rejection); `unknown` stays reserved for true ambiguity (timeout
  per registry `write-ms`).
- **v1.x**: **select-all-matching-filter** — the BFF re-executes the search plan at
  execution time (never the stale grid), records the resolved ID list in the audit record
  BEFORE acting, then per-item fan-out as a **server-side async job**: persisted in Postgres,
  live progress via SSE, **cancel** (stops dispatching), per-engine concurrency cap +
  optional stagger (a simultaneous 300-job DLQ move can DDoS the async executor), per-item
  **precondition recheck** ("still in the DLQ?").
- Outcome classes: `ok | failed | skipped (already resolved) | skipped (protected) |
  unknown | not_run` — a timed-out mutation is UNKNOWN, never auto-retried. Every UNKNOWN
  offers **"Verify now"** (R-SAFE-09): the BFF re-runs the verb's precondition predicate and
  reclassifies to `ok / still-pending / needs L3` with evidence; unresolved UNKNOWNs persist
  in the drawer and the shift report. Concurrent-operator rule (R-SEM-09): an engine 404/409
  on the target maps to `skipped (already resolved)`, enriched from the audit log with
  "handled by <user> at <ts>"; submitting a bulk that overlaps a RUNNING job (same signature
  or ID overlap) requires an explicit run-anyway naming the other job and its owner.
- A **persistent operations drawer** survives navigation; reports survive browser refresh
  (persisted server-side); failed items re-selectable for a targeted second pass. Bulk over
  a partial result set (engine down / truncation) is **blocked until explicitly
  acknowledged** ("billing-prod excluded — proceed anyway?").
- No cross-engine transactionality — stated in the UI, not just the docs.

## 8. Search & filtering (full list)

| Filter | Detail |
|---|---|
| Omnibox | any ID kind or business key, auto-resolved across engines (§4) |
| Target engines | multi-select; unreachable engines shown, labeled |
| Status | flag predicates: Active, Completed, Suspended, **Failed**, **Failing (retries left)** — with facet counts |
| Process definition | key or name; **definition version**; deployment time |
| Timeframe | `startedAfter/Before` AND **failure time** (DLQ job `createTime`) — "failed in the last hour" must not depend on when the instance started |
| Business data | `businessKey` (exact + `businessKeyLike`), variables (name/operator/value, `like` supported). ⚠ Variable-value search hits typically-unindexed engine tables (`ACT_RU/HI_VARINST`): the form warns and nudges "narrow by definition"; a per-engine flag can require it |
| Current activity | activity id/name contains |
| Error text | substring over exception snippets (BFF-side) |
| Tenant | when any engine is multi-tenant |

Combination rule unchanged: **AND between categories, OR within** — made visible by the
compiled-criteria echo. Saved views: curated system views (v1) + user-named views
(localStorage v1.x → shared server-side v2). Hierarchy-aware: businessKey search finds the
tree, not just the root.

## 9. Audit, notes & handover

- Append-only audit — normative schema (R-AUD-02): `audit_entry(id, correlationId,
  bulkJobId FK, user, ts, engineId, tenantId, instanceId, action, reason ≥10 chars,
  ticketId, payload jsonb — per-verb versioned schemas, e.g. edit-variable {name, scope,
  oldValue, newValue, valueType} — httpStatus, outcome, responseSnippet ≤32 KiB
  truncated+flagged, breakGlass, approvedBy)`; indexes `(engineId, instanceId, ts)` + `(ts)`;
  monthly range partitions. One row per bulk item + one for the envelope. Written whether
  the engine call succeeded or failed — and **fail-closed** (R-AUD-01): if the audit INSERT
  fails, a tier ≥1 mutation is not issued. Tier-1 variable edits are compare-and-set
  (R-SEM-09): the request carries `expectedOldValue`; mismatch ⇒ 409 + fresh re-render.
- **Data protection** (R-AUD-03): variable payloads in audit rows and notes are potentially
  personal data. Retention default 400 days with an audited purge; per-engine `audit-payload:
  full | redacted | metadata-only`; secret-name denylist → `«redacted»`; payload bodies
  role-gated OPERATOR+; DB role INSERT/SELECT-only + hash-chain tamper evidence; erasure =
  skeleton-preserving redaction. Details: OPERATIONS.md §6. Never logs secrets.
- Surfaced four ways: **per-instance tab** (what did the last shift try), **global
  operations log** page (filterable by actor/action/engine/ticketId/time), **recent
  operations** on the triage landing, and the **shift report** (R-AUD-05): a "my activity,
  this shift" preset + one-click plain-text export, UNKNOWN outcomes grouped first under
  NEEDS VERIFICATION.
- **Notes** per composite ID (BFF-owned; author + timestamp; "has notes" grid marker).
  Copy-for-ticket includes the latest note + a one-line actions-taken summary (R-AUD-06);
  a group-level copy-for-ticket exists on Stage 0 (v1.x). Reasons carry an optional
  (per-deployment requirable) **ticketId**, regex-validated and linkified via
  `ticket-url-template` (R-AUD-07); bulk completion can fire a signed webhook (v1.x).
- **Watchlist** (R-BAU-05, v1.x): per-user pinned composite IDs as a landing panel with
  changed-since-last-seen indication; with the start-of-shift delta ("changes since
  <marker>") and future maintenance snapshots, it shares ONE count-snapshot store.
- IBM BAW ships no admin-action audit trail at all — this is a headline differentiator.
- **Attribution tradeoff (explicit)**: because the BFF calls engines with a shared service
  account, Flowable's own history tables attribute every mutation to that account — **the
  BFF audit log is the only authoritative source of WHO acted**. Investigations must start
  here, not in `ACT_HI_*`. Where the engine deployment is ours (flap), the optional
  `X-Forwarded-User` header + engine-side interceptor restores native attribution
  (ARCHITECTURE §6); it is a per-engine bonus, never relied upon.

## 10. Tech stack (decided — ADR-001, see [DESIGN-REVIEW.md](DESIGN-REVIEW.md))

Confirmed after an architect review and a Claude-expert review (most implementation is done
by an AI coding agent): the two hard problems live in different halves, and each half gets
the strongest available tool. **Rejected:** all-Spring Thymeleaf/htmx (the UI is SPA-shaped:
grid selection driving wizards, diagram↔tab sync, URL state, operations drawer — and
server-rendered templates deny the agent its Playwright feedback loop); full-stack
TypeScript/NestJS (runner-up: loses Spring Security's OIDC/session/RBAC maturity, team depth,
and would rewrite working M1/M2 code for no capability gain); Go/FastAPI/Kotlin (no axis won).

- **Backend:** **Java 21 (LTS) / Spring Boot 3.5.x** (bump from 3.3.x before M3) / Maven
  (+ enforcer, `mvnw`). `RestClient` per engine over `JdkClientHttpRequestFactory` with
  registry timeouts — blocking-by-design, **no WebFlux**. **Resilience4j** circuit breaker +
  bulkhead per engine around every outbound call (§2 do-no-harm); **Caffeine** short-lived
  cache for the triage aggregations. **Virtual threads**
  (`spring.threads.virtual.enabled=true`; virtual-thread-per-task executor + per-engine
  `Semaphore` for fan-out and bulk dispatch; no preview APIs). Spring Data JPA + **Flyway**
  + Postgres 16 (audit repository insert-only). Spring Security **dual profile**: form/basic
  (dev) / OIDC via `oauth2-client` (prod), one `GrantedAuthoritiesMapper` claim→role,
  `@PreAuthorize` mirroring the guard ladder, cookie CSRF for the SPA. SSE via `SseEmitter`.
- **Contract:** springdoc-openapi from the Java record DTOs (single source of truth) →
  **`openapi-typescript`** generated types + `openapi-fetch` client in the frontend,
  committed; CI regenerates and **fails on diff** — cross-language drift is a build failure.
- **Frontend:** React 18 + TypeScript `strict` + Vite (Node 22 LTS, npm). **TanStack Query
  v5** — polling drives all v1 liveness (health strip, drawer); **SSE arrives in v1.x with
  tracked bulk** (R-SEM-14 resolves the earlier v1/v1.x ambiguity; lifecycle contract —
  heartbeat, session binding, id-only events, documented event catalog — lands with it).
  React Router v7 + typed search-param codec. Zustand only when the operations drawer
  lands. **AG Grid Community ONLY** (ADR-002 / R-GOV-05): the v1 grid is designed against
  Community features (selection count = custom footer; ID copy = copy buttons; filtering in
  the search rail — no Enterprise status bar/set filters/range selection/context menu); CI
  fails on an `ag-grid-enterprise` import; Enterprise, if ever proposed, is a costed
  decision. bpmn-js (`NavigatedViewer` at M3): **the bpmn.io watermark must not be removed —
  license term.** All user-facing strings live in one message catalog per side; all
  date/number formatting through one shared formatter (R-UXQ-07); semantic color tokens
  from day one (dark theme lands v1.x, R-UXQ-08).
- **Testing:** JUnit 5/AssertJ/Mockito · **WireMock** for engine stubs (timeouts, 5xx,
  truncated DLQ, version cliffs — the load-bearing layer) · Testcontainers Postgres ·
  `@WebMvcTest` + `spring-security-test` for per-endpoint RBAC/guard tiers · Vitest + RTL +
  MSW (reusing OpenAPI types) · **Playwright** E2E against the full compose stack (smoke in
  PR CI, matrix nightly) — Playwright is also the agent's autonomous visual feedback loop.
- **Test data (staged, normative):** three stages, each with hard rules —
  **S1 synthetic** (fixture builders + stub payloads; allowed only for pure logic and
  client-fault shapes incl. scale that must never be seeded for real; forbidden for
  join/status/paging semantics), **S2 engine-generated** (all engine semantics proven
  against data generated strictly over REST on the dockerized compose profiles; captured
  corpora committed with the engine image tag, re-captured on bump), **S3 production
  read-only** (prod engines are observation-only validation targets — never seeded, never
  mutated, never load-tested; prod payloads enter the repo only via the sanitized
  golden-corpus pipeline). Rules: TEST-STRATEGY §10; scenario + fixture catalog:
  [TEST-SCENARIOS.md](TEST-SCENARIOS.md) (`TS-*`/`FIX-*` IDs, per-scenario stage tags).
- **Lint/format as CI hard failures** (essential when an agent writes most code): Spotless
  (palantir-java-format) · ESLint strict-type-checked + Prettier.
- **Packaging:** one image, multi-stage (Vite build → jar `static/` → `temurin:21-jre`
  layered jar). One origin, one session. No GraalVM native.
- **Agent ergonomics** (from the Claude-expert review): repo `CLAUDE.md` with **scoped**
  build/test commands (`mvn -o test -Dtest=…`, never the full suite in the loop), pinned
  version floors (Boot ≥3.5, React ≥18, TS ≥5), and the `.claude/skills/*` doctrine set.
- **Dev harness:** dockerized `flowable-rest` engines in **three compose profiles** —
  current 6.x, one pre-6.4/6.5 image (capability cliffs: change-state 6.4+, migration ~6.5+,
  external-worker jobs 6.8+, `scopeType` ~6.8+), and a **Flowable 7.x profile** (the flap
  companion app embeds Flowable 7 — see ARCHITECTURE §6). Cliffs are exercised in CI, not
  discovered in prod. The 7.x profile exists for more than capability flags: Flowable 7 is
  a Spring Boot 3 / Jakarta baseline shift, and **standard Spring error responses changed
  shape** — the BFF's error-handling interceptors, and specifically the exception-snippet
  parsing that feeds the Stage 0 error-class grouping, are validated against 7.x error JSON
  in CI so the normalizer never silently degrades to "unparseable" groups.

## 10a. UX quality standards (R-UXQ-01…07 — CI-enforced where automatable)

- **Accessibility — WCAG 2.1 AA.** Color never carries meaning alone: environment bands
  co-render the literal `PROD/TEST/DEV` token + distinct border styles; status chips keep
  text at every density; diagram markers use shape+glyph (dead-letter = ⚠ badge), the
  sibling diff differentiates by stroke style + endpoint glyphs, not red/green hue.
  Contrast 4.5:1 text / 3:1 non-text. Every diagram-borne fact has a keyboard-focusable
  textual twin (activity summary list with synced selection); canvas is `role="img"` with a
  generated summary. AG Grid: full keyboard nav; row actions never hover-only. Modals: focus
  trapped, cancel-focused, Esc per tier, focus returns to invoker or nearest survivor.
  **axe runs inside the Playwright suite as a CI hard failure.**
- **Time.** Absolute timestamp + explicit offset + relative age, everywhere; user-selected
  display TZ (default browser-local, one-click UTC); "next retry" adds a countdown.
  Machine-facing text — copy-for-ticket, cURL, audit, exports — is **always UTC ISO-8601**.
- **Zero states** — five distinct, non-interchangeable: no-engines (setup guide),
  all-engines-down (full error state, never calm zero-counts), true zero (positive statement
  + criteria echo), **zero-under-partial-coverage** ("0 shown — billing-prod unreachable;
  this is NOT a confirmed zero"), zero failures (explicit positive; doubles as first-run
  orientation with a dismissible omnibox hint).
- **Message style.** Every message = [what happened] + [why/which gate] + [next move];
  names the concrete object; engine-origin text quoted and attributed, visually distinct
  from BFF prose; no bare Success/Failed; no HTTP code without a plain-language line;
  no humor, no apology theater — the 3am rule.
- **Notification budget.** Modals: user-initiated only. Banners: ambient degradation, one
  per scope, update-in-place, never stacked. Toasts: own-action outcomes only, max 3,
  overflow collapses to the drawer, never the sole record. Passive surfaces update silently,
  never steal focus, never mutate a grid with selected rows. Bulk jobs toast exactly twice.
- **Language:** English-only, deliberately (see §11).

## 11. Non-goals & explicitly rejected (with reasons)

- No BPMN editing/deployment tooling; no engine-DB access (unchanged).
- No cross-engine transactional bulk (unchanged) and no fake global pagination (unchanged).
- **No query language** — Flowable's query REST has no OR-across-fields/boolean nesting; a
  grammar would either lie or duplicate the form. The compiled-criteria echo + copy-as-cURL
  delivers the teaching/scripting value without owning a parser. Scope of the rejection
  (R-L3-04): it forbids a query *language*, not the engine's own query surface — the v1.x
  **forensic passthrough** forwards a raw request body to an enumerated set of read-only
  historic-query endpoints (ADMIN, audited, size-capped, rate-limited, breaker-wrapped).
  This honors the whitelist invariant: the whitelist constrains reachable state transitions
  (none, read-only) and RBAC constrains who; what it cannot constrain is query cost — so the
  mitigation is timeout + rate limit + the §8 cost warning, not body inspection.
- **English-only UI, deliberately** (R-UXQ-07) — the operator audience works in English and
  engine error text is English regardless. Hedges that stay mandatory: one string catalog
  per side, one shared formatter. Full localization is COULD-v2, demand-driven.
- **No Temporal-style reset**, no timer reschedule, no engine-verified modification preview,
  no per-retry Gantt gaps, no async-executor thread internals — the REST API cannot honestly
  provide them; nearest honest equivalents are documented per verb.
- **CMMN out of scope for v1** (jobs filtered from every join; `scopeType` carried in DTOs
  so v2 case support is additive).
- No WebSockets/MQTT; no per-component polling.

## 12. Release train (re-cut per R-GOV-07; every item cites the register IDs it discharges)

- **v1 (must ship, gated by §13):** corrected status join + RETRYING tier + hierarchy
  roll-up + explain-status evidence; triage landing incl. acknowledge + leak views +
  badged counts; omnibox; URL state + deep links; full-page detail (vitals, read-only
  diagram, tabs, raw-JSON links); **verbs: tiers 0–1 + suspend/activate +
  suspend-definition + terminate/delete + deadletter-delete** with reversibility badges +
  plain-language labels + tier-0 friction floor; RESPONDER role + per-verb grants +
  protected instances + read-only engine mode; grid-selection bulk as a persisted tracked
  job with the full outcome-class set; audit (normative schema, fail-closed, retention/PII
  controls, correlationId, ticketId column, approval-schema hooks) + notes + shift report +
  audit CSV export; break-glass; Postgres; dual auth + session policy; disabled-with-reason;
  UX quality standards (§10a); OPERATIONS.md MUSTs; TEST-STRATEGY gates.
- **v1.1 (first fast-follow, entry criterion: ≥N audited pilot incidents unresolvable with
  tier 0–1):** change-state / rerun-from-activity / restart-as-new (the former M5), with
  their guardrails.
- **v1.x (ranked; max one in flight alongside pilot support; each names its KPI/trigger):**
  1. error-class bulk-retry from the landing group (pre-committed pilot gap);
  2. select-all-matching-filter bulk + SSE progress + operations drawer + drain;
  3. second-person approval / proposal inbox (hooks already in v1 schema);
  4. error-class annotations + endorsed verbs; group-level copy-for-ticket;
  5. named saved views; column chooser + density + dark theme;
  6. sibling diff (§5.2 — trigger: ≥5 "why did this one fail" investigations/month, probed
     via a stub affordance); timeline polish;
  7. task reassign + person-centric task search (ship together, never apart);
  8. watchlist + start-of-shift delta (one snapshot store); suspend reason/review-by;
     business summary; timers-due-in-window; ops reporting; support bundle; forensic
     passthrough; stacktrace ergonomics; engine advisories; secret-rotation file refs;
     clock-skew badges; capability invalidation; CSV export; print styles; webhook.
- **v2 (demand-driven, triggers stated):** **remediation playbooks (§5.1 — build trigger
  R-GOV-08)**, migration (single w/ validate → batch wizard + typed "MIGRATE"), definition
  version comparison, CMMN, registry CRUD (with the R-OPS-13 SSRF constraints), shared
  server-side views, k-way-merge paging, maintenance snapshots + volume trends, training
  mode, capability overrides.

## 13. Success metrics & v1 release gate (R-GOV-01/02)

- **KPIs (computed from BFF data; instrumented in the M4 audit schema):** weekly active
  operators per role; incident sessions; **time-to-first-fix** (first FAILED-filtered
  search/triage click → first tier-0/1 verb with outcome `ok` on the same instance); % of
  dead-letter jobs resolved via the tool; deep-link opens from tickets. MTTR baseline is
  captured from the ticket system for ≥2 incident classes **before** pilot start.
- **Pilot exit target (testable):** ≥60% of workflow P1/P2 tickets in the pilot month show
  tool audit activity AND median time-to-first-fix < 15 min.
- **v1 release gate:** security sign-off by a named function (whitelist, secrets,
  guard-bypass attempts — TEST-STRATEGY §5); performance budget green in CI (R-NFR-02
  against the reference dataset); operator quick-start + audit-attribution onboarding doc
  delivered; named operating owner + restart/recovery expectations accepted by the support
  team lead; data-classification one-pager approved; zero open Sev1/Sev2.

## Change log
- **v3.2** — Operator feedback round 4: dual-write rule (§6 — sync mutations inherit UNKNOWN;
  audit lifecycle PENDING→ok|failed|unknown; "dispatched — outcome verification failed",
  never a bare 500); break-glass normative semantics (§6 — what it bypasses and what it
  never does); group→scope mapping = hot-reloaded mounted file with ≤5 min grant SLA (§2);
  hierarchy breadth cap 50/node (§4); audit-integrity CI suite vs real Postgres
  (TEST-STRATEGY §11, R-TEST-10). Register R-SEM-18/19, R-SAFE-11/12, R-TEST-10.
- **v3.1** — §10: staged test-data pipeline made normative (S1 synthetic / S2 engine-
  generated on the compose profiles / S3 production read-only observation). New companion
  doc: TEST-SCENARIOS.md (scenario catalog `TS-*` + fixture catalog `FIX-*`, mapped to the
  SPEC §3 flag matrix and the register); TEST-STRATEGY gains §10 (generation rules) and
  points its §6 fixture catalog at TEST-SCENARIOS §1.
- **v3.0** — 14-seat review board (PO, BA, lead dev, architect, DevOps, test manager,
  embedded tester, UX expert, 2 usability testers, support team lead, support engineer, L3,
  L2 — see DESIGN-REVIEW round 3). New: §0 glossary + FAILING→RETRYING display rename;
  RESPONDER role, per-verb grants, protected instances, read-only engine mode, break-glass,
  session/access governance; quantified service levels + operating envelope; normative
  signature contract; omnibox semantics; Stage 0 acknowledge/leak-views/badged counts;
  explain-status evidence; §5.0 reversibility + plain-language labels + tier-0 friction
  floor; bulk job state machine + circuit-open pause + Verify-now; normative audit schema,
  fail-closed audit, retention/PII, correlationId, ticketId, shift report; §10a UX quality
  standards; §12 re-cut (flow surgery → v1.1, ranked v1.x); §13 KPIs + release gate. New
  companion docs: REQUIREMENTS-REGISTER.md (all accepted findings, ID'd + prioritized),
  TEST-STRATEGY.md, OPERATIONS.md.
- **v2.4** — §2: engine-side fencing bullet (embedded-engine target architecture), explicit
  full-base-URL registry guarantee, identity tradeoff elevated to "audit golden master"
  (UI note + onboarding); §10: 7.x compose profile additionally validates error-JSON shape
  for the triage exception parser; ARCH §6: proxy/ingress network-scoping caveats.
- **v2.3** — Do-no-harm hardening (operator feedback): Resilience4j circuit breakers +
  bulkheads per engine; 15–30s triage-aggregation cache; variable byte-size caps (Variables
  tab + sibling diff); unindexed variable-search warning; identity/attribution tradeoff made
  explicit in §2/§9 with optional `X-Forwarded-User`; tenant-scoped RBAC
  (role × engine × tenant); external telemetry URL templates; tab-aware deep links;
  hierarchy max-depth limit.
- **v2.2** — §10 tech stack decided (ADR-001: Java 21/Spring Boot 3.5 + React/TS confirmed;
  OpenAPI-generated contract; virtual threads; test/lint/packaging pins; agent-ergonomics
  guardrails; Flowable 7.x compose profile). ARCHITECTURE gains §6 (inspecting
  embedded-engine apps / flap).
- **v2.1** — Added §5.1 remediation playbooks (fix-once-apply-to-class, v2 headline) and
  §5.2 sibling diff (v1.x); release train updated accordingly.
- **v2.0** — Full refinement after IBM BAW + competitor research and the four-seat design
  review: status flags + FAILING tier + subprocess roll-up + corrected DLQ paging; three-stage
  IA with triage landing and full-page detail; omnibox; failure-time filtering; error-class
  grouping; verb catalog with preserved-semantics labels; guard ladder + reasons + typed
  target-specific tokens; server-side bulk jobs with honest outcome classes; audit surfaced +
  notes; event-subscription visibility; four job lanes; multi-tenancy; CMMN filtering;
  Postgres + dual auth; rejected-features register. See [DESIGN-REVIEW.md](DESIGN-REVIEW.md).
- **v1.0** — Initial agreed spec.
