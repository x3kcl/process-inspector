# 📄 SPECIFICATION — Flowable Multi-Instance Process Inspector

Status: **v1.0 (agreed product spec)** · Owner: workflow platform team · Inspired by IBM BAW Process Inspector.

## 1. System Overview

The Process Inspector is a centralized administrative tool used by support teams and workflow
administrators to investigate, troubleshoot, and resolve runtime problems with process instances
across **multiple Flowable environments** from one UI.

## 2. Core Architecture & Multi-Instance Handling

- **Engine Registry** — the backend maintains a configuration of multiple Flowable Engine REST
  endpoints (e.g., `Engine A: http://engine-a/flowable-rest/service`,
  `Engine B: http://engine-b/flowable-rest/service`).
- **API Aggregation** — the backend is a proxy/BFF (Backend-for-Frontend). A search either targets
  one selected engine or fans out to multiple engines and aggregates the results.
- **Integration** — interaction with Flowable engines is **strictly via the Flowable V6 REST API**
  (`/query/process-instances`, `/query/historic-process-instances`, `/management/deadletter-jobs`,
  `/runtime/executions`, …). No direct DB access, no embedded engine coupling.

## 3. Core Features & Functional Requirements

### A. Advanced Search & Filtering (the "Search Panel")

| Filter | Detail |
|---|---|
| Target engines | Checkboxes/dropdown selecting which registered engine(s) to query |
| Status | `Active`, `Completed`, `Suspended`, and **`Failed/Error`** (mapped to Flowable **DeadLetter jobs**) |
| Process definition | `processDefinitionKey` or deployment name |
| Timeframe | `startedAfter`, `startedBefore` |
| Business data | `businessKey` or specific process variables (name/operator/value) |

**Combination rule:** logical **AND between filter categories**, **OR within the same category**
(e.g. Status = Active OR Suspended, on Engine A OR Engine B).

### B. Results Dashboard (the "Results Panel")

- Data grid showing the snapshot of matching process instances.
- Columns: **Engine Instance, Process ID, Business Key, Status, Start Time, Current Activity / Error snippet**.
- **Bulk selection** via checkboxes for bulk actions (bulk suspend, bulk delete, bulk retry).

### C. Instance Troubleshooting (the "Details Panel")

For a single selected instance:

- **Execution tree** — current executions, active tasks, active activities (tabular and/or visual).
- **Variables management** — view all process/execution variables. **Crucial: EDIT variables on
  the fly** to correct broken data routing.
- **Error / DeadLetter view** — when stuck on a failed service task, show the **exception stack trace**.
- **Timers & async jobs** — pending timer jobs and async jobs for the instance.

### D. Corrective Actions (the Inspector Tools)

| Action | Flowable REST call |
|---|---|
| Retry failed job | `POST /management/deadletter-jobs/{jobId}` `{"action":"move"}` |
| Force-trigger timer early | `POST /management/timer-jobs/{jobId}` `{"action":"move"}` |
| Change activity state / node jump | `POST /runtime/process-instances/{id}/change-state` |
| Suspend / activate instance | `PUT /runtime/process-instances/{id}` `{"action":"suspend"\|"activate"}` |
| Terminate / delete instance | `DELETE /runtime/process-instances/{id}` |
| Reassign user task | `POST /runtime/tasks/{taskId}` `{"action":"delegate"\|assignee}` |
| Complete task with overridden data | `POST /runtime/tasks/{taskId}` `{"action":"complete","variables":[…]}` |
| Edit variables | `PUT /runtime/process-instances/{id}/variables` |

All corrective actions are **audited** (who, when, engine, instance, payload, outcome) and
**role-gated** (see ARCHITECTURE.md §5).

## 4. Tech Stack (chosen)

- **Backend:** Java 21 / Spring Boot 3 — holds the Engine Registry, proxies + aggregates REST
  requests, owns all engine credentials (unified auth in the middle tier; the UI never sees
  engine credentials and never talks to an engine directly → no CORS surface).
- **Frontend:** React + TypeScript (Vite), AG Grid for the results grid, IDE-style split-pane
  layout (Search | Results | Details).
- **Diagram:** `bpmn-js` renders the process model (`GET /repository/process-definitions/{id}/resourcedata`)
  and highlights active activity IDs of the selected instance.

## 5. Non-Goals (v1)

- Not a deployment/modeling tool (no BPMN editing, no deploys).
- No direct engine-DB access; REST only.
- No cross-engine *transactional* bulk actions — bulk = per-item fan-out with a per-item result report.
