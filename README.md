# Flowable Multi-Instance Process Inspector

A centralized management plane (BAW-Inspector-style) to search, troubleshoot and **fix** process
instances across **multiple independent Flowable engines** — strictly via the Flowable V6 REST API.

## Documents

| File | Content |
|---|---|
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | The product spec **v2.0** (design principles, status model, three-stage UI, verb catalog, guard ladder, release train) |
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
docker/     Two demo flowable-rest engines for local development (M0)
```

## Quick start

```bash
# 1. Two demo Flowable engines on :8081/:8082
docker compose -f docker/docker-compose.dev.yml up -d

# 2. BFF on :8085 (engine credentials come from the environment, never from config)
export ENGINE_A_PASSWORD=test ENGINE_B_PASSWORD=test
cd backend && mvn spring-boot:run

# 3. UI on :5173 (proxies /api to the BFF)
cd frontend && npm install && npm run dev
```

Registered engines live in `backend/src/main/resources/application.yml` under `inspector.engines`
(see ARCHITECTURE.md §3 for the full field reference).

## Status

Bootstrap stage: **M1 (registry + health) and M2 (fan-out search + results grid) are coded**;
details panel, corrective actions, diagram and bulk ops follow the implementation plan.

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
remain visible and unmodified.
