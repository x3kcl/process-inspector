#!/usr/bin/env python3
"""R1 burst-aware frequency amendment — feasibility measurement + replay (#365).

Reads a deployment's incident-ledger READ APIs only (never the database, never
engine tables) and reports, per ALARM-COST-MODEL.md §14:
  1. sampler cadence, coverage and gap distribution from the real occurrence
     series (the data-feasibility substrate for the burst window choice)
  2. burst-bin honesty per candidate window W: the fraction of sliding window
     positions whose bin would read EMPTY (no sample) or UNKNOWN (samples, none
     trusted) under the shipped trusted-delta discipline — the "finest honest
     burst window" measurement
  3. pilot replay: proposed burst-aware A(c) vs shipped A(c) vs count-only over
     every recorded minute bucket of the current-generation series (Kendall tau,
     position changes, flood-gate activations) — §5.5's method, re-run
  4. synthetic scenario proofs (the cases the near-static pilot cannot produce):
     (a) trickle-100-over-28d vs flood-100-in-10min
     (b) birth flood — whole population in one bucket, single-banked (F3)
     (c) wholly-untrusted window — neutral 1, never a fake zero (F2)
     (d) empty history — exact count-only degradation (§5.5)

Usage:
  BASE=https://pi.naumann.cloud USER_=viewer PASS=dev \
      python3 scripts/replay-burst-attention.py [--cache DIR]

--cache DIR reuses/creates raw JSON responses in DIR so a quoted number can be
re-derived from the exact bytes that produced it. Read-only; VIEWER suffices.
Documented in docs/ALARM-COST-MODEL.md §14 (amendment #365).
"""

import argparse
import base64
import bisect
import datetime as dt
import json
import math
import os
import sys
import urllib.request

BASE = os.environ.get("BASE", "https://pi.naumann.cloud")
USER = os.environ.get("USER_", "viewer")
PASS = os.environ.get("PASS", "dev")
WINDOW_H = 720  # server clamps to 30 days PER CALL (IncidentQueryService.MAX_WINDOW_HOURS)
ERA_PAGES = int(os.environ.get("ERA_PAGES", "3"))  # extra `until` pages to walk back (#372)

# ---- proposed §14 constants (kept here so the replay provably runs the same
# ---- numbers the design document quotes) -----------------------------------
BURST_WINDOW_S = 600      # W: ISA-18.2's flood definition window (10 min)
BURST_ONSET = 10          # ISA-18.2 flood onset: >= 10 alarms / 10 min
BURST_EXIT = 5            # ISA-18.2 flood end: rate drops below 5 / 10 min
GAMMA = 8.0               # burst-weight: one flood-window arrival "weighs" gamma
ARRIVALS_WINDOW_S = 28 * 24 * 3600


def fetch(path, cache_dir=None, name=None):
    if cache_dir and name:
        p = os.path.join(cache_dir, name)
        if os.path.exists(p):
            with open(p) as f:
                return json.load(f)
    req = urllib.request.Request(BASE + path)
    tok = base64.b64encode(f"{USER}:{PASS}".encode()).decode()
    req.add_header("Authorization", "Basic " + tok)
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.load(r)
    if cache_dir and name:
        os.makedirs(cache_dir, exist_ok=True)
        with open(os.path.join(cache_dir, name), "w") as f:
            json.dump(data, f)
    return data


def parse(ts):
    return dt.datetime.fromisoformat(ts.replace("Z", "+00:00"))


def log2(x):
    return math.log(x) / math.log(2)


# ---------------------------------------------------------------------------
# The shipped trusted-delta discipline (IncidentOccurrenceRepository.arrivalsSince),
# mirrored row-for-row: a delta is trusted only when BOTH endpoints are fully
# observed (truncated=false AND cycleComplete=true on each side); the window's
# first row is differenceable only when it is the incident's first-ever row
# (baseline seeded 0 — F3), otherwise discarded.
# ---------------------------------------------------------------------------
def deltas(series, first_seen, trust_override_birth=False):
    """Yields (t, delta, differenceable, trusted) per row, LAG over the whole series."""
    out = []
    prev = None
    for row in series:
        t = parse(row["sampledAt"])
        ok = (not row["truncated"]) and row["cycleComplete"]
        if trust_override_birth and prev is None:
            ok = True
        if prev is None:
            birth = t <= first_seen or not out  # first row of a from-birth series
            out.append((t, row["total"], birth, birth and ok))
        else:
            p_ok = (not prev["truncated"]) and prev["cycleComplete"]
            if trust_override_birth and len(out) == 1:
                p_ok = True
            out.append((t, row["total"] - prev["total"], True, ok and p_ok))
        prev = row
    return out


class ClassSeries:
    def __init__(self, name, series, first_seen, live_total, trust_override_birth=False):
        self.name = name
        self.live_total = live_total
        rows = deltas(series, first_seen, trust_override_birth)
        self.ts = [r[0] for r in rows]
        # prefix sums over rows: positive trusted arrivals / observed / trusted counts
        self.cum_arr, self.cum_obs, self.cum_tru = [0], [0], [0]
        for _, d, diffable, trusted in rows:
            self.cum_arr.append(self.cum_arr[-1] + (max(d, 0) if trusted else 0))
            self.cum_obs.append(self.cum_obs[-1] + (1 if diffable else 0))
            self.cum_tru.append(self.cum_tru[-1] + (1 if trusted else 0))

    def _idx(self, t):
        return bisect.bisect_right(self.ts, t)

    def bin(self, t_from, t_to):
        """(arrivals, observed, trusted) over rows with t_from < sampled_at <= t_to."""
        a, b = self._idx(t_from), self._idx(t_to)
        return (self.cum_arr[b] - self.cum_arr[a],
                self.cum_obs[b] - self.cum_obs[a],
                self.cum_tru[b] - self.cum_tru[a])


# ---------------------------------------------------------------------------
# The scoring formulas. R = M = S = 1 throughout (the §5.5 measured pilot state:
# both classes continuously live, zero closed episodes, no R2 statistics) —
# the amendment touches F only, so this is exact, not an approximation.
# ---------------------------------------------------------------------------
def f_shipped(arrivals, observed, trusted):
    if observed > 0 and trusted == 0:
        return 1.0, True                      # F2: unknown, neutral — never fake zero
    return log2(1 + arrivals), False


def f_proposed(arr28, obs28, tru28, burst, b_obs, b_tru, prior_burst, p_obs, p_tru):
    base, unknown28 = f_shipped(arr28, obs28, tru28)
    if unknown28:
        return base, True, False, False       # F2 verbatim: whole window untrusted
    burst_unknown = b_obs > 0 and b_tru == 0
    if burst_unknown:
        return base, False, True, False       # bin unknown: gate OFF, F = shipped F
    # Entry: a genuine ISA onset in the CURRENT window. Hold: still >= exit while the
    # onset sits in the PRIOR window. prior alone must reach onset — summing the two
    # windows would open a back-door entry (6+6 across 20 min is not a 10-min flood).
    flooding = burst >= BURST_ONSET or (
        burst >= BURST_EXIT and prior_burst >= BURST_ONSET)
    if flooding:
        return log2(1 + arr28 + (GAMMA - 1) * burst), False, False, True
    return base, False, False, False


def order(rows, key):
    """rows: list of (name, live_total, score). Total order: score DESC, total DESC, name ASC."""
    return [r[0] for r in sorted(rows, key=lambda r: (-key(r), -r[1], r[0]))]


def kendall_tau(a, b):
    pos = {x: i for i, x in enumerate(b)}
    n = len(a)
    if n < 2:
        return 1.0
    conc = disc = 0
    for i in range(n):
        for j in range(i + 1, n):
            if (pos[a[i]] - pos[a[j]]) < 0:
                conc += 1
            else:
                disc += 1
    return (conc - disc) / (conc + disc)


def era_boundary(series):
    """The sampledAt of the first row of the CURRENT era, or None when none is reachable.

    An era boundary is a row whose recorded `fleet` differs from its predecessor's, or whose
    scope is unrecorded (""). Unrecorded is never comparable — not even to another unrecorded
    row — so a "" row always opens a new era (ALARM-COST-MODEL §16.5/§16.7).
    """
    start = None
    prev = None
    for row in series:
        fleet = row.get("fleet", "")
        if prev is not None and fleet != prev:
            # A CHANGE (only reachable at index >= 1 — the first row's own predecessor is
            # outside this page, which is exactly why the caller pages backward).
            start = row["sampledAt"] if fleet else None
        prev = fleet
    return start


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=None)
    args = ap.parse_args()

    incidents = fetch("/api/incidents", args.cache, "incidents.json")
    items = incidents["items"] if isinstance(incidents, dict) else incidents
    current = [i for i in items if i.get("currentGeneration", i["algoVersion"] == 2)]
    print(f"# ledger: {len(items)} incidents, {len(current)} current-generation "
          f"({', '.join(str(i['id']) for i in current)})")

    details = {}
    for i in current:
        details[i["id"]] = fetch(f"/api/incidents/{i['id']}?window={WINDOW_H}",
                                 args.cache, f"inc{i['id']}.json")

    # ---------------- 0. era boundaries (#372) ----------------
    # `fleet` is the row's observation SCOPE — the canonical sorted enabled-engine ids the pass
    # fanned out over. An ERA BOUNDARY is any point where the recorded fleet differs from the
    # previous one, or where scope is unrecorded (""). G5 is measured within the CURRENT era only
    # (ALARM-COST-MODEL §16.7), and it needs >= 56 d of trusted span while ONE call reaches 30 —
    # so when no boundary is visible in the newest window we page BACKWARD with the `until`
    # cursor. Each call stays bounded to one clamped window; only the reachable total span grows.
    print("\n## 0. observation SCOPE / era boundaries (MEASURED, #372)")
    for iid, d in details.items():
        series = list(d["series"])
        oldest = series[0]["sampledAt"] if series else None
        pages = 0
        while oldest is not None and pages < ERA_PAGES and not era_boundary(series):
            page = fetch(f"/api/incidents/{iid}?window={WINDOW_H}&until={oldest}",
                         args.cache, f"inc{iid}-until-{pages}.json")
            older = page["series"]
            if not older:
                break
            series = older + series
            oldest = series[0]["sampledAt"]
            pages += 1
        boundary = era_boundary(series)
        fleets = sorted({r.get("fleet", "") for r in series})
        span_d = ((parse(series[-1]["sampledAt"]) - parse(series[0]["sampledAt"])).total_seconds()
                  / 86400) if series else 0.0
        print(f"- incident {iid}: {len(series)} rows over {span_d:.1f} d "
              f"({pages} extra cursor page(s)); fleets seen {fleets}; "
              f"current era starts {boundary or 'BEFORE the reachable window'}")

    # ---------------- 1. cadence / coverage / gaps ----------------
    print("\n## 1. sampler cadence & coverage (MEASURED)")
    for iid, d in details.items():
        s = d["series"]
        ts = [parse(r["sampledAt"]) for r in s]
        spac = [(b - a).total_seconds() for a, b in zip(ts, ts[1:])]
        span_min = (ts[-1] - ts[0]).total_seconds() / 60
        gaps = [x for x in spac if x > 60]
        blind = sum(1 for r in s if not r["cycleComplete"])
        trunc = sum(1 for r in s if r["truncated"])
        from collections import Counter
        top = Counter(spac).most_common(3)
        print(f"incident {iid}: {len(s)} points over {span_min:.0f} min "
              f"(coverage {100*len(s)/(span_min+1):.2f}%)")
        print(f"  spacing mode {top}; gaps>60s: {len(gaps)} "
              f"(max {max(gaps)/60:.1f} min, total {sum(gaps)/60:.1f} min)")
        print(f"  blind rows (cycleComplete=false): {blind}; truncated rows: {trunc}")

    # ---------------- 2. burst-bin honesty per candidate window ----------------
    print("\n## 2. burst-bin honesty — % of sliding positions EMPTY / UNKNOWN per W")
    for iid, d in details.items():
        inc = d["incident"]
        cs = ClassSeries(str(iid), d["series"], parse(inc["firstSeen"]), inc["lastTotal"])
        # the trusted era: positions from the first complete-cycle row onward — on a
        # deployment with a blind prefix (V21 backfill / a declared-but-dead engine)
        # the whole-series fraction is a history artifact, not a cadence property
        complete = [parse(r["sampledAt"]) for r in d["series"] if r["cycleComplete"]]
        era_start = complete[0] if complete else None
        print(f"incident {iid} (trusted era starts {era_start}):")
        # evaluate at EVERY minute-grid instant, not just at sample instants: the
        # aggregate runs at model-build time, which lands anywhere — a bin that
        # contains no sample must be measurable, and sample-anchored positions
        # structurally cannot be empty
        grid, t = [], cs.ts[0]
        while t <= cs.ts[-1]:
            grid.append(t)
            t += dt.timedelta(seconds=60)
        for w_min in (2, 5, 10, 15):
            w = w_min * 60
            empty = unknown = era_n = era_unknown = 0
            n = len(grid)
            for t in grid:
                _, obs, tru = cs.bin(t - dt.timedelta(seconds=w), t)
                bad = 0
                if obs == 0:
                    empty += 1
                elif tru == 0:
                    unknown += 1
                    bad = 1
                if era_start and t >= era_start + dt.timedelta(seconds=w):
                    era_n += 1
                    era_unknown += bad
            era = (f"  trusted-era unknown {100*era_unknown/era_n:.3f}% (n={era_n})"
                   if era_n else "  trusted-era: no full-window position yet")
            print(f"  W={w_min:>2} min: empty {100*empty/n:.3f}%  "
                  f"unknown {100*unknown/n:.3f}%{era}")

    # ---------------- 3. pilot replay ----------------
    print("\n## 3. pilot replay — proposed vs shipped vs count-only, every bucket")
    for label, override in (("as recorded", False), ("birth-trusted counterfactual", True)):
        classes = []
        totals = {}  # name -> (parsed ts list, totals list), computed ONCE
        for iid, d in details.items():
            inc = d["incident"]
            classes.append(ClassSeries(str(iid), d["series"], parse(inc["firstSeen"]),
                                       inc["lastTotal"], trust_override_birth=override))
            totals[str(iid)] = ([parse(r["sampledAt"]) for r in d["series"]],
                                [r["total"] for r in d["series"]])
        all_ts = sorted({t for c in classes for t in c.ts})
        mism_prop = mism_ship = 0
        gate_buckets = {c.name: 0 for c in classes}
        taus = []
        for t in all_ts:
            scored = []
            for c in classes:
                c_ts, c_tot = totals[c.name]
                j = bisect.bisect_right(c_ts, t) - 1
                total = c_tot[j] if j >= 0 else 0
                arr28, obs28, tru28 = c.bin(t - dt.timedelta(seconds=ARRIVALS_WINDOW_S), t)
                burst, b_obs, b_tru = c.bin(t - dt.timedelta(seconds=BURST_WINDOW_S), t)
                prior, p_obs, p_tru = c.bin(t - dt.timedelta(seconds=2 * BURST_WINDOW_S),
                                            t - dt.timedelta(seconds=BURST_WINDOW_S))
                fs, _ = f_shipped(arr28, obs28, tru28)
                fp, _, _, gate = f_proposed(arr28, obs28, tru28, burst, b_obs, b_tru,
                                            prior, p_obs, p_tru)
                if gate:
                    gate_buckets[c.name] += 1
                scored.append((c.name, total, fs, fp))
            o_count = order([(n, tot, 0) for n, tot, _, _ in scored], key=lambda r: r[2])
            o_ship = order([(n, tot, fs) for n, tot, fs, _ in scored], key=lambda r: r[2])
            o_prop = order([(n, tot, fp) for n, tot, _, fp in scored], key=lambda r: r[2])
            if o_ship != o_count:
                mism_ship += 1
            if o_prop != o_count:
                mism_prop += 1
            taus.append(kendall_tau(o_prop, o_count))
        print(f"[{label}] buckets: {len(all_ts)}")
        print(f"  shipped vs count-only: {mism_ship} ordering mismatches")
        print(f"  proposed vs count-only: {mism_prop} ordering mismatches, "
              f"Kendall tau (mean) = {sum(taus)/len(taus):.4f}, "
              f"top-N position changes = {mism_prop}")
        print(f"  flood-gate-active buckets per class: {gate_buckets}")

    # ---------------- 4. synthetic scenarios ----------------
    print("\n## 4. synthetic scenario proofs (R = M = S = 1 unless stated)")

    def f_of(arr28, burst, prior=0, obs28=1, tru28=1, b_obs=1, b_tru=1):
        return f_proposed(arr28, obs28, tru28, burst, b_obs, b_tru, prior, 1, 1)

    # (a) trickle vs flood, both 100 arrivals / 28d, both last seen now
    fp_trickle, *_ = f_of(100, 0, b_obs=10, b_tru=10)
    fp_flood, _, _, gate = f_of(100, 100)
    fs_both, _ = f_shipped(100, 1, 1)
    print(f"(a) trickle-100/28d: shipped F={fs_both:.2f} proposed F={fp_trickle:.2f}")
    print(f"    flood-100/10min: shipped F={fs_both:.2f} proposed F={fp_flood:.2f} "
          f"(gate={gate}) -> flood ranks {'ABOVE' if fp_flood > fp_trickle else 'level with'}"
          f" trickle; shipped could not distinguish them")
    fp_sub, _, _, gate_sub = f_of(100, BURST_ONSET - 1)
    print(f"    sub-onset ({BURST_ONSET-1} in W): proposed F={fp_sub:.2f} (gate={gate_sub})"
          f" — byte-identical to shipped below the ISA onset")
    fp_hyst, _, _, gate_h = f_of(100, 7, prior=12)
    fp_ended, _, _, gate_e = f_of(100, 4, prior=12)
    print(f"    hysteresis: 7 in W after a 12-arrival prior window -> gate={gate_h}; "
          f"4 in W -> gate={gate_e} (ISA asymmetric onset {BURST_ONSET}/exit {BURST_EXIT})")
    _, _, _, gate_bd = f_of(100, 6, prior=6)
    print(f"    no back-door entry: 6 in W + 6 in prior (12/20min, never an onset) "
          f"-> gate={gate_bd}")

    # (b) birth flood: 5000 born in one bucket — single-banked
    fp_birth, *_ = f_of(5000, 5000)
    print(f"(b) birth-5000-in-one-bucket: proposed F={fp_birth:.2f} "
          f"= log2(1 + {GAMMA:.0f}*5000) = {log2(1+GAMMA*5000):.2f} — the birth arrival is"
          f" counted ONCE, at weight gamma (partition; nothing is added on top of a term"
          f" that already contains it). Shipped F={log2(5001):.2f}.")
    print(f"    double-banked strawman (separate multiplier on top of F): "
          f"{log2(5001):.2f} * clamp = would count the same 5000 twice — rejected shape")

    # (c) wholly-untrusted window (F2) — and untrusted bin only
    fp_u, unknown28, _, gate_u = f_proposed(0, 10, 0, 0, 5, 0, 0, 0, 0)
    print(f"(c) wholly-untrusted 28d window: proposed F={fp_u:.1f} arrivalsUnknown="
          f"{unknown28} gate={gate_u} — neutral 1, never a fake zero (F2 verbatim)")
    fp_bu, _, b_unknown, gate_bu = f_proposed(50, 10, 10, 40, 8, 0, 0, 1, 1)
    print(f"    burst-bin-only untrusted: proposed F={fp_bu:.2f} (= shipped log2(51)="
          f"{log2(51):.2f}), burstUnknown={b_unknown}, gate={gate_bu} — an unknown bin"
          f" can only suppress a promotion, never demote")

    # (d) empty history: exact count-only degradation
    corpus = [("h-a", 300), ("h-b", 21), ("h-c", 8), ("h-d", 1), ("h-e", 57)]
    scored = []
    for name, total in corpus:
        fp, *_ = f_proposed(0, 0, 0, 0, 0, 0, 0, 0, 0)
        scored.append((name, total, fp))
    o_prop = order(scored, key=lambda r: r[2])
    o_count = order([(n, t, 0) for n, t, _ in scored], key=lambda r: r[2])
    print(f"(d) empty-history corpus: every proposed F={scored[0][2]:.1f}; order "
          f"{o_prop} == count-only {o_count}: {o_prop == o_count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
