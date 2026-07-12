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
live compose profiles by `docker/capture-error-corpus.py` (never hand-written; it deploys
the organically-failing error-zoo seeds and harvests message + stacktrace per job),
committed under `backend/src/test/resources/error-signatures/{6.x,7.x}/corpus.json`.
`ErrorSignatureGoldenCorpusTest` is the CI gate: zero unparseable entries, the exact
kind→root-class mapping, one-signature-per-kind grouping (the ID-stripping proof), and
cross-major hash convergence for tail-bearing 7.x messages. A normalizer change must bump
`algoVersion`, re-run the capture, and show the grouping diff in the PR. Image bumps
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
roll-up at depth 1/limit/limit+1, RETRYING in job+timer tables, CMMN rows (both *filtered from
every join leg* and *counted* as `outOfScopeDeadletters` per R-SEM-20 — `OutOfScopeDeadlettersTest`
at rung 1, `TriageCmmnScopeIT` on 6.8, `TriageCmmnScopeLegacyIT` for the 6.3 null gate), tenant
threading.
Capture scripts live in `docker/`; captured fixtures carry the engine image tag.
The catalog is realized in [TEST-SCENARIOS.md](TEST-SCENARIOS.md) §1 (fixtures `FIX-*`) with
the scenario mapping (`TS-*`) in its §§2–12.

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
- RETRYING pinned deterministically: seed task carries the
  `<flowable:failedJobRetryTimeCycle>R10/PT1H</flowable:failedJobRetryTimeCycle>` extension
  ELEMENT (the attribute form parses but is silently ignored — proven on flowable-rest 6.8;
  `validate-bpmn` skill §2); fast-DLQ seeding: `R1/PT1S`, same element form.
- **Anti-flakiness doctrine (enforced, not advisory):** `Thread.sleep()` in any test is a
  hard failure — an **ArchUnit rule in the unit suite** bans `Thread.sleep`/`TimeUnit.sleep`
  from test classes, so a PR containing a fixed sleep fails CI before review. All
  time-dependent assertions use Awaitility with explicit bounds
  (`atMost` sized to the awaited state, explicit `pollInterval`), and `untilAsserted`
  evaluates **real state** — the flowable-rest endpoint or the BFF's own API, never a mock
  interaction or local variable. Awaitility never wraps a mutation (poll reads only).
  Canonical idiom + sizing table: `engine-harness` skill.
- Truncation tested with test-registry `dlq-scan-cap: 50`, `max-page-size: 10` (never by
  seeding 10k jobs).
- Hierarchy depth via one self-recursive seed process (`depth < maxDepth` in-parameter);
  **cycle-guard is the documented exception to the never-mock-Flowable rule** (real engines
  cannot produce cycles) — tested at rung 1 over a fixture parent-map.
- Guard ladder E2E: register the same docker engine twice (`dev` + `prod`); drift fixtures
  via out-of-band engine mutation from Playwright; assert Enter-never-submits.
- **Playwright harness (landed with v1.1 flow surgery, the first R4 rung; CI-gated 2026-07-12,
  #85 — the second R4 rung, "axe accessibility checks hard-fail"):**
  `frontend/playwright.config.ts` + `frontend/e2e/`, `npm run e2e` (the `e2e` CI job on every
  PR/push). Smokes are HERMETIC — a `page.route` predicate on `/api/` fulfills every BFF call
  from canned DTOs (a URL predicate, never the `**/api/**` glob, which would hijack Vite's
  `/src/api/*` modules), so no BFF or engine is needed, only the Vite dev server. Mock-BFF
  smokes assert UI *invariants* (the flow-surgery specs record every `…/execute`/`…/restart`
  request and assert none fired — simulation-first is a tested property, not a convention);
  flows that need REAL engine semantics (drift fixtures above) stay on the live-stack rung.
  The never-mock-Flowable rule governs BFF join logic, not browser-side smokes of
  already-tested BFF contracts. Every spec calls the shared `scanA11y()` helper (`e2e/a11y.ts`,
  wraps `@axe-core/playwright`) at each settled UI state it already asserts against —
  `scripts/check-e2e-a11y-coverage.mjs` hard-fails the build if a spec never calls it, closing
  the gap an autouse fixture would leave (autouse would also risk scanning mid-transition DOM
  and false-positive). Chromium is baked into the CI runner image
  (`docker/ci-runner/Dockerfile`), not installed per-job — the runner is ephemeral and only
  `/opt/hostedtoolcache` survives a restart.
- Breakers: per-engine test profile (window 2, open 500ms) + WireMock fault/scenario recipes;
  assert cache hits don't count against the breaker.
- Bulk cancel/UNKNOWN: latch-gated WireMock stub engine (dispatch order made total via
  concurrency 1); `write-ms` governs mutation timeouts.
- OpenAPI gate determinism: maven-plugin export, sorted keys, fixed info.version, exact
  version pins. SSE contract asserted against the documented event catalog (not OpenAPI).

## 10. Test-data generation — three stages (normative; R-TEST-04/07)
Test data moves toward reality as the confidence claim grows. Each stage has hard rules;
mixing stages silently is a doc/test bug. Scenario catalog:
[TEST-SCENARIOS.md](TEST-SCENARIOS.md) (stages tagged per scenario).

**S1 — Synthetic (ladder rungs 1–3 only).** Hand-built fixture objects via builders (never
raw JSON blobs) for pure join/merge/filter logic; WireMock/MockWebServer payloads for
client-fault shapes only — timeouts, 5xx, breaker cycling, truncated pages, hostile
messages, and scale that must never be seeded for real (the 50k-DLQ P2 stub). Synthetic
wire payloads are **forbidden for join/status/paging semantics** (the never-mock-Flowable
iron rule); the two sanctioned exceptions are the hierarchy cycle-guard (real engines
cannot produce cycles) and CMMN-row filtering where a profile lacks CMMN REST. Determinism:
seeded builders, the `Clock` bean, no wall-clock reads. Replaying a **captured** corpus
(§4) in CI is S1 execution of S2-provenance data — capture provenance is what makes it
legitimate.

**S2 — Generated real data on the dockerized engines (rung 4 + E2E).** All engine
semantics are proven against data *generated* on real `flowable-rest` containers, strictly
over REST (deploy → start → mutate), by idempotent seed scripts in `docker/` plus per-test
setup classes — never by hand-crafted DB rows or mocked responses. Every status/collision
is manufactured per the recipes in TEST-SCENARIOS §1.2 (fast-DLQ `R1/PT1S`, pinned RETRYING
`R10/PT1H`, suspend-after-dead-letter, recursive depth seeding), with poll-with-deadline
and engine timestamps only (§9). Runs on **all three compose profiles**. Captured corpora
(error signatures, capability snapshots, 7.x error JSON) are S2 outputs committed with the
engine image tag and re-captured on every image bump. The R-NFR-02 reference dataset is
S2-generated nightly, bounded by the operating envelope (≤5k DLQ); anything larger is
S1-stubbed, never seeded.
**Raw SQL/JDBC insertion into engine tables is REJECTED as a seeding mechanism** — Flowable
job/execution rows carry invariants (execution linkage, revision counters, exception byte
arrays) that hand-built rows can violate, encoding states no real engine produces: the
quiet-lie class applied to test data. Speed comes from `R1/PT1S` retry cycles and parallel
REST seeding; *scale* comes from lowering the caps under test (`dlq-scan-cap: 50`) or
S1 stubs — never from bypassing the engine. The seconds-fast feedback loop is preserved by
construction, not by a backdoor.

**S3 — Production, read-only observation.** Production engines are validation targets,
**never data generators**: registered `mode: read-only` (R-GOV-04) under a dedicated
read-only credential; no seeding, no mutation, no load generation; scan caps enforced.
Checks (TEST-SCENARIOS §12): shadow status validation via "explain this status" evidence
(mismatch = Sev1 by taxonomy §3), signature-normalizer coverage over the real DLQ,
operating-envelope and latency observation, capability-probe and clock-skew verification.
Prod payloads enter the repo only through the sanitized golden-corpus pipeline
(secret/PII scrub + named approval, R-AUD-03). S3 is observational — it files defects,
never gates CI.

## 11. Audit-integrity suite (R-TEST-10 — merge-gating, S2 + Testcontainers)
The fail-closed audit rule and the dual-write UNKNOWN rule (SPEC §6/§9, R-AUD-01,
R-SEM-18) are the system's integrity spine and get their own named CI suite, run against
**real PostgreSQL** (Testcontainers) — stateful DB failure modes are exactly what H2 or
mocks hide:
- **Fail-closed proof:** Postgres stopped/unreachable → a tier-1 mutation is REFUSED before
  any engine call (assert via WireMock: zero requests received); error names Postgres.
- **Dual-write proof:** audit `PENDING` written → engine call succeeds (WireMock) →
  Postgres killed before the outcome UPDATE → response is "dispatched — outcome
  verification failed" (never a bare 500), row remains/recovers to `unknown`, Verify-now
  reclassifies it.
- **Pool exhaustion:** Hikari pool saturated (hold all connections in-test) → mutations
  queue/fail-closed per config, reads degrade gracefully, `audit_insert_failures_total`
  increments.
- **Transaction rollback:** a failed audit transaction never half-commits (row count
  invariant before/after).
- **Reconciler sweep:** stale `PENDING` rows older than `write-ms`+grace are swept to
  `unknown` on startup.
These scenarios are S1/S2 hybrids (WireMock engine + real Postgres) and merge-gating from
M4 (OPERATIONS §8 gate 3 is this suite).

## 12a. Requirement → suite traceability (R-TEST-02/04)
Coverage floors and the fixture catalog are only credible if every requirement resolves to a
real suite. [TRACEABILITY-MATRIX.md](TRACEABILITY-MATRIX.md) is that ledger: `R-*` → `TS-*` →
concrete `*Test`/`*IT`/`*.test.ts`/`*.spec.ts`, with an honest coverage-gap register (its §C).
Two suites make the register's "no orphan endpoint/verb" rule mechanical rather than aspirational:
- **`RbacGuardMatrixTest`** (L1) — the *generated* verb×role RBAC matrix (TS-RBAC-01, the R2
  floor): it iterates `ActionVerb.values()`, so a verb shipped without a role floor fails CI.
- **`catalog.test.ts` TS-VERB-14 block** (vitest) — iterates `VERBS`; a verb without a
  reversibility badge or its §5.0 plain label fails CI.
The two current release-gating (P1) open gaps are matrix §C-5 (audit-integrity pool/sweep/REVOKE
beyond `FailClosedAuditIT`) and §C-16 (the single canonical-arc E2E behind the per-surface smokes).

## 12. UAT & soak (R-TEST-08/09)
UAT at M6: ≥3 practicing support engineers, scripted incident scenarios from the fixture
catalog, ≥80% unassisted completion; trust-breaking observations file as Sev1/Sev2. Weekly
post-M6 soak: 4h mixed load with engine kills + BFF restart mid-bulk; assert breaker cycling,
INTERRUPTED with intact partial report, no UNKNOWN auto-retry. Playbook test charter is a
precondition for starting v2 playbook work.
