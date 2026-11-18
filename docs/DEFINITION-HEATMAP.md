# DEFINITION HEAT MAP + OUTCOME FUNNEL — design note and wire-shape spike (#678)

**Status:** design proposal for owner review (epic #671, study recommendation R8). Nothing is
built. Implementation issues are cut from this note once it is accepted (§9); this note does
not file them.

**What it is for.** The definition page (`/definitions/{engineId}/{definitionId}`) shows a
static model. A model owner opening it wants to know where the process hurts: per node, the
average duration and how often it is reached (a heat badge on the model), how instances end
(an outcome funnel over the named end events), and a p50/p95 table. flap already ships this
view (`admin/process-stats.html` + `ActivityStatsService`); §7 says what is taken over.

**Method.** Every number below was measured on 2026-11-17 against real engines:

- **Shared dev engines** (6.8 `:8081`/`:8082`, 7.1 `:8083`): read-only counts. Nothing was
  started, changed or deleted on them.
- **Throwaway compose project `pi-heatmap-678`** (ports 8770–8779, torn down after the run):
  6.3.1 ×2, 6.8.0 ×5, 7.1.0 ×2 on in-container H2, plus one 6.8.0 on **PostgreSQL 16** for
  query plans. History level set per container with
  `SPRING_APPLICATION_JSON={"flowable":{"history-level":"…"}}`.
- **Seeding over REST only** (`POST /repository/deployments`, `POST /runtime/process-instances`).
  No `ACT_*` row was written by hand. Seed model `heatSeed`: start → `check` → exclusive gateway
  → three routes (60 % `a1`→`a2`→*Approved*, 25 % `b1`→*Rejected*, 15 % parallel split
  `c1`‖`c2`→join→*Escalated*). Manual tasks only, so the instances finish synchronously.
  Definitions with 20, 1 000 and 10 000 finished instances; the PostgreSQL engine also holds
  60 000 instances of a second definition as noise (809 400 `ACT_HI_ACTINST` rows, 413 MB).
- Timings are the median of three runs from the same host (no network hop), idle engines.
  The H2 runs on the three versions ran at the same time on one box; read them as relative
  costs. The PostgreSQL run was alone.

---

## 1. Wire shape — `historic-activity-instances` across 6.3 / 6.8 / 7.1

### 1.1 `sequenceFlow` rows

| Engine | Rows per instance (20 seeded, same model) | `activityType=sequenceFlow` rows |
|---|---|---|
| 6.3.1 | 124 / 20 = **6.2** | **0** |
| 6.8.0 | 228 / 20 = 11.4 | 104 (46 %) |
| 7.1.0 | 228 / 20 = 11.4 | 104 (46 %) |

One *Escalated* instance, rows in engine order:

```
6.3.1  9 rows: exclusiveGateway:route, manualTask:check, parallelGateway:split, startEvent:start,
               parallelGateway:join, manualTask:c1, manualTask:c2, parallelGateway:join, endEvent:endEscalated
6.8.0 17 rows: the same 9 + sequenceFlow:f0 f1 fc fs1 fs2 fj1 fj2 fe
7.1.0 17 rows: the same as 6.8.0
```

The shared dev engines agree: every 6.8/7.1 definition with instances has flow rows (e.g.
`acmeOrderOrchestrator` 44 of 94 rows, `demoWideChild` 795 of 2 380).

Consequences:

- **6.3 has no flow history.** A per-flow heat or "share of instances that took this flow"
  cannot be computed on 6.3. Node heat and the funnel work on all three versions.
- **Flow rows nearly double the rows read on 6.8/7.x.** The activity query has no
  "type not equal" filter, so a sample cannot leave them out on the engine side.
- **A parallel join records one row per arriving branch** (`join` twice above, on every
  version). Per-node `executions` and `instances` (distinct instances that reached the node)
  are different numbers. The hit rate must use distinct instances (flap does this too).
- **Ordering:** `sort=startTime` ties at millisecond resolution. `startEvent` comes after
  `check` above. Never rely on row order inside one instance.
- Row keys are identical on all three versions (`activityId, activityName, activityType,
  assignee, calledProcessInstanceId, durationInMillis, endTime, executionId, id,
  processDefinitionId, processInstanceId, startTime, taskId, tenantId` + URLs) and match
  `ActivityInstanceRecord` (#714).

**Finding beyond this feature, for the owner: #677 on 6.3.** `executionOverlay.ts` marks an
ended node as *completed* only when one of its outgoing flows has a `sequenceFlow` row. 6.3
never records one, so on a 6.3 instance every ended task, gateway and start event that has an
outgoing flow lands in `armedNotTaken`, and no route is drawn. This was derived by reading the
code against the 6.3 rows above. It was not reproduced in the UI. It needs its own issue: a
capability or "no flow rows at all" fallback.

### 1.1a Event-based gateway: armed catch events look taken

Recorded on fresh 6.3.1, 6.8.0 and 7.1.0 engines (`wire/{6.3,6.8,7.1}/historic-activity-instances-evgw.json`,
model `backend/src/test/resources/heat/heatEvgw.bpmn20.xml`: `gw` arms `catchA` and `catchB`,
only `catchA` fired). The catch event that did NOT fire (`catchB`) still has a **finished row
with a duration** (95–121 ms, the wait until `catchA` fired) on all three versions. Nothing on
the row says "not taken" (no `deleteReason` over REST). Counted naively it gets a 100 % hit rate,
and its wait counts in the total every node's share is measured against.

**Rule (owner decision A, 2026-11-18).** The aggregator gets the definition version's BPMN XML
and reads (`BpmnStructure.eventGatewayCatches()`, the surgery parser, XXE-hardened) every
intermediate catch event or receive task with an incoming flow from an `eventBasedGateway`, in
every process (pool) and subprocess, with its outgoing flows. Per instance, a row of such a node is:

- **with route history** (the instance has at least one `sequenceFlow` row): *taken* iff one of
  its outgoing flows has a row;
- **without** (6.3): *taken* iff one of its direct successors has a row that starts at or after
  the event row's end. This is inferred, not recorded: coverage is `NO_FLOW_HISTORY` and the
  sampling note adds *"event-gateway branches inferred from timing, this engine records no
  routes"* whenever the inference decided a row.

Each successor (or flow) row takes at most one event row, across ALL catch events (a merge
gateway after two branches is one row, so one taken event), the latest-ending one that ended at
or before it started and that no row of the same successor took already; an event visit is taken
once whichever of its outgoing flows took it (an event that forks into two flows is one visit).
With route history the flow row is the record and timing only attributes it to a visit. So a
loop that armed an event twice and took it once is one taken visit and one armed visit, and the
duration counted is the taken visit's. An unfinished event row is armed.

**Known misreading of the 6.3 inference** (marked: the note says *inferred*). A direct successor
also reached by another path makes an armed event look taken. Smallest case: `gw` arms `A` → `x`
and `B` → `y` → `x`; `B` fires, `y` runs, then `x` starts after `A`'s end, so `A` counts as taken
too (`HeatAggregatorEventGatewayEdgeTest`). Route history (6.4+) has no such case.

**A gateway-armed catch event without an outgoing flow** (implicit end) leaves nothing to tell a
fired visit from an armed one: the whole definition counts as without BPMN and says
`structure: UNAVAILABLE`.

An armed visit is left out of `executions`, `instances`/`hitPct`, avg/p50/p95/max/`totalMs` and
the `sharePct` denominator, and counted in the node's `armedNotTaken` (visits, same unit as
`executions`), so H3 can say "armed in N, taken in M". A node armed but never taken stays in
`nodes` with zero figures. No XML, or XML that does not parse: the rows count as before and the
response says `structure: UNAVAILABLE` (never a silent fallback).

### 1.2 Filters the REST layer silently drops

These were checked with impossible or narrowing values on 6.3.1 / 6.8.0 / 7.1.0:

| Parameter on the activity query | 6.3.1 | 6.8.0 | 7.1.0 |
|---|---|---|---|
| `processInstanceIds` (POST `/query/historic-activity-instances`, 2 ids) | ignored: 124 of 124 | ignored: 228 of 228 | ignored: 228 of 228 |
| `startedAfter=2100-01-01` (GET) | ignored | ignored | ignored |
| `size=100000` | all rows, no server cap | same | same |
| `activityId`, `activityType`, `finished`, `processInstanceId`, `sort=startTime` | honoured | honoured | honoured |
| `processInstanceIds` on POST `/query/historic-process-instances` | honoured (2) | honoured | honoured |
| `sort=endTime&order=desc` on historic process instances | honoured | honoured | honoured |

flap bounds its activity read with `startedAfter(oldestStart)`. **Over REST that window does
not exist.** If a client sent it, the engine would return the whole history of the definition
and the client would think it was bounded. The activity rows of a sample can't be fetched by
id list either. The only options are one query per instance, or one query across the whole
definition (§3).

---

## 2. Capability gating — history level and engine version

Each engine was seeded with the same 20 instances. "Default" is the image default (`audit`).

| Engine, level | historic process instances | activity rows | flow rows | historic variables | historic details |
|---|---|---|---|---|---|
| 6.3.1 `none` | 0 | 0 | 0 | 0 | 0 |
| 6.3.1 default | 20 | 124 | **0** | 20 | 20 |
| 6.8 `none` | 0 | 0 | 0 | 0 | 0 |
| 6.8 `instance` | **20** | **0** | 0 | 0 | 0 |
| 6.8 `activity` | 20 | 228 | 104 | 20 | 0 |
| 6.8 default (`audit`) | 20 | 228 | 104 | 20 | 20 |
| 6.8 `full` | 20 | 228 | 104 | 20 | 20 |
| 7.1 `none` | 0 | 0 | 0 | 0 | 0 |
| 7.1 default | 20 | 228 | 104 | 20 | 20 |

A second definition was deployed on the `audit` engines with
`<flowable:historyLevel>none</flowable:historyLevel>` on its `<process>`. On 6.3.1, 6.8 and 7.1
it recorded **0** historic process instances and **0** activity rows, while the engine's other
definitions kept full history.

Consequences:

1. **`activity` is enough.** `activity`, `audit` and `full` return the same activity rows.
2. **The engine's level does not tell you a definition's level.** A process can override it,
   and all three versions honour the override. Flowable REST has no endpoint that exposes either
   level (`/management/engine` gives name and version only). The gate therefore has to be
   **observed per definition, from the data**. It must not come from engine config or a version
   check:
   - **finished instances > 0, and EVERY analysed sampled instance has 0 activity rows** →
     `NO_ACTIVITY_HISTORY` (owner decision B, 2026-11-18). An empty instance among non-empty
     ones is dropped and counted (`dropReasons.noActivityHistory`, §3.4), not a reason to blank
     the map. A row of any type counts, `sequenceFlow` included (an instance with flow rows only
     is not empty; Flowable never writes one without a start-event row). Under `instance` or a per-process override, every finished instance
     at `activity` or above has at least a start-event row. Say *"this definition records no
     activity history (history level below `activity`)"*, never zeros.
   - **0 historic instances** → `NO_HISTORY`. Over REST this can't be told apart from "never
     ran to completion". There is one exception: a running instance in
     `/runtime/process-instances` with no historic row proves level `none`, because at
     `instance` or above the historic row is written when the instance starts (async history
     aside). Wording: *"no finished instance in history, either none finished or history is
     off for this definition"*.
   - **activity rows present, flow rows 0 across the whole sample, on an engine without the
     capability** → `NO_FLOW_HISTORY`. Detect this per call: no `sequenceFlow` row in a
     non-empty sample whose model has flows. A version string is not proof. Node heat and the
     funnel render; flow heat is absent and labelled, never drawn as "0 ×".
3. **Version floor: none.** The node heat, the p50/p95 table and the funnel work on 6.3.1. Only
   the flow layer needs flow rows, so 6.8+ in practice, detected as above.

---

## 3. Sample bound, engine load and truncation honesty

### 3.1 What a definition-scoped query costs on PostgreSQL

Plans captured with `EXPLAIN (ANALYZE, BUFFERS)` on the PostgreSQL engine (809 400 activity
rows; the definition under test has 10 000 instances and 114 000 rows, all older than the
60 000 noise instances). The SQL is what the engine sent, read from the PostgreSQL statement
log.

| Statement (as the engine issues it) | Plan | Buffers | Time |
|---|---|---|---|
| `count(*) … ACT_HI_ACTINST WHERE PROC_DEF_ID_=?` | **parallel seq scan of the whole table** | 27 014 | 92 ms |
| `count(*) … WHERE PROC_DEF_ID_=? AND ACT_ID_=?` | parallel seq scan of the whole table | 27 014 | 96 ms |
| `SELECT … WHERE PROC_DEF_ID_=? ORDER BY START_TIME_ DESC LIMIT 5000` (definition older than the noise) | backward walk of `ACT_IDX_HI_ACT_INST_START`, filtering | 102 221 | 194 ms |
| same, `OFFSET 100000` | same walk | 115 899 | 254 ms |
| same, for the hot, newest definition | same walk, matches at once | 791 | — |
| `SELECT … WHERE PROC_INST_ID_=?` | `ACT_IDX_HI_ACT_INST_PROCINST` | **7** | <1 ms |
| `count(*) … ACT_HI_PROCINST WHERE PROC_DEF_ID_=? AND END_TIME_ IS NOT NULL` | seq scan of `ACT_HI_PROCINST` | 2 036 | 19 ms |
| `SELECT … ACT_HI_PROCINST … ORDER BY END_TIME_ DESC LIMIT 600` | backward walk of `ACT_IDX_HI_PRO_INST_END` | 60 302 | 35 ms |

Why:

- **No `ACT_HI_*` table has an index on `PROC_DEF_ID_`.** This is the same on 6.3.1, 6.8.0
  and 7.1.0. The checked DDL is `flowable.postgres.create.history.sql`: `ACT_HI_ACTINST` has
  `START_TIME_`, `END_TIME_`, `(PROC_INST_ID_, ACT_ID_)` and `(EXECUTION_ID_, ACT_ID_)`;
  `ACT_HI_PROCINST` has `END_TIME_`, `BUSINESS_KEY_` and `SUPER_PROCESS_INSTANCE_ID_`.
- **Every REST list call also runs a `count(*)`**, which is how it fills `total`. The log shows
  the pair for every page. So **every page of a definition-scoped activity query costs one full
  scan of `ACT_HI_ACTINST`**. That cost grows with the size of the engine's whole activity
  history, not with the definition or the page. The measured rate is ≈ 11 ms per 100 k rows,
  all from shared buffers. Extrapolated linearly, 10 M rows is ≈ 1 s per page and 100 M rows
  is ≈ 10 s per page, before any disk reads.
- The per-instance query is index-backed. Its cost does not depend on table size.

### 3.2 Request count and wall time per strategy

Three ways to gather the sample, measured on the 1 k and 10 k definitions. "par" is
concurrency: 2 is a thin lane, 8 the interactive bulkhead.

- **A. Definition scan (flap's shape).** One finished-instance page of N, then activity pages
  `sort=startTime desc`, page size P. Stop when the oldest row read is older than the oldest
  sampled start.
- **B. Per instance.** One finished-instance page of N, then one activity query per sampled
  instance, `processInstanceId=…`.
- **C. Per node.** Per activity id: one count and one latest-K sample (13 nodes = 26 requests).

PostgreSQL 6.8 engine, 10 k definition (114 000 rows), table 809 k rows:

| Strategy | N / K | Requests | Rows read | par 2 (A: serial) | par 8 |
|---|---|---|---|---|---|
| A, P=1000 | 200 | 5 | 3 000 | 1 264 ms | — |
| A, P=5000 | 200 | 3 | 5 000 | 605 ms | — |
| A, P=5000 | 600 | 4 | 10 000 | 1 156 ms | — |
| A, P=1000 | 2 000 | 25 | 23 000 | 8 545 ms | — |
| A, P=5000 | 2 000 | 7 | 25 000 | 2 636 ms | — |
| **B** | **200** | **201** | 2 290 | **549 ms** | 263 ms |
| **B** | **600** | 601 | 6 844 | **1 618 ms** | 583 ms |
| B | 2 000 | 2 001 | 22 812 | 5 236 ms | 1 944 ms |
| C | K=200 | 26 | 2 600 | 3 377 ms | 1 073 ms |
| C | K=1000 | 26 | 13 000 | 3 003 ms | 992 ms |

1 k definition on the same engine: B N=200 is 529 / 234 ms; A P=5000 N=600 is 942 ms.

H2, 10 k definition, par 2 (A is serial):

| Engine | A P=5000 N=600 | B N=200 | B N=600 | B N=2000 | C K=200 |
|---|---|---|---|---|---|
| 6.3.1 (62 000 rows) | 527 ms | 271 ms | 756 ms | 2 484 ms | **11 037 ms** |
| 6.8.0 (114 000 rows) | 1 038 ms | 253 ms | 721 ms | 3 349 ms | 1 115 ms |
| 7.1.0 (114 000 rows) | 1 162 ms | 281 ms | 1 014 ms | 2 990 ms | 1 497 ms |

On idle engines a single definition-scoped activity page cost 316–704 ms at page sizes
200–5000 (PG and H2), and a count-only query cost 5–286 ms. On 6.3.1 a single definition-scoped
`activityId` page was 518 ms against 72 ms on 6.8. That is why C collapses on 6.3.

Reading the numbers:

- **A looks cheap in requests and is the most harmful shape.** It moves the most rows,
  `N=2000` takes 8.5 s at P=1000, and every page is a full scan of the table (§3.1). It cannot
  be windowed over REST (§1.2). On a production table of tens of millions of rows each page
  costs seconds of engine database time.
- **C is out.** It does 26 full-table scans per call on PostgreSQL (each count and each sample
  page carries a count), takes 11 s on 6.3, and samples each node from a different set of
  instances, so hit rate and share stop meaning anything.
- **B is the recommended shape.** Its cost is linear in N and does not depend on table size.
  Every query uses `(PROC_INST_ID_, ACT_ID_)`, and each instance is read completely or not at
  all, so there is no half-read instance and no cutoff trick. The cost is in request count.
  The thin lane absorbs that: at par 2, N=200 takes 0.55 s and N=600 1.6 s on PostgreSQL.

### 3.3 Proposed bounds

| Bound | Value | Grounds |
|---|---|---|
| Default sample N | **200** finished instances, newest end first | B: 0.25–0.55 s on every engine measured |
| Server clamp on `?n=` | **1 000** | B ≈ 2.6 s at par 2 (interpolated between 600 and 2 000); flap clamps at 2 000 |
| Rows per instance | one page of the engine's `max-page-size` (200 today). If `total` > fetched, **drop the instance and count it** | real defs: 3–62 rows per instance (`demoWideParent` 62) |
| p95 shown only when | the node has ≥ 20 timed executions in the sample; otherwise `null` and "too few" | nearest rank: p95 of fewer than 20 values is the maximum |
| Minimum duration | sub-1 ms nodes get no badge (gateways, events, flow rows) | same rule as the #677 duration badge |

**Sample selection:** `GET /history/historic-process-instances?processDefinitionId=…&finished=true&sort=endTime&order=desc&size=N`.
That one call also returns `total`, which is the `finishedTotal` the honesty line needs. Its
cost is one seq scan of `ACT_HI_PROCINST` (19 ms at 71 k rows), paid once per call. Rows that
tie on `endTime` are chosen in an undefined order. Two runs over the same history picked
samples that differed by 6 rows out of 2 286. That is acceptable for a sample; say "newest N",
never "exactly these N".

### 3.4 Truncation honesty (ARCHITECTURE §2.3)

The figure is always a **sample**. It is never a population statistic, so the badge rule
applies every time. Three kinds of incompleteness are reported separately, never merged:

1. **Sampled, not all:** `sampledInstances < finishedTotal`. A permanent caption on the tab:
   *"Newest 200 of 10 000 finished instances (ended 2026-11-10 → 2026-11-17)."*
2. **Dropped instances:** an instance had more rows than one page (`tooManyRows`), its read
   failed (`readFailed`: bulkhead full, timeout, open breaker), or it has no activity row while
   others in the sample do (`noActivityHistory`, decision B). It is excluded, never counted half-read, and reported as
   `droppedInstances` with a reason count. The badge says *"Partial sample: 12 instances
   dropped"*. Hit rate and share are computed over `sampledInstances` minus the dropped ones,
   and the caption says so (*"…; 12 dropped, figures over the remaining 188."*).
3. **Coverage below what the view draws:** `NO_FLOW_HISTORY` (no flow layer), plus
   `NO_ACTIVITY_HISTORY` and `NO_HISTORY` (no figures at all, an explanation in their place).

Never render a figure computed after a mid-run engine error without the partial badge. If
every per-instance read failed, the result is an error envelope, not an empty heat map.

---

## 4. Engine-load lane and caching

**Lane.** Add a new `CallPriority.AGGREGATE` (`"{id}:aggregate"`, its own breaker based on
`engine`, bulkhead `max-concurrent-calls: 2`, `max-wait-duration: 5s`). This copies the
`DEEP_PAGE` reasoning (ARCHITECTURE §2.2 bulkheads, §2.4 deep paging, R-NFR-08): the action is user-initiated and heavy
(201–1 001 requests), so it must never take the 8 interactive permits, and an aggregate storm
must trip its own breaker rather than the interactive one. Reusing `BACKGROUND` (`sampler`)
would let a heat-map click starve the trend sampler. Reusing `DEEP_PAGE` would couple two
unrelated degradations. Measured cost of the thin lane: par 2 against par 8 is 2.1–2.8× the
wall time (B, N=200: 549 against 263 ms on PostgreSQL). A bulkhead wait over 5 s drops that
instance and counts it (§3.4).

**Cache.** Caffeine, keyed `(engineId, definitionId, n)`, **TTL 10 minutes**, single-flight so
that N concurrent viewers trigger one gather. Grounds: the sample covers *finished* instances
only, so between two reads it changes only by the instances that finished meanwhile. The
triage cache's 20 s guards against a thundering herd on live state, which this is not.
Refresh bypasses the cache and is rate-limited per key, the same mechanism as the triage
Refresh (`inspector.triage.refresh-min-interval-s`, 10 s there). Here once a minute, because
one gather is 200+ engine requests. The response carries `asOf` and `cached`, and the UI shows the age ("as of 4 min
ago").

**Not on a schedule.** The view is gathered when someone opens the tab. There is no
background precompute, and no persistence in the BFF's Postgres. A snapshot table would be a
second metrics stack; flap rejected one for the same reason. Revisit only if usage shows the
same definitions being re-gathered all day.

---

## 5. Placement in the UI

**The definition detail page, as a new `Heat` tab** next to `Diagram · XML · Subscriptions ·
Jobs · Compare` (`DefinitionDetailPage.tsx`). Grounds:

- The aggregate is per definition version (`definitionId`), and so is that page and its
  diagram. Node ids can differ between versions. Mixing versions (flap aggregates by key) would
  paint version-2 timings on version-3 nodes. A key-level roll-up is an owner decision (§10).
- The tab is opened deliberately and `enabled` only while selected, the same pattern as
  `subscriptions` and `jobs`. Merely viewing a definition costs the engine nothing. This is the
  do-no-harm reason it is not on the Diagram tab by default.
- The incident view is the wrong home. An incident asks "what is wrong with this instance or
  error class", and the heat map asks "where does this model spend its time". A link from an
  instance ("compare to typical") can come later and is listed in §10.

**What #677 already draws, and where heat goes.** The #677 overlay exists on the **instance**
page and in the change-state picker, not on the definition page, so the two never share a
canvas in this design. The definition page's own markers are the version-diff `+`/`~` glyphs
(Compare tab only). Heat badges are still placed clear of the whole `MARKER_KINDS` vocabulary,
so a later "instance against typical" overlay cannot collide:

| Marker | Position |
|---|---|
| dead-letter `⚠`, blocker `✖`, warning `!` | top −10, right 10 |
| #677 duration badge | top −12, right 10 |
| #677 completed `✓` | top −10, left 8 |
| `×N`, picker `S`/`T`, diff `+` | top-left |
| diff `~` | top −8, right −8 |
| diverge `▲`/`△` | bottom −6, left −6 |
| mapped `↦` (migrate modal only) | bottom −6, right 10 |
| **heat badge (new)** | **bottom 4, right 4, inside the shape**, on tasks, sub-processes and call activities only |

Events and gateways get no heat badge: their durations are ≈ 0 ms, and their labels sit
below the shape, where a badge would cover them. End events are covered by the funnel and
gateways by the flow layer.

**Colour is never the only carrier** (SPEC §10a):

- The badge text carries the figure: `avg 1.2 s · 62 %`.
- The tier is a word and a border style. `dominant` (share ≥ 40 %) is a solid 2 px border with
  the word. `warm` (≥ 10 %) is a solid 1 px border. `cool` is dashed. flap's tier thresholds
  40 / 10 / 1 % are kept. flap's colour-only classes are not.
- Each badge has `role="img"` and an `aria-label`.
- The **table is the keyboard-focusable textual twin**: per node it shows name, executions,
  instances, hit %, avg, p50, p95, max and share, sortable. Selection is synced with the
  canvas, as the Errors & Jobs and Executions tabs do on the instance page
  (`selectedActivityId`, "Show on diagram").
- **Flows** (6.8+): taken share as stroke width in three buckets plus a `62 %` label on hover
  and focus. Not drawn when `NO_FLOW_HISTORY`.
- **Funnel**: horizontal bars with count and percentage as text, one row per end event, plus a
  "no end event recorded" row (terminated or deleted instances end without an end-event row) so
  the rows add up to the sample.

---

## 6. Proposed API

`GET /api/definitions/{engineId}/{definitionId}/heat?n=200[&refresh=true]`

- RBAC: VIEWER on the engine. Read-only; no audit rail. A read is not a mutation.
- Engine paths, all already whitelisted in shape: `history/historic-process-instances`
  (finished, sort endTime) and `history/historic-activity-instances?processInstanceId=`. The
  BPMN XML stays on the existing `/xml` endpoint; the frontend parses the async flags and the
  flow ids from it, as #677 does. The gather (H2) ALSO reads the definition version's BPMN XML
  (`repository/process-definitions/{id}/resourcedata`, already whitelisted) and passes it to
  the aggregator (`Sample.bpmnXml`) for the event-gateway rule (§1.1a); if that read fails it
  passes null and the response says `structure: UNAVAILABLE`.
- `n` is clamped to `[1, 1000]`. A disabled engine resolves through
  `EngineRegistry.resolveOrNotFound()` like the other read surfaces.

```text
DefinitionHeat {
  engineId, definitionId, asOf, cached: boolean,
  coverage: FULL | NO_FLOW_HISTORY | NO_ACTIVITY_HISTORY | NO_HISTORY,
                    // NO_FLOW_HISTORY also means: event-gateway taken/armed split inferred (§1.1a)
  structure: RESOLVED | UNAVAILABLE,                    // BPMN read for the §1.1a rule, or not
  requestedN, sampledInstances, finishedTotal,          // "newest 200 of 10 000"
  droppedInstances, dropReasons: { tooManyRows, readFailed, noActivityHistory },
  oldestEnd, newestEnd,
  partial: boolean,                                     // droppedInstances > 0
  nodes:    [{ activityId, name, type, executions, instances, hitPct, armedNotTaken,
               avgMs, p50Ms, p95Ms?/*null below 20 samples*/, maxMs, totalMs, sharePct, tier }],
  flows:    [{ flowId, instances, takenPct }] | null,   // null when coverage = NO_FLOW_HISTORY
  outcomes: [{ endEventId, name, count, pct }],
  noEndEventCount,
  samplingNote                                          // the one sentence, always rendered
}
```

Errors: the engine is unreachable, the breaker is open, or every per-instance read failed →
`503` ProblemDetail with the engine's reason (`circuit open — engine shedding load`), never an
empty `nodes` array. An unknown definition → `404`.

Aggregation is pure Java, as in flap: nearest-rank percentiles, hit rate over distinct
instances, share of total measured time. `type` contains `endEvent` → funnel. Flow rows are
counted per distinct instance.

---

## 7. flap's process stats — what is taken over

**Taken over:**

- newest-N finished instances as the sample, a server-side clamp on `n`, and a sampling note
  that is always rendered;
- dropping a half-read instance rather than counting it, and saying how many were dropped;
- nearest-rank p50/p95 computed in application code, with no interpolated values;
- `executions` and `instances`/`hitPct` as separate figures (the parallel join in §1.1 is
  why), `sharePct` over total measured time, and the 40 / 10 / 1 % tiers;
- the funnel over end events, matched on `type` containing `endEvent`;
- the async marker on nodes whose durations include job-queue wait, read from the BPMN
  `flowable:async` attribute;
- the history-level guard message: *below `activity` nothing is recorded, so say so, never
  show zeros*.

**Not taken over, and why:**

- **The definition-wide scan windowed by `startedAfter`.** REST ignores `startedAfter`
  (§1.2), and every page is a full table scan (§3.1). Replaced by the per-instance shape B.
- **Reading the history level from engine config.** It is not available over REST, and a
  process can override it (§2). Replaced by coverage observed per definition.
- **Aggregating by key across versions.** Node ids are per version, and the PI page is per
  `definitionId` (§5).
- **The `operatorAccepted` cohort split.** It is a flap domain variable. A generic "split by
  variable X" costs one historic-variable query per instance on REST (no `executionIds`
  batching was measured) and is an owner decision (§10).
- **Colour-only heat classes and server-rendered Thymeleaf.** §5 adds the text and border
  carriers, and the view is a React tab over an openapi DTO.
- **A configuration that may run without a lane.** flap calls an in-process engine. PI must
  go through `GuardedCaller` (§4).

---

## 8. Test strategy (for the implementation issues)

- **Rung 1, pure static, RED first:** the aggregation from fixture rows.
  - The 6.8 parallel join gives `executions` 2 and `instances` 1.
  - 6.3 rows (no flows) give `coverage=NO_FLOW_HISTORY` and `flows=null`.
  - Every analysed instance with 0 rows gives `NO_ACTIVITY_HISTORY`; 0, 1 and N−1 of N empty
    drop the empty ones (`noActivityHistory`), N of N and N = 1 empty give
    `NO_ACTIVITY_HISTORY` (`HeatAggregatorNoHistoryRuleTest`).
  - The recorded `evgw` rows on 6.3/6.8/7.1 with their BPMN: `catchB` has 0 executions, 0 %
    hit, `armedNotTaken` 1, no timing, 0 share, and the shares of the rest add up to 100 %;
    a loop armed twice and taken once is one of each, with the taken visit's duration; 6.3 says
    "inferred" in the note; no or unparseable XML gives `structure: UNAVAILABLE`
    (`HeatAggregatorEventGatewayTest`). The parser finds armed nodes in subprocesses and
    second pools, includes receive tasks, and refuses a DOCTYPE / external entity without
    fetching it (`BpmnEventGatewayStructureTest`).
    Forked, merged, flowless and joined catch events, boundary and event-subprocess starts
    (`HeatAggregatorEventGatewayEdgeTest`).
  - An over-long instance is dropped and counted.
  - Fewer than 20 samples give `p95Ms=null`.
  - Funnel rows plus `noEndEventCount` add up to the sample.
- **Fixtures:** record `historic-activity-instances?processInstanceId=` for one *Escalated*
  instance of the §1.1 model under `backend/src/test/resources/wire/{6.3,6.8,7.1}/`. 6.3 has
  no activity fixture today.
- **Rung 2, MockWebServer:** the client sends `processInstanceId` per instance, never
  `startedAfter` or `processInstanceIds`. A `total` greater than fetched drops the instance.
- **Engine layer (engine-harness):** seed ~50 instances over REST with lowered caps, on 6.8 and
  7.1, and check that coverage and counts match what was seeded. On 6.3 (legacy profile),
  check `NO_FLOW_HISTORY`.
- **Frontend (vitest):** badge text and tier word, no badge on events or gateways, the
  partial badge whenever `partial`, the coverage message instead of zeros, and the table/canvas
  selection sync.

---

## 9. Proposed cut into implementation issues (text only; not filed)

1. **`feat(#671): definition heat — pure aggregation + DTO`**
   - Scope: the `DefinitionHeat` record and a pure `HeatAggregator` covering nodes, flows,
     funnel, coverage, the drop rule and the p95 floor.
   - Write-set: `backend/…/heat/HeatAggregator.java`, `backend/…/dto/DefinitionHeat.java`,
     tests, and the three wire fixtures.
   - Test-first: rung 1, every case in §8.
   - Model: `model:sonnet-5.5`.
2. **`feat(#671): AGGREGATE call lane + heat gather service + endpoint`**
   - Scope: `CallPriority.AGGREGATE` and its resilience4j config; the gather (B, N clamp, drop
     on `total` > page, drop on read failure); the Caffeine cache with 10 min TTL and
     single-flight; Refresh rate limit; `GET …/heat`; and `npm run gen:api`.
   - Write-set: `GuardedCaller.java`, `application.yml` (resilience + `inspector.heat.*`), a new
     `DefinitionHeatService`, `DefinitionController` (or a new controller), `schema.d.ts`,
     ARCH §2.2 (the lane), SPEC §4 and §8, and OPERATIONS (the new config keys).
   - Test-first: MockWebServer for the request shape and the lane name; a cached
     `@SpringBootTest` for RBAC and the clamp.
   - Model: `model:opus-5.5` (do-no-harm surface).
3. **`feat(#671): Heat tab on the definition page`**
   - Scope: the tab, the badges (text, tier word, border), the table with synced selection, the
     funnel, the coverage and partial badges, and the legend rows in `markerKinds.ts`.
   - Write-set: `DefinitionDetailPage.tsx`, a new `catalog/heat/*`, `markerKinds.ts`,
     `styles.css`, and SPEC §10a.
   - Test-first: vitest RED for the cases in §8.
   - Model: `model:sonnet-5.5`.
4. **`test(#671): heat over real engines`**
   - Scope: engine-harness ITs on 6.8 and 7.1 (seeded over REST with lowered caps) and a 6.3
     `NO_FLOW_HISTORY` check.
   - Write-set: `backend/src/test/…/HeatIT.java` and seed BPMN under `docker/processes/`.
   - Test-first: this issue is the test.
   - Model: `model:sonnet-5.5`.
5. **Separate bug, outside #671: `fix(#677): execution overlay on 6.3 marks every node
   armed-not-taken`** (§1.1).
   - Scope: when the timeline has no `sequenceFlow` row at all, either do not classify
     armed-not-taken or derive the flows; show "route unavailable on this engine".
   - Write-set: `executionOverlay.ts` and its test.
   - Test-first: a vitest with 6.3-shaped rows.
   - Model: `model:sonnet-5.5`.

Order: 1 → 2 → 3, with 4 after 2. Issue 5 is independent.

---

## 10. Open decisions for the owner

1. **Default N and clamp:** 200 and 1 000 as proposed, or flap's 600 and 2 000. At 600 the
   cost is 1.6 s on the thin lane on PostgreSQL.
2. **Lane:** a new `AGGREGATE` lane with 2 permits as proposed, or reuse `DEEP_PAGE`.
3. **Cache TTL:** 10 minutes as proposed.
4. **Per version only, or also a key-level roll-up across versions?** A roll-up could show
   only the nodes that exist in every sampled version.
5. **Flow layer on 6.8+:** ship it in issue 3, or nodes and funnel first.
6. **A generic cohort split by a named variable:** it costs N more requests per gather.
   Defer or drop?
7. **Link from the instance page:** a "this run against typical" overlay comparing the #677
   duration with the definition p50. It would reuse this endpoint and the bottom-right
   position.
8. **Should #677's 6.3 behaviour (§1.1) become its own issue now?**

### Decided 2026-11-18 (owner, on #788)

- **A. Armed catch events are resolved from the BPMN.** A catch event an event-based gateway
  armed but that did not fire is left out of hit rate, visit count, durations and the share
  denominator, and reported as `armedNotTaken`; 6.3 infers it from timing and says so; no
  BPMN means the old counting plus `structure: UNAVAILABLE` (§1.1a). H2 passes the BPMN XML.
- **B. `NO_ACTIVITY_HISTORY` only if ALL sampled instances are empty.** Otherwise empty
  instances are dropped and counted (`dropReasons.noActivityHistory`, §2, §3.4).
