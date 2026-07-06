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
OPERATIONS.md §8. **Status correction:** M0's "CI" claim was ahead of reality — no workflows
or Dockerfile exist yet; landing them is part of M2a's gate.

## M0 — Scaffold *(done)*
- Repo layout, CI, docker-compose dev harness (2× `flowable/flowable-rest`).
- **Addition:** a second compose profile pinned to an **older Flowable image** so the
  capability cliffs (change-state 6.4+, migration ~6.5+, external-worker 6.8+) are exercised
  in CI; seed processes incl. the deliberately-failing async task (see `validate-bpmn` skill).

## M1 — Engine Registry + health  *(bootstrapped)*
- Registry YAML binding + env-ref secrets; per-engine `RestClient`; scheduled health probe.
- **Extend:** capability probes per ARCH §2.5 (changeState/migration/externalWorkerJobs/
  scopeType/activityHistory); job-lane counts + oldest-executable-job-age + overdue-timers
  via the `size=1` total trick.
- **Done when:** header strip shows each engine with env-colored badge, version, lanes.

## M2 — Search & results  *(bootstrapped — needs the correctness rework)*
- **M2a (fix before anything else):** rewrite the status join per ARCH §2.3 — DLQ-driven
  inverted plan for FAILED-only, exhaustive bounded paging + `dlqScan` truncation badge,
  per-page runtime-state enrichment, FAILING tier (jobs+timer-jobs `withException`),
  `superProcessInstanceId` roll-up, CMMN filtering, tenant threading. Status = flags.
  Integration-tested against the real dockerized engines (never mocked — `engine-harness`).
- **M2b:** search additions — failure-time filter/sort, `businessKeyLike`, variable `like`,
  current-activity and error-text filters, facet counts; **URL-encoded search state**;
  compiled-criteria echo + copy-as-cURL.
- **M2c:** grid columns (definition version, failure time, status badges), snapshot "as of"
  header + Refresh, partial-result banner + lower-bound labeling.
- **Done when:** a search over 2 engines returns correctly-flagged rows incl. a
  failed-in-subprocess parent and a FAILING (retries-left) instance; killing an engine
  mid-demo degrades to a labeled partial result; a 10k-DLQ engine shows the truncation badge
  instead of lying.

## M3 — Instance detail (full-page route) + triage landing
- **Detail page `/inspect/{engineId}/{id}`** (deep-linkable now, not M6): vitals header with
  "why stuck" strip (exception first line, retries state, waiting-for subscriptions/timers);
  **read-only bpmn-js diagram** (pulled forward from M5) with token + dead-letter markers,
  synced selection; lazy tabs: Variables (view) · Errors & Jobs (four lanes, stacktrace on
  expand) · Tasks · Hierarchy · Timeline. Copy-for-ticket button.
- **Omnibox** (`GET /api/resolve`).
- **Triage landing**: engine health strip, status counts, failure groups by normalized error
  signature with click-through, curated system views, recent-operations placeholder.
- **Done when:** from the landing, one click on an error group reaches a pre-filtered list;
  opening a stuck instance shows why it's stuck without any click; the link pastes into a
  ticket and reopens the same view.

## M4 — Corrective actions + audit + RBAC + Postgres
- **Postgres** joins the deployable: audit log, notes.
- Single-target verb catalog tiers 0–3 (SPEC §5): retry / retry-now / trigger-timer /
  unstick-event / suspend-activate / edit-variable (typed, old→new diff, old value audited) /
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
