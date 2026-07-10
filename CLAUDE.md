# Process Inspector — project instructions

A multi-engine Flowable Process Inspector: Spring Boot 3 BFF (`backend/`) + React/TS SPA
(`frontend/`), integrating with Flowable engines **strictly via the V6/V7 REST API**.

## The docs are the spec — keep them in lockstep
`docs/SPECIFICATION.md` (WHAT) · `docs/ARCHITECTURE.md` (HOW/WHY) · `docs/IMPLEMENTATION-PLAN.md`
(WHEN) · `docs/DESIGN-REVIEW.md` (provenance). Any behavior change updates the matching doc
sections in the same change — see the `spec-sync` skill. Read the relevant skill in
`.claude/skills/` before touching its territory (`flowable-rest` for any engine call,
`corrective-actions` for any mutating endpoint, `engine-harness` before integration tests).

## Stack pins (ADR-001, SPECIFICATION §10)
- Java **21** / Spring Boot **≥3.5** / Maven. Blocking + **virtual threads**; no WebFlux,
  no preview APIs. `RestClient` per engine, wrapped in **Resilience4j** circuit breaker +
  bulkhead (do-no-harm, SPEC §2). JPA + Flyway + Postgres 16; Caffeine for the triage cache.
- React **18** + TypeScript **≥5** `strict` + Vite, Node 22. TanStack Query v5,
  React Router v7, AG Grid Community, bpmn-js.
- API contract: springdoc-openapi (`GET /v3/api-docs` on the running BFF) → generated
  `frontend/src/api/schema.d.ts` via `npm run gen:api` (committed; regenerate after DTO
  changes; all calls go through the singleton `openapi-fetch` client in
  `frontend/src/api/client.ts`). Never hand-edit generated types; never hand-write a
  parallel DTO or fetch wrapper.

## Build & test — SCOPED commands only in the iteration loop
```bash
# backend fast loop (seconds):
cd backend && mvn -o test -Dtest=TheClassYouAreDriving
# backend full (before claiming done; needs the full engine matrix up — engine-harness):
cd backend && mvn spotless:apply && mvn verify
# frontend:
cd frontend && npm test          # vitest
cd frontend && npm run build     # type-check + build
# engines for integration work:
docker compose -f docker/docker-compose.dev.yml up -d   # :8081/:8082, rest-admin/test
```
Never run the full test suite inside a red-green loop; never mock Flowable responses for
join logic (dockerized engine only — `engine-harness` skill).

## Dev tooling — MCP (`.mcp.json`, all dockerized)
- `inspector-postgres` (crystaldba/postgres-mcp, `--access-mode=restricted` = read-only):
  the BFF's OWN dev Postgres (compose `postgres` — audit/bulk/notes store). Audit-chain and
  bulk-job introspection, `explain_query`, index advice, db-health. DEV TOOLING ONLY: never
  touches engine (`ACT_*`) databases — engine state stays REST-only — and has no write path
  (the audit trigger would reject one anyway). Needs the compose stack up; override via
  `INSPECTOR_DB_URI`.
- `playwright` (mcr.microsoft.com/playwright/mcp, host network): drives the real rendered
  UI (Vite :5173 / BFF :8085) — built for the `usability-testing` skill's tester agents and
  UI smokes. Snapshots/screenshots land in `.playwright-mcp/` (gitignored). Remember the
  dev sign-in ladder users (pw `dev`).
- `github` (ghcr.io/github/github-mcp-server): PR/issue workflows against
  `x3kcl/process-inspector` (`gh` CLI is not installed on this box). INERT until
  `GITHUB_PERSONAL_ACCESS_TOKEN` is exported in the environment that launches the session —
  the token is env-ref only, never in config (iron rule).

## Iron rules
- Schema comes from Flyway ONLY: `V1__init.sql` before any JPA entity; `ddl-auto=validate`
  in every profile including tests. Never auto-DDL.
- Never insert rows into engine (`ACT_*`) tables — not even in tests. Seed strictly over
  REST (TEST-STRATEGY §10); speed via `R1/PT1S` retry cycles, scale via lowered caps/stubs.
- Stage 0 aggregations use count-only/`size=1` queries and the dedicated DLQ scan — never
  the grid-search plan.
- `Thread.sleep()` in any test = hard failure (ArchUnit-enforced). Time-dependent asserts
  use Awaitility with explicit bounds against REAL engine/BFF state; never poll a mutation.
- The BFF whitelists engine paths; no generic proxy route, ever.
- Every mutating endpoint follows ALL the `corrective-actions` rails (audit, RBAC tier,
  guard, no auto-retry, per-item bulk reporting).
- Status is derived per ARCHITECTURE §2.3 — never a single unpaged DLQ fetch; never render
  a status derived from truncated data without the badge.
- Secrets only via env refs (`password-ref`); never in config values, logs, or responses.
- Never select, restyle, hide, or remove the bpmn.io watermark (`.bjs-powered-by`) —
  license term (R-GOV-05), enforced by `frontend/scripts/check-bpmn-watermark.mjs` in
  `npm run build`.
- Spotless + ESLint are CI hard failures — run them before finishing.
- A commit to `main` is not done until GitHub Actions is **green on its SHA** — a successful
  `git push` is NOT success. Follow the `green-ci` skill: mirror the gates locally
  (`scripts/ci-local.sh`), push, then block on the real run (`scripts/ci-watch.sh <sha>`,
  backgrounded) and fix any red in the SAME session. Red `main` blocks every parallel session.
- CI runs on **dockerized self-hosted runner slots** (`docker/ci-runner/` — each slot owns
  a disjoint harness-port block; jobs serialize within a slot, parallelize across slots).
  Before pushing to `main` or creating a PR, run `scripts/ci-runner.sh ensure` — it starts
  the slots if down and fails if a foreign (slot-less) runner is online; with no runner
  online every CI run just queues forever.
