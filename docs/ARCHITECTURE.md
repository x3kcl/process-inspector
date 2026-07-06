# 🏗 ARCHITECTURE — Multi-Instance Process Inspector

Answers spec deliverables **1 (high-level design)** and **2 (Engine Registry data model)**.

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
                       │  ┌──────────────┐  ┌──────────┐  ┌───────┐ │
                       │  │ Aggregator   │  │ Audit log│  │ RBAC  │ │
                       │  └──────────────┘  └──────────┘  └───────┘ │
                       └────────────────────────────────────────────┘
```

One BFF, N engines. The browser has exactly **one** origin and **one** auth session; every
engine call is made server-side with that engine's own credentials. This kills the CORS and
credential-sprawl problems in one move.

## 2. The five multi-instance problems and their answers

### 2.1 Identity — composite IDs
A process-instance ID is only unique *within* an engine. Everywhere outside a single engine call
we use the composite `engineId + ":" + processInstanceId` (e.g. `orders-prod:12345`). The row DTO
carries `engineId` as a first-class column; every detail/action endpoint takes
`/{engineId}/{processInstanceId}`.

### 2.2 Fan-out — parallel, bounded, partial
The Aggregator executes one *search plan* per selected engine on a bounded executor:
- parallel across engines, **per-engine timeout** (registry `timeouts.readMs`),
- an engine that is down/slow/misconfigured yields an **error envelope, never a failed search**:

```json
{ "rows": [...], "perEngine": {
    "orders-prod":  { "ok": true,  "fetched": 138, "total": 138 },
    "billing-prod": { "ok": false, "error": "timeout after 10s" } } }
```
The UI renders partial results and badges the failing engine. *A support tool must degrade, not blank.*

### 2.3 Semantics — status mapping (the FAILED join)
Flowable has no `FAILED` instance state; failure lives in the **dead-letter job queue**. The
per-engine search plan therefore composes up to three REST queries and joins in the BFF:

1. `POST /query/historic-process-instances` — the primary query (covers running **and**
   completed; supports `startedBefore/After`, `businessKey`, `processDefinitionKey`, `variables`,
   `finished`).
2. `POST /query/process-instances` `{"suspended":true}` — the suspended-ID set (runtime only).
3. `GET /management/deadletter-jobs?size=…` — the failed-ID set + exception snippets.

Status derivation per row: `endTime != null → COMPLETED`, else `id ∈ dlq → FAILED`,
else `id ∈ suspended → SUSPENDED`, else `ACTIVE`. Then filter to the requested status set —
this yields exactly the spec's *OR within category* semantics (union of statuses), while all
different filter kinds land in one query body = *AND between categories*.

### 2.4 Aggregation — sorting & paging across engines
There is no global cursor across independent engines. v1 strategy (deliberate, simple, honest):
- each engine is queried with `size = min(maxPageSize, requestedPageSize)`,
- rows are merged and sorted in the BFF (default `startTime desc`),
- `perEngine.total` tells the user when an engine has more than was fetched ("138 of 2,410 —
  narrow your filter"). No fake global pagination.
v2 can add k-way-merge cursors per engine if real usage demands deep paging.

### 2.5 Drift — capability probing
Engines may run different Flowable versions. On registry load (and on demand) the BFF calls each
engine's `GET /management/engine` and records version + reachability. Features that need newer
endpoints (e.g. `change-state`) are **capability-flagged** per engine; the UI greys the tool out
instead of failing at click time.

## 3. Engine Registry — data model (spec deliverable 2)

v1 is **config-first** (YAML + env-var secret refs, 12-factor, no DB needed). The identical shape
becomes a JPA table when runtime CRUD of engines is wanted (v2).

```yaml
inspector:
  engines:
    - id: orders-prod                # stable slug, used in composite IDs — never rename
      name: "Orders µService (PROD)" # display name
      base-url: "http://engine-a:8080/flowable-rest/service"
      environment: prod              # dev|test|prod — drives UI badge + confirm-strictness
      color: "#e74c3c"               # engine badge color in the grid
      enabled: true
      auth:
        type: basic                  # basic | bearer | none
        username: rest-admin
        password-ref: ENGINE_A_PASSWORD   # NAME of an env var; secret never in config/UI/logs
        # token-ref: ENGINE_B_TOKEN       # for type: bearer
      timeouts:
        connect-ms: 2000
        read-ms: 10000
      max-page-size: 200             # cap per fan-out query
```

v2 table `engine_registry` (same fields) + `updated_by/updated_at`, secrets still resolved via
refs (env/vault), never stored plaintext.

**Runtime state kept per engine (not config):** `reachable`, `engineVersion`, `lastHealthCheck`,
`capabilities{changeState, …}` — populated by a scheduled health probe, surfaced by `GET /api/engines`.

## 4. BFF API surface (v1)

| Endpoint | Purpose |
|---|---|
| `GET  /api/engines` | Registry + live health/capabilities (no secrets) |
| `POST /api/search` | Fan-out instance search (body: `SearchRequest`) |
| `GET  /api/instances/{engineId}/{id}` | Details composite: instance + executions + activities + tasks |
| `GET  /api/instances/{engineId}/{id}/variables` · `PUT …/variables/{name}` | View / **edit** variables |
| `GET  /api/instances/{engineId}/{id}/jobs` | dead-letter + timer + async jobs incl. stacktrace (`GET /management/deadletter-jobs/{jobId}/exception-stacktrace`) |
| `POST /api/instances/{engineId}/{id}/actions/{action}` | suspend/activate/terminate/delete/retry-job/trigger-timer/change-state |
| `POST /api/bulk/{action}` | Body: list of composite IDs → per-item result report |
| `GET  /api/instances/{engineId}/{id}/diagram` | BPMN XML + active activity IDs for bpmn-js |

## 5. Cross-cutting

- **Unified auth (middle tier):** UI authenticates once against the BFF (v1: form/basic + session;
  v2: OIDC). Engine credentials live only in the BFF process env.
- **RBAC:** `VIEWER` (search/details), `OPERATOR` (retry, timer-trigger, variable edit, task ops),
  `ADMIN` (terminate/delete, change-state, bulk). Enforced in the BFF, mirrored in the UI.
- **Audit log:** every mutating call → append-only record `(user, ts, engineId, instanceId,
  action, requestPayload, httpStatus, response snippet)`. Non-negotiable for a tool whose job is
  poking production state.
- **Safety rails:** destructive actions require typed confirmation in `prod`-tagged engines;
  bulk actions stream per-item results; BFF never retries mutating calls automatically.
