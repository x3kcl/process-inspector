# 🩺 SPEC EXTENSION — Polymorphic Stage-2 CMMN case detail (Phase 2)

**Status:** In progress 2026-07-08 · **Author:** remote-control design session
**Extends:** SPECIFICATION §4 (Stage 2 — Instance detail), ARCHITECTURE §4 (BFF API surface),
`docs/CMMN-SCOPE-PHASE-0.md` §7 (Case Inspector phasing) · **Provenance:** live wire-shape
spike vs dockerized Flowable 6.8.0 (see §1) · **Requirement:** **R-SEM-20** (CMMN scope
visibility; this is its Stage-2 detail slice).

Phase 0 counted the out-of-scope CMMN dead-letters; Phase 1 made them a drillable, scope-typed
lane facet (a read-only **list**). **Phase 2 gives a CMMN case its own Stage-2 detail page** —
the polymorphic sibling of the BPMN `/inspect/{engineId}/{id}` route: a `cmmn-js` case diagram
(top half, read-only) plus a **plan-item state-machine timeline** (the CMMN analog of the BPMN
activity timeline). Still **read-only** — CMMN corrective actions are Phase 3. Still **gated
6.8+** — 6.3.1 is dead-letter-blind and stateless on the CMMN context (Phase 0 §1.1 Q3).

---

## 1. Wire-shape spike (live 6.8.0, 2026-07-08) — the facts this slice rests on

Deployed the failing-case seed (`docker/processes/demo-failing-case.cmmn.xml`), started a case,
read the raw REST JSON, tore it down.

- **Q4 — case vitals.** `cmmn-runtime/case-instances/{id}` (running) carries
  `businessKey/state("active")/ended/startTime/startUserId/caseDefinitionId/caseDefinitionName/
  parentId/tenantId/variables[]`; the historic fallback `cmmn-history/historic-case-instances/{id}`
  parallels historic-process-instances (adds `endTime/state`). **Historic-first**, exactly like the
  BPMN vitals (a completed case renders, never 404s). No `caseDefinitionKey`/`version` on either
  DTO — resolve from the bare-uuid `caseDefinitionId` via `cmmn-repository/case-definitions/{id}`
  (already shipped in Phase 1).
- **Q5 — the diagram source.** A CMMN `case-definition` row carries `resource` (the deployed
  `.cmmn.xml` URL), `deploymentId`, and **`graphicalNotationDefined`** (boolean). The raw model
  is fetched exactly like BPMN: `deploymentId` + resource-name tail → `cmmn-repository/deployments/
  {deploymentId}/resourcedata/{resourceName}`. `…/case-definitions/{id}/model` returns a **JSON**
  object dump (not XML) — not what `cmmn-js` wants; use `resourcedata`.
  - **`cmmn-js` needs CMMNDI.** The viewer does **not** auto-layout: a `.cmmn.xml` with no
    `<cmmndi:CMMNDI>` block (`graphicalNotationDefined:false`) imports to an **empty canvas**. The
    detail page must degrade honestly — an explicit "this case has no graphical layout" state —
    and lean on the plan-item timeline (which needs no DI). The seed
    `docker/processes/demo-case-detail.cmmn.xml` (Phase 2) ships **with** hand-authored CMMNDI so
    the canvas actually renders in the demo/e2e; `demo-failing-case.cmmn.xml` stays DI-less (its
    job is the Phase-0/1 out-of-scope fixture) and exercises the no-layout path.
  - **Two `cmmn-js` (0.20.x) integration facts found while building (it predates bpmn-js's
    modern API):** (a) its `importXML(xml, done)` is the **callback** form — it returns
    `undefined`, so awaiting it (`.then`) throws and crashes the page; call it with a callback.
    (b) The watermark it injects is the **`.bjs-powered-by`** element (the SAME class bpmn-js
    uses), **not** a `cmmn-powered-by` variant — so the already-shipped `bjs-powered-by` guard
    would in fact have caught it, but generalizing the guard to `/(bjs|cmmn)-powered-by/i` is kept
    as forward defense (a future cmmn-js/dmn-js could rename it). e2e asserts `.bjs-powered-by`
    stays present on the canvas.
  - **Seed-authoring gotcha:** Flowable's CMMN DI XSD is stricter than `cmmn-js` — each
    `<cmmndi:CMMNShape>` **must** contain a (possibly empty) `<cmmndi:CMMNLabel/>` child or the
    deployment 500s (`cvc-complex-type.2.4.b … CMMNLabel is expected`); `cmmn-js` renders fine
    without it. The seed carries the label; see `validate-bpmn` for the BPMN analog.
- **Q6 — the plan-item timeline is RUNTIME-ONLY on 6.8.** `cmmn-runtime/plan-item-instances?
  caseInstanceId=` returns every plan item with `id`, **`elementId`** (the planItem id — the CMMN
  DI shape key), `name`, `planItemDefinitionId`, **`planItemDefinitionType`** (`servicetask`/
  `humantask`/`stage`/`milestone`/…), **`state`** (`available`/`enabled`/`active`/`async-active`/
  `completed`/`terminated`/…), `stage` (bool), `stageInstanceId` (parent stage → nesting), and a
  full lifecycle-timestamp set (`createTime`, `lastAvailableTime`, `lastEnabledTime`,
  `lastStartedTime`, `completedTime`, `occurredTime`, `terminatedTime`, `exitTime`, `endedTime`).
  **`cmmn-history/historic-plan-item-instances` 404s on 6.8** — there is **no historic plan-item
  REST surface**. `cmmn-runtime/case-instances/{id}/stage-overview` and `historic-milestone-
  instances` exist (200) but the former is empty for a stage-less case. Consequence: the
  plan-item timeline is available for **running** cases only; for an **ended** case the runtime
  plan items are gone and there is no history to fall back on → render the timeline as
  **"unavailable for ended cases on this engine"** (honest), never a fabricated empty timeline.
  The vitals header still renders for ended cases (from case history).
- **Q7 — the FAILED join is by `planItemInstanceId`, NOT `elementId`.** A CMMN dead-letter job
  row carries `planItemInstanceId` = the plan-item-instance's **`id`** (reliable join) and
  `elementId` = the plan-item **definition** id (`failingService`) — which is **not** the
  plan-item's own `elementId` (`planItem_svc`, the DI shape key). Joining on `elementId` would
  silently never match. So: FAILED plan item ⇔ a dead-letter job whose `planItemInstanceId`
  equals the plan item's `id`; the **canvas marker** is then keyed by that plan item's `elementId`.
  The plan item itself stays `async-active` while its job is dead-lettered — FAILED is derived
  from job presence, exactly like the BPMN dead-lettered-async node (SPEC §4 Timeline).

---

## 2. Behavior change (WHAT — amends SPEC §4)

A polymorphic Stage-2 route: **`/case/{engineId}/{caseInstanceId}`** — the CMMN sibling of
`/inspect/{engineId}/{id}`. Reached from the Phase-1 out-of-scope drawer (each FAILED job's case
becomes a link) and from the omnibox CMMN-case match (Phase 1 shipped it as an inert row —
Phase 2 makes it navigable). Read-only.

- **Vitals header** (no tab): case type (key/name/version), engine env badge, state chip
  (ACTIVE / COMPLETED / TERMINATED — **no SUSPENDED**, cases can't suspend), business key,
  started/ended/duration, start user, and — when the case has a dead-lettered plan item — a
  **"why stuck" strip**: the failing element + first exception line + a link into the Phase-1
  scope drawer's job detail. A `superProcessInstanceId` renders as a "called from a BPMN process"
  breadcrumb (cross-engine hierarchy is Phase 3 territory — Phase 2 shows the id, does not walk).
- **Diagram first** (top half, read-only `cmmn-js` `NavigatedViewer`): plan-item markers —
  active/enabled/async-active plan items highlighted, dead-lettered plan items badged. When the
  case definition has **no graphical notation** (`graphicalNotationDefined:false`), an explicit
  no-layout state replaces the canvas (never a blank box). The `.cmmn-powered-by` watermark is a
  license term (R-GOV-05) and is left exactly as rendered — the build guard now enforces it.
- **Plan-item timeline** (the CMMN analog of the BPMN activity timeline): each plan item as a row
  ordered by `createTime`, showing element + type + **lifecycle state** (a small state-machine
  progression: available → active → completed/terminated, driven by the timestamp set) and a
  **live job badge** (`FAILED` = a dead-letter job is parked on it, `RETRYING` = a failing job
  with retries left) using pattern + text, never hue-only (SPEC §10a). Stage children nest under
  their `stageInstanceId`. Runtime-only (Q6): an ended case shows the honest "timeline unavailable
  for ended cases on this engine version" state.

No Variables/Tasks/Hierarchy/Audit tabs in this slice — the CMMN analogs of those (typed case
variables, human-task ledger, cross-engine hierarchy walk, CMMN audit) are deferred. Phase 2's
job is the **diagram + timeline** pairing that makes a case legible; the tabs follow with Phase 3.

---

## 3. Derivation (HOW — amends ARCHITECTURE §4)

New `io.inspector.cmmn.CaseDetailService` + `CaseDetailController` at
`/api/cases/{engineId}/{caseInstanceId}` (VIEWER floor). Every method **capability-gates 6.8+**
via a shared `CmmnCapabilityGuard.requireScopeType(...)` (extracted from `CmmnScopeService`'s
existing gate so the two CMMN services share one refusal, one ProblemDetail contract). Read-only;
no audit rows, no mutation path.

| Endpoint | Source | Notes |
|---|---|---|
| `GET …/{caseInstanceId}` | historic-first `cmmn-history/historic-case-instances/{id}`, runtime enrich `cmmn-runtime/case-instances/{id}` | `CaseDetail`: identity, case-def key/name/version (resolved from the bare-uuid `caseDefinitionId`), state, business key, times, start user, `superProcessInstanceId`, `present`/`ended`, a `failing` summary (dead-letter count + first exception) |
| `GET …/{caseInstanceId}/diagram` | case-def `resource` → `cmmn-repository/deployments/{deploymentId}/resourcedata/{name}` | `CaseDiagram`: `xml`, `graphicalNotationDefined`, `activePlanItemElementIds`, `failedPlanItemElementIds` (marker sets, keyed by plan-item `elementId` per Q7) |
| `GET …/{caseInstanceId}/plan-items` | `cmmn-runtime/plan-item-instances?caseInstanceId=` (bounded, paged) joined with the case's `cmmn-management/deadletter-jobs?scopeType=cmmn&caseInstanceId=` | `CasePlanItems`: `available` (false + reason for an ended case — Q6), `truncated`, and the rows (elementId, name, type, state, `stageInstanceId`, timestamps, `liveJobState`) |

- **FAILED/RETRYING join** (Q7): build a `planItemInstanceId → job` map from the case's
  dead-letter jobs (`retries==0` ⇒ FAILED) and the case's executable jobs with retries left
  (⇒ RETRYING); annotate each plan item by its **`id`**. The marker sets on the diagram are then
  the annotated plan items' **`elementId`**s. Never join on the job's `elementId` (it is the
  definition id).
- **Bounded, paged, honest** (iron rule): the plan-item and dead-letter scans page to a bounded
  cap and carry `truncated`; a huge case never triggers an unpaged fetch.
- **Runtime-only timeline** (Q6): `plan-items` returns `available:false` + a reason string for an
  ended case (runtime plan items gone, no historic REST source on 6.8) rather than an empty list
  that would read as "no plan items."

---

## 4. Capability gate (amends ARCHITECTURE §2.5)

No new capability infra. `EngineCapabilities.scopeType` (Flowable ≥6.8) is the gate, re-checked
in the BFF on every case-detail call (the calls ride the cmmn context, so a DLQ-blind 6.3 is
refused server-side with a ProblemDetail, never a silently wrong page). The frontend never routes
to `/case/…` for a pre-6.8 engine — but the server stays the gate regardless.

---

## 5. Frontend

- **Dependency:** add `cmmn-js` (bpmn.io toolkit; same license term as `bpmn-js`). The
  **watermark guard is generalized first** (`check-bpmn-watermark.mjs` → `/(bjs|cmmn)-powered-by/i`)
  so the `.cmmn-powered-by` element can never be stripped — done ahead of the dependency.
- **Route** `/case/:engineId/:caseInstanceId` → `CasePage`; per-segment hooks in
  `src/inspect/useCaseQueries.ts` (fetch vitals + diagram + plan-items).
- **`CaseDiagramCanvas`** — a `cmmn-js` `NavigatedViewer` mirroring `DiagramCanvas`
  (one viewer per mount, re-import on xml change, markers re-applied, watermark untouched); an
  explicit no-layout state when `graphicalNotationDefined` is false.
- **`PlanItemTimeline`** — a pure `planItemModel.ts` (stage nesting + state derivation, rung-1
  vitest) rendered as a `role=tree`; live-job badges by pattern+text; the honest ended-case state.
- **Navigation:** the Phase-1 `CmmnScopeDrawer` case ids become links to `/case/…`; the omnibox
  CMMN-case match becomes navigable (its earlier inert-row honesty note is retired now that a
  destination exists).
- `npm run gen:api` (own BFF on a probed free port — the recurring multi-session gotcha).

---

## 6. Test plan (engine-harness, no mocks for join logic)

- **Rung 1 (pure).** `CaseDetailMappingTest` — the plan-item↔job join keys on `planItemInstanceId`
  (a fixture where job `elementId` ≠ plan-item `elementId` proves the trap); marker sets carry the
  plan-item `elementId`; FAILED vs RETRYING precedence. Frontend `planItemModel.test.ts` — stage
  nesting, state derivation, ended-case unavailability.
- **Rung 4 (dockerized 6.8).** `CaseDetailIT` (`@Import(NoDbTestSupport)`): seed the failing case,
  Awaitility-poll until it dead-letters (no `Thread.sleep`), assert vitals (`state==active`,
  resolved case-def key), the plan-item timeline carries the two plan items with the servicetask
  `liveJobState==FAILED`, and the diagram marker `failedPlanItemElementIds` holds `planItem_svc`.
  A DI-bearing `demo-case-detail` variant asserts `graphicalNotationDefined==true` and a non-empty
  marker set. `@AfterAll` cascade-deletes the CMMN deployment (residue hygiene).
- **Rung 4 (legacy 6.3).** Covered by the existing 6.8 gate — a `…Legacy` probe asserts the
  case-detail calls are refused (ProblemDetail) on a pre-6.8 engine.
- **e2e (hermetic Playwright).** `e2e/case-detail.spec.ts` — mock the three endpoints, assert the
  canvas host renders (or the no-layout state), the plan-item timeline nests a stage child and
  shows the FAILED badge, and the drawer→case navigation.

---

## 7. Doc-sync checklist

- [x] SPECIFICATION §4 — added the polymorphic `/case/{engineId}/{id}` CMMN detail (diagram +
      plan-item timeline, read-only, 6.8+) beside the BPMN Stage-2 route.
- [x] ARCHITECTURE §4 — three `GET /api/cases/{engineId}/{id}[…]` rows; §2.5 `scopeType` as a
      Phase-2 consumer.
- [x] IMPLEMENTATION-PLAN — Phase 2 marked landed with a link here.
- [x] `docs/CMMN-SCOPE-PHASE-0.md` §7 — Phase 2 cross-links this doc; the `cmmn-powered-by`
      premise corrected to the real `.bjs-powered-by` (see §1 Q5 note).

## 8. Verified (2026-07-08)

- **Backend, rung-4 live 6.8** (`CaseDetailIT`, 3 tests): vitals resolve the case type + surface
  the failure; the plan-item timeline carries the servicetask `liveJobState==FAILED` joined by
  `planItemInstanceId`; the diagram marks `planItem_svc` (the plan item's `elementId`, not the
  job's).
- **Frontend, hermetic e2e** (`case-detail.spec.ts`, 2 tests): the no-layout state + timeline
  nesting + FAILED badge; a real in-browser `cmmn-js` render with the `marker-deadletter` class
  and the `.bjs-powered-by` watermark present.
- **Live end-to-end** (Playwright MCP vs real 6.8 engine-a): the DI-less `demoFailingCase` renders
  the honest no-layout state + the timeline with the 🛑 Failed servicetask; the DI-bearing
  `demoCaseDetail` seed renders the `cmmn-js` canvas with `planItem_svc` carrying
  `marker-deadletter` + the ⚠ overlay, watermark intact.
