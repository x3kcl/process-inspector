# 🩺 SPEC EXTENSION — CMMN scope visibility (Phase 0)

**Status:** Proposed · **Date:** 2026-07-07 · **Author:** remote-control design session
**Extends:** SPECIFICATION §3 (status join / CMMN hygiene), ARCHITECTURE §2.3 & §2.5
(capability map) · **Provenance:** live wire-shape spike vs dockerized 6.8 / 7.1 / 6.3.1
(see below) · **Requirement:** proposed **R-SEM-08** (assign in REQUIREMENTS-REGISTER on
landing)

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

> **R-SEM-08 (proposed).** A dead-letter job that fails BPMN scope hygiene (null/blank
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
already inside that scan. The `else` branch of the existing guard becomes the tally:

```java
// sliceOf(), in the existing per-job loop
} else if (scan.getKey() == JobLaneKind.DEADLETTER) {
    outOfScopeDeadletters++;   // real dead-letter job, not ours — previously dropped in silence
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
  `record PerEngineTriage(boolean ok, String error, String dlqScan, Long outOfScopeDeadletters)`.
- Frontend: regenerate `frontend/src/api/schema.d.ts` via `npm run gen:api` (run BFF on a
  probed free port; gen:api hardcodes :8085). Render the lane-tile annotation when
  `outOfScopeDeadletters > 0`. No new component.

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

- The `outOfScopeDeadletters` count is the **degenerate scalar** of what Phase 1 turns into a
  first-class CMMN scope facet. Phase 1 replaces the annotation with drillable rows by
  fetching both management DLQ lists and **merging by job `id`** (§1.1 Q1) — no data-contract
  break, the badge becomes a facet.
- Phase 1 status lanes for CMMN are **ACTIVE / FAILED / COMPLETED / TERMINATED** — **no
  SUSPENDED** (§1.1 Q2). `superProcessInstanceId` carries cross-engine hierarchy.
- Phase 2 (polymorphic Stage-2 detail: `cmmn-js` canvas — **R-GOV-05 watermark rule applies
  to `.cmmn-powered-by`** — plus a plan-item state-machine timeline) and Phase 3 (CMMN
  corrective actions under the full `corrective-actions` rails, server-computed
  "Show as cURL" emitting `cmmn-runtime/case-instances/{id}/…`) remain unspecified pending
  a separate plan.

---

## 8. Doc-sync checklist (when this lands, same change)

- [ ] SPEC §3 — replace "excluded from every BPMN join leg" with "excluded **and counted**;
      surfaced as `outOfScopeDeadletters` (R-SEM-08)."
- [ ] ARCHITECTURE §2.3 — add the Stage-0 tally; §2.5 — note `scopeType` gains an explicit
      consumer.
- [ ] REQUIREMENTS-REGISTER — assign R-SEM-08 its real id + acceptance test refs.
- [ ] IMPLEMENTATION-PLAN — add Phase 0 as a shipped slice; reference this doc for Phases 1-3.
- [ ] TEST-STRATEGY / TEST-SCENARIOS — the CMMN-scope IT and its 6.3 gate leg.
