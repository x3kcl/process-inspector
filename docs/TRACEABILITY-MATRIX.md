# 🔗 TRACEABILITY MATRIX — requirement → scenario → suite

The third leg of the test-plan triad: [REQUIREMENTS-REGISTER.md](REQUIREMENTS-REGISTER.md)
says **WHAT must hold** (`R-*`), [TEST-SCENARIOS.md](TEST-SCENARIOS.md) says **HOW we'd prove
it** (`TS-*`), and this file says **WHERE the proof actually lives** (the concrete automated
suite) — or names the gap when it does not yet. It answers three questions a reviewer or
release manager asks:

1. Does every MUST-v1 requirement have at least one scenario **and** a real automated test?
2. For a given scenario, which class(es) discharge it, at which rung?
3. What is genuinely **not yet automated** (the honest coverage-gap register, §C)?

**Maintenance rule (spec-sync).** This matrix moves in lockstep with the code: a new
mutating endpoint, verb, capability, or requirement adds a row here in the same change; a new
`*Test`/`*IT`/`*.test.ts`/`*.spec.ts` updates the suite column. A `GAP` that closes flips to a
class name; a class that is deleted or renamed must not leave a dangling reference here. The
register's own rule (R-SEM-07) — milestone done-when clauses cite the IDs they discharge — is
enforced from this side by the generated-matrix tests (see §B, TS-RBAC-01 / TS-VERB-14), which
fail CI the moment a verb ships without a matrix row.

**Rungs** (per `unit-test-patterns` + TEST-SCENARIOS §layers): **L1** pure logic · **L2** HTTP
stub (WireMock/MockWebServer) · **L3** cached `@SpringBootTest` · **L4** dockerized Flowable
(all three profiles: 6.8 default · 6.3 `legacy` · 7.1 `7x`) · **E2E** Playwright (hermetic
`page.route`, `frontend/e2e/`) · **UNIT-FE** vitest (`frontend/src/**/*.test.ts`).

**Coverage legend.**
- ✅ **automated** — a real suite asserts the behavior; named in the Suite column.
- 🟡 **partial** — covered at one rung but a promised rung/profile or slice is still open (detail in §C).
- 🔲 **gap** — no automated test yet; §C gives priority + recommended shape.
- 📋 **manual/process** — discharged by a documented human/CI-config gate, not a test (e.g. sign-offs, Trivy, axe-config); tracked, never asserted by JUnit/vitest.

---

## A. Requirement → scenario → suite (MUST-v1 first)

Only the ID stem is shown for suites; full paths resolve under `backend/src/test/java/io/inspector/…`
or `frontend/src/…`. A requirement with several legs lists the dominant suite(s); §B is the
exhaustive scenario→class index.

### SEM — semantics & contracts
| Req | Scenario(s) | Suite(s) | Rung | Cov |
|---|---|---|---|---|
| R-SEM-02 RETRYING/FAILED chips | TS-STAT-04/05 | `StatusJoinTest`, `SearchServiceIT`; FE `partials.test`, `plainFailure.test` | L1·L4·UNIT-FE | ✅ |
| R-SEM-03 signature normalizer contract | TS-TRI-04, TS-CAP-03 | `ErrorSignatureNormalizerTest`, `ErrorSignatureGoldenCorpusTest`, `Triage7IT` | L1·L4 | ✅ |
| R-SEM-04 omnibox resolution | TS-OMNI-01/02 | `DetailResolveIT`; FE `omnibox.test` | L4·UNIT-FE | 🟡 (resolver precedence L1 gap — §C-4) |
| R-SEM-05 curated-view honesty | TS-TRI-11 | FE `systemViews.test`, `honesty.test`; `saved-views.spec` | UNIT-FE·E2E | ✅ |
| R-SEM-06 error-text search scope | TS-SRCH-05 | `StatusJoinTest$FailureFilters`, `SearchServiceIT` | L1·L4 | ✅ |
| R-SEM-08 engineId slug / composite split | TS-OMNI-03 | `InspectorPropertiesValidationTest`; FE `omnibox.test` | L1·UNIT-FE | ✅ |
| R-SEM-09 concurrent-op / CAS | TS-VERB-06, TS-BULK-03 | `CorrectiveActionServiceTest`, `CorrectiveActionIT`, `BulkJobServiceTest` | L1·L4 | ✅ |
| R-SEM-10 bulk = tracked job | TS-BULK-05 | `BulkJobServiceTest` (reconcile sweep), `BulkFilterIT` | L1·L4 | ✅ |
| R-SEM-11 circuit-open mid-bulk | TS-BULK-06 | `BulkJobServiceTest` (permits/pause) | L1 | ✅ (pause + INTERRUPTED rung-1 tested; breaker *transition* L2 WireMock slice still §C-6) |
| R-SEM-12 Stage-0 truncation badges | TS-TRI-08, TS-STAT-12 | FE `honesty.test`, `drill.test`; `cmmn-scope.spec` | UNIT-FE·E2E | ✅ |
| R-SEM-14 SSE lifecycle contract | TS-BULK-11 | `SseHubTest`, `BulkFilterIT` | L1·L4 | 🟡 (P3 soak = §C-8) |
| R-SEM-18 dual-write UNKNOWN | TS-AUD-02, TS-BULK-02 | `AuditServiceTest`, `CorrectiveActionServiceTest`, `FailClosedAuditIT` | L1·L4 | ✅ |
| R-SEM-19 hierarchy breadth cap | TS-STAT-08, TS-DET-08 | `InstanceTimelineServiceTest`, `InstanceTimelineIT`; FE `timelineModel.test` | L1·L4·UNIT-FE | ✅ |
| R-SEM-20 CMMN out-of-scope count | TS-STAT-16 | `OutOfScopeDeadlettersTest`, `TriageCmmnScopeIT` (6.8), **`TriageCmmnScopeLegacyIT` (6.3 null gate)** | L1·L4 | ✅ (legacy IT added this pass) |
| R-SEM-22 k-way-merge cursor contract | (design) | `PagingCursorTest` (L1 codec/dedup/bound), crafted-cursor-refused-pre-fan-out (L2 WireMock), deep-scroll ITs (L4, config-lowered caps) | L1·L2·L4 | 🔲 design-locked + spike-gated (`docs/KWAY-PAGING.md`) |
| R-SEM-23 deterministic total order | (design) | `StatusJoinTest` goldens: tiebreak order, `+00:00`/`Z` compare-equal, `nullsLast` | L1 | 🔲 standalone bug-fix, S1 (red-first goldens) |
| R-SEM-24 team/shared saved-view model + honesty | (design) | `shared_view` migration/uniqueness IT (L4-DB, LOCAL-ONLY), `overlaps()` + derived-scope + read-visibility (L1 authorizer — dev ladder is global-only), replay resolvability partial-vs-total-dead-vs-clean-empty (L1) + greying vitest | L1·L4 | 🔲 design-locked + demand-gated (`docs/SHARED-VIEWS.md`) |
| R-SAFE-16 team/shared saved-view governance | (design) | publish `covers()` gate + wildcard→ADMIN + content-bound refusal + forged owner/scope ignored (L1 authorizer + L3 door), audited fail-closed lifecycle via `recordConfigEvent` (L4-DB positive rows + **PRIVATE-write-no-audit negative** + mocked-throws→503+no-flip L3), concurrent-publish→409, injection caps | L1·L3·L4 | 🔲 design-locked (`docs/SHARED-VIEWS.md`); scoped RBAC not rung-3-reachable (dev ladder global-only) |

### SAFE — operator safety & RBAC (risk rank R2)
| Req | Scenario(s) | Suite(s) | Rung | Cov |
|---|---|---|---|---|
| R-SAFE-01 RESPONDER role + verb floors | TS-RBAC-02 | **`RbacGuardMatrixTest`** (generated verb×role), `ActionRbacGuardSpringTest` | L1·L3 | ✅ (matrix added this pass) |
| R-SAFE-02/04 reversibility + plain labels | TS-VERB-14 | FE `catalog.test` **(TS-VERB-14 block)**; `RbacGuardMatrixTest` (tier/floor) | UNIT-FE·L1 | ✅ (badge/label asserts added this pass) |
| R-SAFE-03 tier-0 prod friction floor | TS-GUARD-01 | FE `catalog.test` (`needsTwoStepConfirm`) | UNIT-FE | ✅ |
| R-SAFE-05 protected instances | TS-RBAC-03 | `CorrectiveActionServiceTest`, `ActionRbacGuardSpringTest`; FE `intersection.test` | L1·L3·UNIT-FE | ✅ |
| R-SAFE-06/11 break-glass | TS-RBAC-04 | — | — | 🔲 (§C-2) |
| R-SAFE-08 second-approval hooks | TS-RBAC-05 | — (audit columns exist; not exercised) | — | 🔲 (§C-3) |
| R-SAFE-09 Verify-now | TS-BULK (verify) | `BulkJobServiceTest` (verifyNow…) | L1 | ✅ |
| R-SAFE-12 group→scope hot-reload | TS-RBAC (scope) | `ScopeMappingServiceTest` | L1 | ✅ |
| RBAC 100% matrix | **TS-RBAC-01** | **`RbacGuardMatrixTest`** (verb×role, completeness-guarded) + `ActionRbacGuardSpringTest` (HTTP wiring); scope isolation `ScopeMappingServiceTest`; read-only mode `CorrectiveActionServiceTest` | L1·L3 | 🟡 (OIDC-scoped-grant HTTP leg = §C-1) |

### AUD — audit & data protection
| Req | Scenario(s) | Suite(s) | Rung | Cov |
|---|---|---|---|---|
| R-AUD-01 fail-closed audit | TS-AUD-01 | `AuditServiceTest`, `FailClosedAuditIT`, `CorrectiveActionServiceTest` | L1·L4 | ✅ |
| R-AUD-02 normative schema | TS-AUD-02 | `AuditServiceTest`, `CorrectiveActionIT` | L1·L4 | ✅ |
| R-AUD-03 redaction / role-gate / hash chain | TS-AUD-03/04/05 | `AuditServiceTest` (redact, hash-chain, truncation) | L1 | 🟡 (DB REVOKE + tamper read-path = §C-5) |
| R-AUD-05 shift report | TS-AUD-07 | — | — | 🔲 (§C-7) |
| R-AUD-06 copy-for-ticket | TS-DET-11 | FE `ticket.test` | UNIT-FE | 🟡 (E2E leg pending — §C-9) |
| R-AUD-08 CSV export + formula-escape | TS-AUD-08 | — | — | 🔲 (§C-7) |

### NFR — service levels
| Req | Scenario(s) | Suite(s) | Rung | Cov |
|---|---|---|---|---|
| R-NFR-01 limits/caps | TS-BULK-08, TS-AGG-07, TS-DET-05 | `BulkJobServiceTest`, `BulkFilterServiceTest`; FE `intersection.test` | L1·UNIT-FE | ✅ |
| R-NFR-02 latency budget | TS-TRI-06, P4 | — (perf harness) | — | 🔲 perf (§C-10) |
| R-NFR-03 cache TTL / refresh throttle | TS-TRI-06/07 | `TriageServiceTest`, `TriageAggregationIT` | L1·L4 | ✅ |
| R-NFR-04 alarm thresholds | TS-TRI-02 | `EngineCapabilitiesTest`, health ITs | L1·L4 | 🟡 (threshold arithmetic L1 slice thin — §C-11) |
| R-NFR-07 write-ms timeout | TS-BULK-02 | `InspectorPropertiesValidationTest`, `CorrectiveActionServiceTest` (timeout→UNKNOWN) | L1 | 🟡 (slow-engine L2 exercise = §C-6) |
| R-NFR-08 deep-paging envelope | (design) | inbound-offset-cap-check (L1/L2), `DEEP_PAGE` bulkhead lane wiring (L3), deep-page cost harness (§C-11) | L1·L2 | 🔲 design-locked (`docs/KWAY-PAGING.md`); perf harness = §C-11 |

### OPS / L3 / GOV / UXQ / BAU (representative)
| Req | Scenario(s) | Suite(s) | Rung | Cov |
|---|---|---|---|---|
| R-OPS-01 readiness excludes engines | — | `EnginesApiSpringTest` (registry bind) | L3 | 🟡 (readiness probe assert = §C-12) |
| R-OPS-07/08 injection / secret hygiene | TS-AUD-03/08, FIX-STUB-05 | `EnginesApiSpringTest`, `ActionCurlTest`, `AuditServiceTest` (redact) | L1·L3 | 🟡 (CSV formula-escape + hostile-msg CI fixture = §C-7) |
| R-L3-01 explain-this-status | TS-DET-14, TS-STAT-13 | `DetailResolveIT`, `SearchServiceIT` (plan choice) | L4 | 🟡 (per-flag provenance E2E = §C-9) |
| R-L3-03 raw-JSON per-tab | TS-DET-13 | `DetailResolveIT` | L4 | ✅ |
| R-GOV-04 read-only engine mode | TS-RBAC-01, TS-PROD-01 | `CorrectiveActionServiceTest`, `ActionRbacGuardSpringTest` | L1·L3 | ✅ |
| R-GOV-05 grid/watermark contract | — | `frontend/scripts/check-bpmn-watermark.mjs` (build gate); ESLint ag-grid-enterprise | build | 📋 |
| R-UXQ-01 axe accessibility | TS-E2E-01/04 | Playwright axe (config) | E2E | 🔲 (axe wiring per-spec = §C-13) |
| R-UXQ-13 form-first variables | TS-DET-04/15, TS-VERB-06 | `InstanceDetailMappingTest`; FE `ledger.test`, `editor/diff.test`, `editState.test` | L1·UNIT-FE | 🟡 (rendered edit→verify→CAS E2E = §C-14) |
| R-BAU-01 error-group acknowledge | TS-TRI-09 | — | — | 🔲 (§C-15) |
| R-BAU-02 leak views | TS-TRI-10 | FE `systemViews.test` | UNIT-FE | 🟡 (L4 age-from-engine-ts = §C-15) |

### TEST — governance (self-referential)
| Req | Discharged by | Cov |
|---|---|---|
| R-TEST-01 risk floors | R1 `StatusJoinTest`; R2 `RbacGuardMatrixTest`+`ActionRbacGuardSpringTest`; R3 `BulkJobServiceTest`; R4 Playwright smokes | 🟡 (R4 canonical-arc E2E = §C-16) |
| R-TEST-07 testability hooks | `NoSleepInTestsArchTest` (ArchUnit sleep ban), `Clock`-driven `TriageServiceTest`, cycle-guard `StatusJoinTest`/`InstanceTimelineServiceTest` | ✅ |
| R-TEST-10 audit-integrity suite | `FailClosedAuditIT` (fail-closed), `AuditServiceTest` (dual-write translate) | 🟡 (pool-exhaustion + reconciler-sweep IT = §C-5) |

---

## B. Scenario → suite index (reverse lookup)

Exhaustive TS-* → class(es). `—` = no automated suite yet (see §C). This is the column CI's
"no orphan scenario" check reads.

| TS | Suite(s) | Rung |
|---|---|---|
| TS-STAT-01..11 | `StatusJoinTest`, `SearchServiceIT`/`Search7IT`/`SearchLegacyIT`, `EngineHealth{,7,Legacy}IT`; FE `partials.test` | L1·L4·UNIT-FE |
| TS-STAT-12 dlq-scan-cap truncation | `SearchServiceIT` (cappedDlqScan…) | L4 |
| TS-STAT-13 inverted plan evidence | `StatusJoinTest$PlanSelection`, `SearchServiceIT` | L1·L4 |
| TS-STAT-14 full flag matrix | `StatusJoinTest$Predicates`; FE `partials.test` (secondaryBadges) | L1·UNIT-FE |
| TS-STAT-15 six join bugs red-first | `StatusJoinTest` (join semantics) | L1 | 🟡 §C-17 |
| TS-STAT-16 CMMN out-of-scope | `OutOfScopeDeadlettersTest`, `TriageCmmnScopeIT`, **`TriageCmmnScopeLegacyIT`** | L1·L4 |
| TS-AGG-01..08 | `SearchServiceIT`, `FlowableEngineClientTest` (breaker), `TriageAggregationIT`; FE `partials.test` | L2·L4·UNIT-FE |
| TS-TRI-01..11 | `TriageServiceTest`, `TriageAggregationIT`, `Triage{7,Legacy}IT`, `ErrorSignatureGoldenCorpusTest`; FE `honesty.test`/`drill.test`/`systemViews.test`; `retry-group.spec`/`cmmn-scope.spec` | L1·L4·UNIT-FE·E2E |
| TS-SRCH-01..08 | `SearchServiceIT`, `CriteriaEchoTest`; FE `urlState.test`/`partials.test`/`model.test` | L1·L4·UNIT-FE |
| TS-SRCH-06/07 URL round-trip / cURL E2E | `saved-views.spec` (URL replay) | E2E | 🟡 §C-18 (interactive search) |
| TS-OMNI-01/02/03 | `DetailResolveIT`; FE `omnibox.test` | L4·UNIT-FE | 🟡 §C-4 |
| TS-DET-01..14 | `DetailResolveIT`, `InstanceDetailMappingTest`, `InstanceTimeline{Service,}IT`, `SiblingDiffServiceTest`; FE `ledger.test`/`ticket.test`/`timelineModel.test`/`comparison/*`; `timeline-sublanes.spec`/`sibling-diff.spec`/`external-worker.spec` | all |
| TS-DET-15 date variable edit | FE `editState.test` (parseDate…), `editor/diff.test` | UNIT-FE | 🟡 §C-14 (rendered) |
| TS-VERB-01..07 | `CorrectiveActionServiceTest`, `CorrectiveActionIT`, `ActionCurlTest`; FE `taskAssign.test`/`problem.test` | L1·L4·UNIT-FE |
| TS-VERB-08/09/10 (v1.1 surgery) | `FlowSurgeryServiceTest`, `FlowSurgeryIT`, `BpmnStructureTest`; `flow-surgery.spec` | L1·L4·E2E |
| TS-VERB-14 reversibility + §5.0 labels | **FE `catalog.test` (TS-VERB-14 block)** + `RbacGuardMatrixTest` (tier/floor) | UNIT-FE·L1 |
| TS-GUARD-01..05 | `CorrectiveActionServiceTest`, `ActionRbacGuardSpringTest`, `FlowSurgeryServiceTest`; FE `catalog.test` | L1·L3·UNIT-FE |
| TS-RBAC-01 generated matrix | **`RbacGuardMatrixTest`** + `ActionRbacGuardSpringTest` | L1·L3 | 🟡 §C-1 |
| TS-RBAC-02/03 | `ActionRbacGuardSpringTest`, `CorrectiveActionServiceTest`; FE `intersection.test` | L1·L3·UNIT-FE |
| TS-RBAC-04 break-glass | — | — | 🔲 §C-2 |
| TS-RBAC-05 approval hooks | — | — | 🔲 §C-3 |
| TS-BULK-01..10 | `BulkJobServiceTest`, `BulkErrorClassServiceTest`, `BulkFilterServiceTest`, `BulkErrorClassIT`, `BulkFilterIT`; FE `intersection.test`/`filterScope.test`; `filter-bulk.spec`/`retry-group.spec` | L1·L4·UNIT-FE·E2E |
| TS-BULK-11 SSE progress | `SseHubTest`, `BulkFilterIT` | L1·L4 | 🟡 §C-8 (P3 soak) |
| TS-AUD-01..05 | `AuditServiceTest`, `FailClosedAuditIT`, `CorrectiveActionIT` | L1·L4 | 🟡 §C-5 |
| TS-AUD-06 notes | `ActionRbacGuardSpringTest` (notes floor); `DetailResolveIT` | L3·L4 |
| TS-AUD-07 shift report | — | — | 🔲 §C-7 |
| TS-AUD-08 CSV export | — | — | 🔲 §C-7 |
| TS-CAP-01/02/03 | `EngineCapabilitiesTest`, `CmmnScopeServiceTest`, `ExternalWorkerJob{7,Legacy}IT`, `Triage7IT`, `SearchLegacyIT`; FE `externalWorker.test` | L1·L4·UNIT-FE |
| TS-E2E-01 canonical arc | — (per-surface smokes only) | — | 🔲 §C-16 |
| TS-E2E-02/03/04 | partial across `cmmn-scope.spec`/`filter-bulk.spec` + FE `partials.test` zero-states | E2E·UNIT-FE | 🟡 §C-16 |
| TS-PROD-01..07 | S3 read-only observation (not CI-gated by design) | manual | 📋 |

---

## C. Coverage-gap register (honest open list)

Each gap: the requirement/scenario it leaves short, why it is not yet automated, the
recommended suite shape, and a priority (P1 release-gating risk floor · P2 SHOULD-v1.x ·
P3 later). **Closed this pass** items were gaps until this change and now have suites.

**Closed this pass**
- ~~C-0a TS-RBAC-01 generated verb×role matrix~~ → **`RbacGuardMatrixTest`** (L1, 52 matrix
  rows + completeness/structural guards). The R2 floor previously rested on hand-picked
  `ActionRbacGuardSpringTest` spot checks with no dedicated `RbacAuthorizer` test.
- ~~C-0b TS-VERB-14 reversibility badge + §5.0 labels~~ → **`catalog.test.ts` TS-VERB-14 block**
  (every verb has a valid badge, non-empty note, §5.0 verbatim plain label, prod friction floor).
- ~~C-0c TS-STAT-16 6.3 null gate~~ → **`TriageCmmnScopeLegacyIT`** (L4 against real 6.3.1: a
  non-empty BPMN DLQ lane still yields `outOfScopeDeadletters == null` — unknown, never a
  confident 0). The class was named in three docs but did not exist.

**Open**
| # | Gap | Req/TS | Why open | Recommended shape | Prio |
|---|---|---|---|---|---|
| C-1 | OIDC scoped-grant HTTP leg of the RBAC matrix | R-SAFE-01, TS-RBAC-01 | `RbacGuardMatrixTest` proves verb×role over global (dev) grants; scope isolation is proven separately at L1 (`ScopeMappingServiceTest`). No single test drives an OIDC session with a *scoped* grant over HTTP to prove out-of-engine/out-of-tenant 403s end-to-end. | Add an `it`-profile `@SpringBootTest` with a stubbed OIDC user whose mounted mapping grants ADMIN only on `engine-a/tenant-a`; assert 403 on `engine-b` and on `tenant-b`, 2xx→502 in-scope. | P2 |
| C-2 | Break-glass account | R-SAFE-06/11, TS-RBAC-04 | Feature is SHOULD-v1.x hooks; no `/break-glass` path test. | L3 test: sealed local account → ADMIN-global session, distinguished audit flag set, page-banner flag in `/api/me`, 4h cap; reason ≥10 mandatory on every verb incl. tier-0. | P2 |
| C-3 | Second-approval / proposal hooks | R-SAFE-08, TS-RBAC-05 | Audit columns (`approved_by`, proposal state) exist but no test inserts/exercises them. | L4 audit-schema test: insert a PENDING_APPROVAL row, approver≠proposer enforced, TTL, both identities audited. | P2 |
| C-4 | Resolver precedence L1 | R-SEM-04, TS-OMNI-01 | Kind-detection/ordering proven only at L4 (`DetailResolveIT`); no docker-free unit of the precedence logic. | Extract/parameterize the resolve order (process-instance→execution→task→job→composite→business-key) into an L1 test over fixtures. | P3 |
| C-5 | Audit-integrity: pool exhaustion + reconciler sweep + DB REVOKE | R-AUD-03, R-TEST-10, TS-AUD-05 | `FailClosedAuditIT` covers DB-down fail-closed; `AuditServiceTest` covers hash-chain + dual-write translation. Not covered: Hikari saturation degradation, stale-PENDING startup sweep against real PG, and a REVOKE-UPDATE/DELETE proof. | Testcontainers PG suite `AuditIntegrityIT`: hold all connections → mutations fail-closed + `audit_insert_failures_total`++; seed stale PENDING → startup sweep → `unknown`; attempt UPDATE/DELETE as the app role → rejected. | **P1** |
| C-6 | Circuit-open mid-bulk pause + write-path slow-engine | R-SEM-11, R-NFR-07, TS-BULK-06 | Pausing/permits proven in-memory (`BulkJobServiceTest`); the breaker *transition* mid-dispatch and a genuinely slow write engine aren't exercised. | L2 WireMock: fault after N dispatches trips breaker → undispatched stay `pending`, dispatched fast-fail = `failed`; a `write-ms`-exceeding stub → `unknown`, never retried. | P2 |
| C-7 | Shift report · CSV export · hostile-message CI fixture | R-AUD-05/08, R-OPS-08, TS-AUD-07/08 | Reporting/export surfaces and the FIX-STUB-05 hostile-exception fixture aren't wired to a suite. | L3: streaming CSV endpoint with FIX-DATA-03 content → formula-escaped output; shift-report filter groups UNKNOWNs first; a committed 1 MiB hostile-message fixture replayed through the normalizer + audit ingest. | P2 |
| C-8 | SSE soak (P3) | R-SEM-14, R-TEST-05, TS-BULK-11 | `SseHubTest`+`BulkFilterIT` prove logic + one live stream; the 50-client/30-min soak is unrun. | Scheduled (non-PR) P3 harness: 50 EventSource clients through a 500-item bulk, assert zero dropped/dup, heap ±10%. | P3 |
| C-9 | Explain-status per-flag provenance E2E · group copy-for-ticket | R-L3-01, R-AUD-06, TS-DET-14/11 | Backend derivation proven at L4; the rendered evidence view + Markdown copy variants have no E2E. | Playwright spec: open a FAILED instance → "explain this status" shows per-leg request/response + plan choice; copy-for-ticket emits the SPEC §4 line order. | P2 |
| C-10 | Latency budget (P4 / R-NFR-02) | R-NFR-02, TS-TRI-06 | No perf harness in-repo; FIX-REF-01 reference dataset + k6/Gatling assertions unbuilt. | Nightly S2 job seeding FIX-REF-01, asserting the four P95 budgets + ≥99% landing cache hits. | P2 |
| C-11 | Deep-paging cost curve (R-NFR-08) | R-NFR-08, R-SEM-22 | Design-locked, unbuilt; the O(offset) cost near the depth cap is unmeasured — sets the real per-engine cap and its separate (non-R-NFR-02) latency class. | S0 P0 spike measures offset-near-cap query cost per engine on 6.3/6.8/7.1; nightly deep-scroll perf assertion once built. | P3 |
| C-11 | Alarm-threshold arithmetic L1 | R-NFR-04, TS-TRI-02 | Thresholds exercised implicitly in health ITs; the warn/crit boundary math has no `Clock`-driven L1. | L1 over fixed-`Clock`: oldest-executable >5m warn / >15m crit; overdue-timer any=warn, >100=crit; ages floored at 0. | P3 |
| C-12 | Readiness/liveness probe assertions | R-OPS-01 | Registry binding tested; the actuator readiness (PG+registry, NEVER engine) group isn't asserted. | L3: `/actuator/health/readiness` UP with engines down; DOWN with PG down. | P2 |
| C-13 | axe accessibility wired per-spec | R-UXQ-01, TS-E2E-01/04 | axe is named as a CI hard-fail but not attached inside the current e2e specs. | Add `@axe-core/playwright` scan step to each e2e spec + the canonical-arc spec (C-16); fail on serious/critical. | P2 |
| C-14 | Rendered variable edit → verify → CAS-conflict E2E | R-UXQ-13, TS-DET-10/15, TS-VERB-06 | The editor logic is dense at UNIT-FE (`editState`/`diff`) but no spec drives the rendered form→verify modal→409 three-value recovery. | Playwright: edit one json leaf → verify sentence + path diff → out-of-band change → 409 → three-value recovery, no overwrite-anyway control. | P2 |
| C-15 | Error-group acknowledge · leak-view age-from-engine-ts | R-BAU-01/02, TS-TRI-09/10 | Acknowledge persistence/resurface and engine-timestamp age math have no L4 proof. | L4: acknowledge (who/reason/expiry) → collapses "Acknowledged (N)", resurfaces on +threshold/new version, audited; leak-view age from engine `startTime`. | P2 |
| C-16 | **Canonical incident-arc E2E (R4 floor)** | R-TEST-01 R4, TS-E2E-01 | Current e2e specs are isolated per-surface smokes; no single hermetic journey covers triage→search→detail→fix→verify→audit→copy-ticket. | One `canonical-arc.spec.ts`: landing group → drill to filtered grid → open FAILED → why-stuck strip → edit `divisor` 0→1 → retry DLQ → status COMPLETED → audit tab both actions → copy-for-ticket; axe throughout (C-13). Keep hermetic via `page.route`. | **P1** |
| C-17 | Six join-bugs labeled as red-first regressions | R-TEST-02, TS-STAT-15 | `StatusJoinTest` covers the join semantics but the six DESIGN-REVIEW bugs aren't individually named/annotated as the M2a regression set. | Annotate/rename the six covering cases with the DESIGN-REVIEW bug IDs so the M2a gate is legible. | P3 |
| C-18 | Interactive Search-panel E2E | TS-SRCH-06/07 | `saved-views.spec` replays URL state; no spec builds a search *through the form* (type criteria, chips, run, copy-URL round-trip, copy-as-cURL). | Playwright: construct a filter in the panel, assert compiled-criteria echo, copy URL → fresh session reproduces rows, copy-as-cURL body matches. | P2 |

**Priority summary:** P1 (release-gating) open gaps: **C-5** (audit-integrity pool/sweep/REVOKE)
and **C-16** (canonical-arc E2E). Everything else is P2/P3 and maps to the SHOULD-v1.x / perf /
process lanes already scheduled in IMPLEMENTATION-PLAN.

---

## D. How this file is checked

- The two **generated** suites are the live enforcement of "every verb has a row":
  `RbacGuardMatrixTest` (backend, iterates `ActionVerb.values()`) and the `catalog.test.ts`
  TS-VERB-14 block (frontend, iterates `VERBS`). Add a verb without a role floor / badge /
  §5.0 label and CI goes red before this doc is even opened.
- Scenario IDs (`TS-*`) and requirement IDs (`R-*`) are the test-name handles (R-SEM-07); grep
  them across `backend/src/test` + `frontend/src` to spot a scenario with no citing test.
- On any behavior change, update the matching §A/§B row **and** the source doc (spec-sync);
  a closed gap moves from §C to a class name in §A/§B.
