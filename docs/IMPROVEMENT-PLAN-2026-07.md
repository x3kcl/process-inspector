# 🔍 Whole-solution review & improvement plan — 2026-07-09

**Provenance.** Full-codebase review at main `6129a88` (v1 + v1.x + all v2 feature blocks merged:
registry CRUD, IdP/OIDC S1–S6, k-way deep paging, shared team views, CMMN phases 0–3, instance
migration, snapshot/trend store, M4 close-out, self-hosted CI). Method: a **5-seat panel**
(backend architecture · security · frontend/UX · quality/CI/ops · product/docs), each seat
verifying findings against source with file:line evidence, followed by **two external reviews**
of the draft plan — Gemini 2.5-flash (2.5-pro 429-throttled) and Copilot/GPT-4o. This document
is the WHEN for the review's output; findings that change WHAT/HOW get folded into
SPECIFICATION/ARCHITECTURE by the slices below (spec-sync).

**Verdict in one paragraph.** The build quality is high — the corrective-action rails,
fail-closed audit, hot-reload seam, and honesty-first degradation are real in code, not just in
prose, and the codebase is in far better shape than ~60 parallel-session PRs would predict. The
review found **no rot in the doctrine core**. What it found instead: (1) a small set of
genuinely urgent defects where *newer* code diverged from the doctrine the older code enforces
(URI building, re-auth enforcement, read-side scoping, CI teardown); (2) a **docs-truth gap** —
the spec overclaims one security control and underclaims three shipped features; (3) structural
debt concentrated exactly where parallel-session growth predicts (the engine-client god-class,
three error-body shapes, test-support mock accretion); and (4) operability promises
(observability, backups, releases) that are still fiction.

---

## §1 Consolidated findings register

Seat IDs preserved: **F** = backend architecture, **S** = security, **U** = frontend/UX,
**Q** = quality/CI/ops, **D** = product/docs. Severity as assessed by the seat, cross-checked.

### Critical / high

- **F1 — Engine path-whitelist bypass via unencoded URI concat** (HIGH). CMMN +
  external-worker calls build URLs by string concatenation
  (`FlowableEngineClient.java:230-233, 924-961`); BPMN paths correctly use encoding template
  expansion. An attacker-controlled `jobId`/case-id containing `../` re-targets the request to
  an arbitrary path on the engine host **with the BFF's rest-admin credentials** (GET via
  omnibox resolve at VIEWER; POST/DELETE via the CMMN verbs). Violates the "BFF whitelists
  engine paths" iron rule. Fix: `pathSegment()`-encoding URI helpers + inbound id shape
  validation (`^[A-Za-z0-9._:-]{1,64}$`) + a MockWebServer test proving `../`/`?` ids refused.
- **S1 — Dangerous-verb re-auth is defined but never enforced** (HIGH).
  `SessionFreshness.requiresReauth` has **zero call sites** in `src/main`; the `max_age`
  challenge only fires when the SPA voluntarily appends `?reauth`. A stolen session cookie
  (idle 12 h / absolute 24 h) executes tier-3 prod deletes and `ACCESS_ADMIN` grants
  unchallenged. Compounded by **D7**: `REQUIREMENTS-REGISTER.md:71` already marks R-SAFE-07
  "Built (v2)". Fix: server-side interceptor over tier≥3 + bulk-submit + mapping-write
  endpoints returning a 401 `reauth-required` challenge; SPA interstitial replays. *(A parallel
  session is already on this tail — `feature/v2-idp-security-s5-reauth`; coordinate, don't fork.)*
- **S2 — Search/triage reads are scope-blind** (MED-HIGH). `SearchController`/`TriageController`
  carry no authorization beyond `authenticated()`; `ScopeGrant.overlaps` documents "the search
  result set is grant-blind today". A VIEWER scoped to engine-a reads engine-b/tenant-B failing
  instances, exception text, and variables by naming it in the request. Mutations are scoped;
  reads are not. Fix: intersect `request.engineIds()` with `grantsFor(auth)` server-side
  (per-deploy flag for the single-team dev ladder), and update SHARED-VIEWS' "declutter not
  security" caveat in lockstep.
- **Q1 — Observability is documentary fiction** (HIGH). RUNBOOK/OPERATIONS direct the operator
  to `/actuator/health` + `/actuator/prometheus` + shipped alert rules — actuator/micrometer
  are **not dependencies**, `SecurityConfig` permits a path that 404s, `deploy/` has no alert
  rules, and every "security alert fires" is `log.warn` (`SecurityAlertChannel`). Fix: land the
  minimum real thing (actuator + micrometer-prometheus + a webhook alert bean) and spec-sync
  the rest down to "to land".
- **Q2 — 26 of 42 IT classes run in NO CI workflow** (HIGH). All mutating corrective-action
  ITs, the audit fail-closed/partition/purge family, registry reload/store, views, Keycloak,
  snapshot sampler — local-only, and there is no nightly. TEST-STRATEGY's "merge-gating from
  M4" claim is false. Fix: a `schedule:` nightly running the full IT set; promote
  `FailClosedAuditIT` + `CorrectiveActionIT` into a PR-gating matrix leg.
- **Q3 — Frontend CI job leaks its background BFF** (HIGH on the persistent runner). No
  `if: always()` kill step; the stale process holds :8086 and can serve the **previous
  commit's** contract to the next run's drift gate (false-green/false-red). Fix: pidfile +
  always-kill + `timeout-minutes` on all jobs.
- **Q4 — Zero backups of the legally load-bearing audit store** (HIGH). OPERATIONS claims RPO
  ≤5 min WAL/PITR + quarterly restore drills; reality is one local docker volume on the same
  host as everything else. 400-day retention machinery guards data with zero copies. Fix:
  cron'd `pg_dump` to a second disk + committed restore-drill script + honest RPO note.
- **Q5 — CI runner is ephemeral, single, and shares the prod Docker daemon** (MED-HIGH).
  `nohup`-run (a reboot of hp04 silently kills all CI — `sudo ./svc.sh install flapci` is the
  fix and needs the user); one runner serializes every session's push past `ci-watch` budgets;
  `pull_request:` triggers with daemon access are root-equivalent on the box hosting the live
  demo + personal cloud (acceptable only while the repo stays private).

### Medium

- **F2** — `FlowableEngineClient` is a 1,425-line three-context god-class; CMMN/external-worker
  methods hardwire INTERACTIVE priority (no `CallPriority` parameter); telescoping overloads.
- **F3** — DEEP_PAGE isolation is engine-side only: `deepPage` and `aggregate` share the same
  8-slot BFF fan-out semaphore, contradicting the documented "a crafted-cursor flood degrades
  itself, never interactive search" (R-NFR-08).
- **F4** — Three error-body shapes on one API (ProblemDetail+`code` / ad-hoc `{"error":…}` /
  raw `ResponseStatusException` default JSON) undermine the generated-client discipline.
- **F5/F6** — `EngineConfig` telescoping-ctor accretion (14/15/16-arg); `NoDbTestSupport`
  mock accretion incl. a blanket mocked `JdbcTemplate` (a false-green generator) + 12
  `application-it*.yml` profiles replaying the exclusion dance — two conventions, pick one.
- **S3/S4** — `/break-glass` has no rate-limit/lockout and failed attempts are neither audited
  nor alerted; the alert "channel" behind four-eyes collusion + break-glass abuse is a log line.
- **S5** — CSP report-only in the app, absent at the demo nginx edge; HSTS deliberately weak.
- **S6/S7** — `editBaseUrl` lacks the service-side RBAC re-check every sibling mutator has
  (latent fail-open; IT-only today); break-glass audit flag read from thread-local context on
  virtual-thread bulk workers can understate `breakGlass:true`.
- **U1/U2/U3** — Grid→detail is mouse-double-click only (no keyboard/ctrl-click path — view==URL
  violated where it matters most, mid-incident); `ModalShell` under 13 destructive confirms has
  no focus trap/restore (swap to native `<dialog>`); 1.5 MB entry chunk — `/inspect` +
  bpmn-js + AG Grid ship statically in the entry (`main.tsx:8`), Stage-0 users pay it all.
- **U4/U5** — Zero component-level tests (vitest is node-env, logic-only; no spec covers
  Stage-0, InspectPage shell, OpsDrawer/SSE, omnibox, admin pages); the typed-token/reason
  guard ladder is copy-pasted across 7–13 modals and already drifting.
- **U7/U8/U10** — Omnibox ARIA is a broken listbox; the promised axe gate never landed
  (no jsx-a11y, no @axe-core/playwright); one raw `fetch()` bypasses the client middleware.
- **Q7/Q8** — No release mechanism (no tags, image built `push: false`, demo deploys from
  working tree, rollback = rebuild-while-down); coverage floors claimed "gating from M3" with
  no jacoco/vitest-coverage anywhere.
- **F7/F8/F9/U6/U9/Q9/Q10** — smaller: businessKeyLike canary uncached per search; copy-pasted
  row-parsing helpers; stale client javadoc; dark theme/density/column chooser still open on a
  3,128-line untokenized stylesheet; query keys as scattered literals; OPERATIONS §8 stale both
  directions; only one CI job has a timeout.

### Docs drift (D1–D12, all verified)

Headers claim "unbuilt" for three shipped features (IMPLEMENTATION-PLAN shared-views/registry/
IdP; SHARED-VIEWS/REGISTRY-CRUD/IDP-SECURITY/KWAY-PAGING status lines); TRACEABILITY-MATRIX
missing rows for R-SAFE-14/15, R-OPS-16 and stale for R-SEM-22/23/24, R-SAFE-16, R-NFR-08;
REQUIREMENTS-REGISTER stale on nine rows **and overclaiming R-SAFE-07 (D7 — the dangerous
direction)**; ARCHITECTURE §2.4 + endpoint tables past-tense wrong; SPECIFICATION §12 still
lists shipped features as demand-gated triggers (the duplicate-search-string demand signal was
never built — retire the gate as historical); RUNBOOK §2d describes the pre-DB grants flow;
OPERATIONS §8 lists landed gates as "still to land" and nothing documents the self-hosted
runner; OPERATOR-QUICK-START missing team views, Load-more, and all admin surfaces;
REGISTRY-CRUD §441 still defers the landed forward-user config; C-10 denies the existing
(unwired) k6 script.

---

## §2 The plan — risk tiers, not themes

Both external reviewers rejected strict thematic sequencing: pull the highest-risk items
forward regardless of theme. Adopted. Every slice is independently mergeable, lands with its
tests + spec-sync in the same PR, and follows green-ci.

### P0 — stop the bleeding *(days; ~6 small PRs)*

1. **URI-encoding hotfix (F1)** — ✅ **LANDED** (`feature/p0-uri-encoding`). The six CMMN
   sibling-context id-in-path calls (`getCmmnDeadLetterJob`, `getCmmnCaseDefinition`,
   `getCmmnCaseInstance`, `getHistoricCmmnCaseInstance`, `moveCmmnDeadLetterJob`,
   `deleteCmmnDeadLetterJob`) + the `deploymentId` concat now route the id through the
   RestClient `{id}` template (TEMPLATE_AND_VALUES percent-encoding — the SAME mechanism the
   process-api lanes already used), fronted by a `FlowableEngineClient.safeId()` boundary
   guard (`[A-Za-z0-9._:-]{1,128}`, no `..`) so a `/`/`?`/`#`/traversal id is a 400 before any
   engine byte. WireMock tests prove traversal/reserved-char ids are refused *without dialling*
   and a valid id reaches the exact `/cmmn-api/...` path. External-worker `/jobs` list carries
   no path id (already safe). *Shipped alone, first.*
2. **CI hygiene fix-pack (Q3, Q10)** — ✅ **LANDED** (`feature/p0-ci-hygiene`).
   `timeout-minutes` on lint/unit/frontend/docker (Q10 was the live risk — only `integration`
   had one, so a hung job on the single serialized runner would block every parallel session
   for the 6 h default) + a defensive `if: always()` BFF reap in the frontend job (pidfile).
   **Correction on Q3:** the leaked-BFF *cross-run* poisoning the review flagged was already
   closed by PR #67's dockerized runner (`EPHEMERAL=true` + `restart: unless-stopped` = one
   job per container, torn down between jobs — a leaked process can't survive into the next
   run). The reap is retained as within-job hygiene, not a fix for a live bug.
3. **Runner persistence (Q5)** — ✅ **MOOT / RESOLVED by PR #67.** The review's fix
   (`sudo ./svc.sh install`) was for the bare-metal nohup runner. PR #67 replaced it with a
   *dockerized* runner (`docker/ci-runner`, `restart: unless-stopped`) that survives reboots
   with no sudo action. Residual (unchanged): still a single serialized runner, and
   `pull_request:` with daemon access stays acceptable only while the repo is private.
4. **Audit-store backup (Q4)** — ✅ **LANDED** (`feature/p0-audit-backup`).
   `deploy/backup-audit-db.sh` (`pg_dump -Fc` → second-disk `PI_BACKUP_DIR`, checksummed,
   retention-pruned, `.partial`→rename so a crashed dump never masquerades as good) +
   `deploy/restore-drill.sh` (restores the latest dump into a **throwaway** PG, asserts
   `audit_entry` returns partitioned with partitions+rows — the drill made executable) +
   `deploy/systemd/pi-audit-backup.{service,timer}` (nightly). **Both scripts verified
   end-to-end** against a throwaway partitioned table (backup→checksum→restore→PASS).
   OPERATIONS §4 corrected to the **honest RPO = 24 h** (the nightly interval), not the ≤5-min
   WAL/PITR it claimed — continuous PITR is the noted follow-up; this closes "no copy at all".
5. **Nightly full-IT workflow (Q2)** — ✅ **LANDED** (`feature/p0-nightly-its`).
   `.github/workflows/nightly.yml` (`schedule` 02:00 UTC + `workflow_dispatch`, NOT
   merge-blocking) runs the 25 local-only ITs the PR gate skips, split by infra: a
   **container-its** job (no compose — the audit partition/retention/roles + fail-closed,
   shared/team-view + governance, view/mapping/access-admin/registry stores, and the
   real-Keycloak OIDC IT, all Testcontainers-self-provisioned via `@DynamicPropertySource`)
   and an **engine-its** job (the ci.yml flowable-6+postgres compose harness — the mutating
   corrective-action/flow-surgery/migration/bulk rails + seed-driven detail/timeline/snapshot).
   16 (PR gate) + 25 (nightly) + 1 abstract base = the full 42. Shake out via `workflow_dispatch`
   before relying on it; a red nightly is a morning-routine triage, not a push gate.
6. **Truth hotfixes (D7 + S6)** — ✅ **LANDED** (`feature/p0-truth-hotfixes`), reshaped:
   - **D7 is now MOOT.** The plan's D7 assumed S1 (dangerous-verb re-auth) was unenforced, so
     `REQUIREMENTS-REGISTER` marking R-SAFE-07 "Built" was an overclaim. But parallel sessions
     closed S1 on 2026-07-10 (S5c/S5d/S6b, PRs #63–66): freshness is now enforced in
     `CorrectiveActionService` (tier-3), `BulkJobService` (bulk), surfaced as a 401
     `reauth-required` (`ActionExceptionHandler` + `ReauthRequiredException`). R-SAFE-07 is
     therefore **accurate** — downgrading it would introduce a falsehood, so it is left as-is.
   - **S6 landed:** `EngineRegistryStore.editBaseUrl` (the S3 reload seam, IT-only) now takes an
     `Authentication` and re-checks `requireRegistryAdmin` first — the one store mutator that
     skipped the door-AND-service RBAC re-check its S4 siblings all have. Rung-1 test proves a
     non-admin is refused before the row is even loaded (no lookup/audit/write/reload event); the
     reload IT passes a `ROLE_REGISTRY_ADMIN` principal. (The ArchUnit "every mutator re-checks"
     rule is deferred — a focused reject-test per mutator is the practical guard; ArchUnit can't
     easily assert "method A calls method B".)

### P1 — close the security tail + make the docs true *(1–2 weeks)*

7. **Enforce SessionFreshness (S1)** — server-side 401 `reauth-required` on tier≥3 /
   bulk-submit / mapping writes + `auth_time` validation + SPA interstitial.
   **In-flight in a parallel session** (`feature/v2-idp-security-s5-reauth`) — this plan
   assigns it there; do not double-build.
8. **Scope-filtered reads (S2)** — `grantsFor(auth)` intersection in Search/Triage behind a
   per-deploy flag (default on under oidc); SHARED-VIEWS caveat updated; rung-1 authorizer
   tests.
9. **Real alert channel + break-glass throttle (S3, S4)** — env-ref webhook bean (oidc
   profile, absence = boot warning); failure-count lockout + failed-attempt audit/alert on
   `/break-glass`; break-glass flag derived from `Authentication` on bulk workers (S7).
10. **Edge hardening (S5)** — flip CSP to enforce per-deploy after report-only observation;
    mirror the BFF header set in the demo nginx; fix demo HSTS.
11. **Docs true-up sweep (D1–D12)** — one docs-only PR; the register/matrix/plan/runbook/
    quick-start all reconciled to main; OPERATIONS §8 rewritten as the actual gate list.
12. **Observability minimum (Q1)** — ✅ **LANDED** (`feature/p1-observability`), scoped to the
    verifiable core. Added `spring-boot-starter-actuator` + `micrometer-registry-prometheus`;
    `/actuator/health` (+ liveness/readiness probes; readiness gates on the `db` indicator so a
    BFF that can't reach its audit Postgres isn't routed traffic) and an auth-gated
    `/actuator/prometheus` are now **real** (both 404'd before). `resilience4j_circuitbreaker_state`
    + bulkhead / Hikari / HTTP / JVM metrics come free from the registry — the breaker/lane signal
    RUNBOOK §7 wants. Rung-3 `ActuatorEndpointsSpringTest` proves health is unauthenticated, the
    scrape is gated + emits real metrics, and — the drift-gate guard — actuator paths do **not**
    leak into `/v3/api-docs`. **Honesty (no fiction either way):** the *named custom* app metrics
    (`audit_insert_failures_total`, per-engine fan-out latency, SSE/bulk gauges), structured-JSON
    logs + `correlationId`, `GET /api/diag`, and the `deploy/` alert-rule files are marked **TO
    LAND** in OPERATIONS §2/§3 + RUNBOOK §2b — a Micrometer-counter-per-site follow-up.
    Config gotchas recorded: `exposure.include`/health-group `include` need YAML **list** form
    (a space after the comma left `" prometheus"` unexposed); `@SpringBootTest` disables metrics
    export → the test needs `@AutoConfigureObservability`; the `db` readiness member is optional in
    docker-free profiles → `validate-group-membership: false` so all 10 DataSource-excluded IT
    profiles still boot.
13. **DEEP_PAGE BFF isolation (F3)** — ✅ **LANDED** (`feature/p1-deeppage-isolation`).
    `SearchService.deepPage` now acquires from a dedicated `deepPageSlots` semaphore (sized
    `max(1, fanoutParallelism/2)`) instead of the shared 8-permit `engineSlots`, so a scroller
    or crafted-cursor flood queues on the deep-page budget and can never occupy an interactive
    slot — the engine-side R4j `DEEP_PAGE` lane only throttled *after* a BFF slot was taken, so
    sharing `engineSlots` was the real starvation path. Rung-2 contention test drains the
    deep-page budget and proves a parked `deepPage` leaves `engineSlots` fully available and
    dials no engine. Makes the R-NFR-08 / KWAY-PAGING do-no-harm claim true at the BFF layer.
14. **Playwright + axe into the PR gate (U8, Q9)** — the 14 hermetic specs + an
    `AxeBuilder` pass per spec run in the frontend CI job (browsers cached on the runner);
    prerequisite fixes U1/U2/U7 land first so the gate starts green (see P2 #17).

### P2 — structural debt *(2–4 weeks, interleavable)*

15. **Engine-client split (F2, F9)** — `ProcessApiClient` / `CmmnApiClient` /
    `ExternalJobApiClient` facades over one `GuardedCaller` core; uniform `CallPriority`
    first-param; delete telescoping overloads; fix stale javadoc. *Do this BEFORE #16/#18
    consumers (Copilot's sequencing catch).*
16. **One error contract (F4)** — global advice mapping everything onto ProblemDetail+`code`;
    `spring.mvc.problemdetails.enabled`; `gen:api` regen; frontend `ApiError` simplification.
17. **Frontend fitness pack (U1, U2, U3, U5)** — lazy `/inspect` + `manualChunks`
    (target: halve the 1.5 MB entry); native-`<dialog>` `ModalShell`; grid "Open" `<Link>`
    column + Enter-to-open; `useProdGuard` + `<GuardFields>` extraction across the 13 modals.
18. **Component-test harness (U4)** — vitest jsdom project + testing-library; first targets:
    Shell 401 gating/BreakGlassBanner, ResultsGrid zero-state ladder, StatusChip.
19. **Test-support consolidation (F5, F6, Q8)** — one docker-free convention (profile-gated
    DB beans, retire the blanket `JdbcTemplate` mock), `EngineConfig` builder/test-factory,
    jacoco + vitest coverage thresholds (or amend TEST-STRATEGY to aspirational — no
    unfalsifiable floors).
20. **Registry S4b (deferred from CRUD build)** — four-eyes on registry writes (reuse the V14
    proposal pattern) + socket connect-time IP-pinning closing the validate→connect TOCTOU.
21. **Release mechanics (Q7)** — GHCR push on green main (buildx already wired), demo compose
    pins by digest, rollback = repoint; git tags per deploy.
22. **Supply-chain gates** *(external-review addition)* — Trivy image scan + SBOM + dependency
    audit (OWASP dep-check / `npm audit`) in the nightly; k6 P1 wiring + FIX-REF-01 dataset
    (C-10/C-11); smaller F7/F8/U9/U10 cleanups ride along as they touch files.

### P3 — product roadmap *(separate track; external reviewers: keep out of remediation)*

- **Usability-testing loop** over the six unproven v2 surfaces (team views, Load-more,
  MigrateModal, `/admin/engines`, `/admin/access`, break-glass banner) — the skill exists and
  is an explicit pre-pilot promise.
- **Person-centric task search** (unscheduled since v1.x #6; long-standing operator ask).
- **Dark theme / density / column chooser** (R-UXQ-08/09) — CSS token layer first (three PRs).
- **Remediation playbooks (the v2 headline)** — honest gating this time: an S0 audit-mining
  measurement slice against pilot audit rows decides whether the R-GOV-08 trigger fires; the
  playbook test charter (R-TEST-09) is a build precondition. Then record-from-exemplar MVP →
  replay-as-bulk-job.
- Backlog beyond (unranked): batch migration + mapping wizard, INVERTED-plan deep paging,
  watchlists/shift delta, CSV export, ticket webhook, remaining FIX-PROC seeds, external
  pentest before any real pilot (external-review addition).

---

## §3 External-review disposition

**Adopted:** risk-tier restructure (both); backups + nightly ITs + re-auth enforcement +
read-scoping elevated to P0/P1 (both); docs true-up split — overclaim now, sweep as P1, not a
blocking first act (Gemini); product track separated from remediation (Gemini); backend error
contract before frontend consumers, engine-client split before its consumers, re-auth assigned
to the in-flight parallel session with schemas locked first (Copilot); supply-chain scanning +
dependency audit + pre-pilot pentest added (Gemini).

**Rejected, with reasons:** "drop Prometheus, micrometer alone" (Copilot) — the RUNBOOK's
alert rows are the point; metrics without a scrape target keep the fiction. "Drop the lazy-load
/ bundle work as premature optimization" (Copilot) — a 1.5 MB entry on the *triage landing* is
an incident-response latency issue, not vanity perf; it stays P2. "General API rate limiting"
(Gemini) — the per-engine bulkheads + DEEP_PAGE lane + bulk permit pool already bound the
expensive paths; a blanket limiter on an internal authenticated BFF is scope creep — revisit
only if exposure changes. "Cut H7 entirely" (Gemini) — kept as an explicitly separate P3 track
instead; this repo's demand-gate history shows unscheduled work gets built anyway, so the plan
should say where it belongs.

**Parallel-session coordination.** Re-auth (S1/#7) is owned by `feature/v2-idp-security-s5-reauth`.
The P0 URI hotfix touches `FlowableEngineClient` — land it before the P2 client split starts,
and keep the split in its own worktree. The docs sweep (#11) rewrites IMPLEMENTATION-PLAN
sections other sessions cite — do it in one sitting, merge fast.
