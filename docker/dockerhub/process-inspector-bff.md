# Process Inspector — BFF (backend)

The **backend-for-frontend** of the Flowable **Process Inspector**: a centralized,
multi-engine tool for support teams to **find, diagnose, and fix** runtime problems with
process instances across many Flowable environments from one UI — strictly over the
Flowable **V6/V7 REST API**.

This image is the Spring Boot 3 API tier. It pairs with
[`x3kcl/process-inspector-web`](https://hub.docker.com/r/x3kcl/process-inspector-web)
(the SPA served by nginx). Source & full docs:
**https://github.com/x3kcl/process-inspector**

## What it does

Built around the on-call incident loop — **FIND → ORIENT → DIAGNOSE → FIX → VERIFY →
HANDOVER**:

- **Cross-engine triage** — one search and one status model over every registered engine.
  The **FAILED** status is a corrected join over the dead-letter job lane, never a single
  truncated fetch; truncated data is always labeled, never allowed to impersonate a healthy
  instance.
- **Job-lane diagnosis** — executable / timer / suspended / dead-letter, plus a read-only
  external-worker lane (Flowable 6.8+), capability-gated per engine.
- **Corrective actions** with a graduated guard ladder — friction proportional to blast
  radius, from zero-modal retry to typed, target-specific confirmation; unscoped destructive
  bulk is refused. Every mutation is audited (who / when / engine / instance / payload /
  outcome), RBAC-gated, and never auto-retried.
- **Do-no-harm posture** — every outbound engine call is wrapped in a Resilience4j circuit
  breaker + per-engine bulkhead; an unreachable engine degrades to labeled partial results,
  it never fails the whole search.

## Tech (ADR-001)

Java 21 · Spring Boot 3.5+ · blocking + **virtual threads** (no WebFlux) · `RestClient` per
engine · Resilience4j · Caffeine triage cache · Spring Data JPA + **Flyway** + **Postgres
16** (insert-only audit repository) · Spring Security dual profile (form/basic for dev,
**OIDC** for prod) · SSE via `SseEmitter`. It integrates with engines **strictly over REST**
— it never touches engine (`ACT_*`) databases and ships no engine of its own.

## Run it

The image needs a Postgres 16 database (its own audit/notes store) and one or more Flowable
REST endpoints to point at. The easiest path is the published quick-start compose, which
wires this image, the web image, and Postgres together:

```bash
curl -LO https://github.com/x3kcl/process-inspector/releases/latest/download/docker-compose.release.yml
INSPECTOR_DEV_PASSWORD=pick-one docker compose -f docker-compose.release.yml up -d
# UI on http://localhost:8080 (served by process-inspector-web)
```

### Ports

- `8080` — HTTP (`SERVER_PORT`, container standard).

### Key environment variables

| Variable | Purpose |
|----------|---------|
| `SERVER_PORT` | HTTP port (default `8080`). |
| `INSPECTOR_DB_URL` | JDBC URL of the BFF's Postgres, e.g. `jdbc:postgresql://postgres:5432/inspector`. |
| `INSPECTOR_DB_USER` / `INSPECTOR_DB_PASSWORD` | BFF database credentials. |
| `INSPECTOR_ENGINE_A_BASE_URL` / `INSPECTOR_ENGINE_B_BASE_URL` | Flowable REST base URLs to inspect. |
| `ENGINE_A_PASSWORD` / `ENGINE_B_PASSWORD` | Per-engine machine-account passwords (env refs only — never in config). |
| `INSPECTOR_DEV_PASSWORD` | Sign-in password for the dev-auth ladder users (dev/demo profile). |
| `JAVA_TOOL_OPTIONS` | JVM tuning; defaults to `-XX:MaxRAMPercentage=75`. |

Engines are **yours to point at** — unreachable engines are first-class product state, so
the container boots fine before you wire them. Secrets arrive **only** as env refs; they are
never logged or echoed in responses.

## Tags

- `latest`, `X.Y.Z`, `X.Y` — versioned releases (semver; `latest` tracks the newest stable).
- `edge` — the tip of `main` after a green CI run.
- `sha-<short>` — the exact commit of an edge build, for pinning.

The same tags are mirrored to `ghcr.io/x3kcl/process-inspector-bff`.

## License

Apache-2.0. bpmn.io is used under its own license — the bpmn.io watermark in the web tier
must not be removed.
