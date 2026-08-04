#!/usr/bin/env python3
"""R2 self-heal baseline measurement + hysteresis backtest (issue #347).

Reads a deployment's incident-ledger READ APIs only (never the database, never
engine tables) and reports:
  1. ledger inventory: incidents, episodes, span, closed-with/without-action
  2. RETRYING-spell extraction from the occurrence series, with corrective-action
     confound marking (via GET /api/bulk overlap)
  3. per-class self-heal rate + Wilson 95% interval, time-to-self-heal p50/p90
  4. the sample-size-floor arithmetic (Wilson bound vs the lane thresholds)
  5. badge replay: naive per-cycle lane vs the proposed floor+hysteresis+dwell
     rule, counting displayed lane flips for each

Usage:
  BASE=https://pi.naumann.cloud USER=viewer PASS=dev python3 scripts/measure-selfheal-baseline.py

Read-only; VIEWER suffices for incidents, the bulk list needs its own read floor.
Documented in docs/RETRYING-RISK-LANE.md §8 and docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md.

NOTE: the design's post-panel §3.1 refinement (outcome judged through spell end +1
bucket) postdates the 2026-08-04 baseline run; it does not change that run's numbers
(both recorded spells escalated inside their own window). Fold it in before the next
gate-check run.
"""
import base64
import datetime as dt
import json
import math
import os
import urllib.request

BASE = os.environ.get("BASE", "https://pi.naumann.cloud")
USER = os.environ.get("USER_", os.environ.get("USER", "viewer"))
PASS = os.environ.get("PASS", "dev")
WINDOW_H = 720  # server clamps to 30 days

# Proposed rule constants (RETRYING-RISK-LANE.md §4) — kept here so the backtest
# provably runs the same numbers the design document quotes.
Z = 1.96                 # 95% Wilson
ENTER_LIKELY = 0.70      # Wilson lower bound to ENTER self-heal-likely
EXIT_LIKELY = 0.60       # leave only when lower bound drops below this
ENTER_UNLIKELY = 0.30    # Wilson upper bound to ENTER self-heal-unlikely
EXIT_UNLIKELY = 0.40
FLOOR_N = 10             # completed, unconfounded spells per class
DWELL_CYCLES = 10        # consecutive complete cycles a new lane must hold
GAP_MIN = 5.0            # a series gap > this (minutes) voids a spanning spell


def fetch(path):
    req = urllib.request.Request(BASE + path)
    tok = base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def parse(ts):
    return dt.datetime.fromisoformat(ts.replace("Z", "+00:00"))


def wilson(healed, n, z=Z):
    if n == 0:
        return (0.0, 1.0)
    p = healed / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    m = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - m) / d, (c + m) / d)


def pct(v, q):
    if not v:
        return None
    s = sorted(v)
    i = max(0, math.ceil(q * len(s)) - 1)
    return s[i]


# ---------------------------------------------------------------- 1. inventory
incidents = fetch("/api/incidents")
print(f"# Ledger inventory @ {BASE} ({dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds')})")
print(f"list truncated={incidents['truncated']}  incidents={len(incidents['items'])}")
details = {}
n_episodes = n_closed = 0
firsts, lasts = [], []
for it in incidents["items"]:
    d = fetch(f"/api/incidents/{it['id']}?window={WINDOW_H}")
    details[it["id"]] = d
    eps = d["episodes"]
    n_episodes += len(eps)
    n_closed += sum(1 for e in eps if e.get("endedAt"))
    firsts.append(parse(it["firstSeen"]))
    lasts.append(parse(it["lastSeen"]))
    print(f"  incident {it['id']}: algo=v{it['algoVersion']} state={it['state']} "
          f"episodes={len(eps)} closed={sum(1 for e in eps if e.get('endedAt'))} "
          f"series_pts={len(d['series'])} relatedBulkJobs={len(d['relatedBulkJobs'])} "
          f"sig={it['signatureHash'][:12]} | {(it.get('exceptionClass') or '?')}")
span_days = (max(lasts) - min(firsts)).total_seconds() / 86400
print(f"episodes total={n_episodes} closed={n_closed} (with action: n/a — none closed) "
      f"live={n_episodes - n_closed}")
print(f"ledger span: {min(firsts).date()} -> {max(lasts).date()} = {span_days:.1f} days")

try:
    bulk = fetch("/api/bulk")
except Exception as e:  # the bulk list read floor may exceed the configured user
    bulk = []
    print(f"NOTE: GET /api/bulk unavailable ({e}) — confound marking degraded")
retry_windows = []  # (submittedAt, finishedAt) of retry-shaped bulk jobs, any scope
for j in bulk:
    if j["verb"] == "retry-job":
        retry_windows.append((parse(j["submittedAt"]),
                              parse(j["finishedAt"] or j["submittedAt"]),
                              j["scopeKind"], j["totalItems"]))
print(f"retry-shaped bulk jobs on record: {len(retry_windows)} "
      f"({', '.join(k for _, _, k, _ in retry_windows)})")

# ------------------------------------------------------- 2. spell extraction
print("\n# RETRYING spells (occurrence-series granularity: 60s sampler beat)")
all_spells = []  # (class_key, start, end, dur_min, outcome, confounded)
for iid, d in details.items():
    inc = d["incident"]
    key = f"{inc['signatureHash'][:12]}/v{inc['algoVersion']}"
    s = d["series"]
    cur = None
    prev_t = None
    for p in s:
        t = parse(p["sampledAt"])
        gap_voids = prev_t is not None and (t - prev_t).total_seconds() / 60 > GAP_MIN
        r, dl, trunc = p["retryingCount"], p["deadLetterCount"], p["truncated"]
        if cur is not None and gap_voids:
            cur["voided"] = True
        if r > 0 and cur is None:
            cur = {"start": t, "dl0": dl, "trunc": trunc, "voided": False}
        elif cur is not None:
            cur["trunc"] = cur["trunc"] or trunc
            if r == 0:
                dur = (t - cur["start"]).total_seconds() / 60
                outcome = "ESCALATED" if dl > cur["dl0"] else "SELF-HEALED"
                confounded = any(a - dt.timedelta(minutes=1) <= cur["start"] <= b + dt.timedelta(minutes=2)
                                 or a <= t <= b + dt.timedelta(minutes=2)
                                 or (cur["start"] - dt.timedelta(minutes=2) <= a <= t)
                                 for a, b, _, _ in retry_windows)
                all_spells.append((key, cur["start"], t, dur, outcome,
                                   confounded, cur["trunc"], cur["voided"]))
                cur = None
        prev_t = t
    if cur is not None:
        all_spells.append((key, cur["start"], None, None, "LIVE-AT-SERIES-END",
                           False, cur["trunc"], cur["voided"]))
for sp in all_spells:
    print(f"  {sp[0]}: {sp[1]} -> {sp[2]} dur={sp[3] if sp[3] is None else round(sp[3], 1)}min "
          f"outcome={sp[4]} confounded={sp[5]} truncated={sp[6]} gap-voided={sp[7]}")
completed = [s for s in all_spells if s[4] in ("ESCALATED", "SELF-HEALED") and not s[7]]
unconfounded = [s for s in completed if not s[5]]
print(f"completed spells={len(completed)}  unconfounded={len(unconfounded)}  "
      f"self-healed={sum(1 for s in unconfounded if s[4] == 'SELF-HEALED')}")

# ------------------------------------------------------------- 3. per-class stats
print("\n# Per-class self-heal statistic (unconfounded completed spells only)")
classes = sorted({s[0] for s in all_spells} | {f"{i['signatureHash'][:12]}/v{i['algoVersion']}"
                                              for i in incidents["items"]})
for key in classes:
    mine = [s for s in unconfounded if s[0] == key]
    healed = [s for s in mine if s[4] == "SELF-HEALED"]
    n = len(mine)
    lb, ub = wilson(len(healed), n)
    durs = [s[3] for s in healed]
    print(f"  {key}: n={n} healed={len(healed)} rate={len(healed)/n if n else float('nan'):.2f} "
          f"wilson95=[{lb:.2f},{ub:.2f}] tts_p50={pct(durs, .5)} tts_p90={pct(durs, .9)} (min)")

# ------------------------------------------------------------- 4. floor math
print("\n# Sample-size-floor arithmetic (Wilson, z=1.96)")
for n in (1, 3, 5, 8, 9, 10, 15, 20):
    lb_perfect = wilson(n, n)[0]
    ub_zero = wilson(0, n)[1]
    print(f"  n={n:>2}: perfect-record lower bound={lb_perfect:.3f}  "
          f"zero-record upper bound={ub_zero:.3f}")
print(f"  -> smallest n where a perfect record clears LB>={ENTER_LIKELY}: "
      f"{next(n for n in range(1, 100) if wilson(n, n)[0] >= ENTER_LIKELY)}")
print(f"  -> smallest n where a zero record clears UB<={ENTER_UNLIKELY}: "
      f"{next(n for n in range(1, 100) if wilson(0, n)[1] <= ENTER_UNLIKELY)}")

# ------------------------------------------------------------- 5. badge replay
print("\n# Badge replay — naive vs proposed rule, whole recorded history")


def lane_naive(h, n):
    """No floor, no interval, point-estimate thresholds, live from n>=1."""
    if n == 0:
        return "NO-DATA"
    p = h / n
    if p >= ENTER_LIKELY:
        return "LIKELY"
    if p <= ENTER_UNLIKELY:
        return "UNLIKELY"
    return "MIXED"


def lane_proposed(h, n, prev):
    if n < FLOOR_N:
        return "INSUFFICIENT"
    lb, ub = wilson(h, n)
    if prev == "LIKELY":
        return "LIKELY" if lb >= EXIT_LIKELY else ("UNLIKELY" if ub <= ENTER_UNLIKELY else "MIXED")
    if prev == "UNLIKELY":
        return "UNLIKELY" if ub <= EXIT_UNLIKELY else ("LIKELY" if lb >= ENTER_LIKELY else "MIXED")
    if lb >= ENTER_LIKELY:
        return "LIKELY"
    if ub <= ENTER_UNLIKELY:
        return "UNLIKELY"
    return "MIXED"


for key in classes:
    # chronological completed-spell outcomes for this class (confounded excluded
    # by the proposed rule; the naive baseline counts everything it sees)
    ev_all = sorted([s for s in completed if s[0] == key], key=lambda s: s[2])
    ev_ok = sorted([s for s in unconfounded if s[0] == key], key=lambda s: s[2])
    # replay on the class's own minute series
    iid = next(i["id"] for i in incidents["items"]
               if f"{i['signatureHash'][:12]}/v{i['algoVersion']}" == key)
    series = details[iid]["series"]
    flips_naive = flips_prop = 0
    st_n = st_p = None
    pending = None
    pending_since = 0
    for idx, p in enumerate(series):
        t = parse(p["sampledAt"])
        h_a = sum(1 for s in ev_all if s[2] <= t and s[4] == "SELF-HEALED")
        n_a = sum(1 for s in ev_all if s[2] <= t)
        h_o = sum(1 for s in ev_ok if s[2] <= t and s[4] == "SELF-HEALED")
        n_o = sum(1 for s in ev_ok if s[2] <= t)
        ln = lane_naive(h_a, n_a)
        if st_n is not None and ln != st_n:
            flips_naive += 1
        st_n = ln
        raw = lane_proposed(h_o, n_o, st_p)
        if st_p is None:
            st_p = raw  # initial state displays immediately (no flip counted)
        elif raw != st_p:
            if raw == pending:
                pending_since += 1
            else:
                pending, pending_since = raw, 1
            if pending_since >= DWELL_CYCLES:
                flips_prop += 1
                st_p = raw
                pending = None
        else:
            pending = None
    print(f"  {key}: samples={len(series)} naive_flips={flips_naive} (final={st_n})  "
          f"proposed_flips={flips_prop} (final={st_p})")
