# 📄 SPECIFICATION — Flowable Multi-Instance Process Inspector

Status: **v2.0 (refined product spec)** · Owner: workflow platform team ·
Inspired by IBM BAW Process Inspector; refined against Camunda Operate/Cockpit, Temporal,
Flowable Control, Conductor/Orkes, Airflow and Step Functions, and a four-seat design review
(workflow-engine expert, senior support engineer, lead developer, UX expert — see
[DESIGN-REVIEW.md](DESIGN-REVIEW.md)).

## 1. System overview

The Process Inspector is a centralized administrative tool used by support teams and workflow
administrators to investigate, troubleshoot, and **fix** runtime problems with process
instances across **multiple Flowable environments** from one UI — strictly via the Flowable
V6 REST API.

**The user** is an on-call support engineer under incident pressure — an intermittent user of
this tool who lives in tickets and logs. Every design decision is judged against the incident
loop: **FIND → ORIENT → DIAGNOSE → FIX → VERIFY**, plus **HANDOVER** to the next shift.

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
  out-of-scope actions with the RBAC reason.

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

The UI renders a primary chip (`COMPLETED / FAILED / FAILING / SUSPENDED / ACTIVE`, in that
precedence) **plus secondary badges** for collisions ("SUSPENDED · has dead-letter jobs") and
subprocess roll-up ("FAILED — in subprocess *chargePayment*"). Search filters operate on flag
predicates. CMMN-scoped jobs (null `processInstanceId`) are filtered out of every join; row
DTOs carry `scopeType` from day one for future CMMN support. Multi-tenant engines thread
`tenantId` through **every** query leg.

## 4. UI structure — three stages, not three panes

The v1.0 IDE-style fixed split (Search | Results | Details) starved the detail surface where
incident time is spent, and landed users on an empty search form (IBM's documented mistake).
Replaced by:

### Stage 0 — Triage landing (the default route)
Answers "what is broken, how much, where" in zero keystrokes:
- **Engine health strip** — per engine: badge (environment-colored), version, reachability,
  and job-lane counts (executable / timer / suspended / dead-letter) with two derived alarms:
  *oldest executable job age* and *overdue timers* (executor-starvation signals).
- **Failures grouped by error class** — dead-letter (and FAILING-tier) jobs grouped by
  **normalized exception signature** (class + message with IDs/numbers stripped), with counts
  per engine and per definition version ("NPE in TaxCalculator — 312 · orders-prod · v47: 312,
  v46: 0"). Each group clicks through to the pre-filtered search; each group offers
  **bulk-retry-the-group**. This is the triage centerpiece.
- **Status counts** per engine × status (from query totals — no row fetch).
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
    into the ground.
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
A global input that accepts a **paste of anything**: process-instance ID, execution ID, task
ID, job ID, composite `engine:id`, or business key — resolved in that order across all
engines. The most common 3am entry ("I have *something* from a log") gets one box.

## 5. Corrective actions — the verb catalog

Every verb states what is preserved. Guard tiers per §6. All calls in
[ARCHITECTURE.md §4](ARCHITECTURE.md); Flowable mappings in the `flowable-rest` skill.

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

Cross-cutting: environment (`dev|test|prod`) drives a consistent color band on badges,
headers, and inside every confirm modal (freeform per-engine colors are demoted to a subtle
accent — color encodes environment, not identity). Disabled actions are greyed **never
hidden**, with a tooltip naming the gate — capability ("needs Flowable ≥ 6.4; billing runs
6.3"), role ("requires ADMIN"), or state ("no dead-letter jobs") — three different next moves
for the operator.

## 7. Bulk operations

- **v1**: grid-selection bulk (intersection of valid actions), hard cap, per-item result
  report (successful IDs vs id→error table, Conductor `BulkResponse` style).
- **v1.x**: **select-all-matching-filter** — the BFF re-executes the search plan at
  execution time (never the stale grid), records the resolved ID list in the audit record
  BEFORE acting, then per-item fan-out as a **server-side async job**: persisted in Postgres,
  live progress via SSE, **cancel** (stops dispatching), per-engine concurrency cap +
  optional stagger (a simultaneous 300-job DLQ move can DDoS the async executor), per-item
  **precondition recheck** ("still in the DLQ?").
- Outcome classes: `ok | failed | skipped (already resolved) | unknown` — a timed-out
  mutation is UNKNOWN, never auto-retried, listed for manual verification (a blind retry
  double-fires).
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

- Append-only audit: `(user, ts, engineId, instanceId, tenantId, action, reason,
  requestPayload incl. old values for variable edits, httpStatus, outcome, responseSnippet)`.
  One row per bulk item + one for the envelope. Written whether the engine call succeeded or
  failed. Never logs secrets.
- Surfaced three ways: **per-instance tab** (what did the last shift try), **global
  operations log** page, **recent operations** on the triage landing.
- **Notes** per composite ID (BFF-owned; author + timestamp; "has notes" grid marker).
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
  v5** (its stale-snapshot model IS the spec's snapshot+Refresh semantics; SSE events
  invalidate queries). React Router v7 + typed search-param codec for URL state. Zustand
  only when the operations drawer lands. AG Grid Community (pinned; audit features against
  Community before designing around them), bpmn-js (`NavigatedViewer` M3 → `Viewer` +
  overlays M5).
- **Testing:** JUnit 5/AssertJ/Mockito · **WireMock** for engine stubs (timeouts, 5xx,
  truncated DLQ, version cliffs — the load-bearing layer) · Testcontainers Postgres ·
  `@WebMvcTest` + `spring-security-test` for per-endpoint RBAC/guard tiers · Vitest + RTL +
  MSW (reusing OpenAPI types) · **Playwright** E2E against the full compose stack (smoke in
  PR CI, matrix nightly) — Playwright is also the agent's autonomous visual feedback loop.
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

## 11. Non-goals & explicitly rejected (with reasons)

- No BPMN editing/deployment tooling; no engine-DB access (unchanged).
- No cross-engine transactional bulk (unchanged) and no fake global pagination (unchanged).
- **No query language** — Flowable's query REST has no OR-across-fields/boolean nesting; a
  grammar would either lie or duplicate the form. The compiled-criteria echo + copy-as-cURL
  delivers the teaching/scripting value without owning a parser.
- **No Temporal-style reset**, no timer reschedule, no engine-verified modification preview,
  no per-retry Gantt gaps, no async-executor thread internals — the REST API cannot honestly
  provide them; nearest honest equivalents are documented per verb.
- **CMMN out of scope for v1** (jobs filtered from every join; `scopeType` carried in DTOs
  so v2 case support is additive).
- No WebSockets/MQTT; no per-component polling.

## 12. Release train

- **v1 (must ship):** corrected status join + FAILING tier + hierarchy roll-up; triage
  landing; omnibox; URL state + deep links; full-page detail with vitals header + read-only
  diagram + tabs; verb catalog tiers 0–3 (single-target); grid-selection bulk with per-item
  report; audit + notes + reasons; Postgres; dual auth profile; disabled-with-reason.
- **v1.x (fast follows):** error-class bulk-retry from the landing group; select-all-matching-
  filter bulk as tracked async jobs + SSE progress + operations drawer; named saved views;
  job-lane dashboard trends; timeline tab; **sibling diff (§5.2)**; task reassign; "show as
  cURL" on every action.
- **v2 (demand-driven):** **remediation playbooks (§5.1 — the headline)**, migration (single
  w/ validate → batch wizard w/ side-by-side diagrams + typed "MIGRATE"), definition version
  comparison + per-version instance counts, CMMN, registry CRUD UI, shared server-side
  views, k-way-merge deep paging.

## Change log
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
