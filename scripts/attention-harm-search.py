#!/usr/bin/env python3
"""Adversarial harm search on the SHIPPED attention-ordering score A(c) = F·R·M·S.

The question, stated so that it cannot be circular: **does A(c) order Stage-0 cards
closer to the cost-minimising order than today's `total DESC` does, and where does it
do WORSE?**

Non-circularity is structural, not asserted. The score is a STATIC FEATURE COMBINATION
of ledger evidence (arrivals, lastSeen age, closed-episode medians, self-heal lane). The
ground truth is an OUTCOME INTEGRAL over hidden dynamics — total unresolved
instance-minutes accumulated by an operator working classes sequentially:

    cost(order) = SUM_c  INTEGRAL_0^{T_c} n_c(t) dt ,   n_c(t) = n0_c + g_c*t
                = SUM_c  n0_c*T_c + g_c*T_c^2/2

where T_c is the instant class c stopped accruing (self-healed, operator-resolved, or the
horizon ended). No factor of the score appears anywhere in that integral; the score only
ever sees an OBSERVATION of the same underlying fleet.

    HARM(fleet) := regret(attention) > regret(count),  regret(p) = cost(p) - cost(oracle)

The oracle CANCELS out of the harm predicate: regret(a) > regret(c) <=> cost(a) > cost(c).
So every harm verdict in this script is exact and independent of how the oracle is
computed; the oracle is used only to NORMALISE the magnitude of a regret gap.

Everything the score reads is ported from
`backend/src/main/java/io/inspector/attention/AttentionScoreCalculator.java` (+
`AttentionConfig` / `InspectorProperties.Attention` defaults, `AttentionOrdering`,
`AttentionRationale.laneOf`, `Quantiles`), including the §4.1a burst term. `selftest()`
pins the port against independently verified values and ABORTS on any mismatch.

Usage:
    python3 scripts/attention-harm-search.py             # full run (~5-8 min)
    python3 scripts/attention-harm-search.py --quick     # 10x smaller, same code paths
    python3 scripts/attention-harm-search.py --selftest-only

Pure simulation: no engine calls, no HTTP, no database, no live-demo mutation. Seeded RNG
throughout -> byte-identical output for a given --seed.

Results are written up in `docs/reviews/R-ATTENTION-HARM-2026-08.md`.
"""

import argparse
import itertools
import json
import math
import sys
from dataclasses import dataclass, field

import numpy as np

# ---------------------------------------------------------------------------
# 1. THE SHIPPED SCORE — ported line for line from AttentionScoreCalculator.java
# ---------------------------------------------------------------------------

# AttentionScoreCalculator: lane -> p_heal at the §4.1 band midpoints. Constants, not knobs.
P_HEAL_LIKELY = 0.75
P_HEAL_MIXED = 0.50
P_HEAL_UNLIKELY = 0.15
LANE_P_HEAL = {
    "SELF_HEAL_LIKELY": P_HEAL_LIKELY,
    "SELF_HEAL_MIXED": P_HEAL_MIXED,
    "SELF_HEAL_UNLIKELY": P_HEAL_UNLIKELY,
}


@dataclass(frozen=True)
class AttentionCfg:
    """InspectorProperties.Attention *OrDefault() values, verbatim."""

    recency_half_life_s: float = 24 * 3600.0  # Duration.ofHours(24)
    arrivals_window_days: int = 28
    min_closed_episodes: int = 3
    mttr_clamp_low: float = 0.5
    mttr_clamp_high: float = 2.0
    self_heal_floor: float = 0.25
    burst_window_s: float = 600.0  # Duration.ofMinutes(10)
    burst_onset: int = 10
    burst_exit: int = 5
    burst_weight: float = 8.0  # gamma


DEFAULTS = AttentionCfg()


def log2(x):
    return math.log(x) / math.log(2)


def burst_arrivals(arrivals, burst):
    """AttentionScoreCalculator.burstArrivals — the bin clamped into its own window total."""
    a = max(0, arrivals)
    return min(max(0, burst), a)


def flooding(arrivals_unknown, burst_unknown, arrivals, burst, prior_burst, cfg=DEFAULTS):
    """AttentionScoreCalculator.flooding — the stateless two-bin Schmitt trigger."""
    if arrivals_unknown or burst_unknown:
        return False
    b = burst_arrivals(arrivals, burst)
    p = max(0, prior_burst)
    return b >= cfg.burst_onset or (b >= cfg.burst_exit and p >= cfg.burst_onset)


def frequency(arrivals, arrivals_unknown, burst, prior_burst, burst_unknown, cfg=DEFAULTS):
    """AttentionScoreCalculator.frequency — log2(1+arrivals), burst-decomposed under the gate."""
    if arrivals_unknown:
        return 1.0
    a = max(0, arrivals)
    if not flooding(arrivals_unknown, burst_unknown, a, burst, prior_burst, cfg):
        return log2(1 + a)
    b = burst_arrivals(a, burst)
    outside = a - b
    return log2(1 + outside + cfg.burst_weight * b)


def recency(age_seconds, cfg=DEFAULTS):
    """AttentionScoreCalculator.recency — 2^(-age/tau); a null/future lastSeen reads 1."""
    tau = max(1.0, cfg.recency_half_life_s)
    if age_seconds <= 0:
        return 1.0
    return math.pow(2.0, -(age_seconds / tau))


def mttr_factor(class_median_s, fleet_median_s, cfg=DEFAULTS):
    """AttentionScoreCalculator.mttrFactor — neutral 1 whenever either side is unknown."""
    if class_median_s is None or fleet_median_s is None or fleet_median_s <= 0:
        return 1.0
    ratio = class_median_s / fleet_median_s
    return max(cfg.mttr_clamp_low, min(cfg.mttr_clamp_high, ratio))


def self_heal_factor(lane, cfg=DEFAULTS):
    """AttentionScoreCalculator.selfHealFactor — max(1-p_heal, floor); absent lane = 1."""
    if lane is None or lane == "INSUFFICIENT_HISTORY":
        return 1.0
    return max(cfg.self_heal_floor, 1.0 - LANE_P_HEAL[lane])


def median(values):
    """Quantiles.median — nearest-rank, un-interpolated (matches io.inspector.attention)."""
    s = sorted(values)
    n = len(s)
    if n == 0:
        return None
    rank = max(1, min(n, math.ceil(0.5 * n)))  # Quantiles.percentile(v, 0.5)
    return s[rank - 1]


def factor_values(ev, fleet_median_mttr_s, cfg=DEFAULTS):
    f = frequency(ev.arrivals, ev.arrivals_unknown, ev.burst, ev.prior_burst, ev.burst_unknown, cfg)
    r = recency(ev.age_s, cfg)
    class_med = ev.med_mttr_s if (ev.closed_episodes >= cfg.min_closed_episodes) else None
    m = mttr_factor(class_med, fleet_median_mttr_s, cfg)
    s = self_heal_factor(ev.lane, cfg)
    return f, r, m, s


def attention_score(ev, fleet_median_mttr_s, cfg=DEFAULTS, factors=("F", "R", "M", "S")):
    """A(c) = F·R·M·S. `factors` ablates terms to 1.0; "1/M" inverts M (direction probe)."""
    f, r, m, s = factor_values(ev, fleet_median_mttr_s, cfg)
    out = 1.0
    out *= f if "F" in factors else 1.0
    out *= r if "R" in factors else 1.0
    if "1/M" in factors:
        out *= 1.0 / m
    elif "M" in factors:
        out *= m
    out *= s if "S" in factors else 1.0
    return out


# ---------------------------------------------------------------------------
# 2. SELF-TEST — pin the port. Any mismatch aborts before a single fleet is run.
# ---------------------------------------------------------------------------

TOL = 1.5e-3


def _chk(failures, label, got, want, tol=TOL):
    ok = abs(got - want) <= tol
    print(f"  {'PASS' if ok else 'FAIL'}  {label:<58} got {got:.6f}  want {want}")
    if not ok:
        failures.append(label)


def _chkb(failures, label, got, want):
    ok = got == want
    print(f"  {'PASS' if ok else 'FAIL'}  {label:<58} got {got}  want {want}")
    if not ok:
        failures.append(label)


def selftest():
    print("## 0. self-test — pinning the port against independently verified values")
    fails = []
    cfg = DEFAULTS

    # --- F: trickle vs flood, both 100 arrivals in the 28d window -----------
    _chk(fails, "F  trickle 100 arrivals/28d, no flood", frequency(100, False, 0, 0, False, cfg), 6.658)
    # outside = 100-100 = 0, so this is log2(1 + 8*100) = log2(801) = 9.645658
    _chk(fails, "F  flood 100 arrivals inside W (log2(801))", frequency(100, False, 100, 0, False, cfg), 9.646)

    # --- birth-5000 in W: shipped 12.288 -> burst 15.288 --------------------
    _chk(fails, "F  birth-5000, no burst evidence (shipped)", frequency(5000, False, 0, 0, False, cfg), 12.288)
    _chk(fails, "F  birth-5000 inside W (single-banked at gamma)", frequency(5000, False, 5000, 0, False, cfg), 15.288)

    # --- inflation ceiling: formula and ATTAINED max ------------------------
    ceiling = 1 + log2(cfg.burst_weight) / log2(1 + cfg.burst_onset)
    _chk(fails, "F  inflation ceiling 1+log2(g)/log2(1+onset)", ceiling, 1.867)
    # attained max over PHYSICALLY REACHABLE (arrivals, burst, prior): both bins are subsets
    # of the same 28d window, so arrivals >= burst + prior.
    best, best_at = 0.0, None
    for a in range(1, 4001):
        for b in range(0, min(a, 60) + 1):
            for p in (0, cfg.burst_onset, cfg.burst_onset + 5):
                if b + p > a or not flooding(False, False, a, b, p, cfg):
                    continue
                base = log2(1 + a)
                if base <= 0:
                    continue
                ratio = frequency(a, False, b, p, False, cfg) / base
                if ratio > best:
                    best, best_at = ratio, (a, b, p)
    _chk(fails, f"F  ATTAINED max inflation (at a,b,prior={best_at})", best, 1.833)

    # --- gate truth table (onset 10 / exit 5) -------------------------------
    _chkb(fails, "gate entry      burst=12                 -> ON", flooding(False, False, 100, 12, 0, cfg), True)
    _chkb(fails, "gate hold       burst=7,  prior=12       -> ON", flooding(False, False, 100, 7, 12, cfg), True)
    _chkb(fails, "gate drop       burst=4,  prior=12       -> OFF", flooding(False, False, 100, 4, 12, cfg), False)
    _chkb(fails, "gate back-door  burst=6,  prior=6        -> OFF", flooding(False, False, 100, 6, 6, cfg), False)
    _chkb(fails, "gate empty      burst=0,  prior=0        -> OFF", flooding(False, False, 100, 0, 0, cfg), False)
    _chkb(fails, "gate untrusted 28d window                -> OFF", flooding(True, False, 100, 50, 50, cfg), False)
    _chkb(fails, "gate untrusted burst bin                 -> OFF", flooding(False, True, 100, 50, 50, cfg), False)

    # --- F degradation rules -------------------------------------------------
    _chk(fails, "F  wholly-untrusted window -> neutral 1", frequency(0, True, 0, 0, False, cfg), 1.0)
    _chk(fails, "F  no in-window row at all -> 0 (fleet-uniform)", frequency(0, False, 0, 0, False, cfg), 0.0)

    # --- F COMPRESSION: 100x true ratio -> 2.23x score ratio (the hypothesis) --
    comp = frequency(4000, False, 0, 0, False, cfg) / frequency(40, False, 0, 0, False, cfg)
    _chk(fails, "F  compression 40 -> 4000 members (100x) reads as", comp, 2.23, tol=5e-3)

    # --- the DOMINANCE arithmetic behind the compression hypothesis ---------
    # F's dynamic range over a fleet is a RATIO OF LOGS; M*S's is a fixed 16x
    # ((2.0*1.0)/(0.5*0.25)). Whenever F's range is the smaller one, M and S decide the
    # order and F only breaks their ties — which is the whole harm mechanism.
    ms_span = (DEFAULTS.mttr_clamp_high * 1.0) / (DEFAULTS.mttr_clamp_low * DEFAULTS.self_heal_floor)
    _chk(fails, "M*S dynamic range (2.0*1.0)/(0.5*0.25)", ms_span, 16.0)
    f_span_1000x = frequency(40000, False, 0, 0, False, cfg) / frequency(40, False, 0, 0, False, cfg)
    _chk(fails, "F dynamic range over a 1000x arrival ratio", f_span_1000x, 2.854, tol=5e-3)
    # the crossover: with the quietest class at `lo` arrivals, F out-ranges M*S only once the
    # busiest class reaches 2^(16*log2(1+lo)) - 1 arrivals.
    crossover = 2 ** (ms_span * log2(1 + 2)) - 1
    _chkb(fails, "F out-ranges M*S only above ~4.3e7 arrivals (lo=2)", crossover > 4.0e7, True)

    # --- M = clamp(medMTTR(c)/medMTTR(fleet), 0.5, 2) ------------------------
    _chk(fails, "M  ratio 2x   -> 2.0", mttr_factor(120, 60, cfg), 2.0)
    _chk(fails, "M  ratio 10x  -> 2.0 (clamped)", mttr_factor(600, 60, cfg), 2.0)
    _chk(fails, "M  ratio 50x  -> 2.0 (clamped)", mttr_factor(3000, 60, cfg), 2.0)
    _chk(fails, "M  ratio 0.01 -> 0.5 (clamped)", mttr_factor(1, 100, cfg), 0.5)
    _chk(fails, "M  <3 closed episodes -> neutral 1", mttr_factor(None, 60, cfg), 1.0)
    _chk(fails, "M  fleet has no closed episode -> neutral 1", mttr_factor(600, None, cfg), 1.0)

    # --- S = max(1 - p_heal, 0.25) -------------------------------------------
    _chk(fails, "S  SELF_HEAL_LIKELY  (p=.75) -> 0.25 floor", self_heal_factor("SELF_HEAL_LIKELY", cfg), 0.25)
    _chk(fails, "S  SELF_HEAL_MIXED   (p=.50) -> 0.50", self_heal_factor("SELF_HEAL_MIXED", cfg), 0.50)
    _chk(fails, "S  SELF_HEAL_UNLIKELY(p=.15) -> 0.85", self_heal_factor("SELF_HEAL_UNLIKELY", cfg), 0.85)
    _chk(fails, "S  INSUFFICIENT_HISTORY -> neutral 1", self_heal_factor("INSUFFICIENT_HISTORY", cfg), 1.0)
    _chk(fails, "S  no lane at all -> neutral 1", self_heal_factor(None, cfg), 1.0)
    # STRUCTURAL: the floor is INERT under the shipped lane adapter — the most demoting
    # lane midpoint is exactly 1 - 0.75 = 0.25, so max(floor, 1-p) never selects the floor.
    zero_floor = AttentionCfg(self_heal_floor=0.0)
    same = all(
        abs(self_heal_factor(lane, cfg) - self_heal_factor(lane, zero_floor)) < 1e-12
        for lane in list(LANE_P_HEAL) + ["INSUFFICIENT_HISTORY", None]
    )
    _chkb(fails, "S  floor 0.25 is INERT (never binds at any lane)", same, True)

    # --- R = 2^(-age_hours/24) ----------------------------------------------
    _chk(fails, "R  age 0 h   -> 1.0", recency(0, cfg), 1.0)
    _chk(fails, "R  age 24 h  -> 0.5", recency(24 * 3600, cfg), 0.5)
    _chk(fails, "R  age 72 h  -> 0.125", recency(72 * 3600, cfg), 0.125)

    # --- tie-break: total DESC, then signatureHash ASC (R-SEM-23) -----------
    rows = [("aa", 5, 0.0), ("bb", 9, 0.0), ("cc", 9, 0.0), ("dd", 100, 1.0)]
    got = [r[0] for r in sorted(rows, key=lambda r: (-r[2], -r[1], r[0]))]
    _chkb(fails, "tie-break  score DESC, total DESC, hash ASC", got, ["dd", "bb", "cc", "aa"])

    # --- neutrality guarantee: empty ledger == today's count-only order ------
    ev = [ClassEvidence(0, False, 0, 0, False, 0.0, None, 0, None) for _ in range(5)]
    totals = [300, 21, 8, 1, 57]
    hashes = [f"h{i}" for i in range(5)]
    sc = [attention_score(e, None) for e in ev]
    att = [h for _, _, h in sorted(zip(sc, totals, hashes), key=lambda t: (-t[0], -t[1], t[2]))]
    cnt = [h for _, h in sorted(zip(totals, hashes), key=lambda t: (-t[0], t[1]))]
    _chkb(fails, "empty ledger -> attention order == count-only order", att, cnt)

    print(f"  -> {len(fails)} failure(s)")
    if fails:
        print("\nABORT: the port disagrees with the pinned values; fix the port before running.")
        print("       failing checks: " + ", ".join(fails))
        sys.exit(2)
    print("  self-test OK — the port is the shipped formula.\n")


# ---------------------------------------------------------------------------
# 3. THE GROUND TRUTH — an outcome integral, sharing NO term with the score
# ---------------------------------------------------------------------------


@dataclass
class ClassEvidence:
    """What the BFF's ledger aggregates would report for this class — the score's inputs."""

    arrivals: int
    arrivals_unknown: bool
    burst: int
    prior_burst: int
    burst_unknown: bool
    age_s: float
    med_mttr_s: float | None
    closed_episodes: int
    lane: str | None


@dataclass
class Fleet:
    """Hidden dynamics (cost side) + observed evidence (score side), kept strictly apart."""

    # --- hidden dynamics: ONLY these enter the cost integral ----------------
    n0: np.ndarray  # current failing members
    g: np.ndarray  # arrivals per minute, NOW
    mttr: np.ndarray  # operator wall-clock to fix the class, minutes
    p_heal: np.ndarray  # probability the class clears on its own
    t_heal: np.ndarray  # when it would clear, minutes from now
    horizon: float
    # --- observed evidence: ONLY these enter the score ----------------------
    evidence: list = field(default_factory=list)
    fleet_median_mttr_s: float | None = None
    sig: list = field(default_factory=list)
    regime: str = ""
    knobs: dict = field(default_factory=dict)

    @property
    def K(self):
        return len(self.n0)


def expected_costs(fleet, orders, committed=True, severity=None):
    """E[cost] for every candidate order, EXACT by enumerating self-heal realisations.

    Operator model (assumptions, all listed in the report's ledger):
      * strictly sequential, one class at a time, no context switching, no partial fixes;
      * a class already self-healed when its turn comes is SKIPPED at zero operator cost
        (the card is gone from the board);
      * `committed=True`: a class that heals WHILE being worked still consumes the full
        mttr but stops accruing at the heal instant. This is deliberately the assumption
        that FAVOURS the score — it makes wasted work on a self-healer maximally
        expensive, i.e. maximally rewards the S factor. `committed=False` is the
        sensitivity (the operator notices and moves on).
      * past the horizon nothing more is fixed; unreached classes accrue to the horizon.

    `severity` (optional, GT-B): a per-class weight on the cost integrand, with the
    operator's service time held UNIFORM. That is the charitable reading of §3's
    `c_miss(c)` — MTTR as a per-instance expensiveness proxy rather than as a service time.
    """
    K = fleet.K
    if severity is None:
        n0, gg, mttr = fleet.n0, fleet.g, fleet.mttr
    else:
        n0, gg = fleet.n0 * severity, fleet.g * severity
        mttr = np.full(K, float(fleet.mttr.mean()))
    heal_idx = [c for c in range(K) if fleet.p_heal[c] > 0.0]
    nmask = 1 << len(heal_idx)
    thl = np.full((nmask, K), np.inf)
    probs = np.ones(nmask)
    for m in range(nmask):
        p = 1.0
        for bit, c in enumerate(heal_idx):
            if (m >> bit) & 1:
                thl[m, c] = fleet.t_heal[c]
                p *= fleet.p_heal[c]
            else:
                p *= 1.0 - fleet.p_heal[c]
        probs[m] = p

    P = np.asarray(orders, dtype=np.int64)
    M = P.shape[0]
    H = float(fleet.horizon) if severity is None else float(mttr.sum() * fleet.knobs.get("horizon_mult", 2.0))
    THL = np.repeat(thl, M, axis=0)  # (nmask*M, K)
    PP = np.tile(P, (nmask, 1))
    rows = np.arange(nmask * M)
    tau = np.zeros(nmask * M)
    T = np.zeros((nmask * M, K))
    for pos in range(K):
        c = PP[:, pos]
        h = THL[rows, c]
        skip = (h <= tau) | (tau >= H)
        t_fin = tau + mttr[c]
        Tc = np.where(skip, np.minimum(h, H), np.minimum(np.minimum(h, t_fin), H))
        T[rows, c] = Tc
        tau = np.where(skip, tau, t_fin if committed else np.minimum(t_fin, np.maximum(h, tau)))
    cost = (T * n0).sum(axis=1) + 0.5 * (T * T * gg).sum(axis=1)
    return (probs[:, None] * cost.reshape(nmask, M)).sum(axis=0)


# ---------------------------------------------------------------------------
# 4. POLICIES
# ---------------------------------------------------------------------------


def order_count(fleet):
    """Today's shipped default: total DESC, then signatureHash ASC."""
    idx = sorted(range(fleet.K), key=lambda c: (-fleet.n0[c], fleet.sig[c]))
    return tuple(idx)


def order_attention(fleet, cfg=DEFAULTS, factors=("F", "R", "M", "S")):
    """AttentionOrdering.BY_ATTENTION: score DESC, then total DESC, then signatureHash ASC."""
    sc = [attention_score(fleet.evidence[c], fleet.fleet_median_mttr_s, cfg, factors) for c in range(fleet.K)]
    idx = sorted(range(fleet.K), key=lambda c: (-sc[c], -fleet.n0[c], fleet.sig[c]))
    return tuple(idx)


def order_smith(fleet):
    """Smith's rule for 1||sum w_j C_j — the classical optimum when g=0 and nothing heals."""
    idx = sorted(range(fleet.K), key=lambda c: (-(fleet.n0[c] / max(fleet.mttr[c], 1e-9)), fleet.sig[c]))
    return tuple(idx)


ABLATIONS = {
    "attention (F*R*M*S)": ("F", "R", "M", "S"),
    "F only": ("F",),
    "F*R": ("F", "R"),
    "F*M": ("F", "M"),
    "F*S": ("F", "S"),
    "F*R*S  (M off)": ("F", "R", "S"),
    "F*R*M  (S off)": ("F", "R", "M"),
    "F*R*(1/M)*S  [M flipped]": ("F", "R", "1/M", "S"),
}
MAIN = "attention (F*R*M*S)"


# ---------------------------------------------------------------------------
# 5. FLEET GENERATION — every distribution here is an ASSUMPTION (report §ledger)
# ---------------------------------------------------------------------------

SEC = 60.0
DAY_MIN = 1440.0
WINDOW_MIN = 28 * DAY_MIN


def loguniform(rng, lo, hi, size=None):
    return np.exp(rng.uniform(math.log(lo), math.log(hi), size))


def lane_of(p_heal):
    """The R2 lane a PERFECT estimator would display for a true p_heal (charitable to S)."""
    if p_heal >= 0.70:
        return "SELF_HEAL_LIKELY"
    if p_heal <= 0.30:
        return "SELF_HEAL_UNLIKELY"
    return "SELF_HEAL_MIXED"


BASE_KNOBS = dict(
    K=5,
    count_spread=1.5,
    count_base=120.0,
    mttr_spread=1.0,
    mttr_base_min=30.0,
    arrival_mode="independent",  # independent | rank_consistent | mixed | none
    arrival_scale=0.002,
    arrival_mix=0.0,  # 0 = independent of n0, 1 = perfectly rank-consistent
    g_lo=1e-4,
    g_hi=2.0,
    stale_frac=0.0,
    stale_max_h=96.0,
    heal_mode="uniform",  # none | uniform | bimodal
    heal_t_lo_mult=0.02,
    heal_t_hi_mult=1.2,
    horizon_mult=2.0,
    mttr_known_frac=1.0,
    lane_known_frac=1.0,
)


def make_fleet(rng, knobs, regime):
    """Build one fleet: hidden dynamics first, then the evidence an observer would record."""
    K = knobs["K"]
    cs, cb = knobs["count_spread"], knobs["count_base"]
    n0 = np.maximum(1.0, np.round(10 ** rng.uniform(math.log10(cb) - cs / 2, math.log10(cb) + cs / 2, K)))

    ms, mb = knobs["mttr_spread"], knobs["mttr_base_min"]
    mttr = 10 ** rng.uniform(math.log10(mb) - ms / 2, math.log10(mb) + ms / 2, K)

    mode = knobs["arrival_mode"]
    if mode == "none":
        g_hist = np.zeros(K)
    elif mode == "rank_consistent":
        # arrivals a strictly monotone function of n0 -> F alone reproduces the count-only
        # ORDER exactly, so every divergence is attributable to R/M/S.
        g_hist = n0 * knobs["arrival_scale"]
    elif mode == "mixed":
        a = knobs["arrival_mix"]
        indep = loguniform(rng, knobs["g_lo"], knobs["g_hi"], K)
        tied = n0 * knobs["arrival_scale"]
        g_hist = np.exp(a * np.log(tied) + (1 - a) * np.log(indep))
    else:
        g_hist = loguniform(rng, knobs["g_lo"], knobs["g_hi"], K)

    stalled = rng.random(K) < knobs["stale_frac"]
    age_h = np.where(stalled, rng.uniform(1.0, knobs["stale_max_h"], K), 0.0)
    g_now = np.where(stalled, 0.0, g_hist)

    hm = knobs["heal_mode"]
    if hm == "none":
        p_heal = np.zeros(K)
    elif hm == "bimodal":
        p_heal = np.where(rng.random(K) < 0.5, rng.uniform(0.0, 0.25, K), rng.uniform(0.75, 1.0, K))
    elif hm == "fixed":
        p_heal = np.full(K, knobs["heal_p"])
    else:
        p_heal = rng.uniform(0.0, 1.0, K)

    horizon = knobs["horizon_mult"] * float(mttr.sum())
    if knobs.get("heal_t_fixed_mult") is not None:
        t_heal = knobs["heal_t_fixed_mult"] * mttr
    else:
        t_heal = loguniform(
            rng, max(1e-6, knobs["heal_t_lo_mult"] * horizon), max(1e-5, knobs["heal_t_hi_mult"] * horizon), K
        )

    # ---- OBSERVED EVIDENCE (the score's only inputs) ----------------------
    # The class has been arriving at g_hist for the whole 28d window (its history is longer
    # than the window by construction) and stopped `age_h` ago if stalled.
    active_min = np.where(stalled, np.maximum(0.0, WINDOW_MIN - age_h * 60.0), WINDOW_MIN)
    arrivals = np.floor(g_hist * active_min).astype(np.int64)
    burst = np.minimum(np.floor(g_now * 10.0).astype(np.int64), arrivals)  # W = 10 min
    prior = np.minimum(np.floor(g_now * 10.0).astype(np.int64), np.maximum(arrivals - burst, 0))

    known = rng.random(K) < knobs["mttr_known_frac"]
    lane_known = rng.random(K) < knobs["lane_known_frac"]
    med_known = [float(mttr[c] * SEC) if known[c] else None for c in range(K)]
    known_meds = [m for m in med_known if m is not None]
    fleet_med = median(known_meds) if known_meds else None

    sig = [f"{int(rng.integers(0, 1 << 48)):012x}" for _ in range(K)]
    evidence = [
        ClassEvidence(
            arrivals=int(arrivals[c]),
            arrivals_unknown=False,
            burst=int(burst[c]),
            prior_burst=int(prior[c]),
            burst_unknown=False,
            age_s=float(age_h[c] * 3600.0),
            med_mttr_s=med_known[c],
            closed_episodes=(3 if known[c] else 0),
            lane=(lane_of(float(p_heal[c])) if (lane_known[c] and hm != "none") else "INSUFFICIENT_HISTORY"),
        )
        for c in range(K)
    ]
    return Fleet(
        n0=n0,
        g=g_now,
        mttr=mttr,
        p_heal=p_heal,
        t_heal=t_heal,
        horizon=horizon,
        evidence=evidence,
        fleet_median_mttr_s=fleet_med,
        sig=sig,
        regime=regime,
        knobs=dict(knobs),
    )


# ---------------------------------------------------------------------------
# 6. EVALUATION
# ---------------------------------------------------------------------------

PERM_CACHE = {}
EXACT_K_MAX = 6  # 6! = 720 orders x 2^6 masks — exhaustive oracle, exactly


def perms_for(K):
    if K not in PERM_CACHE:
        p = list(itertools.permutations(range(K)))
        PERM_CACHE[K] = (p, {t: i for i, t in enumerate(p)})
    return PERM_CACHE[K]


def evaluate(fleet, rng=None, cfg=DEFAULTS, ablations=True, committed=True, severity=None, pool=400):
    """Cost of every policy on one fleet. Exhaustive oracle for K<=6, candidate pool above."""
    K = fleet.K
    cand = {}

    def add(o):
        if o not in cand:
            cand[o] = len(cand)
        return cand[o]

    exact = K <= EXACT_K_MAX
    if exact:
        orders, index = perms_for(K)
        cand = dict(index)
    else:
        add(order_count(fleet))
        add(order_smith(fleet))
        for fac in ABLATIONS.values():
            add(order_attention(fleet, cfg, fac))
        base = list(range(K))
        for _ in range(pool):
            add(tuple(rng.permutation(base)))
        orders = [None] * len(cand)
        for o, i in cand.items():
            orders[i] = o

    costs = expected_costs(fleet, orders, committed=committed, severity=severity)
    out = {
        "oracle": float(costs.min()),
        "worst": float(costs.max()),
        "random": float(costs.mean()),
        "exact_oracle": exact,
        "smith": float(costs[cand[order_smith(fleet)]]),
    }
    o_count = order_count(fleet)
    out["count"] = float(costs[cand[o_count]])
    out["count|order"] = o_count
    variants = ABLATIONS if ablations else {MAIN: ABLATIONS[MAIN]}
    for name, fac in variants.items():
        o = order_attention(fleet, cfg, fac)
        out[name] = float(costs[cand[o]])
        out[name + "|order"] = o
    out["oracle|order"] = orders[int(costs.argmin())]
    return out


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (p, max(0.0, c - h), min(1.0, c + h))


def harm_stats(records, key=MAIN):
    """HARM = regret(policy) > regret(count) <=> cost(policy) > cost(count) (oracle cancels)."""
    n = len(records)
    harm = help_ = tie = same = 0
    gaps = []
    for r in records:
        span = max(r["worst"] - r["oracle"], 1e-12)
        d = r[key] - r["count"]
        tol = 1e-9 * max(1.0, abs(r["count"]))
        if d > tol:
            harm += 1
            gaps.append(d / span)
        elif d < -tol:
            help_ += 1
        else:
            tie += 1
        if r[key + "|order"] == r["count|order"]:
            same += 1
    p, lo, hi = wilson(harm, n)
    ph, lo_h, hi_h = wilson(help_, n)
    return {
        "n": n,
        "harm": harm,
        "harm_rate": p,
        "harm_ci": (lo, hi),
        "help": help_,
        "help_rate": ph,
        "help_ci": (lo_h, hi_h),
        "tie": tie,
        "same_order": same,
        "mean_gap": float(np.mean(gaps)) if gaps else 0.0,
        "p90_gap": float(np.percentile(gaps, 90)) if gaps else 0.0,
        "max_gap": float(np.max(gaps)) if gaps else 0.0,
    }


def fmt(st, label, width=30):
    lo, hi = st["harm_ci"]
    return (
        f"{label:<{width}} n={st['n']:>5}  HARM {st['harm_rate']*100:6.2f}% "
        f"[{lo*100:5.2f},{hi*100:5.2f}]  help {st['help_rate']*100:6.2f}%  "
        f"tie {st['tie']/st['n']*100:6.2f}%  same-order {st['same_order']/st['n']*100:6.2f}%  "
        f"gap {st['mean_gap']:.3f}/{st['p90_gap']:.3f}/{st['max_gap']:.3f}"
    )


def norm_regret(recs, key):
    return float(np.mean([(r[key] - r["oracle"]) / max(r["worst"] - r["oracle"], 1e-12) for r in recs]))


# ---------------------------------------------------------------------------
# 7. REGIMES
# ---------------------------------------------------------------------------


def lhs(rng, n, dims):
    """Latin-hypercube sample: n strata per dimension, independently shuffled."""
    out = np.zeros((n, len(dims)))
    for j, (lo, hi) in enumerate(dims):
        u = (rng.permutation(n) + rng.random(n)) / n
        out[:, j] = lo + u * (hi - lo)
    return out


def gen_global(rng, n, kmax=6.99):
    """G1 — global Latin-hypercube sweep. Every knob varies; K in {3..6}."""
    dims = [
        (0.0, 3.0),  # count spread (decades of n0)
        (0.0, 2.3),  # MTTR spread (decades of operator service time)
        (0.0, 0.5),  # stale fraction
        (1.0, 4.0),  # horizon multiple of total service time
        (0.0, 1.0),  # fraction of classes with >=3 closed episodes (M active)
        (0.0, 1.0),  # fraction of classes with a self-heal lane (S active)
        (0.0, 1.0),  # arrivals/backlog coupling (0 independent -> 1 rank-consistent)
        (3.0, kmax),  # K
    ]
    fleets = []
    for row in lhs(rng, n, dims):
        k = dict(BASE_KNOBS)
        k.update(
            count_spread=row[0],
            mttr_spread=row[1],
            stale_frac=row[2],
            horizon_mult=row[3],
            mttr_known_frac=row[4],
            lane_known_frac=row[5],
            arrival_mode="mixed",
            arrival_mix=row[6],
            K=int(row[7]),
            heal_mode="uniform",
        )
        fleets.append(make_fleet(rng, k, "G1"))
    return fleets


def gen_compression(rng, reps):
    """G2 — the PREDICTED harm regime: F rank-consistent with count, R off, M/S free."""
    fleets = []
    for cs in (0.5, 1.0, 1.5, 2.0, 2.5, 3.0):
        for ms in (0.3, 0.7, 1.0, 1.7, 2.3):
            for _ in range(reps):
                k = dict(BASE_KNOBS)
                k.update(
                    count_spread=cs,
                    mttr_spread=ms,
                    arrival_mode="rank_consistent",
                    stale_frac=0.0,
                    heal_mode="uniform",
                    K=5,
                )
                f = make_fleet(rng, k, "G2")
                f.knobs["cs"], f.knobs["ms"] = cs, ms
                fleets.append(f)
    return fleets


def _simple(rng, n, tag, **over):
    fleets = []
    for _ in range(n):
        k = dict(BASE_KNOBS)
        k.update(over)
        fleets.append(make_fleet(rng, k, tag))
    return fleets


def gen_converse(rng, n):
    """G3 — the CONVERSE: counts nearly identical, MTTR + self-heal dominate."""
    return _simple(
        rng, n, "G3", count_spread=0.05, mttr_spread=2.0, arrival_mode="rank_consistent", heal_mode="bimodal", K=5
    )


def gen_selfheal_only(rng, n):
    """G3b — counts near-identical, service time uniform: the ONLY signal is self-heal."""
    return _simple(
        rng, n, "G3b", count_spread=0.05, mttr_spread=0.0, arrival_mode="rank_consistent", heal_mode="bimodal", K=5
    )


def gen_stale(rng, n):
    """G4 — staleness: half the fleet stopped growing but keeps its standing backlog."""
    return _simple(
        rng,
        n,
        "G4",
        count_spread=2.0,
        mttr_spread=0.0,
        arrival_mode="rank_consistent",
        stale_frac=0.5,
        heal_mode="none",
        K=5,
    )


def gen_flood(rng, n):
    """G5 — high arrival rates, so the §4.1a burst gate actually fires."""
    return _simple(
        rng, n, "G5", count_spread=2.0, mttr_spread=1.0, arrival_mode="independent", g_lo=0.05, g_hi=20.0, K=5
    )


def gen_pilot(rng, n, mix=0.0):
    """G0 — today's MEASURED pilot evidence state: 0 closed episodes, 0 spells => M=S=1.

    A(c) then reduces to F·R, and with every class live (R=1) to F alone. Whether that
    still equals the count-only order depends ENTIRELY on the invented arrivals<->backlog
    coupling: `mix=1` makes arrivals rank-identical to totals (attention == count, the
    §5.5 neutrality result), `mix=0` makes them independent (they diverge). §7 sweeps it.
    """
    return _simple(
        rng, n, f"G0 mix={mix}", count_spread=2.0, mttr_spread=1.5, mttr_known_frac=0.0,
        lane_known_frac=0.0, arrival_mode="mixed", arrival_mix=mix, K=5,
    )


def gen_bigk(rng, n):
    """G6 — K in {7,8,9}: harm predicate stays EXACT; the oracle is a candidate-pool bound."""
    fleets = []
    for row in lhs(rng, n, [(0.0, 3.0), (0.0, 2.3), (1.0, 4.0), (7.0, 9.99)]):
        k = dict(BASE_KNOBS)
        k.update(
            count_spread=row[0],
            mttr_spread=row[1],
            horizon_mult=row[2],
            K=int(row[3]),
            arrival_mode="mixed",
            arrival_mix=0.7,
            heal_mode="uniform",
        )
        fleets.append(make_fleet(rng, k, "G6"))
    return fleets


# ---------------------------------------------------------------------------
# 8. REPORTING HELPERS
# ---------------------------------------------------------------------------


def describe(rec, title):
    f = rec["fleet"]
    fm = "n/a (no closed episode anywhere)" if f.fleet_median_mttr_s is None else f"{f.fleet_median_mttr_s/60:.2f} min"
    L = [f"--- {title}", f"regime={f.regime}  K={f.K}  horizon={f.horizon:.1f} min  fleet median MTTR={fm}"]
    L.append(
        f"{'idx':>3} {'sig':>12} {'n0':>7} {'g/min':>8} {'mttr_min':>9} {'p_heal':>7} {'t_heal':>9} "
        f"{'arrivals':>9} {'age_h':>6} {'lane':>20} {'F':>6} {'R':>6} {'M':>5} {'S':>5} {'A':>8}"
    )
    for c in range(f.K):
        e = f.evidence[c]
        F, R, M, S = factor_values(e, f.fleet_median_mttr_s)
        L.append(
            f"{c:>3} {f.sig[c]:>12} {f.n0[c]:>7.0f} {f.g[c]:>8.4f} {f.mttr[c]:>9.2f} {f.p_heal[c]:>7.3f} "
            f"{f.t_heal[c]:>9.1f} {e.arrivals:>9} {e.age_s/3600:>6.1f} {str(e.lane):>20} "
            f"{F:>6.3f} {R:>6.3f} {M:>5.2f} {S:>5.2f} {F*R*M*S:>8.3f}"
        )
    L.append(f"count     order {rec['count|order']}   E[cost] = {rec['count']:>14,.1f} instance-minutes")
    L.append(f"attention order {rec[MAIN+'|order']}   E[cost] = {rec[MAIN]:>14,.1f} instance-minutes")
    L.append(f"oracle    order {rec['oracle|order']}   E[cost] = {rec['oracle']:>14,.1f} instance-minutes"
             f"   ({'exhaustive' if rec['exact_oracle'] else 'candidate-pool bound'})")
    L.append(f"random (mean over the order set)  = {rec['random']:>14,.1f}")
    L.append(f"worst order                       = {rec['worst']:>14,.1f}")
    rc, ra = rec["count"] - rec["oracle"], rec[MAIN] - rec["oracle"]
    span = max(rec["worst"] - rec["oracle"], 1e-12)
    ratio = (
        f"attention carries {ra/rc:.2f}x the count-only regret"
        if rc > 1e-9 * max(1.0, rec["count"])
        else "count-only was itself OPTIMAL here (regret 0)"
    )
    L.append(
        f"regret(count) = {rc:,.1f}   regret(attention) = {ra:,.1f}   GAP = {ra-rc:,.1f}  "
        f"({(ra-rc)/span*100:.1f}% of the oracle-to-worst span; {ratio}; "
        f"attention costs {rec[MAIN]/rec['count']:.2f}x what count-only costs)"
    )
    return "\n".join(L)


# ---------------------------------------------------------------------------
# 9. MAIN
# ---------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=20260805)
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--selftest-only", action="store_true")
    ap.add_argument("--json", default=None, help="optional path for a machine-readable summary")
    args = ap.parse_args()

    selftest()
    if args.selftest_only:
        return 0

    q = 10 if args.quick else 1
    rng = np.random.default_rng(args.seed)
    S = {}

    regimes = [
        ("G0 pilot evidence (M=S=1, decoupled)", gen_pilot(rng, 3000 // q, mix=0.0)),
        ("G0b pilot evidence (M=S=1, coupled)", gen_pilot(rng, 3000 // q, mix=1.0)),
        ("G1 global LHS (all knobs)", gen_global(rng, 8000 // q)),
        ("G2 compression probe", gen_compression(rng, max(1, 150 // q))),
        ("G3 converse (flat counts, MTTR+heal)", gen_converse(rng, 3000 // q)),
        ("G3b converse (self-heal ONLY)", gen_selfheal_only(rng, 3000 // q)),
        ("G4 staleness (R active)", gen_stale(rng, 3000 // q)),
        ("G5 flood (burst gate fires)", gen_flood(rng, 2000 // q)),
        ("G6 big K (7-9, pooled oracle)", gen_bigk(rng, 1200 // q)),
    ]

    print("## 1. harm rate by regime")
    print("##    HARM = regret(attention) > regret(count). The oracle cancels from that")
    print("##    predicate, so every rate below is EXACT regardless of oracle method.")
    print("##    gap = (regret_att - regret_count)/(worst - oracle), over harmed fleets only")
    print("##    (mean/p90/max). CI = Wilson score, z=1.96.\n")

    recs = {}
    for name, fleets in regimes:
        rs = []
        for f in fleets:
            r = evaluate(f, rng=rng)
            r["fleet"] = f
            rs.append(r)
        recs[name] = rs
        st = harm_stats(rs)
        S[name] = st
        print("  " + fmt(st, name, 38))

    pooled = [r for rs in recs.values() for r in rs]
    st_all = harm_stats(pooled)
    S["POOLED"] = st_all
    print("  " + fmt(st_all, "POOLED (all regimes)", 38))

    # ---- 2. the compression grid -----------------------------------------
    print("\n## 2. G2 compression grid — harm rate (and mean gap) by (count spread, MTTR spread)")
    print("##    count spread = decades of n0 across the fleet; MTTR spread = decades of service time")
    grid = {}
    for r in recs["G2 compression probe"]:
        grid.setdefault((r["fleet"].knobs["cs"], r["fleet"].knobs["ms"]), []).append(r)
    css = sorted({k[0] for k in grid})
    mss = sorted({k[1] for k in grid})
    print("     n0-spread \\ mttr-spread  " + "  ".join(f"{m:>13.1f}" for m in mss))
    for cs in css:
        cells = []
        for ms in mss:
            st = harm_stats(grid[(cs, ms)])
            cells.append(f"{st['harm_rate']*100:>6.1f}% ({st['mean_gap']:.2f})")
        print(f"     {cs:>23.1f}  " + "  ".join(cells))
    S["G2_grid"] = {f"cs{cs}_ms{ms}": harm_stats(grid[(cs, ms)]) for cs in css for ms in mss}

    # ---- 3. factor attribution -------------------------------------------
    print("\n## 3. factor attribution — the same fleets, one term of the score at a time")
    for name in ("G1 global LHS (all knobs)", "G2 compression probe", "G3 converse (flat counts, MTTR+heal)",
                 "G3b converse (self-heal ONLY)", "G4 staleness (R active)", "G0 pilot evidence (M=S=1, decoupled)"):
        print(f"  -- {name}")
        for v in ABLATIONS:
            print("     " + fmt(harm_stats(recs[name], v), v))
        S.setdefault("attribution", {})[name] = {v: harm_stats(recs[name], v) for v in ABLATIONS}

    # ---- 4. clamp / floor: do they bound the damage? ----------------------
    print("\n## 4. do the [0.5,2.0] M clamp and the 0.25 S floor BOUND the damage?")
    print("##    identical fleets, identical score, only the clamp/floor moved")
    cfgs = {
        "shipped  M[0.5,2] S floor .25": DEFAULTS,
        "M clamp WIDENED to [0.1,10]": AttentionCfg(mttr_clamp_low=0.1, mttr_clamp_high=10.0),
        "M clamp REMOVED [0,inf)": AttentionCfg(mttr_clamp_low=0.0, mttr_clamp_high=float("inf")),
        "M clamp TIGHTENED to [0.8,1.25]": AttentionCfg(mttr_clamp_low=0.8, mttr_clamp_high=1.25),
        "M disabled  [1,1]": AttentionCfg(mttr_clamp_low=1.0, mttr_clamp_high=1.0),
        "S floor REMOVED (0.0)": AttentionCfg(self_heal_floor=0.0),
        "S floor RAISED to 0.9": AttentionCfg(self_heal_floor=0.9),
    }
    S["clamp"] = []
    for regime in ("G1 global LHS (all knobs)", "G2 compression probe"):
        print(f"  -- {regime}")
        for label, cfg in cfgs.items():
            rs = []
            for r in recs[regime]:
                f = r["fleet"]
                r2 = evaluate(f, rng=rng, cfg=cfg, ablations=False)
                rs.append(r2)
            st = harm_stats(rs)
            S["clamp"].append({"regime": regime, "variant": label, **{k: st[k] for k in
                               ("n", "harm_rate", "help_rate", "mean_gap", "p90_gap", "max_gap")}})
            print("     " + fmt(st, label))

    # ---- 5. absolute quality ---------------------------------------------
    print("\n## 5. absolute quality — mean normalised regret (0 = oracle, 1 = worst order)")
    print(f"  {'regime':<40} {'count':>9} {'attention':>10} {'random':>9} {'Smith':>9}")
    for name, rs in recs.items():
        vals = {k: norm_regret(rs, k) for k in ("count", MAIN, "random", "smith")}
        print(f"  {name:<40} {vals['count']:>9.4f} {vals[MAIN]:>10.4f} {vals['random']:>9.4f} {vals['smith']:>9.4f}")
        S.setdefault("normalised_regret", {})[name] = vals

    # ---- 6. self-heal timing: WHEN does S help? ---------------------------
    print("\n## 6. S factor vs self-heal TIMING — S reads p_heal but never t_heal")
    print("##    flat counts, uniform service time, one class self-heals at t = mult x mttr")
    S["heal_timing"] = {}
    print(f"  {'t_heal / mttr':>14} {'harm%':>8} {'help%':>8} {'mean gap':>10} {'count NR':>9} {'att NR':>9}")
    for mult in (0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0):
        rs = []
        for _ in range(1500 // q):
            k = dict(BASE_KNOBS)
            k.update(
                count_spread=0.05, mttr_spread=0.0, arrival_mode="rank_consistent",
                heal_mode="bimodal", heal_t_fixed_mult=mult, K=5, horizon_mult=6.0,
            )
            f = make_fleet(rng, k, f"G7 t/mttr={mult}")
            r = evaluate(f, rng=rng, ablations=False)
            rs.append(r)
        st = harm_stats(rs)
        S["heal_timing"][mult] = st
        print(f"  {mult:>14.2f} {st['harm_rate']*100:>7.2f}% {st['help_rate']*100:>7.2f}% "
              f"{st['mean_gap']:>10.3f} {norm_regret(rs,'count'):>9.4f} {norm_regret(rs,MAIN):>9.4f}")

    # ---- 7. arrivals/backlog coupling ------------------------------------
    print("\n## 7. sensitivity to the INVENTED arrivals<->backlog coupling (F channel)")
    print("##    mix=1 -> arrivals rank-identical to totals; mix=0 -> statistically independent")
    S["coupling"] = {}
    print(f"  {'mix':>6} {'harm%':>8} {'help%':>8} {'F-only harm%':>14} {'count NR':>9} {'att NR':>9}")
    for mix in (0.0, 0.25, 0.5, 0.75, 1.0):
        rs = []
        for _ in range(2000 // q):
            k = dict(BASE_KNOBS)
            k.update(count_spread=2.0, mttr_spread=1.0, arrival_mode="mixed", arrival_mix=mix, K=5)
            rs.append(evaluate(make_fleet(rng, k, f"G8 mix={mix}"), rng=rng))
        st, stf = harm_stats(rs), harm_stats(rs, "F only")
        S["coupling"][mix] = {"all": st, "F_only": stf}
        print(f"  {mix:>6.2f} {st['harm_rate']*100:>7.2f}% {st['help_rate']*100:>7.2f}% "
              f"{stf['harm_rate']*100:>13.2f}% {norm_regret(rs,'count'):>9.4f} {norm_regret(rs,MAIN):>9.4f}")

    # ---- 8. assumption sensitivities -------------------------------------
    print("\n## 8. assumption sensitivities — does the verdict survive the modelling choices?")
    print("  (a) operator ABANDONS a class that heals mid-fix (instead of staying committed)")
    for regime in ("G1 global LHS (all knobs)", "G2 compression probe"):
        rs = [evaluate(r["fleet"], rng=rng, ablations=False, committed=False) for r in recs[regime]]
        st = harm_stats(rs)
        S.setdefault("abandon", {})[regime] = st
        print("     " + fmt(st, regime, 38))
    print("  (b) GROUND-TRUTH-B, the CHARITABLE reading of §3's c_miss: MTTR is a per-instance")
    print("      severity weight and the operator's service time is UNIFORM. This is the only")
    print("      reading under which M's direction can be right; it is NOT the brief's cost model.")
    for regime in ("G1 global LHS (all knobs)", "G2 compression probe", "G3 converse (flat counts, MTTR+heal)"):
        rs = []
        for r in recs[regime]:
            f = r["fleet"]
            sev = f.mttr / float(np.median(f.mttr))
            rs.append(evaluate(f, rng=rng, ablations=False, severity=sev))
        st = harm_stats(rs)
        S.setdefault("gt_b", {})[regime] = st
        print("     " + fmt(st, regime, 38))

    # ---- 9. worst cases ---------------------------------------------------
    print("\n## 9. worst cases")
    for label, pool_ in (
        ("worst overall (any regime)", pooled),
        ("worst on a REALISTIC live fleet (G2: R=1, arrivals rank-consistent with totals)",
         recs["G2 compression probe"]),
        ("worst under TODAY's pilot evidence (G0: M=S=1, zero closed episodes)",
         recs["G0 pilot evidence (M=S=1, decoupled)"]),
    ):
        w, wg = None, -1.0
        for r in pool_:
            span = max(r["worst"] - r["oracle"], 1e-12)
            gp = (r[MAIN] - r["count"]) / span
            if gp > wg:
                wg, w = gp, r
        print(describe(w, label))
        print()

    # ---- 10. teeth check --------------------------------------------------
    print("## 10. does the search have teeth? — coverage and the no-harm corners")
    st_help = {n: harm_stats(rs) for n, rs in recs.items()}
    best = min(st_help.items(), key=lambda kv: kv[1]["harm_rate"])
    print(f"  lowest harm rate of any regime: {best[0]} at {best[1]['harm_rate']*100:.2f}% "
          f"(help {best[1]['help_rate']*100:.2f}%, tie {best[1]['tie']/best[1]['n']*100:.2f}%)")
    print(f"  fleets evaluated: {len(pooled)} main + sensitivities; K range 3-9; "
          f"count spread 0-3 decades; MTTR spread 0-2.3 decades")
    print(f"  fraction of main fleets where attention and count produced the SAME order: "
          f"{st_all['same_order']/st_all['n']*100:.2f}% — the search is not measuring noise on ties")

    if args.json:
        with open(args.json, "w") as fh:
            json.dump(S, fh, indent=2, default=str)
        print(f"\n(summary written to {args.json})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
