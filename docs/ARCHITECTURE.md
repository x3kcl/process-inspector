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
  definition key. The engine evaluates them; no BFF query parser exists or ever will
  (SPEC §11 — Flowable's query REST has no OR-across-fields to compile to).
- **BFF-side over the scan legs** — `failureTimeAfter/Before` (inclusive window over
  dead-letter/failing job `createTime`) and `errorText` (case-insensitive substring over
  exception snippets): Flowable cannot query instances by failure evidence, so these filter
  the JOB rows **before grouping and root resolution** — a filtered-out child failure never
  rolls up its parent. Setting any of them means only failure-bearing rows can match; in the
  mixed plan, rows whose evidence is filtered away drop out.
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
`processInstanceId` / `scopeType='cmmn'` where serialized — ~6.8+); `tenantId` threaded
through **all** legs when the engine is multi-tenant; async-history lag tolerated — the grid
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
| `GET  /api/resolve?q=…` | **Omnibox**: any ID kind or business key → matching instances across engines |
| `POST /api/search` | Fan-out instance search (`SearchRequest`; URL-serializable) |
| `GET  /api/triage[?refresh=true]` | Stage 0 dashboard: engine health strip (M1 probe state), global + per-engine status counts (query totals, `size=1`), DLQ + RETRYING jobs grouped by normalized error signature with per-engine/per-definition-version counts (sibling versions zero-filled), per-engine honesty envelope (`ok/error/dlqScan`). Served from the 20s Caffeine cache (single-flight); `refresh=true` bypasses, throttled 1/10s |
| `GET  /api/instances/{engineId}/{id}` | Details composite: vitals, executions, activities, tasks, event subscriptions, hierarchy |
| `GET/PUT /api/instances/{engineId}/{id}/variables[/…]` | Type-aware view/edit (incl. per-execution); serializables read-only; list responses byte-capped with an on-demand full-value fetch (an edit always operates on the fetched full value); writes are compare-and-set — `expectedOldValue` ⇒ 409 + fresh re-render on mismatch (R-SEM-09); UI contract: SPEC §4a |
| `GET  /api/instances/{engineId}/{id}/jobs` | Four lanes (executable/timer/suspended/deadletter); stacktrace on expand |
| `GET  /api/instances/{engineId}/{id}/timeline` | Historic activity instances (Gantt rows, call-activity sub-lanes) |
| `GET  /api/instances/{engineId}/{id}/audit` · `…/notes` | Per-instance action history + notes (CRUD) |
| `POST /api/instances/{engineId}/{id}/actions/{verb}` | Verb catalog (SPEC §5); guard tier + reason enforced server-side |
| `POST /api/definitions/{engineId}/{definitionId}/actions/{verb}` | Definition-scoped verbs (`suspend-definition` / `activate-definition`, tier 3) — same guard rails, typed token = the definition key |
| `GET  /api/instances/{engineId}/{id}/diagram` | BPMN XML + active/failed activity IDs for bpmn-js |
| `POST /api/bulk/{verb}` | v1: composite-ID list; v1.x: `{filter:…}` select-all — creates a tracked job |
| `GET  /api/bulk/jobs[/{jobId}]` · `POST …/cancel` | Job state + persisted per-item report |
| `GET  /api/instances/{engineId}/{id}/compare?with={e2}:{id2}` | **Sibling diff** (v1.x): variables / path / timing diffs from historic queries; sibling auto-suggest via same-definition-version query |
| `POST /api/playbooks` · `GET /api/playbooks` · `POST /api/playbooks/{id}/replay` | **Remediation playbooks** (v2): record = distill the exemplar's audit rows into a verb sequence; replay = a bulk job whose items run the sequence with per-step precondition rechecks |
| `GET  /api/audit` | Global operations log (filterable) |
| `GET  /api/stream` | SSE: engine health, bulk-job progress (the BFF is the event source; no engine polling relay) |

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
  per-item reports, saved views (v1.x), registry CRUD (v2). No durable job-execution
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
