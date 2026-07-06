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
BFF: `export ENGINE_A_PASSWORD=test ENGINE_B_PASSWORD=test; cd backend && mvn spring-boot:run`
(:8085). UI: `cd frontend && npm install && npm run dev` (:5173, proxies `/api`).

## Iron rule: never mock Flowable for join logic
The FAILED/dead-letter join, the suspended-set join, historic-vs-runtime fallbacks, and
variable typing MUST each have an integration test against the dockerized `flowable-rest`
(Testcontainers or the compose stack). Mock HTTP is fine for pure client concerns (auth
header shape, timeout handling, error envelopes) — never for status derivation or paging
semantics. Wire-shape drift between Flowable versions is exactly what mocks hide.

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
