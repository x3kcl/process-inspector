#!/usr/bin/env python3
"""FIX-REF-01 reference-dataset generator (TEST-SCENARIOS.md §1.6, issue #93/C-10).

Deterministic, parameterized, REST-only (never touches engine tables — CLAUDE.md iron
rule). Generates, PER ENGINE:

    3 process-definition keys x 3 versions x 4 failure signatures
    ~20,000 historic (completed) instances
    ~2,000 active instances (no failing job)
    5,000 dead-letter jobs (the envelope ceiling, R-NFR-05 — never larger; anything past
        this is S1-stubbed per FIX-STUB-03, never seeded)
    200 suspended instances
    tenants 'a' and 'b'

One BPMN template (a `failMode` exclusive gateway) is reused for all 3 keys x 3 versions —
a trivial per-version documentation-string tweak forces Flowable to register each as a new
version rather than dedupe the deployment. Each of the 4 non-zero failMode branches reuses
one of the already-proven error-zoo expressions (docker/capture-error-corpus.py) so the
failure signatures this dataset produces are ones the normalizer is already tested against:

    1 = ${amount % divisor}        ArithmeticException      (fast R1/PT1S dead-letter)
    2 = ${orderRef.substring(100)} StringIndexOutOfBounds    (fast R1/PT1S dead-letter)
    3 = ${ghost.total}             PropertyNotFoundException (fast R1/PT1S dead-letter)
    4 = ${amount.noSuchMethod()}   MethodNotFoundException   (fast R1/PT1S dead-letter)
    0 = (no service task)          completes immediately     -> the "historic" population
    5 = a plain user task, never completed -> parked ACTIVE (or SUSPENDED, if --suspend)

Usage:
    python3 docker/seed-reference.py                     # every reachable KNOWN_PORTS engine
    python3 docker/seed-reference.py http://localhost:8691/flowable-rest/service
    python3 docker/seed-reference.py --workers 40 --scale 0.01   # 1% scale, for a dry run

--scale multiplies every count (historic/active/dlq/suspended) — use a small value to prove
the script end-to-end against a real engine before trusting the full nightly-scale run.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

CRED = "rest-admin:test"
DEFAULT_PORTS = [
    os.environ.get("PI_ENGINE_A_PORT", "8081"),
    os.environ.get("PI_ENGINE_B_PORT", "8082"),
    os.environ.get("PI_ENGINE_7_PORT", "8083"),
    os.environ.get("PI_ENGINE_LEGACY_PORT", "8084"),
]

DEFINITION_KEYS = ["refFlowAlpha", "refFlowBeta", "refFlowGamma"]
VERSIONS = [1, 2, 3]
TENANTS = ["a", "b"]

# Per-engine target counts at scale=1.0 (TEST-SCENARIOS.md §1.6).
HISTORIC_TOTAL = 20_000
ACTIVE_HEALTHY_TOTAL = 2_000
DEADLETTER_TOTAL = 5_000
SUSPENDED_TOTAL = 200


def call(engine, path, method="GET", body=None, timeout=15):
    req = urllib.request.Request(engine + path, method=method)
    req.add_header("Authorization", "Basic " + _b64(CRED))
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(body).encode()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = resp.read()
    return json.loads(data) if data else None


def _b64(s):
    import base64

    return base64.b64encode(s.encode()).decode()


def bpmn_xml(key, version_note):
    """One process per key: a failMode exclusive gateway over the 4 proven error-zoo
    expressions (mode 0 = straight to end, mode 5 = a parked user task)."""
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://inspector.io/reference">
  <!-- FIX-REF-01 ({version_note}): failMode routes to one of the 4 error-zoo signatures
       (1-4, each a fast R1/PT1S dead-letter), straight to end (0, the historic population),
       or a parked user task (5, the active/suspended population). Conditions MUST be the
       standard BPMN <conditionExpression> CHILD ELEMENT — a flowable:conditionExpression
       ATTRIBUTE parses but is silently ignored (the same attribute-vs-extension-element
       pitfall this codebase already knows from failedJobRetryTimeCycle; confirmed here by
       every instance completing via the unconditional-looking mode0 path regardless of the
       failMode variable, before this was fixed). -->
  <process id="{key}" name="Reference flow {key}" isExecutable="true">
    <documentation>{version_note}</documentation>
    <startEvent id="start"/>
    <exclusiveGateway id="gw"/>
    <sequenceFlow id="toGw" sourceRef="start" targetRef="gw"/>
    <sequenceFlow id="mode0" sourceRef="gw" targetRef="end">
      <conditionExpression xsi:type="tFormalExpression">${{failMode == 0}}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="mode1" sourceRef="gw" targetRef="tArith">
      <conditionExpression xsi:type="tFormalExpression">${{failMode == 1}}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="mode2" sourceRef="gw" targetRef="tStrIdx">
      <conditionExpression xsi:type="tFormalExpression">${{failMode == 2}}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="mode3" sourceRef="gw" targetRef="tGhost">
      <conditionExpression xsi:type="tFormalExpression">${{failMode == 3}}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="mode4" sourceRef="gw" targetRef="tNoMethod">
      <conditionExpression xsi:type="tFormalExpression">${{failMode == 4}}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="mode5" sourceRef="gw" targetRef="parked">
      <conditionExpression xsi:type="tFormalExpression">${{failMode == 5}}</conditionExpression>
    </sequenceFlow>

    <serviceTask id="tArith" name="Charge (arithmetic)" flowable:async="true"
                 flowable:expression="${{amount % divisor}}">
      <extensionElements><flowable:failedJobRetryTimeCycle>R1/PT1S</flowable:failedJobRetryTimeCycle></extensionElements>
    </serviceTask>
    <serviceTask id="tStrIdx" name="Slice (string-index)" flowable:async="true"
                 flowable:expression="${{orderRef.substring(100)}}">
      <extensionElements><flowable:failedJobRetryTimeCycle>R1/PT1S</flowable:failedJobRetryTimeCycle></extensionElements>
    </serviceTask>
    <serviceTask id="tGhost" name="Read ghost (missing-property)" flowable:async="true"
                 flowable:expression="${{ghost.total}}">
      <extensionElements><flowable:failedJobRetryTimeCycle>R1/PT1S</flowable:failedJobRetryTimeCycle></extensionElements>
    </serviceTask>
    <serviceTask id="tNoMethod" name="Call missing (method-not-found)" flowable:async="true"
                 flowable:expression="${{amount.noSuchMethod()}}">
      <extensionElements><flowable:failedJobRetryTimeCycle>R1/PT1S</flowable:failedJobRetryTimeCycle></extensionElements>
    </serviceTask>
    <userTask id="parked" name="Parked (active/suspended population)"/>

    <sequenceFlow id="fArith" sourceRef="tArith" targetRef="end"/>
    <sequenceFlow id="fStrIdx" sourceRef="tStrIdx" targetRef="end"/>
    <sequenceFlow id="fGhost" sourceRef="tGhost" targetRef="end"/>
    <sequenceFlow id="fNoMethod" sourceRef="tNoMethod" targetRef="end"/>
    <endEvent id="end"/>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_{key}">
    <bpmndi:BPMNPlane id="BPMNPlane_{key}" bpmnElement="{key}">
      <bpmndi:BPMNShape id="s_start" bpmnElement="start"><omgdc:Bounds x="20" y="140" width="30" height="30"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_gw" bpmnElement="gw"><omgdc:Bounds x="90" y="135" width="40" height="40"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_tArith" bpmnElement="tArith"><omgdc:Bounds x="180" y="0" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_tStrIdx" bpmnElement="tStrIdx"><omgdc:Bounds x="180" y="80" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_tGhost" bpmnElement="tGhost"><omgdc:Bounds x="180" y="160" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_tNoMethod" bpmnElement="tNoMethod"><omgdc:Bounds x="180" y="240" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_parked" bpmnElement="parked"><omgdc:Bounds x="180" y="320" width="100" height="60"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="s_end" bpmnElement="end"><omgdc:Bounds x="360" y="140" width="28" height="28"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="e_toGw" bpmnElement="toGw"><omgdi:waypoint x="50" y="155"/><omgdi:waypoint x="90" y="155"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_mode0" bpmnElement="mode0"><omgdi:waypoint x="110" y="135"/><omgdi:waypoint x="374" y="140"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_mode1" bpmnElement="mode1"><omgdi:waypoint x="110" y="135"/><omgdi:waypoint x="230" y="60"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_mode2" bpmnElement="mode2"><omgdi:waypoint x="110" y="150"/><omgdi:waypoint x="230" y="140"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_mode3" bpmnElement="mode3"><omgdi:waypoint x="110" y="165"/><omgdi:waypoint x="230" y="220"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_mode4" bpmnElement="mode4"><omgdi:waypoint x="110" y="175"/><omgdi:waypoint x="230" y="300"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_mode5" bpmnElement="mode5"><omgdi:waypoint x="110" y="175"/><omgdi:waypoint x="230" y="380"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_fArith" bpmnElement="fArith"><omgdi:waypoint x="280" y="30"/><omgdi:waypoint x="374" y="140"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_fStrIdx" bpmnElement="fStrIdx"><omgdi:waypoint x="280" y="110"/><omgdi:waypoint x="374" y="140"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_fGhost" bpmnElement="fGhost"><omgdi:waypoint x="280" y="190"/><omgdi:waypoint x="374" y="150"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="e_fNoMethod" bpmnElement="fNoMethod"><omgdi:waypoint x="280" y="270"/><omgdi:waypoint x="374" y="155"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>"""


def deploy_version(engine, key, tenant, version_note):
    boundary = "ref-dataset-boundary"
    xml = bpmn_xml(key, version_note)
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{key}.bpmn20.xml"\r\n'
        f"Content-Type: text/xml\r\n\r\n{xml}\r\n",
        f'--{boundary}\r\nContent-Disposition: form-data; name="tenantId"\r\n\r\n{tenant}\r\n',
        f"--{boundary}--\r\n",
    ]
    payload = "".join(parts).encode()
    req = urllib.request.Request(engine + "/repository/deployments", data=payload, method="POST")
    req.add_header("Authorization", "Basic " + _b64(CRED))
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    urllib.request.urlopen(req, timeout=15).read()


def deploy_all_versions(engine, key, tenant):
    """Deploy 3 versions of `key` for `tenant` — Flowable's tenant isolation applies at the
    DEPLOYMENT level, so starting an instance with a given tenantId requires the definition
    to have been deployed under that same tenant, not just tagged at start time. Each
    deployment's differing <documentation> forces a new version rather than a dedupe.
    Idempotent: deploys only the versions still missing up to len(VERSIONS), so a re-run
    after a partial prior run (e.g. crashed after version 2) tops up to exactly 3 rather
    than minting versions 3..5 on top of the existing 1..2."""
    existing = call(
        engine,
        f"/repository/process-definitions?key={key}&tenantId={tenant}&sort=version&order=desc&size=1",
    )
    current = existing["data"][0]["version"] if existing["total"] > 0 else 0
    for v in VERSIONS[current:]:
        deploy_version(engine, key, tenant, f"{key}[{tenant}] reference-dataset version {v}")
    total = call(engine, f"/repository/process-definitions?key={key}&tenantId={tenant}&latest=true")["total"]
    if total == 0:
        sys.exit(f"ERROR: {key}[{tenant}] deployed to {engine} but no definition appeared — parse failure?")


def start_one(engine, key, tenant, variables):
    body = {"processDefinitionKey": key, "tenantId": tenant, "variables": variables}
    try:
        result = call(engine, "/runtime/process-instances", method="POST", body=body, timeout=30)
        return result["id"]
    except (urllib.error.URLError, OSError, KeyError) as e:
        return None, str(e)


def var_int(name, value):
    return {"name": name, "type": "integer", "value": value}


def var_string(name, value):
    return {"name": name, "type": "string", "value": value}


def variables_for(fail_mode, i):
    v = [var_int("failMode", fail_mode)]
    if fail_mode == 1:
        v += [var_int("amount", 100), var_int("divisor", 0)]
    elif fail_mode == 2:
        v += [var_string("orderRef", "ref")]
    elif fail_mode == 4:
        v += [var_int("amount", 100)]
    # fail_mode 0/3/5 need no extra variables (3's ${ghost.total} deliberately
    # references a variable that was never set — that IS the missing-property fixture).
    return v


def plan_starts(scale):
    """(key, version doesn't matter for start — Flowable always starts 'latest' by key
    unless a specific definition id is given; TEST-SCENARIOS.md's 'x 3 versions' is
    about the DEFINITION catalog breadth, not an even instance split across versions)
    -> a flat list of (failMode,) work items, round-robined across the 3 keys/2 tenants."""
    historic = round(HISTORIC_TOTAL * scale)
    active = round(ACTIVE_HEALTHY_TOTAL * scale)
    deadletter = round(DEADLETTER_TOTAL * scale)
    suspended = round(SUSPENDED_TOTAL * scale)
    dl_modes = [1, 2, 3, 4]
    items = (
        [0] * historic
        + [5] * active
        # round-robin over the 4 modes for the EXACT count, not a floor(deadletter/4)*4
        # truncation (which silently drops the remainder and understates small-scale runs).
        + [dl_modes[i % 4] for i in range(deadletter)]
        + [5] * suspended  # suspended instances start on the SAME parked path, then get suspended
    )
    return items, suspended


def seed_engine(engine, scale, workers):
    print(f"--- {engine} ---")
    try:
        version = call(engine, "/management/engine")["version"]
    except (urllib.error.URLError, OSError) as e:
        print(f"  SKIP — not reachable ({e})")
        return
    print(f"  engine version {version}")

    for key in DEFINITION_KEYS:
        for tenant in TENANTS:
            deploy_all_versions(engine, key, tenant)
        print(f"  {key}: 3 versions present x {len(TENANTS)} tenants")

    items, suspended_count = plan_starts(scale)
    print(f"  planned {len(items)} instance starts ({suspended_count} to be suspended after start)")

    started = []
    failures = 0
    t0 = time.monotonic()

    def work(idx, fail_mode):
        key = DEFINITION_KEYS[idx % len(DEFINITION_KEYS)]
        tenant = TENANTS[idx % len(TENANTS)]
        result = start_one(engine, key, tenant, variables_for(fail_mode, idx))
        if isinstance(result, tuple):
            return None
        return result

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(work, idx, fm): (idx, fm) for idx, fm in enumerate(items)}
        done = 0
        for fut in as_completed(futures):
            idx, fm = futures[fut]
            iid = fut.result()
            done += 1
            if iid is None:
                failures += 1
            elif fm == 5 and len(started) < suspended_count:
                started.append(iid)  # the first N parked-path instances become the suspended set
            if done % 2000 == 0:
                elapsed = time.monotonic() - t0
                print(f"  {done}/{len(items)} started ({elapsed:.0f}s, {done / max(elapsed, 1):.0f}/s)")

    elapsed = time.monotonic() - t0
    print(f"  {len(items)} starts requested, {failures} failed, {elapsed:.0f}s total")

    # Suspend the reserved subset — a separate REST call each, so a smaller, fixed cost.
    def suspend(iid):
        try:
            call(engine, f"/runtime/process-instances/{iid}", method="PUT", body={"action": "suspend"})
            return True
        except (urllib.error.URLError, OSError):
            return False

    if started:
        with ThreadPoolExecutor(max_workers=min(workers, 20)) as pool:
            results = list(pool.map(suspend, started))
        print(f"  suspended {sum(results)}/{len(started)} instances")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("engines", nargs="*", help="engine flowable-rest service base URL(s); default: KNOWN_PORTS")
    ap.add_argument("--workers", type=int, default=32, help="concurrent REST workers (default 32)")
    ap.add_argument("--scale", type=float, default=1.0, help="multiply every count (default 1.0 = full FIX-REF-01)")
    args = ap.parse_args()

    engines = args.engines or [f"http://localhost:{p}/flowable-rest/service" for p in DEFAULT_PORTS]
    for engine in engines:
        seed_engine(engine, args.scale, args.workers)


if __name__ == "__main__":
    main()
