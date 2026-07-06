---
name: unit-test-patterns
description: TDD ladder for the backend — pure-static tests for join/merge logic, MockWebServer for FlowableEngineClient behavior, cached @SpringBootTest for wiring/RBAC, and when to climb to the dockerized-engine layer (see engine-harness). Read before writing or reviewing any backend test, and when deciding how to make a change test-FIRST.
---
# Unit-test patterns (process-inspector backend)

*Ported from the flap `unit-test-patterns` ladder; rungs adapted to this codebase.*

## The ladder — use the LOWEST rung that proves the behavior

| Rung | When | Examples here |
|---|---|---|
| **Pure static / record logic** — zero mocks, zero Spring | status derivation, merge+sort, filter composition, composite-ID parsing | given fixture historic rows + DLQ ids + suspended ids → assert derived statuses; AND/OR filter semantics |
| **MockWebServer stub** | HTTP-client behavior: auth header, timeouts→error envelope, response parsing, per-engine paging caps | `FlowableEngineClient` emits basic-auth from a `password-ref`; read timeout → `perEngine.ok=false`, not an exception |
| **Cached `@SpringBootTest`** | wiring: registry YAML binding, secret-ref resolution, RBAC route rules, SSE bridge | `inspector.engines` binding rejects a missing env ref at boot; VIEWER gets 403 on `POST /api/.../actions/*` |
| **Dockerized engine** (→ `engine-harness` skill) | anything touching a REAL Flowable response shape: the DLQ/suspended join, historic fallback, actions, diagram fetch | never mock these |

Don't climb a rung for convenience (a `@SpringBootTest` for merge logic couples it to boot
time and global state). Don't fake a rung either: status-join logic tested only against
hand-written JSON proves nothing about Flowable's wire shape — that belongs on rung 4.

## Test-first, red-first
Logic-shaped changes (derivation, merging, filter mapping) get the failing test BEFORE the
code — rung 1 makes that cheap: the search plan's inputs are three plain collections, so
fixture-building is trivial. Regression fixes ALWAYS start red: reproduce with a fixture that
fails on HEAD, then fix.

## The fast loop
```bash
cd backend
mvn test -Dtest=SearchServiceTest          # seconds — the class you're driving
mvn verify                                  # before claiming done — full suite
```

## Patterns that pay rent here
- **Fixture builders over JSON blobs** — a `rows(activeRow("a:1"), completedRow("a:2"))`
  builder keeps rung-1 tests readable; raw engine JSON belongs only in MockWebServer stubs.
- **Error-path first-class**: every aggregator test suite includes the engine-down case
  (envelope, not exception) and the partial-page case (`total > fetched`).
- **Constructor-churn trap**: DTOs (`ProcessInstanceRow`, `SearchRequest`) are records used
  everywhere in tests — add new fields via a builder/factory in test-support, not by editing
  20 constructor call sites.
- **No time bombs**: anything time-based (health probe scheduling, timeouts) takes a Clock
  or is tested via the envelope it produces, never with sleeps. `Thread.sleep()` in ANY
  test is a hard failure (ArchUnit-enforced); time-dependent assertions use Awaitility
  with explicit `atMost`/`pollInterval` bounds, asserting against real engine/BFF state —
  full doctrine in the `engine-harness` skill.
