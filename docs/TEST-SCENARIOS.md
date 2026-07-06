# 🎬 TEST SCENARIOS — Process Inspector

The executable scenario catalog: realizes TEST-STRATEGY §6 (fixture catalog, R-TEST-04) and
maps the risk floors (R-TEST-01) to named, automatable scenarios. Scenario IDs (`TS-…`) and
fixture IDs (`FIX-…`) are the handles used in test names, tickets, and milestone done-when
clauses (R-SEM-07). An empty matrix cell (§1.2) is a visible coverage gap.

**Data stages** — generation rules are normative in TEST-STRATEGY §10:

| Stage | Data | Where |
|---|---|---|
| **S1** | Synthetic — fixture builders, WireMock/MockWebServer stub payloads, replay of captured corpora | JVM/CI only |
| **S2** | Generated real data — seeded strictly over REST onto the dockerized engines, all three compose profiles (`6x` default · `legacy` pre-6.4/6.5 · `7x`) | docker compose harness |
| **S3** | Production — existing data on a registered prod engine, **read-only observation** | pilot / prod registry |

**Layers** (per the `unit-test-patterns` ladder): **L1** pure logic · **L2** HTTP stub ·
**L3** cached `@SpringBootTest` · **L4** dockerized engine · **E2E** Playwright, full stack.
A scenario listed at L4/E2E usually has an L1 mirror over fixture collections — the L1 mirror
never *replaces* the L4 proof for join/status semantics (iron rule, `engine-harness`).

## 1. Fixture catalog (FIX-*) — owner: lead dev

> Status: `docker/` currently contains only the compose file. Every FIX-PROC/FIX-CASE seed
> below is an open **M2a entry-gate deliverable** (TEST-STRATEGY §2): BPMN under
> `docker/processes/` (authored per `validate-bpmn`: DI mandatory, stable keys), seeded by an
> idempotent `docker/seed.sh` + per-test setup classes, strictly over REST.

### 1.1 Seed processes (S2 generators)

*Status: FIX-PROC-01..06 are landed in `docker/processes/` and started by `docker/seed.sh`
(the SUSPENDED arc is manufactured by seed.sh via REST suspend on a `demoUserTask`
instance); FIX-PROC-07..10 and FIX-CASE-01 are still to author.*

| ID | Key | Shape / failure mechanism | Manufactures |
|---|---|---|---|
| FIX-PROC-01 | `demoOrder` | straight-through service task, expression `${true}` | COMPLETED |
| FIX-PROC-02 | `demoUserTask` | parks on a user task (assignee/candidate group per `validate-bpmn` §4) | ACTIVE; task fixtures; suspend target; variable-edit target |
| FIX-PROC-03 | `demoTimerWait` | intermediate timer, duration from variable `${dueDuration}` | timer lane; "waiting for timer <due>"; timers-due-in-window (R-BAU-08) |
| FIX-PROC-04 | `demoFailingPayment` | `flowable:async="true"` service task, expression `${amount % divisor}` with `divisor=0`; `failedJobRetryTimeCycle="R1/PT1S"` | dead-letter job in seconds; **the recovery arc**: edit `divisor`→1, retry, completes |
| FIX-PROC-05 | `demoFailingRetry` | same expression; `failedJobRetryTimeCycle="R10/PT1H"` | RETRYING pinned in the **timer table** (`withException`), stable for 1 h (R-TEST-07) |
| FIX-PROC-06 | `demoParent` | call activity → `demoFailingPayment` child, businessKey propagated | `failedInSubprocess` roll-up, depth 1 |
| FIX-PROC-07 | `demoRecursive` | self-recursive call activity, `depth+1` in-parameter, recurses while `depth < maxDepth`; calls `demoFailingPayment` at the leaf | hierarchy chains of arbitrary depth; roll-up at limit / limit+1 |
| FIX-PROC-08 | `demoEventWait` | event-based gateway: message catch `PaymentReceived` vs signal catch `RetryBatch` | event subscriptions visible; unstick targets |
| FIX-PROC-09 | `demoMultiInstance` | MI subprocess over collection variable | execution-local loop variables; change-state MI-body refusal |
| FIX-PROC-10 | `demoParallelJoin` | parallel split → two user tasks → join | parallel-join change-state warning |
| FIX-CASE-01 | `demoCase` (CMMN) | case with an async plan item | cmmn-scoped jobs (null `processInstanceId`) for join filtering. If a profile lacks CMMN REST, this cell falls back to L1 over captured job JSON — documented S1 exception |

### 1.2 The SPEC §3 flag matrix → fixture map (mandatory cells, TEST-STRATEGY §6)

| Matrix cell | Recipe (all over REST; poll-with-deadline, never sleeps) | Fixture ID |
|---|---|---|
| `ended` alone | start FIX-PROC-01 | FIX-STATUS-01 |
| active (no flags) | start FIX-PROC-02 | FIX-STATUS-02 |
| `suspended` alone | FIX-STATUS-02 → `PUT /runtime/process-instances/{id}` `{"action":"suspend"}` | FIX-STATUS-03 |
| `hasDeadLetterJobs` alone | start FIX-PROC-04; poll `/management/deadletter-jobs` (30 s deadline) | FIX-STATUS-04 |
| `hasFailingJobs`, timer table | start FIX-PROC-05; poll `/management/timer-jobs?withException=true` | FIX-STATUS-05a |
| `hasFailingJobs`, job table | L1 fixture row + L2 stub for the `/management/jobs?withException=true` wire mapping (the state is inherently transient on a live executor — documented pragmatic split) | FIX-STATUS-05b |
| suspended **+** DLQ collision | FIX-STATUS-04 first, then suspend the instance | FIX-STATUS-06 |
| `failedInSubprocess`, depth 1 | start FIX-PROC-06 | FIX-STATUS-07 |
| roll-up at depth limit / limit+1 | FIX-PROC-07 with `maxDepth` = limit, then limit+1 | FIX-STATUS-08 |
| cycle guard | L1 over a fixture parent-map — **the sanctioned never-mock exception** (real engines cannot produce cycles, R-TEST-07) | FIX-STATUS-09 |
| CMMN rows filtered | FIX-CASE-01 alongside BPMN failures on the same engine | FIX-STATUS-10 |
| tenant threading | FIX-TENANT-01: deploy `demoOrder`+`demoFailingPayment` under `tenant-a` and `tenant-b`; failures seeded only in `tenant-a` | FIX-STATUS-11 |

### 1.3 Data-shape recipes (S2, applied to running instances)

| ID | Recipe | Used by |
|---|---|---|
| FIX-DATA-01 | PUT an 8 KiB+ and a >5 MiB variable onto a FIX-PROC-02 instance | preview cap / full-fetch / download-only (R-NFR-01) |
| FIX-DATA-02 | create a `serializable` variable via the REST binary part | read-only rendering + tooltip (SPEC §4 Variables) |
| FIX-DATA-03 | hostile text in variables/businessKeys (CSV formulas `=cmd`, CR/LF, ANSI, 200-char+ unicode) | injection defenses (R-OPS-08) |

### 1.4 Captured corpora (S2 outputs, committed with the engine image tag; re-captured on image bump)

| ID | Capture | Rule |
|---|---|---|
| FIX-CORPUS-ERR-6X / -7X | ≥30 distinct exception payloads per engine major, produced by seeding ≥30 distinct failure expressions on the live profiles; script `docker/capture-error-corpus.sh` → `backend/src/test/resources/error-signatures/{6.x,7.x}/` | never hand-written (R-SEM-03); CI replays as S1 goldens |
| FIX-CORPUS-CAP | capability-probe response snapshots per profile (`6x`/`legacy`/`7x`) | drives the TS-CAP matrix |
| FIX-CORPUS-7XERR | Spring Boot 3 problem-details error JSON from the `7x` profile | normalizer must never degrade to "unparseable" (SPEC §10) |

### 1.5 Synthetic stub recipes (S1 — client-fault shapes ONLY, never join semantics)

| ID | Stub | Used by |
|---|---|---|
| FIX-STUB-01 | read-timeout / 503 / connection-refused per engine | error envelope, breaker |
| FIX-STUB-02 | truncated DLQ page (`total > fetched`); paging served up to N then 500 | truncation labeling |
| FIX-STUB-03 | 50k-row DLQ pager | P2 (never seeded on a real engine) |
| FIX-STUB-04 | latch-gated mutation endpoint (concurrency 1 ⇒ total dispatch order) | UNKNOWN, cancel, stagger timing |
| FIX-STUB-05 | hostile exception message (1 MiB trace, formulas, control chars) | R-OPS-08 CI fixture |
| FIX-STUB-06 | replay of FIX-CORPUS-ERR/7XERR payloads | golden-file CI, error-shape contract |

### 1.6 Reference dataset (S2, nightly — the R-NFR-02 latency target)

FIX-REF-01: `docker/seed-reference.sh`, deterministic and parameterized — per engine:
3 definitions × 3 versions × 4 failure signatures; ~20k historic + 2k active instances,
**5k dead-letter jobs** (envelope ceiling, R-NFR-05), 200 suspended, tenants a/b. Generated on
the compose engines only; anything larger than the envelope is S1-stubbed (FIX-STUB-03),
never seeded (R-TEST-07).

## 2. Status join & flags — TS-STAT (risk rank R1)

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-STAT-01 | FIX-STATUS-01 → `ended` from historic `endTime`; chip COMPLETED | S2/L4 | SPEC §3 |
| TS-STAT-02 | FIX-STATUS-02 → ACTIVE; no false flags | S2/L4 | SPEC §3 |
| TS-STAT-03 | FIX-STATUS-03 → `suspended` via per-row runtime enrichment; jobs shown in suspended lane | S2/L4 | SPEC §3 |
| TS-STAT-04 | FIX-STATUS-04 → `hasDeadLetterJobs`; chip "FAILED — needs action" | S2/L4 | SPEC §3, R-SEM-02 |
| TS-STAT-05 | FIX-STATUS-05a → `hasFailingJobs`; chip `RETRYING (1/10, auto)` + next-retry time; 05b wire mapping at L2 | S2/L4 + S1/L2 | SPEC §3, R-SEM-02 |
| TS-STAT-06 | FIX-STATUS-06 → BOTH flags true; primary chip per §3 precedence + secondary collision badge rendered | S2/L4 + E2E | SPEC §3 |
| TS-STAT-07 | FIX-STATUS-07 → parent flagged `failedInSubprocess`, badge "FAILED — in subprocess *X*", badge deep-links to the child's Errors & Jobs tab | S2/L4 + E2E | SPEC §3, R-UXQ-11 |
| TS-STAT-08 | FIX-STATUS-08 at limit → resolved; at limit+1 → "depth limit reached" affordance, no unbounded recursion | S2/L4 | SPEC §4 Hierarchy |
| TS-STAT-09 | FIX-STATUS-09 cycle in parent-map → guard terminates, instance labeled, no hang | S1/L1 | R-TEST-07 |
| TS-STAT-10 | FIX-STATUS-10 → cmmn-scoped jobs excluded from every join leg; rows carry `scopeType` | S2/L4 | SPEC §3 |
| TS-STAT-11 | FIX-STATUS-11 → every query leg tenant-filtered; tenant-b search shows zero tenant-a failures | S2/L4 | SPEC §3 |
| TS-STAT-12 | test registry `dlq-scan-cap: 50`, seed 60 FIX-PROC-04 instances → `dlqScan: truncated@50`; un-scanned instances NEVER rendered healthy; counts labeled lower-bound | S2/L4 | SPEC §3, principle 2 |
| TS-STAT-13 | FAILED-only search selects the DLQ-driven inverted plan (assert via the evidence view's plan choice) | S2/L4 | ARCH §2.3, R-L3-01 |
| TS-STAT-14 | full flag-combination matrix over fixture collections (every §1.2 cell + impossible-combination guards) | S1/L1 | R-TEST-01 (≥90% branch) |
| TS-STAT-15 | the six DESIGN-REVIEW join bugs, each re-expressed as a red-first regression | S1/L1 + S2/L4 | R-TEST-02 (M2a gate) |

All TS-STAT L4 rows run on **all three compose profiles** (R-TEST-01).

## 3. Aggregation, resilience & honesty — TS-AGG

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-AGG-01 | `docker stop engine-b` mid-search → HTTP 200, `perEngine[b].ok=false`, amber banner "1 of 2 engines", engine-a rows intact | S2/L4 + E2E | principle 1 |
| TS-AGG-02 | FIX-STUB-01 read-timeout → error envelope (never an exception); search returns within read-ms + margin | S1/L2 | SPEC §2 |
| TS-AGG-03 | breaker test profile (window 2, open 500 ms): consecutive faults trip breaker → "circuit open — engine shedding load" envelope; **cache hits do not count against the breaker** | S1/L2–L3 | SPEC §2, R-TEST-07 |
| TS-AGG-04 | engine recovers → half-open probe → closed; health flap damping (2 fails → unreachable, 1 success → reachable) | S1/L2 | R-NFR-04 |
| TS-AGG-05 | FIX-STUB-02 `total > fetched` → per-engine "fetched of total" chips; every derived count labeled lower-bound | S1/L2 + E2E | principle 2 |
| TS-AGG-06 | zero rows while an engine is down → amber "NOT a confirmed zero" zero-state | E2E | R-UXQ-04 |
| TS-AGG-07 | fan-out across 11 engines refused with the stated reason (≤10 cap) | S1/L3 | R-NFR-01 |
| TS-AGG-08 | `correlationId` present in every per-engine envelope and propagated across the virtual-thread fan-out | S1/L3 | R-AUD-04 |

## 4. Triage landing — TS-TRI

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-TRI-01 | health strip: per-engine env badge, version, four job-lane counts match seeded reality | S2/L4 + E2E | SPEC §4 Stage 0 |
| TS-TRI-02 | alarm thresholds via `Clock` bean + test-profile thresholds (seconds): oldest-executable warn/crit; overdue timers | S1/L1–L3 | R-NFR-04, R-TEST-07 |
| TS-TRI-03 | seed 3 distinct failure expressions × N instances (FIX-REF-01 subset) → groups by normalized signature with per-engine and per-definition-version counts | S2/L4 | SPEC §4 Stage 0 |
| TS-TRI-04 | golden corpus replay: zero unparseable, exact signature + group mapping; normalizer change ⇒ `algoVersion` bump + regenerated goldens + grouping diff in PR | S1 replay of S2 corpus | R-SEM-03, TEST-STRATEGY §4 |
| TS-TRI-05 | group / per-version count click-through → pre-filtered search; filter state echoed before commit | E2E | R-SEM-12 |
| TS-TRI-06 | 100 landing loads in 5 s → one engine-query round per aggregation (≥99% cache hits); "as of" stamp shows cache age | S2 (P4) | R-NFR-03, R-TEST-05 |
| TS-TRI-07 | Refresh bypass rate limit 1/10 s/user → 429 + retry-after | S1/L3 | R-NFR-03 |
| TS-TRI-08 | Stage-0 counts under truncation/engine error carry the same lower-bound badges as the grid | E2E | R-SEM-12 |
| TS-TRI-09 | acknowledge error group (who + reason + expiry) → collapses to "Acknowledged (N)", never hidden; resurfaces on +threshold growth or new definition version; audited | S2/L4 + E2E | R-BAU-01 |
| TS-TRI-10 | leak views (*Active > 30/90 d*, *Suspended > 7 d*) with config thresholds in seconds; age = now−startTime from ENGINE timestamps | S2/L4 | R-BAU-02, R-TEST-07 |
| TS-TRI-11 | curated-view honesty: "suspended ∧ no audit/notes activity in 24h" evaluates exactly its labeled predicate | S1/L1 + S2/L4 | R-SEM-05 |

## 5. Search, filters & omnibox — TS-SRCH / TS-OMNI

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-SRCH-01 | AND-between-categories / OR-within proven against a seeded distribution; compiled-criteria echo matches the executed plan | S1/L1 + S2/L4 | SPEC §8 |
| TS-SRCH-02 | failure-time predicate operates on DLQ job `createTime`, independent of instance `startTime` (L1 matrix proves independence; L4 proves the wire mapping) | S1/L1 + S2/L4 | SPEC §8 |
| TS-SRCH-03 | businessKey exact + `businessKeyLike`; hierarchy-aware: tree found from the child's key, root-vs-child markers | S2/L4 | SPEC §8, R-UXQ-12 |
| TS-SRCH-04 | variable search (name/op/value, `like`); unindexed warning rendered; per-engine require-definition flag enforced (rejected without definition filter) | S2/L4 + L3 | SPEC §8 |
| TS-SRCH-05 | error-text search only combined with a failed/retrying predicate — refused otherwise; applied pre-hydration to the scanned job set, truncation-labeled | S1/L1 + L3 | R-SEM-06 |
| TS-SRCH-06 | URL state round-trip: search → copy URL → fresh session reproduces filters, sort, and result identity | E2E | SPEC §4 Stage 1 |
| TS-SRCH-07 | copy-as-cURL output executed verbatim against `/api/search` returns the same rows | E2E (S2) | principle 5 |
| TS-SRCH-08 | facet counts match the seeded distribution; under truncation they carry lower-bound labels | S2/L4 | SPEC §8 |
| TS-OMNI-01 | each ID kind resolves in documented order (process instance, execution, task, job, composite, business key); business key → always a pre-filtered search | S2/L4 | R-SEM-04 |
| TS-OMNI-02 | >1 match → disambiguation list (kind, engine badge, status); zero → explicit not-found + engine list; engine down → "resolved against N of M" | S2/L4 + E2E | R-SEM-04 |
| TS-OMNI-03 | composite ID splits on FIRST `:`; hostile/odd IDs property-tested; frontend URL-encodes path segments | S1/L1 | R-SEM-08 |

## 6. Instance detail — TS-DET

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-DET-01 | FAILED vitals: exception first line, "3/3 exhausted", failing activity, stacktrace expander; RETRYING: "attempt 1 of 10, next retry <t>" + countdown | S2/L4 + E2E | SPEC §4 Stage 2, R-UXQ-03 |
| TS-DET-02 | waiting-for: FIX-PROC-08 shows message/signal subscription names; FIX-PROC-03 shows timer due date | S2/L4 | SPEC §4 Stage 2 |
| TS-DET-03 | diagram: token markers on active activities, red badge on DLQ activity, selection synced with tabs; every seed renders (DI mandatory) | E2E (S2) | SPEC §4, `validate-bpmn` |
| TS-DET-04 | variables: typed ledger rendering — never raw-JSON-primary (plain-language type chips, scope groups + shadowing badge, null explicit, json as summary → lazy virtualized tree); FIX-DATA-02 serializable value read-only with tooltip + "what to do instead" path | S2/L4 + E2E | SPEC §4 Variables, R-UXQ-13 |
| TS-DET-05 | FIX-DATA-01: 8 KiB preview cap + "load full value"; >5 MiB download-only; neither browser nor engine harmed | S2/L4 + E2E | R-NFR-01 |
| TS-DET-06 | FIX-PROC-09 loop variables shown execution-local per node in the execution tree | S2/L4 | SPEC §4 Variables |
| TS-DET-07 | four job lanes rendered distinctly; stacktrace fetched only on expand (lazy) | S2/L4 + E2E | SPEC §4 Errors & Jobs |
| TS-DET-08 | hierarchy tab both directions; depth-limit affordance at limit+1 | S2/L4 | SPEC §4 Hierarchy |
| TS-DET-09 | timeline duration bars + call-activity sub-lanes; failing activity annotated with live job state; history level < `activity` → tab greyed-with-reason | S2/L4 | SPEC §4, R-NFR-05 |
| TS-DET-10 | tab-aware deep links (`?tab=…` + job anchor) round-trip into a fresh session | E2E | SPEC §4 Stage 2 |
| TS-DET-11 | copy-for-ticket: composite ID, definition+version, status, exception first line, failure time (UTC ISO-8601), deep link, latest note, actions-taken summary | E2E | R-AUD-06, R-UXQ-03 |
| TS-DET-12 | telemetry URL template renders "open logs" links with placeholders substituted; absent template → no link | S1/L3 + E2E | SPEC §4 Stage 2 |
| TS-DET-13 | raw-JSON download per tab (support-bundle kernel) | S2/L4 | R-L3-03 |
| TS-DET-14 | "explain this status": per-leg raw request/response, plan choice, per-flag provenance, labeled re-derived | S2/L4 | R-L3-01 |
| TS-DET-15 | date variable edit: dual readout (wall-clock in user TZ + exact UTC ISO-8601 to be sent); stored instant correct; offset-less source input rejected; boolean rendered as segmented control (no toggle); null-vs-empty is an explicit choice, spelled out in the verify sentence | S2/L4 + E2E | SPEC §4a, R-UXQ-03/13 |

## 7. Verbs, guard ladder & RBAC — TS-VERB / TS-GUARD / TS-RBAC (risk rank R2)

Verb happy paths run S2/L4 (+E2E for the canonical arc). Guard/RBAC matrices run S1/L3
(`@WebMvcTest` + `spring-security-test`), with E2E spot checks. Dual registration of one
docker engine as `dev` AND `prod` manufactures the prod gates (R-TEST-07).

| ID | Scenario → expected | Refs |
|---|---|---|
| TS-VERB-01 | retry dead-letter job → job back in executable queue, retries reset; delta toast ("Job N moved to executable queue; retries reset to 3") + audit link | SPEC §5/§6 tier 0 |
| TS-VERB-02 | retry-now: success and live-exception both surfaced; 30 s hard timeout → UNKNOWN (FIX-STUB-04) | R-NFR-01 |
| TS-VERB-03 | trigger timer now → fires, takes normal path | SPEC §5 |
| TS-VERB-04 | unstick: subscriptions listed first; message/signal delivered → instance proceeds | SPEC §5 |
| TS-VERB-05 | suspend/activate instance → jobs move to/from suspended lane | SPEC §5 |
| TS-VERB-06 | edit variable, the full §4a arc: form-mode leaf edit on FIX-DATA-01's json changes exactly ONE field (stored-value diff proves the other fields byte-identical); type preserved (number stays number) unless explicitly unlocked — unlock renders its own warning line at verify; source mode gated on parse/type/size before Review enables; verify shows generated sentence + structural path diff (re-serialization noise absent) + exact-request expander; compare-and-set — out-of-band REST change between open and submit → 409 + three-value conflict recovery with attribution, no overwrite-anyway control exists | R-SEM-09, R-UXQ-13 |
| TS-VERB-07 | complete task with data: outputs overridden; skipped-task warning copy rendered verbatim | SPEC §5, R-SAFE-04 |
| TS-VERB-08 *(v1.1)* | change-state: token moved off failed node → proceeds; MI-body source refused with reason; parallel-join warning (FIX-PROC-10); suspended target → offer activate-first; preview labeled *BFF simulation* + exact REST body | SPEC §5 tier 2, R-GOV-07 |
| TS-VERB-09 *(v1.1)* | rerun-from-activity: variable edits applied, then move — one guided composite, both halves audited | SPEC §5, R-GOV-07 |
| TS-VERB-10 *(v1.1)* | restart-as-new: explicit pin-original-version vs latest fork; historic variables copied; new instance ID stated | SPEC §5, R-GOV-07 |
| TS-VERB-11 | suspend definition: new starts rejected; optional running-instance suspension honored | SPEC §5 tier 3 |
| TS-VERB-12 | terminate/delete: cascade children enumerated **server-fresh** in the confirm; children actually gone after | SPEC §5/§6 tier 3 |
| TS-VERB-13 | delete DLQ job: ADMIN-only; orphan warning; execution verifiably orphaned; change-state rescue documented in the modal | SPEC §5 tier 3 |
| TS-VERB-14 | every verb: reversibility badge (`REVERSIBLE`/`RECOVERABLE`/`IRREVERSIBLE`) + plain-language secondary label rendered verbatim from §5.0 | R-SAFE-02/04 |
| TS-GUARD-01 | tier 0 on dev: zero modal; on prod, side-effect verbs (trigger-timer, retry-now) get two-step inline confirm; queue-state verbs stay single-click | R-SAFE-03 |
| TS-GUARD-02 | tier 1: diff confirm; reason optional dev / required prod; reason <10 trimmed chars rejected inline | SPEC §6, R-NFR-06 |
| TS-GUARD-03 | tier 2: required reason + plan-as-sentence + raw REST body preview | SPEC §6 |
| TS-GUARD-04 | tier 3: server-fresh restatement warns on drift (state mutated out-of-band after grid snapshot); typed token = the business key — generic "yes"/"DELETE" rejected; Enter never submits; cancel-focused; env color band | SPEC §6, R-TEST-07 |
| TS-GUARD-05 | tier 4: scope re-resolved at submit with drift shown; prod type-the-count; **unscoped destructive bulk refused outright** | SPEC §6/§7 |
| TS-RBAC-01 | the 100% matrix, generated: every mutating endpoint × {VIEWER, RESPONDER, OPERATOR, ADMIN} × in/out-of-scope (engine, tenant) × read-only engine mode → expected 403 + greyed-with-reason tooltip. CI fails on an endpoint without matrix rows | R-TEST-01, R-GOV-04, SPEC §2 |
| TS-RBAC-02 | RESPONDER: tier-0 + unstick + notes allowed; variable writes and token moves denied with the verb-grant reason | R-SAFE-01 |
| TS-RBAC-03 | protected instance: all verbs below floor disabled-with-reason; badge on row/header/confirm; bulk auto-excludes as `skipped (protected)` | R-SAFE-05 |
| TS-RBAC-04 | break-glass login: ADMIN-global, distinguished audit flag, page banner, 4 h session cap | R-SAFE-06 |
| TS-RBAC-05 | v1 audit schema carries `approved_by` + proposal-state hooks (columns exist, exercised by inserts) | R-SAFE-08 |

## 8. Bulk operations — TS-BULK (risk rank R3)

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-BULK-01 | one run produces EVERY outcome class: `ok / failed / skipped / skipped (protected) / unknown / not_run` (mixed S2 seed + FIX-STUB-04) | S2/L4 + S1 | R-TEST-01 |
| TS-BULK-02 | `write-ms` timeout mid-mutation → UNKNOWN; **never auto-retried**; listed for manual verification | S1 (FIX-STUB-04) | SPEC §7, R-NFR-07 |
| TS-BULK-03 | engine 404/409 on target → `skipped (already resolved)` enriched "handled by <user> at <ts>" from audit | S2/L4 | R-SEM-09 |
| TS-BULK-04 | cancel: dispatching stops; undispatched items `not_run`, never burned as failures | S1 (latch) | SPEC §7 |
| TS-BULK-05 | BFF restart mid-bulk → reconciliation sweep: RUNNING→INTERRUPTED, in-flight item→unknown, never auto-resumed; drawer offers continue-as-new scoped to `not_run`+`failed` | S2 + restart | R-SEM-10 |
| TS-BULK-06 | circuit opens mid-bulk → dispatch to that engine PAUSES (items stay pending/not_run); fast-fail on a dispatched item = `failed`; aggregate readout "N of M dispatched (…)" | S1/L2 + L3 | R-SEM-11 |
| TS-BULK-07 | bulk over a partial result set blocked until explicit acknowledgment ("billing-prod excluded — proceed anyway?") | E2E | SPEC §7 |
| TS-BULK-08 | caps enforced: 200 grid / 5,000 query bulk; per-engine concurrency 4 + 250 ms stagger observable in dispatch order/timing | S1 (latch) | R-NFR-01 |
| TS-BULK-09 | select-all-matching-filter: plan re-executed at submit (never the stale grid); resolved ID list written to audit BEFORE acting; drift shown | S2/L4 | SPEC §7 |
| TS-BULK-10 | one audit row per item + one for the envelope; report survives browser refresh; failed items re-selectable | S2/L4 + E2E | SPEC §7/§9 |
| TS-BULK-11 | SSE progress (v1.x): id-only events, client resyncs on reconnect, 15 s heartbeat, scope-filtered by (role, engine, tenant); P3 soak gates it | S2 (P3) | R-SEM-14, R-TEST-05 |

## 9. Audit, notes & handover — TS-AUD

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-AUD-01 | **fail-closed**: Postgres stopped (Testcontainers) → tier ≥1 mutation NOT sent (stub verifies zero engine calls); error names Postgres | S1/L3 | R-AUD-01 |
| TS-AUD-02 | audit row written on engine success AND failure; full R-AUD-02 schema incl. old values for variable edits, `correlation_id`, response snippet ≤32 KiB truncated+flagged | S2/L4 | R-AUD-02 |
| TS-AUD-03 | secret non-leakage: `password-ref` values appear in no envelope, audit row, or log line (hostile scan over captured output) | S1/L3 | R-AUD-03, TEST-STRATEGY §5 |
| TS-AUD-04 | `audit-payload: full/redacted/metadata-only` honored per engine; denylisted names → `«redacted»`; payload bodies role-gated OPERATOR+ | S1/L3 | R-AUD-03 |
| TS-AUD-05 | DB role cannot UPDATE/DELETE audit rows; tamper-evidence hash chain verifies over a seeded sequence | S1/L3 (Testcontainers) | R-AUD-03 |
| TS-AUD-06 | notes: author+timestamp, "has notes" grid marker; per-instance Audit tab shows the engine-side attribution caveat text | S2/L4 + E2E | SPEC §9, R-AUD-09 |
| TS-AUD-07 | shift report: "my activity, this shift" filter; UNKNOWNs grouped first as NEEDS VERIFICATION; copyable | E2E | R-AUD-05 |
| TS-AUD-08 | streaming CSV export with formula-escaping (FIX-DATA-03 content exported harmlessly) | S1/L3 | R-AUD-08, R-OPS-08 |

## 10. Capability matrix — TS-CAP

| ID | Scenario → expected | Stage/Layer | Refs |
|---|---|---|---|
| TS-CAP-01 | per capability flag × profile: probe detects the expected value (FIX-CORPUS-CAP); supported → verb succeeds E2E; unsupported → BFF rejects with the capability reason AND the UI greys with the matching tooltip. "No orphan flags" — CI fails on a flag without matrix rows. PR CI: `6x` column; nightly: full cross | S2/L4 | TEST-STRATEGY §8, principle 6 |
| TS-CAP-02 | `legacy` profile: change-state rejected pre-flight with "needs Flowable ≥ 6.4 …"; never a raw engine error | S2/L4 | SPEC §6 |
| TS-CAP-03 | `7x` profile: error-JSON shape (Spring Boot 3 problem details) parsed; exception-signature normalizer never silently degrades to unparseable groups | S2/L4 + S1 replay | SPEC §10 |

## 11. End-to-end incident loop — TS-E2E (risk rank R4)

| ID | Scenario → expected | Refs |
|---|---|---|
| TS-E2E-01 | **The canonical arc** (PR smoke, ≤10 min): triage landing shows the seeded error group → click-through to pre-filtered search → open the FAILED instance → why-stuck strip shows `ArithmeticException` → edit `divisor` 0→1 (reason recorded) → retry dead-letter job → status flips to COMPLETED → audit tab shows both actions → copy-for-ticket. axe accessibility checks hard-fail throughout | R-TEST-01 R4, R-UXQ-01, M4 done-when |
| TS-E2E-02 | kill an engine mid-session → health strip badges, search banners, greyed actions — never a blank or a spinner-forever | principle 1, R-UXQ-04 |
| TS-E2E-03 | zero-state catalog: no-engines, all-down, true-zero, zero-under-partial, zero-failures — each rendered per spec | R-UXQ-04 |
| TS-E2E-04 | keyboard + screen-reader pass: grid navigation, modal focus rules (trap, cancel-focused, Esc), diagram textual twin | R-UXQ-02 |

Performance scenarios P1–P4 stay in TEST-STRATEGY §7 (P1/P2/P3 = S1 stubs; P4 + the
R-NFR-02 latency assertions = S2 against FIX-REF-01).

## 12. Production validation — TS-PROD (S3, read-only — normative rules in TEST-STRATEGY §10)

Prod engines are **observation targets, never data generators**: registered
`mode: read-only` under a dedicated read-only credential; no seeding, no mutation, no load
generation; scan caps enforced. S3 findings file as defects (a status mismatch is Sev1 by
taxonomy) — S3 is never a CI gate.

| ID | Scenario → expected | Refs |
|---|---|---|
| TS-PROD-01 | prod engine onboarded read-only: every mutating endpoint rejects; UI greys all verbs "engine registered read-only"; signed onboarding checklist on file | R-GOV-03/04 |
| TS-PROD-02 | **shadow status validation**: sample N composite IDs across statuses; compare inspector flags against direct engine cURL evidence via "explain this status"; zero mismatches tolerated | R-L3-01, R-TEST-03 |
| TS-PROD-03 | signature normalizer over the real prod DLQ: unparseable rate <2%; novel shapes → sanitized (secret/PII-scrubbed, approved) capture proposed to the golden corpus | R-SEM-03, R-AUD-03 |
| TS-PROD-04 | operating envelope check: instance/DLQ volumes vs R-NFR-05; observed search/landing latency vs R-NFR-02 (recorded, observe-only) | R-NFR-02/05 |
| TS-PROD-05 | capability probe result matches the known engine version; advisories rendered if configured | TEST-STRATEGY §8, R-L3-06 |
| TS-PROD-06 | clock-skew measurement: per-engine offset; >30 s → "time filters unreliable" badge | R-OPS-11 |
| TS-PROD-07 | pilot exit KPIs computed from BFF data only (audit activity share, median time-to-first-fix) | R-GOV-01 |

## 13. Register coverage index (MUST-v1 families → scenarios)

| Register family | Scenarios |
|---|---|
| R-TEST-01 floors | TS-STAT-14 (R1) · TS-RBAC-01 (R2) · TS-BULK-01 (R3) · TS-E2E-01 (R4) |
| R-TEST-02 gates | TS-STAT-15 (M2a); every milestone done-when demo has a TS row above |
| R-SEM-02/03/04/08/09/10/11/12 | TS-STAT-04/05 · TS-TRI-04 · TS-OMNI-01/02 · TS-OMNI-03 · TS-VERB-06, TS-BULK-03 · TS-BULK-05 · TS-BULK-06 · TS-TRI-05/08 |
| R-SAFE-01…06 | TS-RBAC-02 · TS-VERB-14 · TS-GUARD-01 · TS-VERB-14 · TS-RBAC-03 · TS-RBAC-04 |
| R-AUD-01…06 | TS-AUD-01…06 |
| R-NFR-01…07 | TS-AGG-07, TS-DET-05, TS-BULK-08, TS-VERB-02 · TS-TRI-06 (+FIX-REF-01) · TS-TRI-06/07 · TS-TRI-02 · TS-DET-09 · TS-GUARD-02 · TS-BULK-02 |
| R-GOV-04 read-only mode | TS-RBAC-01 · TS-PROD-01 |
| R-UXQ-01…06/11/13 | TS-E2E-01/03/04 · TS-DET-01/11 · TS-STAT-07 · TS-DET-04/15, TS-VERB-06 |
| R-L3-01/02/03 | TS-STAT-13, TS-DET-14, TS-PROD-02 · (parity appendix: CI drift gate) · TS-DET-13 |
| R-OPS-08 | TS-AUD-08, FIX-STUB-05, FIX-DATA-03 |
