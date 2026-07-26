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

## Review checklist — rails ArchUnit can't structurally enforce (issue #309)
Two of #309's rules exist as automated ArchUnit guards (`MutatingEndpointRbacArchTest`,
`RestClientWhitelistArchTest` — see `docs/TEST-STRATEGY.md`'s ArchUnit section). Two more
candidates were spiked and dropped because expressing them would need a hand-maintained list
or a check ArchUnit's API doesn't expose — reviewers must check these by hand instead:

- **Every read fan-out over `EngineRegistry.all()` is scope-filtered by `ReadScopeGate`
  (R-SAFE-17, "Reads are scoped too").** Spiked hard, dropped: the codebase has THREE
  incompatible shapes that all reach the same correct outcome — (1) the calling controller
  resolves `readScope.readableEngineIds(auth)` and threads the `Set<String>` down as a plain
  parameter (`SearchController`→`SearchService`, `ResolveController`→`ResolveService` — the
  service class never references `ReadScopeGate` at all, so a "class calls `.all()` ⇒ class
  also references `ReadScopeGate`" rule false-negatives on these); (2) an unscoped aggregator
  (`TriageAggregationService`, `LeakViewService`) builds the raw DTO and a SEPARATE
  `*ScopeProjector` class filters it post-hoc, wired together only at the controller — no
  compile-time link between the two files an ArchUnit rule can pin; (3) several `.all()`
  call sites (`RegistryBootstrap`, `RegistryReloadListener`, `EngineHealthService`,
  `MeController`) are infra bookkeeping or self-lookups that genuinely need no scoping at
  all. Any single mechanical predicate flags real (2) cases and false-positives on (1) and
  (3) indistinguishably. **When adding a new fan-out over `EngineRegistry.all()` that
  surfaces fleet data to a caller, manually verify one of: the result is scope-filtered
  before it reaches the caller, OR the endpoint doesn't answer with cross-engine data at
  all (e.g. it echoes the caller's own grants like `MeController`), OR it's pure infra
  bookkeeping with no `Authentication` in scope.**
- **No `synchronized` around blocking I/O in a virtual-thread world (CLAUDE.md's
  Java-21-VT rule, issue #306).** Spiked, dropped: ArchUnit's `JavaModifier.SYNCHRONIZED`
  only sees a `synchronized` METHOD; every real instance in this codebase is a `synchronized
  (lock) { … }` BLOCK guarding a plain `Object` field (the exact pattern `AuditService` and
  `SseHub`'s own doc comments describe replacing with `ReentrantLock`) — ArchUnit's public
  API has no block/statement-level (monitorenter/monitorexit) visibility to key a rule on.
  Even ignoring that gap, "blocking I/O" has no closed structural definition short of a
  hand-maintained list of qualifying types (JPA repositories, `EntityManager`, `RestClient`,
  `RestTemplate`, `java.nio.file.Files`, sockets, …) — precisely the disqualifier #309's
  expressibility gate rules out. **When adding or reviewing a `synchronized` block, manually
  verify it does not wrap a JDBC call, an HTTP call, or blocking file I/O; prefer
  `ReentrantLock` per CLAUDE.md.** The #309 spike found the pattern still live in
  `EngineRegistryStore` (4 blocks, all wrapping JDBC via `repository.save`/`audit.*`) and
  `AccessMappingAdminService` (2 blocks, same shape) — `ScopeMappingService` and
  `BreakGlassAuditSink` synchronize over blocking file I/O. None of these were introduced by
  this PR; they predate issue #306's fix (which only touched `AuditService`). Worth a
  dedicated follow-up (tracked as issue #327).
