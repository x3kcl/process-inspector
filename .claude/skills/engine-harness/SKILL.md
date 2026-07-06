---
name: engine-harness
description: How to run and test against the dockerized Flowable engines (docker/docker-compose.dev.yml, engines on :8081/:8082) — seeding demo processes incl. a deliberately-failing one, integration-testing the DLQ/suspended status join against REAL flowable-rest (never mocked), and the KEEP-the-stack-up debugging strategy. Read before testing SearchService/aggregator logic or any new engine call.
---
# Engine harness — real Flowable engines for dev & integration tests

*Ported from the flap `smoke-test`/`harness` doctrine: the join logic is where the bugs live,
and it is only proven against a real engine.*

## The stack
```bash
docker compose -f docker/docker-compose.dev.yml up -d   # engine-a :8081, engine-b :8082
# creds: rest-admin / test  (flowable/flowable-rest:6.8.0)
curl -fsS -u rest-admin:test http://localhost:8081/flowable-rest/service/management/engine
```
The 6.x pair is the `flowable-6` profile, activated by default via `docker/.env`
(`COMPOSE_PROFILES=flowable-6`). Extras: `--profile flowable-7` = Flowable 7.1 on :8083;
`--profile legacy` = Flowable 6.3.1 on :8084 (pre-cliff — same context path/creds on all);
`--profile postgres` = the BFF's M4 DB on :5433. Seed with `bash docker/seed.sh` —
no-arg mode auto-discovers and seeds EVERY reachable engine (:8081-:8084) with the full
FIX-PROC-01..06 arc set incl. the REST-suspended and failing-child instances (idempotent
BY KEY — after editing a process file, redeploy manually or `down -v`).
Integration tests are failsafe `*IT` classes: `mvn test` needs no docker, `mvn verify`
needs the FULL matrix up (flowable-6 + flowable-7 + legacy) — a down engine fails loudly
with the compose command, never a silent skip. CI (`.github/workflows/ci.yml`) runs one
matrix leg per profile, gated by `docker/smoke-test.sh` (bounded engine+postgres
readiness — reproduce a leg locally with
`COMPOSE_PROFILES=<profile>,postgres bash docker/smoke-test.sh <profile>`).
BFF: `export ENGINE_A_PASSWORD=test ENGINE_B_PASSWORD=test; cd backend && mvn spring-boot:run`
(:8085). UI: `cd frontend && npm install && npm run dev` (:5173, proxies `/api`).

## Iron rule: never mock Flowable for join logic
The FAILED/dead-letter join, the suspended-set join, historic-vs-runtime fallbacks, and
variable typing MUST each have an integration test against the dockerized `flowable-rest`
(Testcontainers or the compose stack). Mock HTTP is fine for pure client concerns (auth
header shape, timeout handling, error envelopes) — never for status derivation or paging
semantics. Wire-shape drift between Flowable versions is exactly what mocks hide.

**Documented exception (R-TEST-07):** the hierarchy **cycle-guard** is tested at rung 1
over a fixture parent-map containing a loop — real engines cannot produce a cyclic
`superProcessInstanceId` chain, so the iron rule is unsatisfiable there. The depth *limit*
stays rung 4 (recursive seed process, configurable max-depth low in the test profile).

## Anti-flakiness doctrine — ABSOLUTE rules for every integration test

1. **`Thread.sleep()` in any test is a hard failure.** Never wait for async jobs, timers,
   retries, dead-letter transitions, SSE events, or cache expiry with a fixed sleep. This
   is mechanically enforced: an ArchUnit rule in the unit suite fails any test class
   calling `Thread.sleep` (and `TimeUnit.sleep`) — a PR containing one fails CI before
   review.
2. **Awaitility is the only wait primitive.** All time-dependent assertions go through
   `org.awaitility.Awaitility` with explicit bounds — never default/unbounded waits:
   ```java
   await().atMost(5, TimeUnit.SECONDS)
          .pollInterval(200, TimeUnit.MILLISECONDS)
          .untilAsserted(() -> {
              var jobs = engineClient.deadLetterJobs(instanceId);
              assertThat(jobs).extracting("id").contains(expectedJobId);
          });
   ```
   Size `atMost` to the state being awaited (fast-DLQ `R1/PT1S` ⇒ 5s; retry-exhaustion
   with defaults ⇒ 45s), never a blanket 60s.
3. **`untilAsserted` evaluates REAL state** — the actual flowable-rest endpoint or the
   BFF's own aggregation/endpoint, proving the data flushed all the way through (engine DB
   → REST → join → DTO). Never assert on a test-local variable, a mock interaction, or an
   intermediate cache as a proxy for engine state.
4. **Awaitility never wraps a mutation.** Poll reads only; a verb that fires inside a
   polling lambda can double-execute. Mutate once, then await the observable consequence.

## Deterministic-state recipes (from the embedded-tester review; details TEST-STRATEGY §9)
- **RETRYING (failing, retries left)** is a ~10s race with engine defaults — pin it with
  the `<flowable:failedJobRetryTimeCycle>R10/PT1H</flowable:failedJobRetryTimeCycle>`
  extension ELEMENT on the seed task (the attribute form is silently ignored —
  validate-bpmn skill §2); poll-with-deadline, never sleep. **Fast DLQ seeding**: `R1/PT1S`
  dead-letters within seconds; the parked retry timer shows in
  `timer-jobs?withException=true` almost immediately after the first failure.
- **Truncation**: test registry uses `dlq-scan-cap: 50`, `max-page-size: 10` — never seed
  10k jobs.
- **Guard-ladder E2E**: register the SAME docker engine twice (`environment: dev` and
  `prod`) so every prod-only guard branch runs on one stack; drift fixtures via out-of-band
  engine mutation from Playwright (direct engine REST, bypassing the BFF).
- **Breakers**: per-engine test profile (window 2, open 500ms) + WireMock fault/scenario
  recipes; assert cache hits don't count against the breaker.

## Seeding test states
Deploy over REST (`POST /repository/deployments`, multipart `.bpmn20.xml`), then start
instances (`POST /runtime/process-instances` `{"processDefinitionKey":…,"variables":[…]}`).
To manufacture each status:
- **ACTIVE**: a process parked on a user task or timer.
- **SUSPENDED**: `PUT /runtime/process-instances/{id}` `{"action":"suspend"}`.
- **COMPLETED**: a straight-through process.
- **FAILED**: an async service task whose expression/class throws
  (e.g. `flowable:async="true"` + an expression referencing a missing bean) — retries
  exhaust → dead-letter job appears in `/management/deadletter-jobs`. This is the
  demo-fixture for the whole troubleshooting flow (stacktrace → edit variable → retry).

Keep seed BPMN under `docker/processes/` and seed via a script/test-setup class so the
"realistic multi-engine playground" (M0) is one command. See the `validate-bpmn` skill for
authoring rules (DI required, async flags).

## Debugging a red harness — keep the stack up
Don't guess from a failed assertion; inspect the live system:
- Engine state: `curl -u rest-admin:test .../query/historic-process-instances`,
  `.../management/deadletter-jobs` — compare with what the BFF derived.
- Kill one engine (`docker stop`) to test the partial-results envelope: the search must
  return 200 with `perEngine[x].ok=false`, and the UI must badge, not blank.
- Engine logs: `docker compose -f docker/docker-compose.dev.yml logs engine-a`.

## Layer guide (cheapest first)
1. Plain JUnit for pure logic (status derivation given fixture sets, merge/sort).
2. MockWebServer for client behavior (auth, timeouts, error envelope mapping).
3. `@SpringBootTest` for wiring (registry binding, secret-ref resolution, RBAC rules).
4. Dockerized-engine integration tests for every real engine interaction (the join, actions,
   diagram fetch). A feature touching an engine call is not done until layer 4 is green.
