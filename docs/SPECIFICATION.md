# 📄 SPECIFICATION — Flowable Multi-Instance Process Inspector

Status: **v3.5** · Owner: workflow platform team ·
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
  The lane a job sits in IS the diagnosis. A **fifth lane — external-worker jobs** (v1.x #7,
  read-only, Flowable 6.8+ only) sits alongside them, capability-gated: it renders only on a
  supporting engine (`EngineCapabilities.externalWorkerJobs`) and its crux column is the
  **lock owner** — who holds the task and until when. Sourced from the External Worker REST
  API, not the management queues; the active count is surfaced in the vitals diagnostic summary.
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
  notes, bulk-job state + per-item reports, the job-lane snapshot time-series (v2/M4),
  per-user saved views + recent searches (v2/M4), registry CRUD (v2).
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
  attribute natively — optional, per-engine (`forward-user`, **off by default**), never relied
  upon. The forwarded value is the **same actor the audit row records** (break-glass namespaced
  `break-glass-<user>`); it is trustworthy only over an authenticated/isolated BFF→engine channel,
  and the BFF scrubs any client-supplied inbound `X-Forwarded-User` so it can never be reflected.
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
- **Protected instances** (R-SAFE-05) — L3+ may mark a composite ID (#165) or a whole
  definition key (#172) `protected` (reason required; setting/removing is tier-3, audited).
  Below the configured role floor, ALL verbs are disabled-with-reason ("protected — L3 action
  required"); protection badge on rows, vitals header, and inside every confirm; bulk and
  group operations auto-exclude protected members, reported as `skipped (protected)`.
  **Enforcement boundary (#172, extended #184):** definition-key protection is checked
  wherever the target's definition key is already resolved without an extra engine
  round-trip — the two definition-scoped verbs (suspend/activate-definition), instance
  migration, every job/instance/task-targeting `CorrectiveActionService` verb (retry,
  terminate, task actions…, each populating the key from a fetch it already makes),
  `FlowSurgeryService`'s change-state and restart-as-new, and bulk's per-item dispatch
  (`BulkJobService` resolves each target's definition key server-side via a batched
  runtime query at submit time — never trusting a client-supplied key — and settles
  protected items as `skipped_protected` before any are dispatched). Still open:
  `EDIT_VARIABLE`/`UNSTICK_EVENT` (execution-scoped fetches that don't carry a definition
  ID) and CMMN targets (a different protection concept entirely, not just a missing
  field) — extending either needs a genuinely extra engine round-trip or new design, not
  wiring; no follow-up filed until a concrete need surfaces.
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
auto)"). **Terminated ≠ completed (#118/#105):** Flowable ends a normally-completed AND a
terminated/deleted instance the same way (an `endTime`), so the derived status ENUM stays
`COMPLETED` for both (the 5-value set + the search facets/counts must not churn). But the
instance-detail chip must not lie — when the historic row carries a termination signal (a
`deleteReason`, or a `state` other than `COMPLETED`: `EXTERNALLY_/INTERNALLY_TERMINATED`,
`DELETED`), the detail vitals carry a `terminationReason` and the chip DISPLAYS **`TERMINATED`**
(reason in its tooltip, dark fill distinct in lightness not hue) instead of `COMPLETED`. This is
a display-only honesty override on the detail header, not a sixth status. The chip also carries
**secondary badges** for collisions ("SUSPENDED · has dead-letter jobs") and
subprocess roll-up ("FAILED — in subprocess *chargePayment*", which **deep-links to the
failing child's Errors & Jobs tab** — the retry lives on the child; the parent must never be
a dead end, R-UXQ-11). Search filters operate on flag predicates. CMMN-scoped jobs (null
`processInstanceId`) are filtered out of every join; row DTOs carry `scopeType` from day one
for future CMMN support. The Stage-0 triage instead **counts** the dead-letters it excludes
as `outOfScopeDeadletters` (a co-deployed CMMN engine shares the job tables, so its failing
jobs surface in the process-api dead-letter lane as null-`processInstanceId` orphans) — so
the health strip's dead-letter count reconciles with FAILED rather than silently exceeding
it; the count is unknown (null, shown as nothing) on engines that cannot discriminate scope
(pre-6.8, no `scopeType` capability — never a misleading zero), and is shown as a lower bound
(`≥N`) when the dead-letter scan hit its cap. The note is deliberately job-scoped, lower-bound
phrasing ("≥N CMMN jobs not triaged here") — never an exact "N of M" that invites unsound
subtraction against the instance-scoped FAILED chip. The note is **drillable** (Case Inspector
Phase 1): "View jobs" opens a read-only **scope-typed view** of the co-deployed CMMN engine.
It leads with the CMMN case **lane counts** — `ACTIVE / FAILED / COMPLETED / TERMINATED` (there
is **no SUSPENDED lane**: a CMMN case cannot be suspended) — count-only (`size=1`)
historic-case-instance queries per `?state=`, each shown as `—` (unknown) rather than a
misleading `0` if its query degrades; `FAILED` is the distinct cases carrying a dead-letter job.
Below the lanes the **FAILED lane drills** into those CMMN dead-letters — enumerated from the
CMMN-api dead-letter projection (the shared-table rows carrying a case attribution),
bounded/paged/`truncated@N` like every DLQ scan — showing the failing plan-item element,
exception snippet, case instance id, and retries. The whole drawer is gated 6.8+ (a pre-6.8
engine is refused server-side), carries the same `≥` lower-bound honesty as the count on the
FAILED lane, and offers **no corrective action** (CMMN actions are a later phase). Multi-tenant
engines thread `tenantId` through **every** query leg.

**The derivation is falsifiable** (R-L3-01): every status chip offers **"Explain this
status"** — the per-leg evidence (plan shape chosen and why; each engine call's URL, body,
status, duration, `asOf`, cache-hit vs live, truncation; per-flag provenance "hasFailingJobs
⇐ timer-jobs leg, job 8123"). Evidence is re-derived on demand and labeled with both
timestamps — never pretending the original bytes were retained. Served over
`GET /api/instances/{engineId}/{instanceId}/explain-status` (VIEWER, breaker-wrapped) by
re-running the SAME single-instance derivation the vitals chip uses (so the evidence can
never disagree with the chip it explains), the calls captured by a thread-local recorder for
the span of that one derivation. The `failedInSubprocess` finding names the failing
call-activity child and its dead-letter job and **deep-links to that child's Errors & Jobs** —
so a "grid parent ACTIVE vs detail FAILED in subprocess" contradiction is explained on the
chip itself.

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
  target, and the resulting filter state is echoed before commit. The group-total click
  carries the signature hash into the Stage-1 search (`signature` URL param → the search
  plan's signature filter), so the landing's whole-class scope survives the drill and the
  filter-scope bulk (§7 v1.x #2) becomes the one-action whole-class remediation path. Each per-version count
  also offers **bulk-retry-the-group** (§7 v1.x #1) — demoted/warned when the group's
  annotation implies a data fix first (R-SEM-13; the demotion activates with group
  annotations, R-BAU-01 — until then the offer is un-demoted). This is the triage
  centerpiece.
- **Acknowledge** (R-BAU-01): OPERATOR+ (on EVERY engine the group is failing on) can
  acknowledge a group — who + required reason ≥10 + optional ticketId + optional expiry,
  persisted keyed signature × engine × definition KEY with the member-count /
  max-failing-version baselines resolved server-side, audited fail-closed as a config
  event; an un-acknowledge exists under the same rails (reason included). Acknowledging
  mutes the CARD only — engine state is untouched (never the Suspend workaround).
  Acknowledged groups collapse into a labeled "Acknowledged (N)" section — never hidden —
  and **auto-resurface** when the member count grows PAST the acknowledged baseline by a
  threshold (default +20%, `inspector.triage.ack-resurface-threshold-pct`), when a new
  definition version (or engine × definition slice) starts failing, or when the expiry
  passes — badged "GREW SINCE ACK: +45" / "NEW VERSION SINCE ACK" / "ACK EXPIRED". Ack
  state joins the dashboard at render time (the aggregation cache never carries it); a
  normalizer bump orphans old-generation acks ("needs re-binding", never silent).
  Without this the landing rots into alarm fatigue within weeks.
- **Annotations** (R-BAU-03, v1.x): OPERATOR+ may attach per-signature guidance (≤200 chars
  + runbook URL + optionally one **endorsed verb with conditions** — "Retry, but only after
  15:00"). Rendered on the group card and every member's why-stuck strip; the endorsed verb
  is the highlighted action, others demoted; author/updated-at/expiry; audited.
- **Status counts** per engine × status. ACTIVE/SUSPENDED/COMPLETED come from query totals
  (no row fetch); FAILED and RETRYING are **synthesized** — Flowable has no such instance
  states (§3) — as distinct-instance counts harvested from the same capped failure-lane
  scans that feed the error groups (FAILED = instances holding ≥1 dead-letter job; RETRYING
  = instances with withException jobs and none dead-lettered; FAILED wins a collision).
  Statuses collide by doctrine: a FAILED instance is still inside the ACTIVE total — the
  chips are flag tiers, not a partition. **All Stage 0 counts carry the same
  truncation/lower-bound badges as the grid** (R-SEM-12) — the first number an operator
  anchors on gets the same honesty guarantee as the last.
- **Leak views** (R-BAU-02): curated views *Active · started > 30 days ago*, *Active ·
  started > 90 days ago*, *Suspended · started > 7 days ago*, grouped per definition
  ("vacationRequest: 212 > 30d") — the slow leaks that never enter a failure lane. Age is
  `now − startTime` (the `startedBefore` predicate) for every window; each per-definition
  count is a count-only Stage-0 query (never the grid-search plan) and carries the same
  truncation/lower-bound badge as the grid (R-SEM-12). Each count is a deep link that
  replays the exact Stage-1 search it was measured against (URL primacy). Curated-view
  honesty rule (R-SEM-05): **no system view may ship whose predicate the REST API cannot
  evaluate faithfully** — Flowable records no suspension timestamp, so the suspended view
  is defined against **start time** ("currently suspended AND started > 7d ago", the same
  honest scoping as the *Suspended > 24h (by start time)* saved view) and its label says
  exactly that, never implying time-since-suspension.
- **Alarm thresholds** (R-NFR-04, per-engine overridable): oldest executable job >5 min
  warn / >15 min crit; overdue timer = past due >60 s, any = warn, >100 = crit; probe 30 s
  with 2-fail/1-success flap damping.
- **Recent operations** — tail of the audit log.
- **Saved views** — curated system views ship with the product: *Failed (all engines)*,
  *Failed in the last hour* (by failure time, never instance start), *Suspended > 24h (by
  start time)* (R-SEM-05: no suspension timestamp exists, so the predicate is `startedBefore`
  and the name says so), *Started in the last hour*. A view is a named URL search string —
  clicking replays the exact Stage 1 state; relative windows materialize minute-floored at
  render. User-named views render beside them (saved from the Stage 1 rail, same-name
  replace, deletable here). **Recent searches**: the last 10 uniquely-parameterized searches
  that executed successfully, newest first, with a generated criteria label — shown here and
  in the Stage 1 zero state. Both persist **per user in the BFF** (v2/M4, `saved_view` /
  `recent_search`, keyed to the authenticated user so they follow the user across browsers); a
  one-time client backfill migrates any pre-v2 localStorage entries on first authenticated load.
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
  **Collapsible** via a header toggle (state persisted): a small process leaves the fixed-
  height canvas mostly empty, so folding it hands the reclaimed height to the tabs below; a
  tab's "show on diagram" auto-unfolds it.
- **Tabs**: Variables · Errors & Jobs · Tasks · Hierarchy · Timeline · Audit & Notes —
  each lazy-loaded (IBM's slow-detail lesson).
  - **Variables** — a **typed variable ledger, never a raw JSON dump** (the Flowable
    Control anti-pattern; R-UXQ-13). Rows: name · plain-language type chip (text / number /
    yes-no / date / structured / empty / Java object — the engine term lives in the
    glossary tooltip) · typed value preview · scope · last-modified. Grouping: **"Case data
    (process scope)"** open by default; execution-local variables grouped per execution
    node ("Step-local: *Validate line item*, instance 3 of 12" — multi-instance loop
    variables live there), auto-expanded when navigated in from the diagram selection; a
    local variable shadowing a process-scope name is badged *"overrides case-level value"*.
    Per-type rendering: dates per R-UXQ-03; null explicit (*"(no value / null)"* — never
    blank); booleans as words (*"No (false)"* — never a toggle glyph); structured/json as a
    summary (*"object · 14 fields · 2.1 KiB"*) expanding to a **lazy, virtualized read-only
    tree** — no main-thread parse of multi-MiB documents. Editing per **§4a**. Serializable
    Java objects read-only with an explaining tooltip **plus a "what to do instead" path**
    (copy value, copy-for-ticket pre-filled for the owning dev team) — the row must read
    intentionally locked, never broken (REST cannot round-trip serializables safely).
    **Size safeguards**: variable values are capped at a byte
    threshold in list responses (truncated preview + "load full value" on demand) — a huge
    JSON payload or base64 blob must crash neither the browser nor the engine; the same cap
    applies to the sibling diff (§5.2), which diffs the truncated projections and flags
    "values differ beyond preview" rather than fetching two blobs. Raw-JSON download per
    row and per tab stays (R-L3-03): **raw text is the escape hatch, never the
    presentation**.
  - **Errors & Jobs** — Flowable's **four job lanes kept distinct** (executable / timer /
    suspended / dead-letter): the lane IS the diagnosis. Per-job: retries, create time,
    exception (stacktrace fetched on expand), and the verbs (§5).
  - **Tasks** — the instance's user tasks, **completed AND open in one ledger**: task name
    + activity id, derived state (ACTIVE / SUSPENDED / COMPLETED — suspension read live,
    never trusted from history), live assignee/owner, created / due / completed times with
    duration. Historic-first (an open task is a historic row without an end time) unioned
    with the runtime rows, so an engine with dialed-down task history still shows its open
    tasks; list truncation carries the engine-exact total. Complete/reassign verbs per §5.
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
  - **Timeline** — historic activity instances as duration bars. A call-activity bar nests
    the called instance's own activities as a **sub-lane**, recursed under the same caps as
    the Hierarchy tab (depth 10, breadth 50/node, global node budget; `calledProcessInstanceId`
    cycle-guarded) — a node whose sub-lane was truncated by any cap carries a single truncation
    flag so the UI can render the lower-bound warning. A failing/unfinished node is annotated
    with its **live job state** (`FAILED` = a dead-letter job is parked on it, `RETRYING` = a
    failing job with retries remaining), joined from the runtime job lanes. Because a
    dead-lettered **async** activity's history row is rolled back with the failed transaction,
    that node is **synthesized from the live job lanes** rather than read from history —
    otherwise the failure would be invisible on the timeline. (Per-retry gaps are not
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
- **Disabled ≠ removed** (#248): the detail route of a registered-but-`disabled` engine
  renders normally — the read surface resolves past a disable, exactly like the Audit &
  Notes display always did — with an informational "disabled, not removed" banner naming
  the state and its consequences (readable here; excluded from search; no actions until
  re-enabled). Only a genuinely unregistered engine id gets the "Unknown engine" banner;
  the two claims never co-render.

#### Stage 2 — CMMN case detail: the polymorphic sibling route (Case Inspector Phase 2)
A co-deployed **CMMN case** gets its own read-only, deep-linkable Stage-2 route
**`/case/{engineId}/{caseInstanceId}`** — reached from the Phase-1 out-of-scope scope drawer (each
FAILED job's case is now a link) and the omnibox CMMN-case match. It mirrors the BPMN detail:
- **Vitals header** — case type (key/name/version), engine env badge, state chip
  (ACTIVE / COMPLETED / TERMINATED — **no SUSPENDED**, a case cannot suspend), business key,
  started/ended, and a "why stuck" strip when a plan item has dead-lettered (failing element +
  first exception). A `superProcessInstanceId` shows the calling BPMN process (id only).
- **Diagram first** — a read-only **`cmmn-js`** canvas with plan-item markers (active plan items
  highlighted, dead-lettered plan items badged). A case definition deployed **without graphical
  notation** renders an explicit no-layout state, never a blank box (`cmmn-js` does not auto-lay
  out). The bpmn.io watermark is untouched (R-GOV-05; the build guard now covers `cmmn-js` too).
- **Plan-item timeline** — the CMMN analog of the activity timeline: each plan item as a row with
  its lifecycle state and a live-job badge (`FAILED` = dead-letter parked, `RETRYING` = failing
  with retries), pattern + text never hue-only (§10a); stage children nest. **Runtime-only** on
  Flowable 6.8 (no historic plan-item REST API) — an ended case shows an honest "unavailable"
  state rather than a fabricated empty timeline.

Read-only in this phase (CMMN corrective actions are a later phase); gated to Flowable **6.8+**
(6.3 is dead-letter-blind and stateless on the CMMN context). Full design + wire provenance:
[docs/CMMN-CASE-DETAIL-PHASE-2.md](CMMN-CASE-DETAIL-PHASE-2.md).

### 4a. The variable editor — form first, source available (R-UXQ-13)

**One shared surface** — an editor producing a typed *change-set* plus **one** verification
screen rendering it — reused verbatim by edit-variable (change-set of one),
complete-task-with-data and rerun-with-overrides (change-sets of many). The arc follows the
IBM Business Process Choreographer precedent (form → source → verify → confirm), modernized.
Design stance: **the operator edits a value, never a payload** — the form is the product,
source mode is a labeled power tool, verification is where a tired human catches the mistake
the form couldn't prevent.

- **Entry** — per-row pencil: always visible, keyboard-focusable, never hover-only;
  greyed-with-reason when gated (role below OPERATOR / serializable type / read-only engine /
  completed instance / breaker open). Opens an **inline panel** under the row — not a modal
  (the surrounding ledger and vitals stay visible); the panel header restates the target
  (env band, engine badge, business key, instance ID) so the wrong-instance error dies here.
  Opening **forces the full-value fetch first** — a truncated projection is never editable.
  The old value stays visible above the input throughout. **Scope-aware (M4):** both
  process-scope and execution-local ("step-local") rows are editable — a local row scopes the
  full-value fetch, the CAS pre-check and the write to its owning execution (`scope=local`),
  never the case-wide value; the header, verify sentence and a fixed-order warning line all
  say "step-local", and a same-named case variable is left untouched.
- **Form mode (default) — typed widgets**: *number* with per-subtype range validation and a
  live parsed echo (*"will be stored as integer 42"* — the echo IS the contract); *boolean*
  as a True/False segmented control (**never a toggle** — toggles read as immediate-effect,
  and nothing applies before confirm); *date* with the **timezone-honesty block** (dual
  readout: wall-clock in the operator's zone **and** the exact UTC ISO-8601 string that will
  be sent; offset-less input rejected); *text* with visible leading/trailing whitespace and
  no autocorrect; clearing a value is an explicit **"empty text" vs "no value (null)"**
  choice — never inferred from an empty input.
- **Type lock** — the declared engine type is locked by default; changing it requires an
  explicit per-edit-session unlock behind a warning (*"downstream gateways/scripts may
  depend on this type — text `"42"` and number `42` behave differently"*) and renders as its
  **own amber callout at verification**, never buried in the value diff. Silent type
  coercion (string-ifying a number because a widget was lazy) is a review-blocking defect;
  the lock applies in source mode too.
- **JSON in form mode — leaf edits only**: the same tree as the read view with editable
  leaves (each leaf gets the scalar widget for its JSON type); the dominant task ("flip
  `paymentRetry.enabled`") is a two-click leaf edit that never shows the operator a brace.
  Structural changes (add/remove keys, insert/delete/reorder array items, replace subtrees)
  require source mode — deliberately. Multiple leaf edits stage into one change, rendered
  as path lines (`retryPolicy.maxAttempts: 3 → 5`).
- **Source mode — json variables only, opt-in**: a persistent `Form | Source` segmented
  switch inside the panel (labeled, never icon-only); source is visually unmistakable
  (mono, *"SOURCE — exact typed payload"* band). Lazy-loaded editor chunk (§10). Form→Source
  serializes the staged state losslessly; Source→Form is **blocked while the buffer is
  invalid** — the form never renders a lie about what source contains. Proceeding is gated
  on **parse** (error with line/col) **+ type check** (against the lock) **+ byte-size
  pre-flight** (warn >256 KiB; hard-block >5 MiB = the write cap). One-time note: saving
  re-serializes — the review compares *values*, not formatting. Scalars get no source
  editing; their technical view is the exact-request expander at verification.
- **Verification — the one modal in the flow, user-initiated** (fits R-UXQ-06). Top to
  bottom: env band + target restatement; the **generated plain-language sentence** —
  *"Change **orderTotal** from **0** to **149.90** (number, applies to the whole case) on
  **order-4711** in **billing-prod (PROD)**"* — produced from the **same request object** as
  the payload, so sentence and payload can never disagree. Diff: scalars as Current → After
  panes; json as a **structural path diff** (*"1 of 40 fields changes: `shipping.cost`
  0 → 12.50 — the other 39 are unchanged"*), never a wall-of-text diff; re-serialization
  formatting noise (key order, whitespace) never appears as a change; a collapsed raw-text
  diff expander serves power users. Warning lines in fixed order when applicable: type
  change · execution-local scope · PROD. Byte-size delta shown; **freshness re-check on
  open** (*"server value re-checked — unchanged since you loaded it"*; if stale: blocked
  with attribution and reload as the only forward path). Reversibility framing:
  `RECOVERABLE` — *"the old value is kept in the audit trail"*. Collapsed **"exact
  request"** expander: the typed payload verbatim, real target IDs (processInstanceId vs
  executionId), the CAS precondition, copy-as-engine-cURL (`$ENGINE_CRED`). Reason field per
  §6 tier rules. The confirm button **restates the change** (*"Change `orderTotal` from 0 to
  149.90 on order-4711"*) — never "Confirm"; cancel-focused; Enter never submits; diff
  semantics never color-only (glyph + label per R-UXQ-01).
- **Dispatch & outcome** — the write is compare-and-set (R-SEM-09). A CAS conflict
  **replaces** the confirm content (no error toast): three values — the value you started
  from, your new value, the engine's current value — with attribution from audit (*"changed
  40 s ago by k.meier"*), framed as protection (*"nothing was overwritten"*); forward paths
  are **"start over from the current value"** or cancel — there is **no overwrite-anyway
  button**. No optimistic UI: the ledger re-renders only from re-fetched server truth (2 s
  "updated" highlight). Success = delta toast + audit link + the **offered, never automatic**
  follow-on *"Retry the failed job?"* (the #1 incident sequence, §5.1). Timeout ⇒ UNKNOWN +
  Verify-now (R-SAFE-09); mutations are never auto-retried.
- **Banned outright** (review-blocking, complements §11): raw JSON as the default
  presentation of any variable or of the tab; editing a whole document to change one field;
  last-write-wins; optimistic variable updates; toggle-styled booleans; hover-only or
  hidden edit affordances; "best effort" serializable editing (no hex view, no base64
  paste); timezone-ambiguous date editing; editing a truncated projection; eager full-blob
  fetch or un-virtualized rendering; bundling the source editor eagerly.

### The omnibox
A global input **pinned in the header on every stage** that accepts a paste of anything:
process-instance ID, execution ID, task ID, job ID, composite `engine:id`, or business key —
resolved in that order across all engines. The most common 3am entry ("I have *something*
from a log") gets one box. **Resolution semantics** (R-SEM-04): exactly one match → navigate
to detail; more than one (any mix of kinds/engines — IDs are only engine-unique) →
disambiguation list (kind, engine badge, status chip), never auto-navigate; business key →
always a pre-filtered search (hierarchy-aware; root vs child rows marked); any engine
unreachable → "resolved against N of M engines" banner; zero hits → explicit "not found on
any reachable engine" naming the engines. When no BPMN kind matches on a scope-capable engine
(Flowable ≥ 6.8), a co-deployed **CMMN case** with that id is surfaced as a read-only
`CMMN_CASE` row that **navigates to its Stage-2 detail** (`/case/{engineId}/{caseId}`, §4 Stage 2,
"open the read-only case detail") rather than a false not-found — a pasted Case id from the
out-of-scope drawer must be answered truthfully (do-no-harm / never-lie). The row carries no
`compositeId`/`processInstanceId` (a case is not a process instance), so the route is built from
the engine id and the matched case id. Older engines can't discriminate scope, so a CMMN case is
never *claimed* there; the honest not-found stands.

### 4b. Registry administration — runtime engine lifecycle *(v2)*

A dedicated admin surface (`/admin/engines`, REGISTRY_ADMIN only, **greyed-never-hidden**
in the nav with the reason for everyone else) that moves the engine registry from a
YAML-edit-plus-redeploy to runtime CRUD: onboard a newly-stood-up engine, retire a
decommissioned one, flip read-write↔read-only, or tune a per-engine cap / alarm threshold /
`password-ref` **without a deploy**. The registry moves from `application.yml` to a DB table —
the same "BFF is now stateful" arc as Saved Views (v2/M4) — DB-authoritative once seeded,
with YAML as the one-time bootstrap seed and a `registry.source: config` pin for air-gapped
deploys. **Authoritative design + panel: [REGISTRY-CRUD.md](REGISTRY-CRUD.md).**

The load-bearing constraint (why it is gated to v2): an engine entry is a base-URL the BFF
will dial, so runtime CRUD is an **SSRF surface** aimed by the credential-vault BFF
(R-OPS-07). The guardrails — most of the feature's cost — are, per R-OPS-13/R-OPS-15/R-SAFE-13:
- **Trust is earned, not asserted.** An added engine is born DRAFT + read-only, probed with
  **read-only** calls (version + capabilities — never a mutating call), and cannot be enabled
  read-write until a human confirms a good probe. On prod that flip is four-eyes (R-SAFE-08) +
  typed token. Lifecycle: DRAFT → PROBED/PROBE_FAILED → ACTIVE → DISABLED → REMOVED.
- **The base-URL is validated resolve-then-pin** against a **deploy-config egress allowlist**
  (never editable in-app) with a loopback/link-local/private/metadata (`169.254.169.254`)
  denylist — closing DNS-rebinding, not just check-then-connect. Rejections name the rule and
  the next move (R-UXQ-05), never a bare "denied".
- **Secrets stay env-refs** (iron rule): the DB stores the `password-ref` *name*, never a
  value; the UI shows the ref name + a **presence** indicator ("`ENGINE_X_PASSWORD`: present ✓
  / not in this deployment ✗") so a missing secret is a pre-enable failure, not a first-call
  surprise.
- **REGISTRY_ADMIN** is a fleet-level grant orthogonal to the VIEWER→ADMIN ladder — per-engine
  ADMIN does **not** confer it, and break-glass (R-SAFE-11) does **not** grant it (an IdP
  outage is no reason to repoint the vault). Every write is **audited fail-closed** like a
  tier-3 verb (before/after config; secret refs redacted-by-name). `id` is immutable forever
  (composite-ID key); delete is a soft tombstone so audit/notes/saved-view references still
  resolve "removed engine `<id>`" (R-SEM-17).

Zero-states (R-UXQ-04): no-engines → an actionable "Add your first engine"; config-pinned →
a read-only registry labelled "CRUD disabled (registry source = config)".

### 4c. Access administration — the group→scope mapping *(v2)*

A dedicated admin surface (`/admin/access`, **`ACCESS_ADMIN`** only, **greyed-never-hidden**)
that moves the BFF-owned group→scope mapping (R-SAFE-12) from a mounted YAML file to runtime
CRUD: grant a colleague RESPONDER-on-`orders-prod` mid-incident from a screen — change-audited —
instead of SSHing to edit a mounted volume. The mapping moves file→DB (`group_scope_grant` +
`group_fleet_grant`), **DB-authoritative once seeded**, with the mounted YAML as the one-time seed
and an `inspector.security.mapping-source: file|db` pin for air-gapped deploys. This also finally
wires OIDC for real (Entra ID pilot / Keycloak), hardens the session + transport posture
(R-SAFE-07/R-OPS-16), and builds break-glass (R-SAFE-06/11). **Authoritative design + panel:
[IDP-SECURITY.md](IDP-SECURITY.md).**

The load-bearing constraint (why it is gated to v2): **the mapping is the single most privileged
store in the tool** — a row can grant ADMIN-global or `REGISTRY_ADMIN` (repoint the credential
vault) to any IdP group, so editing it is higher-privilege than any tier-3 verb. The guardrails —
most of the feature's cost, per R-SAFE-14/R-SAFE-15/R-GOV-06:
- **`ACCESS_ADMIN` is the apex orthogonal fleet grant** — it administers the mapping incl. the
  assignment of `REGISTRY_ADMIN` and of `ACCESS_ADMIN` itself. Per-engine ADMIN and `REGISTRY_ADMIN`
  do **not** confer it; break-glass does **not** grant it. Checked by `rbac.canAdministerAccess`.
- **Four-eyes on effective-grant widening** — computed on the resolved grant set: any self-widen,
  **any** grant of role ≥ OPERATOR wildcarded to `*` engines/tenants, **any** fleet-grant create,
  **and** fleet-grant **removal** (removing the other apex admins is a control-plane takeover, not a
  fail-safe narrowing) all need a second approver (∉ the affected group, ≠ proposer, freshly
  re-authenticated); a **≥2-independent-`ACCESS_ADMIN` invariant** and a security-alert on every
  apex change back it up. An `INSPECTOR_ACCESS_ADMIN_GROUP` env grant is the always-available
  lock-out floor.
- **Identity can't go stale on the dangerous verbs.** OIDC groups are re-evaluated within a bounded
  window; tier-3 verbs + bulk + every mapping write force a fresh re-authentication (challenge →
  re-auth → replay, never after the confirm token is typed) and resolve grants from the re-authed
  principal. Sessions are capped (12 h idle / 24 h absolute), fixation-protected, cookies
  `HttpOnly; Secure; SameSite=Lax`. **The live authorization gate fails closed** (an unknown verb
  path is never authorized-by-default).
- **Break-glass** (built): a sealed local ADMIN account on a distinct `/break-glass` chain that
  works when the IdP is down, reachable via a directly-known URL (RUNBOOK §4) — a plain, JS-free
  HTML form the BFF itself serves (no SPA load required: every non-`/api` path is
  `authenticated()`, so the SPA can never load pre-auth, and there is no observable
  "IdP-unreachable" event it could react to even if it could). 4 h, ADMIN-global but never a fleet
  grant, loud + reason-on-every-verb; its audit degrades to a local tamper-evident file sink when
  Postgres is also down.

Zero-states (R-UXQ-04): no eligible four-eyes approver → "recover via the file-pin"; config-pinned →
"CRUD disabled (mapping source = file)"; IdP unreachable → the break-glass door.

### 4d. Person task search — "what is this person sitting on?" *(v1.x #6 follow-up, #99)*

A dedicated route (`/tasks`, VIEWER floor, no new role tier) that answers the operator question
the Tasks tab could not: not "what tasks does THIS instance have" but "what OPEN tasks does THIS
PERSON have, across every engine" — the "Bob is on vacation" query. Search state lives in the URL
(`?person=`), mirroring Stage 1's shareable-link convention.

- **Two legs per engine, fanned out the same way as Stage 1 search**: every OPEN task directly
  **assigned** to the person, plus every OPEN task they could **claim** via a candidate-user/group
  link — deduplicated by task id (a directly-assigned task never double-counts as a claimable
  one). An unreachable engine degrades to a labeled entry on the per-engine envelope; the search
  still answers with whatever engines responded (ARCH §2.2 partial-results contract, reused
  verbatim).
- **Feeds the EXISTING reassign / return-to-team verbs unchanged** (§5 verb table below) — this
  page only finds the targets; each row's action button opens the same modal the instance Tasks
  tab uses, gated by the same tier-1/OPERATOR floor and reason discipline.
- **Non-goal (still unscheduled):** group-membership-aware candidate resolution beyond what the
  engine's own `candidateUser` query answers, and a saved/pinned "my team" roster — this ships as
  a plain one-person-at-a-time lookup.

## 5. Corrective actions — the verb catalog

Every verb states what is preserved. Guard tiers per §6. All calls in
[ARCHITECTURE.md §4](ARCHITECTURE.md); Flowable mappings in the `flowable-rest` skill,
operator-facing engine-call parity in the generated **REST Parity Appendix** (R-L3-02 —
built from the same code as the path whitelist, CI-failing on drift; the UI offers both
"show BFF cURL" and **"copy as engine cURL"** with a `$ENGINE_CRED` placeholder). The
**"Show as cURL"** affordance (v1.x #6) is **server-computed** — the BFF renders the exact
command it will dispatch to its OWN whitelisted verb endpoint with a placeholder credential
(never a live token or an engine path), and the UI shows it verbatim (never re-assembled
client-side, mirroring the search cURL invariant). Present on every tier-1+ action modal
**and every tier-0 inline retry flow** (BPMN dead-letter retry, CMMN case retry — issue
#103 closed the modal-only gap); the "copy as engine cURL" / REST Parity Appendix variant
remains v2.

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
  the side effects of the executed job are not."* For modal-less tier-0 verbs the badge is
  visible ON the button (suspend/activate wear `REVERSIBLE` next to the label — a hover
  title is not a badge), and the §6 outcome toast repeats it with the compensating verb
  named: *"Instance 12345 suspended — reversible; Activate resumes it."* An outcome toast
  states only deltas the call itself guarantees — it never asserts a job-lane move
  (suspend touches executable jobs only; a dead-letter job stays dead-lettered and its
  Retry stays available).
- **Tier-0 friction floor on prod** for verbs that fire irreversible external side effects
  (trigger-timer, retry-now): a two-step inline button confirm (click → "Fire timer for job
  8123?" → click) — sub-second, no modal. Queue-state-only verbs stay single-click.

| Verb | Semantics (UI copy states this) | Tier |
|---|---|---|
| **Retry job** | Dead-letter job moves back to the executable queue; history & variables kept; engine-default retries restored | 0 |
| **Retry now (diagnostic)** | Executes the job synchronously; success or the live exception shown immediately (hard timeout; long jobs blocked) | 0 |
| **Trigger timer now** | Timer fires immediately, takes its normal path | 0 |
| **Unstick event wait** | Deliver message / signal / trigger to a waiting execution (event subscriptions made visible first) | 1 |
| **Suspend / activate instance** | Execution pauses/resumes; executable jobs move to/from the suspended lane — a dead-letter job is unaffected (stays dead-lettered, Retry available) | 0 |
| **Edit variable** | Via the §4a editor: form-first typed widgets, leaf-level json edits, opt-in source mode; old→new path-diff verification; compare-and-set; scope-aware (process vs execution) | 1 |
| **Complete task with data** | Task closes with overridden output; warning: a skipped/forced task never writes its own outputs — edit them here (via the shared §4a change-set editor) | 1 |
| **Reassign task / return to team** *(v1.x #6, landed)* | Reassign to a specific user, or clear the assignee (return-to-team) so the task falls back to its candidate groups; **ACTIVE tasks only** (a completed task can no longer be reassigned); task state otherwise untouched | 1 |
| **Rerun from activity (with overrides)** | Guided composite: variable edits first (§4a change-set), then change-state; token re-enters the chosen activity; history append-only | 2 |
| **Change state / move token** | Cancels ALL executions at the source activity, starts at target; guardrails: blocked on multi-instance bodies, parallel-join warning, suspended-check (offer activate-first), preview labeled *BFF simulation* (engine has no dry-run) + exact REST body shown | 2 |
| **Restart as new instance** | Completed/terminated instance re-launched with copied historic variables; **explicit fork: pin original definition version vs latest**; new instance ID | 2 |
| **Suspend process definition** | One call stops new AND (optionally) running instances of a definition — the real "bad deploy" brake (replaces bulk instance-suspend) | 3 |
| **Terminate / delete instance** | Irreversible; runtime state destroyed; **cascade to call-activity children enumerated in the confirm** | 3 |
| **Delete dead-letter job** | ⚠ Orphans the execution permanently (only rescue afterwards: change-state). ADMIN-only, explicit warning | 3 |
| **Migrate instance** *(v2)* | Move instance to another deployed version of the same key. **Inspector static pre-check (NOT an engine validation** — Flowable's REST API exposes no migration validator, P0 spike 2026-07-09): a BFF model diff flags activities that can't auto-map; the engine is the ground truth only at apply. Honest banner ("this is not a Flowable validation … the engine's own check runs only when you execute"); ADMIN unconditional + typed **business-key** confirm on prod; IRREVERSIBLE, never auto-retried (post-dispatch timeout ⇒ UNKNOWN + verify-now); single first, batch later. See `docs/INSTANCE-MIGRATION.md` | 3 |

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
the same definition version (auto-suggested: the most recent successful sibling — same
`processDefinitionId`, `finished` in the Flowable sense of reaching an end event rather than
dead-lettering; or pick any instance by id in the disambiguation input). A brand-new
definition version with no completed run yet auto-suggests nothing and drops straight to the
manual input. Rendered as three synchronized diffs, **all from historic queries** (read-only,
cheap — the successful siblings are gone from runtime, so runtime tables are never touched):
- **Variables** — key-by-key value differences on the **256 KiB capped projection** (the same
  truncated projection the ledger renders); a value over the cap is never fetched in full just
  to compare — the pair is flagged *values differ beyond preview*. Additions, removals and
  changes carry +/−/± glyphs (never colour alone).
- **Path** — the activity sequence each instance took; the diverging activity-id sets drive the
  shared diagram, differentiated by **stroke style + endpoint glyphs, not red/green hue**
  (SPEC §10a): the failed run's unique path solid/heavy, the sibling's dashed.
- **Timing** — per-activity duration bars side by side (loops sum across occurrences); the
  stalled step carries no subject duration and is flagged so "where it stalled" reads at a
  glance.

A cross-definition sibling (manually picked) still diffs, flagged as a definition mismatch so
the comparison is never silently misleading. The second-most-asked 3am question after "why is
it stuck" gets a one-click answer.

Honesty on failure (R-SEM-12): the empty "no comparable sibling exists" state is shown **only**
when the auto-suggest query succeeds and the backend explicitly answers *found = false*. A
failed auto-suggest query — a network reject, a downed proxy, a 5xx, or a missing route — is
never allowed to masquerade as that empty state; it surfaces an explicit fetch-error banner
that says so, so an operator never mistakes an unreachable engine for "this failure had no
known-good precedent". A manual sibling id that the engine has no record of (400/404) is
distinguished in turn from an infra failure: the operator is told the id was not found on this
engine (a fixable input), not that the diff itself broke.

### 5.3 CMMN case scope — dead-letter retry & delete (Case Inspector Phase 3)
The verb catalog is BPMN-shaped (targets a process instance), but the rails are scope-neutral:
audit (keyed on a generic instance id), RBAC tier, reason discipline, the protected-instance
guard, fail-closed audit, no-auto-retry, and the server-computed cURL all apply unchanged to a
CMMN case. Phase 3 turns the read-only case detail (§4 Stage 2) into an actionable one for the
two dead-letter verbs a co-deployed CMMN case needs — **Retry job** (tier 0 / RESPONDER) and
**Delete dead-letter job** (tier 3 / ADMIN) — the same verbs as BPMN. Both are offered per
dead-letter job in the case's "why-stuck" panel, capability-gated on `scopeType` (Flowable ≥ 6.8 —
older engines are dead-letter-blind on the cmmn context, greyed never hidden). Retry is a tier-0
inline confirm; delete is a tier-3 typed-confirm modal (required reason ≥ 10 chars; on prod, the
typed token = the job id) whose blast-radius copy is scope-honest — a CMMN case has **no
change-state rescue** in this tool, so deleting a dead-letter job orphans its plan item for good.
Only the two scope seams differ from BPMN: the server-fresh restatement reads the job by-id from
the `/cmmn-management` DLQ (cap-free) and keys ownership on `caseInstanceId`, and the one engine
call is the `/cmmn-management/deadletter-jobs/{id}` sibling — `POST … {"action":"move"}` for retry,
`DELETE …` for delete — byte-identical to the process-api shapes (both live-proven 6.8, HTTP 204,
2026-07-08). All other single-target verbs (suspend/activate — cases can't suspend anyway;
terminate; edit-variable) are out of scope and refused for a CMMN case. Route:
`POST /api/cases/{engineId}/{caseInstanceId}/actions/{verb}`.

## 6. The guard ladder

| Tier | Guard |
|---|---|
| **0** — reversible-ish, single target | No modal. In-flight row state → **outcome toast with an explicit delta statement** ("Job 8123 moved to executable queue; retries reset to 3") + audit link. Never a bare "success". A `REVERSIBLE` verb's toast also names the compensating verb ("— reversible; Activate resumes it", §5.0) and claims only deltas the call guarantees (§5.0 — no job-lane claims). |
| **1** — data mutation, single target | Diff confirm (old → new, name, scope). Reason optional on dev/test, **required on prod**. |
| **2** — flow surgery, single target | Confirm + **required reason** + plan-as-a-sentence + raw REST body preview. |
| **3** — destructive, single target | Modal restates the target **server-fresh** (warns if state changed since the grid snapshot), enumerates cascade victims, environment color band. Required reason. On prod: **typed token = the business key** (target-specific — never a generic "yes"/"DELETE"). Cancel-focused; Enter never submits. |
| **4** — destructive, bulk | Wizard: scope enumeration (count, per-engine split, expandable list; filter-based selections re-resolved at submit with drift shown) → required reason → prod: **type the count** → async tracked job. **Refuse-unscoped**: no destructive bulk without at least one narrowing filter. **Landed (#100) — `terminate-delete` only**: `POST /api/bulk/destructive/preview` (read-only scope enumeration) + `POST /api/bulk/destructive` (re-resolves and re-validates everything server-fresh, never trusting the preview). ADMIN-hard-gated at the door (fail-fast, before any resolution work) on every target engine. `delete-deadletter`-at-scale needs a job-level, not instance-level, scope resolver — a documented fast-follow (IMPLEMENTATION-PLAN.md), not silently dropped. |

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
6.3"), role ("requires ADMIN"), state ("no dead-letter jobs"), or **engine policy**
("registered read-only — set by the engine owner; policy, not your role" / registry
lifecycle `disabled|draft|probe_failed`) — four different next moves for the operator.
Engine policy is also visible BEFORE any refusal: a read-only or non-active engine
co-renders a literal second token (READ-ONLY / DISABLED / …) next to its environment badge
wherever actions are offered, the engines list/dashboard renders a non-active engine
greyed-with-reason rather than silently omitting it, and the `engine-read-only` refusal
copy is never identical to an RBAC denial (R-GOV-04, R-SEM-17).

## 7. Bulk operations

- **v1**: grid-selection bulk (intersection of valid actions), cap 200 items, **reason
  mandatory ≥10 chars** (usability fix C-back: unified across every submit door — the
  ticked-selection door used to allow it blank, which the error-class and filter doors
  never did). The numeric caps are **disclosed verbatim in every submit modal's copy**
  (R-NFR-01, usability W2 #5): the 200-cap doors state the cap and name the filter-scope
  bulk (cap 5,000) as the door for larger sets, BEFORE the server's over-cap refusal can
  surprise anyone — executed as a **persisted tracked job from day one** (R-SEM-10; resolves the
  earlier §7↔ARCH §4 ambiguity: one machinery, restart-safe), with per-item result report
  (successful IDs vs id→error table, Conductor `BulkResponse` style) and an **aggregate
  readout** "N of M dispatched · ok/failed/skipped/unknown" (R-SEM-11).
- **Job state machine** (normative): job `PENDING → RUNNING → (COMPLETED | CANCELLED |
  INTERRUPTED)`; item `pending → dispatched → (ok | failed | skipped | skipped (protected) |
  unknown | not_run)`. On BFF startup a reconciliation sweep marks RUNNING → INTERRUPTED;
  the item in flight at crash becomes `unknown` (never re-fired); undispatched become
  `not_run`. No automatic resume, ever: the operations drawer banners INTERRUPTED jobs on
  next login and offers "continue as new job" pre-scoped to `not_run` + `failed`.
- **Scope provenance** (usability fix E1, `V4__bulk_job_scope.sql`): every persisted job
  records which of the three submit doors produced it — `scope_kind ∈ SELECTION |
  ERROR_CLASS | FILTER` — plus a `scope_label` one-liner summarizing what was targeted
  (`"12 ticked instances"` / `"payment v3 · error class"` / a compact criteria restatement —
  statuses + definition key[+version] + engines, ≤120 chars, the same chip notion the
  filter-bulk confirm modal already shows). Surfaced in the operations drawer so a job
  reads "what was targeted" without opening the envelope audit payload.
- **Circuit-open mid-job** (R-SEM-11, issue #101): a fast-fail on an OPEN circuit triggers a
  BOUNDED wait-and-retry for that ONE item (default 20s, polling the breaker rather than
  dispatching a doomed call) — safe because `CallNotPermittedException` guarantees the first
  attempt never actually reached the engine, so the retry can never double-send. If the breaker
  leaves OPEN within the bound, the item retries once and — on success — the rest of that
  engine's items dispatch NORMALLY, never paused; per-item outcomes stay truthful (a recovered
  item's real outcome, never mislabeled by the transient trip). Only when the bound is exceeded
  does dispatch to that engine **pause** — undispatched items stay `pending`, never burned as
  failures; a breaker fast-fail on an already-dispatched item is `failed` (clean rejection);
  `unknown` stays reserved for true ambiguity (timeout per registry `write-ms`). Only the
  tripped engine pauses — other engine groups run on independently throughout, bound or no
  bound. When the job finishes, the held `pending` items settle `not_run` (never attempted) and
  the job is **INTERRUPTED**, not COMPLETED, so the "continue as new job" affordance re-scopes
  `not_run`+`failed`. "No automatic resume, ever" still holds at the JOB level — a job that
  finished INTERRUPTED is never later auto-resumed (only "continue as new job", a fresh
  submission); the bounded item-level retry above is a single, safe, in-flight extension of
  the SAME live dispatch pass, not a resume of a finished job.
- **v1.x #1 — error-class group retry** (landed): the triage landing's bulk-retry-the-group
  dispatches `POST /api/bulk/error-class` carrying the group's **coordinates only**
  (`signatureHash + algoVersion + processDefinitionKey [+ definitionVersion] [+ engineId]`,
  reason mandatory ≥10) — never a browser-enumerated ID list. The BFF re-resolves the
  FAILED members from the same capped signature scan the cards aggregate from and feeds
  them into the v1 machinery above (cap, rails, per-item report unchanged; RETRYING members
  are left to their remaining retries). Stale `algoVersion` ⇒ 409 refuse ("refresh the
  landing"); zero members ⇒ 409 `error-class-drained`, never a zero-item job; a degraded
  resolution leg ⇒ fail-closed 502; > 200 members ⇒ honest cap refusal (select-all-filter
  bulk below is the answer at that size). Tier-3 confirm (restated signature/scope/count +
  reason); PROD typed token = the **definition key** — deliberately not the count: the
  member list is re-resolved server-side at dispatch, so a typed count would attest a
  stale number. The envelope audit row records the group provenance
  (`errorClass`: signature, algoVersion, defKey:vN, resolvedCount, scanTruncated).
  **`definitionVersion` is optional (#105 remainder):** each deployed version on the card
  keeps its own single-version "Retry group" button, and a definition with more than one
  deployed version additionally gets a "Retry group (all versions)" button that omits
  `definitionVersion` — the BFF then resolves the signature across every deployed version
  of the key in ONE job, rather than one `defKey:vN` slice at a time. The envelope
  provenance reads `defKey (all versions)` in that case, never a misleading `defKey:vnull`.
- **v1.x #2 — select-all-matching-filter + SSE progress** (landed): the results grid's
  filter scope (affordance when every visible row is selected, or standalone once a
  filtered search ran) dispatches `POST /api/bulk/filter` carrying the **`SearchRequest`
  criteria only** (verb ∈ v1 whitelist; reason mandatory ≥10; statuses must be explicit
  chips and must not include COMPLETED) — never a browser-enumerated ID list. The BFF
  re-executes the SAME search plan at execution time, **paged to exhaustion** (the grid's
  display page size is ignored), records the criteria + resolved composite-ID list in the
  envelope audit row BEFORE acting, then per-item fan-out on the v1 machinery. Query-bulk
  hard cap **5,000** (grid/error-class keep 200): over-cap ⇒ 400 refuse; zero matches ⇒
  409 `filter-drained`; a degraded engine ⇒ fail-closed 502; a **truncated failure-lane
  scan ⇒ refuse** (`filter-scan-truncated` — "all matching" must never silently be a
  subset; stricter than the error-class path, which stays scoped to one signature — one
  definition key, optionally one version — and records truncation as provenance rather
  than refusing). Filter-scope verbs derive from the status chips (intersection
  doctrine: a verb is offered only when every chip implies eligibility). Tier-3 confirm
  restates the criteria + the snapshot count as context ("~N — applies to the
  server-resolved filter at execution, not this snapshot"); PROD typed token = the
  **definition key** (no key in the filter ⇒ the single prod engine id, else `ALL`) —
  never the raceable count.
- **Engine protection & live progress** (v1.x #2, normative): dispatch fans out **per
  engine** — a per-engine in-flight permit pool (default 4, shared across concurrent jobs)
  plus a mandatory **250 ms stagger** between dispatch starts (`inspector.bulk.engine-permits`
  / `inspector.bulk.stagger-ms`) so a 5,000-item job trickles into the target async
  executor. Live progress is **SSE** (`GET /api/bulk/events`, session-cookie auth): id-only
  `bulk-job` events on every job transition + a 15 s `ping` heartbeat; the browser holds ONE
  app-scoped EventSource, refetches its own JSON debounced (~500 ms), and relaxes the drawer
  poll to a 30 s safety net while the stream is live (full v1 polling when offline). On BFF
  shutdown every stream is completed BEFORE the web server's graceful-shutdown phase (an
  open stream must not hold the grace period hostage); clients auto-reconnect.
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
| Business data | `businessKey` (exact + `businessKeyLike`), variables (name/operator/value, `like` supported). ⚠ Variable-value search hits typically-unindexed engine tables (`ACT_RU/HI_VARINST`): the form warns and nudges "narrow by definition"; a per-engine flag can require it. On engines that silently drop the like-filter (6.3-era), `businessKeyLike` degrades to a per-engine error in the envelope — never silently unfiltered rows (ARCH §2.3) |
| Current activity | activity id/name contains |
| Error text | substring over exception snippets (BFF-side) |
| Error signature | `signatureHash` — the normalized-signature hash (R-SEM-03), the triage card's drill-down; BFF-side over the failure-lane scans with the same one-representative-stacktrace refinement triage uses, so a refined card's hash matches its snippet-only jobs |
| Tenant | **not an operator-selectable filter**: the tenant is pinned per engine in the registry (`tenant-id`) and threaded into every engine query automatically — a multi-tenant engine is inspected through its registered tenant, never chosen ad hoc from the search form (`SearchRequest` deliberately has no tenant field) |

Combination rule unchanged: **AND between categories, OR within** — made visible by the
compiled-criteria echo. Saved views: curated system views + user-named views + recent
searches (§4 Stage 0; user-named views/recents are per-user server-backed as of v2/M4, system
views stay client-derived) + **team-published shared views** (v2, ★ built —
[SHARED-VIEWS.md](SHARED-VIEWS.md), R-SEM-24/R-SAFE-16): an operator/admin publishes curated
canon the team inherits, in a separate governed `shared_view` store (publish = snapshot-copy,
`covers()`-gated by scope). Read-visibility is **scoped declutter, NOT a security boundary**
(precedence System → Team → Private); a shared view whose scoped engine is later removed
renders **greyed-with-reason and never as a clean all-clear** (§4 Stage 0). Unpublish is a
**moderation verb**: a reason ≥10 is required from EVERY caller — the author included —
and is rendered first-class in the operations log (R-SAFE-16, usability W2 #3).
Hierarchy-aware: businessKey search finds the tree, not just the root — and the results
grid marks call-activity children (`↳ child`, parent id in the tooltip) so identically-keyed
tree rows stay distinguishable (R-UXQ-12 half, usability W2 #7).

## 9. Audit, notes & handover

- Append-only audit — normative schema (R-AUD-02): `audit_entry(id, correlationId,
  bulkJobId FK, user, ts, engineId, tenantId, instanceId, action, reason ≥10 chars,
  ticketId, payload jsonb — per-verb versioned schemas, e.g. edit-variable {name, scope,
  oldValue, newValue, valueType} — httpStatus, outcome, responseSnippet ≤32 KiB
  truncated+flagged, breakGlass, approvedBy)`; indexes `(engineId, instanceId, ts)` + `(ts)`;
  monthly range partitions. One row per bulk item + one for the envelope. Written whether
  the engine call succeeded or failed — and **fail-closed** (R-AUD-01): if the audit INSERT
  fails, a tier ≥1 mutation is not issued *(the v1 implementation is deliberately stricter:
  fail-closed applies to ALL tiers including 0 — an unaudited queue move is still an
  attribution hole, and R-AUD-01 states a floor, not a ceiling)*. Tier-1 variable edits are compare-and-set
  (R-SEM-09): the request carries `expectedOldValue`; mismatch ⇒ 409 + fresh re-render.
- **Data protection** (R-AUD-03): variable payloads in audit rows and notes are potentially
  personal data. Retention default 400 days with an audited purge; per-engine `audit-payload:
  full | redacted | metadata-only`; secret-name denylist → `«redacted»`; payload bodies
  role-gated OPERATOR+; DB role INSERT/SELECT-only + hash-chain tamper evidence; erasure =
  skeleton-preserving redaction. Details: OPERATIONS.md §6. Never logs secrets.
- Surfaced four ways: **per-instance tab** (what did the last shift try), **global
  operations log** page (filterable by actor/action/engine/ticketId/time, with a streaming
  **CSV export** over the same filters — R-AUD-08; formula-escaped per R-OPS-08, skeleton
  columns only: payload/response bodies stay role-gated in the app per R-AUD-03), **recent
  operations** on the triage landing, and the **shift report** (R-AUD-05): a "My shift"
  preset (current user, since shift start = last 8 h) + one-click plain-text export
  ("Copy shift report", UTC ISO timestamps), UNKNOWN outcomes grouped first under
  NEEDS VERIFICATION.
- **Notes** per composite ID (BFF-owned; author + timestamp; "has notes" grid marker).
  Copy-for-ticket includes the latest note + a one-line actions-taken summary (R-AUD-06);
  a group-level copy-for-ticket exists on Stage 0 (v1.x). Reasons carry an optional
  (per-deployment requirable) **ticketId**, regex-validated and linkified via
  `ticket-url-template` (R-AUD-07) — captured by an optional "Ticket ID" field on every
  reason-bearing confirm dialog (destructive modal, edit-variable verify, bulk submits);
  bulk completion can fire a signed webhook (v1.x).
- **Watchlist** (R-BAU-05, v1.x): per-user pinned composite IDs as a landing panel with
  changed-since-last-seen indication; with the start-of-shift delta ("changes since
  <marker>") and future maintenance snapshots, it shares ONE count-snapshot store.
- IBM BAW ships no admin-action audit trail at all — this is a headline differentiator.
- **Attribution tradeoff (explicit)**: because the BFF calls engines with a shared service
  account, Flowable's own history tables attribute every mutation to that account — **the
  BFF audit log is the only authoritative source of WHO acted**. Investigations must start
  here, not in `ACT_HI_*`. Where the engine deployment is ours (flap), the optional
  `X-Forwarded-User` header + engine-side interceptor restores native attribution
  (ARCHITECTURE §6); it is a per-engine bonus, never relied upon. Surfaced in-product
  (R-AUD-09) as a static info caveat on the per-instance Audit tab AND the operations-log
  header: "Engine-side history attributes these actions to the shared service account —
  this log is the authoritative WHO."

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
- **Contract:** springdoc-openapi from the Java record DTOs (single source of truth),
  served at `/v3/api-docs` (key-ordered, fixed info version → deterministic output) →
  **`openapi-typescript`** generated `frontend/src/api/schema.d.ts` (`npm run gen:api`
  against a running BFF) + the singleton `openapi-fetch` client, committed; CI regenerates
  and **fails on diff** — cross-language drift is a build failure *(drift gate in CI still
  to land — needs a booted BFF in the workflow)*.
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
  license term.** **CodeMirror 6** (`@codemirror/lang-json` + lint) is the variable
  editor's source-mode component (§4a) — chosen for parse-error line/col location, bracket
  matching and folding on multi-KiB payloads; shipped as a **lazy route-level chunk** so
  form-mode-only operators never download it; Monaco rejected (multi-MB + worker plumbing),
  bare textarea rejected (no error location). All user-facing strings live in one message
  catalog per side; all
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
  generated summary. AG Grid: full keyboard nav; row-open is never mouse-only — Enter on
  the focused row/cell opens the instance detail through the same handler as double-click,
  with a visible hint beside the grid; row actions never hover-only. Detail tabs are an
  ARIA-APG tablist: roving tabindex, ArrowLeft/ArrowRight (wrapping) + Home/End move focus,
  activation stays manual. After every route change focus lands on the new route's main
  heading or nearest surviving landmark — never `<body>`. Modals: focus trapped,
  cancel-focused, Esc per tier, focus returns to invoker or nearest survivor.
  Keyboard-only path LENGTH is a first-class concern, served by skip controls (the
  standard off-screen-until-focused pattern): a shell-level "Skip to main content" link
  bypasses the header gauntlet on every page, and the instance detail page — only while
  the instance actually holds dead-letter jobs — renders a "Skip to failed job" control
  as its first focusable, which opens Errors & Jobs and hands focus straight to the
  failed job's Retry action (without ever stealing focus if the user has Tab'd on
  meanwhile), so the common FIND→FIX retry arc costs a handful of key presses instead
  of a full linear Tab traversal of the vitals header, diagram and tablist.
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
  no humor, no apology theater — the 3am rule. Not-found copy is coverage-parameterized:
  definitive ("resolved against N of N engines — confirmed not-found") when every engine
  answered, hedged ("the engine may be unreachable") ONLY under real partial coverage —
  never both at once (usability W2 #4). A coverage claim ("resolved against N of N
  engines", "confirmed zero across N engines", "clean on every reachable engine") counts and
  names the registered-but-excluded engines IN THE SAME SENTENCE — e.g. "(2 more registered
  engines not checked: eng-c [disabled] — see Engines)" — never only in a separate panel
  (#236: a wrong "does not exist" answer closes a real ticket, so the sentence alone must
  reveal the check was not exhaustive). Counts name their unit wherever the two families
  co-render: job-lane numbers say **jobs**, status tiles and drill totals say **instances**
  (usability W2 #7 — 36+13 jobs vs 46+7 instances must never read as comparable).
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
  roll-up + explain-status evidence (landed — usability W3-4, R-L3-01); triage landing incl.
  acknowledge (landed — usability W3-2, R-BAU-01) + leak views (landed — usability W3-3,
  R-BAU-02/R-SEM-05) + badged counts; omnibox; URL state + deep
  links; full-page detail (vitals, read-only
  diagram, tabs, form-first variable ledger + editor (§4a), raw-JSON links); **verbs:
  tiers 0–1 + suspend/activate +
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
- **v1.x** — **sequencing authority: `docs/IMPLEMENTATION-PLAN.md` §"v1.x — fast follows" is
  the single source of truth for order and scope; this list is the product-rationale view and
  defers to it.** (Max one in flight alongside pilot support; each names its KPI/trigger. This
  ordering was reconciled to the plan — the *proposal inbox* and *error-class annotations*
  items, numbered #3/#4 in an earlier draft, are deliberately sequenced **after** the numbered
  fast-follows now and moved to the unscheduled list below.)
  1. error-class bulk-retry from the landing group (pre-committed pilot gap) — **landed**;
  2. select-all-matching-filter bulk + SSE progress + operations drawer + drain — **landed**;
  3. named saved views; column chooser + density + dark theme (saved views, dark theme,
     grid density, grid column chooser, and saved-views column-visibility capture all
     **landed** — issue #104, #197);
  4. timeline polish — **call-activity sub-lanes only** (failing async node synthesized from
     the live job lanes; `FAILED`/`RETRYING` live annotation). Job-lane trend sparklines were
     **descoped to v2** — they need the R-BAU-08 snapshot/time-series store, absent in v1;
  5. sibling diff (§5.2 — trigger: ≥5 "why did this one fail" investigations/month, probed
     via a stub affordance) — **landed**;
  6. task reassign / return-to-team + **"Show as cURL"** on the action modals — **landed
     (v1.x #6)**. NOTE: an earlier draft paired reassign with person-centric task search
     ("never apart"); that coupling was relaxed — reassign/return-to-team shipped with the
     cURL-honesty affordance first, and person-centric task search **landed as the follow-up
     (#99, §4d)**: reassign is reachable from the instance Tasks tab, and finding a person's
     tasks across engines now has its own `/tasks` route feeding the same verbs;
  7. external-worker job view (capability-gated, 6.8+) — **landed (v1.x #7)**: the fifth
     read-only job lane (lock owner / expiration / retries / exception) + vitals count, sourced
     from the External Worker REST API's `/external-job-api/jobs` sibling context (the
     management API has no external-worker endpoint); pre-6.8 engines refuse in the BFF and hide
     the lane in the UI.
  - **Not yet scheduled in the plan** (candidate v1.x/v2, sequenced after the numbered list —
    ordered differently from earlier drafts): second-person approval / proposal inbox (hooks
    already in v1 schema); error-class annotations + endorsed verbs + group-level
    copy-for-ticket; watchlist + start-of-shift delta (one snapshot store); suspend
    reason/review-by; business summary; timers-due-in-window; ops reporting; support bundle;
    forensic passthrough; stacktrace ergonomics; engine advisories; secret-rotation file refs;
    clock-skew badges; capability invalidation; CSV export; print styles; webhook.
- **v2 (demand-driven, still pending):** **remediation playbooks (§5.1 — build trigger
  R-GOV-08)**, migration batch wizard (single-instance migration + Inspector static pre-check
  shipped; the multi-instance wizard is still pending), definition version comparison, CMMN
  (Phase 0/1 shipped — R-SEM-20, docs/CMMN-CASE-DETAIL-PHASE-2.md; further phases pending),
  maintenance snapshots + volume trends, training mode, capability overrides.
- **v2 — SHIPPED (built directly on panel-review consensus rather than a fired demand signal;
  historical trigger framing retained for provenance):** **registry CRUD** (runtime engine
  lifecycle — SPEC §4b, the R-OPS-13/R-OPS-15/R-SAFE-13 SSRF + governance rails; S1–S5 landed,
  [REGISTRY-CRUD.md](REGISTRY-CRUD.md); S4b four-eyes on dangerous writes + connect-time
  IP-pinning deferred, issue #91), **shared server-side (team) views** (an operator/admin
  publishes curated canon the team inherits — S1–S6 landed, [SHARED-VIEWS.md](SHARED-VIEWS.md),
  R-SEM-24/R-SAFE-16; separate governed `shared_view` store, snapshot-copy publish,
  `covers()`-gated by scope, `overlaps()`-scoped read-visibility as declutter, replay-time
  dangling-canon honesty; the stated build trigger — distinct owners repeatedly re-creating the
  same canonical `search` string — was never instrumented, the panel approved building directly),
  **k-way-merge deep paging** (cursor-based browsing of the globally-sorted merged stream — ★
  FEATURE COMPLETE, S0–S5 landed, [KWAY-PAGING.md](KWAY-PAGING.md), R-SEM-22/23, R-NFR-08; the
  stated build trigger — operators repeatedly hitting `perEngine.total > fetched` on a
  time-sorted search without narrowing — was superseded by the P0 wire-shape spike going ahead
  and building ✅; R-SEM-23, the deterministic total-order fix, shipped as a standalone MUST).
- **v2/M4 job-lane trend sparklines — SHIPPED (R-BAU-08):** each Stage-0 status tile carries a
  small trend line of that lane's recent count history, read from the snapshot store (a scheduled
  sampler persists per-engine, per-lane Stage-0 counts to Postgres on a thin background engine
  lane), so the trend reads never touch the live engine. A lane with no history shows no line
  (never a fabricated flat trend); the line's shape + an accessible label carry the meaning, hue
  only echoes the lane chip.

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
- **v3.5** — M3 detail-data backend landed: Stage 0 status counts gain synthesized
  FAILED/RETRYING keys (§4 Stage 0 — distinct-instance counts from the failure-lane scans,
  FAILED precedence, lower-bound under truncation); search gains `definitionVersion`
  (native pushdown via the concrete per-engine definition id) and `signatureHash` (§8 —
  BFF-side with the refinement bridge); `GET /api/resolve` and the Stage 2 detail
  resources (`vitals`/`diagram`/`variables[/{name}]`/`jobs[+stacktrace]`/`hierarchy`/
  `timeline`) specified in ARCHITECTURE §4. Wire truth recorded there: a dead-lettered
  async task has no unfinished historic activity row — current activities are the union
  of unfinished activities and runtime execution positions.
- **v3.4** — M4 backend implementation note (§9): fail-closed (R-AUD-01) is applied to ALL
  tiers including 0 in v1 — the spec floor (tier ≥1) stays normative, the implementation
  is deliberately stricter. New definition-scoped actions route recorded in ARCHITECTURE
  §4 (`/api/definitions/{engineId}/{definitionId}/actions/{verb}`).
- **v3.3** — Variable editor designed (web-design + usability panel, DESIGN-REVIEW
  addendum): §4 Variables tab re-spec'd as a typed ledger (never raw-JSON-primary;
  Flowable Control anti-pattern banned); new §4a shared change-set editor — form-first
  typed widgets, type-lock, leaf-level json edits, json-only lazy source mode (CodeMirror 6,
  §10), one verification modal (generated sentence + structural path diff + exact-request
  expander + CAS freshness re-check), no-overwrite conflict recovery, offered-never-auto
  follow-on. IBM BPC form/source/verify/confirm precedent. Register R-UXQ-13.
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
