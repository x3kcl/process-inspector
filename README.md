# Flowable Multi-Instance Process Inspector

A centralized management plane (BAW-Inspector-style) to search, troubleshoot and **fix** process
instances across **multiple independent Flowable engines** — strictly via the Flowable V6 REST API.

## Documents

**Using the Inspector** (operator-facing):

| File | Content |
|---|---|
| [docs/PRODUCT-GUIDE.md](docs/PRODUCT-GUIDE.md) | **The user-facing manual**: roles, every surface (triage landing, incidents, search, instance/case detail, bulk, admin), how to read the honesty markers, and which actions exist at which role floor |
| [docs/tutorials/](docs/tutorials/) | Eight task-based walkthroughs — sign in to the demo (https://pi.naumann.cloud, ladder users, password `dev`) or the local dev compose and follow the numbered steps |
| [docs/OPERATOR-QUICK-START.md](docs/OPERATOR-QUICK-START.md) | Ten minutes of onboarding before your first shift; pair with [docs/AUDIT-ATTRIBUTION.md](docs/AUDIT-ATTRIBUTION.md) before your first mutation |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) · [docs/OPERATIONS.md](docs/OPERATIONS.md) | Operating the Inspector itself: health, recovery, break-glass, threat model, CI gates |

**Design & engineering** (the docs are the spec):

| File | Content |
|---|---|
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | The product spec (design principles, status model, three-stage UI, verb catalog, guard ladder, release train) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Multi-instance design: BFF topology, composite IDs, fan-out with partial results, the corrected FAILED/dead-letter status join, **Engine Registry data model** |
| [docs/IMPLEMENTATION-PLAN.md](docs/IMPLEMENTATION-PLAN.md) | Milestones M0–M6 + v1.x/v2 release train, module by module |
| [docs/DESIGN-REVIEW.md](docs/DESIGN-REVIEW.md) | Provenance: research, the four-seat panel (v2.0), tech-stack ADRs (v2.2), the 14-seat board (v3.0) |
| [docs/REQUIREMENTS-REGISTER.md](docs/REQUIREMENTS-REGISTER.md) | All board-accepted requirements, ID'd and prioritized (MUST-v1 / SHOULD-v1.x / COULD-v2) |
| [docs/TEST-STRATEGY.md](docs/TEST-STRATEGY.md) | Risk-ranked coverage floors, milestone gates, defect taxonomy, perf/security plans, testability hooks |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Running the Inspector itself: health, telemetry, recovery, threat model, CI gates, runbook index |

## Repo layout

```
backend/    Spring Boot 3 BFF — Engine Registry, health probe, fan-out search aggregator (M1+M2)
frontend/   Vite + React + TS — Search panel, AG-Grid results, split-pane shell (M2)
docker/     Dev harness: compose profiles, seed BPMN (docker/processes/), seed.sh (M0)
```

## Quick start

```bash
# 1. Two demo Flowable 6.8 engines on :8081/:8082 (the default profile, flowable-6 —
#    selected by docker/.env COMPOSE_PROFILES; the CLI --profile flag overrides it)
docker compose -f docker/docker-compose.dev.yml up -d

# Optional extras:
docker compose -f docker/docker-compose.dev.yml --profile flowable-7 up -d   # Flowable 7.1 on :8083
docker compose -f docker/docker-compose.dev.yml --profile legacy up -d       # Flowable 6.3.1 on :8084 (pre-cliff)
docker compose -f docker/docker-compose.dev.yml --profile postgres up -d     # BFF DB (M4) on :5433

# 2. Seed the demo catalog (idempotent deploys, REST-only). No-arg mode auto-discovers
#    and seeds EVERY reachable engine (:8081-:8084): completed, organically dead-lettered,
#    pinned-RETRYING, active-on-user-task, suspended, timer-stuck and failing-child
#    (failedInSubprocess) instances.
bash docker/seed.sh                                               # all reachable engines
bash docker/seed.sh http://localhost:8082/flowable-rest/service   # or exactly one

# 3. BFF on :8085 (engine credentials come from the environment, never from config)
export ENGINE_A_PASSWORD=test ENGINE_B_PASSWORD=test
cd backend && mvn spring-boot:run

# 4. UI on :5173 (proxies /api to the BFF)
cd frontend && npm install && npm run dev
```

Registered engines live in `backend/src/main/resources/application.yml` under `inspector.engines`
(see ARCHITECTURE.md §3 for the full field reference). `GET :8085/api/engines` is the Stage 0
health strip: reachability, version, capability flags, the four job-lane counts and the
executor-starvation alarms per engine.

### Docker image

```bash
# Multi-stage build (maven builder → JRE 21 alpine, runs as non-root, port 8080):
docker build -t process-inspector .
docker run -p 8080:8080 -e ENGINE_A_PASSWORD=test -e ENGINE_B_PASSWORD=test process-inspector
```

### Released images (Docker Hub + ghcr.io)

Every `v*` tag publishes versioned images to **both** registries — the BFF (Spring Boot)
and the web tier (nginx: SPA + `/api` proxy) — plus a GitHub Release with a quick-start
compose attached (`.github/workflows/release.yml`, OPERATIONS §5):

| Image | Docker Hub | GHCR |
|-------|------------|------|
| BFF   | `docker.io/x3kcl/process-inspector-bff` | `ghcr.io/x3kcl/process-inspector-bff` |
| Web   | `docker.io/x3kcl/process-inspector-web` | `ghcr.io/x3kcl/process-inspector-web` |

```bash
curl -LO https://github.com/x3kcl/process-inspector/releases/latest/download/docker-compose.release.yml
INSPECTOR_DEV_PASSWORD=pick-one docker compose -f docker-compose.release.yml up -d
# UI on :8080; pulls from Docker Hub by default — PI_REGISTRY=ghcr.io/x3kcl to use GHCR.
# Point INSPECTOR_ENGINE_{A,B}_BASE_URL + ENGINE_{A,B}_PASSWORD at your engines
```

New versions are cut from the Actions tab (`cut-release` → patch/minor/major — green-CI
gated, auto-computes the next semver, tags, publishes, drafts the Release), and every green
`ci` run on `main` additionally publishes moving `:edge` + `:sha-<short>` images
(`publish-edge.yml`) for tracking the latest verified build.

### Backend tests

```bash
cd backend
mvn test      # unit ladder (rungs 1–3 + ArchUnit no-sleep rule) — no docker needed
mvn spotless:apply   # format (palantir style) — spotless:check is a CI hard failure
mvn verify    # + dockerized integration tests (*IT) — requires the FULL engine matrix:
#   docker compose -f docker/docker-compose.dev.yml --profile flowable-6 \
#     --profile flowable-7 --profile legacy up -d
# (EngineHealthIT → 6.8 :8081, EngineHealth7IT → 7.1 :8083, EngineHealthLegacyIT → 6.3.1 :8084;
#  a down engine fails loudly with the compose command — never a silent skip)
```

### Frontend tests & codegen

```bash
cd frontend
npm test             # vitest (URL-state codec, partial-results/zero-state derivations)
npm run lint         # ESLint strict-type-checked — CI hard failure
npm run format:check # Prettier — CI hard failure
npm run build        # watermark + no-enterprise guards, tsc, vite build
npm run gen:api      # regenerate src/api/schema.d.ts from the RUNNING BFF's /v3/api-docs
                     # (commit the result after any backend DTO change)
```

Sign-in in dev uses the BFF's built-in ladder users (`viewer`/`responder`/`operator`/`admin`,
password `dev` unless `INSPECTOR_DEV_PASSWORD` is set).

## Status

Feature-complete through the v2 release train (SPECIFICATION §12): the three-stage UI
(`/` triage landing · `/search` with deep paging · `/inspect/{engineId}/{id}` full-page
detail), the corrective-action verb catalog behind the guard ladder + fail-closed audit,
tracked bulk operations with SSE progress, saved/shared views, person task search
(`/tasks`), the CMMN case detail route, single-instance migration, registry + access
administration (`/admin/engines`, `/admin/access`), break-glass, and the incident ledger
(`/incidents`). Per-slice provenance: [docs/IMPLEMENTATION-PLAN.md](docs/IMPLEMENTATION-PLAN.md).

## License

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE) and
[NOTICE](NOTICE). Apache-2.0 was chosen to match the project's dependency ecosystem
(Spring, Flowable) and for its explicit patent grant.

This codebase was developed with substantial AI assistance (Anthropic's Claude) under
human direction and review. Anthropic's terms assign output rights to the customer;
under Swiss copyright law purely machine-generated fragments may not attract copyright,
so the license applies to all protectable subject matter without warranting copyright
subsistence in any individual fragment (details in NOTICE).

Third-party note: bpmn-js, when bundled, is used under the
[bpmn.io license](https://bpmn.io/license/) — the "Powered by bpmn.io" watermark must
remain visible and unmodified. This is build-enforced: `npm run build` fails if any
frontend source references the watermark element (`frontend/scripts/check-bpmn-watermark.mjs`).
