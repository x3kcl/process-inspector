---
name: corrective-actions
description: The safety doctrine for EVERY mutating endpoint in the inspector — audit (who/when/engine/instance/payload/outcome), RBAC gates (VIEWER/OPERATOR/ADMIN), typed confirmation on prod engines, capability gating, no automatic retry of mutations, bulk = per-item fan-out with per-item results. Read before adding or changing any action endpoint, bulk operation, or variable edit.
---
# Corrective actions — the safety rails (process-inspector)

This tool's entire job is poking production state. Every mutating path follows ALL of these
rails; a new action endpoint that skips one is a review-blocking bug.

## 1. Audit — non-negotiable, append-only
Every mutating call writes `(user, timestamp, engineId, processInstanceId, action,
requestPayload, httpStatus, responseSnippet)` — BEFORE returning to the caller, whether the
engine call succeeded or failed. The audit row is how the next support person learns what was
already tried; surface the instance's audit trail in the Details panel. Never log secrets or
full variable payloads containing them.

## 2. RBAC — enforced in the BFF, mirrored in the UI
- `VIEWER`: search, details, diagram, variables view, stacktraces. Zero mutations.
- `OPERATOR`: retry dead-letter, trigger timer, variable edit, task reassign/complete,
  suspend/activate.
- `ADMIN`: terminate/delete, change-state (node jump), all bulk operations.
UI hides/disables what the role can't do, but the BFF check is the real gate — never trust
the client. New action → decide its tier explicitly and record it in ARCHITECTURE §5.

## 3. Destructive-action confirmation, scaled by environment
The registry tags each engine `dev|test|prod`. Destructive actions (terminate, delete, bulk
anything, change-state) on a `prod` engine require **typed confirmation** (user types the
instance count or business key); on dev/test a plain confirm suffices. The confirm dialog
states the blast radius in concrete terms ("terminate 47 instances on Orders PROD —
irreversible"), never a bare "Are you sure?".

## 4. Mutations are never auto-retried
The BFF retries reads only. A timed-out mutation is reported as UNKNOWN outcome with a
"re-check instance state" affordance — a blind client-side retry can double-fire (e.g.
complete-task twice). Always re-fetch instance state after an action instead of optimistic
UI updates.

## 5. Bulk = per-item fan-out, honestly reported
No cross-engine transaction pretense (spec §5). A bulk action executes per item, bounded
concurrency, and returns/streams a per-item result report (`ok`/`failed` + reason per
composite ID). Partial failure is a NORMAL outcome — the UI shows exactly which items
failed and offers retry-of-failed-items-only. One audit row per item plus one for the
bulk envelope.

## 6. Capability gating
An action needing an endpoint the target engine's Flowable version lacks (probed by
`EngineHealthService`) is greyed out in the UI per engine AND rejected by the BFF with a
clear message. Never let the click travel to the engine to die as a 404.

## 7. The BFF whitelists engine paths
The proxy layer exposes ONLY the cataloged calls (see `flowable-rest` skill §4). Never a
generic pass-through `/proxy/**` route — that would hand every UI user the full engine
management API, bypassing RBAC and audit.
