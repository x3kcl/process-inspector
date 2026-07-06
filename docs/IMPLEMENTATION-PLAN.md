# 🗺 IMPLEMENTATION PLAN (spec deliverable 3) — v3

Module-by-module; each milestone ends runnable + demoable. Backend and frontend for a
milestone land together. Bootstrap code for **M1 + M2** already exists. Re-sequenced after
the design reviews ([DESIGN-REVIEW.md](DESIGN-REVIEW.md)): correctness fixes fold into M2,
shareability and the diagram move EARLIER, **flow surgery moves OUT of the v1 gate to v1.1**
(R-GOV-07), bulk-by-query and migration move LATER.

**Gates (R-TEST-02):** every milestone has entry/exit criteria per TEST-STRATEGY §2 — suite
green ×3 on applicable engine profiles, coverage floors met, zero open Sev1/Sev2, every
"Done when" demo converted into an automated E2E. Done-when clauses cite the
REQUIREMENTS-REGISTER IDs they discharge. The authoritative CI merge-gate list is
OPERATIONS.md §8.

## M0 — Scaffold *(done — only the listed slice-0 stragglers open)*
- Repo layout, docker-compose dev harness. Profiles in `docker/docker-compose.dev.yml`
  (project name `process-inspector`): **`flowable-6`** (engine-a/engine-b, 6.8.0,
  :8081/:8082 — default via `docker/.env` `COMPOSE_PROFILES`), **`flowable-7`**
  (`flowable/flowable-rest:7.1.0`, :8083, same context path + admin env vars),
  **`postgres`** (postgres:16, :5433 — container only until M4, no JPA/Flyway).
- Seed catalog under `docker/processes/` + idempotent `docker/seed.sh` (REST-only;
  no-arg mode auto-discovers every reachable engine on :8081-:8084): `demoOrder`
  (FIX-PROC-01), `demoFailingPayment` (FIX-PROC-04, `${amount % divisor}` + `R1/PT1S`
  → organic dead-letter; EL `/` never throws — see `validate-bpmn` skill),
  `demoFailingRetry` (FIX-PROC-05, `R10/PT1H` pinned RETRYING), `demoUserTask`
  (FIX-PROC-02; seed.sh starts one ACTIVE + one suspended over REST), `demoTimerWait`
  (FIX-PROC-03, `${dueDuration}` timer-stuck), `demoParent` (FIX-PROC-06, call activity
  → failing child, businessKey inherited → `failedInSubprocess` roll-up data).
- **`legacy`** profile landed too: `flowable/flowable-rest:6.3.1` on :8084 — pre every
  ARCH §2.5 cliff, same creds/context path; `EngineHealthLegacyIT` proves the probe
  reports all four version capabilities absent without 400s on the 6.3 wire shapes.
- CI + image landed: `.github/workflows/ci.yml` (lint / unit / frontend / docker /
  integration matrix over the three engine profiles, gated by `docker/smoke-test.sh`
  bounded readiness probes) and the root multi-stage `Dockerfile` (maven builder →
  `eclipse-temurin:21-jre-alpine`, non-root, `SERVER_PORT=8080`).
- **Frontend toolchain landed (slice-0 close-out):** `package-lock.json`; ESLint 9 flat
  config (typescript-eslint strict-type-checked + react-hooks) + Prettier; Vitest
  (`npm test`); springdoc on the BFF (`/v3/api-docs`, key-ordered, permitted
  unauthenticated) → `npm run gen:api` regenerates the committed
  `frontend/src/api/schema.d.ts`; singleton `openapi-fetch` client (hand-written
  `types.ts`/`api.ts` deleted); `check:no-enterprise` build gate beside the watermark gate.
- **OpenAPI CI drift gate (landed):** the `frontend` CI job boots the real BFF against a
  `postgres:16` service (engines absent — health degrades, boot succeeds), waits bounded
  on `/v3/api-docs`, runs `npm run gen:api` and fails on any
  `git diff -- src/api/schema.d.ts` (R-SEM-15: cross-language drift is a CI failure).
- **Still open (slice-0):** remaining FIX-PROC seeds (recursive / event-wait /
  multi-instance / parallel-join / CMMN case); the OPERATIONS §8 "still to land" gate list.

## M1 — Engine Registry + health  *(landed, incl. the header-strip UI)*
- Registry YAML binding per ARCH §3 (environment/mode enums, engine-id slug validation,
  duplicate-id fail-fast, `write-ms`, `dlq-scan-cap`, `alarm-thresholds`) + env-ref secrets.
- Per-engine `RestClient` on the JDK HttpClient (no redirects) wrapped in per-engine
  Resilience4j circuit breakers (shared config `engine`); open breaker degrades that
  engine's entry, never the request.
- Scheduled health probe (30s, virtual-thread fan-out): version, capability flags per
  ARCH §2.5, four job-lane counts + oldest-executable-job-age + overdue-timers via the
  `size=1` total trick — all surfaced by `GET /api/engines`.
- Proven across the engine matrix: `EngineHealthIT` (6.8, organic dead-letter via
  `demoFailingPayment`; fail→retry→DLQ measures ~45s on engine defaults — waits bounded
  at 60s), `EngineHealth7IT` (7.1, same full arc on the Jakarta wire shapes) and
  `EngineHealthLegacyIT` (6.3.1, capability cliff: all four version caps reported absent).
- **Done when:** header strip shows each engine with env-colored badge, version, lanes
  *(landed: `HeaderStrip` off the shared 30s `/api/engines` poll — literal PROD/TEST/DEV
  token + distinct border per band, accent color demoted to a dot, four lanes with DLQ
  alarm, oldest-exec/overdue-timer alarms, unreachable engines render as warning cards
  without blanking healthy ones)*.

## M2 — Search & results  *(landed: M2a + M2b + M2c incl. the UI)*
- **M2a (landed, backend):** the status join per ARCH §2.3 — status = flags
  (`InstanceStatusFlags` + derived primary chip incl. RETRYING), DLQ-driven inverted plan
  for FAILED/RETRYING-only requests (bounded exhaustive paging, definition pushdown,
  `dlqScan/failingScan: "truncated@N"` envelope markers), FAILING tier (jobs+timer-jobs
  `withException`), batched `superProcessInstanceId` roll-up (depth-capped, cycle-guarded
  at rung 1 per R-TEST-07), CMMN filtering, tenant threading, per-page runtime-state
  enrichment with the legacy ignored-`processInstanceIds` fallback (ARCH §2.3), per-engine
  bulkhead beside the breaker. Proven on all three engine profiles: `SearchServiceIT`
  (6.8: inverted plan, roll-up, RETRYING, truncation badge, engine-down degradation),
  `Search7IT`, `SearchLegacyIT` (6.3.1 incl. the enrichment-fallback regression). Seed
  fixture fix: `failedJobRetryTimeCycle` must be the extension ELEMENT — the attribute
  form was silently ignored (TEST-SCENARIOS §1.2, `validate-bpmn` skill).
- **M2b (backend landed):** search additions — failure-time filter/sort and error-text
  filter (BFF-side over the scan legs, BEFORE root resolution), `businessKeyLike` (native
  pushdown; 6.3 silently drops the field → impossible-match canary → per-engine envelope
  error, `SearchLegacyIT`), variable `like` (native on the whole matrix incl. 6.3, proven
  live), current-activity contains-filter (unfinished-activity leg, bounded N+1),
  `statusCounts` facets (plan-observable candidates, lower-bound under truncation),
  compiled-criteria echo + copy-as-cURL (`criteriaEcho`/`curl` envelope fields, pure
  `CriteriaEcho`), unindexed-variable-scan `log.warn` guardrail, 400 on bad filter input.
  Proven on all three profiles (`SearchServiceIT` +8, `Search7IT` +1, `SearchLegacyIT` +2).
  *M2b UI landed:* the collapsible search rail with the full filter set; **URL-encoded
  search state** (typed codec `frontend/src/search/urlState.ts`, round-trip vitest-proven;
  the URL is the single source of truth and a shared link replays the search); rail
  collapses to criteria chips after a search; server `criteriaEcho` + copyable `curl`
  rendered verbatim; `statusCounts` facets on the status checkboxes (`≥ n` under
  truncation).
- **M2c (landed):** grid columns (env-badged engine, copyable process ID, definition +
  version, failure time, status chip + secondary flag badges), snapshot "as of" header +
  manual Refresh (client-stamped — `/api/search` has no server `asOf`), partial-result
  banner + lower-bound labeling from `perEngine` (`ok/error`, `dlqScan`/`failingScan`
  `truncated@N`, fetched<total overflow), the SPEC §10a distinct zero states, custom
  selection footer (AG Grid Community, enterprise-import build gate).
- **Done when:** a search over 2 engines returns correctly-flagged rows incl. a
  failed-in-subprocess parent and a FAILING (retries-left) instance; killing an engine
  mid-demo degrades to a labeled partial result; a 10k-DLQ engine shows the truncation badge
  instead of lying.

## M3 — Instance detail (full-page route) + triage landing  *(LANDED 2026-07-06: backend incl. `…/tasks` + `/api/resolve`, frontend fully bound)*
- **Detail page `/inspect/{engineId}/{id}`** (deep-linkable now, not M6): vitals header with
  "why stuck" strip (exception first line, retries state, waiting-for subscriptions/timers);
  **read-only bpmn-js diagram** (pulled forward from M5) with token + dead-letter markers,
  synced selection; lazy tabs: Variables (view: typed ledger with scope groups + lazy
  virtualized json tree, size caps — never raw-JSON-primary, SPEC §4/§4a, R-UXQ-13) ·
  Errors & Jobs (four lanes, stacktrace on expand) · Tasks · Hierarchy · Timeline.
  Copy-for-ticket button.
- **Omnibox** (`GET /api/resolve`).
- **Frontend landed (2026-07-06):** three-stage route tree (`/` Stage 0 triage default ·
  `/search` Stage 1 · `/inspect/{engineId}/{id}` Stage 2; legacy root share-links 30x to
  `/search` with params verbatim). Triage landing: status-count tiles + error-group cards
  off `GET /api/triage` (asOf stamp, throttled Refresh bypass), R-SEM-12 honesty —
  failed-engine banner turns every count into `≥`, `dlqScan truncated@N` bounds the group
  counts, zero-filled sibling versions render unlinked; every count is a scope-explicit
  drill-through into a pre-filtered `/search` (echoed by the criteria panel). Stage 2:
  tab-aware deep links (`?tab=`), tabs code-split AND fetch-on-open; Audit & Notes tab live
  against `…/audit` + `…/notes` (note create incl. RESPONDER 403 messaging); typed variable
  ledger implemented + unit-tested (`inspect/variables/`: plain-language chips, explicit
  null/empty-text, booleans as words, structured summaries with 256 KiB expand cap, shadow
  badge, raw copy as escape hatch); read-only bpmn-js viewer (`DiagramCanvas.tsx`,
  NavigatedViewer + active/dead-letter markers + ⚠ overlays, watermark untouched); omnibox
  pinned in the shell — composite `engine:id` → Stage 2, anything else → business-key
  search (labeled as degraded until `/api/resolve`).
- **Detail-data backend (landed 2026-07-06):** `GET /api/resolve` (R-SEM-04 chain,
  composite short-circuit, perEngine reachability envelope) and the Stage 2 resources —
  `GET /api/instances/{engineId}/{id}` (vitals: historic-first, why-stuck, waiting-for,
  current activities = unfinished historic ∪ runtime execution positions — a dead-lettered
  async task has NO unfinished activity row, proven live), `…/diagram` (deployment
  resourcedata proxy + marker id sets), `…/variables[/{name}]` (typed ledger, R-UXQ-13:
  engine type verbatim, 256 KiB structured-preview cap, per-execution locals, historic
  projection for completed instances), `…/jobs` + `…/jobs/{jobId}/stacktrace?lane=` (four
  lanes distinct), `…/hierarchy` (both directions, depth 10 / breadth 50 rendered, exact
  childTotal), `…/timeline`. Triage `statusCounts` now synthesizes FAILED/RETRYING
  (distinct-instance counts off the failure-lane scans, FAILED precedence); search gained
  `definitionVersion` (concrete-id pushdown) and `signatureHash` (refinement-bridge
  drill-down) — error-group drill-throughs can now carry the exact signature + version.
  Proven by `DetailResolveIT` + extended `SearchServiceIT`/`TriageAggregationIT` (full
  matrix green). `…/tasks` (historic ∪ runtime user-task ledger, derived
  ACTIVE/SUSPENDED/COMPLETED state) and the vitals `telemetryUrl` (registry
  `telemetry-url-template` rendered with url-encoded values; absent → no field) landed
  2026-07-06.
- **Frontend binding (landed 2026-07-06):** vitals header (definition+version, status chip,
  business key, started/duration, why-stuck strip, waiting-for chips, "open logs" when
  `telemetryUrl` present) + diagram bound live; all tabs bound via per-segment TanStack
  Query hooks (fetch-on-tab-open): Variables (DTO→ledger adapter, server-truncated rows
  carry the explicit "load full value" hatch via `…/variables/{name}`, structured expand =
  lazy paged JsonTree — never raw-JSON-primary) · Errors & Jobs (four lanes with
  lane-diagnosis captions, stacktrace fetch-on-expand) · Tasks · Hierarchy (recursive tree,
  depth/breadth cap indicators, per-node FAILED badges, node links) · Timeline (duration
  bars scaled to the instance window, ongoing bars hatched, child links). Omnibox →
  `GET /api/resolve`: one ID-kind match navigates to Stage 2, business-key matches land on
  a pre-filtered `/search`, ambiguity renders the disambiguation panel with the
  "resolved against N of M engines" honesty line; composite `engine:id` still short-circuits
  client-side. Proven live via Playwright smoke against the dev stack.
  **Still open (M3 slivers):** copy-for-ticket button, diagram↔tab selection sync,
  per-job "open logs" links in the Errors & Jobs tab (vitals-level link is live).
- **Triage landing**: engine health strip, status counts, failure groups by normalized error
  signature with click-through, curated system views, recent-operations placeholder.
  **Aggregation-independence constraint (binding):** Stage 0 aggregations NEVER reuse the
  M2a grid-search plan to count — status counts come from query `total`s (`size=1`),
  job-lane counts from the management-collection totals trick, and error groups from the
  dedicated capped DLQ/RETRYING scan legs. Fetching grid rows to count them is a rejected
  implementation (it defeats the cache, the caps, and do-no-harm at once).
- **Triage backend (landed):** `GET /api/triage` (ARCH §4) — `ErrorSignatureNormalizer`
  (R-SEM-03 algo v1; golden corpus captured live via `docker/capture-error-corpus.py`,
  gated by `ErrorSignatureGoldenCorpusTest`), `TriageAggregationService` (virtual-thread
  fan-out; status counts from `size=1` totals incl. historic `finished:true` for COMPLETED;
  failure-lane scans with one representative-stacktrace refinement per group; definition
  sibling-version zero-fill), 20s single-flight Caffeine cache + throttled `refresh=true`
  bypass (`inspector.triage` knobs). Proven on all three profiles: `TriageAggregationIT`
  (WireMock transparent proxy — request-journal proof of cache hits and `size:1` wire
  bodies), `Triage7IT` (Jakarta error-shape drift), `TriageLegacyIT` (6.3.1 legs).
- **Done when:** from the landing, one click on an error group reaches a pre-filtered list;
  opening a stuck instance shows why it's stuck without any click; the link pastes into a
  ticket and reopens the same view.

## M4 — Corrective actions + audit + RBAC + Postgres  *(backend landed 2026-07-06; UI open)*
- **Postgres** joins the deployable: audit log, notes.
- **Build-order constraint (binding):** `Flyway V1__init.sql` FIRST → JPA entities SECOND →
  repositories THIRD. Hibernate `ddl-auto=validate` in EVERY profile including tests —
  schema comes from Flyway only, never from auto-DDL; the Java layer aligns to the database
  reality, not the other way around. (The audit table's grants, partitions and hash-chain
  column exist only if the schema is authored, not generated.)
- Single-target verb catalog tiers 0–3 (SPEC §5): retry / retry-now / trigger-timer /
  unstick-event / suspend-activate / edit-variable (the SPEC §4a editor: form-first typed
  widgets, type-lock, leaf-level json edits, lazy source mode, path-diff verification,
  compare-and-set; old value audited) /
  complete-task / suspend-definition / terminate-delete (cascade enumeration) /
  deadletter-delete (orphan warning, ADMIN).
- Guard ladder (SPEC §6): reasons, server-fresh target restatement, target-specific typed
  tokens on prod; delta-statement outcome toasts + audit links; disabled-with-reason.
- RBAC `VIEWER/OPERATOR/ADMIN`; **dual auth profile** (basic dev / OIDC prod).
- Audit & Notes tab on the instance; global operations log page.
- **Done when:** the demo failed instance is fixed end-to-end (edit variable → retry →
  completes) with the delta toast shown, the reason recorded, and the action visible in the
  instance's Audit tab; a VIEWER sees every action greyed with the right tooltip.
- **Deferred out of M4:** task reassign (v1.x — not an incident verb).
- **Backend landed (2026-07-06):** Flyway `V1__init.sql` (audit_entry range-partitioned +
  append-only guard trigger + chain_hash, instance_note, protected_instance) → JPA →
  repositories, `ddl-auto=validate` in every profile; fail-closed audit
  (**applied to ALL tiers incl. 0** — stricter than the R-AUD-01 minimum: an unaudited
  queue move is still an attribution hole) with the full `PENDING → ok|failed|unknown`
  lifecycle, dispatched-unverified error, and the stale-PENDING reconciler sweep; dual
  auth profile (basic/form dev · `oidc` OIDC) + 4-role ladder + hot-reloaded group→scope
  mapping file (R-SAFE-12) resolved at check time; verbs tier 0/1/3 (retry-job,
  trigger-timer, suspend/activate, edit-variable CAS, complete-task, unstick-event,
  terminate-delete, delete-deadletter, suspend/activate-definition) behind the full guard
  chain (scoped RBAC, read-only engine, protected instance, §6 reason ladder, tier-3 prod
  typed token vs server-fresh state); audit/notes read surface (payload role-gated
  OPERATOR+, secret-name redaction, 32 KiB snippet cap); Testcontainers-Postgres IT suite
  incl. the kill-Postgres-mid-test fail-closed proof and the CAS-conflict arc.
- **Still open in M4:** the UI half of the done-when (Audit tab, ops log page, delta
  toasts, greyed-with-reason); audit-row config events for scope-mapping reloads (M4 logs
  them); `X-Forwarded-User` engine attribution; R-AUD-07 ticketId validation/linkify;
  per-engine `audit-payload` modes, retention purge + DB role grants (OPERATIONS §6 —
  provisioning, not schema).

## M5 — Bulk + hardening (v1 close-out; the former M6)
- Grid-selection bulk as a **persisted tracked job** (R-SEM-10: state machine, startup
  reconciliation → INTERRUPTED, circuit-open dispatch pause, aggregate readout), cap 200,
  intersection-of-valid-actions, protected-instance auto-exclusion, acknowledgment gate over
  partial result sets, per-item report with the full outcome-class set.
- Security test plan execution (TEST-STRATEGY §5, independent tester); performance scenarios
  P1/P2/P4; UAT sessions (R-TEST-08); operator quick-start + RUNBOOK.md; break-glass;
  release gate per SPEC §13.
- **Done when:** kill the BFF mid-bulk → on restart the job shows INTERRUPTED with an honest
  per-item report and a continue-as-new-job affordance (R-SEM-10); a 50-instance bulk retry
  reports every outcome class honestly; the §13 gate checklist is green.

## v1.1 — Flow surgery (former M5; entry criterion R-GOV-07: ≥N audited pilot incidents
unresolvable with tier 0–1 verbs)
- change-state as a guarded form verb (activity dropdowns) with BFF-simulation preview +
  REST-body display; guardrails (MI-body block, parallel-join warning, suspended-check,
  variables-first composite = rerun-from-activity); restart-as-new with the pin-vs-latest
  definition fork. Diagram change-state *picker* is polish — only after the form verb works.
- **Done when:** a token is moved off a failed node and the instance proceeds; the preview
  shows exactly the REST call; an MI body as source is refused with the reason.

## v1.x — fast follows (each independently demoable)
1. Error-class **bulk-retry-the-group** from the triage landing.
2. **Select-all-matching-filter bulk**: server-side re-resolution at execution time, tracked
   async job in the BFF (Postgres-persisted per-item results), SSE progress, cancel,
   per-engine concurrency cap + stagger, persistent operations drawer.
3. Named saved views (localStorage) + recent searches.
4. Timeline tab polish (call-activity sub-lanes); job-lane trend sparklines on the landing.
5. **Sibling diff** (SPEC §5.2): compare endpoint over historic queries; variables/path/
   timing diffs; divergence highlighting on the shared diagram; nearest-successful-sibling
   auto-suggest.
6. Task reassign/return-to-team; "show as cURL" on every action modal.
7. External-worker job view (capability-gated, 6.8+).

## v2 — demand-driven
- **Remediation playbooks** (SPEC §5.1 — the headline): distill an exemplar's audit rows
  into a named, literal-values-only verb sequence bound to an error-class signature; replay
  through the bulk-job machinery with per-step precondition rechecks and per-item-per-step
  outcomes. Requires: v1.x bulk framework + error-class grouping + audit old-value capture
  (all landed earlier by design).
- **Migration**: single-instance with server-side `migrate/validate` first; batch + side-by-
  side diagram wizard with typed "MIGRATE" only after the single flow proves demand.
- Definition version comparison + per-version instance counts (the migration on-ramp).
- CMMN case support via the parallel `/cmmn-api` surface (row DTOs already carry `scopeType`).
- Registry CRUD UI; shared server-side saved views; k-way-merge deep paging; OIDC hardening.

## Build order inside any milestone
backend DTO → engine client call → aggregator/join logic → controller → typed frontend API
client → component. Every Flowable call gets an integration test against the dockerized
`flowable-rest` on BOTH compose profiles (no mocked Flowable responses for join logic — the
DLQ/suspended/hierarchy joins are where the bugs live).
