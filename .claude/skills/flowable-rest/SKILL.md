---
name: flowable-rest
description: Flowable V6 REST API doctrine for the inspector — the FAILED/dead-letter status join, historic-vs-runtime query split, variables semantics, job kinds, the corrective-action call catalog, composite engine:instance IDs, and capability gating across engine versions. Read before touching SearchService, FlowableEngineClient, or any new engine call.
---
# Flowable V6 REST doctrine (process-inspector)

*Derived from the flap `trace-events`/`validate-bpmn` skills and this repo's ARCHITECTURE.md.
Everything here is REST-only — the inspector NEVER touches an engine DB or embeds an engine.*

## 1. There is no FAILED state — it's a join (v2 corrected form, ARCH §2.3)
Flowable process instances are only ever active/suspended/ended. "Failed" lives in job
queues. Status is derived in the BFF as **flags** (`ended`, `suspended`,
`hasDeadLetterJobs`, `hasFailingJobs`, `failedInSubprocess`) — statuses collide (a suspended
instance keeps its DLQ jobs; retry must check suspension and offer activate-first).

- **FAILED-only searches drive FROM the DLQ**: page `GET /management/deadletter-jobs` to
  exhaustion (bounded by `dlq-scan-cap`, `processDefinitionId` pushed down), resolve
  call-activity children up the `superProcessInstanceId` chain to the searched root, then
  hydrate via `POST /query/historic-process-instances` `{"processInstanceIds":[…]}`.
- **Mixed searches**: historic query per filters → enrich only the displayed page:
  runtime/suspended state via `/query/process-instances` with `processInstanceIds`; DLQ
  membership per row.
- **FAILING tier** (retries remaining): `GET /management/jobs?withException=true` PLUS
  `GET /management/timer-jobs?withException=true` — a failing async job parks in the TIMER
  table between attempts (`asyncFailedJobWaitTime`).

⚠ NEVER issue one unpaged DLQ fetch as "the failed set" — default page size is 10; anything
past the page silently declassifies FAILED → ACTIVE. A capped scan sets
`dlqScan:"truncated@N"` in the envelope and the UI badges it.
⚠ Filter CMMN-scoped jobs (null `processInstanceId` / `scopeType='cmmn'`) out of every leg —
flowable-rest shares job tables with the CMMN engine. Thread `tenantId` through ALL legs on
multi-tenant engines. Never trust an engine-side "state" field for FAILED.

## 2. Historic vs runtime — pick per lifecycle
- Completed instances exist ONLY in `/query/historic-*` / `/history/*`. Runtime endpoints 404.
- Details/variables for a completed instance: `GET /history/historic-process-instances/{id}` +
  `/history/historic-variable-instances?processInstanceId=`. Always implement the historic
  fallback; a details panel that 404s on completed instances is a known trap.
- Variables: runtime edit via `PUT /runtime/process-instances/{id}/variables` (typed payload
  `{name, type, value}` — preserve the declared type; string-ifying an integer breaks
  gateways downstream).

## 3. Job kinds — four management queues, plus a fifth off to the side
`/management/jobs` (async executable), `/management/timer-jobs`, `/management/suspended-jobs`,
`/management/deadletter-jobs`. Stacktrace: `GET /management/deadletter-jobs/{jobId}/exception-stacktrace`
(plain text). A "retry" is a MOVE between queues: `POST /management/deadletter-jobs/{jobId}`
`{"action":"move"}` → back to the executable queue with fresh retries. Force-fire a timer early
the same way on `/management/timer-jobs/{jobId}`.

**External-worker jobs are a FIFTH queue, and NOT in the management API** (v1.x #7 — Flowable
6.8+). Verified live: `GET /management/external-worker-jobs` does not exist (6.8 → 404; 7.x →
*"No endpoint …"*). They live in the **External Worker REST API at the `/external-job-api`
SIBLING context** (beside `/service`, not under it): `GET …/external-job-api/jobs?processInstanceId=`
is the READ-ONLY list — standard page envelope; 200 on 6.8/7.x, **404 on pre-6.8** (so its
availability tracks the `externalWorkerJobs` capability exactly). Rows carry the worker lock:
`lockOwner` / `lockExpirationTime` (null until acquired). ⚠ The `POST …/external-job-api/acquire/jobs`
endpoint of that same API **LOCKS a job** — a mutation that steals it from a real worker; never
call it for visibility (ITs may, to populate `lockOwner`). Capability-gate (≥6.8) in the BFF
before the call — a pre-6.8 engine has no such context to answer.

⚠ Wire-format gotchas (proven on flowable-rest 6.8):
- **Date query params take WHOLE seconds only** — `dueBefore=…T06:20:00Z` works,
  `…T06:20:00.000Z` is a 400 ("Failed to parse date"). `Instant.toString()` emits millis
  when non-zero: truncate to seconds before building the URL.
- **Job sort fields**: the job collections accept `id, dueDate, executionId,
  processInstanceId, retries, tenantId` — `createTime` is NOT accepted (400). Oldest
  executable job = `sort=dueDate&order=asc`; compute age from the row's
  `dueDate ?? createTime`, floored at 0.

## 4. Corrective-action catalog (exact calls — keep in sync with SPECIFICATION §D)
| Action | Call |
|---|---|
| Retry dead-letter job | `POST /management/deadletter-jobs/{jobId}` `{"action":"move"}` |
| Trigger timer now | `POST /management/timer-jobs/{jobId}` `{"action":"move"}` |
| Suspend / activate | `PUT /runtime/process-instances/{id}` `{"action":"suspend"\|"activate"}` |
| Terminate/delete | `DELETE /runtime/process-instances/{id}` |
| Node jump / change state | `POST /runtime/process-instances/{id}/change-state` `{"cancelActivityIds":[…],"startActivityIds":[…]}` |
| Reassign / return-to-team task | `PUT /runtime/tasks/{taskId}` `{"assignee":"<id>"\|null}` — arbitrary assignee, or null to clear it back to candidate groups. The key must be PRESENT (even null) so Flowable treats it as an explicit set, not "leave unchanged". `POST …{"action":"claim"\|"delegate"}` only self-assigns/delegates — not a free reassign. |
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
