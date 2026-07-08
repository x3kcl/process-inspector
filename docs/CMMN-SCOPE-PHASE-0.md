# 🩺 SPEC EXTENSION — CMMN scope visibility (Phase 0)

**Status:** Landed 2026-07-07 (commits `e712e46` spec · `48a4640` backend · `51300c6`
frontend + spec-sync) · **Author:** remote-control design session
**Extends:** SPECIFICATION §3 (status join / CMMN hygiene), ARCHITECTURE §2.3 & §2.5
(capability map) · **Provenance:** live wire-shape spike vs dockerized 6.8 / 7.1 / 6.3.1
(see below) · **Requirement:** **R-SEM-20** (registered in REQUIREMENTS-REGISTER). NOTE:
the backend commit `48a4640` and earlier drafts of this doc mis-cited **R-SEM-08** — that ID
was already permanently assigned to the engineId slug rule; the register never reuses IDs, so
the CMMN requirement was renumbered to R-SEM-20 on the panel review (2026-07-07).

This is the first, standalone slice of a possible multi-engine **Case Inspector** (CMMN)
feature. It ships on its own, changes **no existing count's value**, and lays the capability
seam that Phases 1-3 (Unified Search + Polymorphic Detail) light up later. Phases 1-3 are
**out of scope here** and sketched only in §7.

---

## 1. Why — the spike, and a premise correction

A live spike deployed a deliberately-failing async CMMN service task to the dockerized
6.8 / 7.1 / 6.3.1 engines, read the raw REST JSON, and tore the engines back down. It
answered the three wire-shape questions and, in doing so, **corrected the working premise**
that "CMMN pollution corrupts the BPMN triage counts."

**It largely doesn't — the BPMN status join is already scope-guarded.** SPEC §3 CMMN
hygiene is implemented twice and both legs already drop a job with null/blank
`processInstanceId` (or an explicit non-`bpmn` `scopeType`, ~6.8+) *before* it reaches a
lane count:

- `StatusJoin.isBpmnJob()` — grid path (`aggregate/StatusJoin.java`).
- `TriageAggregationService.bpmnProcessInstanceId()` — Stage-0 path (`triage/…`).

So `statusCounts.FAILED` / `RETRYING` (distinct BPMN *instances*) are already CMMN-clean: an
orphan CMMN job has no instance to count.

**The one real leak** is the raw dead-letter **lane tile**. `EngineHealthService.probeOne`
sets `JobLanes.deadletter = countJobs(engine, DEADLETTER)` — a `size=1` *total* against
`/management/deadletter-jobs`, which the spike proved returns **8** on an engine carrying
6 BPMN + 2 CMMN dead-letters. A count query cannot filter by scope. Result on any 6.8+
engine also running CMMN:

> Dead-letter lane tile: **8** · FAILED status: **6** · and **nothing** tells the operator
> the other 2 exist. They are real, untriaged dead-letter jobs with **no drill path**.

That silent gap — not a corrupted BPMN number — is what Phase 0 closes. It is the exact
do-no-harm failure mode SPEC forbids elsewhere ("never render a status derived from
truncated data without the badge"): here, dead-letter jobs are silently dropped from view.

### 1.1 Spike findings (the wire facts this extension rests on)

- **Q1 — job→case link is a *projection*, not mangling.** `/service/management/deadletter-jobs`
  (process-api) and `/cmmn-api/cmmn-management/deadletter-jobs` read the **same shared table**
  and both list **all** jobs; both resolve either scope by id. The process-api DTO exposes
  `processInstanceId/executionId/processDefinitionId` — for a CMMN job these are **cleanly
  `null`** (no `scopeId` force-fit; there is **no `scopeType`/`scopeId` field on the DTO at
  all**). The cmmn-api DTO exposes `caseInstanceId/caseDefinitionId/planItemInstanceId`.
  Discriminator is implicit: **CMMN iff the cmmn-api projection has a non-null
  `caseInstanceId`** (equivalently: a dead-letter row the process-api projection cannot
  attribute). No N+1 history join is required.
- **Q2 — historic + suspend.** `cmmn-history/historic-case-instances` (6.8/7.x) honors
  `?state=active|completed|terminated|suspended`; DTO parallels historic-process-instances
  (`businessKey/startTime/endTime/state/caseDefinitionId+Name/superProcessInstanceId/
  variables`; no `processDefinitionKey` → derive from `caseDefinitionId`). **Case instances
  cannot be suspended** (`{"action":"suspend"}` → HTTP 400 "Invalid action: 'suspend'");
  the SUSPENDED lane has **no whole-case analog**. `superProcessInstanceId` links a case back
  to a BPMN parent. *(Bears on Phase 1, recorded here for provenance.)*
- **Q3 — the 6.3 cliff is graceful but silently wrong.** The `/cmmn-api` context exists on
  6.3.1 (deploy/start/query all 200/201, **no 500s**) but: (a) a failing CMMN async job
  **never surfaces** in any cmmn-management job/timer/deadletter endpoint (plan item sits
  `async-active`), while the identical case dead-lettered in ~10s on 6.8; (b) `?state=` is
  **silently ignored** (a `completed` filter returns the active case — canary-detect); only
  legacy `?finished=true` is honored; (c) the historic-case DTO has **no `state` field**.
  → CMMN scope features gate to **6.8+**; on <6.8 the signal is `null` (unknown), never a
  wrong number.

---

## 2. Behavior change (WHAT — amends SPEC §3)

> **R-SEM-20.** A dead-letter job that fails BPMN scope hygiene (null/blank
> `processInstanceId`, or `scopeType` ∉ {null, `bpmn`}) is **excluded from every BPMN join
> leg _and counted_**. Per engine, the count of such rows observed in the Stage-0 failure
> scan is surfaced as `outOfScopeDeadletters`. It is **additive** — no existing status or
> lane count changes value.

Operator-visible effect on the Stage-0 landing:

- The dead-letter lane tile keeps showing the **true lane depth** (raw total — CMMN jobs are
  *not* re-hidden; that was the rejected Option A). When `outOfScopeDeadletters > 0` it is
  annotated, e.g. **"8 · 2 CMMN, not triaged here"**, pairing `jobLanes.deadletter` with the
  new per-engine field (both ride in the same `TriageDashboardResponse`).
- `outOfScopeDeadletters` semantics:
  - `null` — engine cannot distinguish scope (pre-6.8, no `scopeType`); render no annotation.
  - `0` — 6.8+, no untriaged out-of-scope dead-letters.
  - `> 0` — N untriaged out-of-scope (CMMN) dead-letters; also proof CMMN is live on this
    engine (no separate presence probe needed).

**Rejected alternative (Option A — drop silently).** Making `jobLanes.deadletter` BPMN-only
would reconcile the tile with FAILED but re-hide real dead-letter jobs — trading a visible
discrepancy for invisible untriaged failures. Rejected: it makes the tool complicit in
hiding server-side incidents, violating the do-no-harm honesty doctrine.

---

## 3. Derivation (HOW — amends ARCHITECTURE §2.3)

Zero new engine calls. `TriageAggregationService.sliceOf` already pages the full DEADLETTER
lane (`withException=true`, bounded by `dlq-scan-cap`) and calls `bpmnProcessInstanceId(job)`
on every row. CMMN dead-letters carry an `exceptionMessage` (spike-confirmed), so they are
already inside that scan. **As shipped**, the tally is a *separate* fold over the DEADLETTER
lane's jobs — deliberately NOT inlined into the grouping loop — invoked once after the lane
scans complete and gated on the `scopeType` capability (the gate is what produces the `null`
case, so it is load-bearing, not decoration):

```java
// sliceOf(): after the failure-lane scans complete —
Integer outOfScope = outOfScopeDeadletters(
        scans.get(JobLaneKind.DEADLETTER).get().jobs(), scopeTypeCapable);

static Integer outOfScopeDeadletters(List<Map<String,Object>> deadLetterJobs, boolean scopeTypeCapable) {
    if (!scopeTypeCapable) {
        return null;                 // pre-6.8: cannot discriminate scope → unknown, never a lying 0
    }
    int count = 0;
    for (Map<String, Object> job : deadLetterJobs) {
        if (bpmnProcessInstanceId(job) == null) {
            count++;                 // real dead-letter, not ours — previously dropped in silence
        }
    }
    return count;
}
```

- **Same denominator as FAILED.** The tally shares the capped, `withException=true` scan, so
  it reconciles with the FAILED count and inherits the existing `dlqScan="truncated@N"`
  badge (a truncated scan makes the tally a documented floor — never silently exact).
- **Aggregation independence preserved.** The count lives in the Stage-0 scan that already
  derives FAILED; the `EngineHealthService` `size=1` lane probe is untouched (it stays the
  raw depth). No cross-wiring of the two aggregators.
- The raw `jobLanes.deadletter` tile keeps its meaning ("jobs parked in the dead-letter
  lane," scope-agnostic). The annotation *explains* the FAILED-vs-lane gap rather than
  papering over it.

---

## 4. Capability gate (amends ARCHITECTURE §2.5)

No new capability infra. `EngineCapabilities.scopeType` (Flowable ≥6.8) already exists, is
derived in `fromVersion`, stored on `EngineHealth`, and shipped to the frontend via
`EngineDto.capabilities`. Phase 0 makes it an **explicit consumer**: `sliceOf` populates
`outOfScopeDeadletters` only when `capabilities.scopeType()` is true; `null` otherwise. This
matches the existing gate style (`FlowSurgeryService.requireChangeStateCapability`,
`InstanceDetailService.externalWorkerCount` degrading to `null`).

---

## 5. Interface deltas

- `dto/TriageDashboardResponse.PerEngineTriage` gains a trailing nullable field:
  `record PerEngineTriage(boolean ok, String error, String dlqScan, Integer outOfScopeDeadletters)`.
  (Shipped as `Integer`, not `Long` — a row count, never near int32; both serialize to a JSON
  number so `schema.d.ts` is identical either way.)
- Frontend: regenerate `frontend/src/api/schema.d.ts` via `npm run gen:api` (run BFF on a
  probed free port; gen:api hardcodes :8085). As shipped, the annotation is a `role="note"`
  reconciliation strip on the Stage-0 landing (`TriagePage`, via the `outOfScope` channel in
  `honesty.ts`) rendered when `outOfScopeDeadletters > 0` — not an inline lane-tile badge. No
  new component.

---

## 6. Test plan (amends TEST-STRATEGY; engine-harness, no mocks)

- **Rung 1 (pure).** Unit-test the `sliceOf` tally over a fixture job set mixing BPMN rows,
  null-pid rows, and `scopeType:"cmmn"` rows → asserts FAILED excludes them and
  `outOfScopeDeadletters` counts exactly the DEADLETTER-lane out-of-scope rows.
- **Rung 4 (dockerized, 6.8).** New `TriageCmmnScopeIT` (`@ActiveProfiles("it-triage")`,
  `@TestInstance(PER_CLASS)`, `@Import(NoDbTestSupport)`): seed one failing BPMN payment +
  one failing CMMN case on engine-a; `Awaitility`-poll real engine state until both
  dead-letter (no `Thread.sleep`); assert `statusCounts.FAILED == <bpmn only>` (regression
  guard) **and** `perEngine["engine-a"].outOfScopeDeadletters == 1`.
- **Rung 4 (legacy, 6.3).** `TriageCmmnScopeLegacyIT` (:8084) asserts `outOfScopeDeadletters
  == null` — the 6.8+ gate (spike Q3: CMMN never dead-letters there and `scopeType` absent).
- **New REST-only seed helpers** in `support/EngineSeed` (no SQL, per the iron rule):
  `deployCmmnIfMissing(client, key, cmmnPath)`, `startFailingCase(client, key, businessKey)`,
  `caseDeadLetterCountFor(client, caseInstanceId)` → `/cmmn-api/cmmn-management/deadletter-jobs`.
  Promote the spike seed `demo-failing-case.cmmn.xml` (async java service task
  `${nonExistentBean.doStuff()}` + `R1/PT1S` + a blocking humanTask to keep the case alive)
  into `docker/processes/`.
- **Teardown (residue hygiene).** `@AfterAll` cascade-deletes the CMMN deployment
  (`DELETE /cmmn-api/cmmn-repository/deployments/{id}?cascade=true`) so the KEEP-up stack's
  shared BPMN DLQ counts stay pristine for parallel sessions.

---

## 7. Forward-compatibility (Phases 1-3 — NOT in this slice)

Phase 0 deliberately produces the seam the later phases consume:

- The `outOfScopeDeadletters` count is a **display placeholder** (a nullable scope facet), not
  a load-bearing seam. Phase 1 replaces the annotation with drillable rows by fetching **both**
  management DLQ lists and **merging by job `id`** (§1.1 Q1). The wire *contract* is genuinely
  additive (scalar → facet-with-rows, no break), but the Phase-1 rows are a **net-new**
  cmmn-api fetch+merge: Phase 0 counts null-pid orphans from the **process-api** scan, whereas
  Phase 1 enumerates from the **cmmn-api** projection (non-null `caseInstanceId`). The two are
  different windows on the shared table with **different truncation caps** — the scalar and the
  facet count can legitimately disagree under truncation. Do not frame Phase 1 as "upgrading"
  the Phase-0 code.
  - **FIRST SLICE SHIPPED 2026-07-08 (full-stack).** `GET /api/triage/engines/{id}/
    out-of-scope-deadletters` (`CmmnScopeService`) enumerates the CMMN dead-letters straight
    from the **cmmn-api** projection (`FlowableEngineClient.cmmnApiBase` + `listCmmnDeadLetterJobs`),
    keeping rows with a non-null `caseInstanceId`. It honors both CONSTRAINTs below: bounded by
    `dlq-scan-cap` + paged + `truncated` lower bound (never an unpaged fetch), and gated on
    `scopeType` (≥6.8) with a live re-check — the call rides the cmmn context, so a DLQ-blind
    6.3 (refused server-side on the capability gate) never returns a wrong number. Frontend: a
    "View jobs" drill on the Stage-0 note → read-only `CmmnScopeDrawer`. Live-verified against
    6.8 (`TriageCmmnScopeIT`). A 2nd increment (2026-07-08) resolves the bare-uuid
    `caseDefinitionId` → readable `caseDefinitionKey`/`caseDefinitionName` via a bounded
    distinct-id lookup against `cmmn-repository/case-definitions/{id}` (N+1 on definitions, never
    on jobs; a missing def degrades to null).
  - **SCOPE-TYPED LANE FACET SHIPPED 2026-07-08 (3rd increment, full-stack).** The drawer is now a
    case-scoped view: `GET …/cmmn-scope` → `CmmnScopeFacet` (`CmmnLaneCounts{active,failed,
    completed,terminated}` + the inlined dead-letter detail). ACTIVE/COMPLETED/TERMINATED are
    count-only (`size=1`) `historic-case-instances?state=` queries
    (`FlowableEngineClient.countHistoricCmmnCaseInstances`), degrading to `null` (unknown, never a
    misleading `0`); FAILED = distinct `caseInstanceId`s among the dead-letters (a lower bound when
    truncated). **No SUSPENDED** (cases can't suspend, Q2); the frontend renders tiles off a
    dedicated `CMMN_STATUSES` const — the M4 hazard below is now CLOSED in code. Same VIEWER floor
    + 6.8 gate (reuses the enumeration, so the gate runs before any lane query). IT seed-await
    hardened to key on the per-run `caseInstanceId` (`EngineSeed.cmmnDeadletterPresentForCase`) —
    the needle-keyed await could short-circuit on a parallel session's same-seed residue.
  - **MERGE SLICE DESCOPED 2026-07-08 (near-zero-yield — supersedes the "deferred" note below).**
    The process-api↔cmmn-api merge-by-`id` reconciliation was originally the "load-bearing spine
    of the unified grid," but the `?scopeType=cmmn` server-side filter (shipped in the first slice)
    obviates it: the cmmn-api leg already spends the whole `dlq-scan-cap` on CMMN rows, so it
    **strictly dominates** the diluted process-api orphan window — the merge yields no rows in the
    normal case (CMMN dead-letters ≤ cap), and extras only in the pathological case where the two
    endpoints order CMMN rows differently past the cap. Crucially the **"degraded — Unknown case"
    fallback can't fire honestly**: a null-pid process-api orphan is only a *candidate* CMMN job;
    confirming it needs a by-id cmmn-api hydration (`deadletter-jobs/{id}`, cap-free) that, on
    success, returns FULL context (→ a normal row, not degraded), and on 404 can't be claimed as
    CMMN at all (never-lie). So the enumeration reads the cmmn-api window ONLY — the discriminator
    is intrinsic to that projection — and the merge is dropped, not deferred. The "structurally
    mandatory degraded-orphan path" reasoning below predates the scope filter; it is superseded.
  - **CONSTRAINT (iron rule):** Phase 1's cmmn-api DLQ fetch is **bounded by `dlq-scan-cap`,
    paged, and carries the `truncated@N` badge**, exactly like the BPMN Stage-0 scan — never a
    single unpaged DLQ fetch.
  - **DLQ scope-filter ASYMMETRY (spike 2026-07-08, live 6.8.0 — the load-bearing wire fact for
    the merge slice).** The two DLQ projections are NOT symmetrically filterable:
    - **`cmmn-api /cmmn-management/deadletter-jobs` HONORS `?scopeType=cmmn`** (narrowed an
      all-BPMN residue set 7→0). So the CMMN leg passes `?scopeType=cmmn` and spends its
      `dlq-scan-cap` on **CMMN rows only** — BPMN projections never crowd CMMN past the cap, and
      the CMMN `truncated` flag is a pure-CMMN lower bound. **The shipped `CmmnScopeService` now
      passes this filter** (was fetch-all + client-filter — corrected as a standalone fix,
      2026-07-08); the intrinsic `caseInstanceId` discriminator is kept as defense-in-depth.
    - **`process-api /management/deadletter-jobs` IGNORES every scope param** (`scopeType=bpmn|
      cmmn`, `withoutScopeType` all returned the unfiltered set) and its rows carry **no scope
      keys at all**. The BPMN leg is therefore unavoidably mixed (BPMN + CMMN orphans), capped
      together — so the merge slice's **degraded-orphan path is STRUCTURALLY MANDATORY**, not a
      nicety: a CMMN job seen only as a null-`processInstanceId` process-api orphan (past the
      cmmn-api cap) has no case context and must render degraded ("Unknown case (context
      truncated)"), never be dropped.
  - **CONSTRAINT:** all CMMN incident features **gate to 6.8+** (§1.1 Q3 — 6.3 is silently
    wrong: DLQ-blind, `?state=` ignored, no `state` field). On <6.8 emit `null`, never a wrong
    number. Re-verify on the cmmn context, not merely inferred from `scopeType`.
  - **`cmmn-repository/case-definitions` wire facts (spike 2026-07-08, live 6.8.0 vs 7.1.0 —
    6.8 and 7.1 BYTE-IDENTICAL on this endpoint; needed by the version facet + the bounded key/
    name resolution already shipped).** The row shape is the BPMN process-definition shape
    **minus `suspended`** (cases can't suspend, §1.1 Q2): `id, key, name, version` (int),
    `deploymentId, category, description, tenantId` (`""` not null when unset), `resource/
    diagramResource, graphicalNotationDefined, startFormDefined, url`.
    - **Versioning is the BPMN "page-to-exhaustion" trap, WORSE.** Default sort is **`name` asc**
      (NOT version); with identical names across versions the tiebreak is DB-dependent and
      **unstable across page sizes** (a `size=200` page came back version-ascending `1,1,2,3`
      while `size=2` offset paging came back descending `3,2,1,1` — same reported
      `sort=name/order=asc`). So `size=1` yields **version 1 (oldest), never latest**. Rules:
      to enumerate versions set **`sort=version` explicitly and page to exhaustion**; for "current"
      use **`latest=true`** (returns latest **per tenant** — 2 rows on a 2-tenant key, NOT one);
      for an exact one use **`version=N`** (+ `tenantId`). The by-id resolver already shipped
      (`/case-definitions/{id}`) sidesteps ALL of this — prefer it whenever the id is known.
    - **Tenant threading is IDENTICAL to the BPMN repository queries** (no cross-streams):
      `?tenantId=X` exact-matches, `?tenantId=` (empty) isolates the blank-tenant defs. **BUT
      `?withoutTenantId=true` is SILENTLY IGNORED** on CMMN (returned all tenants on both 6.8 &
      7.1 — a param-drop like the 6.3 BPMN drops). Thread `tenantId` exactly; **never rely on
      `withoutTenantId`** — use empty `tenantId=` for the blank-tenant filter.
- Phase 1 status lanes for CMMN are **ACTIVE / FAILED / COMPLETED / TERMINATED** — **no
  SUSPENDED** (§1.1 Q2; cases cannot suspend). `superProcessInstanceId` carries cross-engine
  hierarchy. **SHIPPED 2026-07-08** as `CmmnLaneCounts` behind `GET …/cmmn-scope` (see the
  lane-facet note above).
  - **HAZARD (M4) — CLOSED 2026-07-08:** the drawer drives its tiles off the new `CMMN_STATUSES`
    const (`frontend/src/api/model.ts`), NOT `ALL_STATUSES`, so TERMINATED is never dropped and no
    empty SUSPENDED lane appears. Original hazard retained for context: the BPMN lane set
    (`ALL_STATUSES`) hardcodes
    SUSPENDED and lacks TERMINATED — the **mirror image** of CMMN's. A polymorphic Phase-1 UI
    must drive its tile/lane set off the row's `scopeType` (introduce a `CMMN_STATUSES` const),
    **not** reuse the single global `ALL_STATUSES` — else TERMINATED is silently dropped into
    the unlabeled bucket and an always-empty SUSPENDED lane can appear.
  - **HAZARD:** `superProcessInstanceId` crosses the cmmn-history → process-api context (and
    possibly engine) boundary. The existing within-process hierarchy roll-up cycle-guard does
    not span two APIs — Phase 1 must **extend**, not reuse, it.
- **Phase 2 — LANDED 2026-07-08 (full-stack).** Polymorphic Stage-2 detail: a `cmmn-js` canvas +
  a plan-item state-machine timeline at `/case/{engineId}/{caseInstanceId}`. Full design + wire
  provenance: **`docs/CMMN-CASE-DETAIL-PHASE-2.md`** (the authority for the phase). Read-only,
  gated 6.8+.
- **Phase 3 — LANDED 2026-07-08 (full-stack): CMMN dead-letter retry & delete.** The case detail
  becomes actionable for the two dead-letter verbs a co-deployed CMMN case needs — **Retry job**
  (tier 0 / RESPONDER) and **Delete dead-letter job** (tier 3 / ADMIN) — under the FULL
  `corrective-actions` rails. The key design call: the existing BPMN `CorrectiveActionService`
  dispatcher was GENERALIZED (an `ActionScope.CMMN` seam), NOT forked — the rails are scope-neutral
  (`audit_entry` keys on a generic instance id), so only two seams differ: the by-id restatement
  reads `cmmn-management/deadletter-jobs/{id}` (cap-free, owner = `caseInstanceId`) and the one
  engine call is the `/cmmn-management/deadletter-jobs/{id}` sibling — `POST … {"action":"move"}`
  for retry, `DELETE …` for delete (both byte-identical to process-api — **live-proven 6.8,
  2026-07-08**: HTTP 204 each; move re-queues → re-dead-letters as the same id, delete is terminal
  → gone for good). Route `POST /api/cases/{engineId}/{caseInstanceId}/actions/{verb}` (+`/curl`,
  server-computed, BFF-targeted — never an engine path/token). SPEC §5.3, ARCH §4.
  **Delete is scope-honest:** a CMMN case has no change-state rescue in this tool, so the delta and
  the (dedicated `CaseDeleteModal`) blast-radius copy say the plan item is orphaned permanently —
  never the BPMN "rescue via change-state" line. **Correction to the earlier sketch:** the "Show as
  cURL" targets the BFF verb endpoint (like every other action), NOT
  `cmmn-runtime/case-instances/{id}/…` — that draft premise was wrong; no engine path or credential
  ever reaches the client.
  - **The delete follow-up flagged as open at Phase-3 landing is now CLOSED (2026-07-08):** the
    `ActionScope.CMMN` seam absorbed it with a `deleteCmmnDeadLetterJob` client method + the shared
    tier-3 job-id token; no new controller route (the generic verb door already accepts it).
  - **CONSTRAINT (R-GOV-05, discharged before the canvas landed):** the guard
    `frontend/scripts/check-bpmn-watermark.mjs` was generalized to `/(bjs|cmmn)-powered-by/i`
    **first**. NOTE the premise correction: `cmmn-js` 0.20 actually emits the SAME
    `bjs-powered-by` element as `bpmn-js` (not a `cmmn-powered-by` variant — verified live), so the
    original guard would in fact have covered it; the generalization is kept as forward defense
    against a future `cmmn-js`/`dmn-js` renaming. See CMMN-CASE-DETAIL-PHASE-2 §1 Q5.

---

## 8. Doc-sync checklist (discharged on the panel review, 2026-07-07)

- [x] SPEC §3 — "excluded from every BPMN join leg" now reads "excluded **and counted**;
      surfaced as `outOfScopeDeadletters`" (SPECIFICATION §3, L166-173).
- [x] ARCHITECTURE §2.3 — Stage-0 tally added (L124-127, §4 API table L228); §2.5 — `scopeType`
      recorded as an explicit consumer of Phase 0.
- [x] REQUIREMENTS-REGISTER — requirement registered as **R-SEM-20** (the earlier R-SEM-08
      citation collided with the engineId slug rule; renumbered on review).
- [x] IMPLEMENTATION-PLAN — Phase 0 recorded as shipped; Phases 1-3 sequenced with a link here.
- [x] TEST-SCENARIOS — `TS-STAT-16` covers the out-of-scope count (6.8 = 1, 6.3 = null gate);
      TEST-STRATEGY R1 names `TriageCmmnScopeIT` / `TriageCmmnScopeLegacyIT`.

### 8.1 Panel-review follow-ups (raised 2026-07-07) — RESOLVED 2026-07-07 (`b6c7837`)
Both items below were fixed together in `b6c7837` ("triage: floor out-of-scope CMMN count
under a capped DEADLETTER scan (H1/M1)"); ARCHITECTURE §2.2 and SPECIFICATION §3 were updated
in the same commit. Recorded here as closed for provenance.
- **Truncation honesty gap (HIGH) — FIXED.** `PerEngineTriage` gained a lane-specific
  `deadletterTruncated` flag (the shared `dlqScan` marker OR-conflates all three failure lanes,
  so it is not a faithful per-lane proxy), captured from the DEADLETTER `LaneScan.truncated()`
  before it merges into `dlqScan`. `outOfScopeDeadletters(...)` now returns
  `OutOfScope(count, deadletterTruncated)`; `honesty.ts` carries `floor = deadletterTruncated`
  per entry; the strip renders a "≥N" lower bound when floored. `styles.css` dropped the false
  "counts ARE exact" claim.
- **Reconciliation wording (MODERATE) — FIXED.** The Stage-0 strip was reworded to job-scoped
  "≥N CMMN jobs not triaged here", dropping the "tile − CMMN = FAILED" arithmetic that paired a
  jobs count against the instance-scoped FAILED chip over a different denominator.
