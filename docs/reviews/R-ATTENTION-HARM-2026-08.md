# Adversarial harm search — the shipped attention-ordering score `A(c) = F·R·M·S`

**Date:** 2026-08-05
**Verdict: HARM FOUND, and it is the dominant outcome, not a corner case.** Against an
outcome-defined cost (total unresolved instance-minutes under a sequentially-working
operator), the shipped attention ordering carries **more regret than today's `total DESC`
on 66.06 % of 30,700 simulated fleets** (Wilson 95 % CI **[65.53 %, 66.59 %]**), helps on
19.18 %, and is order-identical on 14.66 %. In the regime the brief predicted — wide count
spread, live classes, arrivals rank-consistent with totals — harm reaches **87.62 %
[86.63, 88.55]**. In four of nine regimes the attention order's mean normalised regret is
**worse than a random shuffle of the cards**.

**The dominant mechanism is not the log compression of `F`. It is that `M` points the
wrong way.** Under the brief's cost definition (`mttr_c` = the wall-clock the operator
needs to fix the class) the cost-minimising order is Smith's rule, `n/mttr DESC` —
short-service-time-first. `M = clamp(medMTTR(c)/medMTTR(fleet), 0.5, 2)` *promotes* long-MTTR
classes, i.e. it multiplies by the very quantity the optimum divides by. The compression
hypothesis is nevertheless **confirmed, and it is what makes `M`'s error decisive**: `F`'s
dynamic range across a fleet is a ratio of logarithms (2.23× for a 100× count ratio,
2.85× for 1000×) while `M·S`'s is a fixed **16×**, so on any fleet where every class has
≥ 2 arrivals, `M` and `S` decide the order and `F` merely breaks their ties.

> **This does NOT license "turn the flag off" on its own, and it does not license "the
> score is broken in production".** Read §9 before quoting any number here. The single
> most important caveat: today's pilot has **0 closed episodes and 0 self-heal spells**, so
> `M = S = 1` for every live class and the shipped ordering is *provably* count-only
> (`ALARM-COST-MODEL.md` §5.5, Kendall τ = 1.0 over 21,229 buckets — reproduced here as
> regime G0b, 100 % order-identical). Everything below is about the fleet the ledger will
> describe **once evidence exists**, under distributional assumptions that are **invented,
> not measured** (§8).

Reproduce with:

```bash
python3 scripts/attention-harm-search.py                 # full run, ~8.5 min, seed 20260805
python3 scripts/attention-harm-search.py --selftest-only  # just the port pin
```

No engine calls, no HTTP, no database, no live-demo mutation. Pure simulation, seeded RNG,
byte-identical output for a given `--seed`.

---

## 1. Why this is not circular

The brief's one methodological requirement. The score is a **static feature combination**
of ledger evidence: arrivals in a 28 d window, `lastSeen` age, closed-episode median,
self-heal lane. The ground truth is an **outcome integral** over hidden dynamics:

```
cost(order) = SUM_c  INTEGRAL_0^{T_c} n_c(t) dt          n_c(t) = n0_c + g_c·t
            = SUM_c  n0_c·T_c + g_c·T_c²/2
```

`T_c` is the instant class `c` stopped accruing — it self-healed, the operator finished it,
or the horizon ended. **No factor of `A(c)` appears anywhere in that integral.** The score
only ever sees an *observation* of the same fleet, through a generative model stated in
full in §8.

```
regret(p) = cost(p) − cost(oracle)
HARM      = regret(attention) > regret(count)
```

**The oracle cancels out of the harm predicate.** `regret(a) > regret(c) ⟺ cost(a) >
cost(c)`. Every harm rate in this report is therefore *exact and oracle-independent*; the
oracle is used only to normalise the **magnitude** of a regret gap into
`(regret_att − regret_count) / (cost_worst − cost_oracle)`, a 0–1 scale where 1 means
"attention picked the worst of all orders and count-only picked the best".

**Oracle exactness.** For K ≤ 6 the oracle is **exhaustive** — all K! orders × all 2^K
self-heal realisations, evaluated exactly (the expectation over self-heal is enumerated,
not sampled, so there is no Monte-Carlo noise in any cost). Regime G6 (K = 7–9) uses a
candidate pool (count, Smith, all seven score ablations, plus 400 seeded random orders);
its harm **rate** is still exact, only its normalised gaps are a lower bound.

## 2. The port, pinned before anything ran

`scripts/attention-harm-search.py` ports `AttentionScoreCalculator.java` (plus
`InspectorProperties.Attention` defaults, `AttentionOrdering.BY_ATTENTION`,
`AttentionRationale.laneOf`, `Quantiles`) line for line, including §4.1a's burst term. The
self-test runs first and **exits 2 on any mismatch**. All 36 checks pass:

| pinned value | got |
|---|---|
| trickle 100 arrivals/28 d, no flood → `F = 6.658` | 6.658211 |
| flood 100 arrivals inside W → `F = 9.646` | 9.645658 |
| birth-5000 in W: shipped `12.288` → burst `15.288` | 12.288001 → 15.287748 |
| inflation ceiling `1 + log2(γ)/log2(1+onset) = 1.867` | 1.867194 |
| **attained** max inflation `1.833` | 1.832628, at `(arrivals, burst, prior) = (10, 10, 0)` |
| gate: entry `burst=12` ON / hold `burst=7,prior=12` ON / drop `burst=4,prior=12` OFF / back-door `burst=6,prior=6` OFF / empty OFF | all as pinned |
| `M = clamp(ratio, 0.5, 2)`; 2×, 10×, 50× all → 2.0; neutral 1 below 3 closed episodes | as pinned |
| `S = max(1−p_heal, 0.25)`; lanes → 0.25 / 0.50 / 0.85; neutral 1 when insufficient | as pinned |
| `R = 2^(−age_h/24)`: 1.0 / 0.5 / 0.125 at 0 / 24 / 72 h | as pinned |
| tie-break `total DESC` then `signatureHash ASC` (R-SEM-23) | as pinned |
| empty ledger ⇒ attention order **==** count-only order | holds |

> **A note on the attained-max scan.** `1.833` is only reachable if the scan respects the
> physical constraint `arrivals ≥ burst + prior` (both bins are subsets of the same 28 d
> window). Without it the hold leg would admit `arrivals = burst = 5, prior = 10` and the
> attained max would read 2.07. The Java clamps only `burst ≤ arrivals`; the tighter
> invariant lives in SQL. The pinned value is correct **because** SQL enforces it — worth
> recording, since a future edit to `LedgerNativeQueries` could break the ceiling claim
> without touching the calculator.

Two derived facts also pinned, because the whole finding turns on them:

- `F`'s dynamic range for a **100×** count ratio (40 → 4000 members) is **2.2335×**; for
  **1000×** (40 → 40 000) it is **2.8535×**.
- `M·S`'s dynamic range is `(2.0·1.0)/(0.5·0.25)` = **16×**, exactly, on every fleet.
- Consequence: with the quietest class at just 2 arrivals, `F` out-ranges `M·S` only once
  the busiest class exceeds ≈ **4.3 × 10⁷** arrivals. **On any real fleet, `M·S` dominates
  `F`.** That is the compression hypothesis, stated analytically rather than empirically.

## 3. Harm rate by regime

`n` = fleets. `gap` = mean / p90 / max normalised regret gap **over harmed fleets only**.
CI = Wilson score interval, z = 1.96. `same-order` = attention produced the identical
permutation to count-only (structurally impossible to harm).

| regime | n | **HARM** | 95 % CI | help | tie | same-order | gap mean/p90/max |
|---|---|---|---|---|---|---|---|
| **G0** pilot evidence (M=S=1), arrivals *decoupled* from totals | 3 000 | 76.70 % | [75.15, 78.18] | 22.37 % | 0.93 % | 0.93 % | 0.403 / 0.822 / 1.000 |
| **G0b** pilot evidence (M=S=1), arrivals *rank-consistent* | 3 000 | **0.00 %** | [0.00, 0.13] | 0.00 % | 100 % | 100 % | — |
| **G1** global Latin-hypercube, every knob varies, K 3–6 | 8 000 | 72.09 % | [71.09, 73.06] | 21.29 % | 6.62 % | 6.62 % | 0.428 / 0.876 / 1.000 |
| **G2** compression probe (predicted regime) | 4 500 | **87.62 %** | [86.63, 88.55] | 10.67 % | 1.71 % | 1.71 % | 0.410 / 0.821 / 1.000 |
| **G3** converse: flat counts, MTTR + self-heal dominate | 3 000 | 70.03 % | [68.37, 71.65] | 28.67 % | 1.30 % | 1.30 % | 0.342 / 0.716 / 1.000 |
| **G3b** converse: self-heal is the **only** varying signal | 3 000 | **33.43 %** | [31.77, 35.14] | **47.37 %** | 19.20 % | 18.30 % | 0.073 / 0.175 / 0.998 |
| **G4** staleness — half the fleet stopped growing | 3 000 | **89.13 %** | [87.97, 90.20] | 1.87 % | 9.00 % | 8.90 % | 0.350 / 0.786 / 1.000 |
| **G5** flood — high arrival rates, §4.1a gate fires | 2 000 | 71.30 % | [69.28, 73.24] | 28.10 % | 0.60 % | 0.60 % | 0.384 / 0.766 / 1.000 |
| **G6** big fleets, K = 7–9 (pooled oracle; rate still exact) | 1 200 | 88.83 % | [86.93, 90.49] | 11.17 % | 0.00 % | 0.00 % | 0.398 / 0.736 / 0.980 |
| **POOLED** | **30 700** | **66.06 %** | **[65.53, 66.59]** | 19.18 % | 14.76 % | 14.66 % | 0.380 / 0.808 / 1.000 |

Conditioned on the two orders actually *differing*, pooled harm is **77.5 %**
(66.06 / (100 − 14.76)); in G2 it is **89.1 %**.

### Absolute quality — is count-only even a good baseline?

Mean **normalised regret** (0 = oracle, 1 = worst possible order). `Smith` = `n/mttr DESC`,
the classical optimum for `1 || Σ w_j C_j`, included as an upper reference.

| regime | count | **attention** | random | Smith |
|---|---|---|---|---|
| G0 pilot (decoupled) | 0.1782 | 0.4349 | 0.4731 | 0.0582 |
| G0b pilot (coupled) | 0.1432 | 0.1432 | 0.4674 | 0.0352 |
| G1 global LHS | 0.2043 | 0.4524 | 0.4693 | 0.0695 |
| G2 compression | 0.1543 | **0.4918** | 0.4589 | 0.0532 |
| G3 converse (MTTR+heal) | 0.4522 | **0.6160** | 0.4581 | 0.0504 |
| G3b converse (self-heal only) | 0.3320 | **0.1207** | 0.3729 | 0.3320 |
| G4 staleness | 0.0010 | 0.3126 | 0.4969 | 0.0010 |
| G5 flood | 0.2583 | **0.4601** | 0.4339 | 0.1845 |
| G6 big K | 0.1711 | **0.5062** | 0.4597 | 0.0406 |

Two things to sit with. **(a)** In G2, G3, G5 and G6 attention's mean normalised regret
**exceeds the random control's** — on the brief's ground truth the attention order is not
merely worse than count-only, it is worse than shuffling the cards. **(b)** Smith's rule
sits at 0.03–0.18 nearly everywhere, i.e. the oracle is well approximated by
`n/mttr DESC`. Count-only (`n DESC`) is Smith with `mttr` held constant — which is why it
does respectably. `A(c)` moves in the opposite direction on that axis.

## 4. Where the harm concentrates

### 4a. The G2 grid — the brief's predicted regime, swept

Harm rate (mean normalised gap), 150 fleets per cell, arrivals **rank-consistent** with
totals (so `F` alone reproduces the count-only order exactly and contributes zero harm),
all classes live (`R ≡ 1`), self-heal drawn uniform.

| n0 spread (decades) ↓ / MTTR spread (decades) → | 0.3 | 0.7 | 1.0 | 1.7 | 2.3 |
|---|---|---|---|---|---|
| **0.5** | 69.3 % (0.29) | 77.3 % (0.33) | 77.3 % (0.41) | 84.0 % (0.42) | 88.0 % (0.48) |
| **1.0** | 81.3 % (0.30) | 83.3 % (0.39) | 86.7 % (0.42) | 84.7 % (0.49) | 92.0 % (0.46) |
| **1.5** | 82.7 % (0.32) | 86.0 % (0.38) | 92.0 % (0.46) | 91.3 % (0.44) | 92.0 % (0.49) |
| **2.0** | 86.7 % (0.30) | 88.0 % (0.41) | 91.3 % (0.44) | 92.7 % (0.47) | 94.0 % (0.49) |
| **2.5** | 86.7 % (0.32) | 89.3 % (0.35) | 90.0 % (0.44) | 90.7 % (0.45) | 92.7 % (0.52) |
| **3.0** | 88.7 % (0.30) | 91.3 % (0.38) | 92.0 % (0.38) | 92.0 % (0.42) | 94.7 % (0.49) |

Harm rises along **both axes** — 69.3 % at the mildest corner, 94.7 % at the widest; the
count-spread margin is monotone at every MTTR spread, the MTTR-spread margin has two
one-cell reversals (rows 1.0 and 1.5) well inside sampling noise at 150 fleets/cell. The brief's prediction — *"on wide-dynamic-range fleets the score approaches
uniform, so `M`/`S` variation can invert an order that count-only gets right"* — is
confirmed, with the mechanism made precise: `F` is not "approaching uniform" in absolute
terms, it is approaching uniform **relative to `M·S`'s fixed 16× range** (§2). Magnitude
tracks the MTTR spread rather than the count spread (0.29 → 0.48 left-to-right at every
row), which is the `M` signature.

### 4b. Factor attribution — one term at a time, same fleets

`F only` etc. means the other terms are forced to their multiplicative identity.
`F·R·(1/M)·S` inverts `M`'s direction and is the direct test of the "wrong sign" claim.

**G2 (F contributes nothing by construction — 100 % order-identical on its own):**

| variant | HARM | help | tie | mean gap |
|---|---|---|---|---|
| **attention `F·R·M·S`** | **87.62 %** | 10.67 % | 1.71 % | 0.410 |
| `F only` | 0.00 % | 0.00 % | 100 % | — |
| `F·R` | 0.00 % | 0.00 % | 100 % | — |
| `F·M` | **93.96 %** | 3.53 % | 2.51 % | 0.470 |
| `F·S` | 64.62 % | 26.87 % | 8.51 % | 0.266 |
| `F·R·S` (M off) | 64.62 % | 26.87 % | 8.51 % | 0.266 |
| `F·R·M` (S off) | 93.96 % | 3.53 % | 2.51 % | 0.470 |
| `F·R·(1/M)·S` **[M flipped]** | **54.91 %** | **43.29 %** | 1.80 % | 0.214 |

**G3 (flat counts, MTTR + self-heal dominant):**

| variant | HARM | help | mean gap |
|---|---|---|---|
| attention `F·R·M·S` | 70.03 % | 28.67 % | 0.342 |
| `F·M` / `F·R·M` | 92.43 % | 5.87 % | 0.419 |
| `F·S` / `F·R·S` | 31.43 % | 49.67 % | 0.177 |
| `F·R·(1/M)·S` **[M flipped]** | **12.83 %** | **85.90 %** | 0.151 |

Flipping `M`'s direction takes G3 from 70 % harm / 29 % help to **13 % harm / 86 % help**.
That is as close to a controlled demonstration of a sign error as this method can produce.

**G3b (self-heal the only varying signal) — the one place attention genuinely wins:**

| variant | HARM | help | mean gap | count NR | attention NR |
|---|---|---|---|---|---|
| attention (`= F·S` here) | 33.43 % | **47.37 %** | 0.073 | 0.3320 | **0.1207** |
| `F only` / `F·M` / `F·R·M` | 0.00 % | 0.00 % | — | 0.3320 | 0.3320 |

`S` earns its place: it cuts mean normalised regret from 0.332 to 0.121 and helps 1.4× as
often as it harms. Its harm, when it comes, is also *small* — mean gap 0.073 against 0.410
for the full score.

**G4 (staleness) — `R` is a harm channel on its own:**

| variant | HARM | help | tie | mean gap |
|---|---|---|---|---|
| attention | 89.13 % | 1.87 % | 9.00 % | 0.350 |
| `F only` | 2.17 % | 7.70 % | 90.13 % | 0.004 |
| any variant containing `R` | 89.13 % | 1.87 % | 9.00 % | 0.350 |

A class that stopped *growing* keeps its entire standing backlog and keeps accruing at
`n0` per minute forever. `R = 2^(−age/24 h)` demotes it 8× after three days while its true
cost rate is **unchanged**. Count-only is essentially optimal here (normalised regret
**0.0010**); attention is at 0.3126. `R` conflates "no new arrivals" with "cheaper to
wait", and those are different claims.

### 4c. `S` is blind to *when* the class heals

`S = max(1 − p_heal, 0.25)` reads the probability and never the timing. But the cost of
deferring a self-healer depends entirely on `t_heal` versus the operator's service time.
Flat counts, uniform service time, `t_heal` pinned at a multiple of `mttr`:

| `t_heal / mttr` | HARM | help | mean gap | count NR | attention NR |
|---|---|---|---|---|---|
| 0.10 | 33.47 % | 43.53 % | 0.004 | 0.4275 | **0.0588** |
| 0.25 | 31.00 % | 50.07 % | 0.003 | 0.4799 | **0.0552** |
| 0.50 | 33.00 % | 45.27 % | 0.003 | 0.4400 | **0.0594** |
| 1.00 | 31.53 % | 48.07 % | 0.003 | 0.4638 | **0.0584** |
| 2.00 | 15.00 % | **64.60 %** | 0.005 | 0.4047 | **0.0477** |
| 4.00 | 32.73 % | 48.47 % | 0.061 | 0.3551 | 0.0967 |
| **8.00** | **80.73 %** | **0.00 %** | **0.316** | **0.0000** | 0.2552 |
| **16.00** | **78.60 %** | **0.00 %** | **0.315** | **0.0000** | 0.2479 |

Clean phase change. While the heal lands within ~4 service times, `S` is a large win
(normalised regret 0.05–0.10 against count-only's 0.36–0.48). Once the heal lands *after*
the operator would have finished the whole board, deferring the class buys nothing and
costs everything: harm 80.7 %, **help exactly 0.00 %**, and count-only becomes optimal
(NR 0.0000) while attention sits at 0.25. The badge that produces `S` (`RETRYING-RISK-LANE`)
does compute a `ttsP90` time-to-self-heal; §4.1 does not consume it.

## 5. The single worst case found

Largest normalised regret gap over all 30,700 fleets — regime G2, a **realistic live
fleet**: five classes, all currently failing, all last-seen now (`R ≡ 1`), arrivals
rank-consistent with totals (so `F` alone would have reproduced count-only exactly).

| # | sig | n₀ | g /min | mttr (min) | p_heal | t_heal (min) | arrivals | lane | F | R | M | S | **A** |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | `3b0ac1f49637` | 67 | 0.134 | 76.2 | 0.159 | 41.9 | 5 402 | UNLIKELY | 12.400 | 1.000 | **2.00** | 0.85 | **21.079** |
| 1 | `a36361d5b5c9` | 98 | 0.196 | 24.3 | 0.423 | 137.5 | 7 902 | MIXED | 12.948 | 1.000 | 1.00 | 0.50 | 6.474 |
| 2 | `739e912dc2b3` | **208** | 0.416 | **11.6** | 0.885 | 555.9 | 16 773 | LIKELY | 14.034 | 1.000 | **0.50** | **0.25** | **1.754** |
| 3 | `6847d93a6b4c` | 52 | 0.104 | **125.1** | 0.259 | 295.8 | 4 193 | UNLIKELY | 12.034 | 1.000 | **2.00** | 0.85 | 20.458 |
| 4 | `160bdd7047e2` | 144 | 0.288 | 12.0 | 0.346 | 65.5 | 11 612 | MIXED | 13.503 | 1.000 | 0.50 | 0.50 | 3.376 |

```
count     order (2, 4, 1, 0, 3)   E[cost] =  34,434.2 instance-minutes
attention order (0, 3, 1, 4, 2)   E[cost] = 129,854.5 instance-minutes
oracle    order (2, 4, 1, 0, 3)   E[cost] =  34,434.2 instance-minutes   (exhaustive, all 120)
random    (exact mean, 120 orders) = 84,675.3
worst     order                    = 129,854.5

regret(count)     = 0
regret(attention) = 95,420.2
GAP               = 95,420.2 = 100.0 % of the oracle-to-worst span
attention costs 3.77x what count-only costs
```

**Count-only picked the optimal order. Attention picked the single worst of all 120.**
The mechanism is legible in the table: class 2 is the biggest (208 members), the fastest
to fix (11.6 min) and the most urgent by every cost consideration — so `M` demotes it to
0.50 for being *quick*, `S` demotes it to 0.25 for being *likely to heal* (at t = 556 min,
long after the whole board would have been cleared), and the product buries it last at
`A = 1.75`. Class 3, with a quarter of class 2's members and an 11× longer service time,
is promoted to first at `A = 20.5` — 125 minutes of operator time spent on 52 instances
while 208 accrue.

**The worst case under today's pilot evidence** (`M = S = 1`, zero closed episodes,
`A = F`) is milder but real: 5 classes, count-only again optimal at 76,117 instance-minutes,
attention at 151,935 — **2.00×**, again the worst of all 120 orders. Here the whole error
is `F`: class 1 has 671 members but only 5 arrivals in the window (an old, stalled-but-large
backlog) and drops to `A = 2.59`, below a 308-member class with 44,302 arrivals.

## 6. Do the clamp and the floor bound the damage as designed?

Identical fleets, identical score, only the clamp/floor moved.

**G2 (n = 4 500):**

| variant | HARM | help | mean gap | p90 gap |
|---|---|---|---|---|
| `M` disabled `[1,1]` (reference floor) | 64.62 % | 26.87 % | 0.266 | 0.627 |
| `M` clamp TIGHTENED `[0.8, 1.25]` | 79.07 % | 19.22 % | 0.341 | 0.734 |
| **shipped `M[0.5, 2]`, `S` floor 0.25** | **87.62 %** | 10.67 % | **0.410** | 0.821 |
| `M` clamp WIDENED `[0.1, 10]` | 89.82 % | 9.02 % | 0.454 | 0.872 |
| `M` clamp REMOVED `[0, ∞)` | 89.98 % | 8.93 % | 0.455 | 0.876 |
| `S` floor REMOVED (0.0) | **87.62 %** | 10.67 % | **0.410** | 0.821 |
| `S` floor RAISED to 0.9 (≈ `S` off) | 93.96 % | 3.53 % | 0.470 | 0.889 |

**G1 (n = 8 000)** shows the same ordering: 66.77 % (M off) → 69.91 % (tight) → **72.09 %
(shipped)** → 73.56 % (wide) → 73.65 % (removed).

### The M clamp: bounds the damage in the claimed direction, but bounds very little of it

The response is monotone, so the design's *direction* is right — narrowing the clamp
strictly reduces both harm rate and magnitude. The *quantity* is the finding. In G2, `M`
adds 25.36 points of harm when unclamped (64.62 → 89.98) and 23.00 points at the shipped
width (64.62 → 87.62). **The shipped clamp removes 9.3 % of the damage its own factor
causes.** Mean gap: 0.266 → 0.455 unclamped, → 0.410 shipped; the clamp buys back 0.045 of
0.189, i.e. **24 % of the magnitude**. A clamp that actually bounded the damage would have
to be near `[0.8, 1.25]` (79.07 %), and the limit of that road is `[1, 1]` — deleting the
factor.

Interpretation: a clamp bounds a factor's *range*, but harm here comes from its
**direction and its rank order**, neither of which a clamp touches. `M = 2.0` for a class
whose MTTR ratio is 2×, 10× or 50× is the design's stated compression — and all three
still sort *above* every `M ≤ 1` class. Compression preserves the inversion; it only caps
how far the inverted item travels.

### The S floor: **structurally inert** — it can never bind

Removing the 0.25 floor produced **bit-identical results in every regime**. The reason is
in the shipped lane adapter, not the data: `AttentionScoreCalculator` maps
`SELF_HEAL_LIKELY` to the band midpoint `P_HEAL_LIKELY = 0.75`, so the most demoting value
`S` can ever take is `max(0.25, 1 − 0.75) = 0.25` — the floor **exactly**, reached from
above. `max(floor, 1 − p_heal)` therefore never selects the floor for any of the four
lanes. This is asserted in the self-test (`S floor 0.25 is INERT`).

So §4.1's claim *"the 0.25 floor on `S` means a reliably-self-healing class is demoted at
most 4×, never zeroed"* is **true but not because of the floor**. The 4× bound comes from
the lane→midpoint quantisation. The floor is a genuine guard only against a future change
that feeds `S` a raw rate above 0.75 (§4.1's own text contemplates exactly that: "the S
factor reads the LANE, never a raw rate"). It is dead code with respect to today's
behaviour, and the `self-heal-floor` config key advertised as "the clamp a deployment that
wants to retune demotion has" **does nothing at any value below 0.75**. Raising it above
0.75 works (0.9 → harm 93.96 %) — by *disabling* `S`, which makes things worse.

## 7. Where attention genuinely beats count-only

Stated as plainly as the harms, because it is real.

1. **Self-heal discrimination with timely heals (G3b, and §4c rows ≤ 4×).** When counts are
   comparable and service times uniform, so self-heal is the only signal that carries
   information, `S` cuts mean normalised regret from **0.332 to 0.121** and helps 47.4 % of
   fleets against 33.4 % harmed — and its harm is an order of magnitude smaller in
   magnitude than the full score's (gap 0.073 vs 0.410). At `t_heal ≈ 2 × mttr` it helps
   **64.6 %** of fleets. Count-only has no way to express "don't spend the next hour on
   something that is about to clear itself"; `S` does, and it is right more often than not.
2. **The charitable reading of `M` (§8, GT-B).** If `medMTTR` is read as a per-instance
   *severity weight* (§3's `c_miss(c)`: "weighted by how expensive this class historically
   is to clear") rather than as the operator's service time, and service time is held
   uniform, `M`'s direction becomes correct. In the MTTR-dominated regime G3 the verdict
   **flips**: harm **13.13 %**, help **85.57 %**. This is the strongest legitimate defence
   of `M` available, and it is a defence about *what MTTR means*, not about the arithmetic.
   Note it does **not** rescue the overall picture: pooled over G1, GT-B still gives
   63.78 % harm / 29.60 % help.
3. **`F`'s ordering is fine wherever arrivals rank with totals** — 0.00 % harm from `F`
   alone in G2/G3/G3b (100 % order-identical). `F` is not a harm channel in that (arguably
   realistic) case; it becomes one only when growth and backlog rank differently (§8, the
   coupling sweep).

No other positive was found. In particular the §4.1a burst term produced no measurable
benefit: G5 (arrival rates high enough that the gate fires) shows harm 71.30 % and
attention normalised regret 0.4601 against random's 0.4339 — one of the four regimes where
the score is worse than shuffling.

## 8. Assumption ledger — calibrated vs invented

### Calibrated from the pilot (measured facts, cited)

| assumption | value | source |
|---|---|---|
| sampler cadence | 60 s buckets | `ALARM-COST-MODEL` §14.2 / `R2-SELFHEAL-BASELINE` |
| occurrence coverage | ≈ 99.8 %, one 4-min gap in 16 days | `R2-SELFHEAL-BASELINE-2026-08.md` |
| within-episode jitter | CV ≈ 0 on both live classes | `ALARM-COST-MODEL` §5.6 |
| ⇒ **the score is given a noise-free reading of arrivals** | exact `g·window` | follows from the above; deliberately charitable |
| closed episodes in the pilot | **0** | `R2-SELFHEAL-BASELINE` §Ledger inventory |
| self-heal spells (unconfounded) | **0** | ibid. |
| ⇒ **`M = S = 1` today for every class** | regime **G0/G0b** | `AttentionScoreCalculator` degradation rule |
| shipped ordering today == count-only | Kendall τ = 1.0, 21 229 buckets | `ALARM-COST-MODEL` §5.5 |
| every constant (`τ=24h`, 28 d, min 3 episodes, `[0.5,2]`, 0.25, `W=10min`, onset 10, exit 5, γ=8) | read from `InspectorProperties.Attention` | code |

### Invented — no pilot data exists for any of these

| assumption | what was assumed | direction of bias |
|---|---|---|
| **`mttr_c` = operator service time** | the brief's definition; makes Smith's rule the optimum | **decisive against `M`**; GT-B (§7.2) is the sensitivity |
| MTTR distribution | log-uniform, spread swept 0 → 2.3 decades | wider spread ⇒ more harm (§4a) |
| self-heal probability | uniform(0,1), or bimodal {U(0,.25), U(.75,1)} | — |
| **`t_heal` distribution** | log-uniform over 0.02–1.2 × horizon; swept explicitly in §4c | **decisive for `S`'s sign** |
| member counts `n0` | log-uniform, spread swept 0 → 3 decades | wider ⇒ more harm |
| arrival rate `g` | log-uniform 1e-4 … 2 /min (20 /min in G5) | — |
| **arrivals ↔ backlog coupling** | swept `mix` 0 → 1; see below | **decisive for `F` only** |
| fleet size K | 3–9 | harm rises with K (G6: 88.83 %) |
| horizon | 1–4 × total service time | — |
| operator model | one operator, strictly sequential, no context switching, no partial fixes, skips an already-healed class at zero cost, **stays committed** to a class that heals mid-fix | committed is the **score-favouring** choice; the abandon sensitivity raises harm (§8b) |
| observation quality | score sees the **exact** class MTTR and a **perfectly classified** lane | charitable to `M` and `S` |
| lane ↔ truth map | `p ≥ 0.70 → LIKELY`, `≤ 0.30 → UNLIKELY`, else MIXED | mirrors R2's band definition |
| staleness | 0–50 % of classes stalled, age 1–96 h | drives G4 |
| truncation / blindness | **never simulated** — `arrivalsUnknown` and `burstUnknown` are always false in generated fleets (they are exercised in the self-test only) | see §9 coverage gaps |

### Sensitivity to the two most load-bearing invented assumptions

**(a) The arrivals ↔ backlog coupling** (`mix = 1` ⇒ arrivals rank-identical to totals;
`mix = 0` ⇒ statistically independent), n = 2 000 per point:

| mix | HARM | help | **harm from `F` alone** | count NR | attention NR |
|---|---|---|---|---|---|
| 0.00 | 88.05 % | 11.10 % | 82.45 % | 0.1372 | 0.5145 |
| 0.25 | 89.60 % | 9.40 % | 80.25 % | 0.1181 | 0.5184 |
| 0.50 | 90.30 % | 8.65 % | 78.15 % | 0.1157 | 0.5104 |
| 0.75 | 90.15 % | 8.50 % | 58.85 % | 0.1264 | 0.5053 |
| 1.00 | 90.05 % | 8.65 % | **0.00 %** | 0.1092 | 0.4671 |

The `F` channel's harm is **entirely** an artifact of this invented coupling — it vanishes
at `mix = 1`. **The headline harm rate is not**: it stays at 88–90 % across the whole
sweep, because `M` and `S` do not care how arrivals relate to backlog. Anyone wishing to
dismiss the `F` finding on realism grounds may do so; it does not move the verdict.

This is also the honest reading of regime **G0 vs G0b**. Today's pilot has `M = S = 1`, so
`A = F·R`, so the ordering matches count-only **if and only if** arrivals rank with totals.
G0b (coupled) reproduces §5.5's τ = 1.0 exactly — 100 % order-identical, 0 % harm. G0
(decoupled) gives 76.70 % harm. The measured τ = 1.0 is therefore evidence about the
pilot's *two* classes, not a structural property of the score; a third class whose backlog
and growth rank differently would break it. `AttentionScoreCalculator`'s own neutrality
guarantee is precisely stated ("with **no ledger row at all** every class scores 0.0") and
is not what G0 tests — G0 tests a ledger with arrivals but no closed episodes, which is
the pilot's actual state.

**(b) Operator commitment.** Letting the operator abandon a class that heals mid-fix
*raises* harm (G1 72.09 → **74.20 %**, G2 87.62 → **90.29 %**), confirming that the
committed model used throughout is the assumption that flatters the score.

**(c) Ground-truth-B, the charitable `c_miss` reading** — MTTR as per-instance severity
weight, service time uniform:

| regime | HARM | help | mean gap |
|---|---|---|---|
| G1 global LHS | 63.78 % | 29.60 % | 0.356 |
| G2 compression | 55.40 % | 42.89 % | 0.229 |
| G3 (MTTR-dominated) | **13.13 %** | **85.57 %** | 0.168 |

## 9. What this does and does not license

**It licenses:**

- The claim *"`A(c) = F·R·M·S` approximates the expected cost of waiting better than
  `total DESC`"* is **not supported** under any ground truth tested, and is **contradicted**
  under the brief's. Under the brief's cost model the claim is false at 66 % of fleets
  pooled and 88 % in the wide-dynamic-range regime, and in four of nine regimes the score
  is worse than a random shuffle.
- A **specific, actionable** defect claim about `M`: its direction is inverted with respect
  to operator service time. If `medMTTR` is a service time, `A` should divide by it
  (Smith), not multiply. If it is a severity weight, the doc must say so and the estimator
  must stop being sourced from *closed-episode duration*, which is a duration.
- A **specific, actionable** claim about `R`: it demotes classes that stopped growing while
  their standing backlog keeps accruing at full rate, and count-only is near-optimal in
  exactly that regime (NR 0.0010 vs 0.3126).
- A **specific, factual** claim about the `S` floor: it is inert. `max(0.25, 1 − 0.75)` can
  never select the floor under the shipped lane adapter, so the `self-heal-floor` key has
  no effect below 0.75 and §4.1's "the floor means at most 4×" attributes the bound to the
  wrong mechanism.
- A **pre-registration** for the §8 usability measurement: `S` should be measured
  separately from `M`, and the `t_heal / mttr` ratio is the covariate that decides `S`'s
  sign.

**It does NOT license:**

- **"Turn the flag off."** The flag is off by default, and with today's ledger
  (0 closed episodes, 0 spells) the shipped ordering is *provably identical* to count-only.
  There is nothing to turn off yet. This report is about what happens **when the ledger
  matures**.
- **"The score harms production."** No production number was measured. Every fleet here is
  synthetic, from distributions that are **invented** (§8) because the pilot has no data to
  calibrate them from. A real fleet whose MTTR spread is under half a decade and whose
  self-heals land within 2 service times would look like §4c row 2.00, where attention
  helps 64.6 % of the time.
- **"Count-only is good."** It is not — its own normalised regret is 0.15–0.45, against
  Smith's 0.03–0.18. The finding is *relative*. A cost-aware ordering is a good idea; this
  particular product is not the one.
- **Anything about alarm fatigue.** The brief's cost is pure waiting cost. `c_att` — the
  interruption cost that motivates the whole ALARM-COST-MODEL — is **not priced in this
  ground truth at all**. A score that surfaced fewer, better cards would earn no credit
  here. If `S`'s real job is "don't interrupt me for something that will fix itself", this
  experiment measures only half of its value. That is a genuine limitation of the method,
  not a defence of the numbers above.
- **Anything about the burst term's design intent.** §4.1a is about *peak* rates and
  operator perception (ISA-18.2, Beebe 2013). This model has no perception, no attention
  budget and no miss probability, so a flood's real cost — the operator failing to *notice*
  a card — is unmodelled. G5's result says the burst term does not help on waiting cost; it
  says nothing about whether it helps on noticing.

### Coverage gaps, named

- `arrivalsUnknown` / `burstUnknown` (the §13 F2 truncation/blindness paths) are exercised
  in the self-test but **never in a generated fleet** — no harm search was run over
  partially-untrusted evidence. The §13 F2 correction's own reasoning (untrust correlates
  with class SIZE) suggests that regime deserves its own study.
- Acknowledgement, ack-expiry (§3.2) and auto-resurface (§3.3) are out of scope; only the
  live section's sort order was simulated.
- One operator, one pass, no re-prioritisation after each fix. A model in which the
  operator re-reads the board after every class would give any ordering policy a second
  chance to recover — untested, and it would plausibly reduce all magnitudes.
- `eff(c)` is absent from the score by design (§3.1) and absent from this ground truth too.
- Kendall τ against the oracle order was not reported; the harm predicate is a strict
  cost comparison, which is stronger, but a rank-correlation view might localise *which*
  positions move.

## 10. Reproducibility

- `scripts/attention-harm-search.py`, seed **20260805**, numpy 1.26.4, Python 3.12.
  Deterministic: the self-test uses no RNG, so `--selftest-only` and the full run share a
  stream, and every table above is regenerated byte-identically by re-running.
- The self-test runs **first** and `sys.exit(2)`s on any mismatch with the pinned values,
  so no result can be produced by a drifted port.
- **30 700 main fleets**, each evaluated against all 8 score ablations; plus 115 500
  re-evaluations of those same fleets under altered clamps/floors (7 × 12 500), the
  abandon-on-heal operator (12 500) and ground-truth-B (15 500); plus 12 000 fresh
  heal-timing fleets and 10 000 fresh coupling fleets. Runtime 8 m 25 s on hp04.
- `bash scripts/shell-syntax-check.sh` and `bash scripts/security-audit.sh` pass (the
  script is Python; both gates were run for the branch as a whole).
- `docs/ALARM-COST-MODEL.md` was **not modified** — this report states findings; where
  their conclusions land is the owner's call.

---

## Addendum (2026-08-18, #401 — slice 3 of epic #398): re-pinned to the corrected score

This section is **appended**, not a rewrite. Everything above describes the round that
produced #399/#400 and is a record of that round; read it as such. This addendum re-pins
`scripts/attention-harm-search.py` to the score **after** #399 (`M` dropped: `A(c) = F·R·S`)
and #400 (`S` consumes self-heal timing: `S = max(floor, 1 − p_heal·w)`,
`w = 2^(−t_heal/τ_heal)`, `τ_heal` default `PT1H`), and re-runs the full search at the
**same seed, 20260805** — i.e. the identical 30 700 fleets, re-scored. Reproduce with
`python3 scripts/attention-harm-search.py` / `--selftest-only` / `--quick`.

**Headline verdict: the round helped, broadly and without making the pooled or per-regime
aggregate numbers worse anywhere — but it did not fix everything, and one narrow sensitivity
got measurably worse.** Pooled harm falls from **66.06 % → 54.70 %** and the wide-dynamic-range
regime (G2) falls from **87.62 % → 61.02 %**. The one place the correction shows **zero**
improvement is the exact synthetic point the original report flagged (§4c, `t_heal/mttr = 8`:
still 80.73 % harm / 0.00 % help, bit-identical to the original run) — explained below, and it
is a property of that probe's own design, not evidence #400 doesn't work. The one place the
round measurably **cost** something is the GT-B sensitivity (§8c), where the charitable
defense of `M` no longer has an `M` to defend.

### A.1 The re-pinned self-test — every changed value, and why

44 checks now (up from 36), 0 failures. Every value that moved is named:

| Pinned check | Before (2026-08-05) | After (#401) | Why |
|---|---|---|---|
| `S` at each lane | `self_heal_factor(lane, cfg)` → 0.25/0.50/0.85/1.0 | `self_heal_factor(lane, tts_p50_s, floor, horizon)` → **same values at `tts_p50_s = 0`** | #400: `S` gained a timing argument; at `t_heal = 0` (`w = 1`) it is byte-identical to the old expression — pinned explicitly, not assumed |
| "`M*S` dynamic range … 16.0" | `(2.0·1.0)/(0.5·0.25) = 16×` | **removed** — replaced by "`S` dynamic range (floor..1, t_heal=0) = 1/0.25 = **4.0×**" | #399: `M` is no longer a term, so the "other factor's" fixed range is `S` alone (4×), not `M·S` (16×). This is a *consequence* of the removal, not a reformulation choice — `M`'s own 4× contribution to the old 16× figure is simply gone |
| "F out-ranges M·S only above ~4.3×10⁷ arrivals" | crossover ≈ 4.3×10⁷ (`lo=2`) | **crossover = exactly 80 arrivals** (`2^(4·log2(3)) − 1 = 81 − 1`) | Direct consequence of the span dropping 16× → 4×: `F` now out-ranges the bounded-range factor at *ordinary* fleet sizes instead of only at absurd ones. This is the single most important re-derived fact — it says `F` is a live discriminator on real fleets now, not just a tie-breaker |
| `S` floor inertness | "floor 0.25 is INERT (never binds at any lane)" — checked at one implicit `t_heal` | **generalised across `t_heal`**: floor ≤ 0.25 is bit-identical (`==`, not `isCloseTo`) at all 4 lanes × 5 `tts_p50_s` values (`None, 0, 60, 3600, 86400`) | The worst case (most-demoting `S`) is still exactly at `t_heal = 0`; for every `t_heal > 0` the demoted value is *higher* (further from the floor), so if the floor doesn't bind at `t_heal = 0` it never does. Pinned rather than assumed |
| `S` floor binding boundary | not pinned per-lane | **added**: floor 0.26/0.6 binds on LIKELY (0.25→0.26/0.6), floor 0.6 binds on MIXED (0.50→0.6), floor 0.6 does **not** bind on UNLIKELY (0.85 > 0.6) | Directly tests the §6/§18 correction (A.3 below) — the binding boundary is per-lane (0.25/0.50/0.85), not a single number |
| `S` timing values | not present | **added**: LIKELY heals in 8 h (`tau_heal=1h`) → `1 − 0.75/256 = 0.9970703125`; heals at the horizon (1 h) → `1 − 0.375 = 0.625`; known lane + unknown timing → neutral `1.0`; timing only ever weakens a demotion (`S(t_heal) ≥ S(0)` for every lane × 7 `t_heal` values) | Pinned against `AttentionScoreCalculatorTest`'s own values for #400 part B, verbatim |
| `M` checks (`mttr_factor`) | pinned | **unchanged, still pinned** | `M` is retained as the diagnostic behind `factors.mttr` (#399) — still computed, still tested, just never multiplied into the score. No value here changed |
| `F`, `R`, gate, tie-break, neutrality checks | pinned | **unchanged** | Neither #399 nor #400 touches `F`, `R`, the burst gate, or the tie-break |

No pinned value changed that I could not explain from #399/#400's own diffs — nothing here is
a defect in either slice (see A.6).

### A.2 Corrected vs. original — same fleets, same seed, side by side

Every row below is `evaluate()`'s **corrected** column (`F·R·S`) next to a `original`
column computed in the *same run*, on the *same fleet objects*, via the labelled
counterfactual `attention_score_original` (`F·R·M·S`, the literal 2026-08-05 shipped
formula, no timing) — so this is not two script versions diffed, it is one run scoring the
same 30 700 fleets two ways. The `original` row exactly reproduces the 2026-08-05 report's
own numbers (G0 76.70 %, G0b 0.00 %, POOLED 66.06 % [65.53,66.59], G2 87.62 %
[86.63,88.55] — bit-identical), which is itself the proof that seed 20260805 regenerates the
identical fleets.

| regime | n | **corrected HARM** | 95% CI | help | original HARM (2026-08-05, reproduced here) | 95% CI | help |
|---|---|---|---|---|---|---|---|
| G0 pilot (decoupled) | 3 000 | 76.70 % | [75.15,78.18] | 22.37 % | 76.70 % | [75.15,78.18] | 22.37 % |
| G0b pilot (coupled) | 3 000 | 0.00 % | [0.00,0.13] | 0.00 % | 0.00 % | [0.00,0.13] | 0.00 % |
| G1 global LHS | 8 000 | **65.24 %** | [64.19,66.27] | 26.79 % | 72.09 % | [71.09,73.06] | 21.29 % |
| G2 compression (predicted regime) | 4 500 | **61.02 %** | [59.59,62.44] | 32.71 % | 87.62 % | [86.63,88.55] | 10.67 % |
| G3 converse (MTTR+heal) | 3 000 | **36.97 %** | [35.26,38.71] | 61.97 % | 70.03 % | [68.37,71.65] | 28.67 % |
| G3b converse (self-heal only) | 3 000 | 32.20 % | [30.55,33.89] | 66.47 % | 33.43 % | [31.77,35.14] | 47.37 % |
| G4 staleness | 3 000 | 89.13 % | [87.97,90.20] | 1.87 % | 89.13 % | [87.97,90.20] | 1.87 % |
| G5 flood | 2 000 | **48.70 %** | [46.51,50.89] | 50.25 % | 71.30 % | [69.28,73.24] | 28.10 % |
| G6 big K | 1 200 | **66.92 %** | [64.20,69.52] | 32.92 % | 88.83 % | [86.93,90.49] | 11.17 % |
| **POOLED** | **30 700** | **54.70 %** | **[54.14,55.25]** | 31.25 % | **66.06 %** | **[65.53,66.59]** | 19.18 % |

Conditioned on the two orders actually differing, corrected harm is **63.6 %**
(54.70/(100−14.05)), down from the original's **77.5 %**.

**Two regimes are bit-identical, correctly.** G0 and G4 have `M=1` in *both* formulas
already (G0: zero closed episodes; G4: `heal_mode="none"`, no lane ever assigned), so
neither `M`'s removal nor `S`'s new timing term has anything to act on. G0's own harm is
entirely an `F` artifact (§9 "worst case" below); G4's is entirely `R` (unchanged from the
original report's own finding — a stalled class demotes on staleness regardless of #398).

**Every other regime improved, several sharply**, and none got worse in this table:
G2 −26.6 pp, G5 −22.6 pp, G6 −21.9 pp, G3 −33.1 pp, G1 −6.9 pp. G3b's small improvement
(−1.2 pp harm, but help rises 47.37 %→66.47 % and mean gap falls 0.073→0.059 — see the JSON
for the fuller picture) reflects that regime already having `M ≡ 1` **exactly** in the
*original* score too (`mttr_spread = 0.0` collapses every class's median to the identical
fleet median, so `M = clamp(1.0, …) = 1.0` bit-for-bit even pre-#399) — #399 changes
nothing there; the (small) movement is entirely #400.

### A.3 Absolute quality — normalised regret, corrected vs. original vs. random vs. Smith

| regime | count | **corrected** | original | random | Smith |
|---|---|---|---|---|---|
| G0 pilot (decoupled) | 0.1782 | 0.4349 | 0.4349 | 0.4731 | 0.0582 |
| G0b pilot (coupled) | 0.1432 | 0.1432 | 0.1432 | 0.4674 | 0.0352 |
| G1 global LHS | 0.2043 | **0.3653** | 0.4524 | 0.4693 | 0.0695 |
| G2 compression | 0.1543 | **0.2076** | 0.4918 | 0.4589 | 0.0532 |
| G3 converse (MTTR+heal) | 0.4522 | **0.3327** | 0.6160 | 0.4581 | 0.0504 |
| G3b converse (self-heal only) | 0.3320 | **0.0626** | 0.1207 | 0.3729 | 0.3320 |
| G4 staleness | 0.0010 | 0.3126 | 0.3126 | 0.4969 | 0.0010 |
| G5 flood | 0.2583 | **0.2277** | 0.4601 | 0.4339 | 0.1845 |
| G6 big K | 0.1712 | **0.2298** | 0.5062 | 0.4596 | 0.0407 |

Corrected regret is **never worse than original** in this table, and in G1/G2/G3/G5/G6 it is
no longer worse than the random control either (the original report's finding that attention
was worse than a shuffle in four of nine regimes — G2, G3, G5, G6 — **no longer holds**:
corrected regret sits below random in all of them now, though still well above Smith).
G4 is unchanged (0.3126 both — `R`-only, as above) and remains the one regime where
count-only is close to optimal (0.0010) while corrected is not.

### A.4 Same-order fraction — did it "rise sharply"? Not pooled, and here's why

The prediction (#401's own framing) was that with `M ≡ 1`, the corrected order should
collapse onto count-only far more often. **It rose in every regime where `M` genuinely
discriminated in the original score, and fell in the pilot's own G3b — pooled, the two
roughly cancel (14.66 % → 14.00 %, a *decrease*, well outside the ~0.4 pp Wilson noise floor
at this n but tiny in absolute terms).**

| regime | corrected same-order | original same-order | direction |
|---|---|---|---|
| G0 | 0.93 % | 0.93 % | unchanged (M=1 in both already) |
| G0b | 100.00 % | 100.00 % | unchanged |
| G1 | **7.98 %** | 6.62 % | ↑ as predicted |
| G2 | **6.27 %** | 1.71 % | ↑ sharply, as predicted |
| G3 | 1.07 % | 1.30 % | ↓ slightly (within noise: ±0.4 pp Wilson half-width at n=3000) |
| G3b | **0.93 %** | 18.30 % | ↓ sharply, opposite of the prediction |
| G4 | 8.90 % | 8.90 % | unchanged |
| G5 | **1.05 %** | 0.60 % | ↑ as predicted |
| G6 | **0.17 %** | 0.00 % | ↑ as predicted |
| **POOLED** | **14.00 %** | 14.66 % | ↓ (dominated by G3b) |

**Why G3b moved the wrong way.** In G3b, `M ≡ 1` **exactly** in the original score too
(A.2), so #399 changes nothing there — the movement is entirely #400. #400 gave `S` a
*continuous* input (`t_heal`, drawn independently per class) where before it only had a
*discrete* one (the lane, 4 values). A discrete-only `S` ties with the count order far more
often by construction (only 4 possible `S` values to break ties with); handing it a
continuous per-class covariate breaks most of those coincidental ties into a genuine total
order, which mechanically **reduces** the same-order fraction even though (A.2, A.3) it
makes the resulting order *better* on every other measure in this exact regime (help
47.37 %→66.47 %, mean gap 0.073→0.059). More information reducing "accidental agreement
with count-only" while improving actual quality is the expected shape of this effect, not a
contradiction. G1/G2/G5/G6 show the *predicted* direction because those regimes have real
`mttr_spread > 0`, so `M` was genuinely reordering things pre-#399 that #399 stops
reordering.

### A.5 The §4c `t_heal` sweep — the direct test of #400's fix

| `t_heal / mttr` | corrected harm% | help% | original harm% | help% |
|---|---|---|---|---|
| 0.10 | 33.47 % | 43.53 % | 33.47 % | 43.53 % |
| 0.25 | 31.00 % | 50.07 % | 31.00 % | 50.07 % |
| 0.50 | 33.00 % | 45.27 % | 33.00 % | 45.27 % |
| 1.00 | 31.53 % | 48.07 % | 31.53 % | 48.07 % |
| 2.00 | 15.00 % | 64.60 % | 15.00 % | 64.60 % |
| 4.00 | 32.73 % | 48.47 % | 32.73 % | 48.47 % |
| **8.00** | **80.73 %** | **0.00 %** | **80.73 %** | **0.00 %** |
| 16.00 | **46.87 %** | 0.00 % | 78.60 % | 0.00 % |

**This is the one place the round shows zero measured benefit at the exact point the
original report flagged, and it needs to be reported as such — not smoothed over.**
Through `t_heal/mttr = 8` (inclusive — the original headline's own number), corrected and
original are **bit-identical**, not just close. Only at 16× does correction show up
(46.87 % vs 78.60 %).

**Why, mechanically — verified by direct inspection of individual fleets, not just the
aggregate.** This sweep's own fleet generator (`gen_heal_timing`, unchanged by this slice)
gives every class in a fleet the **same absolute `t_heal`** (`heal_t_fixed_mult × mttr`,
and `mttr_spread=0.0` makes `mttr` itself identical across classes too), with
`count_spread=0.05` and arrivals rank-consistent with `n0`, so `F·R` is nearly flat across
the fleet's 5 classes by construction — this probe exists to isolate `S`'s own effect, and
it does that by suppressing everything else. Given one shared `t_heal` (hence one shared
`w`), `1 − p_heal·w` is monotone decreasing in `p_heal` for **any** `w > 0` — so the
*relative* ranking `S` imposes across lanes (UNLIKELY > MIXED > LIKELY) is **invariant to
`w`**, i.e. invariant to `t_heal`, whenever `F·R` doesn't have enough spread to overturn it.
Verified directly: for a `t_heal/mttr = 8` fleet, `S` values were **0.953 (LIKELY) vs. 0.25
original** — a large absolute change — yet `order_attention == order_original` for that
fleet, because the *ordinal* information `S` contributes didn't change, only its magnitude.
Only once `w` shrinks enough (`t_heal/mttr = 16` ⇒ `w = 2^-16/6·60/60...` ≈ tiny) that the
now-compressed `S` spread becomes comparable to the fleet's residual `F·R` noise does the
order actually flip.

**This is a limitation of the existing synthetic probe, not a defect in #400.** The probe
was built (pre-#401) to cleanly isolate `p_heal`'s own effect by holding everything else
flat — which is exactly what makes it structurally blind to a *magnitude-only* correction
when `t_heal` is shared fleet-wide. The realistic regimes, where `t_heal` is drawn
**independently per class** (G1, G2, G3, G3b, G5, G6 — A.2), show the fix working broadly
and substantially. Read together: **#400 fixes the general per-class-varying case
(measured, A.2) but this specific controlled single-point probe of "exactly 8× service
time" cannot see it** — worth naming as a gap in the probe for whoever next touches this
sweep, not a claim that #400 under-delivers.

### A.6 Do the clamp/floor/horizon knobs bound the damage? (full run, n=8 000/4 500)

**`M`'s clamp is now provably inert on the corrected score, everywhere tested** — widened
`[0.1,10]`, removed `[0,∞)`, tightened `[0.8,1.25]`, or disabled `[1,1]`: **all four produce
the exact same corrected harm rate as shipped** (G1: 65.24 % in all five rows; G2: 61.02 %
in all five), because `M` is not a term of `F·R·S`. The same four variants move `original`
substantially (G2: 64.62 %→89.98 % across the same range) — the direct falsifier of "the
clamp still matters," run on request rather than merely asserted.

**The `S` floor stays inert at 0.25** on both formulas (`S floor REMOVED (0.0)` is
bit-identical to shipped on both corrected and original, in G1 and G2) — consistent with
A.1/A.3.

**Raising the floor moves corrected and original in *opposite* directions.**
`S floor RAISED to 0.9` (≈ disables `S`, forcing it toward 1 for nearly every class):
G2 corrected harm **falls** to 25.18 % (collapsing toward the near-optimal "`F` only"
behaviour this regime has, A §4b in the original report), while G2 original harm **rises**
to 93.96 % (matching the original report's own finding that disabling `S` there removes its
one genuine benefit and makes `M`'s dominant error worse). Same knob, same fleets, opposite
effect — because the corrected score has nothing left for a disabled `S` to be a net loss
*against*.

**`tau_heal` (new knob) behaves as designed.** Shortening it to 10 min weakens the timing
term's reach (G2 corrected: 61.02 %→44.33 %, since `S` now decays to neutral over a much
shorter half-life, so most of G2's per-class `t_heal` draws saturate `S`→1 well before the
score sees them); lengthening it to 24 h strengthens it back toward
original behaviour (G2 corrected: 61.02 %→66.69 %, still well short of original's 87.62 %
since `M`'s removal is untouched by this knob). Monotone in the predicted direction both
ways.

### A.7 GT-B — the one sensitivity the round measurably cost something on

The original report's §8(c) GT-B is the **charitable** reading of `M` (MTTR as a
per-instance severity weight, operator service time held uniform) — the strongest available
defense of `M`'s direction, explicitly caveated there as "not the brief's cost model."
Since GT-B only reweights the **ground-truth cost**, not the score, it is a fair,
same-fleets comparison against the original report's own GT-B numbers:

| regime | original GT-B harm (2026-08-05) | corrected GT-B harm (this run) | Δ |
|---|---|---|---|
| G1 | 63.78 % | 64.16 % | +0.4 pp (noise) |
| G2 | 55.40 % | 59.73 % | **+4.3 pp** |
| G3 | **13.13 %** | **37.47 %** | **+24.3 pp** |

**This is the round's one honest cost, and it is exactly what #399's own design doc predicts
rather than a surprise.** GT-B existed specifically to test whether MTTR-as-severity makes
`M`'s direction correct — in G3 it flipped the original verdict from 70.03 % harm to
13.13 % harm. Since the corrected score doesn't consume `M` (or any severity proxy) at all,
it cannot benefit from that reading anymore: G3 GT-B harm reverts to essentially G3's own
baseline corrected number (36.97 %, A.2) because there is no `M` left to weight by
severity. `ALARM-COST-MODEL.md` §17.1/§17.4 names this trade-off explicitly ("Both readings
are refused… `M` re-enters the ordering only behind an uncontaminated estimator") and its
own re-entry falsifier is the intended way back to recovering this upside, not a defect in
#399 to be fixed here. Recorded as a **result**, per the brief: the round is a net
improvement pooled and in every regime tested directly (A.2–A.3), but it gives up GT-B's
best-case defense of `M` along with `M`'s dominant, larger downside — a trade the original
report itself, and #399's design doc, already argued was correct to make.

### A.8 Verdict on the two claims this slice was asked to check

**1. "§6 overstates the inert band — the true boundary is 0.25, not 0.75; the headline (0.25
default is bit-identically inert) is correct."** **Confirmed independently**, three ways: (a)
direct algebra — `S = max(floor, 1−p_heal·w)` binds iff `floor > 1−p_heal·w`; at `w=1`
(worst case) that is `floor > 0.25` for LIKELY, `floor > 0.50` for MIXED, `floor > 0.85` for
UNLIKELY, so "does nothing below 0.75" is correct only for the UNLIKELY lane and wrong for
the other two; (b) the re-pinned self-test now asserts exactly that per-lane boundary
(A.1) and passes; (c) `ALARM-COST-MODEL.md` §18 already carries this identical correction
verbatim ("The binding boundary is **0.25**, not 0.75: at `floor = 0.5` the clamp already
selects for both `SELF_HEAL_LIKELY` (0.25) and `SELF_HEAL_MIXED` (0.50)… The report's
*headline* claim… is correct") — so this finding is not new, it is independently
re-derived and cross-checked against a correction #400 already recorded. One nuance worth
naming precisely: at `floor = 0.5` MIXED's value is a **boundary tie** (already exactly
0.50 pre-floor), not a value *change* — "selects" is mathematically true (`max` picks the
floor argument) but produces no observable difference for MIXED at that exact floor; the
observable change at `floor = 0.5` is LIKELY's alone (0.25→0.5). This generalises cleanly
under #400's timing term too: the worst case (most-demoting `S`) is still exactly
`t_heal = 0`, so the 0.25 boundary is unchanged by the timing amendment (A.1).

**2. "`ttsP50Seconds` is NOT hysteresis-stabilized — only `lane` runs through
`DwellStateMachine` — and the simulation can't say anything about flap risk."**
**Confirmed** from source (`RETRYING-RISK-LANE.md` §4.2's own correction, and
`ALARM-COST-MODEL.md` §18's "Named correction to RETRYING-RISK-LANE.md §4.2" both state it
directly: `SelfHealStatsService.get()` runs only `lane` through `DwellStateMachine`;
`n`/`healed`/`ttsP50Seconds`/`ttsP90Seconds` are served raw off the 60 s Caffeine cache).
**And the second half is also correct: this harm-search simulation cannot speak to it,
structurally.** Every fleet here is a single static snapshot — one score computed once per
fleet, compared against a hidden-dynamics cost integral. There is no repeated-poll,
multi-cycle model anywhere in this script (unlike `DwellStateMachine`'s own dedicated test
suite, which *does* exercise repeated cycles). A raw, non-dwelled `ttsP50Seconds` that
genuinely oscillated between polls — e.g. hovering near a `10`-spell sample-size floor, or
near a horizon-relative threshold where `w` is sensitive — would be invisible to this
method entirely; the ordering-stability argument #400 makes (§4.1b: `w` is continuous,
monotone and bounded, `|ΔS| ≤ p_heal·ln2/τ_heal·Δt`, so a whole sampler bucket of movement
in the median moves `S` by under 0.009 at the `PT1H` default) is analytic, not something
this harness measures or could measure without a genuinely new multi-cycle simulation
mode. Flagging as a named coverage gap (alongside `arrivalsUnknown`/`burstUnknown`, per §9
of the original report) rather than attempting to address it here — out of scope for a
re-pinning slice.

### A.9 Defects found in #399/#400? None in the Java

Every value that changed between the two self-test pins (A.1), every regime movement (A.2),
and the one place the round didn't help (A.5) all trace cleanly to #399/#400's own
documented mechanics — nothing here required a value I couldn't explain from the diffs, so
nothing here is being reported as a slice defect per this issue's stop-condition. The
closest thing to a gap found is **methodological, in the harm-search harness itself, not in
`AttentionScoreCalculator`**: the §4c sweep's single-shared-`t_heal`-per-fleet design (A.5)
makes it structurally insensitive to #400's fix at any one fixed multiple, which is worth
naming for whoever next extends that sweep (e.g. a per-class-varying-`t_heal` variant of
the same probe would show the fix's effect continuously rather than only past a threshold)
— not a change made in this slice, per the rails against "improving" the port while pinning
it.

### A.10 What this addendum does and does not license

**Licenses:** the correction round (#399+#400) measurably reduces harm pooled and in every
regime directly comparable to the original run, without making any of those numbers worse;
the wide-dynamic-range regime the brief predicted (G2) drops from 87.62 %→61.02 %; the `M`
clamp knobs are now provably inert on the ordering (A.6); the two claims this slice was
asked to check are both confirmed from source, independent of this simulation.

**Does not license:** "the score is now harmless" — G2's own corrected harm rate is still
61.02 % outright (65.1 % conditioned on the two orders actually differing), corrected regret
still exceeds Smith's by a wide margin everywhere, and the single worst fleet found this run
still shows attention costing
4.33× what count-only costs on a realistic G2 fleet (96.8 % of the oracle-to-worst span) —
the correction narrows the damage, it does not eliminate it. It does not license flipping
`inspector.triage.attention-ordering` — still default-false, §7's gate is still NOT MET
(0 of 5), and this remains a synthetic-fleet study exactly as caveated in §8/§9 above,
unchanged by this slice. It does not license treating A.5's null result as "#400 doesn't
work" — the realistic per-class regimes (A.2) are the more representative test and show it
does.

### A.11 Reproducibility

- `scripts/attention-harm-search.py`, seed **20260805** (unchanged), numpy 1.26.4,
  Python 3.12. `--selftest-only` reproduces A.1's 44/44 pass; the full run reproduces every
  table above byte-for-byte (the `original` columns independently re-derive the 2026-08-05
  report's own published numbers, confirming the fleets are identical).
- **30 700 main fleets**, each now additionally scored against the labelled `original`
  (pre-#398) counterfactual in the same pass — no second run needed for the before/after
  comparison. Runtime this run: full log in the branch's own record; on the order of the
  original's 8m25s (same fleet count, one extra cheap score per fleet).
- `bash scripts/shell-syntax-check.sh` and `bash scripts/security-audit.sh` pass.
- `docs/ALARM-COST-MODEL.md` was **not modified** by this slice (per the #401 rails); this
  addendum records findings only. §17/§18 already anticipate and partially pre-empt two of
  this addendum's findings (the §6 floor-boundary correction, A.8.1) — cross-referenced,
  not duplicated as new claims.
