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
(`CmmnApiClient.cmmnApiBase` — the `/cmmn-api` sibling of `/service`, same convention as
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

**Deterministic total order (R-SEM-23):** the post-merge sort is a total order — the sort key
then `compositeId` as a final tiebreak — so a given result set orders identically across
requests (a precondition for any cursor, but a correctness fix in its own right for page-1).
`startTime` is compared as a parsed `Instant`, since the matrix mixes offset-form and `Z`-form
ISO timestamps that a raw String comparison mis-orders. See §2.4 and `docs/KWAY-PAGING.md`.

### 2.4 Aggregation — sorting & paging across engines
There is no global cursor across independent engines. v1 strategy (deliberate, simple,
honest): each engine queried with `size = min(maxPageSize, requestedPageSize)`, rows merged
and sorted in the BFF (default `startTime desc`; `failureTime` sortable), `perEngine.total`
tells the user when an engine has more ("138 of 2,410 — narrow your filter"). No fake global
pagination. Every enumeration loop (bulk-by-query, error-class aggregation) has a **hard item
cap and refuses above it** — refuse-unscoped is an industry pattern, not an apology.
The merge uses a **deterministic total order** — the sort key then `compositeId` as a final
tiebreak, with `startTime` compared as a parsed `Instant` (not a raw String, which mis-orders
the offset-form vs `Z`-form timestamps different engines emit); ties are otherwise
nondeterministic across requests (R-SEM-23, §2.3).
v2 **k-way-merge deep paging** (★ FEATURE COMPLETE 2026-07-09 — S0–S5 landed, CI-green,
`docs/KWAY-PAGING.md`): a stateless opaque cursor pages the globally-sorted merged stream, wired
into `POST /api/search` as the SAME endpoint with a cursor present (the SPA's "Load more"). It is a
**tagged union by plan** — the MIXED/`startTime desc` plan carries a resumable per-engine offset;
the INVERTED/`failureTime` plan has **no** engine-side resume position (it scans the DLQ unsorted
and sorts on a BFF-derived key), so deep paging is MIXED-first and INVERTED is gated off.
Do-no-harm: an inbound per-engine offset bound-check + size clamp **before** fan-out (the real
DoS ceiling — a `filterHash`-bound cursor gives no integrity against a crafted one), a dedicated
`DEEP_PAGE` bulkhead lane so deep scroll can't starve interactive search, a per-engine depth cap,
and a cursor TTL. Built after the mandatory P0 wire-shape spike (6.3/6.8/7.1) confirmed the
offset-cost model; the per-engine depth cap is a conservative interim default — the real O(offset)
cost curve near the cap is still unmeasured (§C-11).

### 2.5 Drift — capability probing
Engines run different Flowable versions. On registry load (and on demand) the BFF calls
`GET /management/engine`, records version + reachability, and empirically probes feature
cliffs: `changeState` (6.4+), `migration` (execute route only, ~6.5+; there is **no**
`migration/validate` REST resource on ANY version — P0 spike 2026-07-09, so the migration
"preview" is a BFF static model diff, never an engine call; batch shape varies 6.5→7.x),
`externalWorkerJobs` (6.8+), `scopeType` on job rows (~6.8+), historic-activity
availability (history level ≥ activity). Capability-flagged features are greyed in the UI
**with the reason** and rejected by the BFF — never a confusing engine-side 404 at click time.
The Stage-0 out-of-scope-dead-letter tally (§2.3, R-SEM-20) is an explicit consumer of
`scopeType`: it emits `outOfScopeDeadletters = null` (unknown, never a confident zero) where
the flag is false, since a pre-6.8 engine is CMMN-dead-letter-blind.

### 2.6 Trend — the snapshot store (v2/M4, R-BAU-08)
Job-lane trends must not be reconstructed by re-querying engines: the BFF **persists** the
Stage-0 counts over time. A `@Scheduled` virtual-thread **sampler** (`io.inspector.snapshot`)
re-runs the Stage-0 count aggregation on a fixed cadence and **upserts** one narrow row per
`(engine_id, lane)` — the status chips plus `OUT_OF_SCOPE_DLQ` — into the `triage_snapshot`
time-series, keyed to a bucketed `sampled_at` (idempotent `ON CONFLICT`, so a restart or
scheduler overlap within a bucket cannot double-count; a poll is NOT a mutation, so no audit
rail). A `SnapshotSource` seam sits between ingestion and the store so an event-driven source
can replace polling later, per-engine and capability-gated, without touching the schema or the
trend read path. **Ingestion stays strictly-via-REST** (the iron rule) and inherits do-no-harm:
sampler engine calls run on a **separate, thin per-engine Resilience4j lane** (`GuardedCaller.CallPriority.BACKGROUND`,
instance `{id}:sampler`, 2 permits) so a trend poll can never starve the 8 interactive search
permits. Retention is **drop-partition** (400-day revFADP): the table is `PARTITION BY RANGE
(sampled_at)` with a DEFAULT catch-all, and a maintainer creates months ahead and DROPs (never
DELETEs) those past the horizon, so the sweep never locks the partition being written. A NULL
out-of-scope count writes no row — the series stays honest rather than fabricating a zero.

## 3. Engine Registry — data model

v1 is **config-first** (YAML + env-var secret refs, 12-factor). The identical shape becomes a
JPA table (`engine_registry`, `V7`) when runtime CRUD of engines is wanted — **v2 Registry
CRUD, design locked in [REGISTRY-CRUD.md](REGISTRY-CRUD.md)**. The store is
**DB-authoritative once initialized**: an empty engine table imports `inspector.engines` as a
one-time audited seed (like the v2/M4 localStorage→server backfill), a non-empty table wins
and YAML is ignored with a startup WARN, and `inspector.registry.source: config` pins to
config-only (CRUD off, restart semantics restored — the air-gap posture). A registry write
refreshes `EngineRegistry`'s map, **evicts the affected `GuardedCaller` RestClient
caches** (which cache per id forever — an edited base-URL/auth is otherwise stale) and resets
the Resilience4j named instances on remove, publishes a `RegistryChangedEvent`, and triggers
an immediate read-only re-probe — a live reload, no restart. `id` is immutable (composite-ID
key); delete is a soft tombstone so id→name still resolves in audit/notes/saved-views. The
runtime base-URL is validated **resolve-then-pin** against a deploy-config egress allowlist
(SSRF, §5 + REGISTRY-CRUD.md §5). The YAML shape below is unchanged and remains the seed:

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

**One error contract everywhere (issue #87 — F4).** Every error response, from a domain-specific
action-layer refusal to Spring Security's own 401/403 to a typo'd route, is the SAME RFC-7807
`ProblemDetail` shape — `type`/`title`/`status`/`detail`/`instance` plus two additive properties,
`code` (machine-readable — a domain slug like `cas-conflict`/`rbac-denied` where the exception
carries one, else a kebab-case slug of the HTTP status) and `requestId` (the quotable support id,
R-AUD-04). Three call-site layers converge on it: `ActionExceptionHandler` (`@RestControllerAdvice`)
handles every domain exception plus the two generic ones (`IllegalArgumentException` → 400
`bad-request`, `ResponseStatusException` → its own status + a status-derived `code` — this is
where all ~50 plain `throw new ResponseStatusException(status, "…")` call sites across the app
land, with ZERO per-call-site changes); `ProblemDetailRequestIdAdvice` stamps `requestId` onto
any `ProblemDetail` an advice or handler returns; `RequestIdErrorAttributes` renders the SAME
shape for the container `/error` path — everything that never reaches a handler because the
servlet/security filter chain answered first (`sendError`): the security 401/403 and the
no-handler 404. `detail` on that last path is deliberately the HTTP status's stock reason phrase,
never a raw exception message — unlike the handler-path exceptions (whose messages are always
developer-authored, client-facing copy), anything reaching `/error` is by construction unexpected,
so surfacing `.getMessage()` there would risk leaking internals. Frontend: `ApiError` (generic)
and `parseActionProblem`/`ActionProblem` (the action guard-ladder's richer parser, SPEC §6) both
collapsed from three shape-sniffing branches to one — no `bareSpringError` flag is needed anymore;
a Spring-Security-originated 403 (typically a missing CSRF token) is now the stable machine code
`forbidden`, distinguishable from a domain `rbac-denied` refusal by code alone.

| Endpoint | Purpose |
|---|---|
| `GET  /api/engines` | Registry + live health/capabilities/job-lane counts (no secrets). The DISPLAY surface (usability W1#4/theme T6): every entry carries `mode` (`read-write\|read-only`) and `lifecycle` (`active\|disabled\|draft\|probed\|probe_failed`; under `source: config` derived from `enabled`), and the list includes NON-ACTIVE engines so the dashboard renders them greyed-with-reason instead of silently omitting them (R-SEM-17/R-GOV-04). Fan-out, search and mutation targets keep resolving through the enabled-only registry views (`all()`/`require()`) |
| `GET  /api/resolve?q=…` | **Omnibox**: one pasted string resolved across engines in the R-SEM-04 order (process-instance → execution → task → job → business key; composite `engine:id` short-circuits to that engine); returns a disambiguation list (kind, engineId, compositeId, derived flags) + a `perEngine` reachability envelope for the "N of M engines" banner. On a scope-capable engine (`scopeType`, Flowable ≥ 6.8) where no BPMN kind matches, a co-deployed CMMN case with that id (probed by-id: `cmmn-runtime/case-instances/{id}` then `cmmn-history/…`) is returned as a read-only, non-navigable `CMMN_CASE` match (no `compositeId`/`processInstanceId`) so a pasted Case id is answered truthfully instead of a false not-found — never claimed on a pre-6.8 engine that can't discriminate scope |
| `POST /api/search` | Fan-out instance search (`SearchRequest`; URL-serializable). **Scope-filtered reads (S2, R-SAFE-17):** under `inspector.security.scope-reads-enforced` (on by default in `oidc`) the fan-out is intersected with the caller's grants at VIEWER via `ScopeGrant.overlaps` (`ReadScopeGate`); an explicitly-named out-of-scope engine is labeled "outside your access scope" on the per-engine envelope, an implicit all-engines search narrows silently. Off ⇒ legacy fleet-wide reads |
| `GET  /api/tasks?person=&engineIds=` | **Person-centric task search (#99, SPEC §4d)**: every OPEN task assigned to, or claimable by, `person`, fanned out per engine the same way as `/api/search` (virtual-thread-per-engine, bounded by `engineSlots`, per-engine timeout, `EngineResult` partial-failure envelope). Two bounded `GET /runtime/tasks` legs per engine — `assignee=` and `candidateUser=` — deduped by task id (`PersonTaskSearchService`); `PersonTaskRow.matchReason` is `ASSIGNED` or `CANDIDATE`. Same `ReadScopeGate.readableEngineIds` narrowing and `markExcludedEngines` labeling as `/api/search`. VIEWER floor, no new role tier — rows feed the EXISTING `reassign-task`/`unassign-task` verbs below unchanged |
| `GET  /api/triage[?refresh=true]` | Stage 0 dashboard: engine health strip (M1 probe state), global + per-engine status counts (query totals, `size=1`), DLQ + RETRYING jobs grouped by normalized error signature with per-engine/per-definition-version counts (sibling versions zero-filled), per-engine honesty envelope (`ok/error/dlqScan`, plus `outOfScopeDeadletters` — dead-letters belonging to a co-deployed engine sharing the job tables (CMMN), counted not dropped so the strip's dead-letter lane reconciles with FAILED; null when the engine cannot discriminate scope, gated on the `scopeType` capability ~6.8+). Served from the 20s Caffeine cache (single-flight); `refresh=true` bypasses, throttled 1/10s. The R-BAU-01 **acknowledge overlay** (`ErrorGroup.acknowledgement`: who/when/reason/ticket/expiry + `resurfaced`/`resurfaceReason`/`grownBy`) is joined onto the CACHED aggregation at render time from the `error_group_ack` store — the engine cache never carries ack state and is never busted by an ack/unack; rows from another `algoVersion` generation decorate nothing (R-SEM-03 needs-re-binding) |
| `GET  /api/triage/trends[?hours=24]` | **v2/M4 job-lane trends** (R-BAU-08): the Stage-0 sparkline series, read from the `triage_snapshot` store (§2.6) — never the live engine. `TriageTrendResponse`: one `Series` per `(engineId, lane)` with any point in the window, each an ascending-by-time list of `{sampledAt, count}`; a lane with no history yields no series (never a fabricated flat line). `hours` server-clamped to 30 days. Same openness as the dashboard (it trends the counts the open landing already shows). **Scope-filtered reads (S2, R-SAFE-17, issue #126):** `triage_snapshot` rows already carry `engineId` per the sampler's write path, so scoping is a plain `readable.contains(engineId)` row filter in `TriageTrendService` — no shared cache to protect here (unlike the dashboard/leak-views), so no separate post-cache projector. Off ⇒ legacy fleet-wide reads |
| `GET  /api/triage/leak-views` | **R-BAU-02 leak views** (usability W3-3): per-definition counts of the slow leaks that never enter a failure lane — long-RUNNING and long-SUSPENDED instances. `LeakViewsResponse`: `windows` (the exact `startedBefore` instant used per window — `activeOver30d`/`activeOver90d`/`suspendedStartedOver7d`), `definitions` (one `LeakDefinitionCount` per key, merged across every reachable engine, only where a window count > 0), `lowerBound` + `unavailableEngines`. `LeakViewService` fans out per engine on virtual threads: a cheap whole-engine pre-check, then — only if it leaks — `latest=true` definition-key enumeration (bounded `DEFINITION_CAP=500`, a lower bound past it) and count-only (`size=1`) runtime queries per (key, window). Aggregation-independent (never the grid-search plan). Age is `now − startTime` for EVERY window, SUSPENDED included: no suspension timestamp exists (R-SEM-05), so the honest predicate is `suspended ∧ startedBefore(now−7d)` and the frontend label says so. Served from the same 20s Caffeine cache as the dashboard; a down engine degrades to a named lower bound (always 200). Each count is a Stage-1 deep link (URL primacy). **Scope-filtered reads (S2, R-SAFE-17, issue #126):** `LeakDefinitionCount` carries a `countsByEngine` breakdown so `LeakViewScopeProjector` can post-cache render-time narrow it exactly like `TriageScopeProjector` does the dashboard — every window count decomposes per engine, so a scoped slice is honestly RECOMPUTED from survivors (never nulled, unlike the dashboard's DL/retrying split); a definition touching no readable engine is dropped, one only partially in scope sets `partial=true`. `unavailableEngines` is narrowed to readable engines (no topology leak); `lowerBound` carries over unchanged (a fleet-wide honesty floor, not scope-dependent). Off ⇒ legacy fleet-wide reads |
| `GET  /api/triage/engines/{engineId}/out-of-scope-deadletters` | **Case Inspector Phase 1** (first slice): the drill behind `outOfScopeDeadletters` — read-only enumeration of the co-deployed CMMN engine's dead-letter jobs (`CmmnDeadLetterJob`: readable case type — `caseDefinitionKey`/`caseDefinitionName` resolved by a bounded distinct-id lookup against `cmmn-repository/case-definitions/{id}` since a CMMN `caseDefinitionId` is a bare uuid — plus failing element, exception snippet, case-instance id, retries) from the `/cmmn-api` sibling context, keeping only rows with a non-null `caseInstanceId`. Bounded by `dlq-scan-cap`, paged, `truncated` lower bound. VIEWER floor; capability-gated (`scopeType`, Flowable ≥ 6.8 — a pre-6.8 engine is refused with a ProblemDetail). No corrective action (CMMN actions are a later phase) |
| `GET  /api/triage/engines/{engineId}/cmmn-scope` | **Case Inspector Phase 1** (scope-typed lane facet): `CmmnScopeFacet` — the CMMN case lane counts (`CmmnLaneCounts`: `active`/`failed`/`completed`/`terminated`, **no `suspended`** — cases can't suspend) plus the FAILED-lane detail (the `out-of-scope-deadletters` enumeration, inlined). `active`/`completed`/`terminated` are count-only (`size=1`) `cmmn-history/historic-case-instances?state=` queries, each degrading to `null` (unknown, never a misleading `0`) on its own failure; `failed` is the distinct `caseInstanceId`s among the dead-letter jobs (a lower bound when that scan `truncated`). Same VIEWER floor + 6.8 gate as the deadletters drill (reuses it, so the gate runs before any lane query). Read-only. The frontend drives its lane tiles off a dedicated `CMMN_STATUSES` const, never the SUSPENDED-carrying BPMN `ALL_STATUSES` |
| `POST /api/triage/error-groups/acknowledge` · `…/unacknowledge` | **R-BAU-01 error-group acknowledge** (usability W3-2): BFF-store-only mutations — no engine call, ever (muting a card is not a corrective action on engine state). Body is coordinates only: `{signatureHash, algoVersion, reason (mandatory ≥10 both verbs), ticketId?, expiresAt? (ISO instant, future)}` — the BFF resolves the group's live engine × definition slices and their baselines (count + max FAILING version, zero-filled siblings ignored) from the SAME cached aggregation the operator is looking at, and persists one `error_group_ack` row per slice (V15; re-acknowledge replaces the signature's rows wholesale — fresh baselines). Rails: OPERATOR floor at the door + programmatic OPERATOR re-check per slice ENGINE; fail-closed `recordConfigEvent` (`error-group-acknowledge`/`error-group-unacknowledge`, reason in the COLUMN) with store compensation on audit failure (503, R-AUD-10); never auto-retried. Refusals: stale `algoVersion` ⇒ 409 `error-class-algo-mismatch`; drained/absent group ⇒ 409 `error-group-absent`; un-ack of an unacknowledged group ⇒ 409 `error-group-not-acknowledged`; short reason / bad expiry ⇒ named 400s. Resurface predicate (`ErrorGroupAckPolicy`, threshold default +20% via `inspector.triage.ack-resurface-threshold-pct`): new failing version or uncovered slice ⇒ `new-version`, growth past baseline·(1+pct) ⇒ `grew`, passed expiry ⇒ `expired` |
| `GET  /api/instances/{engineId}/{id}` | Vitals (historic-first — a completed instance renders, never 404s): identity, definition key/name/version, status flags + primary chip, **`terminationReason`** (#118/#105 status honesty — an ended instance that was TERMINATED/deleted carries the engine's `deleteReason` or humanized historic `state`; the status enum stays COMPLETED to keep facets/counts stable, but the detail chip DISPLAYS TERMINATED not COMPLETED), current activities (unfinished historic activities ∪ runtime execution positions — a dead-lettered async task has NO unfinished activity row, its execution carries the token), why-stuck strip (exception first line, failing activity, retries state), waiting-for (event subscriptions + pending timers), `telemetryUrl` (the engine's `telemetry-url-template` rendered with url-encoded values; absent template → no field) |
| `GET  /api/instances/{engineId}/{id}/explain-status` | **"Explain this status"** (R-L3-01, SPEC §3) — the falsifiable, re-derived-on-demand `StatusEvidence`: the `plan` shape chosen and why (`ENDED_SHORT_CIRCUIT` — an ended instance is COMPLETED, lanes not scanned; `LIVE_LANE_SCAN` — open instance, all failure lanes + a bounded call-activity descendant walk), the raw `legs` (each engine call's `label`/`method`/`url`/`requestBody`/`status`/`durationMs`/`asOf`/`source`, captured by a thread-local `EngineCallRecorder` for the span of one derivation), and per-flag `findings` (`flag ⇐ source`, the job/child id, and a `deepLinkInstanceId` for the failing call-activity child). Re-runs the SAME `InstanceDetailService.deriveStatus` the vitals chip uses (never disagrees with the chip), labeled `rederived=true`/`rederivedAt` (the original bytes are never retained). VIEWER floor; breaker-wrapped like every other read |
| `GET  /api/instances/{engineId}/{id}/variables[/{name}]` | The TYPED ledger (R-UXQ-13), READ-only: engine type verbatim next to the typed value, process scope + per-execution locals, HISTORIC projection for completed instances; serializables read-only; list responses byte-capped at 256 KiB with the on-demand full-value fetch `GET …/variables/{name}` (scope-aware: `?executionId=` reads the execution-local variable `scope=local`, else process scope). The EDIT is not a PUT here — it is the tier-1 `edit-variable` verb via `POST …/actions/edit-variable` (row above), always on the fetched full value: compare-and-set — `expectedOldValue` ⇒ 409 + fresh re-render on mismatch (R-SEM-09). **Scope-aware (M4, 2026-07-08):** `variable.executionId` present ⇒ the read/CAS/write leg targets that execution-local ("step-local") variable (`GET/PUT /runtime/executions/{execId}/variables/{name}` with `scope=local`, ownership-checked against the instance); absent ⇒ process (case) scope. UI contract: SPEC §4a |
| `GET  /api/instances/{engineId}/{id}/jobs` | Four lanes (executable/timer/suspended/deadletter), typed rows; stacktrace on expand via `GET …/jobs/{jobId}/stacktrace?lane=` (plain text) |
| `GET  /api/instances/{engineId}/{id}/jobs/external-worker` | **Fifth queue** (v1.x #7, read-only): external-worker jobs with the worker lock (`lockOwner`/`lockExpirationTime`). Capability-gated (Flowable ≥ 6.8) — a pre-6.8 engine gets a ProblemDetail, never an empty list. Sourced from the External Worker REST API at the `/external-job-api` sibling context (the management API has no external-worker endpoint), derived from base-url by convention; the active count also rides the vitals summary (`InstanceDetail.externalWorkerJobs`) |
| `GET  /api/instances/{engineId}/{id}/tasks` | User tasks, completed AND open, one ledger: historic-first (`/history/historic-task-instances` — an open task is a historic row with a null `endTime`) unioned with `/runtime/tasks` (live assignee, `suspended`; covers dialed-down task history); derived `state` = COMPLETED ▸ SUSPENDED ▸ ACTIVE; engine-total + `truncated` honesty |
| `GET  /api/instances/{engineId}/{id}/hierarchy` | Call-activity tree BOTH directions: up-walk to the root (`superProcessInstanceId`, cycle-guarded), BFS down; depth cap 10 (registry), breadth cap 50 rendered/node (R-SEM-19) with exact `childTotal` from the query total, per-node dead-letter markers |
| `GET  /api/instances/{engineId}/{id}/timeline` | Historic activity instances (Gantt rows, `startTime` asc, top-level truncation marker). A call-activity row nests the called instance's own activities as a `children` sub-lane, recursed under the hierarchy caps (depth 10, breadth 50/node, 500-node budget) with a `calledProcessInstanceId` cycle guard; a node truncated by any cap carries `isCapped`. Failing/unfinished nodes carry `liveJobState` (`FAILED`=dead-letter, `RETRYING`=failing-with-retries) joined from the runtime lanes — a dead-lettered async node, whose history row rolled back with its transaction, is **synthesized from the lanes** (phantom-node union) so the failure is never invisible |
| `GET  /api/instances/{engineId}/{id}/audit` · `…/notes` | Per-instance action history + notes (CRUD) |
| `GET·PUT /api/views` · `DELETE /api/views/{id}` | **v2/M4 per-user Saved Views** (SPEC §8) — the server-backed replacement for the v1 localStorage store. `PUT` upserts by name (re-saving a name replaces it); `DELETE` is ownership-scoped (404 if absent or owned by another). Every route is keyed on `authentication.getName()` server-side — never a client-supplied owner — so a user only ever sees/mutates their OWN rows. VIEWER floor. System views (R-SEM-05 relative windows) stay client-derived, not stored |
| `GET·POST /api/team-views` · `PUT /api/team-views/{id}` · `POST …/{id}/unpublish` | **v2 team/shared Saved Views** (SPEC §8, SHARED-VIEWS.md, R-SEM-24/R-SAFE-16 — ★ built, S1–S6 landed) — a **separate governed `shared_view` store**, not a flag on `saved_view`. `GET` returns the caller's `overlaps()`-visible canon (declutter, NOT a security boundary); `POST` publishes via **snapshot-copy** (create-only), gated by `covers(OPERATOR, scope)` where the scope is **derived from the view's `search` `engineIds`** (wildcard scope ⇒ ADMIN-on-scope; publish refused if the search reaches outside the declared scope). `PUT`/unpublish are author-or-scope-ADMIN moderation (default verb unpublish); **unpublish requires a reason ≥10 from EVERY caller — author included — bound to the audit row's reason column** (usability W2 #3; the former reason-free `DELETE /{id}` alias is removed with it); every transition audited fail-closed via `recordConfigEvent` (R-AUD-10, same tx). Concurrent-publish → 409. VIEWER floor to read |
| `GET·POST /api/recents` | **v2/M4 per-user Recent Searches** (SPEC §8): `POST` records a just-executed search (deduped by search, capped at 10 newest-first in the BFF) and returns the caller's updated list. Same per-user ownership + VIEWER floor |
| `POST /api/instances/{engineId}/{id}/actions/{verb}` | Verb catalog (SPEC §5); guard tier + reason enforced server-side. Includes `reassign-task` / `unassign-task` (v1.x #6, tier 1 / OPERATOR): server-fresh task restatement gates on the LIVE task (`GET /runtime/tasks/{taskId}` — a completed task 404s → "not active"), then one `PUT /runtime/tasks/{taskId}` `{"assignee":…\|null}`; the audit payload records old→new assignee |
| `POST /api/instances/{engineId}/{id}/actions/{verb}/curl` | "Show as cURL" (v1.x #6): SERVER-computed command for the proposed action — same RBAC door as execute, but touches neither engine nor audit; renders THIS BFF's verb URL + JSON body + placeholder credential (never a live token, never the engine path). UI shows it verbatim |
| `POST /api/definitions/{engineId}/{definitionId}/actions/{verb}` | Definition-scoped verbs (`suspend-definition` / `activate-definition`, tier 3) — same guard rails, typed token = the definition key. **Tier-3 dangerous-set freshness (v2 IdP-Security §5):** a stale OIDC session is refused with a `401` + `reauth-required` marker (`X-Reauth-Required` header) BEFORE the token/reason/audit rails; dev Basic + break-glass sessions are exempt |
| `POST /api/instances/{engineId}/{id}/change-state/preview` | v1.1 flow surgery: the **BFF simulation** (Flowable has no change-state dry-run — SPEC §5 "not offered"). Runs every blocking guard against live state + the parsed model — capability ≥6.4 (fail-closed while unprobed), writable engine, instance running + NOT suspended (409 activate-first), activities exist, sources currently active per `/runtime/executions`, **MI-body block both directions (422)** — and returns the EXACT `{cancelActivityIds, startActivityIds}` body execute will send, a plan-as-a-sentence summary and non-blocking warnings (`parallel-branch-target`). Read-only: no audit row, no Postgres dependency |
| `POST /api/instances/{engineId}/{id}/change-state/execute` | The token move (tier 2; OPERATOR floor, **ADMIN on prod** — §5): re-plans server-fresh (never trusts an earlier preview), then the full M4 rails — protection guard, reason ≥10 always, fail-closed audit PENDING carrying source/target activities + the verbatim REST payload, ONE `POST …/change-state`, honest close-out |
| `POST /api/instances/{engineId}/{id}/restart` | Restart-as-new (tier 2, OPERATOR): refuses running instances (409) — historic `endTime` required; explicit version fork `pinDefinitionVersion` (true = original `processDefinitionId`, verified still deployed; false = definition KEY → latest); carries portable **global** historic variables (task-locals/execution-locals are not instance state; intrinsics like `initiator` and non-round-trippable types land in a reported `skippedVariables` map — never a silent drop; >500 variables = refused, a partial copy would be a lie); audited against the ORIGINAL instance id, response carries the new instance id |
| `POST /api/instances/{engineId}/{id}/protect` · `/unprotect` | R-SAFE-05 instance-scope write path (issue #165) — BFF-store-only, no engine call: ADMIN-per-engine floor, reason ≥10 required either way, fail-closed config-event audit (write→audit→compensate). `protect` on an already-protected composite ID (or `unprotect` on an unprotected one) is a named `409` — mark/unmark is insert/delete, never an in-place reason update. Deliberately NOT behind the R-SAFE-07 reauth gate (that sweep is engine-mutating tier-3 verbs + bulk + mapping writes; this is a locally reversible governance flag, same shape as `ErrorGroupAckService`/`SharedViewService.unpublish`, neither of which reauth-gates either) |
| `POST /api/definitions/{engineId}/{definitionKey}/protect` · `/unprotect` | R-SAFE-05 definition-key-scope write path (issue #172, the "or definition key" half deferred from #165) — same rails as the instance-scope door above, a sibling `protected_definition` table (`engine_id`+`definition_key` PK — a nullable-column overload of `protected_instance` would be worse modeling for a genuinely distinct concept). Every instance of the key, present and future deployed versions alike, is refused below the ADMIN floor |
| `POST /api/instances/{engineId}/{id}/migrate/preview` | v2 instance migration (SPEC §5 tier 3): the **BFF static auto-map check** — Flowable's REST API exposes NO migration validator (P0 spike 2026-07-09), so this is an Inspector estimate, NEVER an engine validation (`engineValidated:false`). Runs every blocking guard (ADMIN floor, migration cap ≥6.5, writable, instance running + NOT suspended, target resolve+pin: same-key else 422, cross-tenant 422, same-version 409) then diffs the instance's ACTIVE activities against the target model by id + type + nesting → `AUTO_MAPPED`/`FLAGGED_UNMAPPED`/`TYPE_CHANGED` (loud silent-corruption warning)/`NESTING_CHANGED`, the exact `{toProcessDefinitionId, activityMappings}` document execute would send, an `activityStateDigest`, the honesty banner. Read-only: no audit row |
| `POST /api/instances/{engineId}/{id}/migrate/execute` | The migration (tier 3; ADMIN floor every env, typed **business-key** confirm on prod): re-plans server-fresh, asserts the §5 compare-and-set (`expectedFromDefinitionId` + `expectedActivityStateDigest`, both MANDATORY — 400 `preview-required` if absent, 409 if the instance moved since preview), then protection guard, reason ≥10, refuses if any activity is still `FLAGGED_UNMAPPED` (422) or >200 active executions (422 `instance-too-large`), fail-closed audit PENDING carrying the `migrate/v1` document (`engineValidated:false`, from/to pinned, `activityStateDigest`), ONE `POST …/migrate`, never retried (post-dispatch timeout ⇒ UNKNOWN + verify-now), re-reads the instance to record the observed definition. IRREVERSIBLE |
| `GET /api/definitions/{engineId}/{key}/versions` | The migration on-ramp (cohort visibility): every deployed version of a process key with its RUNNING instance count ("37 running on v3 · latest v5") — count-only Stage-0 (`size=1` runtime queries per version, never a row fetch). Read-only, VIEWER floor, no capability gate; 404 on an unknown key |
| `GET  /api/instances/{engineId}/{id}/diagram` | BPMN XML exactly as deployed (definition → deploymentId + resourceName → `/repository/deployments/{deploymentId}/resourcedata/{resourceName}`) + active/dead-letter activity-id sets for the bpmn-js markers |
| `POST /api/bulk` | v1 (landed): `{verb, items:[{engineId,instanceId,jobId?}], reason?, ticketId?, continuedFrom?}` — creates a PERSISTED tracked job (cap 200; verb whitelist retry-job/suspend/activate/trigger-timer — destructive bulk goes through the tier-4 wizard's OWN door below, a separate stricter verb whitelist); protected targets settle `skipped_protected` at submit; ONE fail-closed envelope audit row + one per item; per-item fan-out through the full single-target guard chain. **Break-glass attribution (S7):** the per-item rows carry `breakGlass=true` when the job was submitted under a sealed-account session — a worker virtual thread has an empty `SecurityContextHolder` (identity is threaded, not inherited), so `CorrectiveActionService` sets a `BreakGlassActor` dispatch-thread marker from the passed auth (mirroring `ForwardedActor`) and `AuditService` reads it as a fallback to the context. **Dangerous-set freshness (v2 IdP-Security §5):** a stale OAuth2 session is refused `401 reauth-required` ONCE at submit (all FOUR bulk doors — selection/error-class/filter/destructive — converge on the same gate) — never per persisted item; dev Basic + break-glass exempt. Dispatch is per ENGINE (v1.x #2): an in-flight permit pool per engine (default 4, shared across concurrent jobs) + a mandatory 250 ms stagger between dispatch starts (`inspector.bulk.engine-permits` / `inspector.bulk.stagger-ms`) — engines proceed independently, one slow engine never starves the others. retry-job resolves the instance's CURRENT dead-letter jobs at dispatch time (built-in precondition recheck; none left ⇒ `skipped`). Every job/item transition publishes an id-only `BulkJobChangedEvent` (the SSE feed) |
| `POST /api/bulk/error-class` | v1.x #1 (landed): the triage group retry — body is the group's COORDINATES only `{signatureHash, algoVersion, processDefinitionKey, definitionVersion, engineId?, reason (mandatory ≥10), ticketId?}`; the BFF re-resolves the FAILED members itself via the same capped failure-lane scan + signature-refinement bridge the cards aggregate from (never a client ID list, never the grid plan) and delegates to the `/api/bulk` machinery above — cap, per-item RBAC, protected auto-exclusion, fail-closed envelope + per-item audit all unchanged. Refusals: stale `algoVersion` ⇒ 409; zero members ⇒ 409 `error-class-drained` (never a zero-item job); any degraded engine leg ⇒ 502 fail-closed; >200 ⇒ `bulk-cap-exceeded`. Envelope payload carries the group provenance (`errorClass`: hash, algoVersion, defKey:vN, engineId, resolvedCount, scanTruncated) |
| `POST /api/bulk/filter` | v1.x #2 (landed): select-all-matching-filter — body `{criteria: SearchRequest, verb, reason (mandatory ≥10), ticketId?}`; server-side re-resolution is BINDING: the BFF re-executes the SAME M2a plan **paged to exhaustion** (`SearchService.resolveAllMatching`; the criteria's display `pageSize` is stripped) and delegates to the `/api/bulk` machinery under the **5,000** query-bulk cap (`BulkJob.FILTER_ITEM_CAP`, V3 migration; every other entry keeps 200). Refusals: missing/short reason; statuses absent or containing COMPLETED (`filter-statuses-required` / `filter-completed-not-actionable`); zero matches ⇒ 409 `filter-drained`; degraded engine ⇒ 502 fail-closed; truncated failure-lane scan ⇒ 400 `filter-scan-truncated` (a binding "all matching" never silently acts on a subset); >5,000 ⇒ `bulk-cap-exceeded`; a MIXED-plan candidate pool already known over the cap degrades that engine honestly instead of enumerating it. Envelope payload carries `filter: {criteria, resolvedCount}` plus the full resolved target list — recorded BEFORE dispatch |
| `POST /api/bulk/destructive/preview` · `POST /api/bulk/destructive` | **Tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100) — landed, `terminate-delete` only.** Same `{criteria: SearchRequest, verb, reason, ticketId?, confirmedCount?}` body on both; `preview` is read-only (no audit row, mirrors migrate/change-state preview) and MUST NOT be trusted by `submit` — every guard, including the resolution itself (`BulkFilterResolution.resolveExhaustively`, shared with `/api/bulk/filter`), re-runs server-fresh at submit. Door floor is **ADMIN** (not the coarse RESPONDER floor above every other bulk door); `DestructiveBulkService` ALSO hard-gates ADMIN per target engine before any resolution work (fail-fast, never burns a job's per-item report on RBAC denials). **Refuse-unscoped** (`bulk-destructive-unscoped`): status + engine alone is not a narrowing filter — a definition key, business key, error class, activity, variable filter, or time window is mandatory. On any PROD-touching resolved scope, `confirmedCount` must match the FRESH resolution exactly — a mismatch is a `409 bulk-count-drift` (`{confirmedCount, actualCount}`), never a silent act-on-whatever-it-resolved-to. Delegates to a SEPARATE, stricter verb whitelist (`DESTRUCTIVE_BULK_VERBS = {terminate-delete}`) via `BulkJobService#submitDestructive`, which still converges on the SAME private `submit()` as the other three doors — the dangerous-set reauth gate applies here too. Per-item dispatch goes through `CorrectiveActionService#executeBulkItem` (not `execute`) — it skips the tier-3 reauth re-check (already covered once at submit; re-checking against a moving clock would 401 the tail of a long job, R-SEM-10) and the per-instance typed-confirm-token restatement (meaningless across N items; the wizard's own typed-count gate is the bulk-shaped equivalent). `delete-deadletter`-at-scale needs a job-level (not instance-level) scope resolver — a documented fast-follow, not silently dropped |
| `GET  /api/bulk/events` | SSE (v1.x #2, live-ui-sse doctrine): ONE stream per browser; id-only `bulk-job` events (data = job UUID) bridged from `BulkJobChangedEvent` + a 15 s `ping` heartbeat; no initial event on connect (clients fetch their own first state); a failed write drops the emitter, never `complete()`s it. Auth = session cookie (EventSource cannot send Authorization; the dev chain persists Basic auth into the HTTP session). On shutdown the hub completes every stream BEFORE the web server's graceful-shutdown lifecycle phase — an open stream must not hold the 30 s grace period hostage. Engine-health events join this stream in a later slice |
| `GET  /api/bulk[/{id}]` · `POST /api/bulk/{id}/cancel` | Job state machine (`PENDING→RUNNING→COMPLETED\|CANCELLED\|INTERRUPTED`) + persisted per-item report (tallies on the list read, items on the detail read); cancel stops DISPATCHING — sent items keep their outcome; startup sweep marks stale PENDING/RUNNING → INTERRUPTED (dispatched→`unknown`, pending→`not_run`, never re-fired); "continue as new job" = a fresh submit with `continuedFrom` |
| `POST /api/bulk/{id}/items/{ordinal}/verify` | Verify-now (R-SAFE-09): re-runs the verb's precondition predicate against LIVE engine state and reclassifies `unknown` with evidence — never re-fires the mutation |
| `GET  /api/me` | Auth hint for greyed-never-hidden UI: username + highest ladder role per engine, resolved through the SAME `RbacAuthorizer` path the guards use; the `registryAdmin`/`accessAdmin`/`breakGlass` fleet+session flags; and a `reauth` freshness hint (`required`/`freshUntil`/`windowSeconds`, from `DangerousActionReauthGate`) so the SPA runs the re-auth interstitial at modal open — never after the confirm token is typed; plus `sessionExpiresAt` (session birth + effective absolute cap, break-glass 4 h override honoured) driving the warn-before-guillotine countdown banner (presentation only — the per-request BFF check and the `AbsoluteSessionTimeoutFilter` stay the gates) |
| `GET  /api/instances/{engineId}/{id}/nearest-sibling` | **Sibling diff** auto-suggest (v1.x #5, landed): the smart default — most recently COMPLETED instance of the same `processDefinitionId` (`finished:true` + `sort=endTime desc`, reached an end event, not dead-lettered); returns `found:false` (not an error) when a fresh version has no completed run. VIEWER floor, historic queries only |
| `GET  /api/instances/{engineId}/{id}/diff/{siblingId}` | **Sibling diff** three-way `SiblingDiffResponse` (v1.x #5, landed): variable deltas on the 256 KiB capped projection (an over-cap pair ships `DIFFER_BEYOND_PREVIEW`, never a full fetch), path divergence as `onlyInSubject`/`onlyInSibling`/`common` activity-id sets (drive the diagram stroke overlay — ids only, no hue), per-activity timing deltas (loops sum; the stalled open step carries a null duration + `subjectUnfinished`). A manually-picked cross-definition sibling still diffs, flagged `sameDefinition:false`. VIEWER floor, historic only |
| `GET  /api/cases/{engineId}/{caseInstanceId}` | **Case Inspector Phase 2** (polymorphic CMMN Stage-2 detail — `CaseDetailService`): case vitals, historic-first (a completed case renders, never 404s), runtime-enriched for live state. `CaseDetail`: case-def key/name/version (resolved from the bare-uuid `caseDefinitionId`), state (ACTIVE/COMPLETED/TERMINATED — **no SUSPENDED**), business key, times, `superProcessInstanceId` (the calling BPMN process, id only), and a `failing` summary (dead-letter count + first exception + the bounded list of dead-letter `jobs` — id/element/exception/retries — so the Phase-3 retry can target each) when a plan item dead-lettered. VIEWER floor; capability-gated (`scopeType`, Flowable ≥ 6.8 — pre-6.8 refused with a ProblemDetail) |
| `GET  /api/cases/{engineId}/{caseInstanceId}/diagram` | Raw CMMN 1.1 XML (case-def `resource` → `cmmn-repository/deployments/{id}/resourcedata/{name}`) for a `cmmn-js` viewer, plus `graphicalNotationDefined` (false ⇒ the UI shows an honest no-layout state — `cmmn-js` does not auto-layout) and the plan-item marker id sets (`activePlanItemElementIds`/`failedPlanItemElementIds`, keyed by the plan item's `elementId` — the CMMN DI shape key, NOT a job row's `elementId` which is the plan-item DEFINITION id) |
| `GET  /api/cases/{engineId}/{caseInstanceId}/plan-items` | The plan-item state-machine timeline (`CasePlanItems`): the runtime plan items with lifecycle timestamps + a `liveJobState` (`FAILED`=dead-letter parked, `RETRYING`=failing-with-retries, joined by `planItemInstanceId`==the plan item's `id`), stage nesting via `stageInstanceId`. **RUNTIME-ONLY** on 6.8 (`cmmn-history/historic-plan-item-instances` 404s) — an ended case returns `available:false` + a reason, never a fabricated empty timeline; bounded + `truncated` per the iron rule |
| `POST /api/cases/{engineId}/{caseInstanceId}/actions/{verb}` (+`/curl`) | **Case Inspector Phase 3** (CMMN corrective actions): the case-scoped sibling of the instance verb route, sharing the SAME `CorrectiveActionService` dispatcher via an `ActionScope.CMMN` seam — all rails (audit on the caseInstanceId, RBAC, reason, protected-instance guard, fail-closed audit, no-auto-retry, server-computed cURL) unchanged. Verbs: `retry-job` (tier 0 / RESPONDER) and `delete-deadletter` (tier 3 / ADMIN). Two scope seams: the server-fresh restatement reads the job by-id from `cmmn-management/deadletter-jobs/{id}` (cap-free) keying ownership on `caseInstanceId`, and the one engine call is the `/cmmn-management/deadletter-jobs/{id}` sibling — `POST … {"action":"move"}` for retry, `DELETE …` for delete (both byte-identical to the process-api shapes, HTTP 204). Capability-gated (`scopeType`, ≥6.8); any non-CMMN verb is refused (`verb-not-cmmn-scoped`) |
| `POST /api/playbooks` · `GET /api/playbooks` · `POST /api/playbooks/{id}/replay` | **Remediation playbooks** (v2): record = distill the exemplar's audit rows into a verb sequence; replay = a bulk job whose items run the sequence with per-step precondition rechecks |
| `GET  /api/audit` | Global operations log (filterable) |
| `GET  /api/audit/export` | Operations-log **CSV export** (R-AUD-08): the SAME filters as `GET /api/audit` (actor/action/engineId/ticketId/since), streamed as `text/csv` in repository pages (500/chunk, 10 000-row ceiling), attachment disposition. Skeleton columns only — payload/response bodies never travel (R-AUD-03); every text cell RFC-4180 + formula-escaped (R-OPS-08, `Csv.cell` shared with the access-review export). VIEWER floor |
| `GET  /api/stream` | RESERVED (v1.x): engine-health SSE — bulk-job progress already streams on `GET /api/bulk/events`; whichever lands second consolidates the two into one app stream (the BFF is the event source; no engine polling relay) |
| `GET·POST /api/admin/engines` · `PUT·DELETE /api/admin/engines/{id}` · `POST …/{id}/probe`·`/enable`·`/disable`·`/purge` | **Registry CRUD** (v2, REGISTRY-CRUD.md §9): runtime engine lifecycle. `GET` returns the full registry incl. tombstoned rows + auth/timeout/cap config + secret-ref **presence** (never values — distinct from the secret-free, display-shaped `GET /api/engines`, which carries live rows' health + `mode`/`lifecycle` only). `POST`/`PUT` add/edit (all fields except the immutable `id`), SSRF-validated (resolve-then-pin, egress allowlist, metadata/private denylist), audited fail-closed before/after. `probe` runs the **read-only** version+capability probe (no mutating call, transitions DRAFT→PROBED). `enable?mode=read-write` on a prod engine ⇒ typed token + four-eyes (R-SAFE-08). `DELETE` soft-tombstones (requires DISABLED); `purge` hard-removes after retention. ALL gated `@rbac.canAdministerRegistry` (REGISTRY_ADMIN, fleet-scoped — per-engine ADMIN does not confer it) |
| `GET  /v3/api-docs` | The OpenAPI contract (springdoc, key-ordered + fixed info version for deterministic codegen); the only unauthenticated API route besides health — it describes the surface, never data. Source of `frontend/src/api/schema.d.ts` via `npm run gen:api` (R-SEM-15) |

The BFF **whitelists** engine paths — never a blind proxy. flowable-rest authorization is
effectively binary (`access-rest-api`), so **BFF RBAC is the only real permission layer**;
nobody may "simplify" by exposing an engine directly.

## 5. Cross-cutting

- **Auth (dual profile from M4):** dev = form/basic + session; prod = **OIDC**
  (roles from a claim). One session-stateful BFF instance; engine credentials live only in
  the BFF process env. **v2 IdP-Security (IDP-SECURITY.md)** wires OIDC for real — **S1 built**: the
  `oidc` profile carries a real `oauth2-client` registration (Entra ID pilot / Keycloak; issuer
  pinned to one tenant, PKCE, tokens server-side-session-only) and a single authoritative
  `OidcGroupResolver` (issuer pinning, non-array-claim rejection, Entra groups-overage
  detect-and-legibly-fail) owns the one place a token's groups are trusted, at login and at check
  time — hardens the session/transport posture (**S2 built**: idle+absolute session caps,
  fixation scoped (`changeSessionId` on `oidc`, `none` on dev-Basic so the SSE isn't orphaned),
  `HttpOnly/Secure/SameSite=Lax`, the header set + **fail-closed verb gate**
  (`canExecute .orElse(false)` + a pre-`@PreAuthorize` verb-existence 404), CSP enforcing (S5),
  HSTS opt-in, CORS off — R-SAFE-07/R-OPS-16). **CSRF** is `CookieCsrfTokenRepository` +
  `X-XSRF-TOKEN` echo (basic-per-request exempt); a `CsrfCookieFilter` MATERIALIZES Spring 6's
  deferred token on every response so the `XSRF-TOKEN` cookie is reliably PRIMED — otherwise the
  SPA's first post-login unsafe request raced an unwritten cookie into a bare 403 (#118 items 1&2).
  It also builds
  break-glass (sealed local ADMIN on a distinct `/break-glass` chain that works when the IdP is
  down; ADMIN-global never fleet; audit degrades to a local file sink when Postgres is also down).
  **Brute-force throttle (S4):** the `/break-glass` door is protected by a self-healing PROGRESSIVE
  DELAY (`BreakGlassThrottle`) — never a hard lockout, which would brick the emergency door during
  an outage: first two failures free, then `429 + Retry-After` doubling 1s→30s; a correct password
  resets it and it self-expires after 15 min idle; failures alert on a sustained burst.
- **Security alerts:** `SecurityAlertChannel` fires on every `ACCESS_ADMIN` change, break-glass
  login, and break-glass brute-force — an always-on greppable log marker PLUS (S3) a real
  fire-and-forget POST to an env-ref webhook (`AlertWebhookSender`) when configured; under `oidc` an
  unconfigured webhook is a boot warning. A dead pager never blocks the security flow it reports on.
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
  resolves the acting user's scope set against the target of every call. **Reads are scoped
  too (S2, R-SAFE-17):** mutations and single-instance detail were always scope-checked, but
  the fan-out read aggregators (search, the triage dashboard, leak-views and trends — issue #126
  closes out the last two) intersect the caller's grants at VIEWER via `ScopeGrant.overlaps`
  (`ReadScopeGate`) behind `inspector.security.scope-reads-enforced` (default off — the dev ladder
  is global-scoped; on under `oidc`). Search resolves the readable set on the request thread; the
  triage dashboard and leak-views (both served from a shared single-flight Caffeine cache) apply it
  as a per-request POST-cache render-time projection (`TriageScopeProjector`/`LeakViewScopeProjector`)
  — never inside the fan-out or the background `SnapshotSampler` (no auth ⇒ would empty the shared
  snapshot); trends has no such cache to protect (a direct per-request DB read), so it scopes inline
  as a plain row filter in `TriageTrendService`. §4c's `/api/triage/leak-views` and
  `/api/triage/trends` entries above have the per-endpoint detail.
  **`REGISTRY_ADMIN`** (v2, REGISTRY-CRUD.md §7) is an **orthogonal fleet-level grant**, not a
  ladder rung — you cannot scope "add an engine" to an engine that does not exist. It maps
  from its own OIDC group, is checked by `rbac.canAdministerRegistry`, and repoints the credential
  vault; per-engine ADMIN does not confer it and break-glass does not grant it.
  **`ACCESS_ADMIN`** (v2, IDP-SECURITY.md §9) is the **apex** orthogonal fleet grant — it
  administers the group→scope mapping itself, including the assignment of `REGISTRY_ADMIN` and of
  `ACCESS_ADMIN`, so it is strictly *above* `REGISTRY_ADMIN`; checked by `rbac.canAdministerAccess`,
  never conferred by ADMIN/`REGISTRY_ADMIN` or break-glass, and guarded by four-eyes on any
  effective-grant widening (self-widen, wildcard-breadth `≥OPERATOR`, any fleet create, any fleet
  removal) plus a ≥2-independent-`ACCESS_ADMIN` invariant. **The live gate fails closed**:
  `RbacAuthorizer.canExecute` authorizes only *known* verbs (an unknown verb path is never
  authorized-by-default), and the dangerous set (tier-3 verbs + bulk + mapping writes) runs on a
  forced-re-authenticated principal (IDP-SECURITY.md §5). The inbound half is `DangerousActionReauthGate`:
  it reads the OIDC session's `auth_time` and refuses a stale/absent-`auth_time` session with a
  `401 reauth-required` challenge on every dangerous entry — tier-3 verbs (in
  `CorrectiveActionService.execute`, before reason/typed-token/audit), **bulk submit** (once at the
  `BulkJobService#submit` convergence all three doors funnel through — never per persisted item, a
  bulk job outlives its session, R-SEM-10), and **mapping writes incl. the four-eyes approve**
  (`AdminAccessController`, before the file-pin 409 — the approver's independence test runs on fresh
  membership). The SPA re-auths at verb intent and replays; non-OIDC sessions (dev Basic re-auths
  every XHR; break-glass can't bounce a down IdP) are exempt.
- **Attribution:** engines see the shared service account; Flowable's `ACT_HI_*` tables
  therefore attribute mutations to it. The BFF audit log is the sole authoritative
  human-attribution record. Engines with `forward-user-header: true` additionally receive
  `X-Forwarded-User: <username>` on mutating calls for an engine-side interceptor (§6).
- **Persistence (Postgres, M4+):** audit log (append-only), instance notes, bulk-job state +
  per-item reports, the `triage_snapshot` job-lane time-series (v2/M4, range-partitioned +
  drop-partition retention — §2.6), error-group acknowledgments (`error_group_ack`, V15 —
  R-BAU-01: one row per signature × engine × definition-key slice carrying the resurface
  baselines; state-not-history, the config-event audit rows are the history),
  per-user Saved Views + Recent Searches (`saved_view` /
  `recent_search`, v2/M4 — keyed to the authenticated user; superseded the v1 localStorage
  store, with a one-time client backfill), **team/shared Saved Views** (`shared_view`, v2 — ★ built, S1–S6 landed,
  design locked in SHARED-VIEWS.md — a **separate governed store**, NOT a flag on
  `saved_view`: publish = snapshot-copy, `covers()`-gated by a scope derived from the view's own
  `search` string, `overlaps()`-scoped read-visibility as declutter, lifecycle audited via
  `recordConfigEvent`; dangling-scope canon renders greyed-with-reason via R-SEM-17 id→name, never
  a clean all-clear), the **engine registry itself** (`engine_registry`,
  v2 Registry CRUD — DB-authoritative once seeded from YAML; REGISTRY-CRUD.md), and the
  **group→scope mapping** (`group_scope_grant` + `group_fleet_grant`, v2 IdP-Security — **S3 built**:
  DB-authoritative once seeded from the mounted YAML, `mapping-source: file|db` pin, a
  `MappingSource` seam — `FileMappingSource`/`DbMappingSource` by profile — that `RbacAuthorizer`
  now reads for both ladder and fleet grants; the env-bootstrap `ACCESS_ADMIN` apex overlay + the
  ≥1/≥2-apex boot invariant are the lock-out floor; IDP-SECURITY.md). No durable
  job-execution framework — in-memory execution, per-item flush, `INTERRUPTED` on restart.
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
