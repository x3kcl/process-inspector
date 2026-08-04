#!/usr/bin/env python3
"""Grouping-quality corpus harvest (issue #350, R-SEM-03 R4 track).

Distinct from — and never a replacement for — docker/capture-error-corpus.py, which
captures the NORMATIVE CI-gating golden corpus for ErrorSignatureNormalizer itself
(backend/src/test/resources/error-signatures/). This script captures a SEPARATE,
hand-labeled-ground-truth fixture used ONLY to MEASURE the current normalizer's grouping
quality (over-grouping / under-grouping, Drain/dedupT methodology) — it does not gate the
normalizer and must never be confused with the golden corpus.

REST-only (never touches ACT_* engine tables — CLAUDE.md iron rule). Harvests dead-letter /
parked-retry job exceptionMessage + stacktrace from the dockerized engines for:

  - the existing "error zoo" fixtures (arithmetic, string-index, missing-property,
    method-not-found) — each a distinct, precisely-known root cause;
  - the ACME suite's one organically-failing process (acmeApiOutage — a real outbound-HTTP
    DNS failure, a genuinely different shape from the JUEL-expression zoo fixtures);
  - two ADVERSARIAL pairs (added by this issue), each two genuinely distinct root causes
    hand-picked to STRESS the normalizer's sanitization rules:
      * zooMissingProperty / zooMissingPropertyVariant ('ghost' vs 'phantom' — two
        different missing upstream data dependencies). Measured result: does NOT collide
        (a documented near-miss — see the BPMN file's own comment for why).
      * acme-api-outage / acme-shipping-outage (billing vendor down vs shipping vendor
        down — two different downstream integrations). Measured result: DOES collide —
        Flowable's HTTP-task connector's job-level exceptionMessage is the generic
        constant "execution exception" regardless of which host failed.
    Both pairs are written to a SEPARATE "adversarialEntries" array, excluded from the
    primary metrics (a corpus that gives engineered/hand-picked pairs equal weight to
    organically-sampled causes would bias the headline numbers) — see
    docs/reviews/R4-GROUPING-QUALITY-2026-08.md for the full writeup of both.

Best-effort also pulls a couple of CURRENT-generation (algoVersion == the corpus's own
ALGO_VERSION at capture time) rows from the live demo's OWN read API
(GET /api/incidents, dev sign-in ladder — never the engine REST API directly, since the
demo's engines are not exposed) to confirm cross-deployment stability. Network-optional:
skipped with a warning, never a hard failure, if the demo is unreachable.

Every harvested string passes through anonymize() before being written — defense in depth;
this corpus is entirely synthetic/internal engine-internal messages with no real customer
data, but the pass runs unconditionally so a reviewer can trust it ran rather than take that
on faith.

Output: backend/src/test/resources/grouping-quality/corpus.json (checked in).

Usage: python3 scripts/harvest-grouping-quality-corpus.py
"""

import base64
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

CRED = base64.b64encode(b"rest-admin:test").decode()
HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
OUT = REPO_ROOT / "backend" / "src" / "test" / "resources" / "grouping-quality" / "corpus.json"
ENGINES = [f"http://localhost:{p}/flowable-rest/service" for p in (8081, 8082, 8083, 8084)]
DEMO_BASE = "https://pi.naumann.cloud"
DEMO_AUTH = base64.b64encode(b"viewer:dev").decode()
STACKTRACE_MAX_LINES = 30
DEADLINE_S = 60
INSTANCES_PER_KIND = 4  # per engine — real N-instance harvesting for the under-grouping check

# groupId -> (definition key, bpmn file, start variables, lane, human root-cause description)
INT_VARS = [
    {"name": "amount", "type": "integer", "value": 100},
    {"name": "divisor", "type": "integer", "value": 0},
]
ORGANIC_GROUPS = {
    "arithmetic-family": {
        # Same underlying expression/root cause, two lanes (dead-letter vs pinned-RETRYING
        # timer parking) — a real cross-lane under-grouping check: one bug, two job kinds,
        # must still land on one signature.
        "kinds": [
            ("arithmetic", "demoFailingPayment", "demo-failing-payment.bpmn20.xml", INT_VARS, "deadletter"),
            ("retrying-arithmetic", "demoFailingRetry", "demo-failing-retry.bpmn20.xml", INT_VARS, "timer"),
        ],
        "description": "EL '%' arithmetic with divisor=0 -> ArithmeticException / by zero",
    },
    "string-index": {
        "kinds": [
            (
                "string-index",
                "zooStringIndex",
                "error-zoo-string-index.bpmn20.xml",
                None,  # varies per-instance, see below
                "deadletter",
            )
        ],
        "description": "substring() past end of a variable-length string -> StringIndexOutOfBoundsException",
    },
    "missing-property": {
        "kinds": [
            ("missing-property", "zooMissingProperty", "error-zoo-missing-property.bpmn20.xml", [], "deadletter")
        ],
        "description": "expression references identifier 'ghost', which no variable/bean provides -> PropertyNotFoundException",
    },
    "method-not-found": {
        "kinds": [
            ("method-not-found", "zooMethodNotFound", "error-zoo-method-not-found.bpmn20.xml", INT_VARS, "deadletter")
        ],
        "description": "expression calls a method no type provides -> MethodNotFoundException",
    },
    "acme-api-outage": {
        "kinds": [("acme-api-outage", "acmeApiOutage", "acme-api-outage.bpmn20.xml", [], "deadletter")],
        "description": "async HTTP task to a reserved-invalid host -> UnknownHostException (organic ACME fixture, not a JUEL-expression zoo shape)",
    },
}
# Two hand-picked adversarial pairs — deliberately excluded from ORGANIC_GROUPS / the
# primary metrics (see the module docstring for the measured result of each).
ADVERSARIAL_GROUPS = {
    "missing-property": ORGANIC_GROUPS["missing-property"],
    "missing-property-variant": {
        "kinds": [
            (
                "missing-property-variant",
                "zooMissingPropertyVariant",
                "error-zoo-missing-property-variant.bpmn20.xml",
                [],
                "deadletter",
            )
        ],
        "description": "expression references identifier 'phantom' (a DIFFERENT missing data dependency) -> PropertyNotFoundException. HYPOTHESIZED to collide with missing-property via quoted-literal sanitization; MEASURED to NOT collide, because v2 identity hashes the job's own exceptionMessage snippet (which still embeds the literal '${phantom.balance}' expression text), not the stacktrace-refined/quoted form.",
    },
    "acme-billing-outage": {
        "kinds": ORGANIC_GROUPS["acme-api-outage"]["kinds"],
        "description": "async HTTP task to a reserved-invalid BILLING host -> UnknownHostException (same fixture as the organic acme-api-outage group, re-harvested here under an adversarial-pair label for direct comparison against acme-shipping-outage).",
    },
    "acme-shipping-outage": {
        "kinds": [
            ("acme-shipping-outage", "acmeShippingOutage", "acme-shipping-outage.bpmn20.xml", [], "deadletter")
        ],
        "description": "async HTTP task to a DIFFERENT reserved-invalid host (SHIPPING vendor, a distinct downstream integration/root cause from billing) -> also UnknownHostException. MEASURED to COLLIDE with acme-billing-outage: Flowable's HTTP-task connector's job-level exceptionMessage is the generic constant 'execution exception' regardless of which host failed — a real, organically-confirmed over-grouping case.",
    },
}

# Defense in depth: no real hostnames/tokens/PII are expected in this synthetic corpus, but
# every message is still passed through this before being written.
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
# Reserved/synthetic hosts used on purpose by the fixtures (RFC 2606 .invalid, RFC 2606
# .example, and the docker-compose service names) are NOT customer identifiers — never
# redacted, so a reviewer can see the pass ran without losing the (harmless) fixture text.
_SAFE_HOSTS = {"acme-billing.invalid"}


def anonymize(text):
    if not text:
        return text
    redactions = 0
    out = text
    for pattern in (_EMAIL, _IPV4):
        out, n = pattern.subn("[REDACTED]", out)
        redactions += n
    return out, redactions


def call(engine, path, body=None, raw=False, auth=CRED):
    req = urllib.request.Request(engine + path)
    req.add_header("Authorization", "Basic " + auth)
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
    path = REPO_ROOT / "docker" / "processes" / bpmn_file
    boundary = "grouping-quality-corpus-boundary"
    payload = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{bpmn_file}"\r\n'
        f"Content-Type: text/xml\r\n\r\n{path.read_text()}\r\n--{boundary}--\r\n"
    ).encode()
    req = urllib.request.Request(engine + "/repository/deployments", data=payload)
    req.add_header("Authorization", "Basic " + CRED)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    urllib.request.urlopen(req, timeout=15).read()
    if not call(engine, f"/repository/process-definitions?key={key}&latest=true")["total"]:
        sys.exit(f"ERROR: {bpmn_file} deployed to {engine} but '{key}' did not appear — parse failure?")


def await_failed_job(engine, lane, instance_id):
    path = "/management/deadletter-jobs" if lane == "deadletter" else "/management/timer-jobs"
    deadline = time.monotonic() + DEADLINE_S
    while time.monotonic() < deadline:
        page = call(engine, f"{path}?withException=true&processInstanceId={instance_id}")
        if page["data"]:
            return page["data"][0]
        time.sleep(0.5)
    return None


_entry_id = 0


def next_id():
    global _entry_id
    _entry_id += 1
    return _entry_id


def harvest_kind(engine, version, group_id, kind, key, bpmn_file, variables, lane):
    deploy_if_missing(engine, key, bpmn_file)
    entries = []
    for i in range(INSTANCES_PER_KIND):
        vars_i = variables
        if kind == "string-index":  # per-instance length variation -> the ID-stripping proof
            vars_i = [{"name": "orderRef", "type": "string", "value": "ref-" + "x" * i}]
        started = call(engine, "/runtime/process-instances", {"processDefinitionKey": key, "variables": vars_i})
        job = await_failed_job(engine, lane, started["id"])
        if job is None:
            print(f"  WARN {kind}[{i}] on {version}: no failed job within {DEADLINE_S}s — skipped")
            continue
        trace_path = (
            f"/management/{'deadletter-jobs' if lane == 'deadletter' else 'timer-jobs'}"
            f"/{job['id']}/exception-stacktrace"
        )
        stacktrace_raw = "\n".join(call(engine, trace_path, raw=True).splitlines()[:STACKTRACE_MAX_LINES])
        message, msg_redactions = anonymize(job.get("exceptionMessage"))
        stacktrace, trace_redactions = anonymize(stacktrace_raw)
        entries.append(
            {
                "id": next_id(),
                "groupId": group_id,
                "kind": kind,
                "source": "engine",
                "processDefinitionKey": key,
                "engineVersion": version,
                "exceptionMessage": message,
                "stacktrace": stacktrace,
                "redactions": msg_redactions + trace_redactions,
            }
        )
    return entries


def harvest_engine(engine, groups):
    try:
        version = call(engine, "/management/engine")["version"]
    except (urllib.error.URLError, OSError) as e:
        print(f"  SKIP {engine} — not reachable ({e})")
        return []
    entries = []
    for group_id, spec in groups.items():
        for kind, key, bpmn_file, variables, lane in spec["kinds"]:
            got = harvest_kind(engine, version, group_id, kind, key, bpmn_file, variables, lane)
            entries.extend(got)
            print(f"  {version}: {group_id}/{kind} captured {len(got)} entries")
    return entries


def harvest_demo(algo_version):
    """Best-effort: widen the corpus with a couple of REAL rows from the live demo's own
    read API (never the demo engine's REST API — the demo's engines aren't exposed). Only
    CURRENT-generation rows are used; the demo also carries legacy algoVersion=1 rows that
    are historical evidence of the #270 defect class, not corpus material for measuring v2.
    """
    # processDefinitionKey (as seen in countsByEngine) -> groupId, restricted to keys this
    # harvester already knows the true root cause for (never guess a groupId for an unknown
    # key — an unrecognized demo signature is skipped, not mis-labeled).
    KEY_TO_GROUP = {
        "demoFailingPayment": "arithmetic-family",
        "demoFailingRetry": "arithmetic-family",
        "acmeApiOutage": "acme-api-outage",
    }
    try:
        incidents = call(DEMO_BASE, "/api/incidents", auth=DEMO_AUTH)
    except (urllib.error.URLError, OSError) as e:
        print(f"  SKIP demo ({DEMO_BASE}) — not reachable ({e})")
        return []
    entries = []
    for item in incidents.get("items", []):
        if item.get("algoVersion") != algo_version:
            continue  # legacy generation — historical, not v2 corpus material
        keys = {k for per_engine in item.get("countsByEngine", {}).values() for k in per_engine}
        base_keys = {k.split(":")[0] for k in keys}
        group_id = next((KEY_TO_GROUP[k] for k in base_keys if k in KEY_TO_GROUP), None)
        if group_id is None:
            print(f"  demo incident {item.get('id')}: unrecognized definition keys {keys} — skipped (no safe label)")
            continue
        message, msg_redactions = anonymize(item.get("sampleRawMessage"))
        entries.append(
            {
                "id": next_id(),
                "groupId": group_id,
                "kind": group_id + "-demo",
                "source": "demo",
                "processDefinitionKey": ",".join(sorted(k.split(":")[0] for k in keys)),
                "engineVersion": "unknown (demo, via BFF read API not engine REST)",
                "exceptionMessage": message,
                # The demo's read API exposes only the group's sample message, not a
                # per-instance stacktrace — no raw stacktrace to harvest here.
                "stacktrace": "",
                "redactions": msg_redactions,
            }
        )
    print(f"  demo: {len(entries)} current-generation (algoVersion={algo_version}) entries captured")
    return entries


def main():
    # ALGO_VERSION is read from the normalizer source itself (never hand-pinned — the same
    # discipline TEST-STRATEGY §4 requires of the golden corpus) so a future bump doesn't
    # silently mislabel demo rows against a stale constant.
    normalizer_src = (REPO_ROOT / "backend" / "src" / "main" / "java" / "io" / "inspector" / "triage" / "ErrorSignatureNormalizer.java").read_text()
    m = re.search(r"ALGO_VERSION\s*=\s*(\d+)", normalizer_src)
    if not m:
        sys.exit("ERROR: could not read ALGO_VERSION from ErrorSignatureNormalizer.java")
    algo_version = int(m.group(1))

    organic_entries = []
    for engine in ENGINES:
        print(f"Harvesting organic groups from {engine}")
        organic_entries.extend(harvest_engine(engine, ORGANIC_GROUPS))

    print(f"Harvesting adversarial pair from {ENGINES[0]}")
    adversarial_entries = harvest_engine(ENGINES[0], ADVERSARIAL_GROUPS)

    print(f"Harvesting demo (algoVersion={algo_version})")
    organic_entries.extend(harvest_demo(algo_version))

    total_redactions = sum(e["redactions"] for e in organic_entries + adversarial_entries)

    groups_meta = [{"groupId": gid, "description": spec["description"]} for gid, spec in ORGANIC_GROUPS.items()]
    adversarial_groups_meta = [
        {"groupId": gid, "description": spec["description"]} for gid, spec in ADVERSARIAL_GROUPS.items()
    ]

    corpus = {
        "capturedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "algoVersionAtCapture": algo_version,
        "note": (
            "Issue #350 (R4) grouping-quality measurement corpus — NOT the normative "
            "ErrorSignatureGoldenCorpusTest fixture (that one lives under "
            "error-signatures/ and gates the normalizer itself). 'entries' is the primary, "
            "organically-harvested, hand-labeled-ground-truth set the baseline metrics run "
            "against. 'adversarialEntries' is a deliberately engineered near-collision pair "
            "(missing-property vs missing-property-variant), reported separately as a case "
            "study of the normalizer's documented, accepted over-grouping cost — excluded "
            "from the primary threshold verdict so one engineered case can't dominate an "
            "organic-corpus metric. See docs/reviews/R4-GROUPING-QUALITY-2026-08.md."
        ),
        "groups": groups_meta,
        "adversarialGroups": adversarial_groups_meta,
        "entries": organic_entries,
        "adversarialEntries": adversarial_entries,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(corpus, indent=2) + "\n")
    print(
        f"Wrote {len(organic_entries)} organic + {len(adversarial_entries)} adversarial entries "
        f"-> {OUT} ({total_redactions} redaction(s) applied)"
    )
    if len(organic_entries) < 20:
        print("  WARN: organic corpus is thin (<20 entries) — metrics will be noisy")


if __name__ == "__main__":
    main()
