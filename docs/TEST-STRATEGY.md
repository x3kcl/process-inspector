# 🧪 TEST STRATEGY — Process Inspector

Governance layer over the tooling pinned in SPECIFICATION §10 and the `engine-harness` /
`unit-test-patterns` skills. Register IDs: R-TEST-01…09 (REQUIREMENTS-REGISTER.md).

## 1. Risk ranking → coverage floors (R-TEST-01)
Test depth follows product risk, not code size:

| Rank | Area | Floor |
|---|---|---|
| R1 | **Status join & derived flags** (a wrong status is the product lying) | ≥90% branch on rung-1 logic; every flag combination incl. collisions (suspended+DLQ, subprocess roll-up at depth limit, RETRYING parked in the timer table, CMMN filtering, tenant threading) proven against real engines on **all three compose profiles** |
| R2 | **Guard ladder / RBAC / tenant scoping** (the only permission layer) | 100% enumerated matrix: every mutating endpoint × role (VIEWER/RESPONDER/OPERATOR/ADMIN) × in/out-of-scope engine+tenant × read-only-engine mode → expected 403/greyed reason |
| R3 | **Bulk outcomes** | every outcome class (`ok/failed/skipped/skipped-protected/unknown/not_run`) produced by an automated test; timeout→UNKNOWN never re-fired; INTERRUPTED reconciliation |
| R4 | **UI flows** | Playwright smoke of FIND→ORIENT→DIAGNOSE→FIX→VERIFY, ≤10 min in PR CI; axe accessibility checks hard-fail |

Backend line coverage ≥80%, frontend logic ≥70%, measured and gating from M3.

## 2. Milestone gates (R-TEST-02)
**Entry:** previous exit gate green; required fixtures exist. **Exit:** (a) full suite green on
all applicable profiles, 3 consecutive CI runs; (b) floors met for touched code; (c) zero open
Sev1/Sev2; (d) every "Done when" demo converted into an automated E2E that IS the gate.
M2a additionally: the six status-join bugs from DESIGN-REVIEW re-expressed as red-first
regressions.

## 3. Defect taxonomy (R-TEST-03) — severity derives from the design principles
- **Sev1 (release-blocking by definition):** any *quiet lie* — wrong status/flag rendered; a
  count shown without lower-bound labeling under truncation/engine error; guard-tier, RBAC,
  tenant-scope or protected-instance bypass; action executed against a different target than
  confirmed; mutation with missing/wrong audit row; timed-out mutation auto-retried.
- **Sev2:** labeled-but-wrong data; capability-gated feature hidden instead of
  greyed-with-reason; broken deep link/URL state.
- **Sev3/4:** conventional functional/cosmetic. Every Sev1 fix lands red-first.

## 4. Golden files: the signature normalizer (R-SEM-03)
Versioned corpus ≥30 real exception payloads **per engine major** (6.x, 7.x), captured from
live compose profiles (never hand-written), committed under
`backend/src/test/resources/error-signatures/{6.x,7.x}/` with expected signatures + group
assignments. CI asserts zero unparseable + exact mapping. A normalizer change must bump
`algoVersion`, regenerate goldens, and show the grouping diff in the PR. Image bumps
re-capture the corpus before capability sign-off.

## 5. Security testing (R-TEST-06)
Scope: path-whitelist bypass (traversal, double-encoding, method override), the full
RBAC×tenant×mode matrix incl. SSE and bulk endpoints, CSRF, ARCH §6 fencing probed **from
outside the trusted network path**, secret non-leakage in envelopes/audit, actuator surface,
CSV formula injection, hostile-exception-message fixture. Executed by an independent tester
during M6; zero high findings at GA. Continuous: SCA hard-fails known-exploitable or CVSS ≥7.

## 6. Fixture catalog (R-TEST-04)
Every seed process / captured payload has a stable ID (`FIX-STATUS-03: suspended+DLQ
collision`), an owner (lead dev), and a purpose. The SPEC §3 flag matrix maps to fixtures —
an empty cell is a visible coverage gap. Mandatory cells: each flag alone, each collision,
roll-up at depth 1/limit/limit+1, RETRYING in job+timer tables, CMMN rows, tenant threading.
Capture scripts live in `docker/`; captured fixtures carry the engine image tag.

## 7. Performance scenarios (R-TEST-05, replaces "Load-test fan-out")
| # | Scenario | Threshold | When |
|---|---|---|---|
| P1 | 10 stub engines, 3 slow (read-ms−500), 2 timing out | search P95 ≤ read-ms+2s; correct envelope; BFF health P99 <200ms throughout | M2 exit, re-run M6, nightly |
| P2 | 50k-row DLQ vs `dlq-scan-cap` | scan ≤10s, `truncated@cap`, un-scanned never shown healthy | M2 exit, nightly |
| P3 | 50 SSE clients through a 500-item bulk, 30 min | zero dropped/dup events; heap ±10% | gates the v1.x SSE/bulk item |
| P4 | 100 landing loads in 5s | one engine-query round per aggregation (≥99% cache hits); Refresh rate-limit holds | M3 exit |

## 8. Capability matrix (R-TEST — SHOULD)
Per capability flag × profile: probe detects expected value; supported → verb succeeds E2E;
unsupported → BFF rejects with the capability reason AND UI greys with matching tooltip.
"No orphan flags": CI fails when a flag lacks matrix rows. PR CI runs the current-6.x
column; nightly runs the full cross.

## 9. Testability hooks (R-TEST-07 — normative)
- One `java.time.Clock` bean behind every age/staleness/cache computation; **event timestamps
  always from engine responses**, never BFF receive time; ages floored at 0.
- Curated-view thresholds are config (test profile: seconds, not days).
- RETRYING pinned deterministically: seed task `flowable:failedJobRetryTimeCycle="R10/PT1H"`;
  fast-DLQ seeding: `R1/PT1S`. Poll-with-deadline, never fixed sleeps.
- Truncation tested with test-registry `dlq-scan-cap: 50`, `max-page-size: 10` (never by
  seeding 10k jobs).
- Hierarchy depth via one self-recursive seed process (`depth < maxDepth` in-parameter);
  **cycle-guard is the documented exception to the never-mock-Flowable rule** (real engines
  cannot produce cycles) — tested at rung 1 over a fixture parent-map.
- Guard ladder E2E: register the same docker engine twice (`dev` + `prod`); drift fixtures
  via out-of-band engine mutation from Playwright; assert Enter-never-submits.
- Breakers: per-engine test profile (window 2, open 500ms) + WireMock fault/scenario recipes;
  assert cache hits don't count against the breaker.
- Bulk cancel/UNKNOWN: latch-gated WireMock stub engine (dispatch order made total via
  concurrency 1); `write-ms` governs mutation timeouts.
- OpenAPI gate determinism: maven-plugin export, sorted keys, fixed info.version, exact
  version pins. SSE contract asserted against the documented event catalog (not OpenAPI).

## 10. UAT & soak (R-TEST-08/09)
UAT at M6: ≥3 practicing support engineers, scripted incident scenarios from the fixture
catalog, ≥80% unassisted completion; trust-breaking observations file as Sev1/Sev2. Weekly
post-M6 soak: 4h mixed load with engine kills + BFF restart mid-bulk; assert breaker cycling,
INTERRUPTED with intact partial report, no UNKNOWN auto-retry. Playbook test charter is a
precondition for starting v2 playbook work.
