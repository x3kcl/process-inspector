# 🗺 IMPLEMENTATION PLAN (spec deliverable 3)

Module-by-module; each milestone ends runnable + demoable. Backend and frontend for a milestone
land together. Bootstrap code for **M1 + M2** already exists in `backend/` and `frontend/`.

## M0 — Scaffold (this repo)
- Repo layout `backend/` (Spring Boot 3, Java 21) + `frontend/` (Vite/React/TS) + `docs/`.
- Docker-compose dev harness: 2× `flowable/flowable-rest` containers (A/B) with a demo process
  deployed → a realistic multi-engine playground from day one.
- CI: `mvn verify` + `npm run build`.
- **Done when:** `docker compose up` gives two engines, BFF boots against them.

## M1 — Engine Registry + health  *(bootstrapped)*
- `EngineRegistryProperties` (YAML binding, §3 of ARCHITECTURE.md), secret resolution from env refs.
- `FlowableEngineClient`: per-engine `RestClient` with auth filter + timeouts.
- Health probe (`GET /management/engine`) on boot + scheduled; capability flags.
- `GET /api/engines` (no secrets).
- **Done when:** UI header shows each engine with live green/red badge + version.

## M2 — Search & Results  *(bootstrapped)*
- `SearchRequest` DTO (engines[], statuses[], processDefinitionKey, businessKey,
  startedAfter/Before, variables[]) — AND across categories, OR within.
- Per-engine **search plan** (historic query + suspended-set + dead-letter join, ARCH §2.3),
  parallel fan-out with per-engine timeout + partial-result envelope (§2.2).
- `POST /api/search` → merged rows sorted `startTime desc` + `perEngine` metadata.
- Frontend: SearchPanel (engine + status checkboxes, definition/businessKey/date/variable
  filters) + ResultsGrid (AG Grid, checkbox selection, engine badge, status chip, error snippet).
- **Done when:** one search over 2 engines returns mixed, correctly-statused rows; killing an
  engine mid-demo degrades to a partial-result badge, not an error page.

## M3 — Details Panel (read-only troubleshooting)
- BFF composite endpoint: instance (`GET /runtime/process-instances/{id}` or historic fallback),
  executions (`GET /runtime/executions?processInstanceId=`), active activities
  (`GET /runtime/executions/{execId}/activities`), tasks (`GET /runtime/tasks?processInstanceId=`).
- Variables view (`GET /runtime/process-instances/{id}/variables`; historic fallback for completed).
- Jobs view: dead-letter / timer / async / suspended jobs + on-demand stacktrace
  (`GET /management/deadletter-jobs/{jobId}/exception-stacktrace`).
- Frontend: third split-pane with tabs *Overview · Execution tree · Variables · Jobs/Errors*.
- **Done when:** a stuck instance shows its failing job + full stacktrace and the execution tree.

## M4 — Corrective actions + audit + RBAC
- Action endpoints (ARCH §4): retry dead-letter (`{"action":"move"}`), force-trigger timer,
  suspend/activate (`PUT /runtime/process-instances/{id}`), terminate/delete, variable **edit**
  (`PUT .../variables/{name}`), task reassign/complete-with-variables.
- Audit log (append-only table) + RBAC roles VIEWER/OPERATOR/ADMIN + typed-confirm for
  destructive ops on `prod` engines.
- Frontend: action toolbar in Details, optimistic-free (always re-fetch after action).
- **Done when:** the demo "failed service task" is fixed end-to-end from the UI:
  edit variable → retry dead-letter job → instance completes; audit rows written.

## M5 — Diagram + change-state (node jumping)
- `GET /api/instances/{e}/{id}/diagram`: BPMN XML via
  `GET /repository/process-definitions/{defId}/resourcedata` + active activity IDs.
- bpmn-js viewer with active-node highlight + dead-letter node marked red.
- Change-state UI: pick source/target activity on the diagram →
  `POST /runtime/process-instances/{id}/change-state`
  `{"cancelActivityIds":[…],"startActivityIds":[…]}` (capability-gated per engine, ADMIN only).
- **Done when:** a token is visibly moved off a failed node on the diagram and the instance proceeds.

## M6 — Bulk + hardening
- Bulk suspend/activate/delete/retry over grid selection → per-item result report (stream or
  summary table), no transactionality pretense.
- Saved searches, CSV export, deep links (`/inspect/{engineId}/{id}`).
- Load-test fan-out; tune executor + per-engine caps; pen-test pass on the proxy layer
  (the BFF must **whitelist** engine paths, never blind-proxy).

## Suggested build order inside any milestone
backend DTO → engine client call → aggregator/join logic → controller → typed frontend API
client → component. Every Flowable call gets one integration test against the dockerized
`flowable-rest` (no mocked Flowable responses for join logic — the DLQ/suspended join is where
the bugs will live).
