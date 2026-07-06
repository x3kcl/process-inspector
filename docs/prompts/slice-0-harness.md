# Slice 0 prompt — engine harness, CI gates, client base

*The first implementation prompt of the roadmap. Paste into a fresh Claude Code session in
this repo. Scoped to be completable in one session; everything it needs is referenced, not
inlined.*

---

Implement **Slice 0: the M2a entry gate** — the test/CI harness everything else is built
red-first against. No search-logic changes in this slice.

**Read first:** `CLAUDE.md`, then `.claude/skills/engine-harness/SKILL.md` and
`.claude/skills/validate-bpmn/SKILL.md`, then `docs/TEST-SCENARIOS.md` §1 (the FIX-*
fixture catalog) and `docs/OPERATIONS.md` §8 (the CI gate table). Reference for decisions:
`docs/SPECIFICATION.md` §10 (stack pins), `docs/ARCHITECTURE.md` §3 (registry fields).

**Deliverables:**
1. **Compose profiles** under `docker/`: extend the existing `docker-compose.dev.yml`
   family to three engine profiles — `6x` (current 6.8), `legacy` (a pre-6.4 flowable-rest
   image — verify an available tag and record it), `7x` (flowable/flowable-rest 7.x).
   Pairwise-distinct host ports. Document profile usage in the README quick start.
2. **Seed processes** under `docker/processes/` per TEST-SCENARIOS §1.1: every FIX-PROC
   seed authored per the `validate-bpmn` skill (DI mandatory, stable keys,
   `failedJobRetryTimeCycle="R1/PT1S"` for fast-DLQ fixtures, `"R10/PT1H"` for the pinned
   RETRYING fixture, the self-recursive `demoNested` for hierarchy depth, the expression
   error-corpus tasks). Plus an idempotent `docker/seed.sh` (deploy → start → mutate,
   strictly over REST; safe to re-run; per-fixture flags).
3. **Fixture capture script** `docker/capture-fixtures.sh`: runs against a live profile,
   captures error-JSON payloads + capability-probe responses into
   `backend/src/test/resources/error-signatures/<major>/` and
   `.../capability-snapshots/<major>/`, stamped with the image tag.
4. **Dockerfile** (repo root): the SPEC §10 multi-stage build (Vite build → jar `static/`
   → `eclipse-temurin:21-jre` layered jar; non-root, no shell extras). The frontend build
   may produce today's minimal SPA — the packaging shape is the deliverable.
5. **CI workflow** `.github/workflows/ci.yml` implementing the OPERATIONS §8 gates that
   exist today: backend build + unit tests, Spotless check, ESLint + Vitest, frontend
   build, image build + Trivy scan (fail on fixable HIGH/CRITICAL), and an
   integration job that boots the `6x` profile, runs `seed.sh`, and executes the
   engine-harness integration tests. Add a nightly workflow stub that runs the same
   integration job on all three profiles. (OpenAPI-diff and Playwright gates arrive in
   later slices — leave clearly-marked placeholders, not fake passes.)
6. **Client base hardening** in `backend/`: (a) engineId slug validation
   `^[a-z0-9][a-z0-9._-]{0,63}$` failing fast at startup naming the offender (R-SEM-08) —
   red-first unit test; (b) `write-ms` added to registry timeouts (R-NFR-07), default =
   read-ms, wired into `FlowableEngineClient` for mutating calls; (c) the `RestClient`
   never follows redirects; (d) a test-profile registry (`application-test.yml`) with
   `dlq-scan-cap: 50`, `max-page-size: 10`, and the same engine registered twice as
   `environment: dev` and `environment: prod` (guard-ladder fixture).
7. **Spotless + ESLint config** wired as build failures (they're pinned in SPEC §10 but
   not yet configured).

**Register IDs discharged:** R-SEM-08, R-NFR-07, R-TEST-04 (catalog realized), R-TEST-07
(hooks: caps, retry cycles, dual registration), R-OPS-06 (gate table partially live —
update IMPLEMENTATION-PLAN's M0 status note when the workflows exist).

**Iron rules that bite in this slice:** never mock Flowable for join logic; never insert
into `ACT_*` tables — seeding is REST-only, speed comes from `R1/PT1S`; poll-with-deadline,
never fixed sleeps; BPMN needs diagram interchange or M5's viewer renders nothing; scoped
test commands in the loop (`mvn -o test -Dtest=…`), full suite before done.

**Exit gate:** `docker/seed.sh` green on all three profiles locally; every FIX-* row in
TEST-SCENARIOS §1.1 manufactured and verified by a passing integration test; capture
script has produced committed 6.x and 7.x fixture sets; CI workflow green on this branch;
`mvn verify` + `npm run build` green; docs updated per the `spec-sync` skill (README
profile instructions, IMPLEMENTATION-PLAN M0 status note, TEST-SCENARIOS status line).
