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

## M0 — Scaffold _(done — only the listed slice-0 stragglers open)_

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

## M1 — Engine Registry + health _(landed, incl. the header-strip UI)_

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
  _(landed: `HeaderStrip` off the shared 30s `/api/engines` poll — literal PROD/TEST/DEV
  token + distinct border per band, accent color demoted to a dot, four lanes with DLQ
  alarm, oldest-exec/overdue-timer alarms, unreachable engines render as warning cards
  without blanking healthy ones)_.

## M2 — Search & results _(landed: M2a + M2b + M2c incl. the UI)_

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
  _M2b UI landed:_ the collapsible search rail with the full filter set; **URL-encoded
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

## M3 — Instance detail (full-page route) + triage landing _(LANDED 2026-07-06: backend incl. `…/tasks` + `/api/resolve`, frontend fully bound)_

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

## M4 — Corrective actions + audit + RBAC + Postgres _(backend + action/editor UI landed 2026-07-06; ops-log page open)_

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
  - fixed-order warnings + exact-request expander from the SAME request object, freshness
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
- **M4 close-out — DESIGN LOCKED 2026-07-09** (five-seat panel + Gemini adversarial;
  `docs/M4-CLOSEOUT.md` v1.0). The five "still open" items — audit-row config events for
  scope-mapping reloads (R-SAFE-12), `X-Forwarded-User` engine attribution (optional bonus,
  SPEC §9/ARCH §6), R-AUD-07 ticketId validate/linkify/filter (SHOULD-v1.x), per-engine
  `audit-payload` modes (R-AUD-03), and retention purge + DB role grants (R-AUD-03) — plus a
  new **R-AUD-10** config-event primitive. The review reshaped two of them: the config-event
  failure policy is a **trichotomy** (fail-to-previous / fail-closed-ordering / fail-closed),
  NOT blanket fail-open (a scope-mapping-reload fail-open was a silent privilege-escalation
  hole); and the retention purge is **BFF-orchestrated through a `SECURITY DEFINER
purge_audit()`** (age + legal-hold DB-enforced) after the panel found `audit_entry` has **no
  monthly partitions today** (only DEFAULT — "drop old partitions" is a no-op) and that an
  external second-writer forks the JVM-serialized hash chain. Slices:
  **S0** role/prod-like scaffolding _first_ (grant regime under every later slice: non-owner
  `inspector_app`, `USAGE` on `audit_entry_seq`, `ALTER DEFAULT PRIVILEGES`, `REVOKE
UPDATE/DELETE/TRUNCATE`, grant-level negative test, `prod-like` compose + Testcontainers-PG
  gate — none of which exist yet) → **S1** config-event primitive + scope-mapping reload events
  → **S2** `audit-payload` modes (V8 registry column; fix the pre-existing List-blind `redact()`;
  govern `response_snippet`) → **S3** ticketId validate/linkify/filter → **S4** X-Forwarded-User
  send-side (V9 registry column; explicit actor propagation, NOT `SecurityContextHolder`) →
  **S5a** partition maintainer + carve (V10) → **S5b** `legal_hold` + `purge_audit()` +
  orchestrator (V11). Registry-schema slices serialized (S2→S4) to avoid `Vn` collision with the
  parallel Registry-CRUD / Migration worktrees. Each slice lands its own SPEC §9 / OPERATIONS §6
  / DATA-CLASSIFICATION §3 / ARCH §6 / register / TRACEABILITY / TEST-SCENARIOS / TEST-STRATEGY
  §11 / RUNBOOK lockstep on green merge.
  **Landed:** S0 (PR #25 — `deploy/sql/audit-roles.sql` + grant-level `AuditRoleGrantsIT`);
  S1 (`AuditService.recordConfigEvent` sentinel-keyed single-shot terminal row + config-event
  read-RBAC; `ScopeMappingService` now writes the R-SAFE-12 reload to the ledger with
  fail-to-previous emit-outside-the-lock); S2 (per-engine `audit-payload` modes — `V8`
  `engine_registry.audit_payload` + `AuditPayloadMode`/policy resolved at the mutation call site;
  the pre-existing List-blind `redact()` leak fixed + recursion into variable containers keeps
  NAMES / masks values; the mode governs `response_snippet` too; minimization by default =
  `redacted`); S3-backend (R-AUD-07 ticketId — `inspector.audit` config `ticket-id-pattern` /
  `ticket-required-on` / boot-validated `ticket-url-template`; `TicketPolicy` guard wired into
  CorrectiveAction + FlowSurgery + Migration (bulk enforced per-item), CR/LF-strip + refuse-
  over-long + pattern + required-on-by-environment; `GET /api/meta` exposes the template;
  `GET /api/audit` gains a `ticketId` filter); S3b (frontend — `ticketHref` safe linkify
  [encodeURIComponent + `new URL` http(s) re-check + `rel=noopener noreferrer`, plain-text
  fallback], `useMeta` hook, linkified ticketId in the `/audit` grid + the instance Audit tab,
  ticketId filter input on `/audit`); S4 (X-Forwarded-User send-side — `V9`
  `engine_registry.forward_user` off by default + `EngineConfig`/`Row`/mapper; a per-thread
  `ForwardedActor` set at each mutation dispatch from the audit row's OWN actor
  (`AuditEntry.forwardedIdentity()`, break-glass → `break-glass-<user>`) — **not**
  `SecurityContextHolder` (empty on the bulk virtual-thread fan-out), sanitized + cleared-in-
  `finally`; a write-client-only request interceptor installed iff `forward-user`, reusing the
  S3 `evict()` rebuild on a flip; `InboundForwardedUserFilter` ingress-scrubs any client-supplied
  inbound `X-Forwarded-User` so it is never reflected (D2c); config-load **hard-WARN**, no
  environment auto-refuse — "outside the ARCH §6 trust fence" is not a modeled attribute and §6
  recommends the flag for the possibly-prod embedded-engine (flap) case. Design-only vs SPEC §9 /
  ARCH §6 — no MUST/requirement id is claimed); S5a (audit monthly-partition substrate — `V10`
  carves any existing `audit_entry_default` rows into `audit_entry_YYYY_MM` children and
  create-aheads current+next, append-only never relaxed [the guard trigger is BEFORE UPDATE OR
  DELETE; the carve is DETACH/CREATE/INSERT/TRUNCATE/ATTACH only, `seq`+`chain_hash` copied
  verbatim, atomic in Flyway's single tx]; `AuditPartitionMaintainer` [mirrors
  `SnapshotPartitionMaintainer`] create-aheads current+next at startup+daily and does NOT drop
  [retention DROP is S5b's `SECURITY DEFINER purge_audit()` under the ops role]; a guard raises the
  stable `AUDIT_DEFAULT_PARTITION_NONEMPTY` ERROR marker whenever a row lands in DEFAULT; owner
  CREATE direct [the prod non-owner-role SECURITY DEFINER create-helper is deferred to S5b];
  `AuditPartitionCarveIT` proves the carve empirically [no row loss, correct routing, chain
  preserved, trigger survives]); S5b (audit retention purge + legal hold — `V11` `legal_hold`
  table + `SECURITY DEFINER purge_audit(partition, cutoff)` [DB-enforced 400-day floor + whole-
  partition age + legal-hold via `LOCK legal_hold IN SHARE`; partition resolved from the catalog,
  DEFAULT/non-child/parse-fail refused; `format('DROP TABLE %I')` injection-safe; app role
  `EXECUTE`-only, never raw DROP]; `AuditRetentionPurger` @Scheduled single-writer orchestrator
  writing a per-partition **chain-boundary checkpoint** config event [last-dropped + first-surviving
  `seq`/`chain_hash`] BEFORE each drop — fail-closed [`AUDIT_RETENTION_PURGE_ABORTED` if unauditable];
  `LegalHoldService` + ADMIN `/api/admin/legal-holds` set/release [fail-closed audited config events,
  human actor, compensate-on-failure]; `inspector.audit.retention-days` [default 400, boot-refused
  below the DB floor]; `audit-roles.sql` folds in `EXECUTE purge_audit` + `legal_hold` grants;
  end-to-end `AuditRetentionPurgerIT` proves the whole flow against real Postgres).
  **★ M4 CLOSE-OUT COMPLETE (S0–S5b all merged, each CI-green).**
  The `audit_config_event_failures_total` counter is reserved for the R-OPS-02
  telemetry milestone (no metric stack exists yet — nor does `audit_insert_failures_total`);
  until then the failure surfaces as the stable `AUDIT_CONFIG_EVENT_FAILURE` ERROR marker.
  Deferred to a follow-on: auditing an in-app `audit-payload` FLIP (`config-audit-payload-mode-change`,
  D4f) once an admin edit path exposes the field; operator-authored `reason`/`ticketId` free-text
  is governed by DATA-CLASSIFICATION §4 handling rules (redacting the accountability reason would
  defeat the point), not payload minimization.

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
  mid-job burned the rest of the engine's items as failures~~ retired 2026-07-08 (pause-and-
  give-up only) then **fully retired 2026-07-12, issue #101: R-SEM-11 pause-AND-RESUME
  LANDED** — the 2026-07-08 pass only stopped burning the rest as failures; it never actually
  resumed, so a transiently-tripped breaker still gave up the whole engine group. Now a
  `engine-shedding-load` fast-fail triggers a BOUNDED wait (default 20s, a hair past the
  "engine" breaker's own 15s `wait-duration-in-open-state`), polling `GuardedCaller.isOpen`
  rather than dispatching a doomed call; if the breaker leaves OPEN within the bound, the SAME
  item retries ONCE (safe — `CallNotPermittedException` guarantees the first attempt never
  actually dispatched, so no double-send risk) and — on success — the REST of the engine
  group's items dispatch normally, never paused. Only when the bound is exceeded does the
  service fall through to the original 2026-07-08 behavior: PAUSE the rest of this engine's
  dispatch (`dispatchOne` signals, the group loop breaks after re-checking on permit acquire),
  leaving undispatched items `pending` → settled `not_run` and the job **INTERRUPTED**
  (partial; "continue as new job" re-scopes `not_run`+`failed`); other engine groups run on
  independently throughout. Per-item outcomes stay truthful either way: a recovered item ends
  up its REAL dispatch outcome, never mislabeled by the transient trip
  (`BulkJobServiceTest`, rung-1, 2 new cases). WIRE GOTCHA: Jackson serializes absent
  DTO fields as JSON null while openapi-typescript types them `?: undefined` — guard
  with `typeof x === 'string'` / `?? undefined`, never bare `!== undefined` (a null
  `continuedFrom` crashed the drawer until guarded).
- **Tier-4 destructive-bulk wizard (SPEC §6/§7, issue #100) — landed, `terminate-delete`
  only.** `POST /api/bulk/destructive/preview` (read-only scope enumeration, re-plans
  server-fresh, never trusted by submit — same doctrine as migrate/change-state preview) +
  `POST /api/bulk/destructive` (re-resolves and re-validates everything server-fresh:
  refuse-unscoped, ADMIN hard-gated per target engine at the door, typed-count vs. a FRESH
  resolution → `409 bulk-count-drift` on mismatch). Reuses the filter door's exhaustive
  resolution (`BulkFilterResolution`, extracted so both doors share the same
  degraded/truncated/drained/cap honesty checks) and `BulkJobService`'s existing
  reauth-at-submit convergence pattern (`DESTRUCTIVE_BULK_VERBS`, a separate stricter
  whitelist, via a new `submitDestructive` package door). **Security finding fixed as part of
  this issue:** `CorrectiveActionService.execute()`'s tier-3 reauth re-check and per-instance
  typed-confirm-token were unconditional — the moment a tier-3 verb entered bulk, a
  long-running job would re-challenge per item once the freshness window elapsed mid-dispatch
  (misclassified as a generic `failed` item, no auditId). Fixed with a dedicated
  `executeBulkItem()` entry point that skips exactly those two live-session-shaped checks
  (both already covered once, upstream, at bulk submit) — every other rail unchanged.
  Frontend: `DestructiveBulkWizard` (auto-preview on open, per-engine scope readout, typed-count
  gate reusing `useProdGuard`), a standalone ADMIN-gated `DestructiveBulkEntry` beside
  `BulkBar` (not folded into its SELECTION/FILTER intersection-rule machinery — destructive
  bulk is FILTER-scoped only). **Deferred, documented, not silently dropped:**
  `delete-deadletter`-at-scale needs a job-level (not instance-level) scope resolver — a
  distinct, mechanically similar follow-up.
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
  definition fork. Diagram change-state _picker_ is polish — only after the form verb works.
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
  on cancel). Diagram-click _picker_ stays v1.x polish ✅ **LANDED 2026-07-13, issue #102**
  — see below.
- **Done when:** a token is moved off a failed node and the instance proceeds; the preview
  shows exactly the REST call; an MI body as source is refused with the reason.

### #102 — Flow-surgery polish: diagram-click picker + rerun-from-activity _(✅ LANDED 2026-07-13, issue #102)_

The two items this section explicitly deferred as v1.x polish ("Diagram change-state
_picker_ is polish", "Diagram-click _picker_ stays v1.x polish").

_Scope check before building:_ issue #102's own title says "rerun-from-activity
composite" as "terminate here + restart from activity X" — but Flowable's REST API has
no start-at-activity primitive (confirmed via `ProcessInstanceBuilder`'s decompiled
interface — only `startEventId`, never an arbitrary node), so that literal reading would
need a brand-new ADMIN/tier-3/IRREVERSIBLE 3-call composite (terminate → start fresh →
change-state) that neither this section nor `TEST-SCENARIOS.md`'s TS-VERB-09 ever
scoped. Confirmed with the user before building: implement the documented version —
"variable edits applied, then move — one guided composite, both halves audited" (the
exact TS-VERB-09 wording, "variables-first composite = rerun-from-activity" above) — on
the SAME still-live instance, staying at the existing OPERATOR/tier-2 change-state floor.

_Shipped — diagram-click picker:_ `DiagramCanvas.tsx` gains two optional multi-select
props (`pickerSourceIds`/`pickerTargetIds`), diffed against the previous render and
applied as two new marker classes (`marker-picker-source`/`-target`) + lettered S/T
overlay badges — same "stroke style + glyph, never hue alone" discipline as the
sibling-diff markers, and the same containment (an id the diagram doesn't know can't
kill the render). `element.click` reporting is unchanged; a new caller-owned mode toggle
(`ChangeStateModal`'s own `pickerMode` state, a `Segmented` control) decides whether a
click adds to `sources` or `targets`, validated against the SAME eligibility sets the
checklist already uses (`currentActivities` for sources, the parsed catalog for
targets) — an ineligible click surfaces an inline hint rather than silently no-opping.
The diagram is a supplementary picking surface, never the only one — the checklist keeps
working unchanged (R-UXQ-02: every diagram fact needs a textual twin).

_Shipped — rerun-from-activity:_ new `RerunFromActivityModal.tsx`, three phases, **zero
new backend endpoint** — a frontend-only composite reusing two already-audited,
already-tested primitives verbatim: a new lightweight `EditStep` (case-scope scalar
variables only — string/number/boolean; JSON variables are explicitly out of this
composite's scope, with an inline pointer to the Variables tab) builds an `ActionRequest`
and hands it to the **existing, unmodified** `VerifyModal` (the same component the
standalone variable editor uses) for the edit half; on a successful `edit-variable`
dispatch, the wizard transitions straight into the **existing, unmodified**
`ChangeStateModal` (inheriting its own new diagram-click picker for free) for the move
half. Both halves audited separately, exactly as if the two verbs had been run one after
another by hand. Entry point in `InstanceActions.tsx`, gated on the same
`changeStateGate` as the standalone Change-state button (the stricter of its two
constituent verbs' floors).

_Tests:_ two new Playwright e2e smokes in `flow-surgery.spec.ts` (hermetic route-mocked,
extending the existing simulation-first-execute-never-fires harness): the diagram-click
picker toggling both lists via `data-element-id` clicks (bpmn-js's own stable per-element
attribute — the text label itself is intercepted by an invisible hit-rect and isn't a
reliable click target) plus the ineligible-node hint; the full rerun-from-activity arc
asserting `edit-variable` dispatches exactly once and the wizard lands on the move step
with zero premature `change-state/execute`/`restart` calls. Full e2e suite (42 specs)
green, no regressions in the shared `DiagramCanvas`/`InstanceActions` components other
features also exercise.

## v1.x — fast follows (each independently demoable)

1. Error-class **bulk-retry-the-group** from the triage landing. **Landed 2026-07-07**:
   `POST /api/bulk/error-class` (SPEC §7, ARCH §4) — coordinates-only body, server-side
   member re-resolution through the capped signature scan into the unchanged M5 machinery;
   card button per engine × defKey:vN (greyed-never-hidden), tier-3 modal (PROD token =
   definition key — a typed count would attest a stale number), operations-drawer
   auto-focus handoff (context lift shared with the bulk bar), `['triage']` invalidation
   on job settle. R-SEM-13 annotation demotion waits for group _annotations_ (R-BAU-03,
   still open) — NOT R-BAU-01's _acknowledge_ verb (built 2026-07-11, issue #97's own text
   originally conflated the two the same way this line did).
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
   (SPEC §4; R-SEM-05 honest predicates — _Suspended > 24h (by start time)_ uses
   `startedBefore`, _Failed in the last hour_ uses `failedAfter`; relative windows
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
   = failing job with retries left); the dead-lettered **async** node is _synthesized from the
   live job lanes_ because its `ACT_HI_ACTINST` row is rolled back with the failed transaction
   (phantom-node union — annotating historic rows alone would be a guaranteed false negative).
   A single `isCapped` flag marks a node whose sub-lane was truncated by any cap (breadth,
   depth, or node budget). **Job-lane trend sparklines are descoped to v2** (see below) — they
   require the R-BAU-08 snapshot/time-series store, which does not exist in v1.
5. **Sibling diff** (SPEC §5.2). **Backend landed 2026-07-07**: two read-only endpoints under
   the Stage-2 composite path, VIEWER floor, **historic queries only** (never a runtime table —
   completed siblings live only in history). `GET …/{id}/nearest-sibling` resolves the smart
   default — the most recently COMPLETED instance of the _same_ `processDefinitionId`
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
   the shared `TaskAssignModal`, `CurlPreview`. Person-centric task search **— landed (#99,
   SPEC §4d).** `GET /api/tasks?person=` (`PersonTaskSearchService`, same fan-out/partial-
   envelope shape as `/api/search`): two bounded `GET /runtime/tasks` legs per engine
   (`assignee=`, `candidateUser=`), deduped by task id. Frontend: `/tasks` route
   (`PersonTaskSearchPage`), reusing the EXISTING `TaskAssignModal` unchanged per row.
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

### CMMN scope visibility — Phase 0 _(shipped 2026-07-07, R-SEM-20)_

Standalone first slice of a possible multi-engine **Case Inspector**; ships on its own and
changes **no existing count's value**. A co-deployed CMMN engine shares flowable-rest's job
tables, so its failing jobs land in the same dead-letter lane; the BPMN join already drops
them (null `processInstanceId`), but they were dropped _silently_. Phase 0 **counts** them
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
  (all landed earlier by design). **S0 gate (issue #106) run 2026-07-14 — NOT met:**
  `docs/reviews/S0-PLAYBOOK-DEMAND-2026-07.md` / `scripts/mine-playbook-demand.sql` mine
  `audit_entry` for repeated ≥2-verb sequences per R-GOV-08; current data is one week of dev
  traffic (no real pilot deployment yet), so the ≥3-pilot-months/≥10-instances bar cannot be
  evaluated. Do not start the MVP until a re-run against real pilot audit data clears the gate.
- **Migration** — **panel-reviewed design locked in [INSTANCE-MIGRATION.md](INSTANCE-MIGRATION.md)**:
  single-instance migrate with server-side `migrate/validate` first; slice-1 = auto-map + a
  validator-driven targeted mapping table + the definition-versions on-ramp. The full side-by-side
  diagram mapping wizard and batch (async `Batch`) stay deferred with typed "MIGRATE" only after
  the single flow proves demand. Builds in parallel with Registry CRUD (INSTANCE-MIGRATION.md §13:
  no Flyway collision — CRUD owns V7, migration adds no table).
- **Job-lane trend sparklines** on the Stage-0 landing (per-engine dead-letter / timer /
  executable / suspended counts over time) — descoped here from v1.x #4 because v1 exposes
  only _live_ job-lane counts; a trend needs the **R-BAU-08** snapshot/time-series store
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
      (a null-pid process-api orphan is only a _candidate_ CMMN job; confirming it needs a by-id
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
- **OIDC hardening + access-lifecycle + group→scope CRUD + break-glass → the v2 IdP & Security
  block below (design locked).** Shared server-side saved views → now its own design block (below).

### v2 — Shared (team-wide) saved views _(design locked 2026-07-09, ★ S1–S6 LANDED 2026-07-09 — see [SHARED-VIEWS.md](SHARED-VIEWS.md))_

An operator/admin publishes curated views the whole team (or a tenant/engine scope) inherits —
"stuck payments in prod", "failed in the last hour" — so new responders get the team's canonical
entry points instead of rebuilding them, and during an incident everyone _working the engine_
drills the same filter. Codifies runbook starting points as first-class objects. **Full design +
6-seat panel + walls + RE-LOCK decisions + slice plan: `docs/SHARED-VIEWS.md`.** Adds R-SEM-24
(team-view model + scoped read-visibility + replay-time resolvability honesty) and R-SAFE-16
(publish/moderation governance). Promotes the folded v2 "shared server-side saved views" line.

- **Demand-gated:** build only if operators repeatedly re-create each other's filters — instrument
  **duplicate canonical `search` strings across distinct owners**. Empty signal ⇒ don't build;
  private views stay the whole feature. NOT spike-gated — a shared view executes nothing against
  any engine (no unknown wire-shape), so the panel (not a live spike) de-risked it.
- **Reshaped by the panel (unanimous ACCEPT-WITH-CHANGES, no BLOCK):** the draft's `visibility`
  flip on the per-user `saved_view` row became a **separate governed `shared_view` table +
  `SharedViewService` + `/api/team-views`** (house pattern, à la Registry CRUD) — publish is a
  **snapshot-copy** (create-only), which keeps `saved_view`/`ViewStoreService`/V6 pristine and
  **structurally kills the canon-hijack** the inherited owner-blind upsert-by-name would open.
- **Scope-of-governance must equal scope-of-content** (the fatal flaw Gemini + security surfaced):
  the publish scope is **derived from the `search` string's `engineIds`**, not authored free-hand;
  publish is refused if the search references an engine outside the declared scope; `scope_tenant_id`
  derived from the registry pin.
- **Two RBAC predicates, not one:** publish gate = `ScopeGrant.covers()` (containment — OPERATOR-on-
  scope, wildcard scope escalates to ADMIN-on-scope); read-visibility = a **new `overlaps()`**
  intersection predicate (a concrete grant can't cover a `*` scope), **co-signed with IdP-Security**.
  Read-visibility is **declutter, NOT a security boundary** (search is grant-blind; result sets are
  caller-invariant today — if per-caller scoping ever lands the picker MUST badge the limitation).
- **Dangling-canon honesty:** a shared view over a soft-tombstoned engine / redeployed definition /
  6.3 param-drop must NOT read as a clean all-clear — a **distinct "resolves to no live engine" state**
  - per-engine unresolvable markers + **greyed-with-reason** canon (R-SEM-17 id→name survives), riding
    the existing lower-bound envelope; no background poller.
- **Governance:** lifecycle audited fail-closed via `recordConfigEvent` (R-AUD-10, same `@Transactional`);
  moderation default = **unpublish** (reversible); reason≥10 + security-alert on another's moderation
  (not four-eyes). Publish is a deliberate second act off the hot save path; layout capture (R-UXQ-09)
  stays OUT (view==URL invariant). Injection R-OPS-08 extended to the `search` string + export surfaces.
- **Slices (`docs/SHARED-VIEWS.md` §6):** **S1** `shared_view` table + entity (reserve R-SEM-24/
  R-SAFE-16 in the first commit) → **S2** `overlaps()` + read-visibility filter (rung-1 authorizer —
  the dev basic-auth ladder is global-only, so scoped cases can't be rung-3) → **S3** publish/unpublish/
  moderate + audited fail-closed lifecycle → **S4** replay-time resolvability honesty → **S5** API
  surface + `gen:api` → **S6** frontend. No S0 spike; no rung-4-engine slice.

### v2 — K-way-merge deep paging _(★ FEATURE COMPLETE 2026-07-09 — S0–S5 all merged, each CI-green; capability-gated 6.8+)_

Cursor-based browsing through the globally-sorted merged stream across all engines, without
pulling everything into memory and without breaking sort correctness or the per-engine
do-no-harm bounds. **Full design + 6-seat panel + wire-wall + RE-LOCK decisions + spike plan:
`docs/KWAY-PAGING.md`.** Adds R-SEM-22 (cursor contract), R-SEM-23 (deterministic total order —
a standalone MUST that ships first regardless), R-NFR-08 (deep-paging envelope). Discharges
ARCH §2.4's parked "v2 can add k-way-merge cursors…" sentence.

- **Demand-gated** (ARCH §2.4): build only if operators repeatedly hit `perEngine.total >
fetched` on a _time-sorted_ search and do not narrow. The "narrow your filter" doctrine is the
  default; deep paging serves the one honest case (a live wide incident whose discriminator is
  still being discovered).
- **Reshaped by the panel:** a _uniform_ offset cursor is unsound — the INVERTED/`failureTime`
  plan scans the DLQ unsorted and sorts on a BFF-derived key, so it has no engine-side resume
  position. The cursor is a **tagged union by plan**, **MIXED/`startTime desc` first**; INVERTED
  deep paging is initially gated off (the overflow banner offers a pre-filled time-bound filter
  seam instead).
- **Do-no-harm (R-NFR-08):** an inbound per-engine offset bound-check + `size` clamp _before_
  fan-out (the real DoS ceiling — `filterHash` binding gives no integrity vs a crafted cursor);
  a dedicated `CallPriority.DEEP_PAGE` bulkhead lane so deep scroll can't starve interactive
  search; a per-engine, config-lowerable depth cap; a cursor TTL; deep pages excluded from the
  R-NFR-02 P95 gate (a separate latency class).
- **UX:** progressive-disclosure "Load more" (`useInfiniteQuery`), surfaced only on overflow —
  **not** a row-model swap, no numbered pages, no infinite scroll. Two-door selection preserved
  (no loaded-rows-as-ID-list door → also avoids AG Grid Enterprise / R-GOV-05).
- **Slices:** **S0 ✔ discharged** live P0 wire-shape spike (`docker/spike-kway-paging.sh`, §6.1):
  offset stable on 6.8/7.1 but **UNSTABLE on 6.3.1** → deep paging **capability-gated 6.8+**; cost
  unmeasurable at test-safe scale → cap = **5000/engine by reasoning**; corrected the draft —
  `failureTime` (job `createTime`) **is** engine-sortable on 6.8/7.1 (INVERTED door known-open,
  still deferred). → **S1 ✔ landed** deterministic total order (R-SEM-23, standalone: comparator
  extracted into `StatusJoin.resultOrder`, `compositeId` tiebreak, `startTime` as `Instant`; rung-1
  goldens) → **S2 ✔ landed** backend cursor + bounded merge (`PagingCursor` codec/bound-check/`mergePage`,
  `PageWindow` seam, `CallPriority.DEEP_PAGE` lane threaded through the MIXED chain, per-engine
  `deep-paging-max-depth` on `EngineConfig`, `SearchService.deepPage`; offset advances over the RAW engine
  set so same-instant clusters drain without dup/skip; R-SEM-22/R-NFR-08; rung-1+rung-2, no API surface yet)
  → **S3 ✔ landed** API surface (`SearchRequest.cursor` + `SearchResponse.nextCursor`/`depthCapped`/
  `pagingCoherence`, `SearchController` deep-page branch, `schema.d.ts` regen, rung-3 web test)
  → **S4 ✔ landed** frontend "Load more" (`useInfiniteQuery` chain, overflow-only, depth-wall + snapshot
  seams, Refresh resets; backend `aggregate()` mints the entry cursor on overflow) → **S5 ✔ landed**
  live-engine ITs (`AbstractKwayPagingIT` + 6.8 `KwayPagingIT`/7.1 `Kway7IT` in the CI matrix,
  config-lowered `deep-paging-max-depth:6`: multi-page scroll no-dup/skip cross-version, depthCapped,
  crafted-cursor 400, drop-engine honesty). Each CI-green + independently merged.

### v2 — Registry CRUD: runtime engine lifecycle _(design locked 2026-07-09, ★ S1–S5 LANDED 2026-07-09 — S4b four-eyes + connect-time IP-pinning deferred, issue #91)_

The registry moves YAML→DB so ops can onboard/retire/tune engines without a deploy — the
completion of the "BFF is now stateful" arc. **Full design + panel + threat model +
API/DDL/state-machine: `docs/REGISTRY-CRUD.md`.** Discharges R-OPS-13
(SSRF rails, now concrete), R-OPS-15 (source-of-truth + hot reload), R-SAFE-13
(REGISTRY_ADMIN + governance); supersedes R-SEM-17's "registry change = restart" for the
CRUD-managed path.

**Boundary decisions locked before build** (REGISTRY-CRUD.md §3): DB-authoritative once
initialized (YAML = one-time audited seed + `registry.source: config` pin); the egress
allowlist is deploy config, never a UI field; validation is **resolve-then-pin**, not
check-then-connect; trust is earned by a **read-only** probe (DRAFT→PROBED→ACTIVE);
`REGISTRY_ADMIN` is an orthogonal fleet grant; CRUD is audited fail-closed; `id` is immutable
and delete is a soft tombstone; hot reload evicts the per-id client caches (no restart).

**Slices — dangerous plumbing lands and is tested behind nothing before any UI reaches it:**

- **S1 — SSRF validator (pure, no wiring). ✅ LANDED 2026-07-09.** `RegistryUrlValidator`: **canonicalize first**
  (trailing-dot, punycode, `..`-traversal, implicit-port) → scheme → egress-allowlist →
  **IPv6-complete** metadata/private/loopback/ULA denylist across all resolved IPs →
  resolve-then-pin (pin the validated IP; connect-time re-checks the _pinned_ IP, never
  re-resolves) → credential-in-URL + redirect rejection. The `/external-job-api` + `/cmmn-api`
  **sibling URLs inherit the same pin + policy**. Ships with the **hostile-URL corpus** as a
  CI-gating rung-1 suite (every metadata-IP encoding incl. `::ffff:`, `..`/trailing-dot host, a
  rebinding stub, scheme/credential/redirect rejects). A quiet allow = Sev1 (R-TEST-03).
  Done-when: corpus green; validator rejects every hostile case, accepts the demo engines.
  _Shipped:_ `RegistryUrlValidator` + `RegistryAddresses`/`Cidr`/`RegistryEgressPolicy` +
  `HostResolver` seam; the denylist decodes glibc numeric v4 (decimal/hex/octal/short-form)
  AND recurses IPv6 v4-embeddings (v4-mapped `::ffff:`, v4-compatible, NAT64 `64:ff9b::/96`,
  6to4 `2002::/16`) so the metadata IP can't ride a transition prefix (Gemini S1 review).
  Redirect rejection stays `followRedirects(NEVER)` on the built client (asserted in the S3/S4
  IT, not the pure corpus). 55 rung-1 assertions green.
- **S2 — store + seed. ✅ LANDED 2026-07-09.** `V7__engine_registry.sql` (identity-keyed by the immutable slug,
  lifecycle/tombstone columns, secret **refs** by name — DDL in REGISTRY-CRUD.md §10); entity +
  `JpaRepository` + `@Transactional EngineRegistryStore` (V6 saved-view shape). YAML-seed
  import on empty table (audited `registry-seed`); `inspector.registry.source: db|config`
  switch. `NoDbTestSupport` gains the new repo mock. Done-when: empty-table seed IT + non-empty
  WARN + config-pin 403 all green; `ddl-auto=validate` holds.
  _Shipped:_ `EngineRegistryRow`/`EngineRegistryRepository`/`EngineRegistryMapper` (row↔`EngineConfig`
  seam) + `@Transactional EngineRegistryStore` (fail-closed `registry-seed`, whole import in ONE tx)
  - `RegistryBootstrap` (`ApplicationRunner`: config-pin skip / seed-on-empty / per-engine drift log,
    fail-closed = boot with empty registry on audit failure, never crashes) + `RegistryDrift` (pure) +
    `RegistryProperties` (`inspector.registry` — `source` + `egress-allowlist`/`egress-ports`). NB: S2
    does NOT yet point `EngineRegistry` at the store (that + live reload is S3); the config-pin/CRUD-403
    is enforced at the endpoint in S4, so here `source: config` simply makes the boot seeder inert. Test
    profiles are `source: config` (seeder inert, existing suites unperturbed); `EngineRegistryStoreIT`
    (own `it-registry` `source: db` profile, Testcontainers) proves seed→rows+audit + `ddl-auto=validate`.
- **S3 — reload seam (strictly post-commit). ✅ LANDED 2026-07-09.** `EngineRegistry` moves the `enabled` filter
  from ctor to `all()` and gains `refresh(id)`; `FlowableEngineClient.evict(id)` drops the
  cached read/write RestClients (+ **removes**, not resets, the R4j named instances on
  remove/purge). Refresh/evict/`RegistryChangedEvent`/re-probe run in a
  `TransactionSynchronization.afterCommit` hook so in-memory state never runs ahead of a
  rolled-back row. Done-when: rung-4 IT edits an ACTIVE engine's base-URL over REST and the next
  call hits the new host (Awaitility on the re-probe — never `Thread.sleep`); a forced
  audit-close failure leaves neither the row nor the in-memory map mutated.
  _Shipped:_ `EngineRegistry` holds enabled+disabled rows in a `volatile` map, `all()`/`require()`
  stay **enabled-only** (unchanged contract) + new `resolve(id):Optional` for disabled id→name;
  `reload(Collection)` atomically swaps the map preserving health. `FlowableEngineClient.evict(id)`
  drops both RestClients + **removes** the R4j breaker/bulkhead per `CallPriority` lane.
  `RegistryChangedEvent` + `RegistryReloadListener` (`@TransactionalEventListener(AFTER_COMMIT)` =
  the Spring afterCommit hook) runs reload→evict→reprobe (order matters for the lazy client cache;
  evict-first would re-cache stale config). `EngineRegistryStore.editBaseUrl` (the S3 seam-driver;
  S4 generalizes) publishes the event inside the tx; `RegistryBootstrap` reloads the registry from
  the store at boot under `source: db`. `EngineHealthService.reprobe(id)` for the immediate re-probe.
  Tests: `EngineRegistryReloadTest`, `RegistryReloadListenerTest` (order + swallow), evict test on
  `FlowableEngineClientTest` (R4j REMOVED), `EngineRegistryReloadIT` (Testcontainers `source=db`:
  boot points registry at DB, `editBaseUrl` commit → Awaitility reload + `registry-edit` audit).
  **Deferred to S4** (where the write door exists): wiring `RegistryUrlValidator` into the connect
  path (validate-at-write is the primary SSRF guard; connect-time `isPinAllowed` is belt-and-braces).
- **S4 — admin API + governance. ✅ LANDED 2026-07-09 (four-eyes + connect-pinning deferred, below).**
  New `rbac.canAdministerRegistry` gate + `ROLE_REGISTRY_ADMIN`
  dev user + OIDC group mapping; `AdminEnginesController` (REGISTRY-CRUD.md §9) with
  SSRF-validate → persist → reload; fail-closed audit (`registry-*` actions, before/after
  payload, secret-ref redaction); the read-only-probe endpoint; the prod enable-read-write
  four-eyes + typed-token path (reuses R-SAFE-08); break-glass exclusion. `/api/me` gains
  `registryAdmin`. Done-when: RBAC matrix (door + service, per-engine-ADMIN refused,
  break-glass refused) + audit-integrity (fail-closed, redaction) green.
  _Shipped:_ `RbacAuthorizer.canAdministerRegistry` (orthogonal fleet grant; OIDC group in prod,
  `ROLE_REGISTRY_ADMIN` authority in dev — a `registry-admin` dev user; per-engine ADMIN AND the
  break-glass ADMIN-global shape refused) checked at the `@PreAuthorize` DOOR **and** re-checked in
  `EngineRegistryStore` (SERVICE). `AdminEnginesController` GET/POST/PUT/DELETE + probe/enable/disable/
  purge/drift; store `add`(→DRAFT read-only)/`edit`/`recordProbe`/`enable`/`disable`/`remove`(tombstone,
  requires disabled)/`purge`(requires removed) — each SSRF-validated (add/edit, via `RegistryUrlValidator`
  - `RegistryProperties.egressPolicy()`, rejected BEFORE any audit/write), fail-closed audited
    `registry-*` with before/after (secret refs redacted), AFTER_COMMIT reload. Typed token (the engine
    id) on prod enable-read-write + remove + purge. `source: config` ⇒ writes 409. `/api/me.registryAdmin`.
    Tests: `EngineRegistryStoreWriteTest` (rung-1: RBAC service re-check, SSRF-reject-before-write,
    lifecycle guards), `AdminEnginesApiSpringTest` (rung-3: door matrix registry-admin/admin/viewer/
    unauth, SSRF 400, add 201, `/api/me` hint).
    The PROBE endpoint (a live dial surface) RE-VALIDATES the base-URL before dialling (Gemini S4
    review) — it re-resolves, so a URL validated-at-add that has since rebound to an internal address
    is refused before any connection.
    **DEFERRED to S4b** (below): (1) FOUR-EYES dual-control (`PENDING_APPROVAL`, approver≠proposer) —
    no such infra existed yet; typed-token was the interim prod gate. (2) Socket-level connect-time
    IP-PINNING of the pinned IP on the LIVE `HttpClient` connect for the health-loop + actual-operation
    dials (`isPinAllowed`) — validate-at-write + validate-at-probe were the guards shipped here.
- **S5 — admin UI. ✅ LANDED 2026-07-09.** Route `/admin/engines` (greyed-never-hidden), list with
  lifecycle column + env band + secret-ref presence + "Test connection", add/edit form with live
  rule-named SSRF validation, prod enable-read-write typed-token/four-eyes UI, R-UXQ-04
  zero-states. Types via `npm run gen:api` (never hand-written). Done-when: Playwright smoke
  (add→probe→enable→edit→disable→remove) + axe green.
  _Shipped:_ `AdminEnginesPage` (list + env band + lifecycle col + secret-ref PRESENCE + drift
  banner + zero-state), `EngineFormModal` (add/edit, server SSRF-400 inline), `LifecycleModal`
  (reason≥10 + read-write checkbox + typed-token on prod-enable-rw/remove/purge). Pure logic in
  `lifecycle.ts` (`rowActions`, `needsTypedToken`, `toEnvironment`), unit-tested — no component-render
  tests (codebase convention). Greyed-never-hidden nav link in `Shell.tsx`.
- **S4b — four-eyes dual-control + connect-time IP-pinning. ✅ LANDED 2026-07-12 (#91).** Closes both
  S4 deferrals in one slice: a second independent `REGISTRY_ADMIN` must approve a prod
  enable-read-write / a remove / a purge, and every ordinary dial (health loop, every operation)
  now connects to the resolve-then-pinned IP rather than re-resolving DNS on every call.
  _Shipped — four-eyes:_ `V16__registry_write_proposal.sql` (mirrors V14 `access_grant_proposal`:
  proposer, proposer's REGISTRY_ADMIN groups, `engine_id`, `kind` ∈ {ENABLE_READ_WRITE, REMOVE,
  PURGE}, summary, reason, status, 24h TTL). `RegistryChange` (kind + engineId, no serialized
  payload needed — the three kinds carry no extra parameters) + `RegistryFourEyesPolicy` (pure:
  prod enable-read-write, or ANY remove/purge regardless of environment — exactly the existing
  typed-token scope). `EngineRegistryStore.enable/remove/purge` now return an `Outcome`
  (`applied`|`proposed`) instead of the row/void directly; a JVM `writeLock` (mirroring
  `AccessMappingAdminService`'s TOCTOU guard) serializes the lifecycle-guard-read → four-eyes-decision
  → mutate/propose sequence. Independence is REGISTRY_ADMIN-group-based (mirroring
  `distinctAccessAdminGroups()`, filtered on `FleetGrant.REGISTRY_ADMIN`): the proposer's own
  REGISTRY_ADMIN groups are captured at propose time and excluded from the eligible-approver set;
  `approve()` re-checks `approver != proposer` AND `approverGroups ∩ proposerGroups = ∅`, re-verifies
  the lifecycle preconditions (state may have shifted since the proposal), then applies. Under a
  dev/Basic session (no OIDC groups) independence reduces to "a different authenticated principal" —
  a SECOND dev fixture (`registry-admin-2`) was added to `SecurityConfig.devUsers()` so this is
  actually exercisable locally/in CI, not just in the DB-mode IdP-style test harness.
  `AdminEnginesController.enable/remove/purge` return `EngineWriteOutcome` (200, not the old
  200/204/void); new `GET /proposals` + `POST /proposals/{id}/approve`. **Deliberately NOT** folded
  into the `DangerousActionReauthGate` dangerous set (that set is scoped to tier-3 verbs + bulk +
  mapping writes, IDP-SECURITY.md §5) — registry CRUD keeps its own orthogonal REGISTRY_ADMIN +
  typed-token + (now) four-eyes rail instead; noted as a re-visit if the two dangerous-set
  definitions should ever merge.
  _Shipped — connect-time IP-pinning:_ rather than persisting a pinned IP to the DB (schema churn,
  `EngineConfig` constructor churn) or wiring `RegistryUrlValidator` into `FlowableEngineClient`
  directly (would require re-plumbing every dial site), the fix hooks JDK 18's JEP 418
  `java.net.spi.InetAddressResolverProvider` SPI: `PinnedAddressResolverProvider` (registered via
  `META-INF/services/java.net.spi.InetAddressResolverProvider`) intercepts ONLY hostnames explicitly
  pinned by `RegistryPinRegistry` — every OTHER hostname (Postgres, the OIDC issuer, anything else
  the JVM dials) falls straight through to the platform resolver untouched. `RegistryPinRegistry`
  wraps `RegistryUrlValidator.validate(...)` (a drop-in decorator `EngineRegistryStore`/
  `AdminEnginesController.probe` now call instead of the validator directly): a `Pinned` result also
  registers `host → pinnedIp` with the provider. Pins are (re-)established at add/edit/probe AND at
  boot + every registry reload (`RegistryPinRegistry.resync`, wired from `RegistryBootstrap` — INCL.
  the `source: config` early-return path, which is still a live dial target — and
  `RegistryReloadListener`, after evict/before reprobe). Every actual socket connect — the 30s health
  loop and every operation dial, all funnelled through `FlowableEngineClient.build()` — therefore
  answers from the pin without ever re-resolving DNS; the resolver re-checks the pinned IP against
  the CURRENT egress policy via `RegistryUrlValidator.isPinAllowed` on every lookup (fail-closed
  `UnknownHostException` on a denied pin), exactly the "re-check the pinned ip, never re-resolve"
  contract the validator's own doc comment promised since S1. `FlowableEngineClient`'s constructor
  and every call site are UNTOUCHED — the interception is transparent below the RestClient/HttpClient
  layer. Tested against the REAL JVM resolution path (`InetAddress.getAllByName`) with a hostname
  that cannot resolve over real DNS, not a mocked `Configuration` (a sealed JDK interface — can't be
  mocked or hand-implemented outside the JDK).
  **⚠️ GOTCHA (self-caught by the test suite, not the reviewers):** without a SecurityManager (the
  only option since JEP 486), the JDK's `InetAddress` layer caches a SUCCESSFUL resolution FOREVER
  for the process's life — a cache that sits ABOVE any resolver SPI, including ours. Left alone,
  every claim above about re-checking on every connect would silently only be true for a hostname's
  FIRST-EVER resolution in the JVM's life; every later admin re-pin would be masked by the stale
  cache and never actually reach `PinnedAddressResolverProvider` again. Caught because a rung-1 test
  that re-registered a different IP for the same host and re-resolved got back the FIRST ip, not the
  second. Fixed with `Security.setProperty("networkaddress.cache.ttl", "0")` as the literal first
  line of `ProcessInspectorApplication.main()` (must run before ANY DNS lookup anywhere in the
  process — Postgres, OIDC, engines, …) and mirrored in `PinnedAddressResolverProviderTest`'s
  `@BeforeAll` (which never runs through `main()`). Deliberately JVM-wide, not scoped to registry
  hosts only — every outbound hostname now re-resolves per connection instead of caching forever,
  which is itself a defensible hardening default for a credential-vault BFF, not just a side effect.
  **Known limitation (Copilot + Gemini S4b review, both independently flagged):** the pin map is
  keyed by hostname alone — two registry rows sharing a literal hostname (a realistic same-host
  different-context-path multi-engine setup) share ONE pin, last-(re-)validation wins, and
  round-robin DNS could make that pin flip between the rows' base-URLs' otherwise-identical
  addresses. NOT a new failure mode this PR introduces: JVM DNS resolution is inherently
  per-hostname, so both rows were always going to share whatever the resolver answered at connect
  time even pre-S4b; pinning only moves WHEN that shared answer gets fixed (at the last validate,
  not at each connect) — and every IP a host is ever pinned to has already passed the identical
  SSRF/egress check, so this is a correctness quirk, not a security bypass. `PinnedAddressResolverProvider
.register` now logs loudly on an IP change so an operator notices; not otherwise fixed (would mean
  abandoning per-hostname JEP 418 resolution or accepting engine-scoped non-determinism instead).
  Tests: `RegistryFourEyesPolicyTest` (pure matrix), `EngineRegistryStoreWriteTest` (+propose/approve/
  self-approve-refused/no-eligible-approver/expired-proposal cases), `PinnedAddressResolverProviderTest`
  - `RegistryPinRegistryTest` (real-resolver end-to-end), `RegistryBootstrapTest`/
    `RegistryReloadListenerTest` (updated for the resync call). Frontend: `EngineWriteOutcome`/
    `EngineProposalView` types (regenerated via `npm run gen:api` — note `EngineProposalView` is
    deliberately NOT named `ProposalView` like the IdP one; springdoc names OpenAPI schemas by simple
    class name, and two same-named nested records collapsed into one wrong-shaped schema when first
    tried), `adminEnginesView.ts#engineOutcomeNotice` (mirrors `accessView.ts`), a proposal inbox
    section + outcome banner in `AdminEnginesPage.tsx` (mirrors `AdminAccessPage.tsx`).

### v2 — IdP & Security: identity wiring, access lifecycle & the who-can-do-what store _(design locked 2026-07-09, ★ S1–S6 core LANDED 2026-07-09/10; Playwright/axe grant-flow gate LANDED 2026-07-12 (#85); IdP-unreachable break-glass door LANDED 2026-07-14 (#94) — backend-only, see below)_

Wires OIDC for real, hardens the session/transport posture, moves the group→scope mapping
file→DB with a CRUD admin surface, and builds break-glass. **Full design + 5-seat panel +
adversarial pass + threat model + API/DDL: `docs/IDP-SECURITY.md`.** Concretizes R-GOV-06
(ADR-003), builds R-SAFE-07 (access lifecycle) + R-SAFE-06/11 (break-glass), designs the v2
CRUD promised by R-SAFE-12; adds R-SAFE-14/15 + R-OPS-16.

**Boundary decisions locked before build** (IDP-SECURITY.md §3): OIDC becomes real (Entra pilot
/ Keycloak, issuer pinned to one tenant, PKCE, tokens server-side-session-only, overage
detect-and-legibly-fail); the mapping is **DB-authoritative once seeded** (mounted YAML = seed +
`mapping-source: file|db` pin) behind a `MappingSource` seam; **`ACCESS_ADMIN`** is the apex
orthogonal fleet grant (above `REGISTRY_ADMIN`); four-eyes on effective-grant widening (self-widen,
wildcard-breadth `≥OPERATOR`, any fleet create, **any fleet removal**) + a ≥2-`ACCESS_ADMIN` boot
invariant + an `INSPECTOR_ACCESS_ADMIN_GROUP` env floor; **the live gate fails closed**
(`canExecute` `.orElse(true)`→`.orElse(false)` + a pre-auth verb-existence check); dangerous-set
(tier-3 + bulk + mapping writes) forced-re-auth via a bounded-window challenge→replay protocol;
break-glass never grants a fleet grant and its audit degrades to a local file sink when Postgres
is down.

**Slices — the identity foundation, fail-closed gate, and escalation rails land and are tested
behind nothing before any UI reaches them:**

- **S1 — OIDC wiring + ADR-003. ✅ LANDED 2026-07-09.** Real `oauth2-client` registration
  (`application-oidc.yml`: issuer-uri pinned to one tenant, client-id + secret-**ref**, PKCE via a
  customized `DefaultOAuth2AuthorizationRequestResolver`, `openid profile <groups>` scopes, exact
  redirect-uri); the single authoritative `OidcGroupResolver` (issuer pinning as belt-and-suspenders
  over Spring's `iss` validation — groups trusted only from the pinned issuer; non-array `groups`
  claim → legible login failure; Entra groups-overage `_claim_names`/`_claim_sources` → detect +
  resolve-via-`OverageGroupResolver`-if-deployed ELSE legible fail; a silent zero = Sev1) wired into
  both the login mapper (strict) and the check-time `RbacAuthorizer` (issuer-pinned, quiet). Dev
  chain untouched. _Tests:_ rung-1 `OidcGroupResolverTest` (all trust branches, CI gate) + a
  **real-Keycloak `OidcKeycloakIT`** (Testcontainers — discovery/JWKS/PKCE against a live issuer +
  a real array `groups` claim through the resolver + issuer-pinning rejects a foreign tenant),
  empirically proven locally. _CI note:_ like every other DB/container IT in this repo the Keycloak
  IT is **local-only** (not in `ci.yml`'s itClass matrix; CI gates on the rung-1 resolver matrix) —
  the zero-flake doctrine keeps container ITs off the per-PR path; a merge-blocking Keycloak leg +
  Graph overage _resolution_ (vs detection) are tracked follow-ups. Graph overage resolution and the
  `max_age`/refresh re-auth semantics land in **S5**.
- **S2 — session + header hardening + fail-closed gate. ✅ LANDED 2026-07-09.** Session caps —
  idle **12h** (`server.servlet.session.timeout`) + absolute **24h** (`AbsoluteSessionTimeoutFilter`,
  Clock-driven, invalidate-and-let-the-entry-point-answer); fixation **scoped** —
  `changeSessionId` on the `oidc` chain, **`none` on the dev Basic chain** so the SSE `EventSource`
  isn't orphaned; cookie flags `HttpOnly`/`SameSite=Lax` (`Secure` container-derived so dev-over-http
  still logs in). Header set (`HttpHardeningProperties`, config-bounded): `nosniff`,
  `X-Frame-Options: DENY` + CSP `frame-ancestors 'none'`, `Referrer-Policy`, `Permissions-Policy`;
  **CSP report-only-first** (bpmn-js/AG-Grid/CodeMirror-safe default, flip to enforce per deploy);
  **HSTS opt-in, off by default** (never double-emit vs the proxy). **Fail-closed gate:**
  `RbacAuthorizer.canExecute` → `.orElse(false)`, with a `VerbExistenceInterceptor` that 404s an
  unknown verb **before** `@PreAuthorize` so typo→404 survives while a known-but-forbidden verb is a
  clean 403 and the authorization decision never defaults to allow. _Tests:_
  `AbsoluteSessionTimeoutFilterTest` (fake-Clock cap, no sleep), `HttpHardeningSpringTest`
  (headers present + HSTS absent + CSP report-only; unknown verb → 404; forbidden verb → 403;
  **JSESSIONID stable across consecutive Basic XHRs**), `RbacGuardMatrixTest` updated to
  fail-closed. Full unit ladder green (607). CSP _enforcement_ tuning against the live bundle stays
  report-only until observed (a Playwright/axe follow-up in S6); the SPA-serving nginx should mirror
  these headers (demo-deploy follow-up).
- **S3 — mapping store (file→DB). ✅ LANDED 2026-07-09.** **Flyway `V13`** (`group_scope_grant` +
  `group_fleet_grant`, DDL per IDP-SECURITY.md §11 — V8–V12 were taken by M4-closeout + shared-views,
  so V13 was the next free at merge time) + entities/repos + `@Transactional` `MappingStore` + the
  `MappingSource` seam (`grantsForGroups`/`rolesForGroups`/**`fleetGrantsForGroups`** +
  `allLadderGrants`/`allFleetGrants`, BOTH impls — `FileMappingSource` `@Profile("!db")` delegating
  to the hot-reloaded file + config fleet grants, `DbMappingSource` `@Profile("db")` reading the
  store behind a ≤60s cache) + the boot file-seed import (`MappingSeeder`, audited `mapping-seed`,
  DB-authoritative once seeded) + the **env-bootstrap `ACCESS_ADMIN` apex overlay**
  (`inspector.security.mapping.access-admin-group` = the always-available floor, never a store row)
  - the **≥1/≥2-`ACCESS_ADMIN` boot invariant** (`ApexInvariantChecker`, `@Profile("oidc")`: 0 apex
    → refuse-to-boot loudly, 1 → boot with CRUD disabled, ≥2 → CRUD enabled). `RbacAuthorizer` +
    `InspectorAuthoritiesMapper` now consume the seam (registry-admin resolution unified through
    `fleetGrantsForGroups`, file-mode behavior identical). Profile-driven so the rung-3 suite keeps the
    file source with **zero new `NoDbTestSupport` mocks** (the DB beans are `@Profile("db")`-only).
    _Tests:_ `FileMappingSourceTest` + `ApexInvariantCheckerTest` (rung-1, CI gate) + a real-Postgres
    `MappingStoreDbIT` (seed + read + env-overlay + refresh, local-only). Full unit ladder green (621).
    Drift-report + file-pin-403 land with the S4 CRUD surface that consumes them.
- **S4 — `ACCESS_ADMIN` + mapping CRUD API + governance. ✅ LANDED 2026-07-09.**
  `rbac.canAdministerAccess` (apex, orthogonal — never a ladder ADMIN / `REGISTRY_ADMIN` /
  break-glass; dev `access-admin` user) + `AdminAccessController` (`@PreAuthorize` door + service
  re-check; GET mapping **audited-read**, POST/DELETE grants, GET `/drift`, `/proposals` +
  `/proposals/{id}/approve`). `AccessMappingAdminService` (`@Profile("db")` — file mode 409s):
  the **`FourEyesPolicy`** on the resolved change (self-widen / wildcard-≥OPERATOR breadth / any
  fleet create / any fleet removal → a **V14 `access_grant_proposal`** a second independent
  `ACCESS_ADMIN` must approve; ladder-narrow single-actor); the **eligible-approver set computed
  now** (empty ⇒ refuse with the file-pin next-move); **fail-closed audit** (`grant-add`/
  `grant-remove`, audit-first so no-audit ⇒ no-change); the **≥2-`ACCESS_ADMIN` invariant** on apex
  removal + CRUD-enabled-only-with-≥2; a **security-alert fire** on every `ACCESS_ADMIN` change
  (detective backstop, §9); the drift endpoint (no-file-apex hard-alert). Grants are value tuples so
  add/remove address them by tuple (edit = remove+add, client-composed — `PUT` deferred). Proposer &
  approver re-auth binds to the **S5** challenge protocol. _Tests:_ `FourEyesPolicyTest` (rung-1
  escalation matrix, **CI gate**) + `AdminAccessRbacSpringTest` (rung-3: only `ACCESS_ADMIN` reaches
  it, ADMIN/`REGISTRY_ADMIN`/unauth refused, file-mode 409) + `AccessMappingAdminDbIT` (rung-4 real
  Postgres: apply / propose→approve / self-approve refused / ≥2-invariant, local-only). Unit ladder
  green (634); `schema.d.ts` regenerated for the new endpoints.
- **S5 — dangerous-set re-auth protocol + break-glass.** _(S5a re-auth foundation ✅ LANDED
  2026-07-09):_ `ReauthAuthorizationRequestResolver` (PKCE always; on the `reauth` marker it injects
  `max_age` = the freshness window + `prompt=login`, so a normal login carries no `max_age` — no
  per-login MFA storm — and the dangerous-set replay forces a fresh `auth_time`) wired into the oidc
  chain; the pure `SessionFreshness` bounded-window decision (absent `auth_time` fails closed);
  `inspector.security.oidc.freshness-window-s` (≤15 min). Tests: `SessionFreshnessTest` (rung-1) +
  `OidcKeycloakIT` (real IdP: normal login has no `max_age`; `?reauth=true` carries `max_age`+`prompt=login`).
  _(S5b break-glass + hints ✅ LANDED 2026-07-09):_ the sealed **break-glass** chain — a local ADMIN
  account on a distinct `/break-glass` form login wired into the oidc chain ONLY when
  `INSPECTOR_BREAK_GLASS_PASSWORD` is set (works IdP-down; the oauth2 entry point is pinned
  explicitly so formLogin doesn't hijack it); ADMIN-global + a `ROLE_BREAK_GLASS` marker, **never**
  `ACCESS_ADMIN`/`REGISTRY_ADMIN`; **4 h** session cap (`AbsoluteSessionTimeoutFilter` per-session
  override); **alert-on-login**; audit fail-closed to Postgres, degrading to a **tamper-evident
  local file sink** (`BreakGlassAuditSink`, hash-chained) when the DB is down — the one deliberate
  fail-closed exception; `AuditService` now flags EVERY sealed-session mutation `breakGlass:true`.
  `/api/me` gains `accessAdmin` + `breakGlass` hints (schema.d.ts regenerated). Tests:
  `OidcKeycloakIT` (real IdP + MockMvc: sealed login → ADMIN session, `breakGlass:true`,
  `/api/admin/access` → 403). _(S5c inbound enforcement ✅ LANDED 2026-07-09 — the "`max_age`
  recorded ≠ enforced" gap closed):_ `DangerousActionReauthGate` reads the session principal's
  `auth_time` and, on a **tier-3 verb**, refuses a stale (or absent-`auth_time`) OIDC session with a
  **401 + `reauth-required` marker** (`X-Reauth-Required` header + body `code`) — a pre-condition in
  `CorrectiveActionService.execute` placed BEFORE the reason/typed-token rails (challenge at verb
  intent, never after the confirm token is typed) and BEFORE the audit gate (nothing happened,
  nothing recorded). Non-OIDC sessions (dev Basic, break-glass) are **exempt** (Basic re-auths every
  XHR; break-glass can't bounce a down IdP) so the dev + no-DB matrix is untouched. Membership
  freshness rides the re-auth free: a re-auth login mints a new id-token and `RbacAuthorizer` resolves
  groups from it per check. `/api/me` gains a `reauth` hint (`required`/`freshUntil`/`windowSeconds`)
  so the SPA interstitials at modal open. Tests: `DangerousActionReauthGateTest` (rung-1: fresh/stale/
  absent-`auth_time`/dev/break-glass/window), `CorrectiveActionServiceTest` (tier-3 stale → challenge
  before token+audit; within-window → reaches token check; tier-0 never challenged),
  `ActionExceptionHandlerTest` (401 + marker). _(S5d write-surface re-auth ✅ LANDED 2026-07-10 —
  the dangerous set is now FULLY gated):_ **bulk submit** — `reauth.enforce(auth)` at the single
  private `BulkJobService#submit` convergence overload all three doors (selection / error-class /
  filter) funnel through, so the challenge fires ONCE at submit where the session is live, never per
  persisted item (a bulk job survives session expiry, R-SEM-10; bulk is dangerous regardless of verb
  tier — it is the guard-tier-4 fan-out); **mapping writes** — `AdminAccessController` add / remove /
  four-eyes `approve` challenge FIRST, before the file-pin 409 and before governance (the approver's
  "∉ affected group" test runs on a fresh membership). Tests: `BulkJobServiceTest` (stale → challenged
  before persist/audit; fresh → submits and is never re-challenged per item), `AdminAccessReauthTest`
  (challenge outranks the file-pin 409; approve gated; dev basic passes to the next rail).
  **Belt-and-suspenders login-time `auth_time` conformance + membership re-pull LANDED 2026-07-12
  (#95, PR #148)** — see the dedicated `### #95` section below. Validation: identity-freshness
  IT (removed group can't authorize tier-3 post-re-auth; no per-verb MFA storm within window);
  break-glass IT (works IdP-down; audit degrades DB-down and still proceeds; cannot reach fleet CRUD).
  **Break-glass sign-in door LANDED 2026-07-14 (#94):** `GET /break-glass` — a plain, JS-free HTML
  form served by `BreakGlassLoginPageController` at a directly-known URL (RUNBOOK.md §4), not an
  auto-surfaced SPA interstitial — the SPA can never load pre-auth (every non-`/api` path is
  `authenticated()`) and there is no observable "IdP-unreachable" event it could ever react to (a
  down IdP fails as the browser's own native network error on the IdP's foreign origin). CSRF rides
  a server-rendered hidden field, not the SPA's cookie/header echo — no JavaScript required. Tests:
  `BreakGlassLoginPageControllerTest` (404 when unconfigured, form renders with the real CSRF
  token when configured), `OidcKeycloakIT#theBreakGlassDoorIsReachableByAPlainBrowserAloneNeverMockMvcsCsrfBypass`
  (a real GET→scrape-cookie-and-hidden-field→POST round-trip against live Keycloak — the FIRST
  break-glass test that doesn't use MockMvc's `csrf()` bypass).
- **S6 — admin UI + access-review + `/api/me` hints.** _(access-review BACKEND ✅ LANDED
  2026-07-09):_ `GET /api/access-review` — the effective-grant export (the R-GOV-02 "who can do what"
  release-gate artifact): full mapping expansion with a **grant-type column (ladder|fleet)** + source
  tag + the caller's own grants, `ACCESS_ADMIN`-gated + **audited read**, JSON/CSV/Markdown
  (RFC-4180-escaped CSV). `AccessReviewSpringTest` (rung-3: RBAC gate, formats, audit). The
  `me.accessAdmin`/`breakGlass` hints landed in S5b. _(FRONTEND core ✅ LANDED 2026-07-09):_
  `/admin/access` route + `AdminAccessPage` — the effective mapping (env-banded `ladder-chip`s +
  **intrinsic `FleetChip`**: an in-chip glyph ◆ + a literal "FLEET" token + the kind, textual so it
  survives sort/filter + SR, never colour-alone — ⚠️ UX), an add/remove-grant form (Enter never
  submits; explicit buttons), the widen path's **eligible-approver legibility** (`outcomeNotice`:
  names the approver groups, or the file-pin/RUNBOOK next-move when the set is empty — never a
  rotting prompt), the **four-eyes proposal inbox** (approve), and the **access-review CSV/MD
  download** (blob from `/api/access-review`). Shell nav: greyed-never-hidden **Access** link off
  `me.accessAdmin` + the permanent **red break-glass banner** off `me.breakGlass`. Pure helpers
  (`accessView.ts`) unit-tested (`accessView.test.ts`); lint/format/tsc/build/vitest(281) green.
  _(FRONTEND re-auth interstitial + verb-intent pre-empt + resume ✅ LANDED 2026-07-10):_
  `src/auth/reauth.ts` (pure staleness decision off the `/api/me` `reauth` hint — `freshUntil`
  extends the `staleTime: Infinity` me-cache client-side; sessionStorage route checkpoint with
  10-min TTL + same-origin-path-only decode, open-redirect hygiene) + `ReauthNotice`/`useReauthStale`
  rendered on EVERY dangerous surface — `DestructiveModal` (all tier-3 verbs incl. case delete +
  definitions), the three bulk submit modals (`BulkBar`, `FilterBulkModal`, `RetryGroupModal`), and
  the `/admin/access` writes — both PRE-EMPTIVE (hint at modal open, confirm disabled before the
  operator types) and REACTIVE (the 401 `reauth-required` answer flips the banner slot to the
  interstitial); the button navigates top-level to `/oauth2/authorization/oidc?reauth=true` (the S5a
  resolver injects `max_age`+`prompt=login`) and the Shell restores the checkpointed route on the
  post-login boot (single-shot, only when landing on `/`). `useAnyAuthError` excludes
  `reauth-required` 401s (a freshness challenge is never a sign-out → the SignIn overlay must not
  hijack it). Tests: `reauth.test.ts` + `problem.test.ts` (vitest, 296) + `e2e/reauth.spec.ts`
  (pre-empt / checkpoint+navigate / reactive challenge / resume; full suite 32 green).
  _(warn-before-guillotine ✅ LANDED 2026-07-10, full-stack):_ `/api/me` gains `sessionExpiresAt`
  — the session's `CREATED_AT` birth stamp + the effective absolute cap (the break-glass 4 h
  `SESSION_CAP_MS_ATTR` override honoured; a brand-new session is only created at response commit,
  so the unstamped first call answers now + cap, which IS its birth). The Shell renders a **passive**
  countdown banner (never a takeover over a dirty form, R-UXQ-06) once ≤30 min remain, ticking every
  30 s off the cached me-fetch; the CTA (`checkpointAndReauth`) shows ONLY for a freshness-tracked
  OIDC session (string `freshUntil` or `required:true` — dev basic AND break-glass answer
  `freshUntil:null`, and Jackson sends null where the generated type says undefined, so the check is
  `typeof`, never `!== undefined`); break-glass gets the countdown without a CTA (nothing to bounce
  through — its 4 h cap exists because the IdP is down). Tests: `AdminEnginesApiSpringTest`
  guillotine-instant (rung-3), `reauth.test.ts` `sessionExpiryState` (window/boundary/ceil-minutes),
  `e2e/reauth.spec.ts` (CTA / no-CTA / silent-far-away; suite 36 green). schema.d.ts regenerated.
  **Break-glass sign-in door LANDED 2026-07-14 (#94)** — backend-only, not a frontend
  interstitial (see S5 above for why). **The Playwright smoke
  (grant→four-eyes→revoke) + axe/SR gate LANDED 2026-07-12 (#85):**
  `e2e/admin-access.spec.ts` (narrow-applies, widening-proposes+approve, 403-gated) +
  the `e2e` CI job running the full spec suite with an axe scan per settled state.

Each slice: rung-1 unit → rung-3 Spring wiring/RBAC → rung-4 Keycloak/Testcontainers IT → Playwright.

### v2/M4 — State store + snapshot store: architectural boundary _(decided 2026-07-07, pre-build)_

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
  - `sparkline.test.ts`, rung-3 `TriageTrendApiSpringTest` (endpoint/clamp/JSON shape).
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
  - `recent_search` (`UNIQUE(owner,search)` = dedupe; cap-10 in the BFF). Entities/repos +
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

### v2 — Incident Ledger: persisted failure-class lifecycle & history _(design locked 2026-07-18 — [INCIDENT-LEDGER.md](INCIDENT-LEDGER.md), R-BAU-10; 3-model panel-reviewed: architecture / data-model / product seats, all APPROVE-WITH-CHANGES, changes folded in)_ — **★ FEATURE COMPLETE: S1–S5 ALL LANDED 2026-07-19** (S1 PR #262 · S2 PR #263 · S3 PR #264 · S4 PR #266 · S5 #267)

The "Sentry for process engines" delta over Stage 0: clustering, fingerprinting, acks and
error-class bulk retry all EXIST — what evaporates is history. One incident row per R-SEM-03
signature (fleet-wide), episodes per open→resolve cycle (MTTR), an occurrence time-series
(sparklines), zero-state-gated REGRESSED detection, resolve/reopen as audited config-events.
Ingestion piggybacks the R-BAU-08 sampler (zero new engine calls). Slices (each its own PR,
green-CI-merged, spec-sync in the same PR):

- **S1 — ledger substrate** _(✅ LANDED 2026-07-19, PR #262)_ (backend-only, no REST surface): V18 migration (`incident` +
  `incident_episode` + `incident_occurrence`), `io.inspector.incident` package
  (state-machine service, entities/repos, native upserts, optimistic + state-conditional
  transitions), `AggregationSample` seam + `AggregationSampledEvent`, partition-maintenance
  extension, config `inspector.incidents.{enabled,quiet-window,regression-min-count,retention-days}`.
  Done-when: rung-1 state-machine suite green (incl. zero-state regression gate, episode
  lifecycle, algo-bump orphaning); sampler cycle writes ledger rows against a real Postgres;
  no OpenAPI drift.
- **S2 — read API** _(✅ LANDED 2026-07-19, PR #263)_: `GET /api/incidents` (bounded list, R-SAFE-17 service-layer scope
  projection, derived quiet, generation split) + `GET /api/incidents/{id}` (episodes +
  windowed occurrence series + live ErrorGroup/ack join). ARCH §4 rows + `gen:api` regen.
  Done-when: rung-3 RBAC/scope tests green.
- **S3 — lifecycle verbs** _(✅ LANDED 2026-07-19, PR #264)_: `POST /api/incidents/{id}/resolve` (reason ≥10, ticketId?,
  `alsoAcknowledge?` → second separately-audited ack action) + `/reopen`; OPERATOR floor;
  config-event audit rows incl. regression events from S1. Done-when: rung-3 verb + audit
  coverage green; optimistic-lock conflict behavior asserted.
- **S4 — frontend** _(✅ LANDED 2026-07-19, PR #266)_: `/incidents` route + topbar link, sectioned list
  (REGRESSED→OPEN→QUIET→RESOLVED-collapsed), cards with truncation-honest sparklines,
  detail (timeline, episodes/MTTR, breakdown, resolve-with-ack-checkbox / reopen /
  retry-all via the EXISTING error-class bulk modal, prefiltered-search deep link).
  Done-when: vitest green + browser-verified against the dev stack.
- **S5 — hardening & close-out** _(✅ LANDED 2026-07-19, #267 — issue #261)_:
  local-only dockerized-engine IT arc `IncidentLedgerArcIT` (run-unique generated failing
  process → OPEN → error-class retry-all → drain → resolve → reopen → re-resolve →
  zero-state armed by an observing cycle → re-seed → REGRESSED + new episode + one
  `incident-regressed` config event; NOT in ci.yml's itClass — engine-harness doctrine),
  related-bulk-jobs join on the detail (`relatedBulkJobs`: the submit ENVELOPE audit row is
  the join table — the `bulk_job` scope label never carried the signature; VIEWER-floor
  bulk-read rules mirrored, no new mutation path), edge-case tests (occurrence-gap
  no-interpolation at UNIT-FE; quiet-boundary + generation split were already S2-covered),
  DATA-CLASSIFICATION verified complete (incident/episodes/occurrences rows all present from
  S1), TRACEABILITY R-BAU-10 row, goal-catalog candidate mission, docs close-out.

Deferred (issue-on-demand per R-GOV-08): assignee/severity, auto-resolve policies, alert/
deploy correlation, MTTR reporting dashboard/CSV, per-engine episode splits, SQL-side scope
filtering.

## 2026-07 whole-solution review → hardening plan

A full-codebase 5-seat panel review (+ Gemini/Copilot external critique) at main `6129a88`
produced a consolidated findings register and a risk-tiered improvement plan (P0 hotfixes →
P1 security-tail/docs-truth → P2 structural debt → P3 product track):
**[IMPROVEMENT-PLAN-2026-07.md](IMPROVEMENT-PLAN-2026-07.md)**. That document is the
authoritative WHEN for the review's output; the doc-drift sweep it scheduled (its slice #11,
issue #84) reconciled the section headers in THIS file (shared-views / registry-CRUD / IdP
headers now read shipped, not "unbuilt") plus REQUIREMENTS-REGISTER, TRACEABILITY-MATRIX,
RUNBOOK, OPERATIONS §8, OPERATOR-QUICK-START, ARCHITECTURE §2.4 and SPECIFICATION §12.

### P2 #15 — Engine-client split (F2, F9) _(✅ LANDED 2026-07-12, issue #86)_

`FlowableEngineClient` — a 1,425-line three-context god-class — is deleted and replaced by a
shared resilience core plus one facade per REST context, all in `io.inspector.client`:
`GuardedCaller` (@Component: HTTP-client build/cache, Resilience4j circuit-breaker + bulkhead
wiring, the `CallPriority {INTERACTIVE, BACKGROUND, DEEP_PAGE}` enum, `evict(id)`, the
`X-Forwarded-User` write-side header logic), `ProcessApiClient` (all BPMN/process-engine
methods + `JobLaneKind`), `CmmnApiClient` (case/job methods — `getCmmnDeadLetterJob`,
`moveCmmnDeadLetterJob`, `getCmmnCaseInstance`, etc.), and `ExternalJobApiClient`
(`listExternalWorkerJobs`). `FlowablePage` is now a top-level record. This also fixes the
CMMN/external-worker hardcoded-INTERACTIVE bug F2 named (both facades now take `CallPriority`
like every other call).

**Uniform `CallPriority` first-param (F9):** every facade method's signature is now
`(EngineConfig engine, CallPriority priority, ...rest)` — no priority-less convenience
overloads. Telescoping overloads were collapsed into one canonical signature per method (e.g.
`listProcessDefinitionsByKey`'s 5 overloads → one `(engine, priority, key, version, start,
size)`); callers that used to omit `size`/`start`/`version` now pass the canonical defaults
explicitly. All ~19 consumer classes across `action`, `aggregate`, `bulk`, `cmmn`, `detail`,
`migration`, `registry`, `resolve`, `sibling`, `snapshot`, `surgery`, `triage` were updated to
the new constructor shapes and call sites (some, like `CorrectiveActionService` and
`ResolveService`, now inject both `ProcessApiClient` and `CmmnApiClient` since they route
BPMN and CMMN calls through different facades).

_Tests:_ `FlowableEngineClientTest` (383 lines, one grab-bag rung-2 WireMock suite) split into
`GuardedCallerTest` (auth/timeout/redirect/breaker/evict/forwarded-user — cross-cutting
concerns exercised through `ProcessApiClient` as a thin call vehicle), `ProcessApiClientTest`,
`CmmnApiClientTest`, `ExternalJobApiClientTest` — one class per facade, matching the
production split. Every consumer test's mock type/constructor/call-site was updated in
lockstep. Full `mvn test` (798 tests) green; `mvn spotless:apply` clean.

### P2 #16 — One error contract (F4) _(✅ LANDED 2026-07-12, issue #87)_

Three different error-body shapes on one API — `ProblemDetail`+`code` (`ActionExceptionHandler`,
the action/guard-ladder surface), three subtly-different hand-rolled `{"error": "…"}` maps
(`SearchController`/`ResolveController`/`InstanceDetailController`, each a local
`@ExceptionHandler(IllegalArgumentException.class)`), and Spring's bare
`{timestamp,status,error,path}` shape (the container `/error` fallback for anything that never
reached a handler: the security 401/403 `sendError` legs and the no-handler 404) — collapse into
ONE: every error the BFF answers now carries `type`/`title`/`status`/`detail`/`instance` plus
`code` (machine-readable) and `requestId` (R-AUD-04).

_Shipped:_ `ActionExceptionHandler` gains two generic handlers ahead of its existing
domain-specific ones — `IllegalArgumentException` → 400 `bad-request` (subsuming the three
ad-hoc map handlers, all deleted) and `ResponseStatusException` → the exception's own status +
`ProblemCodes.fromStatus` (a new package-private kebab-case-slug helper, e.g.
`NOT_FOUND`→`not-found`) as `code`, `getReason()` as `detail` (falling back to the status's
reason phrase when null) — this is the ONE change that makes all ~50 plain
`throw new ResponseStatusException(status, "…")` call sites across the app (`SharedViewService`,
`CaseDetailService`, `InstanceDetailService`, `AdminEnginesController`, `EngineRegistryStore`,
etc.) answer the unified shape with ZERO changes at any of those call sites. `RequestIdErrorAttributes`
(the `/error` fallback, `DefaultErrorAttributes` subclass) is rewritten to build the SAME
shape instead of Spring's flat one — `detail` there is deliberately the status's stock reason
phrase, NEVER a raw exception message, since anything reaching that path is by construction
unexpected (unlike the handler-path exceptions, whose messages are always developer-authored
client-facing copy) — surfacing `.getMessage()` there would risk leaking internals. Deliberately
does NOT set `spring.mvc.problemdetails.enabled` (see IMPROVEMENT-PLAN-2026-07.md item 16's
landed-note for why). Frontend: `ApiError.sentence()` drops its `'error' in body` branch (now
dead — nothing sends that shape); `ActionProblem` drops `bareSpringError` entirely, replaced by
checking `code === 'forbidden'` directly in `problemBanner`'s switch (a new explicit case,
alongside `rbac-denied`) — the missing-CSRF-token hint fires off the SAME stable machine code a
domain refusal would use, not a shape-sniffing heuristic. `npm run gen:api` regenerated
`schema.d.ts` byte-identical (springdoc doesn't type error responses without per-endpoint
`@ApiResponse` annotations — out of scope here; confirmed via diff, not assumed).

_Tests:_ `RequestIdSpringTest` (the one file that explicitly documented and asserted all THREE
old shapes side by side) rewritten to assert the SAME shape from every path — handler (400),
security 403, ad-hoc-turned-global 400, no-handler 404 — all now carry `code`+`requestId`.
`SearchDeepPageApiSpringTest`'s crafted-cursor-400 assertion moved from `.get("error")` to
`.get("detail")`. New `ActionExceptionHandlerTest` cases for both generic handlers +
`ProblemCodesTest`. Frontend: `problem.test.ts`'s `bareSpringError` tests replaced with
`code: 'forbidden'`-based equivalents; `client.test.ts` updated to the new container-fallback
body shape. Full `mvn test` (803 tests) + `npm test` (478 tests) green.

### P2 #19 — Test-support consolidation (F5, F6, Q8) _(✅ LANDED 2026-07-12, issue #90)_

Three unrelated pain points bundled under one finding: (1) `NoDbTestSupport` (the docker-free
Spring context's persistence stand-in) required a hand-added `@Bean` mock for every new JPA
repository, or all 33 dependent test classes broke at once with a diffuse
`NoSuchBeanDefinitionException`; (2) `EngineConfig`'s 18-arg positional record already needed
5 backward-compat constructors to avoid churning ~60 `TestEngines` call sites, and 6 more
test classes still hand-rolled the full positional constructor for fields `TestEngines`
didn't expose; (3) `TEST-STRATEGY.md` claimed "backend line coverage ≥80%, frontend logic
≥70%, measured and gating from M3" — untrue on both counts, no tool anywhere measured either
number.

_Shipped — (1):_ `NoDbTestSupport` replaced its 12 individual `@Bean Mockito.mock(...)`
methods with a `BeanDefinitionRegistryPostProcessor` that classpath-scans `io.inspector` for
every `interface X extends JpaRepository<...>` and registers a mock for each automatically —
proven (not assumed) against the live codebase: the scan found 3 repositories
(`AccessGrantProposalRepository`/`GroupFleetGrantRepository`/`GroupScopeGrantRepository`) that
predated ANY hand-written mock, a latent gap the old list never caught. A new repository now
needs zero edits here. The lone `JdbcTemplate` mock (a framework type the scan can't and
shouldn't discover) stays a single explicit, documented exception. `NoDbTestSupportTest`
proves the mechanism directly, including the 3 previously-uncovered repos.

_Shipped — (2):_ `TestEngines` gains `TestEngines.builder(id, baseUrl)` — a fluent builder
with a named setter per `EngineConfig` field, defaulting exactly like the existing named
factories (`name=id`, DEV, enabled, everything else null/off). The 5 existing named factories
(`engine(...)`, `engineInTenant`, `forwardUserEngine`, …) now delegate to the builder
internally instead of duplicating positional construction, and stay byte-identical for their
~60 existing call sites. The 6 registry-focused test classes that hand-rolled
`new EngineConfig(...)` for fields no named factory exposed (`lifecycle`,
`maxPageSize`/`dlqScanCap`/`alarmThresholds`/`telemetryUrlTemplate`/`accentColor`) now use the
builder. A NEW `EngineConfig` field means a new builder setter, never a constructor-arity
bump anywhere else.

_Shipped — (3):_ jacoco (`backend/pom.xml`, `prepare-agent`+`report` goals bound to the `test`
phase — runs automatically on every `mvn test`, backend included in CI's `unit` job, report at
`target/site/jacoco/index.html`) and `@vitest/coverage-v8` (`frontend/vite.config.ts`
`test.coverage` block, opt-in via the new `npm run test:coverage` script — deliberately NOT
wired into the default `npm test` CI step) now MEASURE line/branch coverage for real. Neither
gates: `TEST-STRATEGY.md`'s floor sentence is rewritten to say so plainly, with the actual
measured unit-only baselines (~66% backend lines, ~38% frontend lines) replacing the false
"measured and gating from M3" claim — both numbers are well under the 80%/70% floors, so a
blind gate today would have broken every build. Turning either into a real threshold is
follow-up work now informed by a true starting point instead of an assumed one.
`frontend/coverage/` (the html report) is gitignored + excluded from eslint/prettier.

_Tests:_ `NoDbTestSupportTest` (new, 2 cases). `EnginesControllerTest` +
`RegistryBootstrapTest` + `EngineRegistryReloadTest` + `RegistryPinRegistryTest` +
`RegistryDriftTest` + `EngineRegistryMapperTest` migrated from raw `new EngineConfig(...)` to
the builder, behavior-preserving (every prior explicit value threaded through unchanged).
Full `mvn verify` (805 unit + 151 IT, full engine matrix) green aside from the pre-existing,
unrelated `SharedViewFailClosedIT` failure (confirmed to also fail on `origin/main`); `npm
test` (487 tests) + `npm run build` green; `scripts/ci-local.sh --full` green.

### #95 — Login-time `auth_time` conformance + membership re-pull verification (R-SAFE-07) _(✅ LANDED 2026-07-12, issue #95)_

IDP-SECURITY.md §5's dangerous-set re-auth protocol had only a CHECK-time freshness gate
(`DangerousActionReauthGate`, landed S5c): it reads whatever `auth_time` the IdP already
returned, but nothing verified the IdP actually _honored_ the `max_age` it was asked for at
login. A nonconforming IdP could silently echo the stale SSO session's old `auth_time` (or omit
the claim), and the gap would only surface minutes later as a confusing 401 on an unrelated
verb — never at the login itself, where the operator could see it immediately. Separately, the
gate's own Javadoc asserted "membership freshness rides for free on the re-auth" with no test
anywhere proving it.

_Shipped — login-time gate:_ `ReauthAuthorizationRequestResolver` now stashes a one-shot session
marker (`REAUTH_SESSION_MARKER`) whenever it injects `max_age`/`prompt=login` — the same
`HttpSession` is guaranteed current at the callback (OAuth2's own state-param CSRF defense
depends on that continuity). `ReauthConformantOidcUserService` (new, wraps the default
`OidcUserService`, wired into `SecurityConfig`'s `oidcChain`'s `userInfoEndpoint().oidcUserService(...)`)
reads-and-clears that marker on every token response; when set, it demands the returned
`OidcUser.getAuthenticatedAt()` satisfy the same `SessionFreshness` window the check-time gate
uses, failing the login itself (`OAuth2AuthenticationException`, error code `stale_auth_time`)
when it does not. Ordinary logins (no marker) pay zero extra cost and get zero extra behavior.
Confirmed empirically (not assumed) that Spring's default OIDC validator chain does nothing here
— decompiled `spring-security-oauth2-client`'s `OidcIdTokenValidator`, which validates `iss`,
`aud`, `exp`/`iat` clock skew, and `azp`, but never `auth_time`/`max_age` — hence the two
purpose-built gates rather than a validator hook.

_Shipped — membership re-pull verification:_ no production change (design already correct by
construction — `RbacAuthorizer` caches nothing keyed by identity; `grantsFor`/`hasRoleOn` are
pure functions of whatever `Authentication` is currently in `SecurityContextHolder`, so a re-auth
round trip's fresh id-token is read on the very next check). Deliberately did NOT touch
`InspectorAuthoritiesMapper`/`RbacAuthorizer`'s id-token-only group source (their own comments
already explain why: Entra puts groups in the id token, not userinfo, and the pinned `iss` must
match the token the groups came from) — `RbacAuthorizerOidcFreshnessTest` proves the EXISTING
mechanism re-resolves fresh across an `Authentication` swap, closing the previously-unverified gap.

_Tests:_ `ReauthConformantOidcUserServiceTest` (new, 6 cases — no marker/fresh/stale/absent/
marker-always-consumed/no-leak-into-next-login). `RbacAuthorizerOidcFreshnessTest` (new, 2 cases).
`OidcKeycloakIT` gains `aRealKeycloakIdTokenCarriesAuthTime` — empirical proof a real Keycloak
password-grant id-token actually carries `auth_time` (the real-world assumption the whole
freshness mechanism rests on), plus all 6 existing cases still green against the live Keycloak +
Testcontainers Postgres harness.

### #126 — Scope-filter leak-views + trends (R-SAFE-17 remainder) _(✅ LANDED 2026-07-12, issue #126)_

Follow-up to the S2 read-scoping work (#81; R-SAFE-17): search and the triage dashboard were
scope-filtered, but two triage read surfaces stayed fleet-wide because `LeakDefinitionCount`
had no per-engine dimension to project post-cache the way `TriageScopeProjector` does the
dashboard, and it was unverified whether `triage_snapshot` carried one either.

_Shipped — leak-views:_ `LeakDefinitionCount` gains `countsByEngine` (a
`Map<engineId, EngineLeakCount>`) and `partial` (boolean). `LeakViewService.aggregate()` already
computed a per-engine breakdown transiently inside `EngineSlice` before summing it away at the
`byKey` merge — the fix carries that breakdown through into the DTO instead of discarding it. New
`LeakViewScopeProjector` (same post-cache render-time doctrine as `TriageScopeProjector`, required
by the shared 20s single-flight cache): unlike `ErrorGroup`'s dead-letter/retrying split, every
leak-view window count DOES decompose per engine, so a scoped slice's totals are honestly
RECOMPUTED from survivors — never nulled. A definition touching no readable engine is dropped; one
only partially in scope keeps its recomputed totals and sets `partial=true` so the UI can badge
"may not cover every engine this definition runs on" without misrepresenting the numbers.
`unavailableEngines` is narrowed to readable engines (no engine-topology leak); `lowerBound`
carries over unchanged (a fleet-wide honesty floor, not scope-dependent).

_Shipped — trends:_ verified (not assumed) that `triage_snapshot` rows already carry `engineId`
per the sampler's write path — no schema change needed. `TriageTrendService` gains a
`ReadScopeGate` dependency and filters rows to the caller's readable engines before grouping into
series; unlike leak-views/the dashboard, trends has no shared cache to protect (a direct
per-request DB read every call), so scoping is a plain inline filter, not a separate projector.

_Both_ reuse the existing `inspector.security.scope-reads-enforced` flag for free via
`ReadScopeGate.readableEngineIds()` (`null` = enforcement off = unrestricted) — no new flag.

_Tests:_ `LeakViewScopeProjectorTest` (new, 4 rung-1 cases). `LeakViewServiceTest` extended with
`countsByEngine`/`partial` assertions on the merge. `TriageTrendServiceTest` gains a scoped-row-
filter case + a `ReadScopeGate` test seam. `TriageTrendApiSpringTest` unchanged and still green
(dev/test profile has enforcement off, so the new `Authentication` parameter is a no-op there).

### #96 — Observability follow-up (R-OPS-02/03, R-AUD-04) _(✅ LANDED 2026-07-13, issue #96, except cache hit rate)_

Follow-up to the Q1 observability minimum (PR #77): actuator health + auth-gated Prometheus
scrape were real, but the named application-metric contract, structured JSON logs, correlationId
MDC fan-out propagation, `GET /api/diag`, and `deploy/` alert rules were all still honestly
marked TO LAND in OPERATIONS §2/§3 + RUNBOOK §2b.

_Shipped — metrics:_ `audit_insert_failures_total` (tagged `site` — `beginPending`/
`recordConfigEvent`, the two INSERT paths) in `AuditService`'s existing fail-closed catch blocks.
`engine_fanout_duration_seconds` (tags `engineId`/`leg`) wraps `GuardedCaller.call()` — the
single resiliency chokepoint every facade call already runs through, so ONE instrumentation site
covers every engine call uniformly, timed around bulkhead+breaker+HTTP including failure paths
(a fast-fail on an open breaker is itself a meaningful near-zero sample). `sse_emitters_active`
(gauge over `SseHub.subscriberCount()`) + `sse_emitter_errors_total` (incremented on a dropped
send or an emitter's own `onError`). `bulk_jobs_running` (gauge, `BulkJobRepository.countByState`)

- `bulk_item_outcomes_total` (tagged `state`, tallied once per finished job in the existing
  `closeEnvelope` tally step — semantically identical to counting at each `settle()` call site, one
  hook point instead of half a dozen). All four verified against a REAL running instance's
  `/actuator/prometheus` scrape, not assumed correct from the code alone.

_Shipped — structured logs + correlationId fan-out:_ `logback-spring.xml` (new) emits JSON via
`logstash-logback-encoder` gated on the `oidc` profile (this codebase's existing prod-like
marker — e.g. `scope-reads-enforced` defaults on there too) rather than enumerating every
dev/test/`it-*` profile by name (logback's `springProfile` matches exact names only, no
wildcards — excluding profiles individually would silently miss a new one added later). New
`MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor()` — a drop-in `ExecutorService`
decorator snapshotting `MDC.getCopyOfContextMap()` at submit time and restoring/clearing it
around each task — swapped into all 7 `Executors.newVirtualThreadPerTaskExecutor()` call sites
(search, triage aggregation, leak-views, bulk dispatch, resolve, the alert-webhook sender, the
engine-health prober): `RequestIdFilter` bound `correlationId` to MDC on the request thread years
ago, but every virtual-thread fan-out silently lost it (thread-locals don't inherit) until now.

_Shipped — `GET /api/diag`_ (new `DiagController`/`DiagService`): door check is `@rbac.atLeast(..,
'ADMIN')` — ADMIN on at least one engine, the SAME coarse shape `AuditController#operationsLog`
uses for the cross-engine operations log. Breaker states
(`CircuitBreakerRegistry.getAllCircuitBreakers()`), cache AGES for the triage dashboard and
leak-views (new `cacheAge()` accessors peeking each Caffeine cache's `asOf`-stamped current value
via `asMap()`, never triggering the loader), bulk permit-pool saturation (new
`BulkJobService.permitSnapshot()`, only engines dispatched-to this process), the last 20
engine-call failures with correlationIds (new `RecentEngineErrors`, a bounded 50-entry ring
buffer fed from the SAME `GuardedCaller.call()` chokepoint the timer uses), and build info
(`spring-boot-maven-plugin`'s `build-info` goal, `Optional`ly absent outside a `mvn package` run).
Deliberately did NOT build the "targeted per-composite-ID derivation tracing with 15-min TTL"
line from OPERATIONS §2's aspirational text — a materially bigger feature layering a cached trace
view on the EXISTING `EngineCallRecorder` (today only wired for on-demand "Explain this status"
re-derivation), tracked separately.

_Fixed post-adversarial-review, before merge:_ the first cut's door check doubled as the ONLY
check — every per-engine section (breakers/permits/recent-errors) went out fleet-wide to any
caller who was ADMIN on even one engine, violating this codebase's own scope invariant
(`ScopeGrant`: a grant on one engine/tenant authorizes nothing on another). Fixed by filtering
each per-engine section, per entry, to engines the caller actually holds ADMIN on
(`RbacAuthorizer.hasRoleOn`) — mirroring `AuditController#payloadVisible`'s coarse-door/fine-item
shape exactly, no new grant type needed. Separately, `RecentEngineErrors` captured
`Throwable.getMessage()` verbatim with no bound; a deserialization failure on a malformed or
wire-shape-drifted engine response (`HttpMessageNotReadableException`/Jackson) can embed a
snippet of response content in that message (confirmed `HttpStatusCodeException.getMessage()`
itself does NOT — it's just `"<status> <reasonPhrase>"` — but the Jackson path can), so messages
are now truncated to 500 chars before being stored.

_Shipped — alert rules:_ `deploy/prometheus/alert-rules.yml` (new), matching RUNBOOK §7's table —
every expression checked against a real scrape, not assumed. `InspectorDown`,
`AuditInsertFailures`, `AllCircuitBreakersOpen`, `SseEmitterErrorsSpiking`, and
`DatabaseConnectionTimeoutsSpiking` (the honest proxy for Postgres-unreachable, via HikariCP's
own `hikaricp_connections_timeout_total`) fire off metrics this app emits today.
`InspectorReadinessFailing`/`DiskSpaceHigh` are written but explicitly marked infra-dependent —
they need `blackbox_exporter`/`node_exporter`, neither deployed by this repo's `docker/*.yml`.

_Deliberately out of scope (honestly documented, not silently dropped):_ triage-cache **hit
rate** (age is shipped via `GET /api/diag`; a hit/miss ratio needs `Caffeine.recordStats()` +
Micrometer's `CaffeineCacheMetrics` binder — not in issue #96's stated bullet list) and the
audit-retention-purge dead-man alert (RUNBOOK already documents its LOG-based interim signal;
a metric-based version needs new instrumentation this issue doesn't scope).

_Tests:_ `AuditServiceTest` extended with counter assertions on both failure sites.
`GuardedCallerTest` gains 2 new cases (timed success + timed failure, tag assertions).
`MdcPropagatingExecutorsTest` (new, 4 rung-1 cases: context visible on the worker, no-context
stays no-context, no cross-contamination between tasks on a shared executor, caller's own context
untouched). `RecentEngineErrorsTest` (new, 6 cases: correlationId capture, null-safe with no MDC
context, newest-first, limit respected, capacity eviction, overlong message truncated). `DiagServiceTest`
(new, 3 cases: assembly from mocked sources, per-engine scope filtering excludes a breaker/permit/
recent-error the caller isn't ADMIN on, build-info presence). `DiagRbacSpringTest` (new, 3 cases:
ADMIN reaches it, every lesser role 403s, unauthenticated 401s). `BulkJobServiceTest` gains a
permit-gauge case.

### P2 #21 — Release mechanics remainder (Q7) _(✅ code landed 2026-07-13, issue #92; live cutover/drill still pending human action)_

`docs/IMPROVEMENT-PLAN-2026-07.md` §2 P2 item 21's remaining slivers, after PR #68/#80 shipped
the GHCR publish pipeline (`:edge`/`:sha-<short7>` on green main, `:X.Y.Z`/`latest` on a
version tag — the published IMAGE tag drops the git/release tag's leading "v", issue #200)
and v0.1.0 released with public packages.

_Shipped — digest capture:_ `release.yml` and `publish-edge.yml` both give their
`docker/build-push-action@v6` steps an `id:` and add a step reading `steps.<id>.outputs.digest`
into `$GITHUB_STEP_SUMMARY`; `release.yml`'s GitHub Release body also gets a "Digests" section
so a versioned release's exact image identity outlives the transient job summary.

_Shipped — demo pinned by digest:_ `docker/docker-compose.demo.yml`'s `backend`/`frontend`
switch from `build:` (rebuild-from-working-tree — confirmed that's what was actually
happening, `docker/DEMO-DEPLOY.md`'s old deploy command was `up -d --build`) to
`image: ghcr.io/x3kcl/process-inspector-{bff,web}@${PI_BFF_DIGEST:?...}` — a required env var
with a fail-loud default, never silently building from source or floating to whatever `:edge`
resolves to at pull time. New `docker/deploy-demo.sh` resolves a tag's current digest via
`docker buildx imagetools inspect` (registry metadata only, no pull), writes it into
`docker/.env.demo`, redeploys, verifies the standard `/api/engines` 401 probe, then commits +
tags the result `demo-YYYY-MM-DD-<shortsha>` — that file's git history is the attribution
record ("git tag per demo deploy" from the issue). Does not auto-push; publishing the
attribution commit/tag is a separate, printed, deliberate step.

_Shipped — rollback:_ new `docker/rollback-demo.sh <demo-tag>` restores a PRIOR deploy's
_exact_ digest pair straight from git history (`git show <tag>:docker/.env.demo`) rather than
re-resolving a tag (which would be wrong — `:edge` moves). RUNBOOK.md §8 (new) documents the
symptom → command → verification shape, mirroring §5's Postgres-restore entry.

_Fixed post-adversarial-review, before merge:_ Copilot + Gemini review of the first cut (Gemini
hit a persistent 429 mid-review but did its own empirical bash verification instead of
guessing) surfaced 5 real bugs in the two scripts, all confirmed by direct reproduction in a
scratch git repo and fixed: (1) the deploy's `git commit`/rollback's `git commit` both aborted
the whole script under `set -e` on a no-op redeploy ("nothing to commit" is exit 1) — both now
`git diff --cached --quiet` first and exit 0 cleanly; (2) the demo-tag name was computed
BEFORE the commit it names, so it embedded the PARENT commit's short-sha while the tag itself
resolved to the new commit — moved the `TAG=` line after `git commit`; (3) `resolve_digest()`'s
`sha256:...` passthrough branch ignored which image (`$1`) was being resolved, silently
pinning both BFF and web to the same digest — removed the passthrough entirely (rollback is
the correct tool for "go back to an exact prior state", not a raw-digest arg to the deploy
script); (4) `rollback-demo.sh`'s `git show ... > "$ENV_FILE"` truncated the target file as
part of shell-redirection setup BEFORE `git show`'s exit status was known, so a failing show
could silently zero out `docker/.env.demo` — now writes to a `mktemp` file first, checked, then
`mv`'d into place; (5) a failed post-deploy verify probe only warned and still committed+tagged
the bad state as "the" attribution record, inconsistent with rollback's (correct) exit-before-
commit — deploy now matches rollback's fail-before-commit posture. Also added digest-format
validation (`^sha256:[0-9a-f]{64}$`) before writing any resolved value into `.env.demo`, closing
a gap where `jq -r '.digest'` silently prints the literal string `"null"` (exit 0) if a
manifest's digest field were ever absent.

_Deliberately not done here, honestly flagged rather than silently skipped:_ the actual live
cutover of pi.naumann.cloud to digest pinning, and an against-prod rollback drill, were NOT
performed as part of this change — this dev box IS hp04 (the same Docker daemon also runs
unrelated personal-cloud services behind the same Traefik), so touching the live deploy is a
deliberate, human-confirmed action, not something a background issue-implementation pass
should do on its own. What WAS verified: `docker/deploy-demo.sh --dry-run edge` against the
real published images (digest resolution succeeds), `docker compose config` fails closed on
the committed (empty) `docker/.env.demo`, and succeeds once real digests are substituted.
RUNBOOK §8 records this distinction explicitly so "drilled" isn't overclaimed.

_Tests:_ none added — this is deploy tooling (bash + YAML + workflow config), not application
code; verified via `bash -n` (both scripts), a live `--dry-run` resolve against the real GHCR
images, and `docker compose config` fail-closed/success checks (see above). `mvn -o test` /
`npm test` are unaffected (no `backend/`/`frontend/` source changed).

### #97 — Goal-catalog MUST-v1 gaps, audit + remainder _(✅ 7/8 already landed 2026-07-11 under a `usability-w3-*` sprint that never updated this doc or closed the issue; remaining sliver ✅ LANDED 2026-07-13)_

Issue #97 (filed 2026-07-10, referencing `docs/usability/GOAL-CATALOG.md` v1.0's same-day
audit) listed 6 MUST-v1 gaps + 2 slivers. Before writing any code, a ground-truth audit
against `origin/main` (not the issue text, not the stale GOAL-CATALOG snapshot) found
**7 of the 8 items were already fully shipped** — backend AND frontend, with tests — in a
same-day `usability-w3-*` commit series merged 2026-07-11 (PRs #116/#120/#121/#122/#123,
one day after the issue was filed): R-BAU-01 acknowledge (`ErrorGroupAckService` +
`AcknowledgeGroupModal.tsx`), R-BAU-02 leak views (`LeakViewService` +
`LeakViewsSection.tsx`), R-AUD-05 shift report (`ops/shiftReport.ts` +
`AuditLogPage.tsx`'s "Copy shift report"), R-AUD-08 audit CSV export
(`AuditController#operationsLogCsv` + `ops/exportCsv.ts`), R-AUD-09 attribution caveat
(`AttributionCaveat.tsx` on both audit surfaces), R-L3-01 explain-status
(`StatusEvidenceService` + `StatusEvidenceModal.tsx`, wired off `StatusChip`), and the
R-AUD-07 ticket-capture field sliver (`TicketField.tsx`). None of these PRs updated
`docs/IMPLEMENTATION-PLAN.md` or closed #97 — this entry is the first record of that sprint
in this file. Also caught: issue #97's own text claimed R-BAU-01 "blocks the R-SEM-13
annotation demotion" — false; R-SEM-13 is gated on the separate, still-unbuilt R-BAU-03
(annotations), a conflation the v1.x-fast-follows section above (§"Error-class bulk-retry")
carried too, now fixed there as well.

_Shipped here — the one genuine remainder:_ the R-SAFE-05 sliver was **partial**, not
built like the other 7 — the point-of-action badge (`InstanceActions.tsx`) and bulk-bar
auto-exclusion existed (2026-07-11), but the results-grid itself carried no marker, so a
protected instance looked identical to any other row until you opened it. New "Protected"
column in `ResultsGrid.tsx` (a `🔒 Protected` badge, reusing the existing `.protected-badge`
style — no new CSS) renders whenever `row.protectedInstance === true`; the backend field
already existed (`ProcessInstanceRow.protectedInstance`, already consumed by
`bulk/intersection.ts`), so this was frontend-only.

_Genuinely still open, not part of this issue's own bullet list but surfaced by the audit:_
R-BAU-03 (error-class annotations, blocks R-SEM-13), R-SAFE-10 (pre-action evidence
snapshot), R-SEM-16 (SPA/BFF version-skew banner), and a frontend surface for R-OPS-14
(`GET /api/diag` is backend-complete since issue #96 but has no admin UI page yet) —
left for separate issues, not silently rolled into this one.

_Tests:_ `ResultsGrid.test.tsx` gains 2 cases (badge renders for a protected row and only
that row; no badge at all when `protectedInstance` is undefined/false).

### #98 — Usability loop over the six unproven v2 surfaces _(✅ run 2026-07-13, issue #98; harness fixes + break-glass verified separately; 5 findings filed as issues)_

`docs/usability/GOAL-CATALOG.md` §2 P3's pre-pilot promise: run the existing
`usability-run` named workflow (`.claude/workflows/usability-run.js`) over team/shared
views, k-way Load-more paging, MigrateModal, `/admin/engines`, `/admin/access`, and the
break-glass banner — none of which had ever been exercised by a real tester run before.

_Harness bugs found + fixed before any mission could produce real data:_

1. The "briefs" extraction agent's structured-output schema was a bare
   `{additionalProperties: string}` object with no enumerated `properties` — unreliable
   for tool-calling; it came back wrapped as `{input: "<stringified JSON>"}` instead of
   the requested mapping, silently breaking every mission's brief lookup (100%
   `FIXTURE_DRIFT` on the first real attempt, despite staging itself succeeding cleanly).
   Fixed by switching to an array-of-`{id,brief}`-objects schema, which round-trips
   reliably through structured output.
2. `{{SCRATCH_URL}}` (M8's engine-onboarding target) was referenced in
   `docs/usability/MISSIONS.md`'s placeholder contract but never computed by the stager
   agent at all — a pre-existing gap, unrelated to (1). Fixed as a plain script-level
   constant (`http://localhost:8083/flowable-rest/service`, per M8's own STAGING note
   pinning it to engine-7) merged into the placeholders object, rather than added to the
   stager's own prompt — this kept the (expensive) `stage` agent call's cache key
   unchanged across the fix-and-rerun cycle.
3. New mission **M10 "Digging past page one"** (wave 1, `viewer`) added to
   `docs/usability/MISSIONS.md` + `usability-run.js`'s `MISSION_USER` map — R-NFR-08
   (deep-paging envelope) had a written goal-arc in the catalog since it shipped but had
   never been wired into any mission narrative, exactly matching this issue's own framing
   of "never usability-tested."

_Run outcome (full detail: `docs/usability/results/latest/RUN-REPORT.md`):_ all 10
missions (M1–M10) reconciled. **Zero Sev1 findings** — no mis-triage, no guard bypass, no
wrong-target destructive action, no invisible apply, no hallucination/quiet-lie (every
claimed fix ground-truth-verified against real engine/audit state). The 6 former
MUST-v1 gaps from issue #97 were re-confirmed built by live testers (5 of 6 cleanly; the
6th, R-SAFE-05, surfaced a deeper problem — see below). Two mission arcs (M5's F-G7
engine-down staging, M6's F-G2 prod-flip + protected-instance staging) could not be
exercised because their pre-stage hooks mutate **shared dev infrastructure** other
parallel sessions in this repo may depend on (`docker stop` on a shared engine
container, flipping a shared registry row's environment tag) — correctly blocked by the
run-time safety classifier both times this was attempted; not forced through, and not
counted as a product failure. `docs/usability/GOAL-CATALOG.md`'s theme list documents
this as a repeat of a prior baseline report's explicit warning.

_5 findings filed as issues (Sev1/Sev2, real product/UX bugs, distinct from the harness
bugs above):_

- **#165** — R-SAFE-05's protected-instance feature has a fully-built READ side
  (enforcement, bulk skip, both badges) but **zero production write path** —
  `ProtectedInstanceRepository` has no `.save()`/`.delete()` call anywhere outside test
  code. No admin can actually protect an instance today. `GOAL-CATALOG.md` and
  `REQUIREMENTS-REGISTER.md`'s R-SAFE-05 entries (both edited alongside the #97 PR, which
  verified the read/display side only) are corrected back to `BUILT partial` in this
  same change.
- **#166** — search-results grid shows COMPLETED for an instance whose detail page
  correctly shows TERMINATED (ground-truth-confirmed via `deleteReason`); likely the same
  derivation site issue #105 already fixed for the detail page never got applied to the
  grid's own status derivation.
- **#167** — Load-more's "X of Y fetched" counter never updates after paging, and hitting
  the internal depth cap (~513/1,598 in this run) gives no "why stopped" message —
  R-NFR-08's own goal text explicitly requires distinguishing depth-cap from true-end.
- **#168** — keyboard-only navigation: first Tab skips ~11 header elements, no
  focus-adjacent confirmation after a mutating action, and (compounded by both) the M9
  keyboard retry task took 46 interactions against a 15-interaction budget.
- **#169** — a four-eyes self-approval refusal (403, guard works correctly) produces zero
  UI feedback — confusing today, would become a real invisible-apply risk if the guard
  ever regressed.

_Break-glass banner (one of the six named surfaces) — separately verified, NOT via the
usability-run harness:_ the dev-stack harness this workflow drives cannot reach the
`oidc` profile at all (break-glass only exists there). Stood up a standing, loopback-only
Keycloak + `oidc`-profile BFF instance and drove it directly with Playwright: the
break-glass login mechanism itself (POST → session flag → 4h cap → banner render, the
last confirmed by existing `Shell.test.tsx` coverage) works correctly end-to-end, but
there is **no reachable UI path to invoke it at all** — no server-rendered `/break-glass`
page (raw 404), no SPA component, and the app's cookie-based CSRF scheme requires
JavaScript cooperation a plain HTML form couldn't provide even if a page existed. Posted
as hard evidence on the existing issue #94 (which already named this gap) rather than
filing a duplicate.

_Tests:_ none — this is a testing/investigation run, not application code. Harness fixes
verified via `node --check` + the actual 10-mission live run completing successfully
after both fixes landed.

### #103 — "Show as cURL" in the tier-0 inline retry flows (BPMN + CMMN) _(✅ LANDED 2026-07-13, issue #103)_

Sliver from the CMMN Phase-3 build: `POST …/actions/{verb}/curl` already existed for both
scopes (instance and case), and every modal-based verb already surfaced it, but the tier-0
`InlineConfirm` retry flows — BPMN dead-letter retry and CMMN case retry — didn't.

_Shipped:_ `CurlPreview.tsx` generalized from a hardcoded instance-scope fetch
(`engineId`/`instanceId`/`verb`/`body` props) to a scope-agnostic `queryKey` +
`fetchCurl: () => Promise<ActionCurlResponse>` pair — the instance and case routes render
identically, only which BFF endpoint is called differs. New
`fetchCaseActionCurl` in `api/caseActions.ts` mirrors `api/actions.ts`'s existing
`fetchActionCurl`, hitting the already-live `/api/cases/{engineId}/{caseInstanceId}/actions/{verb}/curl`
route (built in Phase 3, never consumed by the frontend until now). `CurlPreview` wired
into `ErrorsJobsTab.tsx`'s dead-letter `InlineConfirm` and `CasePage.tsx`'s case-retry
`InlineConfirm`, both lazy — nothing hits the BFF until the operator opens the "Show as
cURL" toggle, matching the existing modal precedent and the never-client-generated
invariant. `TaskAssignModal.tsx` (the one existing consumer) updated to the new prop
shape, no behavior change.

_Tests:_ new e2e cases in `deadletter-delete.spec.ts` (BPMN) and `case-detail.spec.ts`
(CMMN), both asserting the toggle fires zero `/curl` requests until clicked, then renders
the server string verbatim with the correct request body. Full e2e suite + `npx vitest
run` (530 tests) green; the existing `task-reassign.spec.ts` cURL case re-verified
unaffected by the `CurlPreview` prop refactor.

### #104 slice 1/4 — CSS design-token layer (R-UXQ-08) _(✅ LANDED 2026-07-13, issue #104)_

Per the plan's own scoping ("CSS token layer first, then three separate PRs — dark theme
/ density / AG Grid column chooser"): this is slice 1, the token layer only. No theme
switch, no `prefers-color-scheme`, no `.dark`/`[data-theme]` anywhere yet — those are the
three follow-up PRs' job.

_Audit before touching anything:_ `styles.css` (3,577 lines) had **zero** existing CSS
custom properties, zero `:root` block, zero theme infrastructure — a from-scratch token
layer, not a refactor. 492 hex-color occurrences across ~140 distinct values; a genuine
long tail (100+ values used once or twice, mostly near-duplicate pale-shade one-offs).
Checked every SPEC §10a-flagged encoding class (`.marker-diverge-*`, `.marker-picker-*`
from #102, `.status-badge`/`.status-chip.*`, `.callout-*`, `EnvBadge`'s `accentColor`) —
**all already compliant**, color is decorative/reinforcing everywhere, shape/glyph/text
carries the actual meaning. `EnvBadge`'s `accentColor` is a real per-engine
admin-configured DB column (`V7__engine_registry.sql`) — correctly left as an untouched
inline-style pass-through, never tokenized.

_Shipped:_ new `:root { }` block (25 `--color-*` custom properties across neutral/
danger/warning/info/success families) covering every color repeated ≥5 times. Mechanical,
scripted, byte-identical substitution of those literals with `var(--color-*)` references
— confirmed no-op by occurrence-count parity checks (each token's `var()` count matches
its literal's pre-substitution count exactly) and a live-browser screenshot comparison
before/after. The long tail of one-off near-duplicate shades is **deliberately left
untokenized** — inventing tokens nobody asked for, or silently merging visually-distinct
one-offs, is scope creep this slice doesn't need; each follow-up PR can tokenize what it
actually touches.

_Adversarial review (Copilot + independent senior-review pass, Gemini API was
quota-exhausted so a manual equivalent pass covered the same five angles):_ both
independently flagged the same real gap — `--color-text-secondary`/`--color-text-dim`/
`--color-text-muted` (and `--color-danger-mid`/`--color-danger-alt`) sit within
single-digit RGB distance of each other, i.e. visually indistinguishable, yet ship as
separate tokens with no comment distinguishing intended semantic weight. Correctly left
alone here (this slice is a mechanical, no-decisions rename — collapsing "near-identical"
colors is a judgment call outside that scope), but flagged forward: the dark-theme slice
must not naively invent 5-6 near-identical dark shades for what today renders as one or
two greys/reds — collapse or deliberately re-differentiate them there. Both reviewers'
other findings (token-count wording, naming bikeshedding, expanding this slice's scope to
merge one-offs) were checked against source and refuted or judged not actionable.

_Deliberately not done here:_ AG Grid has zero custom `--ag-*` overrides today (stock
`ag-theme-quartz`) — bridging into that vendor variable namespace is the column-chooser
PR's job, not this one's.

_Tests:_ `npx vitest run` (533 tests) + full local CI green — a value-identical rename
has no unit-testable behavior of its own. Live-verified via a Playwright screenshot
against the real running app (topbar, status badges, DLQ counts) — visually
indistinguishable from before, as expected for a byte-identical substitution.

### #104 slice 2a/5 — extended surface tokens (R-UXQ-08) _(✅ LANDED 2026-07-14, issue #104)_

An EXPANSION pass, not the dark-theme switch itself: slice 1 only tokenized colors
repeated ≥5 times. This slice lowers the bar to ≥2, adding 20 more `--color-*` custom
properties (45 total, 47 after the role-split noted below) for the group of colors slice 1
deliberately left out — chip/badge
surfaces (`--color-chip-bg-muted`, `--color-chip-text-muted`, `--color-chip-border-muted`,
`--color-badge-bg-subtle`), dark surfaces (`--color-surface-dark`, `--color-text-on-dark`),
panel backgrounds (`--color-bg-faint`, `--color-surface-code`), a divider
(`--color-divider-subtle`), two `var(--border|--accent, <fallback>)` custom-property
fallbacks (`--color-group-btn-border`, `--color-group-btn-accent`), a fleet-chip accent
(`--color-fleet-accent`), plus danger/warning/info/success family extensions
(`--color-danger-bg-alt`, `--color-danger-fill`, `--color-warning-bg-alt`,
`--color-warning-retry-accent`, `--color-info-border`, `--color-info-bg-alt`,
`--color-success-border`, `--color-success-bg`). Same mechanical discipline as slice 1:
scripted, case-insensitive, word-boundary-safe substitution; occurrence-count parity
verified per token (each `var()` count matches its literal's pre-substitution count, and
each literal now appears exactly once in the file — inside its own `:root` definition);
no consolidation of near-duplicate shades into one token, even where two of the 20 look
alike; existing 25 slice-1 tokens untouched.

_What's still deliberately untokenized:_ after this slice, 95 distinct literal hex colors
remain in the file body. Of those, **72 occur exactly once** — the true long tail slice 1
flagged — and are deliberately left as-is; slice 2b (or later cleanup) should reach for
scoped one-off dark-mode overrides on these rather than inventing 72 more global tokens
nobody will reuse. Separately, a post-substitution audit found **23 more distinct values
that do technically repeat ≥2 times** but fell outside this slice's fixed 20-item candidate
list (most are SVG `stroke`/two-stop-gradient pairs repeated only within a single
declaration — e.g. `#7fb3ea`, `#e79a9a`, `#efc4c4`, `#90a4ae`/`#cfd8dc` — plus a handful of
genuine cross-component repeats like `#1a4d80`, `#2c3e50`, `#566573`, `#a0aab5`,
`#721c24`, `#d1f2eb`, `#0a5c36`). These are flagged forward, not silently dropped: the next
tokenization pass should re-run the ≥2 audit against the file as it stands rather than
reusing this slice's now-stale candidate list.

_Tests:_ `npm run build` (tsc + vite) green; `npx prettier --write` reported the file
already correctly formatted. Full local CI (`scripts/ci-local.sh --full`) green.

_Adversarial review (Copilot + independent senior pass, Gemini quota-exhausted again):_
scripted parity/boundary/build verification all independently reconfirmed clean. One real
finding, fixed: `--color-surface-dark` and `--color-divider-subtle` had each been reused
across two DIFFERENT visual roles at different call sites (one as a background fill, one as
a foreground `color:`/border) purely because they happened to share a hex value — harmless
today (value-identical), but a hazard for slice 2b's actual dark-value assignment, since a
background wants to stay dark while foreground-on-light wants to invert to light. Split
into role-specific tokens: `--color-text-charcoal` (new, `.scope-drill`'s text) separated
from `--color-surface-dark` (background-only now, `.engine-card`/omnibox), and
`--color-track-bg` (new, `.timing-track`'s fill) separated from `--color-divider-subtle`
(border-only now). 47 tokens total after the split (was 45).

### #104 slice 2b/5 — the dark-theme switch itself (R-UXQ-08) _(✅ LANDED 2026-07-14, issue #104)_

The actual mechanism, building on slices 1/2a's now-complete token coverage: dark values
for all 47 `--color-*` tokens + one new one, a persisted three-way preference (System /
Light / Dark) with a `prefers-color-scheme` default, and a segmented `ThemeToggle` next to
`ZoneToggle` in the topbar.

_Shipped:_

- `frontend/src/lib/theme.ts` — mirrors `lib/format.ts`'s `DisplayZone` store exactly (lazy
  `localStorage`-hydrated module value, listener `Set`, get/set/subscribe +
  `useSyncExternalStore` hook), storage key `inspector.theme`. `setThemePreference()` (and a
  module-load call) applies the resolved state to `<html>`: `'system'` REMOVES `data-theme`
  entirely (the CSS media query alone decides, deliberately no `matchMedia` listener); `'light'`
  /`'dark'` set `data-theme`, which CSS overrides win in both directions. Imported in
  `main.tsx` for its module-load side effect, before `createRoot(...).render(...)`. Review
  noted `<script type="module">` is treated like `defer` by browsers, so this isn't an
  ironclad FOUC guarantee (an inline `<head>` script would be) for a user whose explicit
  override contradicts system preference — low-impact in practice since `#root` is empty
  until React mounts (only a blank `body { background }` could flash), but a real gap, not a
  guarantee; deliberately not adding a separate inline `<script>` for this now.
- `frontend/src/components/ThemeToggle.tsx` — mirrors `ZoneToggle.tsx`, a labeled 3-option
  `Segmented` (System/Light/Dark), mounted next to `ZoneToggle` in `Shell.tsx`'s topbar.
- `styles.css`: a dark value for every one of the 47 tokens plus one genuinely new token,
  `--color-page-bg` (`body` had no explicit `background` at all before this — now
  `#fff` light / `#14181c` dark), duplicated across a `@media (prefers-color-scheme: dark) {
:root:not([data-theme='light']) { … } }` block (system default) and a
  `:root[data-theme='dark'] { … }` block (explicit override, wins regardless of OS
  preference) — both required for correct cascade coverage. No existing token's LIGHT value
  or call site was touched.

_Draft palette vs. what actually shipped — every deviation was a REAL axe-core
color-contrast failure, not a theoretical judgment call (`frontend/e2e/dark-theme.spec.ts`,
5 states: triage landing, AG Grid search results, the errors-jobs instance tab, a tier-3
destructive modal, and the operations drawer after a dispatch — chosen to spread across
chips/badges, grid cells, form controls, and the topbar). Iterated to zero violations across
~90 initial findings, mostly a small number of repeating root causes:_

- `--color-text-faint` (`#6c7d89`→**`#8fa0ab`**) and `--color-text-dim` (`#82929d`→
  **`#a4b2bb`**) were too dark against the app's own dark surfaces (`.strip-note`,
  `.count-unit`, `.engine-version`, etc. — real body text, not decorative).
- `--color-danger-dark` (`#c0453a`→**`#e8695c`**), `--color-danger-mid` (`#cf5d4e`→
  **`#ea7a6e`**) and `--color-success` (`#3fa06a`→**`#4bb47c`**) were too dark/desaturated as
  _text_ accent colors (`.exception-class`, `.version-count`, the dead-letter `<summary>`,
  `.action-danger`, `.rev-reversible`) — brightening these three also fixed
  `.status-chip.failed`'s text-on-chip pairing for free, without pinning that chip to a
  literal.
- A missing-`color` latent bug, invisible in light mode, surfaced everywhere dark mode
  inverted a button's background: `<button>` (and UA-default styling generally) does not
  reliably inherit `color`, so `.segment`, `.copy-btn`/`.action-btn` variants, `.tab-button`,
  `.rail-toggle`, `.retry-group-btn`, and the unclassed modal Cancel button all rendered
  near-black default text. Fixed with **one** low-specificity dark-scoped rule giving every
  button an explicit `background: var(--color-white); color: var(--color-ink);` fallback —
  every button that already sets its own colors (`.segment-active`, `.action-danger`,
  `.modal-footer button.primary/.danger`, `.tab-active`) keeps winning via specificity.
  **Gotcha hit while building this:** nesting that fallback inside
  `:root[data-theme='dark'] { button { … } }` (or the `@media` equivalent) inflates its
  _effective_ specificity — `:root` and `[data-theme='dark']` each count as a class-level
  selector, so the nested form actually outranks plain single-class rules like
  `.segment-active` and silently broke them. Fixed with `:where()`, which zeroes the
  specificity of its argument: `:where(:root:not([data-theme='light'])) button` /
  `:where([data-theme='dark']) button` stay genuinely low-specificity, as originally
  intended. A parallel `:where(...) .ops-drawer code { color: var(--color-ink); }` covers
  the drawer's `<code>` verb/id spans the same way — deliberately scoped to the drawer
  rather than a blanket `code` selector, because AG Grid's own cell renderers also emit bare
  `<code>` (composite-ID column) on AG Grid's _own_ un-themed white cell background; #104 is
  explicit that AG Grid theming is a separate, later slice, and a blanket rule broke those
  cells in testing.
- The topbar reuses `--color-ink`/`--color-white` in REVERSED roles (dark bar, light text) —
  a coincidence of both tokens sharing a value in light mode only, the same "role collision"
  class slice 2a already split `--color-surface-dark`/`--color-text-charcoal` for. Once ink/
  white invert for their PRIMARY roles (general text, card surfaces), the topbar would flip
  to a light bar with dark text, breaking every literal color designed against it
  (`.topbar-link`, `.identity-who/-label/-role`, the "unknown" fallback, `.engine-name`'s
  inherited color). Rather than thread a new token through one component, the topbar gets a
  dark-scoped override pinning it to `var(--color-surface-dark)` / `var(--color-text-on-dark)`
  — tokens that DON'T invert for this use — so it stays a stable dark bar in both themes and
  every existing literal child color keeps working unmodified.
- A handful of literal (never-tokenized, outside the 47-token set) badge/chip/keycap
  backgrounds were hardcoded against a permanently-light canvas and don't adapt on their
  own: `.env-dev`/`.state-running`'s text (reused `--color-info-border`, already correctly
  inverted, instead of a new literal), `.status-chip.suspended`'s background, `.protected-
badge`'s background/border, `.bulk-bar`'s background, `.grid-keys kbd`'s background, and
  `.leak-views-caption`'s color (reused the now-fixed `--color-text-faint`) — all pinned to a
  readable dark-scoped literal or reused token, following the SAME "pin the badge, don't
  invert it" precedent `.status-chip.active/.completed/.retrying/.terminated` already set
  (pure literals, unaffected by theme). (`.drawer-live`/`.drawer-interrupted` were
  initially given the same dark-scoped `color` override, but review found their only
  color-setting ancestor, `.drawer-toggle`, already sets `color: var(--color-white)`
  unconditionally in both themes — the override was a no-op and was removed.)

_Tests:_ `frontend/e2e/dark-theme.spec.ts` (new, 5 real axe-core scans, all green) + every
pre-existing e2e a11y spec re-run (57/57 green — light-mode contrast unaffected, confirming
no light value or call site was touched) + `npx vitest run` (552/552, incl. new
`lib/theme.test.ts` mirroring `format.test.ts`'s DisplayZone coverage and
`components/ThemeToggle.test.ts` mirroring `ZoneToggle.test.ts`) + `npm run build` (tsc +
vite + the bpmn-watermark guard) + `scripts/ci-local.sh --full` (backend unaffected, unit
ladder green) all green.

_Adversarial review (Copilot + manual senior pass, Gemini quota-exhausted both tries):_
independently re-ran the full dark + light e2e suites and vitest against the committed
code (not just read the diff) and re-derived the WCAG contrast math for the reported
palette adjustments — all confirmed real. Two real findings, both fixed above: the
`.drawer-live`/`.drawer-interrupted` override was a no-op (removed) and this section's
test counts had drifted stale after the pre-merge rebase pulled in other sessions' landed
work (57 pre-existing specs, not 62; 552 vitest tests, not 546 — corrected). The FOUC
claim was also softened to state the real (low-impact) gap rather than an unqualified
guarantee. Other AI-generated findings (imagined race conditions, SSR concerns in a
pure-CSR app) did not hold up against the actual code and were rejected.

### #104 slice 3/6 — density toggle (R-UXQ-09) _(✅ LANDED 2026-07-14, issue #104)_

The grid half of R-UXQ-09 ("Grid: column chooser + density + layout persisted…
reset-to-default") — column chooser and layout-capture stay open for a later slice; this
slice is the density preference alone, applied globally to both AG Grid instances
(`ResultsGrid.tsx`'s `.grid-host` and `AuditLogPage.tsx`'s `.grid-host.ops-log-grid`), not a
per-grid setting.

_Shipped:_

- `frontend/src/lib/density.ts` — mirrors `lib/theme.ts`'s store exactly (lazy
  `localStorage`-hydrated module value, listener `Set`, get/set/subscribe +
  `useSyncExternalStore` hook), storage key `inspector.density`. `setDensity()` (and a
  module-load call) applies the preference to `<html>`: `'comfortable'` REMOVES
  `data-density` entirely (it's the CSS default, no selector needed — mirroring `theme.ts`'s
  `'system'` case); `'compact'` sets `data-density`. Imported in `main.tsx` for its
  module-load side effect, before `createRoot(...).render(...)`, same FOUC rationale as
  `theme.ts`.
- `frontend/src/components/DensityToggle.tsx` — mirrors `ThemeToggle.tsx`, a labeled
  2-option `Segmented` (Comfortable/Compact), mounted next to `ThemeToggle` in `Shell.tsx`'s
  topbar.
- `styles.css`: a single `[data-density='compact'] .ag-theme-quartz { … }` block overriding
  four of AG Grid Quartz's own `--ag-*` theme variables. Scoped to `.ag-theme-quartz` (not a
  bespoke class) so the one global preference reaches both grid hosts automatically.

_Compact values vs. the measured defaults —_ the installed `ag-grid-community` version is
**32.3.9**; its Quartz theme derives `--ag-row-height`/`--ag-header-height` from
`--ag-font-size` (14px) and `--ag-grid-size` (8px) (`ag-theme-quartz.css`), giving these
actual defaults:

| variable                       | measured default | compact override | reduction |
| ------------------------------ | ---------------- | ---------------- | --------- |
| `--ag-row-height`              | 42px             | **30px**         | 28.6%     |
| `--ag-header-height`           | 48px             | **36px**         | 25.0%     |
| `--ag-cell-horizontal-padding` | 16px             | **12px**         | 25.0%     |
| `--ag-font-size`               | 14px             | **13px**         | 7.1%      |

Row/header height and horizontal padding land in the requested ~25–30% band; font-size only
drops a point (7%) — shrinking it further started to compete with the row/header reduction
for legibility, and this grid's own column defs have no oversized text to reclaim space
from. `ResultsGrid.tsx`'s and `AuditLogPage.tsx`'s cell renderers were checked for
interactive elements before picking these numbers: both grids' in-cell affordances are text
links (`.grid-open-link`, the audit-log external link) and one copy-ID icon button
(`.copy-btn`, ~17px tall) — no button needs more room than it already uses, so 30px rows
leave comfortable click room; a future in-cell button renderer should re-check this.

_Verification:_ live Playwright check against the dev server — Comfortable vs. Compact
screenshots of the search-results grid (visibly tighter, same viewport shows ~8 rows compact
vs. ~5.5 comfortable, no clipping/overlap, "Open →" link and the copy-ID button stay
comfortably sized) and of the ops-log grid; a compact+dark combination screenshot (no
interaction with the dark-theme mechanism from slices 1/2a/2b); `localStorage` persistence
confirmed across a full reload (`data-density` applied before first paint, matching
`theme.ts`'s FOUC guard). Plus: `frontend/src/lib/density.test.ts` and
`frontend/src/components/DensityToggle.test.ts` (new, mirroring `theme.test.ts` /
`ThemeToggle.test.ts` exactly) + `npx vitest run` (all green) + `npm run build` (tsc + vite +
the bpmn-watermark guard) + the full pre-existing e2e suite re-run (62/62 green — no
regression from the new `data-density` attribute or CSS; no new Playwright spec added for
this slice per its low-risk scope, pure CSS variables + a persisted preference, no
contrast/accessibility color implications) + `scripts/ci-local.sh --full` (backend
unaffected, unit ladder green).

### #104 slice 4/6 — column chooser (R-UXQ-09) _(✅ LANDED 2026-07-14, issue #104)_

The remaining grid-scoped half of R-UXQ-09's "Grid: column chooser + density + layout
persisted…reset-to-default" — a custom hide/show dropdown for `ResultsGrid.tsx`'s 6 optional
columns, plus a reset-to-default action. Layout capture (column order/width persisted through
saved views) stays explicitly deferred — see the REQUIREMENTS-REGISTER note below.

AG Grid **Community** edition has no built-in column tool panel (that's an Enterprise-only
feature; ADR-002 mandates Community-only, per `ResultsGrid.tsx`'s own top-of-file comment), so
this is a small custom dropdown rather than a vendor panel.

_Locked vs. hideable split (`ResultsGrid.tsx`'s 10-column `columns` memo):_

- **Locked (always visible, never offered in the chooser)** — `open` (colId `'open'`, the
  row-open navigation link: hiding the only way to open a row is a usability trap),
  `protected` (colId `'protected'`, the R-SAFE-05 protected-instance badge — one of
  R-UXQ-09's "honesty indicators", so a protected instance can never look like any other row),
  `engineName` (field, R-UXQ-09 names it "Engine" explicitly), `status` (field, R-UXQ-09 names
  it "Status" explicitly; `StatusChip` carries the other inline honesty flags/badges).
- **Hideable (offered in the chooser)** — `processInstanceId` (Process ID), `businessKey`
  (Business Key), `definition` (colId, Definition), `startTime` (Start Time), `failureTime`
  (Failure Time), `currentActivityOrError` (Current Activity / Error).

_Shipped:_

- `frontend/src/lib/columnVisibility.ts` — mirrors `lib/density.ts`'s store shape (lazy
  `localStorage`-hydrated module value, listener `Set`, get/set/subscribe +
  `useSyncExternalStore` hook) but the value is a **set** of hidden colId/field strings, not a
  single enum: `getHiddenColumns(): ReadonlySet<string>`, `setColumnHidden(colId, hidden)`,
  `resetColumnVisibility()` (back to the empty-set default), `subscribeHiddenColumns`,
  `useHiddenColumns()`. Storage key `inspector.resultsGridHiddenColumns`, a JSON array. Default
  (nothing stored, or storage unavailable) is the empty set — everything visible, matching
  pre-existing behavior exactly, with no module-load DOM side effect (this store never touches
  `document.documentElement`, unlike `theme.ts`/`density.ts`).
- `frontend/src/components/ColumnChooser.tsx` — a toggle button
  (`aria-expanded`/`aria-haspopup="true"`) opening a panel of real `<input type="checkbox">` +
  `<label>` pairs, one per hideable column (checked = visible), a "Reset to default" button
  (disabled once nothing is hidden), and a note explaining why Open/Engine/Status/Protected
  aren't offered. Closes on Escape and on click-outside via a `document`-level
  keydown/mousedown listener pair (no existing shared non-modal-dropdown pattern in this
  codebase to reuse — `ModalShell.tsx`'s Escape handling is scoped to a full `<dialog>`).
- `ResultsGrid.tsx`: each hideable `ColDef` gets `hide: hiddenColumns.has(colId)`, computed
  declaratively inside the existing `columns` `useMemo` (no imperative
  `api.setColumnsVisible` calls); `useHiddenColumns()`'s return value joins `enginesById` in
  the memo's dependency array.
- `SearchPage.tsx`: `<ColumnChooser />` mounted in the results toolbar, next to the Refresh
  button (grid-scoped, not a global topbar preference like `ThemeToggle`/`DensityToggle`).
- `AuditLogPage.tsx`'s grid is explicitly untouched — R-UXQ-09's "Engine/Status/honesty
  indicators" language matches `ResultsGrid`'s columns specifically; out of scope for this
  slice.

_Verification:_ `frontend/src/lib/columnVisibility.test.ts` (12 cases, mirroring
`density.test.ts`'s coverage: default, hide/unhide, multi-column tracking, reset, persistence
round-trip, corrupt/non-array stored value, private-mode survival, subscribe/unsubscribe) +
`frontend/src/components/ColumnChooser.test.ts` (9 cases: collapsed by default, exactly the 6
hideable columns render checked, locked columns never appear, the explanatory note renders,
un/re-checking round-trips through the store, reset re-checks everything and disables itself
once nothing is hidden, Escape and click-outside both close the panel) + `npx vitest run` (all
582 tests green) + `npm run build` (tsc + vite + bpmn-watermark/no-enterprise guards) +
`npm run lint` (ESLint strict-type-checked, clean) + a new
`frontend/e2e/column-chooser.spec.ts` (hide a column → disappears from the grid → survives a
full page reload → reset restores it; a second spec covers click-outside-closes) with a
`scanA11y()` call taken with the panel **open** (the new interactive surface) + re-run of
`filter-bulk.spec.ts`, `dark-theme.spec.ts`, `deep-paging.spec.ts`, `saved-views.spec.ts` (13
tests, all green — no regression to `ResultsGrid`) + `scripts/ci-local.sh --full` (backend
unaffected, unit ladder green; frontend fast tier green).

_Adversarial review (Copilot + manual pass, Gemini quota-exhausted):_ one real finding,
fixed — Escape didn't restore focus after closing the panel (`ModalShell.tsx`'s own
focus-restore precedent, which this component otherwise doesn't share code with). Fixed
with a dedicated `toggleRef` restored to on Escape specifically — NOT on click-outside,
which deliberately leaves focus alone since the user just clicked something else and
forcing it back would fight that click. (An initial `previouslyFocused =
document.activeElement` approach, mirroring `ModalShell.tsx` more literally, proved
unreliable under jsdom's click-focus timing and was replaced with the explicit ref.) Two
new test cases added. Other findings — `HIDEABLE_COLUMNS`' hand-duplication against
`ResultsGrid.tsx`'s own column set — were judged an acceptable, non-blocking footgun given
the low cardinality (6 columns) and no stronger existing single-source-of-truth pattern in
this codebase to derive from; flagged as a good future cleanup, not required now.

### #200 — versioned release images "never published to GHCR" (root-caused, fixed) _(✅ LANDED 2026-07-14, issue #200)_

Filed as a suspected broken publish pipeline (`docker buildx imagetools inspect
ghcr.io/x3kcl/process-inspector-bff:v0.4.0` → not found for every v0.1.0–v0.4.0). Investigated
against the real GHCR registry (anonymous token + manifest/tag-list fetches, not just
Actions-log reading) and the actual `cut-release.yml`/`release.yml` run history. Two separate,
unrelated things were going on:

1. **A real, already-fixed pipeline bug.** v0.3.0 and v0.4.0's `cut-release` runs genuinely
   failed — `release.yml`'s `workflow_call` from `cut-release.yml` was missing
   `secrets: inherit`, so `secrets.DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` were empty inside
   `release.yml`, the "Log in to Docker Hub" step failed, and the job aborted _before_ either
   registry's push step ran — GHCR never got the image even though GHCR's own login (the
   always-injected `GITHUB_TOKEN`, not a repo secret) was fine. Already fixed by a parallel
   session's `fix(ci): inherit secrets when cut-release calls release.yml` — verified by
   re-running `cut-release` end-to-end (v0.5.0): every step green, including both registry
   pushes.
2. **A tag-naming footgun that made even the SUCCESSFUL releases (v0.1.0, v0.2.0, and now
   v0.5.0) look broken too.** `docker/metadata-action`'s `type=semver,pattern={{version}}`
   strips the git/release tag's leading `v` when generating the published image tag — the git
   tag is `v0.5.0`, the image tag is `0.5.0`. The issue's own reproduction command (and this
   repo's OWN documentation in three places) used the `v`-prefixed form, which 404s
   regardless of publish success. Confirmed empirically:
   `ghcr.io/x3kcl/process-inspector-bff:v0.5.0` → not found;
   `ghcr.io/x3kcl/process-inspector-bff:0.5.0` → resolves, matches the digest the workflow
   actually pushed.

_Fixed:_

- `.github/workflows/release.yml` — new "Compute the published IMAGE tag" step
  (`IMAGE_VERSION=${RELEASE_TAG#v}`, written to `$GITHUB_ENV`); the auto-generated GitHub
  Release body's `PI_VERSION=` pull instruction and "pin the digest, not the floating tag"
  line now use `IMAGE_VERSION`, not the git-tag-shaped `RELEASE_TAG` — every future release's
  own official instructions now hand out a tag that actually resolves.
- `docker/docker-compose.release.yml` and `docker/deploy-demo.sh`'s usage comments corrected
  from `vX.Y.Z`/`v1.2.3` examples to the real (un-prefixed) format.
- `deploy-demo.sh` also gained a defensive check: an `IMAGE_TAG` matching `^v\d+\.\d+\.\d+`
  now fails fast with "try '<tag minus v>' instead" rather than a generic registry
  "not found" — this exact mistake is what produced the issue, worth catching at the source
  rather than just fixing the docs that led to it.

_Verified NOT the cause (issue's own hypotheses #2/#3):_ GHCR package visibility/retention —
both packages remain public and reachable (anonymous registry token acquisition succeeds;
digest-based manifest fetches succeed for every published version); no evidence of tags being
deleted post-publish. The "missing" versions were never successfully pushed in the first
place (root cause 1), not deleted afterward.

### #201 — Continuous WAL archiving + PITR tooling for the audit-store Postgres _(✅ tooling LANDED 2026-07-15, issue #201; live activation deliberately NOT done — separate follow-up)_

The nightly logical dump (`backup-audit-db.sh`, P0 #4/Q4) closed the "zero copies" gap but
left a 24 h RPO — this adds the OTHER half of a real PITR setup: continuous WAL archiving +
the physical base backup it replays onto. WAL-based PITR needs a `pg_basebackup`, not the
existing `pg_dump` — the two mechanisms are incompatible, so this is additive tooling, not a
replacement.

_Shipped:_

- `docker/docker-compose.demo.yml`'s `postgres` service: a bind-mounted
  `${PI_BACKUP_DIR}/wal-archive` volume, and a `command:` override
  (`archive_mode=on`, an idempotent `archive_command`, `wal_level=replica` pinned explicitly).
  Verified against a running instance (`SHOW wal_level` → `replica`, `SHOW archive_mode` →
  `off`) that `postgres:16-alpine`'s own compiled-in default is already `wal_level=replica` —
  sufficient for archiving without a `logical` bump this app has no consumer for; pinned
  anyway so the file stays correct if a future base-image default ever changed. NOT applied
  to `docker/docker-compose.release.yml` — that file targets a quick-start self-hosted
  deploy where WAL-archive activation would need the same host-side directory/ownership
  ceremony this issue documents for the demo, adding real complexity to a file whose whole
  point is "up in one command"; skipped on purpose, not an oversight.
- `deploy/basebackup-audit-db.sh` (new): streams a `pg_basebackup -Ft -z -X none` (tar+gzip,
  no bundled WAL — pairs with the continuous archive instead) to
  `PI_BACKUP_DIR/basebackups/`, checksummed, crash-safe `.partial`→rename. **Weekly**
  retention keeping the newest 3 — a physical base backup is the full data directory (far
  bigger than a logical dump), and every backup after the first is redundant with the
  continuously archived WAL for recovery purposes; reasoning lives in the script's own header.
- `deploy/systemd/pi-audit-basebackup.{service,timer}` (new): mirrors the existing
  `pi-audit-backup.*` pair; scheduled Sunday 04:00 UTC — deliberately a different
  day/time than the nightly logical dump's 02:30 UTC so the two never contend for the same
  live-cluster read I/O window.
- `deploy/pitr-drill.sh` (new): mirrors `restore-drill.sh`'s safe-by-construction pattern
  (throwaway container — this time a throwaway _named volume_ too, since the base backup has
  to be unpacked into it by a one-shot helper container before the real Postgres container
  starts against it — `trap cleanup EXIT` removes both). Restores the latest base backup, then
  replays archived WAL via Postgres 16's CURRENT recovery mechanism — `recovery.signal` +
  `restore_command` (+ optional `recovery_target_time`/`recovery_target_action=promote`) in
  `postgresql.auto.conf`, confirmed this is the correct modern approach and NOT the pre-12
  `recovery.conf` file Postgres removed outright. Takes `--target-time` (flag or
  `PI_PITR_TARGET_TIME` env), defaulting to "replay everything available." Explicitly asserts
  `pg_is_in_recovery() = false` (fully promoted, not just accepting hot-standby reads) before
  running the same partitioned/partition-count/row-count integrity checks as
  `restore-drill.sh`.
- Docs: `docs/OPERATIONS.md` §4 now describes the new tooling as **shipped but not yet
  activated in prod** — the RPO claim deliberately stays 24 h until a drill runs against real
  accumulated live WAL history (not this issue's disposable-container rehearsal).
  `docs/RUNBOOK.md` §5 gained a status note pointing at the same honesty framing (today's
  actual 3 AM restore path is still the logical dump). `deploy/README.md` gained a full
  section (mirroring the existing `backup-audit-db.sh`/`restore-drill.sh` one) plus an
  "Activating WAL archiving" subsection spelling out the two-step human activation this PR
  deliberately does not perform: (a) create+own the host `wal-archive` directory then
  `docker compose -f docker/docker-compose.demo.yml up -d postgres` (recreates the service,
  restarts Postgres — a real if brief disruption, reserved for a human to run with separate
  confirmation), (b) run `basebackup-audit-db.sh` once to establish the first anchor, (c)
  install the weekly timer, (d) once real WAL history has accumulated, run `pitr-drill.sh`
  for real and only then bump the RPO claim.

_Verification — a real rehearsal against disposable infrastructure (not the live demo
container, never touched beyond read-only `SHOW`/`docker inspect` probes to confirm current
config):_ stood up a throwaway `postgres:16-alpine` with the exact same `archive_mode`/
`archive_command`/`wal_level` config as the compose change, hit an initial `Permission
denied` from `archive_command` (the bind-mounted host dir defaults root-owned; fixed by
`chown`-ing it to uid **70**, confirmed empirically as the `postgres` user inside this image
— now called out explicitly in `deploy/README.md`'s activation steps), created a partitioned
`audit_entry` table with rows, ran `basebackup-audit-db.sh` against it (verified retention
pruning by running it 4×, confirming the oldest was dropped keeping 3), inserted more rows +
forced WAL switches, captured a target timestamp, inserted yet more rows past that point, then
ran `pitr-drill.sh` twice: with no target (recovered **all** rows — 100/100) and with
`--target-time` pinned to the captured instant (recovered **exactly** the rows committed
before it — 80/100, correctly excluding the later insert). Both runs' integrity checks
(partitioned `audit_entry`, ≥1 partition, row count) passed and confirmed `pg_is_in_recovery()
= false` (fully promoted). One rehearsal-only false start along the way, root-caused and
instructive rather than a script bug: batching `INSERT; SELECT pg_switch_wal();` in one
`psql -c` string runs them as a single implicit transaction, so the forced segment switch
closed the WAL segment _before_ the COMMIT record was written — `pg_waldump` on the archived
segment confirmed the inserts were present but the transaction had no COMMIT record yet.
Splitting into separate `psql -c` calls (each auto-commits) fixed the test; the drill itself
correctly refused to replay the still-in-flight transaction both times, which is precisely the
transactional-boundary correctness PITR must have. All test containers/volumes/scratch
directories were torn down after verification.

`scripts/ci-local.sh --full` run clean (deploy-tooling-only change, no backend/frontend
diff); `bash -n` on both new scripts and the syntax-checked existing ones; both compose files'
YAML parsed via `python3 -c "import yaml; yaml.safe_load(...)"`.

### #201-followup — Docker-native backups: replacing host-systemd + host bind-mounts with compose services + named volumes _(✅ LANDED 2026-07-15; live activation deliberately NOT done — separate follow-up, same discipline as #201)_

Issue #201's design needed host-level pieces (`sudo`-installed systemd timers, a
`sudo`-created uid-70-chowned host bind-mount directory) the automation session operating this
repo doesn't have host root for. This follow-up REPLACES the host-dependent pieces with fully
Docker-native equivalents — new services in `docker/docker-compose.demo.yml`, scheduled
**internally** (no host cron/systemd), storing backups in **named Docker volumes** (no host
bind-mount), connecting to Postgres **over the network** rather than `docker exec`. Ports the
exact same safety properties (checksums, `.partial`→rename crash-safety, retention pruning,
the same integrity checks in the drill scripts) — a delivery-mechanism change, not a
backup-strategy redesign.

_Shipped:_

- `docker/backup/audit-backup/{entrypoint.sh,backup-once.sh,crontab}` (new) + a new
  `audit-backup` compose service (`postgres:16-alpine` base — has `pg_dump`; busybox `crond`
  confirmed present at `/usr/sbin/crond`, empirically verified it inherits the container's
  full environment unlike some cron implementations, so `PGPASSWORD` etc. reach the cron job
  with no env-file workaround needed): nightly `pg_dump -Fc` over the network
  (`PGHOST=postgres`, standard libpq env vars) to the new named volume
  `inspector-logical-dumps`, same 02:30 UTC cadence / `.partial`→rename / sha256 sidecar /
  35-day retention as `backup-audit-db.sh`. One-shot/manual: `docker compose ... run --rm
audit-backup /scripts/backup-once.sh`.
- `docker/backup/audit-basebackup/{entrypoint.sh,basebackup-once.sh,crontab}` (new) + a new
  `audit-basebackup` compose service: weekly (Sunday 04:00 UTC) `pg_basebackup -Ft -z -X none
-c fast` over the network to the new named volume `inspector-basebackups`, same
  checksum/newest-3-retention as `basebackup-audit-db.sh`.
- `docker/backup/wal-receiver/entrypoint.sh` (new) + a new `wal-receiver` compose service:
  continuous WAL capture via `pg_receivewal` against the replication protocol, writing to the
  new named volume `inspector-wal-archive` — **replaces** issue #201's
  `archive_mode`/`archive_command` mechanism entirely (removed from `docker-compose.demo.yml`'s
  `postgres` service; `pg_receivewal` needs only `wal_level=replica`, already pinned there).
  Backed by a **permanent physical replication slot** (`--create-slot --if-not-exists` on
  every start) so Postgres retains WAL for however long the receiver is down — the property
  that makes it safe as a long-lived, restartable service. Runs `pg_receivewal` as the
  `postgres` container's own uid (70) via `gosu`, not root: `pg_receivewal` writes each
  segment `0600`, and a root-owned segment turned out to be unreadable to the `postgres`-uid
  process a recovery `restore_command` runs as — hit this exact `Permission denied` in
  rehearsal (see Verification below), fixed by chowning the volume + `gosu postgres` before
  streaming.
- `docker/backup/postgres/pg_hba-demo.conf` (new), wired into `docker-compose.demo.yml`'s
  `postgres` service via `command: -c hba_file=...`: extends the upstream image's own
  generated default `pg_hba.conf` with a `replication` entry for the internal docker network
  (same `scram-sha-256` password requirement the image's existing ordinary-connection
  catch-all already applies). REQUIRED — empirically confirmed the upstream default only
  trusts replication connections from `127.0.0.1`/`::1`, so a plain network
  `pg_basebackup`/`pg_receivewal` fails closed (`no pg_hba.conf entry for replication
connection from host "..."`) without it.
- `deploy/restore-drill.sh` / `deploy/pitr-drill.sh` (updated): both now read from the named
  volumes above DIRECTLY — `docker run`/`docker exec -v <volume>:...` mounts a named volume by
  name regardless of host-path visibility, so no host-copy indirection is needed. Both keep a
  legacy host-path mode (an explicit existing host path/dir as an argument or
  `PI_WAL_ARCHIVE_HOST_DIR`) for anyone still on the superseded host-systemd mechanism.
- `deploy/systemd/pi-audit-{backup,basebackup}.{service,timer}` (updated): header comments
  mark them SUPERSEDED for this repo's Compose-based demo, pointing at the new compose
  services — not deleted, since they document a real prior design that may still fit a
  non-Compose (bare-host) deployment.
- `docker/deploy-demo.sh` / `docker/rollback-demo.sh` (updated): their scoped `up -d` (issue
  #201's own fix, avoiding accidental `postgres` config-drift reconciliation) now ALSO
  includes the three new services (`audit-backup audit-basebackup wal-receiver`) alongside
  `backend frontend` — they don't carry `postgres`'s "restart disrupts live traffic" risk (no
  client ever connects to them; they only connect OUT), so routine reconciliation on every
  deploy is safe and desirable rather than something to guard against. `postgres` itself stays
  excluded, unchanged reasoning from #201.
- Docs: `docs/OPERATIONS.md` §4, `docs/RUNBOOK.md` §5, `docs/IMPLEMENTATION-PLAN.md` (this
  entry), and `deploy/README.md` all corrected (not just appended to) to describe the
  Docker-native mechanism as the current path for the Compose-based demo, retiring the
  #201-era host-systemd instructions as the primary story. Same honesty discipline #201
  established: the RPO claim stays 24 h until a REAL drill runs against real accumulated WAL
  history from the LIVE system — this issue's rehearsal, like #201's, only proves the
  mechanism against disposable test infrastructure.

_Verification — a real rehearsal against disposable `docker compose` infrastructure (its own
throwaway Postgres + the three new services; never the live demo container, volumes, or
`/var/backups/pi-audit` host directory — read-only `docker ps`/`docker volume ls` only to
confirm they were untouched afterward):_ confirmed `busybox crond` present in
`postgres:16-alpine` and that it inherits the container's full environment (no env-file
workaround needed for `PGPASSWORD` to reach cron jobs); confirmed `pg_dump`/`pg_basebackup`/
`pg_receivewal` all present; confirmed the upstream image's default `pg_hba.conf` refuses a
network `pg_basebackup`/`pg_receivewal` (`no pg_hba.conf entry for replication connection`)
and that the custom `pg_hba-demo.conf` (mounted via `hba_file=`) fixes it. Generated activity
and forced `pg_switch_wal()` — confirmed WAL segments landed in the `inspector-wal-archive`
volume. **The property that matters most**: killed `wal-receiver` mid-stream, confirmed the
replication slot went `active=f` while holding `restart_lsn` at the pre-kill floor, generated
5 more forced WAL switches' worth of activity while it stayed down, restarted the container —
it resumed streaming from exactly `restart_lsn`, and every segment produced across the
outage boundary was later confirmed complete (`stat` size = 16 MiB, no `.partial` suffix) and
uncorrupted (`pg_waldump` parsed every segment cleanly, transactional boundaries — INSERT
records through COMMIT through the SWITCH marker — all intact). Hit and fixed the root-owned
WAL segment permission bug during this same rehearsal (see Shipped above). Proved
`audit-backup`'s `pg_dump` and `audit-basebackup`'s `pg_basebackup` both succeed over the
network with password auth (not `docker exec`). Proved retention pruning's 0/N/N+1 edge cases
for both backup types (basebackup: 0→3 no prune, then a 4th run correctly pruned the oldest
back to 3; logical dump: a faked 40-day-old dump was pruned on the next run while a faked
34-day-old one was correctly retained, alongside real same-run dumps). Ran
`deploy/pitr-drill.sh` against the new volume-based storage in both modes: "replay everything"
recovered all 100 rows (50 seed + 25 pre-target + 25 post-target); `--target-time` pinned to
the captured instant recovered exactly 75 (correctly excluding the post-target insert) — hit
the SAME `INSERT; SELECT pg_switch_wal();` single-transaction artifact #201's own rehearsal
already documented (this time against the new mechanism) and fixed it the same way (separate
`psql` invocations per statement). Ran `deploy/restore-drill.sh` against the new
`inspector-logical-dumps` volume, both with the default (newest-dump) selection and an
explicit in-volume filename — passed, `audit_entry` came back partitioned with its rows. Also
validated the ACTUAL shipped `docker/docker-compose.demo.yml` (not just a scratch mirror) by
starting `postgres`/`audit-backup`/`audit-basebackup`/`wal-receiver` from it under an isolated
compose project name, confirming the same behavior. All test containers, volumes, and networks
were torn down after verification; the live `process-inspector-demo-*` containers/volumes were
never started, stopped, recreated, or written to.

`scripts/ci-local.sh --full` run clean.

_Adversarial review (Copilot + manual pass, Gemini quota-exhausted for 2.5-pro):_ four real
findings, all fixed. (1) The permanent replication slot's decommissioning risk (unbounded
disk growth if `wal-receiver` is ever removed without dropping it first) was undocumented —
added to both the entrypoint header and `docs/RUNBOOK.md`. (2) `deploy-demo.sh`/
`rollback-demo.sh` claimed routine deploys "pick up script/crontab changes promptly" for the
three sidecars, but `docker compose up -d` only recreates on a CONFIG HASH change (image/env/
volume list), not bind-mounted file content — an edit to `backup-once.sh`/`crontab` alone
wouldn't actually trigger a recreate. Fixed with `--force-recreate` scoped to just those
three services. (3) `deploy/backup-audit-db.sh`/`basebackup-audit-db.sh` (the original #201
scripts) were missing the SUPERSEDED header the systemd unit files already got — added,
matching style. (4) No CI gate syntax-checked any of this repo's growing set of deploy/CI
shell scripts — every prior verification was a manual, one-time `bash -n` pass. Added
`scripts/shell-syntax-check.sh` (tracked-file, shebang-detected, `bash -n`-only — no new
tool/runner dependency) wired into both `ci-local.sh` and `.github/workflows/ci.yml`'s lint
job, closing the gap going forward. One other gap found independently while fixing the
above: `docs/RUNBOOK.md`'s top-of-file "Facts" section still claimed "RPO ≤ 5 min
(Postgres WAL/PITR)" — a stale figure predating even issue #201's original honesty
correction to `docs/OPERATIONS.md` §4, silently contradicting that same file's own §5
further down. Corrected to the real current number (24h) with a pointer to §5.

## Build order inside any milestone

backend DTO → engine client call → aggregator/join logic → controller → typed frontend API
client → component. Every Flowable call gets an integration test against the dockerized
`flowable-rest` on BOTH compose profiles (no mocked Flowable responses for join logic — the
DLQ/suspended/hierarchy joins are where the bugs live).
