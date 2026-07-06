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
- Make it fail via a variable-dependent expression (e.g.
  `flowable:expression="${1/0 == goodValue}"` or a class cast on a process variable) so the
  documented recovery loop works: **edit variable → retry dead-letter job → completes**.
- Default retry is 3 with ~10s intervals — a demo/test must WAIT for retries to exhaust
  before asserting on `/management/deadletter-jobs` (poll with a deadline, never fixed sleep).
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
