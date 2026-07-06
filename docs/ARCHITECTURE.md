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
  children up the `superProcessInstanceId` chain, then hydrate via
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

**Hygiene applied to every leg:** CMMN-scoped jobs filtered out (shared job tables; null
`processInstanceId` / `scopeType='cmmn'`); `tenantId` threaded through **all** legs when the
engine is multi-tenant; async-history lag tolerated (runtime query unioned in for
ACTIVE/SUSPENDED; details panel fetches live-first with historic fallback). Actions never
trust the grid snapshot: every mutation re-validates against live engine state server-side
and returns the engine's 404/409 verbatim (§2.7 of the old spec — snapshot races are
acceptable for display, never for action targeting).

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
  engines:
    - id: orders-prod                # stable slug, used in composite IDs — never rename
      name: "Orders µService (PROD)" # display name
      base-url: "http://engine-a:8080/flowable-rest/service"
      environment: prod              # dev|test|prod — drives env color band + guard strictness
      accent-color: "#e74c3c"        # OPTIONAL subtle accent only; environment owns semantics
      enabled: true
      tenant-id: ""                  # OPTIONAL: pin this registration to one tenant
      actuator-url: ""               # OPTIONAL: real executor metrics when exposed; never required
      auth:
        type: basic                  # basic | bearer | none
        username: rest-admin
        password-ref: ENGINE_A_PASSWORD   # NAME of an env var; secret never in config/UI/logs
      timeouts:
        connect-ms: 2000
        read-ms: 10000               # budgeted PER CALL within a search plan
      max-page-size: 200             # cap per fan-out query page
      dlq-scan-cap: 5000             # exhaustive-paging bound for the dead-letter scan
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
| `GET  /api/triage/failure-groups` | DLQ + failing jobs grouped by normalized error signature, counts per engine/definition-version |
| `GET  /api/instances/{engineId}/{id}` | Details composite: vitals, executions, activities, tasks, event subscriptions, hierarchy |
| `GET/PUT /api/instances/{engineId}/{id}/variables[/…]` | Type-aware view/edit (incl. per-execution); serializables read-only |
| `GET  /api/instances/{engineId}/{id}/jobs` | Four lanes (executable/timer/suspended/deadletter); stacktrace on expand |
| `GET  /api/instances/{engineId}/{id}/timeline` | Historic activity instances (Gantt rows, call-activity sub-lanes) |
| `GET  /api/instances/{engineId}/{id}/audit` · `…/notes` | Per-instance action history + notes (CRUD) |
| `POST /api/instances/{engineId}/{id}/actions/{verb}` | Verb catalog (SPEC §5); guard tier + reason enforced server-side |
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
- **RBAC:** `VIEWER` (search/details/diagram/stacktraces), `OPERATOR` (tier 0–2 verbs:
  retry, timer, unstick, variable edit, task ops, suspend/activate, rerun/change-state*),
  `ADMIN` (tier 3–4: terminate/delete, suspend-definition, deadletter-delete, migrate, bulk).
  (*change-state is tier 2 flow surgery but gated ADMIN on `prod` engines.) Enforced in the
  BFF, mirrored in the UI as greyed-with-reason.
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
