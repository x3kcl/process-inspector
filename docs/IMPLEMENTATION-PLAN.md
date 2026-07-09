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
  **M3 slivers (landed 2026-07-06):** copy-for-ticket button (`inspect/ticket.ts`, plain
  text: composite ID / definition+version / status / exception first line / failure time /
  deep link), diagram↔tab selection sync both directions (canvas click on a dead-letter
  node jumps to its row in Errors & Jobs — auto-expand, scroll, highlight; "show on
  diagram" per job row adds the `marker-selected` marker + scroll-to-element; selection
  also drives the Variables execution-group auto-expand), per-job "open logs" links in the
  Errors & Jobs tab (runtime-probed off `JobDto` — renders the moment the contract adds
  `telemetryUrl`, nothing until then).
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

## M4 — Corrective actions + audit + RBAC + Postgres  *(backend + action/editor UI landed 2026-07-06; ops-log page open)*
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
- **Deferred out of M4:** task reassign (v1.x — not an incident verb). **Landed in v1.x #6**
  as `reassign-task` / `unassign-task`.
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
- **Frontend landed (2026-07-06):** the M4 action UI over the live BFF, Playwright-proven
  end-to-end (real edit-variable dispatch → delta toast → audit row on the dev stack).
  Foundations: ProblemDetail parser keeping the §6 three-way distinction
  (refused / engine-rejected / dispatched-unverified) in plain-language banners
  (`actions/problem.ts`), toast host (delta statement + audit deep link, never bare
  success), cancel-focused ModalShell (env band, Esc closes, Enter never submits), the
  one non-retrying mutation hook (`api/actions.ts` — settled ⇒ re-fetch every instance
  segment + audit: server truth only, no optimistic UI), role hint from the dev-ladder
  username (no `/api/me` yet; unknown role stays optimistic, BFF 403 is the gate).
  Guard ladder: tier-0 verbs (retry-job, trigger-timer, suspend/activate) as inline
  buttons with the §5.0 two-step armed confirm on PROD for external-side-effect verbs and
  reversibility badges/plain labels from the spec'd catalog (`actions/catalog.ts`);
  tier-3 destructive modals for terminate-delete (cascade victims enumerated from the
  hierarchy tree, 'unavailable' stated when truncated) and delete-deadletter (orphan
  warning), reason ≥10 gate, PROD typed token (business key / job id) gating the
  restating confirm button; CAS 409 / fail-closed 503 / outcome-unknown 504 each get
  explicit copy, UNKNOWN blocks resubmit from the same surface.
  The §4a editor: per-row pencil (greyed-with-reason: read-only engine / ended instance /
  step-local / serializable), inline panel with forced full-value fetch + target
  restatement + old value always visible; typed widgets (parsed-echo numbers with subtype
  ranges, True/False segmented — never a toggle, offset-required dates with dual UTC
  readout, whitespace-visible text, explicit empty-text-vs-null clearing); per-session
  type unlock behind the warning; JSON leaf-edit tree (structural changes need source);
  `Form | Source` segmented switch with the CodeMirror 6 chunk lazy-loaded on first use
  (proven: no eager chunk request), Source→Form blocked while invalid, parse + 256 KiB
  warn / 5 MiB block gates; verification modal generating sentence + structural path diff
  + fixed-order warnings + exact-request expander from the SAME request object, freshness
  re-check on open blocking on drift, CAS-conflict replacement panel (three values,
  start-over-only, no overwrite-anyway). Audit tab: collapsed payload expander (old
  values for variable edits). Pure logic vitest-covered (problem/catalog/cascade/ticket/
  editState/diff — 125 tests green); `npm run lint` + `npm run build` (watermark +
  enterprise guards) clean.
- **M4 stragglers landed 2026-07-06 (same-day follow-up session):** `GET /api/me`
  (username + highest ladder role per engine via the SAME RbacAuthorizer resolution the
  guards use) with the SPA's role-greying switched from the dev-ladder username heuristic
  to it (`api/me.ts`, per-engine `roleOn`; unknown role stays optimistic); the global
  ops-log page at `/audit` (AG Grid over `GET /api/audit`, actor/action/since filters,
  target column deep-links to the instance's Audit tab); the §4a offered follow-on —
  after a successful edit-variable on a FAILED instance a sticky toast offers "Retry the
  failed job?" (live dead-letter re-check first; offered, never automatic).
- **M4 execution-local (step-local) variable edits landed 2026-07-08:** the same tier-1
  `edit-variable` verb now carries an optional `variable.executionId`. Present ⇒ the BFF
  scopes the whole leg to that execution — server-fresh restatement re-reads the execution
  and refuses a foreign/gone one (`execution-instance-mismatch` / `execution-gone`), the CAS
  pre-check reads `GET /runtime/executions/{id}/variables/{name}?scope=local` (the exact row
  the write touches, never a process-scope value shadowed down the tree), and dispatch writes
  `PUT /runtime/executions/{id}/variables/{name}` with `scope:"local"`. Absent ⇒ the
  unchanged process-scope path. The full-value fetch (`GET …/variables/{name}?executionId=`)
  is likewise scoped so a truncated step-local row is editable; audit payload records
  `scope:"local"` + `executionId` + `activityId`. Scope segregation proven against real
  flowable-rest (CorrectiveActionIT: local `amount` edited, same-named case value untouched).
  Frontend: the §4a pencil gate no longer blocks local rows, the editor header + verify
  sentence/warning say "step-local", freshness re-check is scoped.
- **Still open in M4:** audit-row config events for scope-mapping reloads (M4 logs
  them); `X-Forwarded-User` engine attribution; R-AUD-07 ticketId validation/linkify;
  per-engine `audit-payload` modes, retention purge + DB role grants (OPERATIONS §6 —
  provisioning, not schema).

## M5 — Bulk + hardening (v1 close-out; the former M6)
- Grid-selection bulk as a **persisted tracked job** (R-SEM-10: state machine, startup
  reconciliation → INTERRUPTED, circuit-open dispatch pause, aggregate readout), cap 200,
  intersection-of-valid-actions, protected-instance auto-exclusion, acknowledgment gate over
  partial result sets, per-item report with the full outcome-class set.
- **Grid-selection bulk LANDED 2026-07-06** (backend + frontend, Playwright-proven live:
  real 2-item bulk retry → drawer COMPLETED → per-item `ok` → audit envelope + item rows
  in the ops log). Backend: Flyway `V2__bulk_job.sql` (bulk_job + bulk_job_item, CHECK-
  constrained states) → JPA → `BulkJobService` — submit (cap 200, v1 verb whitelist
  retry-job/suspend/activate/trigger-timer, protected targets settled `skipped_protected`
  at submit, fail-closed envelope audit row) → sequential per-item fan-out through the
  FULL single-target guard chain (per-item audit/RBAC/guards reused, outcome mapping:
  guard-gone→`skipped`, engine-rejected→`failed`, timeout legs→`unknown`, never
  auto-retried; retry-job resolves the instance's CURRENT dead-letter jobs at dispatch =
  built-in precondition recheck), cancel (stops dispatching), startup INTERRUPTED sweep
  (dispatched→unknown, pending→not_run), verify-now per-verb precondition predicates;
  endpoints `POST/GET /api/bulk[/{id}]`, `…/cancel`, `…/items/{ordinal}/verify` (ARCH §4).
  `ProcessInstanceRow.protectedInstance` (batched registry lookup per page; null =
  store unreachable). Frontend: checkbox selection on the M2c grid feeding the pure
  Intersection-Rule module (`bulk/intersection.ts`, tested: strict per-verb intersection,
  disabled-with-reason naming the first offending row, protected auto-exclusion badge,
  200-cap copy), pinned bulk bar, submit modal with scope enumeration (count, per-engine
  split, expandable ID list) doubling as the partial-result acknowledgment gate
  ("engine X excluded — proceed anyway?" checkbox required when engines failed or scans
  truncated), and the Shell-mounted operations drawer — hydrated from `GET /api/bulk`
  (server-persisted ⇒ survives navigation AND refresh), NO optimistic state anywhere,
  polling tightens only while a job is live; state-machine chips, aggregate "N of M
  dispatched" readout, per-item sub-table, cancel, per-unknown Verify-now, and
  "continue as new job" pre-scoped to `not_run`+`failed` with `continuedFrom` lineage.
  **v1 deviations (deliberate, revisit in M5 close-out):** ~~live progress is short-poll~~
  and ~~per-engine parallel dispatch + stagger pending~~ both retired by v1.x #2 (SSE on
  `GET /api/bulk/events`; permit pool + stagger in `BulkJobService`) and ~~a circuit-open
  mid-job burned the rest of the engine's items as failures~~ retired 2026-07-08: **R-SEM-11
  pause LANDED** — a `engine-shedding-load` fast-fail now PAUSES dispatch to that engine
  (`dispatchOne` signals, the group loop breaks after re-checking on permit acquire), leaving
  undispatched items `pending` → settled `not_run` and the job **INTERRUPTED** (partial;
  "continue as new job" re-scopes `not_run`+`failed`); the fast-failed item stays a clean
  `failed`, other engine groups run on independently (`BulkJobServiceTest`, rung-1). Still
  open: destructive bulk (terminate) deferred to the tier-4 wizard. WIRE GOTCHA: Jackson serializes absent
  DTO fields as JSON null while openapi-typescript types them `?: undefined` — guard
  with `typeof x === 'string'` / `?? undefined`, never bare `!== undefined` (a null
  `continuedFrom` crashed the drawer until guarded).
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
- **Backend: LANDED 2026-07-06.** `FlowSurgeryService` + three whitelisted routes
  (`…/change-state/preview`, `…/change-state/execute`, `…/restart` — ARCH §4);
  `BpmnStructure` guardrail parser (MI scopes from the engine's `/model` JSON, gateway
  types + flow graph from the deployed XML — the /model serialization cannot distinguish
  gateway kinds, see DESIGN-REVIEW), Caffeine-cached per definition id; capability gate
  on `changeState` (≥6.4, fail-closed while unprobed); tier-2 rails with ADMIN-on-prod
  for change-state. Seed fixture `demo-flow-surgery.bpmn20.xml` (runway → parallel
  fork/join → sequential MI subprocess). Proven by `BpmnStructureTest` +
  `FlowSurgeryServiceTest` (22 unit) and `FlowSurgeryIT` (8 against live 6.8 + Postgres):
  the done-when arc below runs green end-to-end.
- **Frontend: LANDED 2026-07-06.** Simulation-first two-step `ChangeStateModal`
  (source checklist from `currentActivities`, target picker from the client-parsed
  definition XML — `surgery/activityCatalog.ts` — then the §4a-style verification step
  rendering the BFF preview verbatim: `summary` sentence, amber `warnings`,
  `simulationNote` honesty line, exact `{cancelActivityIds, startActivityIds}` payload
  expander, reason ≥10 + typed business key on prod); `RestartModal` with the mandatory
  un-defaulted pin-original-vs-latest `Segmented` fork and the post-execute
  carried/skipped-variables honesty report (`skippedVariables` name → reason table, new
  instance deep-link). Both wired into the InstanceActions row via `actionGate` extended
  with capability (`engine.capabilities.changeState`), suspended, requires-ended and
  ADMIN-on-prod gates (greyed-never-hidden). New `api/surgery.ts` mutations reuse the M4
  invalidation triad; no optimistic state. Net-new Playwright harness
  (`playwright.config.ts`, hermetic route-mocked BFF) with 3 smokes proving the
  simulation-first arc (execute is unreachable before a rendered preview and never fires
  on cancel). Diagram-click *picker* stays v1.x polish.
- **Done when:** a token is moved off a failed node and the instance proceeds; the preview
  shows exactly the REST call; an MI body as source is refused with the reason.

## v1.x — fast follows (each independently demoable)
1. Error-class **bulk-retry-the-group** from the triage landing. **Landed 2026-07-07**:
   `POST /api/bulk/error-class` (SPEC §7, ARCH §4) — coordinates-only body, server-side
   member re-resolution through the capped signature scan into the unchanged M5 machinery;
   card button per engine × defKey:vN (greyed-never-hidden), tier-3 modal (PROD token =
   definition key — a typed count would attest a stale number), operations-drawer
   auto-focus handoff (context lift shared with the bulk bar), `['triage']` invalidation
   on job settle. R-SEM-13 annotation demotion waits for group annotations (R-BAU-01).
2. **Select-all-matching-filter bulk + SSE progress. Landed 2026-07-07**:
   `POST /api/bulk/filter` (SPEC §7, ARCH §4) — criteria-only body (binding server-side
   re-resolution through `SearchService.resolveAllMatching`, the SAME plan paged to
   exhaustion; V3 raises the DB cap to the 5,000 query-bulk limit while grid/error-class
   keep 200; truncated-scan/degraded-engine/drained/over-cap all refuse, never a silent
   subset); per-ENGINE dispatch with a shared permit pool (4) + 250 ms stagger
   (`inspector.bulk.*`); `GET /api/bulk/events` SSE (id-only `bulk-job` events + 15 s ping,
   session-cookie auth — dev chain now persists Basic auth into the session; SmartLifecycle
   completes streams before Tomcat's graceful shutdown, else every stop eats the 30 s grace
   period). UI: bulk-bar filter scope (all-visible-selected affordance + standalone button),
   status-chip verb intersection (`planFilterScope`), tier-3 modal restating the criteria
   (PROD token = definition key, else single prod engine id), app-scoped `LiveProvider`
   (ONE EventSource) with debounced invalidation — drawer polling relaxes to a 30 s safety
   net while live. This retires the M5 "short-poll + sequential dispatch" deviations.
3. **Named saved views (localStorage) + recent searches. Landed 2026-07-07** (pure
   frontend): a view = a named URL search string replayed through the M2b codec — no new
   state path (`frontend/src/views/`). Four curated system views on the Stage 0 landing
   (SPEC §4; R-SEM-05 honest predicates — *Suspended > 24h (by start time)* uses
   `startedBefore`, *Failed in the last hour* uses `failedAfter`; relative windows
   materialize minute-floored at render), user-named views (save affordance in the Stage 1
   rail, same-name replace, delete on the landing), last-10 recent searches recorded only
   on successful execution with a generated criteria label. Stage 1 view strip highlights
   the chip whose canonical (key-sorted) params exactly match the current URL. Storage is
   version-enveloped (`{version: 1, items}`, corrupt/unknown → empty) for the v2
   server-side migration; hermetic Playwright smokes in `e2e/saved-views.spec.ts`.
   Column chooser + density + dark theme from the original SPEC item stay open.
4. **Timeline tab polish — call-activity sub-lanes (SPEC §4).** A call-activity row nests
   the called instance's own historic activities as a sub-lane; recursion is bounded by the
   hierarchy caps (depth 10, breadth 50/node, 500-node budget) with a `calledProcessInstanceId`
   cycle guard (R-TEST-07 — a real engine cannot cycle, so the guard is rung-1 tested). Each
   unfinished/failing node carries a joined live job state (`FAILED` = dead-letter, `RETRYING`
   = failing job with retries left); the dead-lettered **async** node is *synthesized from the
   live job lanes* because its `ACT_HI_ACTINST` row is rolled back with the failed transaction
   (phantom-node union — annotating historic rows alone would be a guaranteed false negative).
   A single `isCapped` flag marks a node whose sub-lane was truncated by any cap (breadth,
   depth, or node budget). **Job-lane trend sparklines are descoped to v2** (see below) — they
   require the R-BAU-08 snapshot/time-series store, which does not exist in v1.
5. **Sibling diff** (SPEC §5.2). **Backend landed 2026-07-07**: two read-only endpoints under
   the Stage-2 composite path, VIEWER floor, **historic queries only** (never a runtime table —
   completed siblings live only in history). `GET …/{id}/nearest-sibling` resolves the smart
   default — the most recently COMPLETED instance of the *same* `processDefinitionId`
   (`finished:true` + `sort=endTime desc`; "successful" = reached an end event, not
   dead-lettered), returning `found:false` (not an error) when a fresh version has no completed
   run. `GET …/{id}/diff/{siblingId}` composes a three-way `SiblingDiffResponse`: variable
   deltas diffed on the **256 KiB capped projection** (`InstanceDetailService.typedRow` reused;
   an over-cap pair is never fetched in full — it ships `DIFFER_BEYOND_PREVIEW`), path
   divergence as `onlyInSubject`/`onlyInSibling`/`common` activity-id sets (drive the diagram
   stroke overlay — ids only, no hue), and per-activity timing deltas (loops sum; the stalled
   open step carries a null duration + `subjectUnfinished`). The join core is pure/static and
   rung-1 tested; a manually-picked cross-definition sibling still diffs, flagged
   `sameDefinition:false`. **Frontend landed 2026-07-07**: a dedicated **Compare** tab (own
   lazy chunk) — the why-stuck strip carries a one-click "Compare with a sibling" CTA into it.
   The nearest sibling auto-populates the comparison; a manual process-instance id overrides
   it, and the choice lives in `?sibling=` so a comparison is a shareable deep link. Three
   panes: side-by-side variable diff (divergent-first, identical collapsed, +/−/±/~/= glyphs —
   no hue-only), the subject's diagram with the path divergence overlaid by **stroke style +
   ▲/△ glyphs** (solid/heavy = failed-only, dashed = sibling-only), and per-activity timing
   bars (failed over sibling, the stalled step called out). Hermetic Playwright smokes in
   `e2e/sibling-diff.spec.ts`; the pure formatting logic is vitest-covered.
6. Task reassign/return-to-team; "show as cURL" on every action modal. **— landed (v1.x #6).**
   Backend: `reassign-task` / `unassign-task` verbs (tier 1 / OPERATOR) through the existing
   action dispatcher (audit + RBAC + guard rails reused, not re-implemented); `PUT
   /runtime/tasks/{taskId}` with `{"assignee":…|null}`; active-task gate via the server-fresh
   task read. cURL is SERVER-computed (`POST …/actions/{verb}/curl`, placeholder credential,
   BFF endpoint) and rendered verbatim — NOT a client-side generator (that would break the
   search-cURL invariant and risk a live token in the DOM). Frontend: Tasks-tab row actions,
   the shared `TaskAssignModal`, `CurlPreview`. Person-centric task search stays unscheduled.
7. External-worker job view (capability-gated, 6.8+). **— landed (v1.x #7).** The fifth
   read-only job lane. Backend: `GET …/jobs/external-worker` + `ExternalWorkerJobDto` (lock
   owner/expiration), capability-gated (reuses the version-derived `externalWorkerJobs` flag —
   refuses pre-6.8 with a ProblemDetail); the count rides the vitals diagnostic summary. Wire:
   the management API has NO external-worker endpoint — sourced from the External Worker REST
   API's `/external-job-api/jobs` SIBLING context (derived from base-url by convention), verified
   live. Frontend: capability-gated fifth lane in the Errors & Jobs tab (never rendered / never
   called on pre-6.8). Tests: unit (gate + mapping + sibling-context GET), Playwright (gate both
   ways), and `ExternalWorkerJob7IT` (fetch + acquire→lock-owner) / `ExternalWorkerJobLegacyIT`
   (refuse) on the real matrix. New seed process `demo-external-worker`. **v1.x release train
   complete.**

### CMMN scope visibility — Phase 0 *(shipped 2026-07-07, R-SEM-20)*
Standalone first slice of a possible multi-engine **Case Inspector**; ships on its own and
changes **no existing count's value**. A co-deployed CMMN engine shares flowable-rest's job
tables, so its failing jobs land in the same dead-letter lane; the BPMN join already drops
them (null `processInstanceId`), but they were dropped *silently*. Phase 0 **counts** them
per-engine as `outOfScopeDeadletters` (gated on the `scopeType` capability, ~6.8+; `null` —
unknown, never a lying zero — below that), so the health strip's raw dead-letter lane
reconciles with FAILED instead of hiding untriaged server-side incidents. Backend fold in
`TriageAggregationService`; frontend `role="note"` reconciliation strip on the Stage-0 landing.
Full design + wire-shape provenance + forward plan: **`docs/CMMN-SCOPE-PHASE-0.md`**. Tests:
`OutOfScopeDeadlettersTest` (rung 1) + `TriageCmmnScopeIT` (6.8) + `TriageCmmnScopeLegacyIT`
(6.3 null gate). **Open (panel review 2026-07-07):** the frontend does not yet degrade the
count to a "≥N" lower bound under a truncated DLQ scan — see `CMMN-SCOPE-PHASE-0.md` §8.1.

## v2 — demand-driven
- **Remediation playbooks** (SPEC §5.1 — the headline): distill an exemplar's audit rows
  into a named, literal-values-only verb sequence bound to an error-class signature; replay
  through the bulk-job machinery with per-step precondition rechecks and per-item-per-step
  outcomes. Requires: v1.x bulk framework + error-class grouping + audit old-value capture
  (all landed earlier by design).
- **Migration**: single-instance with server-side `migrate/validate` first; batch + side-by-
  side diagram wizard with typed "MIGRATE" only after the single flow proves demand.
- **Job-lane trend sparklines** on the Stage-0 landing (per-engine dead-letter / timer /
  executable / suspended counts over time) — descoped here from v1.x #4 because v1 exposes
  only *live* job-lane counts; a trend needs the **R-BAU-08** snapshot/time-series store
  (ranked with maintenance snapshots + per-definition volume trends).
- Definition version comparison + per-version instance counts (the migration on-ramp).
- **CMMN Case Inspector — Phases 1-3** (Phase 0 counting already shipped in v1, above).
  `docs/CMMN-SCOPE-PHASE-0.md` §7 is the authoritative source of truth for the architecture,
  sequencing, and the wire-shape constraints; it deprecates the older one-line "CMMN case
  support" bullet. **Phase 1** — Unified Search: promote `outOfScopeDeadletters` from a scalar
  to a drillable CMMN scope facet by fetching both management DLQ lists and merging by job `id`;
  bounded/paged/`truncated@N` like the BPMN scan; **scope-typed lane sets** (ACTIVE/FAILED/
  COMPLETED/TERMINATED, no SUSPENDED).
  - **Phase 1 — first slice LANDED 2026-07-08 (full-stack).** The scalar is now DRILLABLE:
    `GET /api/triage/engines/{id}/out-of-scope-deadletters` (`CmmnScopeService` +
    `CmmnScopeController`, VIEWER floor) enumerates the CMMN dead-letters from the `/cmmn-api`
    sibling context (`FlowableEngineClient.cmmnApiBase` + `listCmmnDeadLetterJobs`), keeping
    rows with a non-null `caseInstanceId` (the shared cmmn-api DLQ list also projects BPMN jobs
    with null case attribution — spike Q1); bounded by `dlq-scan-cap`, paged, `truncated` lower
    bound; gated 6.8+ via `scopeType` (pre-6.8 refused with a ProblemDetail — the cmmn context
    is DLQ-blind there). Frontend: the Stage-0 "≥N CMMN jobs not triaged here" note gains a
    "View jobs" drill into a read-only `CmmnScopeDrawer` (readable case type, element, exception,
    case id, retries; same `≥` lower-bound honesty; no corrective action). Tests:
    `CmmnScopeServiceTest` (rung-1: discriminator, mapping, gate ordering, definition-resolution
    dedup + degrade) + drill assertions folded into `TriageCmmnScopeIT` (rung-4, live 6.8) +
    hermetic `e2e/cmmn-scope.spec.ts`. Schema regen'd (additive).
    - **Case key/name resolution LANDED 2026-07-08 (same slice, 2nd increment).** A CMMN
      `caseDefinitionId` on a job row is a bare uuid (NOT `key:version:uuid`), so the readable
      key/name are resolved by a **bounded, distinct-id** lookup against
      `cmmn-repository/case-definitions/{id}` (`FlowableEngineClient.getCmmnCaseDefinition`;
      N+1 on distinct DEFINITIONS, never on jobs; a 404/undeployed def degrades that entry to
      null, never fails the slice). `CmmnDeadLetterJob` gains `caseDefinitionKey`/
      `caseDefinitionName`; the drawer leads each row with the case type.
    - **Scope-typed lane facet LANDED 2026-07-08 (3rd increment, full-stack).** The drawer is now
      a scope view, not just a job list: `GET /api/triage/engines/{id}/cmmn-scope` →
      `CmmnScopeFacet` (`CmmnLaneCounts{active,failed,completed,terminated}` + the inlined
      dead-letter detail). `active`/`completed`/`terminated` are count-only (`size=1`)
      `historic-case-instances?state=` queries (`FlowableEngineClient.countHistoricCmmnCaseInstances`),
      each degrading to `null` (unknown, never a misleading `0`); `failed` = distinct
      `caseInstanceId`s among the dead-letters (a lower bound when that scan truncated). **No
      SUSPENDED lane** (cases can't suspend, spike Q2); the frontend renders the tiles off a
      dedicated `CMMN_STATUSES` const (`api/model.ts`), never the SUSPENDED-carrying BPMN
      `ALL_STATUSES` (§7 M4 hazard closed). Tests: `CmmnScopeServiceTest` (+3: lane counts,
      distinct-FAILED, per-lane degrade-to-null, gate-before-any-query) + `TriageCmmnScopeIT`
      (+1, rung-4 live 6.8) + `e2e/cmmn-scope.spec.ts` lane-tile assertions. Also hardened the IT
      seed-await to key on the per-run `caseInstanceId` (`EngineSeed.cmmnDeadletterPresentForCase`)
      — the old needle-keyed await could short-circuit on a parallel session's same-seed residue.
    - **Merge slice DESCOPED 2026-07-08 (near-zero-yield).** The originally-planned process-api↔
      cmmn-api merge-by-`id` reconciliation is obviated by the `?scopeType=cmmn` server-side filter
      that shipped in the first slice: the cmmn-api leg already spends the whole `dlq-scan-cap` on
      CMMN rows, so it strictly dominates the diluted process-api orphan window — the merge yields
      no rows in the normal case, and its "degraded — Unknown case" fallback can't fire honestly
      (a null-pid process-api orphan is only a *candidate* CMMN job; confirming it needs a by-id
      hydration that, on success, returns FULL context, not a degraded row). See §7.
  **Phase 2 — LANDED 2026-07-08 (full-stack).** Polymorphic Stage-2 CMMN detail at
  `/case/{engineId}/{caseInstanceId}` — the read-only sibling of `/inspect`: a `cmmn-js` case
  diagram (with an honest no-layout state for a DI-less definition) + a plan-item state-machine
  timeline. Backend `CaseDetailService`/`CaseDetailController` (three `GET /api/cases/…` endpoints,
  VIEWER floor, gated 6.8+ via a shared `CmmnCapabilities.requireScopeType`); FAILED/RETRYING
  joined by `planItemInstanceId` (NOT the job's `elementId`, which is the plan-item DEFINITION id —
  the load-bearing wire trap); the plan-item timeline is **runtime-only** on 6.8 (no historic
  plan-item REST API — an ended case degrades honestly). The watermark guard was generalized to
  `/(bjs|cmmn)-powered-by/i` **first** (cmmn-js 0.20 actually emits the same `bjs-powered-by`
  class; the generalization is forward defense). Frontend: `CasePage` + lazy `cmmn-js` chunk +
  `CaseDiagramCanvas` + a pure `planItemModel` timeline; the Phase-1 drawer's case ids became
  links. New DI-bearing seed `docker/processes/demo-case-detail.cmmn.xml`. Tests:
  `CaseDetailServiceTest` (rung-1, incl. the Q7 join trap) + `CaseDetailIT` (rung-4 live 6.8) +
  `planItemModel.test.ts` + `e2e/case-detail.spec.ts` (incl. a real in-browser cmmn-js render).
  Live-verified vs real 6.8. Full design + wire provenance: **`docs/CMMN-CASE-DETAIL-PHASE-2.md`**.
  - **Phase 3 — LANDED 2026-07-08 (full-stack): CMMN dead-letter retry & delete.** The read-only
    case detail becomes actionable for the two dead-letter verbs a co-deployed CMMN case needs —
    **Retry job** (tier 0 / RESPONDER, inline confirm) and **Delete dead-letter job** (tier 3 /
    ADMIN, typed-confirm modal) — under the FULL `corrective-actions` rails. Key design call: the
    BPMN `CorrectiveActionService` dispatcher was **generalized** (an `ActionScope.CMMN` seam), NOT
    forked into a parallel service — the rails are scope-neutral (`audit_entry` keys on a generic
    instance id). Two seams differ: the by-id restatement reads `cmmn-management/deadletter-jobs/{id}`
    (cap-free, owner=`caseInstanceId`, tier-3 token = the job id) and the one engine call is the
    `/cmmn-management/deadletter-jobs/{id}` sibling — `POST … {"action":"move"}` for retry, `DELETE …`
    for delete (both byte-identical to process-api — live-proven 6.8, HTTP 204). Route
    `POST /api/cases/{engineId}/{caseInstanceId}/actions/{verb}` (+`/curl`, BFF-targeted);
    capability-gated on `scopeType`; non-CMMN verbs refused. `CaseDetail.failing` carries the
    dead-letter `jobs` list; the CasePage offers a per-job retry + a scope-honest delete (dedicated
    `CaseDeleteModal` — no BPMN change-state rescue for a case), and the "read-only" badge is
    retired. Tests: `CmmnCorrectiveActionServiceTest` (rung-1, 9) + `CmmnCorrectiveActionIT` (rung-4
    live 6.8 + Postgres, 2 — retry + delete over two concurrently-failing cases) + `case-detail.spec.ts`
    retry + delete-gated + delete-modal e2e. SPEC §5.3, ARCH §4,
    `docs/CMMN-CASE-DETAIL-PHASE-2.md` §9, `docs/CMMN-SCOPE-PHASE-0.md` §7.
- Registry CRUD UI; shared server-side saved views; k-way-merge deep paging; OIDC hardening.

### v2/M4 — State store + snapshot store: architectural boundary *(decided 2026-07-07, pre-build)*
The BFF shifts from transient proxy to **stateful telemetry aggregator**. Boundary decisions
locked before build so the milestone doesn't re-litigate them:

- **Ingestion = scheduled polling, NOT a message broker.** Event-stream ingestion (Flowable
  history events → JMS/Kafka) is rejected for v2.0: it is a **non-REST channel into the
  engine** (violates the strictly-via-REST iron rule), can't survive the heterogeneous
  multi-engine V6/V7 premise without per-engine broker wiring + capability gating, and adds
  a second new stateful dependency in the same milestone that already introduces Postgres
  (do-no-harm). The sampler is an `@Scheduled` **virtual-thread** job running the existing
  **Stage-0 `count-only`/`size=1`** aggregation on a 30–60s cadence — the snapshot store IS
  the mechanism that keeps the trend UI off the live engine.
- **Keep the door open:** a `SnapshotSource` seam sits between ingestion and the store; the
  poller is the only v2.0 impl, but store/query tiers depend on the interface. An
  event-driven source can drop in **per-engine, capability-gated** later, on real demand,
  without touching the snapshot schema or the UI query path.
- **Telemetry table = narrow wide-row time-series** (`engine, lane, count, sampled_at`),
  **not** JSONB. JSONB (+ GIN only where the UI filters) is reserved strictly for
  variable-snapshot / audit-context blobs.
- **Idempotent samples:** upsert keyed `(engine, lane, sampled_at_bucket)` so scheduler
  overlap/restart can't double-count. A poll is not a mutation — no corrective-action rails.
- **Bulkhead sharing:** the sampler competes with live user queries for each engine's
  Resilience4j permits — give it its own thin lane or a low-priority share so a burst of
  trend polls can't starve an interactive search.
- **Retention (400-day revFADP, R-BAU-08):** partition-by-range with **drop-partition**
  (never `DELETE`) for BOTH the snapshot time-series and the audit rows, so the `@Scheduled`
  sweep never locks the active partition.
- **Boot resilience:** container-level DB readiness gate (init smoke test) so the Java
  service only dials Postgres when it is genuinely ready — app-level retries alone are too
  fragile for enterprise boot-sequence race conditions.
- v1 `localStorage` payloads (Saved Views, Recent Searches) migrate to relational tables
  keyed to user identity via Flyway (`ddl-auto=validate` holds — no auto-DDL).

- **Slice 1 — ingestion backbone LANDED 2026-07-08 (backend, full-stack pending):** the
  store + sampler half of the milestone. `V5__triage_snapshot.sql` adds the narrow
  `(engine_id, lane, count, sampled_at)` time-series, `PARTITION BY RANGE (sampled_at)` +
  a DEFAULT catch-all, with a partitioned `UNIQUE (engine_id, lane, sampled_at)` as the
  `ON CONFLICT` arbiter (idempotent bucketed upsert — a poll is not a mutation, no audit
  rail). Lanes are the Stage-0 status chips + `OUT_OF_SCOPE_DLQ`; a NULL out-of-scope count
  writes NO row (never a fabricated zero). The `SnapshotSource` seam (`io.inspector.snapshot`)
  keeps the door open for an event-driven source; the only v2.0 impl (`PollingSnapshotSource`)
  re-runs `TriageAggregationService.aggregate(BACKGROUND)` — ONE source of truth for the
  FAILED/RETRYING synthesis. `SnapshotSampler` (`@Scheduled` fixedDelay + `ApplicationReadyEvent`,
  store-down = skipped cycle) buckets via `SnapshotBucket` and upserts. **Bulkhead sharing
  implemented as `FlowableEngineClient.CallPriority`:** the sampler runs on a SEPARATE thin
  per-engine lane keyed `{id}:sampler` (Resilience4j `sampler` bulkhead, 2 permits / 5s wait +
  its own breaker) so a trend poll can never starve the 8 interactive permits. Retention is
  `SnapshotPartitionMaintainer` — create-ahead current+next month, drop-behind past 400 days
  (DROP TABLE, never DELETE); the DEFAULT partition means a write never fails on missing
  housekeeping. Config `inspector.snapshot.{enabled,sample-interval,bucket-width,retention-days}`
  (OFF in docker-free test profiles). Tests: rung-1 `SnapshotBucketTest` /
  `SnapshotPartitionsTest` / `PollingSnapshotSourceTest` / `SnapshotSamplerTest` (in CI's unit
  job) + rung-4 `SnapshotSamplerIT` (live 6.8 + Testcontainers Postgres — sample lands, upsert
  idempotent, partitions created; LOCAL-ONLY, not in ci.yml itClass). Boot-readiness gate and
  the localStorage→relational migration remain open. **Slice 2 (next):** the trend query API +
  typed frontend client + job-lane sparklines on the Stage-0 lanes.

- **Slice 2 — trend read + sparklines LANDED 2026-07-08 (full-stack):** the visible R-BAU-08
  payoff. `GET /api/triage/trends?hours=` (`TriageTrendService` + `TriageController`, one windowed
  query grouped into per-`(engine,lane)` ascending series; `hours` clamped to 30 days; open like
  the dashboard). Frontend: a self-contained inline-SVG `LaneSparkline` under each Stage-0 status
  tile, fed by `useTriageTrends`; the tile is global so the pure `globalLaneSeries` sums the
  per-engine series by bucket, and `toPolyline` maps to the line (flat → mid-height, not pinned to
  zero). A lane with no history renders nothing; meaning lives in the line shape + aria-label, hue
  only echoes the chip (the "hue is redundant" convention). Tests: rung-1 `TriageTrendServiceTest`
  + `sparkline.test.ts`, rung-3 `TriageTrendApiSpringTest` (endpoint/clamp/JSON shape).
  `NoDbTestSupport` gained the `SnapshotCountRepository` mock (an always-on service now reads it).
  Schema regenerated from the running BFF (never hand-edited). Browser-verified end-to-end against
  a seeded store — sparklines render with correct global sums + labels.

- **Boot-readiness gate — ALREADY SATISFIED (docker-compose.demo.yml):** the containerized BFF
  gates on `depends_on: postgres: { condition: service_healthy }`, and the postgres service's
  healthcheck is a target-specific `pg_isready -U inspector -d inspector` — the container-level
  gate the boundary decision called for (app-level connect retries alone were the rejected
  fragile path). No app change needed; the remaining v2/M4 item is the localStorage→relational
  migration below.

- **localStorage→relational (Saved Views + Recent Searches) — LANDED 2026-07-09 (full-stack):**
  the last v2/M4 boundary item — v1 localStorage payloads now persist per-user in the BFF (SPEC
  §8). `V6__saved_view_recent_search.sql`: `saved_view` (`UNIQUE(owner,name)` = upsert-by-name)
  + `recent_search` (`UNIQUE(owner,search)` = dedupe; cap-10 in the BFF). Entities/repos +
  `ViewStoreService` (ownership-scoped: every read/write keyed on `authentication.getName()`,
  never a client field) + `ViewsController` (`GET·PUT /api/views`, `DELETE /api/views/{id}`,
  `GET·POST /api/recents`; VIEWER floor). System views (R-SEM-05 relative windows) stay
  client-derived. Frontend: `useViewStores` rewritten from `localStore` to TanStack Query against
  the server (same `SavedViewsApi` shape — consumers barely change); `useRecordRecentSearch` is
  now a stable hook; a one-time `useLegacyViewMigration` (run in `Shell` once authenticated) pushes
  any pre-v2 localStorage entries to the server then clears the keys (best-effort, idempotent). The
  dead `localStore.ts` + client upsert/dedupe/cap helpers were removed (that logic now lives
  server-side, tested there). Tests: rung-1 `ViewStoreServiceTest`, rung-3 `ViewsApiSpringTest`
  (ownership: a crafted `owner` body field is ignored; cross-user delete → 404), rung-4
  `ViewStoreIT` (real Postgres: upsert-by-name, owner isolation, recents cap). Browser-verified
  end-to-end: save→persist-across-reload, record recent→persist, delete→gone. `NoDbTestSupport`
  gained both repo mocks. **v2/M4 milestone COMPLETE.**

## Build order inside any milestone
backend DTO → engine client call → aggregator/join logic → controller → typed frontend API
client → component. Every Flowable call gets an integration test against the dockerized
`flowable-rest` on BOTH compose profiles (no mocked Flowable responses for join logic — the
DLQ/suspended/hierarchy joins are where the bugs live).
