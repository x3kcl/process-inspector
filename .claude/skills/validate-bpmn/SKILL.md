---
name: validate-bpmn
description: Rules for authoring the demo/seed BPMN 2.0 processes deployed to the dockerized Flowable engines (docker/processes/) — required diagram interchange for bpmn-js, async+retry semantics that produce dead-letter jobs on purpose, and how to prove a file deploys over REST.
---
# BPMN authoring/validation for seed processes (process-inspector)

*Ported from the flap `validate-bpmn` skill; trimmed to what matters for this repo — we AUTHOR
only demo/seed processes for the dev engines (the inspector itself never edits BPMN, spec §5).*

## 1. Diagram interchange (DI) is mandatory
Every seed `.bpmn20.xml` MUST contain the `<bpmndi:BPMNDiagram>` section. The inspector's M5
diagram view renders `GET /repository/process-definitions/{defId}/resourcedata` with bpmn-js —
a file without DI deploys fine but renders nothing, which silently breaks the demo and any
diagram test. Author with Flowable/Camunda Modeler or copy an existing seed's DI and adjust.

## 2. Producing FAILED on purpose (the DLQ demo fixture)
- Mark the failing service task `flowable:async="true"` — only async work retries and
  dead-letters; a synchronous failure just rolls back the start call.
- Make it fail via a variable-dependent expression so the documented recovery loop works:
  **edit variable → retry dead-letter job → completes**.
  ⚠ **EL arithmetic trap (proven on flowable-rest 6.8):** EL `/` coerces operands to
  Double — `${amount / divisor}` with `divisor=0` yields `Infinity` and the instance
  COMPLETES. EL `%` coerces integer operands to Long, so `${amount % divisor}` with
  `divisor=0` genuinely throws ArithmeticException — this is what `demoFailingPayment`
  ships. Recovery: set `divisor` non-zero → retry → completes.
- Default retry is 3 with ~10s intervals — a demo/test must WAIT for retries to exhaust
  before asserting on `/management/deadletter-jobs` (poll with a deadline, never fixed sleep).
- **Control the retry cycle per task** with `flowable:failedJobRetryTimeCycle`:
  `"R1/PT1S"` = fastest dead-letter — but the scheduled retry parks in the TIMER queue
  until the async executor's timer-acquisition cycle picks it up, so wall-clock
  fail→retry→dead-letter is **~45s** on flowable-rest 6.8 defaults (bound waits at 60s,
  not 5s); `"R10/PT1H"` = pins the
  RETRYING/failing-with-retries-left state stably for an hour (the only deterministic way
  to test the `hasFailingJobs` tier — see TEST-STRATEGY §9).
- **Error-class corpus** (expression-only — flowable-rest has no custom beans): distinct
  exception classes with per-instance noise, e.g. a method call on a variable value
  (`${orderRef.substring(100)}` on a short string → StringIndexOutOfBoundsException with a
  per-instance message — proves ID-stripping groups N instances into one signature);
  `${amount % divisor}` → ArithmeticException; missing-variable access →
  PropertyNotFoundException. (JUEL has no `T(...)` static calls — that's SpEL; and see the
  `/`-vs-`%` trap above.)
- **Deep hierarchies**: one self-recursive seed process (call activity with `calledElement`
  = its own key, gated `${depth < maxDepth}`, `depth+1` in-parameter) yields N-deep
  call-activity chains from one file.
- Use `flowable:expression`/`flowable:delegateExpression` referencing things that exist in
  the ENGINE container (flowable-rest has no custom beans) — plain expressions on variables
  are the reliable failure mechanism; `flowable:class` referencing a non-existent class fails
  at deploy or instantly, not through the retry path.

## 3. Deploy over REST, prove it deployed
```bash
curl -u rest-admin:test -F "file=@docker/processes/demo-order.bpmn20.xml" \
  http://localhost:8081/flowable-rest/service/repository/deployments
curl -u rest-admin:test ".../repository/process-definitions?key=demoOrder&latest=true"
```
- Deployment success ≠ definition parsed: assert the definition list, not just the 2xx.
- Redeploying the same content is version-bumped by Flowable only when content changed;
  seed scripts should be idempotent (query first or tolerate duplicates).
- Keep process keys stable (`demoOrder`, `demoFailingPayment`, …) — tests and docs reference
  them; renaming a key orphans running instances in the dev engines.

## 4. Executable checklist
- `<process id="…" isExecutable="true">` — without it, deploy succeeds but no definition.
- Every flow node reachable from the start event; every path reaches an end event.
- User tasks get a candidate group that exists in flowable-rest defaults, or an explicit
  assignee — otherwise "reassign task" demos have nothing to grab.
