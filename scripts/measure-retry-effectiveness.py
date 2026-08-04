#!/usr/bin/env python3
"""Retry-vs-data-fix effectiveness, partitioned to remove the always-fail confound
(issue #358, spun out of the #348/#356 measurement pass, docs/ALARM-COST-MODEL.md §5.4).

#348 measured retry-only effectiveness at 1/41 (~2.4%) on the whole pilot audit log and
flagged it as CONFOUNDED: the corpus is dominated by seed processes that fail permanently
by construction, so a retry cannot possibly succeed against them (the same "biased toward
zero by construction" problem #347 found for the self-heal rate on this same corpus). This
script partitions every retry-shaped intervention by whether its TARGET instance's process
definition is a known always-fail fixture or the one class documented as organic
(`acmeApiOutage`; see docs/reviews/R4-GROUPING-QUALITY-2026-08.md), and reports retry-only
vs. data-fix-then-retry effectiveness SEPARATELY per partition. The synthetic partition's
rate is reported for completeness only and is never treated as informative.

## Attribution method (closes part of the #358/#351 attribution gap, R-AUD-03-safe)

#351 (docs/RETRYING-RISK-LANE.md §3.3/§10) already solved a NARROW attribution problem —
confound *detection* for the self-heal lane (did ANY successful retry land on an engine a
class touches, in a time window) — via `AuditEntryRepository#findSuccessfulRetryJobPoints`,
a constructor projection that never selects `AuditEntry.payload`. That mechanism answers a
different question than this script needs (it never resolves WHICH class a retry targeted)
and is reused conceptually, not rebuilt: this script also never reads `payload` anywhere.

Full per-CLASS (signature) attribution was reported unreachable in #348/§5.4 because
`relatedBulkJobs` only joins ERROR_CLASS-scoped bulk envelopes and `payload` is null over
the read API (R-AUD-03). This script closes a materially wider slice of that gap using two
existing, unrelated read endpoints that never touch the audit payload at all:

  1. `GET /api/audit` already exposes `instanceId` on every instance-scoped row (not
     sensitive; it is the row's own identity, not payload).
  2. `GET /api/bulk/{id}` (the bulk job DETAIL, as opposed to the list) exposes a full
     per-ITEM breakdown with `instanceId` and the originating `auditId` for every item of
     ANY scope kind, including FILTER-scoped jobs — not just ERROR_CLASS ones.
  3. `GET /api/instances/{engineId}/{instanceId}` (historic-first: a completed or
     dead-lettered instance still resolves) returns `definitionKey` and the instance's
     CURRENT `flags.hasDeadLetterJobs`/`status` — again, no payload involved.

Composing these three gives, per retry-shaped audit row: which process definition it
targeted (hence which partition) and whether that instance is dead-lettered right now. This
is NOT full signature-level attribution in general (a definitionKey can outlive an
ALGO_VERSION bump and can in principle map to more than one error-message shape), but for
THIS deployment's actual catalog every definitionKey observed maps to exactly one partition
bucket, so it is sufficient for the question #358 asks. Where a definitionKey is not in the
known catalog below, it is reported UNCLASSIFIED rather than guessed — never silently
bucketed (the same honesty rail #351/#353 apply to truncated/blind samples).

## Success signal

A retry's ultimate outcome is read from the TARGET INSTANCE's current state
(`flags.hasDeadLetterJobs`), not from the retry call's own `ok`/`failed` outcome (which only
means "the API call succeeded", i.e. the job was accepted back onto the executable queue —
proven live: every item in the audit log below shows outcome=ok, including ones that
re-dead-lettered). When an instance was retried more than once, every attempt except the
chronologically LAST is inferred FAILED (a later retry would not have been needed
otherwise); the last attempt's outcome is read from current state. This requires the
deployment's audit trail to be quiescent since the retries under measurement (true for the
demo: no operator action recorded in the 8+ days before this script was last run against
it) — a re-run against a deployment with fresh activity after an old retry could
misattribute a since-superseded-by-an-even-later-retry instance; the script does not detect
that case and callers re-running this against a live, active deployment should discount any
instance whose LAST recorded retry is not actually the most recent thing that happened to
it.

Usage:
  BASE=https://pi.naumann.cloud USER_=viewer PASS=dev python3 scripts/measure-retry-effectiveness.py

Read-only; VIEWER suffices for every endpoint used (proven live 2026-08-04).
Documented in docs/reviews/R5-RETRY-EFFECTIVENESS-2026-08.md.
"""
import base64
import datetime as dt
import json
import os
import urllib.request

BASE = os.environ.get("BASE", "https://pi.naumann.cloud")
USER = os.environ.get("USER_", os.environ.get("USER", "viewer"))
PASS = os.environ.get("PASS", "dev")

# ---------------------------------------------------------------------------------------
# Pre-registered sample-size floor (issue #358's own instruction: set the floor BEFORE
# looking at results, exactly as #350 did). This is NOT invented for this script: it is
# the UNCHANGED n>=10 floor from docs/RETRYING-RISK-LANE.md §7.1, derived there from Wilson
# score arithmetic (z=1.96) for a Bernoulli success rate — n=9 is the smallest sample where
# even a perfect record's lower bound clears a 0.70 decisive threshold, n=10 leaves one
# spare observation so a floor-entry verdict is never sitting exactly on a decision
# boundary. This script asks a differently-framed question (retry-only vs
# data-fix-then-retry effectiveness, not a self-heal lane), but it is the same statistical
# object (a per-partition Bernoulli success rate on thin pilot data) and reuses the same
# floor rather than inventing a new number tuned to what this script happens to find.
FLOOR_N = 10

# ---------------------------------------------------------------------------------------
# Partition catalog (issue #358 point 1). ORGANIC = the one class explicitly confirmed
# organic by the issue text and by docs/reviews/R4-GROUPING-QUALITY-2026-08.md (a real
# HTTP-connector failure shape, not a JUEL-expression zoo fixture) — `acmeShippingOutage`
# is R4's adversarial-corpus sibling of the same fixture family, included for
# completeness even though it has not yet been observed live. SYNTHETIC = every seed
# process documented (docs/TEST-SCENARIOS.md, validate-bpmn skill) as deliberately,
# permanently failing by construction. Anything else observed is UNCLASSIFIED and is
# reported, never silently bucketed.
ORGANIC = {"acmeApiOutage", "acmeShippingOutage"}
SYNTHETIC_ALWAYS_FAIL = {
    "demoFailingPayment",
    "demoFailingRetry",
    "zooMethodNotFound",
    "zooMissingProperty",
    "zooMissingPropertyVariant",
    "zooStringIndex",
}

RETRY_CHAIN_ACTIONS = ("retry-job", "edit-variable")


def fetch(path):
    req = urllib.request.Request(BASE + path)
    tok = base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def parse(ts):
    return dt.datetime.fromisoformat(ts.replace("Z", "+00:00"))


def classify(defkey):
    if defkey in ORGANIC:
        return "ORGANIC"
    if defkey in SYNTHETIC_ALWAYS_FAIL:
        return "SYNTHETIC"
    return "UNCLASSIFIED"


# ------------------------------------------------------------------------- 1. fetch corpus
print(f"# Retry-vs-data-fix effectiveness @ {BASE} "
      f"({dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')})")

incidents = fetch("/api/incidents")["items"]
ledger_start = min(parse(i["firstSeen"]) for i in incidents) if incidents else None
print(f"ledger first sighting: {ledger_start} (interventions before this predate the "
      f"incident ledger and are excluded, matching the ALARM-COST-MODEL §5.4 convention)")

audit = fetch("/api/audit?size=500")
print(f"audit log: {len(audit)} rows total")

bulk_jobs = fetch("/api/bulk")
audit_to_bulk = {}  # auditId -> (bulkJobId, scopeKind, scopeLabel)
for j in bulk_jobs:
    detail = fetch(f"/api/bulk/{j['id']}")
    for it in detail.get("items") or []:
        if it.get("auditId"):
            audit_to_bulk[it["auditId"]] = (j["id"], j["scopeKind"], j["scopeLabel"])
print(f"bulk jobs: {len(bulk_jobs)}, {len(audit_to_bulk)} per-item audit rows joined")

# ------------------------------------------------------ 2. resolve instanceId -> defkey
instance_cache = {}


def resolve_instance(engine, instance_id):
    key = (engine, instance_id)
    if key not in instance_cache:
        try:
            instance_cache[key] = fetch(f"/api/instances/{engine}/{instance_id}")
        except Exception:
            instance_cache[key] = None
    return instance_cache[key]


events = []
unresolved = []
for a in audit:
    ts = parse(a["ts"])
    if ledger_start is not None and ts < ledger_start:
        continue
    if a["action"] == "registry-seed" or not a["instanceId"]:
        continue  # config events carry no real instanceId (engine key, not a process instance)
    inst = resolve_instance(a["engineId"], a["instanceId"])
    if inst is None:
        unresolved.append(a)
        continue
    events.append({
        "ts": ts, "engine": a["engineId"], "instanceId": a["instanceId"],
        "action": a["action"], "outcome": a["outcome"], "auditId": a["id"],
        "bulk": audit_to_bulk.get(a["id"]),
        "defkey": inst["definitionKey"],
        "partition": classify(inst["definitionKey"]),
        "hasDeadLetterNow": inst["flags"]["hasDeadLetterJobs"],
        "statusNow": inst["status"],
    })
events.sort(key=lambda e: e["ts"])

print(f"\n{len(events)} attributable interventions from {ledger_start}; "
      f"{len(unresolved)} instanceIds could not be resolved (pre-ledger/purged history — "
      f"listed below, never silently dropped)")
for a in unresolved:
    print(f"  UNRESOLVED: {a['ts']} {a['action']} {a['engineId']}:{a['instanceId']}")

print("\n# definitionKey inventory (all attributable interventions)")
by_key = {}
for e in events:
    by_key.setdefault(e["defkey"], []).append(e)
for k, v in sorted(by_key.items(), key=lambda kv: (kv[1][0]["partition"], kv[0])):
    print(f"  {k}: {len(v)} interventions, partition={v[0]['partition']}")

# ---------------------------------------------------------- 3. per-instance retry chains
print("\n# Per-instance retry chains (retry-job / edit-variable only)")
by_instance = {}
for e in events:
    if e["action"] in RETRY_CHAIN_ACTIONS and e["outcome"] == "ok":
        by_instance.setdefault((e["engine"], e["instanceId"]), []).append(e)

# (partition, is_data_fix_then_retry) -> list[bool success]
outcomes = {}
# distinct TARGET instances per (partition, is_data_fix_then_retry) — repeated retries
# against the SAME instance are not independent trials (most starkly for a
# deterministically-unreachable host: a second retry against it carries no new
# information), so this is the more defensible "n" for judging the floor, alongside the
# raw attempt count above it.
distinct_targets = {}
for (engine, iid), evs in sorted(by_instance.items(), key=lambda kv: kv[1][0]["ts"]):
    evs.sort(key=lambda e: e["ts"])
    retries = [e for e in evs if e["action"] == "retry-job"]
    if not retries:
        continue
    part = retries[0]["partition"]
    print(f"  {engine}/{iid} defkey={retries[0]['defkey']} partition={part}: "
          f"{len(retries)} retry-job attempt(s)")
    last_data_fix_pair = False
    for i, r in enumerate(retries):
        preceding = [x for x in evs if x["ts"] < r["ts"]]
        data_fix_pair = bool(preceding) and preceding[-1]["action"] == "edit-variable"
        is_last = (i == len(retries) - 1)
        success = (not r["hasDeadLetterNow"]) if is_last else False
        note = "current state" if is_last else "superseded by a later retry => inferred failed"
        print(f"      retry@{r['ts'].isoformat()} data_fix_then_retry={data_fix_pair} "
              f"success={success} ({note})")
        outcomes.setdefault((part, data_fix_pair), []).append(success)
        if is_last:
            last_data_fix_pair = data_fix_pair
    # one target instance counts once, under whichever kind its LAST attempt was (that is
    # the attempt whose outcome the instance's current state actually reflects)
    final_success = not retries[-1]["hasDeadLetterNow"]
    distinct_targets.setdefault((part, last_data_fix_pair), []).append(final_success)

# ------------------------------------------------------------------------- 4. the numbers
print(f"\n# Results (raw attempts), gated at the pre-registered floor n>={FLOOR_N} "
      f"(RETRYING-RISK-LANE.md §7.1)")
for part in ("ORGANIC", "SYNTHETIC", "UNCLASSIFIED"):
    for kind, label in ((False, "retry-only"), (True, "data-fix-then-retry")):
        vals = outcomes.get((part, kind), [])
        n = len(vals)
        k = sum(vals)
        rate = f"{k / n:.1%}" if n else "n/a"
        gate = "MEETS FLOOR" if n >= FLOOR_N else "BELOW FLOOR — not reportable as a rate"
        meaning = "" if part == "ORGANIC" else "  (meaningless by construction — not averaged into any organic conclusion)"
        print(f"  {part:12s} {label:20s} n={n:3d} successes={k:3d} rate={rate:>6s}  [{gate}]{meaning}")

print(f"\n# Results (distinct TARGET instances — the more defensible n: repeated retries "
      f"against the SAME instance are not independent trials), same floor n>={FLOOR_N}")
for part in ("ORGANIC", "SYNTHETIC", "UNCLASSIFIED"):
    for kind, label in ((False, "retry-only"), (True, "data-fix-then-retry")):
        vals = distinct_targets.get((part, kind), [])
        n = len(vals)
        k = sum(vals)
        rate = f"{k / n:.1%}" if n else "n/a"
        gate = "MEETS FLOOR" if n >= FLOOR_N else "BELOW FLOOR — not reportable as a rate"
        meaning = "" if part == "ORGANIC" else "  (meaningless by construction)"
        print(f"  {part:12s} {label:20s} n={n:3d} successes={k:3d} rate={rate:>6s}  [{gate}]{meaning}")

if unresolved:
    print(f"\nNOTE: {len(unresolved)} pre-ledger/unresolvable instanceId(s) above are "
          "excluded from every count, not treated as failures or successes.")

print("\n# Honesty note (do not skip when reading the ORGANIC numbers above)")
print("  acmeApiOutage's HTTP task targets an RFC 2606 reserved *.invalid host — it is")
print("  documented (docs/TEST-SCENARIOS.md) as a DETERMINISTIC, permanent dead-letter,")
print("  same as the synthetic zoo fixtures, just with a realistic connector failure")
print("  SHAPE rather than a JUEL-expression bug. A retry against it cannot succeed by")
print("  construction, so even a floor-clearing 0% rate here is NOT evidence about")
print("  genuinely-transient real-world retriability — it is a second, subtler instance")
print("  of the exact always-fail confound this script was written to remove, one level")
print("  deeper than the fixture-vs-organic partition alone can reach.")
