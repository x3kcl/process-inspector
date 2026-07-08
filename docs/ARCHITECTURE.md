# 🏗 ARCHITECTURE — Multi-Instance Process Inspector

Answers spec deliverables **1 (high-level design)** and **2 (Engine Registry data model)**.
Updated to v2 alongside SPECIFICATION v2.0 (see [DESIGN-REVIEW.md](DESIGN-REVIEW.md)).

## 1. Topology

```
                       ┌────────────────────────────────────────────┐
                       │        Process Inspector (one deployable)  │
 ┌──────────┐  HTTPS   │  ┌──────────────┐      ┌─────────────────┐ │   basic/bearer   ┌───────────────┐
 │ React SPA├─────────▶│  │  BFF REST API │────▶│ Engine Registry │ │────────────────▶│ Engine A REST │
 │ (static) │  session │  │  /api/**      │      │ (config+health) │ │                  └───────────────┘
 └──────────┘          │  └──────┬───────┘      └─────────────────┘ │────────────────▶ Engine B REST
                       │         │ fan-out (parallel, per-engine     │────────────────▶ Engine C REST
                       │         ▼  timeout, partial results)        │
                       │  ┌──────────────┐ ┌──────────┐ ┌─────────┐ │      ┌──────────┐
                       │  │ Aggregator   │ │Audit+Note│ │RBAC+Guard│ │─────▶│ Postgres │ (M4+)
                       │  │ + BulkJobs   │ └──────────┘ └─────────┘ │      └──────────┘
                       └────────────────────────────────────────────┘
```

One BFF, N engines. The browser has exactly **one** origin and **one** auth session; every
engine call is made server-side with that engine's own credentials. This kills the CORS and
credential-sprawl problems in one move. The BFF is **single-instance and session-stateful by
design** — which is also what legitimizes in-memory bulk-job execution (state flushed to
Postgres per item; a job interrupted by restart is marked `INTERRUPTED` with its partial
report intact). Don't scale out until someone demonstrates the need.

## 2. The multi-instance problems and their answers

### 2.1 Identity — composite IDs
A process-instance ID is only unique *within* an engine. Everywhere outside a single engine
call we use the composite `engineId + ":" + processInstanceId` (e.g. `orders-prod:12345`).
The row DTO carries `engineId` (and `scopeType`, and `tenantId` where relevant) as first-class
fields; every detail/action endpoint takes `/{engineId}/{processInstanceId}`.

### 2.2 Fan-out — parallel, bounded, partial
The Aggregator executes one *search plan* per selected engine on a bounded executor:
parallel across engines, per-engine timeout (registry `timeouts.readMs`, budgeted **per
call**, not per plan), and an engine that is down/slow/misconfigured yields an **error
envelope, never a failed search**:

```json
{ "rows": [...], "asOf": "2026-07-06T14:32:05Z", "perEngine": {
    "orders-prod":  { "ok": true,  "fetched": 138, "total": 138, "dlqScan": "complete" },
    "billing-prod": { "ok": false, "error": "timeout after 10s", "hint": "variable filters are expensive — narrow by definition" },
    "orders-dr":    { "ok": true,  "fetched": 200, "total": 2410, "dlqScan": "truncated@5000" } } }
```
The UI renders partial results and badges the failing engine; every count derived under an
error or truncation is labeled a lower bound. *A support tool must degrade, not blank —
and never let truncated data impersonate a healthy status.*

**Do no harm (resiliency):** every outbound engine call runs through a per-engine
**Resilience4j circuit breaker + bulkhead**. The engines are typically already under load
or DB contention when this tool is in use — a struggling engine must neither starve the
BFF's threads nor be tipped over by fan-out (DLQ scans, triage aggregations). An open
circuit is an ordinary `perEngine` error envelope (`"error": "circuit open — engine
shedding load"`, with the retry-after). The **triage aggregations are cached ~15–30s**
(Caffeine) so N concurrent operators produce one round of engine queries; Refresh bypasses
the cache, rate-limited. Variable list responses **cap value byte size** (truncated preview
+ on-demand full fetch) so blob variables exhaust neither browser nor engine.

### 2.3 Semantics — the status join (v2, corrected)

Flowable has no `FAILED` instance state; failure lives in job queues. Status is derived in
the BFF as **flags** (`ended`, `suspended`, `hasDeadLetterJobs`, `hasFailingJobs`,
`failedInSubprocess`), because statuses genuinely collide (a suspended instance keeps its
dead-letter jobs — retrying one then burns its retries against the suspended instance, so
the retry verb checks suspension and offers activate-first).

**Plan selection by requested status set:**
- **FAILED-only searches drive from the DLQ** (inverted plan): page
  `GET /management/deadletter-jobs` to exhaustion (bounded, `processDefinitionId` pushed down
  when a definition filter is set), collect `processInstanceId`s, resolve call-activity
  children up the `superProcessInstanceId` chain (**max-depth 10**, cycle-guarded — a
  looping call-activity structure must not recurse the BFF), then hydrate via
  `POST /query/historic-process-instances` with the `processInstanceIds` list.
- **Mixed searches**: primary historic query per filters → per-page enrichment of exactly
  the displayed rows: runtime state (suspended flag) via `/query/process-instances` with
  `processInstanceIds`; DLQ membership via `GET /management/deadletter-jobs?processInstanceId=`
  (bounded N+1 over one page, parallelized).
- **FAILING tier** (about-to-fail): `GET /management/jobs?withException=true` +
  `GET /management/timer-jobs?withException=true` — a failing async job parks in the *timer*
  table between attempts (`asyncFailedJobWaitTime`), so both queries are required.

**Never** issue a single unpaged DLQ fetch as "the failed set" (default page size is 10;
anything beyond the page silently declassifies FAILED → ACTIVE — the exact instances the
tool exists to find). Where a scan is capped anyway, set `dlqScan: "truncated@N"` and badge.

**Filter kinds (M2b, SPEC §8):** three mechanically different families, ANDed between
categories:
- **Flowable-native** — `businessKey` (exact), `businessKeyLike` (substring, wrapped `%…%`
  and pushed down as `processBusinessKeyLike`), `variables` (equals/like — the like
  operation is honored by the whole matrix incl. 6.3.1, proven live), start window,
  definition key, `definitionVersion` (requires the key; resolved per engine to the ONE
  concrete `processDefinitionId` and pushed down into the historic query AND the job-lane
  scans; an undeployed version is an honestly empty slice, never an unfiltered scan; the
  key→ids resolution pages `/repository/process-definitions` to exhaustion — a
  long-deployed key holds more versions than one engine page, and a version outside the
  first page must not silently vanish from the plan). The
  engine evaluates them; no BFF query parser exists or ever will (SPEC §11 — Flowable's
  query REST has no OR-across-fields to compile to).
- **BFF-side over the scan legs** — `failureTimeAfter/Before` (inclusive window over
  dead-letter/failing job `createTime`), `errorText` (case-insensitive substring over
  exception snippets) and `signatureHash` (the R-SEM-03 triage drill-down: jobs group by
  snippet signature; a group whose snippet hash misses gets ONE representative-stacktrace
  refinement — same algorithm and sample cap as triage — so a refined card's hash still
  matches its snippet-only jobs): Flowable cannot query instances by failure evidence, so
  these filter the JOB rows **before grouping and root resolution** — a filtered-out child
  failure never rolls up its parent. Setting any of them means only failure-bearing rows
  can match; in the mixed plan, rows whose evidence is filtered away drop out.
- **Separate native leg** — `currentActivity` (contains, id or name): unfinished
  `historic-activity-instances` per candidate open row (bounded N+1, same budget rules as
  DLQ membership), intersected in the BFF. Completed rows can never match.

**Facets (`statusCounts`):** counts of the candidates the chosen plan actually evaluated —
after the non-status filters, before the status predicate, keyed by primary chip. The map
contains only statuses the plan could observe (an inverted search never saw ACTIVE, so no
ACTIVE key — never a fake zero) and inherits lower-bound semantics from any truncation
marker. These are search-page facets; the Stage-0 triage counts remain the independent
`size=1`-total queries (M3), never this plan.

**Hygiene applied to every leg:** CMMN-scoped jobs filtered out (shared job tables; null
`processInstanceId` / `scopeType='cmmn'` where serialized — ~6.8+); the Stage-0 triage
additionally **counts** the dead-letters it excludes as `outOfScopeDeadletters` (null when
the engine lacks the `scopeType` capability) instead of dropping them silently, so the health
strip's dead-letter lane reconciles with the process-scoped FAILED count — and a concrete
count becomes a **lower bound** (rendered `≥N`) when the DEADLETTER lane's own scan hit the
cap, flagged by `deadletterTruncated`: the lane-specific truncation, captured before the
unified `dlqScan` marker OR-conflates it with the timer/executable lanes; `tenantId` threaded
through **all** legs when the engine is multi-tenant. That scalar count is **drillable** (Case
Inspector Phase 1, first slice): `GET /api/triage/engines/{id}/out-of-scope-deadletters`
(`CmmnScopeService`) enumerates the CMMN dead-letters from the **CMMN-api** sibling context
(`FlowableEngineClient.cmmnApiBase` — the `/cmmn-api` sibling of `/service`, same convention as
the external-job-api), keeping only rows with a non-null `caseInstanceId` (the shared cmmn-api
DLQ list also projects BPMN jobs, which carry a null case attribution). It is bounded by
`dlq-scan-cap`, paged, and floors to a `truncated` lower bound like every DLQ scan; the
`scopeType` capability is the gate (a pre-6.8 engine — whose cmmn context is dead-letter-blind
— is refused with a ProblemDetail, never a silently-wrong view). This is a NET-NEW window on
the shared table — it reads the cmmn-api projection whereas the Phase-0 scalar counts null-pid
orphans from the process-api scan; the two have different caps and can legitimately disagree
under truncation, so it is NOT an upgrade of the Phase-0 code. Read-only (CMMN corrective
actions are a later phase); async-history lag tolerated — the grid
is a labeled snapshot, the details panel fetches live-first with historic fallback (M3), and
actions never trust the grid: every mutation re-validates against live engine state
server-side and returns the engine's 404/409 verbatim (snapshot races are acceptable for
display, never for action targeting).

**Enrichment cliffs (call-time detection, not version sniffing):** the runtime query
silently IGNORES an unknown `processInstanceIds` field on old engines (proven on 6.3.1 —
the result is unfiltered, and trusting it would join foreign rows into the suspended set).
The bulk suspended-state enrichment detects this (a returned id outside the requested set,
or an impossible total) and falls back to bounded parallel per-id GETs. Historic-query
`processInstanceIds` filters correctly on the whole matrix (6.3.1/6.8/7.1). No engine on
the matrix serializes `processDefinitionKey`/`processDefinitionVersion` on historic rows —
both are derived from `processDefinitionId` (`key:version:uuid`).

Same failure mode, second field (M2b): 6.3.1 silently drops `processBusinessKeyLike` on
BOTH the historic and runtime queries (proven live — an impossible-match like returns the
full unfiltered total). Detection is an impossible-match **canary** (`size=1`, only on
searches that use businessKeyLike): if it still returns rows, the filter was dropped and
that engine degrades to a per-engine envelope error ("businessKeyLike not supported…") —
never confidently unfiltered rows. There is no BFF post-filter fallback on purpose: page-
bounded substring filtering would silently miss matches beyond the page. The `variables`
filter and the unfinished-activity query have NO such cliff — identical wire shapes across
the matrix (probed live 2026-07).

### 2.4 Aggregation — sorting & paging across engines
There is no global cursor across independent engines. v1 strategy (deliberate, simple,
honest): each engine queried with `size = min(maxPageSize, requestedPageSize)`, rows merged
and sorted in the BFF (default `startTime desc`; `failureTime` sortable), `perEngine.total`
tells the user when an engine has more ("138 of 2,410 — narrow your filter"). No fake global
pagination. Every enumeration loop (bulk-by-query, error-class aggregation) has a **hard item
cap and refuses above it** — refuse-unscoped is an industry pattern, not an apology.
v2 can add k-way-merge cursors if real usage demands deep paging.

### 2.5 Drift — capability probing
Engines run different Flowable versions. On registry load (and on demand) the BFF calls
`GET /management/engine`, records version + reachability, and empirically probes feature
cliffs: `changeState` (6.4+), `migration` + `migration/validate` (~6.5+, batch shape varies
6.5→7.x), `externalWorkerJobs` (6.8+), `scopeType` on job rows (~6.8+), historic-activity
availability (history level ≥ activity). Capability-flagged features are greyed in the UI
**with the reason** and rejected by the BFF — never a confusing engine-side 404 at click time.
The Stage-0 out-of-scope-dead-letter tally (§2.3, R-SEM-20) is an explicit consumer of
`scopeType`: it emits `outOfScopeDeadletters = null` (unknown, never a confident zero) where
the flag is false, since a pre-6.8 engine is CMMN-dead-letter-blind.

## 3. Engine Registry — data model

v1 is **config-first** (YAML + env-var secret refs, 12-factor). The identical shape becomes a
JPA table when runtime CRUD of engines is wanted (v2).

```yaml
inspector:
  triage:                            # Stage 0 landing knobs (SPEC §4)
    cache-ttl-s: 20                  # aggregation cache TTL — thundering-herd protection
    refresh-min-interval-s: 10       # Refresh-bypass throttle (per user once auth lands, M4+)
    stacktrace-sample-cap: 25        # representative stacktrace fetches per round (group refinement)
  engines:
    - id: orders-prod                # stable slug, used in composite IDs — never rename
      name: "Orders µService (PROD)" # display name
      base-url: "http://engine-a:8080/flowable-rest/service"
      environment: prod              # dev|test|prod — drives env color band + guard strictness
      accent-color: "#e74c3c"        # OPTIONAL subtle accent only; environment owns semantics
      enabled: true
      tenant-id: ""                  # OPTIONAL: pin this registration to one tenant
      actuator-url: ""               # OPTIONAL: real executor metrics when exposed; never required
      telemetry-url-template: ""     # OPTIONAL: APM/logs deep-link, e.g.
                                     # "https://kibana/app/discover#/?_a=(query:'{processInstanceId}')"
                                     # placeholders: {processInstanceId} {executionId} {businessKey} {failureTime}
      forward-user-header: false     # OPTIONAL: send X-Forwarded-User for engine-side attribution (§6)
      auth:
        type: basic                  # basic | bearer | none
        username: rest-admin
        password-ref: ENGINE_A_PASSWORD   # NAME of an env var; secret never in config/UI/logs
      mode: read-write               # read-write | read-only — rollout ramp (R-GOV-04)
      jurisdiction: ""               # OPTIONAL: data-residency tag (R-AUD-03)
      timeouts:
        connect-ms: 2000
        read-ms: 10000               # budgeted PER CALL within a search plan
        write-ms: 10000              # governs MUTATING calls; UNKNOWN's definition depends on it (R-NFR-07)
      max-page-size: 200             # cap per fan-out query page
      dlq-scan-cap: 5000             # exhaustive-paging bound (test profile uses 50 — TEST-STRATEGY §9)
      alarm-thresholds:              # R-NFR-04 defaults, per-engine overridable
        oldest-job-warn-min: 5
        oldest-job-crit-min: 15
        overdue-timer-grace-s: 60
      advisories: []                 # known engine-version issues → enabled-with-warning verbs (R-L3-06)
      require-second-approval: []    # e.g. [tier3, tier4] — four-eyes (R-SAFE-08)
```

**Runtime state kept per engine (not config):** `reachable`, `engineVersion`,
`lastHealthCheck`, `capabilities{changeState, migration, externalWorkerJobs, scopeType,
activityHistory}`, `jobLanes{executable, timer, suspended, deadletter}`,
`oldestExecutableJobAge`, `overdueTimers` — populated by the scheduled health probe,
surfaced by `GET /api/engines` and pushed to the health strip via SSE.

## 4. BFF API surface (v2)

| Endpoint | Purpose |
|---|---|
| `GET  /api/engines` | Registry + live health/capabilities/job-lane counts (no secrets) |
| `GET  /api/resolve?q=…` | **Omnibox**: one pasted string resolved across engines in the R-SEM-04 order (process-instance → execution → task → job → business key; composite `engine:id` short-circuits to that engine); returns a disambiguation list (kind, engineId, compositeId, derived flags) + a `perEngine` reachability envelope for the "N of M engines" banner |
| `POST /api/search` | Fan-out instance search (`SearchRequest`; URL-serializable) |
| `GET  /api/triage[?refresh=true]` | Stage 0 dashboard: engine health strip (M1 probe state), global + per-engine status counts (query totals, `size=1`), DLQ + RETRYING jobs grouped by normalized error signature with per-engine/per-definition-version counts (sibling versions zero-filled), per-engine honesty envelope (`ok/error/dlqScan`, plus `outOfScopeDeadletters` — dead-letters belonging to a co-deployed engine sharing the job tables (CMMN), counted not dropped so the strip's dead-letter lane reconciles with FAILED; null when the engine cannot discriminate scope, gated on the `scopeType` capability ~6.8+). Served from the 20s Caffeine cache (single-flight); `refresh=true` bypasses, throttled 1/10s |
| `GET  /api/triage/engines/{engineId}/out-of-scope-deadletters` | **Case Inspector Phase 1** (first slice): the drill behind `outOfScopeDeadletters` — read-only enumeration of the co-deployed CMMN engine's dead-letter jobs (`CmmnDeadLetterJob`: readable case type — `caseDefinitionKey`/`caseDefinitionName` resolved by a bounded distinct-id lookup against `cmmn-repository/case-definitions/{id}` since a CMMN `caseDefinitionId` is a bare uuid — plus failing element, exception snippet, case-instance id, retries) from the `/cmmn-api` sibling context, keeping only rows with a non-null `caseInstanceId`. Bounded by `dlq-scan-cap`, paged, `truncated` lower bound. VIEWER floor; capability-gated (`scopeType`, Flowable ≥ 6.8 — a pre-6.8 engine is refused with a ProblemDetail). No corrective action (CMMN actions are a later phase) |
| `GET  /api/instances/{engineId}/{id}` | Vitals (historic-first — a completed instance renders, never 404s): identity, definition key/name/version, status flags + primary chip, current activities (unfinished historic activities ∪ runtime execution positions — a dead-lettered async task has NO unfinished activity row, its execution carries the token), why-stuck strip (exception first line, failing activity, retries state), waiting-for (event subscriptions + pending timers), `telemetryUrl` (the engine's `telemetry-url-template` rendered with url-encoded values; absent template → no field) |
| `GET  /api/instances/{engineId}/{id}/variables[/{name}]` | The TYPED ledger (R-UXQ-13), READ-only: engine type verbatim next to the typed value, process scope + per-execution locals, HISTORIC projection for completed instances; serializables read-only; list responses byte-capped at 256 KiB with the on-demand full-value fetch `GET …/variables/{name}`. The EDIT is not a PUT here — it is the tier-1 `edit-variable` verb via `POST …/actions/edit-variable` (row above), always on the fetched full value: compare-and-set — `expectedOldValue` ⇒ 409 + fresh re-render on mismatch (R-SEM-09); UI contract: SPEC §4a |
| `GET  /api/instances/{engineId}/{id}/jobs` | Four lanes (executable/timer/suspended/deadletter), typed rows; stacktrace on expand via `GET …/jobs/{jobId}/stacktrace?lane=` (plain text) |
| `GET  /api/instances/{engineId}/{id}/jobs/external-worker` | **Fifth queue** (v1.x #7, read-only): external-worker jobs with the worker lock (`lockOwner`/`lockExpirationTime`). Capability-gated (Flowable ≥ 6.8) — a pre-6.8 engine gets a ProblemDetail, never an empty list. Sourced from the External Worker REST API at the `/external-job-api` sibling context (the management API has no external-worker endpoint), derived from base-url by convention; the active count also rides the vitals summary (`InstanceDetail.externalWorkerJobs`) |
| `GET  /api/instances/{engineId}/{id}/tasks` | User tasks, completed AND open, one ledger: historic-first (`/history/historic-task-instances` — an open task is a historic row with a null `endTime`) unioned with `/runtime/tasks` (live assignee, `suspended`; covers dialed-down task history); derived `state` = COMPLETED ▸ SUSPENDED ▸ ACTIVE; engine-total + `truncated` honesty |
| `GET  /api/instances/{engineId}/{id}/hierarchy` | Call-activity tree BOTH directions: up-walk to the root (`superProcessInstanceId`, cycle-guarded), BFS down; depth cap 10 (registry), breadth cap 50 rendered/node (R-SEM-19) with exact `childTotal` from the query total, per-node dead-letter markers |
| `GET  /api/instances/{engineId}/{id}/timeline` | Historic activity instances (Gantt rows, `startTime` asc, top-level truncation marker). A call-activity row nests the called instance's own activities as a `children` sub-lane, recursed under the hierarchy caps (depth 10, breadth 50/node, 500-node budget) with a `calledProcessInstanceId` cycle guard; a node truncated by any cap carries `isCapped`. Failing/unfinished nodes carry `liveJobState` (`FAILED`=dead-letter, `RETRYING`=failing-with-retries) joined from the runtime lanes — a dead-lettered async node, whose history row rolled back with its transaction, is **synthesized from the lanes** (phantom-node union) so the failure is never invisible |
| `GET  /api/instances/{engineId}/{id}/audit` · `…/notes` | Per-instance action history + notes (CRUD) |
| `POST /api/instances/{engineId}/{id}/actions/{verb}` | Verb catalog (SPEC §5); guard tier + reason enforced server-side. Includes `reassign-task` / `unassign-task` (v1.x #6, tier 1 / OPERATOR): server-fresh task restatement gates on the LIVE task (`GET /runtime/tasks/{taskId}` — a completed task 404s → "not active"), then one `PUT /runtime/tasks/{taskId}` `{"assignee":…\|null}`; the audit payload records old→new assignee |
| `POST /api/instances/{engineId}/{id}/actions/{verb}/curl` | "Show as cURL" (v1.x #6): SERVER-computed command for the proposed action — same RBAC door as execute, but touches neither engine nor audit; renders THIS BFF's verb URL + JSON body + placeholder credential (never a live token, never the engine path). UI shows it verbatim |
| `POST /api/definitions/{engineId}/{definitionId}/actions/{verb}` | Definition-scoped verbs (`suspend-definition` / `activate-definition`, tier 3) — same guard rails, typed token = the definition key |
| `POST /api/instances/{engineId}/{id}/change-state/preview` | v1.1 flow surgery: the **BFF simulation** (Flowable has no change-state dry-run — SPEC §5 "not offered"). Runs every blocking guard against live state + the parsed model — capability ≥6.4 (fail-closed while unprobed), writable engine, instance running + NOT suspended (409 activate-first), activities exist, sources currently active per `/runtime/executions`, **MI-body block both directions (422)** — and returns the EXACT `{cancelActivityIds, startActivityIds}` body execute will send, a plan-as-a-sentence summary and non-blocking warnings (`parallel-branch-target`). Read-only: no audit row, no Postgres dependency |
| `POST /api/instances/{engineId}/{id}/change-state/execute` | The token move (tier 2; OPERATOR floor, **ADMIN on prod** — §5): re-plans server-fresh (never trusts an earlier preview), then the full M4 rails — protection guard, reason ≥10 always, fail-closed audit PENDING carrying source/target activities + the verbatim REST payload, ONE `POST …/change-state`, honest close-out |
| `POST /api/instances/{engineId}/{id}/restart` | Restart-as-new (tier 2, OPERATOR): refuses running instances (409) — historic `endTime` required; explicit version fork `pinDefinitionVersion` (true = original `processDefinitionId`, verified still deployed; false = definition KEY → latest); carries portable **global** historic variables (task-locals/execution-locals are not instance state; intrinsics like `initiator` and non-round-trippable types land in a reported `skippedVariables` map — never a silent drop; >500 variables = refused, a partial copy would be a lie); audited against the ORIGINAL instance id, response carries the new instance id |
| `GET  /api/instances/{engineId}/{id}/diagram` | BPMN XML exactly as deployed (definition → deploymentId + resourceName → `/repository/deployments/{deploymentId}/resourcedata/{resourceName}`) + active/dead-letter activity-id sets for the bpmn-js markers |
| `POST /api/bulk` | v1 (landed): `{verb, items:[{engineId,instanceId,jobId?}], reason?, ticketId?, continuedFrom?}` — creates a PERSISTED tracked job (cap 200; verb whitelist retry-job/suspend/activate/trigger-timer, destructive bulk waits for the tier-4 wizard); protected targets settle `skipped_protected` at submit; ONE fail-closed envelope audit row + one per item; per-item fan-out through the full single-target guard chain. Dispatch is per ENGINE (v1.x #2): an in-flight permit pool per engine (default 4, shared across concurrent jobs) + a mandatory 250 ms stagger between dispatch starts (`inspector.bulk.engine-permits` / `inspector.bulk.stagger-ms`) — engines proceed independently, one slow engine never starves the others. retry-job resolves the instance's CURRENT dead-letter jobs at dispatch time (built-in precondition recheck; none left ⇒ `skipped`). Every job/item transition publishes an id-only `BulkJobChangedEvent` (the SSE feed) |
| `POST /api/bulk/error-class` | v1.x #1 (landed): the triage group retry — body is the group's COORDINATES only `{signatureHash, algoVersion, processDefinitionKey, definitionVersion, engineId?, reason (mandatory ≥10), ticketId?}`; the BFF re-resolves the FAILED members itself via the same capped failure-lane scan + signature-refinement bridge the cards aggregate from (never a client ID list, never the grid plan) and delegates to the `/api/bulk` machinery above — cap, per-item RBAC, protected auto-exclusion, fail-closed envelope + per-item audit all unchanged. Refusals: stale `algoVersion` ⇒ 409; zero members ⇒ 409 `error-class-drained` (never a zero-item job); any degraded engine leg ⇒ 502 fail-closed; >200 ⇒ `bulk-cap-exceeded`. Envelope payload carries the group provenance (`errorClass`: hash, algoVersion, defKey:vN, engineId, resolvedCount, scanTruncated) |
| `POST /api/bulk/filter` | v1.x #2 (landed): select-all-matching-filter — body `{criteria: SearchRequest, verb, reason (mandatory ≥10), ticketId?}`; server-side re-resolution is BINDING: the BFF re-executes the SAME M2a plan **paged to exhaustion** (`SearchService.resolveAllMatching`; the criteria's display `pageSize` is stripped) and delegates to the `/api/bulk` machinery under the **5,000** query-bulk cap (`BulkJob.FILTER_ITEM_CAP`, V3 migration; every other entry keeps 200). Refusals: missing/short reason; statuses absent or containing COMPLETED (`filter-statuses-required` / `filter-completed-not-actionable`); zero matches ⇒ 409 `filter-drained`; degraded engine ⇒ 502 fail-closed; truncated failure-lane scan ⇒ 400 `filter-scan-truncated` (a binding "all matching" never silently acts on a subset); >5,000 ⇒ `bulk-cap-exceeded`; a MIXED-plan candidate pool already known over the cap degrades that engine honestly instead of enumerating it. Envelope payload carries `filter: {criteria, resolvedCount}` plus the full resolved target list — recorded BEFORE dispatch |
| `GET  /api/bulk/events` | SSE (v1.x #2, live-ui-sse doctrine): ONE stream per browser; id-only `bulk-job` events (data = job UUID) bridged from `BulkJobChangedEvent` + a 15 s `ping` heartbeat; no initial event on connect (clients fetch their own first state); a failed write drops the emitter, never `complete()`s it. Auth = session cookie (EventSource cannot send Authorization; the dev chain persists Basic auth into the HTTP session). On shutdown the hub completes every stream BEFORE the web server's graceful-shutdown lifecycle phase — an open stream must not hold the 30 s grace period hostage. Engine-health events join this stream in a later slice |
| `GET  /api/bulk[/{id}]` · `POST /api/bulk/{id}/cancel` | Job state machine (`PENDING→RUNNING→COMPLETED\|CANCELLED\|INTERRUPTED`) + persisted per-item report (tallies on the list read, items on the detail read); cancel stops DISPATCHING — sent items keep their outcome; startup sweep marks stale PENDING/RUNNING → INTERRUPTED (dispatched→`unknown`, pending→`not_run`, never re-fired); "continue as new job" = a fresh submit with `continuedFrom` |
| `POST /api/bulk/{id}/items/{ordinal}/verify` | Verify-now (R-SAFE-09): re-runs the verb's precondition predicate against LIVE engine state and reclassifies `unknown` with evidence — never re-fires the mutation |
| `GET  /api/me` | Auth hint for greyed-never-hidden UI: username + highest ladder role per engine, resolved through the SAME `RbacAuthorizer` path the guards use (presentation only — the per-request BFF check stays the gate) |
| `GET  /api/instances/{engineId}/{id}/nearest-sibling` | **Sibling diff** auto-suggest (v1.x #5, landed): the smart default — most recently COMPLETED instance of the same `processDefinitionId` (`finished:true` + `sort=endTime desc`, reached an end event, not dead-lettered); returns `found:false` (not an error) when a fresh version has no completed run. VIEWER floor, historic queries only |
| `GET  /api/instances/{engineId}/{id}/diff/{siblingId}` | **Sibling diff** three-way `SiblingDiffResponse` (v1.x #5, landed): variable deltas on the 256 KiB capped projection (an over-cap pair ships `DIFFER_BEYOND_PREVIEW`, never a full fetch), path divergence as `onlyInSubject`/`onlyInSibling`/`common` activity-id sets (drive the diagram stroke overlay — ids only, no hue), per-activity timing deltas (loops sum; the stalled open step carries a null duration + `subjectUnfinished`). A manually-picked cross-definition sibling still diffs, flagged `sameDefinition:false`. VIEWER floor, historic only |
| `POST /api/playbooks` · `GET /api/playbooks` · `POST /api/playbooks/{id}/replay` | **Remediation playbooks** (v2): record = distill the exemplar's audit rows into a verb sequence; replay = a bulk job whose items run the sequence with per-step precondition rechecks |
| `GET  /api/audit` | Global operations log (filterable) |
| `GET  /api/stream` | RESERVED (v1.x): engine-health SSE — bulk-job progress already streams on `GET /api/bulk/events`; whichever lands second consolidates the two into one app stream (the BFF is the event source; no engine polling relay) |
| `GET  /v3/api-docs` | The OpenAPI contract (springdoc, key-ordered + fixed info version for deterministic codegen); the only unauthenticated API route besides health — it describes the surface, never data. Source of `frontend/src/api/schema.d.ts` via `npm run gen:api` (R-SEM-15) |

The BFF **whitelists** engine paths — never a blind proxy. flowable-rest authorization is
effectively binary (`access-rest-api`), so **BFF RBAC is the only real permission layer**;
nobody may "simplify" by exposing an engine directly.

## 5. Cross-cutting

- **Auth (dual profile from M4):** dev = form/basic + session; prod = **OIDC**
  (roles from a claim). One session-stateful BFF instance; engine credentials live only in
  the BFF process env.
- **RBAC:** `VIEWER` (read-only) → **`RESPONDER`** (tier-0 verbs + unstick + notes — the
  runbook tier; no variable writes, no token moves) → `OPERATOR` (adds tiers 1–2) → `ADMIN`
  (tiers 3–4: terminate/delete, suspend-definition, deadletter-delete, migrate, bulk).
  (change-state is tier 2 flow surgery but gated ADMIN on `prod` engines.) Per-verb grant
  overrides `(role, verb, engineId, tenantId)` supported (R-SAFE-01). Enforced in the BFF,
  mirrored in the UI as greyed-with-reason. Protected instances (R-SAFE-05) and read-only
  engine mode (R-GOV-04) are additional guard-layer gates. `engineId` slugs are validated
  fail-fast at startup: `^[a-z0-9][a-z0-9._-]{0,63}$` (R-SEM-08); composites split on the
  FIRST `:`. **Grants are scoped, not global**: an OIDC
  role/group maps to `(role, engineId | *, tenantId | *)` tuples — ADMIN on
  `orders-prod`/tenant-A authorizes nothing on another engine or tenant; the guard layer
  resolves the acting user's scope set against the target of every call.
- **Attribution:** engines see the shared service account; Flowable's `ACT_HI_*` tables
  therefore attribute mutations to it. The BFF audit log is the sole authoritative
  human-attribution record. Engines with `forward-user-header: true` additionally receive
  `X-Forwarded-User: <username>` on mutating calls for an engine-side interceptor (§6).
- **Persistence (Postgres, M4+):** audit log (append-only), instance notes, bulk-job state +
  per-item reports, shared saved views (v2; v1.x saved views/recents live in browser
  localStorage under a versioned envelope), registry CRUD (v2). No durable job-execution
  framework — in-memory execution, per-item flush, `INTERRUPTED` on restart.
- **Audit:** every mutating call → `(user, ts, engineId, instanceId, tenantId, action,
  reason, requestPayload incl. old values, httpStatus, outcome, responseSnippet)`; one row
  per bulk item + envelope. Non-negotiable for a tool whose job is poking production state —
  and the differentiator IBM BAW lacks.
- **Safety rails:** the SPEC §6 guard ladder (reasons tier ≥2, target-specific typed tokens
  on prod, refuse-unscoped bulk, server-fresh target restatement); BFF never auto-retries
  mutating calls; timed-out mutations reported `UNKNOWN`, never re-fired; bulk streams
  per-item results with `ok/failed/skipped/unknown`.
- **Variable-search cost:** engine history tables are typically unindexed on value; variable
  filters get a "narrow by definition" nudge and a distinguished timeout hint in the envelope.
- **Operating the Inspector itself** — health semantics (engine reachability is NEVER a
  readiness component), the contract metric set, correlationId propagation, audit
  fail-closed + backup/retention/tamper-evidence, threat model (the BFF is a credential
  vault: egress allowlist, actuator lockdown, per-engine unique credentials), break-glass,
  session policy, deploy/drain, CI gates: **[OPERATIONS.md](OPERATIONS.md)**. Board-accepted
  requirements with priorities: **[REQUIREMENTS-REGISTER.md](REQUIREMENTS-REGISTER.md)**;
  test governance: **[TEST-STRATEGY.md](TEST-STRATEGY.md)**. One `java.time.Clock` bean
  behind every age/staleness computation; event timestamps always from engine responses
  (R-TEST-07).

## 6. Inspecting an embedded-engine application (the flap case)

The companion system **flap** embeds Flowable **7.0.0** (`flowable-spring-boot-starter`)
behind its own UI and does **not** expose the Flowable REST API — so it is not inspectable
until it does. The integration recipe (an afternoon on the flap side):

1. **Add** `org.flowable:flowable-spring-boot-starter-rest` (same version as the embedded
   engine) — mounts the process REST API under **`/process-api/**`** in flap's servlet
   context. Flowable 7's REST surface is the direct continuation of the V6 API for
   everything the inspector uses (`/query/*`, `/history/*`, `/runtime/*`, `/management/*`,
   `/repository/*`).
2. **Secure it explicitly**: a dedicated `SecurityFilterChain` ordered ahead of the UI
   chain, matching `/process-api/**`, HTTP Basic, `STATELESS`, CSRF off *for that matcher
   only* (API calls must not create UI sessions), a dedicated machine account
   (`inspector-svc`) with the `access-rest-api` privilege, credential injected as an env
   secret and referenced from the inspector registry via `password-ref`. flowable-rest
   authorization is binary — network scoping plus the inspector's BFF RBAC is the entire
   defense.
   **Network scoping must match the actual topology, or it silently fails:**
   - *Plain Docker*: a dedicated backend bridge network shared only by the inspector and
     the engine is the cleanest fence — the path is simply unroutable from elsewhere; no
     IP evaluation needed in the app.
   - *Behind an ingress controller / reverse proxy*: the engine sees the **proxy's** IP.
     An application-level IP restriction then requires the security chain to trust
     forwarded headers — a correctly configured `ForwardedHeaderFilter` /
     `server.forward-headers-strategy` with the proxy explicitly trusted — or the
     restriction evaluates the wrong address and either blocks the inspector or (worse)
     allows everyone the proxy forwards. Prefer enforcing the allow-list **at the
     proxy/ingress itself** (location-scoped rule for `/process-api/**`) over in-app IP
     checks; if in-app checks are used, they must be tested from outside the trusted path.
   - Never rely on `X-Forwarded-For` from an untrusted hop — any client can set it.
3. **Registry entry**: `base-url: https://flap-host/process-api` — base-URL shapes vary per
   deployment (`…/flowable-rest/service` on the standalone image); nothing outside config
   may assume a path shape.
4. **Native attribution (optional, recommended for flap)**: set
   `forward-user-header: true` in the registry; flap's `/process-api/**` chain adds a small
   interceptor that reads `X-Forwarded-User` and sets the Flowable authenticated user for
   the request (`Authentication.setAuthenticatedUserId(...)` scoped to the call), so
   `ACT_HI_*` records the real engineer instead of `inspector-svc`. Trust the header ONLY
   on this chain, only from the inspector's network path — it is an attribution courtesy,
   not an authentication mechanism; the BFF audit log remains authoritative either way.

**Inspector-side implications:** the CI engine matrix includes a **7.x profile** (probe
treats 7.x as passing all 6.x capability cliffs unless proven otherwise; watch the
batch-migration payload shape, §2.5); the inspector never links Flowable libraries, so no
version-lockstep with flap — the codebases stay idiomatically similar (both Spring) but
physically decoupled over HTTP.
**Error-shape drift (7.x):** Flowable 7 sits on Spring Boot 3/Jakarta, and standard Spring
error responses (which Flowable wraps or sometimes leaks) changed shape across that
baseline. The BFF's engine-error interceptors — and specifically the exception-snippet
extraction feeding the triage error-class normalizer — must be contract-tested against
BOTH 6.x and 7.x error JSON in CI (WireMock fixtures captured from each real profile).
A parser that silently fails degrades every 7.x failure into one "unparseable" group —
exactly the kind of quiet lie the design principles forbid.
