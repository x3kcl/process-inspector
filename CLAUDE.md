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
- API contract: springdoc-openapi → generated `types.gen.ts` via `openapi-typescript`
  (committed; regenerate after DTO changes — CI fails on diff). Never hand-edit generated
  types; never hand-write a parallel DTO.

## Build & test — SCOPED commands only in the iteration loop
```bash
# backend fast loop (seconds):
cd backend && mvn -o test -Dtest=TheClassYouAreDriving
# backend full (before claiming done):
cd backend && mvn verify
# frontend:
cd frontend && npm test          # vitest
cd frontend && npm run build     # type-check + build
# engines for integration work:
docker compose -f docker/docker-compose.dev.yml up -d   # :8081/:8082, rest-admin/test
```
Never run the full test suite inside a red-green loop; never mock Flowable responses for
join logic (dockerized engine only — `engine-harness` skill).

## Iron rules
- The BFF whitelists engine paths; no generic proxy route, ever.
- Every mutating endpoint follows ALL the `corrective-actions` rails (audit, RBAC tier,
  guard, no auto-retry, per-item bulk reporting).
- Status is derived per ARCHITECTURE §2.3 — never a single unpaged DLQ fetch; never render
  a status derived from truncated data without the badge.
- Secrets only via env refs (`password-ref`); never in config values, logs, or responses.
- Spotless + ESLint are CI hard failures — run them before finishing.
