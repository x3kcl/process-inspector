# Flowable Multi-Instance Process Inspector

A centralized management plane (BAW-Inspector-style) to search, troubleshoot and **fix** process
instances across **multiple independent Flowable engines** — strictly via the Flowable V6 REST API.

## Documents

| File | Content |
|---|---|
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | The agreed product spec (features, filters, corrective actions) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Multi-instance design: BFF topology, composite IDs, fan-out with partial results, the FAILED/dead-letter status join, **Engine Registry data model** |
| [docs/IMPLEMENTATION-PLAN.md](docs/IMPLEMENTATION-PLAN.md) | Milestones M0–M6, module by module |

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
