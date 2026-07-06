---
name: flowable-rest
description: Flowable V6 REST API doctrine for the inspector — the FAILED/dead-letter status join, historic-vs-runtime query split, variables semantics, job kinds, the corrective-action call catalog, composite engine:instance IDs, and capability gating across engine versions. Read before touching SearchService, FlowableEngineClient, or any new engine call.
---
# Flowable V6 REST doctrine (process-inspector)

*Derived from the flap `trace-events`/`validate-bpmn` skills and this repo's ARCHITECTURE.md.
Everything here is REST-only — the inspector NEVER touches an engine DB or embeds an engine.*

## 1. There is no FAILED state — it's a join
Flowable process instances are only ever active/suspended/ended. "Failed" lives in the
**dead-letter job queue**. The per-engine search plan (`SearchService`) composes:
1. `POST /query/historic-process-instances` — primary (running AND completed; supports
   `startedBefore/After`, `businessKey`, `processDefinitionKey`, `variables`, `finished`).
2. `POST /query/process-instances` `{"suspended":true}` — suspended-ID set (runtime only).
3. `GET /management/deadletter-jobs?size=…` — failed-ID set + exception snippets.

Derivation per row: `endTime != null → COMPLETED`, else `id ∈ dlq → FAILED`,
else `id ∈ suspended → SUSPENDED`, else `ACTIVE`. Filter to the requested status set AFTER
derivation. Never trust an engine-side "state" field for FAILED.

## 2. Historic vs runtime — pick per lifecycle
- Completed instances exist ONLY in `/query/historic-*` / `/history/*`. Runtime endpoints 404.
- Details/variables for a completed instance: `GET /history/historic-process-instances/{id}` +
  `/history/historic-variable-instances?processInstanceId=`. Always implement the historic
  fallback; a details panel that 404s on completed instances is a known trap.
- Variables: runtime edit via `PUT /runtime/process-instances/{id}/variables` (typed payload
  `{name, type, value}` — preserve the declared type; string-ifying an integer breaks
  gateways downstream).

## 3. Job kinds — four queues, not one
`/management/jobs` (async executable), `/management/timer-jobs`, `/management/suspended-jobs`,
`/management/deadletter-jobs`. Stacktrace: `GET /management/deadletter-jobs/{jobId}/exception-stacktrace`
(plain text). A "retry" is a MOVE between queues: `POST /management/deadletter-jobs/{jobId}`
`{"action":"move"}` → back to the executable queue with fresh retries. Force-fire a timer early
the same way on `/management/timer-jobs/{jobId}`.

## 4. Corrective-action catalog (exact calls — keep in sync with SPECIFICATION §D)
| Action | Call |
|---|---|
| Retry dead-letter job | `POST /management/deadletter-jobs/{jobId}` `{"action":"move"}` |
| Trigger timer now | `POST /management/timer-jobs/{jobId}` `{"action":"move"}` |
| Suspend / activate | `PUT /runtime/process-instances/{id}` `{"action":"suspend"\|"activate"}` |
| Terminate/delete | `DELETE /runtime/process-instances/{id}` |
| Node jump / change state | `POST /runtime/process-instances/{id}/change-state` `{"cancelActivityIds":[…],"startActivityIds":[…]}` |
| Reassign task | `POST /runtime/tasks/{taskId}` `{"action":"delegate"\|assignee}` |
| Complete task w/ data | `POST /runtime/tasks/{taskId}` `{"action":"complete","variables":[…]}` |
| Edit variables | `PUT /runtime/process-instances/{id}/variables` |
| Diagram source | `GET /repository/process-definitions/{defId}/resourcedata` (BPMN XML for bpmn-js) |

## 5. Composite IDs & capability gating
- An instance ID is unique only WITHIN an engine. Outside a single engine call, always
  `engineId + ":" + processInstanceId`; endpoints take `/{engineId}/{id}`.
- Engines run different Flowable versions. Probe `GET /management/engine` (health service),
  record version, and **capability-flag** features that need newer endpoints (`change-state`).
  The UI greys a gated tool out per engine; the BFF rejects the call — never fail at the
  engine with a confusing 404.
- Auth is per-engine (basic/bearer) resolved from env-var refs in the registry
  (`application.yml` → `inspector.engines[].auth.password-ref`). Secrets never appear in
  config values, logs, or API responses.

## 6. Paging & fan-out honesty
Each engine query is capped (`max-page-size`). There is NO global cursor across engines: merge
+ sort in the BFF, and surface `perEngine.total` so the user sees "138 of 2,410 — narrow the
filter". Never fake global pagination. An unreachable engine yields an error envelope inside a
200 response (`perEngine[x].ok=false`), never a failed search — partial results are the contract.
