#!/usr/bin/env python3
"""Golden-corpus capture for the error-signature normalizer (TEST-STRATEGY §4).

Deploys the organically-failing error-zoo processes to every reachable engine of the
compose matrix, starts instances, waits for the jobs to dead-letter (or, for the
pinned-RETRYING kind, to park in the timer lane), and dumps the REAL exceptionMessage +
stacktrace payloads into

    backend/src/test/resources/error-signatures/{6.x,7.x}/corpus.json

REST-only (never touches engine tables); idempotent deploys BY KEY. Re-run after an
engine image bump to re-capture (the corpus files are replaced wholesale). Payload
stacktraces are truncated to the first N frames — the normalizer only consumes the head
line and the Caused-by chain; full traces would bloat the repo without adding signal.

Usage: python3 docker/capture-error-corpus.py            # all reachable engines
"""

import base64
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

CRED = base64.b64encode(b"rest-admin:test").decode()
HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent / "backend" / "src" / "test" / "resources" / "error-signatures"
ENGINES = [f"http://localhost:{p}/flowable-rest/service" for p in (8081, 8084, 8083)]
STACKTRACE_MAX_LINES = 40
DEADLINE_S = 60

# kind -> (definition key, bpmn file, start variables, lane)
INT_VARS = [
    {"name": "amount", "type": "integer", "value": 100},
    {"name": "divisor", "type": "integer", "value": 0},
]
KINDS = {
    "arithmetic": ("demoFailingPayment", "demo-failing-payment.bpmn20.xml", INT_VARS, "deadletter"),
    "retrying-arithmetic": ("demoFailingRetry", "demo-failing-retry.bpmn20.xml", INT_VARS, "timer"),
    "string-index": (
        "zooStringIndex",
        "error-zoo-string-index.bpmn20.xml",
        # varying length -> per-instance digits in the message (the ID-stripping proof)
        [{"name": "orderRef", "type": "string", "value": "ref"}],
        "deadletter",
    ),
    "missing-property": ("zooMissingProperty", "error-zoo-missing-property.bpmn20.xml", [], "deadletter"),
    "method-not-found": ("zooMethodNotFound", "error-zoo-method-not-found.bpmn20.xml", INT_VARS, "deadletter"),
}
INSTANCES_PER_KIND = {"6": 3, "7": 6}  # 6.x major has two engines; 7.x must reach ≥30 alone


def call(engine, path, body=None, raw=False):
    req = urllib.request.Request(engine + path)
    req.add_header("Authorization", "Basic " + CRED)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(body).encode()
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = resp.read()
    return data.decode() if raw else json.loads(data)


def deploy_if_missing(engine, key, bpmn_file):
    total = call(engine, f"/repository/process-definitions?key={key}&latest=true")["total"]
    if total:
        return
    path = HERE / "processes" / bpmn_file
    boundary = "zoo-corpus-boundary"
    payload = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{bpmn_file}"\r\n'
        f"Content-Type: text/xml\r\n\r\n{path.read_text()}\r\n--{boundary}--\r\n"
    ).encode()
    req = urllib.request.Request(engine + "/repository/deployments", data=payload)
    req.add_header("Authorization", "Basic " + CRED)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    urllib.request.urlopen(req, timeout=15).read()
    # deployment 2xx != definition parsed (validate-bpmn §3)
    if not call(engine, f"/repository/process-definitions?key={key}&latest=true")["total"]:
        sys.exit(f"ERROR: {bpmn_file} deployed to {engine} but '{key}' did not appear — parse failure?")


def await_failed_job(engine, lane, instance_id):
    """Poll (bounded) until the instance's job carries an exception in the given lane."""
    path = "/management/deadletter-jobs" if lane == "deadletter" else "/management/timer-jobs"
    deadline = time.monotonic() + DEADLINE_S
    while time.monotonic() < deadline:
        page = call(engine, f"{path}?withException=true&processInstanceId={instance_id}")
        if page["data"]:
            return page["data"][0]
        time.sleep(0.5)
    return None


def capture_engine(engine):
    try:
        version = call(engine, "/management/engine")["version"]
    except (urllib.error.URLError, OSError) as e:
        print(f"  SKIP {engine} — not reachable ({e})")
        return None, []
    major = version.split(".")[0]
    entries = []
    for kind, (key, bpmn_file, variables, lane) in KINDS.items():
        deploy_if_missing(engine, key, bpmn_file)
        for i in range(INSTANCES_PER_KIND[major]):
            if kind == "string-index":  # vary the length -> per-instance message digits
                variables = [{"name": "orderRef", "type": "string", "value": "ref-" + "x" * i}]
            started = call(
                engine,
                "/runtime/process-instances",
                {"processDefinitionKey": key, "variables": variables},
            )
            job = await_failed_job(engine, lane, started["id"])
            if job is None:
                print(f"  WARN {kind}[{i}] on {version}: no failed job within {DEADLINE_S}s — skipped")
                continue
            trace_path = (
                f"/management/{'deadletter-jobs' if lane == 'deadletter' else 'timer-jobs'}"
                f"/{job['id']}/exception-stacktrace"
            )
            stacktrace = "\n".join(call(engine, trace_path, raw=True).splitlines()[:STACKTRACE_MAX_LINES])
            entries.append(
                {
                    "kind": kind,
                    "engineVersion": version,
                    "lane": lane,
                    "exceptionMessage": job.get("exceptionMessage"),
                    "stacktrace": stacktrace,
                }
            )
        print(f"  {version}: {kind} captured")
    return major, entries


def main():
    by_major = {}
    for engine in ENGINES:
        print(f"Capturing from {engine}")
        major, entries = capture_engine(engine)
        if major:
            by_major.setdefault(f"{major}.x", []).extend(entries)
    for major, entries in by_major.items():
        out = OUT / major / "corpus.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"capturedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "entries": entries}, indent=2) + "\n")
        print(f"Wrote {len(entries)} payloads -> {out}")
        if len(entries) < 30:
            print(f"  WARN: {major} corpus below the ≥30 floor (TEST-STRATEGY §4)")


if __name__ == "__main__":
    main()
