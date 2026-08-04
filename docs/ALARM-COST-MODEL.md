# Alarm & Attention Cost Model — cost-aware noise policy for Stage 0 + incident ledger (R1)

Status: **DESIGN, backend + frontend build slices ★ SHIPPED** — issue #348 (track R1, umbrella
#356) · drafted 2026-08-04 from a live pilot-ledger measurement · panel: 1 of 2 seats complete,
second seat owed (§10) · build slices #353 (backend attention score — **built**, §11:
`io.inspector.attention.*`, shipped **FLAG-OFF**, `inspector.triage.attention-ordering=false`)
and #354 (frontend ordering + rationale — **built**, §11: renders whatever order it is served,
never re-sorts Stage 0 itself, and reconciles with #352's Incident Ledger self-heal-risk sort)
· **data-maturity gate NOT MET as of 2026-08-04** (§7, 0 of 5 axes). Unlike R2 — whose machinery
ships enabled and self-gates per class on its own sample floor — R1's gate governs the FEATURE
ITSELF: the ordering is a single global switch over shared cards, so it ships inert and
flipping it requires re-measuring §7 with the §5 method.

**Amendment round (#365, 2026-08-04): burst-aware frequency — §4.1a (formula) + §14
(measurement, build-slice spec, panel) — DESIGN LOCKED, and the build slice ★ SHIPPED (§15),
still FLAG-OFF.** The flag default is untouched. **One §7 gate axis DID change**: the same
round's feasibility measurement found 99.2 % of the pilot's occurrence rows are blind
(`cycle_complete = false`, everything before 2026-08-04T15:39 Z), so **G5 is redefined over
TRUSTED span** and its earliest-satisfaction date moves ≈ 2026-09-14 → ≈ 2026-09-29 (§5.1,
§5.5 and §7 corrections). G1–G4 were audited and are not trust-gated.

**§8's usability A/B run plan is AUTHORED (issue #366, 2026-08-04) — comprehension probe +
operator doc (R-SEM-25, `docs/usability/MISSIONS.md` M13, `docs/OPERATOR-QUICK-START.md`)
specified end to end, run NOT YET EXECUTED (§8.8).** Issue #359 (the transiently-failing
self-heal seed fixture) landed 2026-08-04 as `85342e1`/PR #368 — ALL FOUR self-heal lanes,
including `SELF_HEAL_LIKELY`/`SELF_HEAL_MIXED`, are now stageable for §8 (§8.6); the fixture
is opt-in (`PI_SEED_SELF_HEALING=1`) and, by design, counts toward neither this doc's own §7
gate nor RETRYING-RISK-LANE.md's §7.2 gate (§8's own sequencing note explains why).

## 0. Provenance

Drafted 2026-08-04 from (a) a hand inventory of every hand-tuned noise-control constant in
the current codebase (§2, file:line cited) and (b) a REST-only extraction of the REAL pilot
incident-ledger history from the live demo (`https://pi.naumann.cloud`) — method and raw
numbers in §5, which is the auditable substrate for every calibration claim in this doc.
Measured facts are labeled MEASURED; everything else is design proposal.

Literature base (#348, DOIs verified in #356): Fahrenkrog-Petersen et al., *Fire Now, Fire
Later* (KAIS 2022, 10.1007/s10115-021-01633-w) — the parameterized alarm cost model this doc
adapts; Shoush & Dumas, *When to Intervene?* (BPM 2022, 10.1007/978-3-031-16171-1_13) —
ranking cases for scarce operator attention; Wang/Yang/Chen/Shah, *An Overview of Industrial
Alarm Systems* (IEEE TASE 2016, 10.1109/TASE.2015.2464234) — the alarm-overload human-factors
case SPEC §4 already gestures at ("alarm fatigue within weeks").

Amendment round #365 adds the alarm-management human-factors base (full-text calibration
digest: #356 comment 5182803157): Beebe, Ferrer, Logerot, *The connection of peak alarm rates
to plant incidents and what you can do to minimize* (Process Safety Progress 32(1), 2013,
10.1002/prs.11539) — "peak rates are responsible for operators missing critical alarms and
average rates are not"; their case data shows sites with fine averages (0.83–1 alarms/10 min)
still hitting peaks of 117–211/10 min (evidence grade: practitioner paper, 4 uncontrolled
sites — cited for the consensus ceilings and the framing, not as causal proof). Normative:
**ANSI/ISA-18.2** (flood defined as ≥ 10 annunciated alarms per 10 min per operator, ending
when the rate drops below 5 per 10 min — an asymmetric, hysteresis-bearing *peak-window*
definition, not an average) and **EEMUA 191** (the same ceilings as benchmarks).

Panel review (repo convention, two independent seats — full outcome in §10): the Gemini
seat reviewed and returned APPROVE-WITH-CHANGES (findings adopted below); the GitHub-Models
seat was **unavailable at review time** (service-level HTTP 410 Gone) and was NOT
substituted with an unauthorized model — the seat is recorded as owed, to be taken before
or at the design-lock PR.

## 1. Problem

Stage 0's noise controls all work — the pilot has not rotted into alarm fatigue — but every
one of them is a hand-tuned constant chosen by intuition at build time:

- the two derived health-strip alarms (*oldest executable job age*, *overdue timers*) and
  their R-NFR-04 thresholds,
- the R-BAU-01 ack auto-resurface baseline factor (+20%),
- the ack-expiry presets (no suggested default at all),
- the incident-ledger regression hysteresis (`regression-min-count`),
- and the triage-card ordering itself, which is **count-only**
  (`TriageAggregationService.java:126` — `total DESC`): a 300-count known-noisy class
  outranks an 8-count outage of a critical dependency forever.

The prescriptive-monitoring literature replaces intuition with an explicit **cost model**:
missed/late-attention cost vs. operator-attention cost, with mitigation effectiveness as a
first-class parameter. This doc defines that model, states which knobs become model-derived
and which honestly stay constants, and specifies how parameters are estimated from
incident-ledger data — with a numeric data-maturity gate, because the pilot ledger is
demonstrably too thin today (§5, §7).

**This track orders and expires attention only. It prescribes no interventions** (issue
#348 non-goal; #106 remediation playbooks stay untouched and gated). There are no
notification channels (none exist; out of scope). Ordering **never hides a card** —
acknowledged groups keep their labeled, never-hidden collapse semantics (R-BAU-01)
verbatim.

## 2. Current constants — inventory (MEASURED from code, 2026-08-04)

| # | Knob | Current value | Where | Verdict (§3/§7) |
|---|---|---|---|---|
| C1 | Triage card ordering | count-only, `total DESC` | `backend/src/main/java/io/inspector/triage/TriageAggregationService.java:126` | → **model-derived** (attention score, §4) |
| C2 | Ack-expiry default | **none** (expiry optional; server validates only ISO/future — `ErrorGroupAckService.java:221-238`); UI presets none/4h/24h/3d/7d/30d, initial selection `none` | `frontend/src/triage/AcknowledgeGroupModal.tsx:18-26,41` | → **model-derived suggestion** (class-P75 episode duration, §3.2), presets stay |
| C3 | Auto-resurface baseline factor | +20 % past acked baseline | default in `backend/src/main/java/io/inspector/config/InspectorProperties.java:67-69` (`ack-resurface-threshold-pct`), consumed `ErrorGroupAckPolicy.java:85` | → **model-derived** (jitter-calibrated, §3.3) |
| C4 | Regression hysteresis | `regression-min-count` = 1 (plus the zero-state gate) | `InspectorProperties.java:193-195`, consumed `IncidentLedgerService.java:224` | **stays a constant** (§3.4 — zero regressions ever observed; nothing to fit) |
| C5 | R-NFR-04 alarm thresholds | oldest executable job: warn > 5 min / crit > 15 min; overdue-timer grace 60 s; any overdue = warn, > 100 = crit (SPEC §4); client display mirror warn = 300 s | `InspectorProperties.java:530-542` (`AlarmThresholds`); `frontend/src/components/HeaderStrip.tsx:15-17`; SPEC §4 "Alarm thresholds" | **stay constants** (§3.4 — zero starvation events in 21 d of snapshot history; per-engine override already exists) |
| C6 | Recency-adjacent: incident quiet window | 24 h (read-time derivation) | `InspectorProperties.java:189-191` | reused as the recency half-life default τ (§4) — one operator mental model |
| C7 | Context: triage cache TTL / refresh throttle | 20 s / 10 s | `InspectorProperties.java:55-61` | untouched (spec-pinned, R-NFR-03) |

Incident-list ordering (`GET /api/incidents`) is `lastSeen DESC` under section grouping
REGRESSED → OPEN → QUIET → RESOLVED (INCIDENT-LEDGER §6/§8) — the list keeps its section
doctrine; the attention score orders **within** the live sections only (§4).

## 3. The cost model

Adapted from *Fire Now, Fire Later*: an attention policy is an alarm policy with explicit
costs. Per error class `c` (R-SEM-03 fingerprint, the ledger identity):

| Parameter | Meaning | Source |
|---|---|---|
| `c_att` | cost of drawing operator attention (surfacing/resurfacing a card) — the alarm-fatigue currency | constant per deployment; never per-class |
| `c_miss(c)` | cost of delayed attention: live stuck instances × time, weighted by how expensive this class historically is to clear | `lastTotal` × MTTR statistics (ledger episodes) |
| `eff(c)` | mitigation effectiveness — probability operator intervention actually drains the class | ledger occurrence deltas joined to intervention timestamps (§6; MEASURED once on the pilot, §5.4) |
| `p_heal(c)` | self-heal probability | **consumed from track R2** (#347 design / #351 API) — interface in §4.2, never computed here |

Expected net benefit of attending to `c` now:
`B(c) = (1 − p_heal(c)) · eff(c) · c_miss(c) − c_att`. Three derivations fall out:

### 3.1 Ordering (→ §4)
`c_att` is identical across cards, so ranking by `B` reduces to ranking by the product
score — no absolute cost calibration needed for ordering. This is why the ordering can ship
first: it is the cost model's *ordinal* shadow and needs no currency unit.

**`eff(c)` is deliberately NOT a factor of the v1 attention score** (panel fix — stated
explicitly rather than implied): per-class effectiveness attribution is blocked for
single-job verbs (redacted audit payloads carry no signature, §5.4/§6), so in v1 `eff`
would collapse to a fleet-wide prior and discriminate nothing — a constant factor with an
honesty problem. It parameterizes the conceptual model `B(c)` and the §3.2/§3.3
derivations; it enters the *ordering* only in a future revision, if per-class attribution
(bulk error-class envelopes first) ever produces per-class values that pass the same
sample-size rails as every other estimate.

### 3.2 Ack-expiry suggested default (C2)
An ack is a bet that attention is not needed for a while. The break-even mute duration is
when accrued expected miss-cost reaches `c_att`. Proxy without currency calibration: suggest
the expiry preset nearest the class's **P75 closed-episode duration** once the class has ≥ 3
closed episodes; otherwise the fleet-tier P75; otherwise no suggestion (today's behavior,
selection `none`). UI change is a **pre-selected preset + one-line why**; the operator always
overrides; the resurface guarantees are untouched.

**Correction (post-ship, adversarial review 2026-08-04 — §13 F6).** The copy above originally
read *"episodes of this class usually resolve within X"*, and that was FALSE at the sample
size this estimator is gated at. `Quantiles` is deliberately **nearest-rank and
un-interpolated** (shared with `SelfHealStatsComputer` so "P75" means one thing across both
research tracks), so at `min-closed-episodes = 3` the rank is `ceil(0.75 · 3) = 3` ⇒ index 2 ⇒
the **longest** of the three recorded episodes. "Usually resolve within X" reads as a
typical-case claim; the number is an observed maximum. **The statistic is kept, the claim is
corrected**: erring long is the safe direction for a mute suggestion (a too-short expiry buys
an interruption nobody asked for), an interpolated quantile at n = 3 would invent precision the
sample does not carry, and changing the estimator would also move R2's shipped `ttsP90` badge.
The honest wording, and the one the code now carries, is: **"at least 75 % of the N recorded
closed episodes resolved within X — and at N = 3 that is all three, i.e. the observed
maximum."** The same nearest-rank shape applies to `SelfHealStatsComputer.percentile` for p90
at n = 10 (rank `ceil(9) = 9` of 10 — the 9th of ten, not the maximum, so the existing
"typically ≤ X min" copy there is already a `≤` bound and stays correct).

### 3.3 Auto-resurface baseline factor (C3)
+20 % is currently arbitrary. Model-derived: the threshold should exceed normal
within-episode count jitter so a resurface fires on genuine growth, at a target
false-resurface budget. Estimator: per class, the coefficient of variation `CV(c)` of
occurrence totals within stable episode segments; threshold
`t(c) = max(floor_pct, k · CV(c))` with `floor_pct = 10 %`. `k` is fit by
**counterfactual-ack replay** (panel fix — real acks are not required to *fit*): simulate
an ack at every stable occurrence-series point, replay the threshold forward, and count
simulated resurfaces not followed by genuine episode growth as false — computable from
`incident_occurrence` alone, so the estimator is well-defined even at 0 real acks. Real ack
lifecycles (gate G4) are then required to *validate* the derived threshold against actual
operator behavior before it takes effect: **C3 switches to the derived value only after the
full §7 gate**, and until then the constant 20 % stays and the config key keeps working as
today (per-deployment override). Target budget: ≤ 1 false resurface per 30 ack-days on the
replay.

**Correction (post-ship, adversarial review 2026-08-04 — §13 F4/F5).** As first shipped, opting
in to `derived-resurface-threshold` **halved** the guard instead of tightening it, on exactly
the data this design was built for. Two independent defects, both fixed:

- **A vacuous fit won.** For a low-jitter class — *the measured pilot state*, §5.6 "CV ≈ 0 on
  both live classes" — every grid candidate collapses to `max(10, k·0·100) = 10 %`, nothing
  ever crosses it, so `falseResurfaces = 0 ≤ budget` held **vacuously** and the smallest grid
  point `k = 0.5` won immediately. `thresholdPct` then returned `max(floor_pct, ceil(0)) = 10`.
  Merely opting in therefore moved every static class from a 20 % threshold to a 10 % one —
  **twice as many ack interruptions** — via a fit satisfied by having no data. Now: a replay in
  which the *smallest* candidate on the grid fires ZERO resurfaces is **UNFITTABLE** (the series
  never exercised any threshold, so it carries no information about one) and the class keeps the
  constant. Monotonicity makes the smallest-candidate probe exact rather than merely cheap, and
  a genuinely noisy class is untouched — its small candidates fire plenty.
- **A fitted value could still lower the guard.** Belt and braces, independent of the above: the
  derived value is now **floored at `inspector.triage.ack-resurface-threshold-pct`**. §3.3's
  purpose is to lift the threshold *clear of* jitter; a derived value below today's constant
  would interrupt the operator more often than the constant does. **Opting in can now only ever
  raise the threshold, never lower it.**
- **Censored settle windows counted as proven growth.** `growthHeld` silently SHORTENED its
  window to the segment end (`end = min(size-1, resurface + SETTLE_BUCKETS)`) instead of
  discarding an unobservable sample. When the resurface landed on the LAST index the loop ran
  exactly once, at an index that by construction sits above the trigger — so it **always
  returned true**. And segments break at every zero and every truncated bucket, so the common
  "class spikes, then drains to zero" shape puts the spike at the segment tail: precisely the
  case that should count as a FALSE resurface was banked as genuine. The bias ran one way —
  false resurfaces under-counted ⇒ fitted `k` too small ⇒ a shipped threshold delivering *more*
  false resurfaces than the budget promises. Now an ack whose settle window would run past the
  segment end is **skipped entirely** (neither numerator nor denominator of the budget).

**Not changed, and why (the reviewer's secondary, PLAUSIBLE item — `ackDays` grows ~O(L²) in
segment length while `falseResurfaces` grows ~O(L), so on long quiet segments the budget may be
non-binding).** Judged NOT a defect. `ackDays` is an EXPOSURE denominator over independent
simulated acks — the person-years construction — and numerator and denominator are pooled over
the same set of simulated acks, so `Σfalse / Σexposure` is exactly "expected false resurfaces
per ack-day if you ack at a uniformly random moment", which is the quantity the budget is
phrased in. A long quiet segment giving a low rate is the correct answer, not a dilution
artifact. And the fits it could distort are now structurally harmless: an under-fitted `k` can
only produce a value at or below the constant, which the new floor pins back to the constant.
**Residual, unverified:** an ack that never resurfaces still accrues its full exposure, and for
the LAST segment of a series (which ends at the series end rather than at a genuine drain to
zero) that exposure is itself right-censored. Not fixed — no case was constructed where it
moves a fitted `k`, and the same floor bounds its effect.

### 3.4 What stays a plain constant — honesty about thin data
- **C5 R-NFR-04 thresholds**: executor starvation is engine-executor pathology observed in
  the snapshot store, not the ledger — and the pilot has **zero** starvation events in 21
  days of history (§5.6): nothing to fit, and the event is too rare to ever fit soon.
  Per-engine YAML override remains the operator's control. Not model-derived.
- **C4 regression hysteresis**: zero regressions (true or false) ever recorded (§5.3). The
  zero-state gate (INCIDENT-LEDGER §5) is the primary anti-zombie control; `min-count = 1`
  stays. Re-visit only if false regressions are ever actually observed and hurt.
- `c_att` itself: no interruption-cost telemetry exists or is planned; it stays implicit
  (it cancels out of ordering, and §3.2/§3.3 use proxies).

## 4. Attention-score ordering (issue point 2)

### 4.1 Score
Per live (non-acknowledged) card `c`, computed BFF-side at render join time — exactly where
ack state joins today, from ledger + R2 data already in the BFF's own Postgres. **Zero new
engine calls; the Stage 0 iron rule (count-only/`size=1` aggregation queries + the dedicated
DLQ scan, never the grid-search plan) is untouched** — the score consumes the aggregation's
output, never adds a leg to it.

```
A(c) = F(c) · R(c) · M(c) · S(c)

F(c) = log2(1 + arrivals_28d(c))          frequency — positive occurrence-total deltas,
                                          28d trailing, from incident_occurrence;
                                          1 (neutral) when the window was WHOLLY UNTRUSTED
R(c) = 2^(−age(lastSeen(c)) / τ)          recency — τ default 24h = the existing
                                          quiet-window constant (C6)
M(c) = clamp(medMTTR(c) / medMTTR(fleet), 0.5, 2)
                                          historic cost — closed-episode durations only;
                                          < 3 closed episodes ⇒ 1 (neutral) + "no history"
S(c) = 1 − p_heal(c), floored at 0.25     self-heal demotion — R2 statistic (§4.2);
                                          null/insufficient ⇒ 1 (neutral)
```

Tie-break: live `total DESC`, then `signatureHash ASC` — the R-SEM-23 deterministic total
order, and the guarantee that **with no history the ordering degrades to exactly today's
count-only ordering** (proven on the pilot data, §5.5 — this is a feature: neutral factors
can never make the landing worse than today).

Rules:
- **Ordering only — never hides.** Every live card renders; acknowledged groups keep their
  labeled collapse + auto-resurface semantics untouched; the score does not touch the
  Acknowledged section's membership, only the sort within the live section.
- The S factor consumes the R2 badge's **stabilized** (hysteresis-applied) rate, never a raw
  per-cycle rate — ordering must not flap when the badge doesn't (the DMKD temporal-stability
  rule #347 owns, inherited here by construction). That hysteresis pattern
  (RETRYING-RISK-LANE.md §4.2) is itself grounded in ISA-18.2's own asymmetric flood
  onset/exit thresholds, not only the DMKD result — a stronger citation for the same
  enter/exit design (Roohi & Izadi 2023, §2 eq. 2,
  [10.61186/joc.17.2.113](https://doi.org/10.61186/joc.17.2.113); Beebe et al. 2013,
  [10.1002/prs.11539](https://doi.org/10.1002/prs.11539)).
- The 0.25 floor on `S` means a reliably-self-healing class is demoted at most 4×, never
  zeroed — a mass self-heal class stays visible (same doctrine as never-hide).
- Score factors ship in the DTO next to the card (`attention: {score, factors, rationale}`)
  so the UI renders the one-sentence rationale (below) with real numbers, not vibes.

**Correction (post-ship, adversarial review 2026-08-04 — §13 F2/F3): `F` had two ways to read
0 when the truth was "unknown" or "the whole class just arrived".**

- **Untrusted ≠ zero.** `IncidentLedgerService.isTruncated` marks a group truncated when ANY
  engine it touches hit the failure-lane scan cap, so on an engine PERMANENTLY at the cap every
  group it contributes is truncated in EVERY bucket, every delta is discarded, `arrivals = 0`
  and `F = log2(1) = 0`. `F` is a FACTOR, so that zeroed `A(c)` outright whatever R, M and S
  said. Truncation correlates with SIZE, so enabling the flag on any fleet with a capped engine
  **systematically demoted the biggest classes** — a 4 000-member class freshly seen on a capped
  engine scored `A = 0`, below a one-member class with one arrival at `A = 1.0` — and nothing
  signalled it (`insufficientHistory` only ever tracked M and S). The arrivals aggregate now
  returns sample COUNTS beside the sum (`observedSamples`, `trustedSamples`); a window with
  samples but no TRUSTED one reads **`F = 1` (the multiplicative identity, §4.1's own degradation
  rule)**, and `factors.arrivalsUnknown` + `factors.discardedArrivalSamples` carry the fact onto
  the wire so the tooltip can say "arrival volume unknown" instead of implying a measured zero.
  Both untrust reasons — truncation (R-SEM-12) and blindness (`cycle_complete = false`, #302) —
  feed the same signal. **A class with NO in-window row at all keeps `F = 0`**, deliberately:
  that case is fleet-uniform, collapses every score to 0, and is exactly the count-only tie-break
  the neutrality guarantee (§5.5) rests on.
- **A class's BIRTH is an arrival.** `LAG(total)` is NULL for the window's first row and that row
  was filtered out, so an incident's FIRST EVER occurrence row — which *is* the arrival of its
  entire population — could never be counted. A bad deploy breaking 5 000 instances at once
  appeared at `total = 5000`, stayed flat, and scored `arrivals = 0 ⇒ F = 0 ⇒ A = 0`, permanently
  below a class that gained one member. `0 → 5000` in one bucket is the LARGEST possible arrival
  event. The aggregate now joins `incident.first_seen` and seeds the baseline at 0 **for the
  incident's own first row and only there**. This does not weaken "growth is not size" (§1): a
  window that merely STARTS mid-life still finds `LAG` NULL on its first row and still discards
  it, so a standing population is never re-banked as fresh growth, and
  `arrivalsAreTheGrowthSignalNotTheSizeSignal` passes unchanged.

### 4.1a Burst-aware frequency (amendment #365 — DESIGN LOCKED, build slice pending)

**The gap this closes.** `F` is a 28-day *volume* measure and `R` only sees `lastSeen` age, so
two classes both "last seen now" with 100 arrivals score identically whether those arrivals
trickled over four weeks or landed in the last ten minutes — yet the second is exactly the
flood shape the literature says demands attention first ("peak rates are responsible for
operators missing critical alarms and average rates are not" — Beebe et al. 2013,
10.1002/prs.11539; ISA-18.2's flood definition is a 10-minute *peak window*, §0). Burstiness
is invisible to the shipped ordering. The amendment makes `F` peak-aware by a **windowed
decomposition** — not a bolt-on term — of the arrivals it already counts:

```
F(c) = log2(1 + arrivals_28d(c))                          when NOT flooding(c) — UNCHANGED
F(c) = log2(1 + arrivals_28d(c) + (γ−1) · burst_W(c))     when flooding(c)
     ≡ log2(1 + outside_W(c) + γ · burst_W(c))            since arrivals_28d = outside_W + burst_W

burst_W(c)  = positive TRUSTED deltas over (asOf−W, asOf]     — same discipline as arrivals_28d
prior_W(c)  = positive TRUSTED deltas over (asOf−2W, asOf−W]  — the gate's hold input, only
flooding(c) = burst_W ≥ onset  OR  (burst_W ≥ exit AND prior_W ≥ onset)
```

Defaults (new keys under `inspector.triage.attention.*`, consulted only when the master flag
is on): `burst-window` W = **PT10M** — ISA-18.2's own flood window; `burst-onset` = **10** and
`burst-exit` = **5** — ISA-18.2's asymmetric flood onset/end verbatim, so the hysteresis is
the standard's, not our invention (the #347 dwell doctrine independently confirmed at the
source, §0); `burst-weight` γ = **8** — one flood-window arrival weighs eight trickled ones.
γ's rationale (panel fix — stated, not implied): the operative quantity is the BOUNDED
inflation ceiling `1 + log2(γ)/log2(1 + onset)` below, and γ = 8 (3 bits) puts that ceiling
at ≈ 1.87× — deliberately the same order as the M factor's 2× clamp, so no single factor can
dominate the others' full range. It cannot be data-fitted today (the pilot has zero recorded
floods, §14.4); it is a knob, not a law, and is re-examined at §7 gate time with real flood
data like every other calibration.

**The five named invariants, preserved:**

- **§5.5 neutrality guarantee.** No history ⇒ `burst_W = 0` ⇒ not flooding ⇒ `F = log2(1+0)
  = 0`, and below onset `F` is **byte-identical to the shipped formula** — the amendment is
  provably inert outside flood conditions, so the no-history degradation to exactly count-only
  ordering (and `AttentionOrderingNeutralityTest`) holds unchanged. Measured: synthetic
  scenario (d), §14.4 — every empty-history score 0.0, order == count-only.
- **§13 F2 semantics.** The burst bins inherit the trusted-samples discipline verbatim — a
  delta counts only when BOTH endpoints were fully observed; both untrust reasons (R-SEM-12
  truncation AND #302 blind cycles) discard identically. A wholly-untrusted 28d window still
  reads `F = 1` + `arrivalsUnknown` (the burst bin is a subset of that window, so the gate
  cannot fire either — no path to a fake zero OR a fake flood). New sub-case: a burst bin
  that has samples but no TRUSTED one forces the gate OFF, leaves `F` at the shipped value,
  and sets `factors.burstUnknown` — the tooltip says "recent arrival rate unknown", never
  "not spiking". Because the burst term can only ever *raise* `F` behind a gate, an unknown
  bin can only suppress a promotion — it can never demote, which is strictly safer than the
  original F2 defect class (where unknown zeroed the score). An EMPTY bin (no row at all,
  the sampler was down) reads `burst_W = 0`, gate off — a fleet-uniform degradation to the
  shipped formula, same shape as F2's "no in-window row keeps F = 0" rule.
- **§13 F3 semantics — no double-banking, by construction.** This is why the amendment is a
  decomposition and not a multiplier. `arrivals_28d = outside_W + burst_W` is a PARTITION of
  the very deltas F already counts: under flooding, `F = log2(1 + outside_W + γ·burst_W)`
  counts every arrival **exactly once**, at weight 1 or weight γ, depending on which bin its
  bucket falls in. The birth-seeded delta (the F3 fix) is one of those deltas: a class born
  inside W counts its whole population once at weight γ — and may legitimately trigger the
  gate, because a mass birth IS the largest flood (`0 → 5000` in one bucket: `F =
  log2(1 + γ·5000) = 15.29`, vs shipped 12.29 — synthetic scenario (b), §14.4). A separate
  multiplicative factor `B` on top of `F` was **rejected as a shape**: `F` already contains
  the birth arrival, so any term that re-reads the same bucket re-banks it (`12.29 × clamp`),
  and keeping it honest would need a hand-maintained exclusion filter that a future edit can
  silently break. The partition identity cannot double-bank without breaking arithmetic. The
  mid-life guard is untouched: a window merely *starting* mid-life still discards its
  `LAG`-less first row, so a standing population is never re-banked as growth —
  `arrivalsAreTheGrowthSignalNotTheSizeSignal` passes unchanged.
- **Zero new engine calls.** The two bins are four more FILTERED columns on the SAME single
  native `arrivalsSince` aggregate over `incident_occurrence` (§14.6) — one pass, zero
  additional statements, BFF's own Postgres. The Stage 0 iron rule (count-only/`size=1`
  aggregation + the dedicated DLQ scan, never the grid-search plan) is untouched: nothing is
  added to the engine aggregation, ever.
- **`inspector.triage.attention-ordering` stays default-off.** No new flag; the burst knobs
  are dead configuration until the master flag is opted into, and the §7 gate (still NOT MET,
  0 of 5 axes) governs that flip exactly as before.

**Non-goals honored:** `R`, `M`, `S`, the R-SEM-23 tie-break (`total DESC → signatureHash
ASC`), ack/resurface semantics and the flag default are not touched — the amendment changes
`F` and only `F`.

**Bounded influence, no clamp knob needed.** The gate requires `burst_W ≥ onset` (or the
hold), so `log2(1 + arrivals_28d) ≥ log2(1 + onset) ≈ 3.46` whenever the boost applies, and
the boost adds at most `log2(γ) = 3` bits: the multiplicative inflation of `F` is
self-bounded at `1 + log2(γ)/log2(1 + onset) ≈ 1.87×`, shrinking as volume grows (flood-100:
9.65 vs 6.66 ≈ 1.45×). Contrast M's explicit clamp: here the log does the clamping.

**Hysteresis, stated honestly.** The gate is a stateless two-bin Schmitt trigger: entry needs
a genuine onset in the current window; hold needs `burst_W ≥ exit` while the onset sits in
the PRIOR window. `prior_W` must reach onset ALONE — summing the bins would open a back-door
entry (6+6 across 20 min is not a 10-minute flood; proven in scenario (a), §14.4). Known
approximation vs a stateful trigger: a flood lingering in `[exit, onset)` for longer than W
without re-reaching onset drops the gate one window early. The 5-minute model TTL adds the
same dwell it adds to every factor, and W > model-TTL, so a detected flood both surfaces
within one TTL and survives at least one rebuild.

**Rationale & wire (§4.3 extension).** When flooding, the per-card sentence gains the clause
**"spiking: 40 in the last 10 min"** — the absolute count and window, per #365 ("beats a bare
ratio"); when `burstUnknown`, the clause is "recent arrival rate unknown". Wire fields in
§14.6.

### 4.2 Interface consumed from track R2 (#347 design / #351 API) — dependency, not duplication
The score consumes, per `(signatureHash, algoVersion)`:

```
{ selfHealRate: number | null,   // stabilized, hysteresis-applied — the badge's value
  sampleSize:   number,
  insufficient: boolean,         // R2's own minimum-sample honesty rail
  asOf:         instant }
```

`insufficient=true` or `null` ⇒ `S = 1` (neutral). This doc does not define how the rate is
computed, stabilized, or floored — that is #347's design surface; #353 consumes whatever
shape #351 ships, adapting the join only.

### 4.3 Rationale — one tooltip sentence (hard requirement, issue #348)
> "Ordered by the expected cost of waiting: freshness and growth, weighted by this class's
> historic time-to-resolve — proven self-healers rank lower, and nothing is hidden."

(Tightened per panel: one sentence, glanceable.) Per-card variant substitutes the numbers:
*"21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal
history."*

## 5. MEASURED BASELINE — pilot-ledger extraction & ordering simulation (auditable)

**Method.** 2026-08-04 ≈ 07:05–07:10 Z, against the live demo `https://pi.naumann.cloud`,
authenticated as dev-ladder user `viewer` (HTTP Basic, VIEWER floor), **REST only** (no DB
access; `docker exec` into the demo Postgres is out of bounds and was not attempted).
Endpoints: `GET /api/incidents`, `GET /api/incidents/{1..5}?window=720` (episodes + 30-day
occurrence series), `GET /api/triage`, `GET /api/engines`, `GET /api/audit?size=100`
(returned 64 rows = the whole log), `GET /api/triage/trends`. Analysis: occurrence-series
delta scan + audit-timestamp cross-matching (scripted, reproducible from the responses).

### 5.1 Ledger volume & span (MEASURED)
- **5 incidents** total; **2 current-generation** (algo v2: ids 4, 5), 3 archived (algo v1:
  ids 1–3, orphaned by the 2026-07-20 v2 bump, all `quiet`). All 5 `state=OPEN` —
  **0 RESOLVED, 0 REGRESSED, 0 reopens, ever**.
- **5 episodes, all still live** (`endedAt` null) → **closed episodes = 0 → MTTR
  observations = 0**.
- Ledger first sighting 2026-07-19T07:08:47Z (whole-ledger span 16.0 d at extraction);
  current-generation span 2026-07-20T12:38:02Z → 14.8 d.
- Occurrence series: 60 s buckets; incidents 4/5 carry 21 229 points each vs ≈ 21 268
  expected minute-buckets → **99.8 % sampler coverage** (~39 min of gaps).

**Correction (amendment round #365, 2026-08-04 — the span and coverage above lacked a trust
qualifier, and that presentation was misleading).** These are RECORDED-series figures.
Re-extraction (§14.2) found **99.2 % of the recorded rows BLIND** (`cycle_complete = false`;
21 741 of 21 909): the registry carried a declared-but-unreachable `engine-7` slot from the
current generation's birth until 2026-08-04T15:39 Z (when a real 7.1.0 engine filled it),
and V21's fail-closed backfill marks all pre-V21 rows blind. Every §6 estimator DISCARDS
blind deltas by design, so the **trusted** current-generation span — the only span the
estimators can fit on — began at 2026-08-04T15:39 Z and was **168 min** old at
re-extraction. The recorded span (14.8 d at first extraction, 15.2 d at re-extraction)
remains true as a statement about rows on disk; it was wrong to present it as usable
history. §7 G5 is corrected accordingly; zero truncated rows either way.

### 5.2 The two live classes (MEASURED)
| | Incident 4 | Incident 5 |
|---|---|---|
| Class | `java.lang.ArithmeticException` — `${amount % divisor}` (demoFailingPayment + demoFailingRetry, engine-a/b) | `java.net.UnknownHostException` — acmeApiOutage, engine-a/b |
| Live total | 21 (peak 22) | 8, constant |
| Total-changes in 14.8 d | **1** (22→21, 2026-07-22T05:54Z) | **0** |
| RETRYING excursions | **1**: 120 consecutive minute-buckets 2026-07-21T04:16→06:15Z (8 RETRYING / 14 DLQ), returning 8/8 to dead-letter at 06:16 — net drain 0 | 0 ever |

### 5.3 Noise-control event counts (MEASURED)
Acks: **0 ever** (no ack audit rows; both live groups unacknowledged) → resurfaces: 0 →
ack-expiries: 0. Regressions: 0. R-NFR-04 alarm firings: 0 — 24 h trends show executable
and timer lanes flat at 0 on both engines; live `oldestExecutableJobAge=null`,
`overdueTimers=0`. Three engines registered (engine-a/b 6.8.0, engine-7 v7 Boot-layout).

### 5.4 Intervention effectiveness (MEASURED — manual attribution, see honesty note)
Whole audit log = 64 rows (2026-07-08 → 2026-07-27; **no operator action in the last 8
days**): 48 retry-job, 3 bulk:retry-job envelopes, 6 edit-variable, 3 activate, 1
restart-as-new, 3 registry-seed. Within the ledger-observable window (≥ 07-19):

- 2026-07-19 07:11: 2 bulk envelopes + 10 retries → incident 1 drained 23→22 at 07:12 —
  **1 drain / 10 retries**.
- 2026-07-21 04:15:33: bulk retry, 30 items across both engines → the 120-min RETRYING
  excursion above, all returned to DLQ — **0 drains / 30 retries**.
- 2026-07-22 05:54:09 `edit-variable` + 05:54:14 `retry-job` on the SAME instance
  (`4ba82687…`, engine-a — an instance that had survived a plain retry on 07-21) → incident
  4 drained 22→21 in that same minute-bucket — **1 drain / 1 data-fix-then-retry**.
- 2026-07-27: 1 retry + 1 activate → 0 drains.

**Retry-only effectiveness: 1/41 ≈ 2.4 %. Data-fix-then-retry: 1/1.** (n is tiny, but it is
the pilot's entire intervention history — and it is direct empirical support for the
R-SEM-13 demoted-retry doctrine.)

**⚠️ Confounded — do not read this number naively (issue #358, follow-up track).** This
corpus is dominated by seed processes that fail permanently by construction, biasing the
rate toward zero the same way #347 found the self-heal rate unobservable on this corpus.
`docs/reviews/R5-RETRY-EFFECTIVENESS-2026-08.md` partitions the same interventions by
whether the target was a known always-fail fixture or the deployment's one documented
organic class (`acmeApiOutage`) and finds the partitioned organic-only number is **still
not informative** — not because the partition failed, but because `acmeApiOutage` is
itself a deterministic, permanent failure by construction (an RFC 2606 reserved-`.invalid`
host), a second, subtler instance of the identical confound one level down. Full method,
the attribution mechanism that replaces the manual cross-matching below, and the
pre-registered floor: see that report.

**Not exposed over REST (stated per the extraction mandate):** per-class ↔ audit-action
attribution. Audit rows carry `instanceId` but no signature; `payload` is null over the API
(R-AUD-03 minimization), so `relatedBulkJobs` is empty even for the 07-21 class-scoped bulk.
The attribution above is manual cross-matching of audit timestamps/instance ids to
occurrence-series inflections — reproducible but not an API aggregate. No API measures
operator attention (time-to-first-drill etc.). #351 (self-heal statistics) does not exist
yet, so no self-heal aggregate is exposed either.

### 5.5 Ordering simulation — attention score vs count-only (MEASURED result)
Replayed `A(c)` (§4.1) against count-only over all **21 229** minute-buckets of the
current-generation window, factors computed from ledger data available *at each bucket*:
`M = 1` at every bucket (0 closed episodes at all times); `S = 1` (no #351 statistics; even
using the measured evidence as a stand-in, p_heal = 0 for BOTH classes — incident 4's one
excursion returned 8/8 to DLQ, incident 5 never entered RETRYING); `R = 1` (both
continuously live); `F` equal under the no-post-mint-arrivals convention, and ordered
21-vs-8 under the count-initial-burst convention — same order as count-only either way.

**Result: the attention ordering is IDENTICAL to count-only at 21 229 / 21 229 buckets —
0 of 2 top-N positions change, Kendall τ = 1.0.** The 5-row incident list likewise: 0
changes vs today's section + `lastSeen DESC` ordering.

Worked examples (real buckets, factor-by-factor):

**Ex 1 — extraction instant, 2026-08-04T07:05Z.**
| factor | inc 4 (total 21) | inc 5 (total 8) |
|---|---|---|
| F | equal (0 arrivals both, trailing 28 d) | equal |
| R | 1 (live) | 1 (live) |
| M | 1 — no closed episodes | 1 — no closed episodes |
| S | 1 — no R2 stats (measured p_heal = 0 anyway) | 1 — no R2 stats |
| A | tie → tie-break total DESC → **1st** | **2nd** |

Count-only: same order. Every factor that could discriminate is data-starved — this is the
measured *reason* for the null result, not a model defect.

**Ex 2 — mid-excursion, 2026-07-21T04:20Z.** Count-only: 22 vs 8, incident 4 first.
Attention: same tie → same order. The instructive point is the failure mode the four-factor
design *avoids*: a naive "recently touched, demote it" heuristic would have dropped incident
4 below incident 5 while its 30 fresh retries were busy failing — the measured 0/30 drain
proves that demotion would have buried the still-broken costlier class. The score has no
touched-recently factor by design; only *proven* self-healing (R2, minimum-sample-gated)
demotes.

**Honest conclusion:** on the real pilot data the reordering makes **no difference at all**.
The pilot is a near-static seeded demo: 2 concurrent live classes (ordering has no room to
matter), 0 closed episodes (M inert), no R2 statistics (S inert), both classes continuously
live (R inert). This is a legitimate finding (issue #348 point 3 anticipates it): it does
not invalidate the model — it means the model's discriminating terms are exactly the data
the ledger has not yet accumulated, so activation must be gated (§7) and the build ships
flag-off (§9). No benefit claim is made for the pilot as it stands.

**Correction (amendment round #365, 2026-08-04 — the MECHANISM stated above was wrong; the
outcome stands).** The replay result is re-verified (§14.4: 21 909 / 21 909 buckets, τ = 1.0,
0 position changes) but this section — written before the §13 F2 correction existed — says
the classes tie because "F equal (0 arrivals both)". Under the F2 semantics as actually
shipped, on the data as recorded, that is false for 99.2 % of the window: every bucket whose
trailing window holds observed-but-no-TRUSTED samples (the blind prefix, §5.1 correction)
reads `arrivalsUnknown` on BOTH classes ⇒ **F = 1 (the neutral identity), not F = 0** — a
neutral tie falling through to the same count-only tie-break. Genuine zero-arrival `F = 0`
ties occur only inside the trusted era (from 2026-08-04T15:39 Z). Same ordering, same τ,
different — and now correctly documented — reason. The distinction matters beyond pedantry:
the whole fleet rode the F2 degradation rule for two weeks, which is exactly why §7 G5 now
gates on *trusted* span, not recorded span.

### 5.6 What the pilot CAN already calibrate (MEASURED)
Sampler cadence 60 s and 99.8 % series coverage — **corrected #365: bucket coverage said
nothing about trust; 99.2 % of those buckets are blind (§5.1 correction), so "estimation
windows in §6 are trustworthy" was FALSE before 2026-08-04T15:39 Z and is true only for the
trusted era since**;
within-episode count jitter CV ≈ 0 on both classes (the §3.3 estimator currently returns
the floor — a demo artifact, flagged as such, another gate argument); retry-only
`eff ≈ 2.4 %` vs data-fix-then-retry `1/1` (n = 42, one class — a prior, not a per-class
estimate); alarm-event base rate 0/21 d (the §3.4 stay-constant argument).

## 6. Parameter-estimation plan (issue point 3)

All estimators read `incident_episode` + `incident_occurrence` (+ the #351 store) in the
BFF's own Postgres — no engine calls, no new tables. Recomputed on a daily schedule (or on
demand) in #353, cached (Caffeine, same pattern as the triage cache), parameter values
versioned and logged so a tooltip's numbers are reproducible.

| Parameter | Estimator | Fallback while thin |
|---|---|---|
| `arrivals_28d(c)` (F) | sum of positive `total` deltas over 28 d occurrence rows, with the baseline **seeded at 0 for the incident's own first-ever row** (a class's birth IS an arrival; a window merely starting mid-life is not); truncated points (floors) never produce negative-then-positive phantom arrivals — deltas across a truncated boundary are discarded, **and identically across a BLIND boundary** (`cycle_complete = false`, V21: an unreachable engine makes a multi-engine class's total drop and recover, which is an outage, not 900 arrivals). The aggregate returns `observedSamples`/`trustedSamples` alongside the sum so "0 arrivals" and "no trustworthy sample" are distinguishable | 0 when the class has no in-window row; **neutral `F = 1` when it has rows but no trusted sample** |
| `burst_W(c)` / `prior_W(c)` (F, §4.1a — amendment #365, build pending) | positive trusted-delta sums over `(asOf−W, asOf]` and `(asOf−2W, asOf−W]`, four FILTERED columns on the SAME `arrivalsSince` pass — identical trust discipline (truncation + blind boundaries discarded), identical F3 birth seeding (the birth delta lands in whichever bin holds its bucket, once), plus bin-scoped sample counts so a wholly-untrusted bin is distinguishable from a quiet one | gate off + shipped `F` when the bin is untrusted (`burstUnknown`) or empty; the amendment is inert outside flood conditions |
| τ (R) | keep = quiet-window 24 h; re-estimate later from episode inter-arrival distribution | constant 24 h |
| `medMTTR(c)` (M) | median closed-episode `ended_at − started_at`, per class with ≥ 3 closed episodes; fleet median otherwise | neutral 1 |
| `p_heal(c)` (S) | **not estimated here** — consumed from #351 (§4.2) | neutral 1 |
| `eff(c)` (§3) | drains attributable to interventions ÷ intervention count; needs the per-class action join `relatedBulkJobs` already defines (envelope-audit payload) — **blocked on redacted audit-payload mode for single-job verbs**; v1 scopes `eff` to bulk error-class actions only, where the envelope carries the signature | fleet prior 2.4 % (§5.4) |
| resurface threshold `t(c)` (§3.3) | `max(constant_pct, 10 %, k·CV(c))`, `k` fit by counterfactual-ack replay over `incident_occurrence` (no real acks needed to fit; G4 validates before activation) to a false-resurface budget ≤ 1/30 ack-days. **A replay whose smallest candidate fires no resurface is UNFITTABLE**, and a fitted value is floored at the constant — opting in can only ever raise the guard | constant 20 % (today's default) |
| ack-expiry suggestion (§3.2) | class P75 closed-episode duration → nearest preset (nearest-rank: "≥ 75 % of the N recorded episodes resolved within X"; at N = 3 that is the observed maximum) | no suggestion (today's `none`) |

Estimation honesty rails: every derived value carries `sampleSize` + an `insufficient` flag
mirroring R2's doctrine; an insufficient estimate renders as "no history", never as a
number; truncated occurrence rows are floors (R-SEM-12) and never enter jitter/arrival
estimators across their boundaries, and blind occurrence rows (`cycle_complete = false`,
#302/V21 — an engine was unreachable when the row was written) are excluded by the same rule
for the same reason: neither is a level that may be differenced against a real one.
**And discarding is COUNTED, never silent (post-ship correction, §13 F2):** an estimator that
threw away every sample it had must report "unknown" and degrade to its neutral value, not
report the zero that a fully-discarded window arithmetically produces. A silent zero is a
claim the data never made — and, because truncation correlates with class size, it is a claim
that is wrong in a systematically biased direction.

## 7. Data-maturity gate (issue point 3 — numeric, from §5 measurements)

The pilot activation of ANY model-derived behavior (ordering weights beyond neutral,
expiry suggestion, derived resurface threshold) requires ALL of:

| # | Gate | Measured today (2026-08-04) | Met? |
|---|---|---|---|
| G1 | ≥ 20 closed episodes fleet-wide AND ≥ 3 classes with ≥ 3 closed episodes each (M term) | 0 closed episodes, 0 classes | **NO** |
| G2 | ≥ 6 distinct current-generation classes live concurrently at least once in trailing 28 d (ordering has room to matter) | max concurrent = 2 | **NO** |
| G3 | #351 shipped; R2's own sufficiency rail passed for ≥ 25 % of live classes (S term) | not built | **NO** |
| G4 | ≥ 10 completed ack lifecycles (ack → expiry/resurface/un-ack) recorded (C2/C3 calibration) | 0 acks ever | **NO** |
| G5 | ≥ 56 d of **TRUSTED** current-generation ledger span (28 d fit + 28 d holdout; redefined #365 — see correction below) | recorded 14.8 d, but **trusted 0.12 d** (era began 2026-08-04T15:39 Z, §5.1 correction) | **NO** |

**Gate status: NOT MET (0 of 5).**

**Correction (amendment round #365, 2026-08-04): G5's measured value and earliest-satisfaction
date were WRONG — recorded span is not usable span.** The 14.8 d figure, and the "earliest G5
satisfaction ≈ 2026-09-14" previously stated here (birth 2026-07-20 + 56 d), counted rows the
§6 estimators are FORBIDDEN to fit on: 99.2 % of the current-generation series is blind
(§5.1 correction), and a fit-plus-holdout over discarded deltas is a fit over nothing. G5 is
therefore **redefined over the trusted span** — first `cycle_complete = true` row → present,
i.e. the same rows the estimators actually consume. Measured trusted span at re-extraction:
**0.12 d** (168 min). Recomputed earliest satisfaction: 2026-08-04T15:39 Z + 56 d ≈
**2026-09-29** — 15 days later than the false figure. Two definitional notes: (i) a FUTURE
blind interval does not reset the clock (the discipline discards deltas *across* it; trusted
rows on both sides stay fittable) but it thins both the fit and holdout halves, so a long
outage pushes the date out correspondingly; (ii) the same stale "≈ 2026-09-14" appears in
`AttentionScoreService`'s javadoc — a code-comment correction owed to the #365 build slice,
not this docs round.

**The other four axes, audited for the same trust sensitivity (#365):** **G1** (closed
episodes) and **G4** (ack lifecycles) count lifecycle events in the BFF's own store —
resolves and acks are operator actions, not occurrence-delta arithmetic — so they are NOT
trust-gated (both measured 0 regardless, and both still depend on operators actually
resolving/acking, which the pilot's audit tail — no action in 8 days at first extraction —
shows is not yet routine). **G2** (concurrent live classes) reads class PRESENCE, not
deltas; a blind cycle can only UNDER-count it (an unreachable engine's classes drop out of
the aggregation), never over-count — so a G2 pass on blind-heavy data is still a genuine
pass, the conservative direction; measured max 2 either way, unaffected. **G3** is R2's own
sufficiency rail, and R2 already gap-voids any spell whose observed shape contains a blind
sample (its trust discipline is internal, RETRYING-RISK-LANE) — no redefinition needed,
though the blind prefix is also part of why the pilot has almost no judgeable spells. Only
G5 needed redefinition. Until the gate: the score computes with neutral M/S (provably
identical to count-only, §5.5), ships **flag-off** (`inspector.triage.attention-ordering`,
default false), and no constant changes value. The gate is re-measured with the §5 method
(REST-only, reproducible) and its status recorded in the PR that flips the flag.

## 8. Measurement — proving the reordering helps (issue point 4)

Plugs into the EXISTING usability harness (`usability-testing` skill, `usability-run`,
`docs/usability/GOAL-CATALOG.md` format contract + `MISSIONS.md` tester briefs) — no
parallel protocol. **Authored by issue #366 (2026-08-04) as an executable plan — NOT YET
RUN**; §8.8 records that honestly and must not be filled from anything but a real
execution.

**Sequencing (hard precondition):** this run must not execute before issue #365 (a
burst-term amendment to this same attention score, `A(c) = F·R·M·S`) lands. #365 is IN
PROGRESS on another branch at authoring time. Running arm B against a since-superseded
scoring formula would test an ordering the shipped flag never actually serves once #365
merges — whoever schedules this run re-checks #365's status first, and re-derives the
fixture in §8.2 if the formula's shape changed underneath it.

**Correction (2026-08-04, same day as authoring): issue #359 landed on `main` as `85342e1`
(PR #368) while this plan was being written.** §8.2/§8.6 below were originally written
against the pre-#368 reality ("every seed process fails permanently, so `SELF_HEAL_LIKELY`/
`SELF_HEAL_MIXED` are unreachable") — that was TRUE at first authoring and is now
SUPERSEDED, not silently rewritten (§13's own correction convention): #368 shipped
`docker/processes/demo-self-healing.bpmn20.xml` (+ its `-baseline` companion), an opt-in
(`PI_SEED_SELF_HEALING=1`, never on `seed.sh`'s default path) transiently-failing fixture
that makes all four self-heal lanes reachable end-to-end. §8.2/§8.6 are rewritten below to
reflect this.

**The honesty rail that survives the good news unchanged:** the fixture is opt-in
specifically so it counts toward **neither** the R4 grouping-quality corpus **nor**
RETRYING-RISK-LANE.md's own §7.2 production data-maturity gate (R2's counterpart to this
doc's §7) — both gates read real engine/ledger history over REST and have no way to tell a
harness fixture's spells apart from organic pilot ones, so keeping the seed off by default
on any deployment that measures either gate (the demo/pilot) is what protects both gates'
honesty. **Staging this fixture for the M13 A/B run does not move this doc's §7 gate, or
RETRYING-RISK-LANE.md's §7.2 gate, one inch.** The run's verdict (§8.8) is USABILITY
evidence — whether the ordering and the operator note work when the ordering IS live — never
evidence that either gate's real-history thresholds are met. Do not let a green M13 run be
read, cited, or summarized as gate evidence.

### 8.1 Goals under test (catalog entry R-SEM-25, `docs/usability/GOAL-CATALOG.md`)

Three arcs, one staged mission (`docs/usability/MISSIONS.md` M13, "Why is this one
first?"), one shared fixture:

- **/a — the direct benefit measurement** (the goal this section originally sketched,
  now minted in catalog form): a 3am first-time on-call engineer landing on `/` with
  several failure classes on screen must start working on the class that is genuinely
  costliest — not merely the largest — and cite what told them so. Metric:
  time-to-first-relevant-card (§8.5).
- **/b — ordering-rule comprehension**: the same engineer must correctly restate, from the
  `AttentionBadge` tooltip, that card position is not decided by raw count alone.
- **/c — self-heal badge comprehension**: meeting a `SelfHealBadge`, the same engineer
  must correctly restate that its fraction+time is a historic rate over past retrying
  spells, not a live guarantee, and derive the right posture from the lane they actually
  saw.

/b and /c together are **the trained-response-strategy half** the Laberge et al. 2014
finding (DOI 10.1016/j.ergon.2013.11.008, full text read for issue #366) says the display
needs to ship WITH: the traditional list beat the redesigned display ALONE on orienting
score (52.3% vs 45.0%, p = 0.04) and on response time; the redesign's benefit appeared only
paired with a trained response strategy (n = 8 exploratory subsample, α = 0.10), and ~60%
label comprehension is the level their data associates with that benefit disappearing.
`docs/OPERATOR-QUICK-START.md`'s "Reading the attention ranking" note (added alongside
this plan) IS that strategy; /b and /c measure whether it worked, at a **≥80%
correct-restatement pass bar** — deliberately above Laberge's ~60% failure-associated
level — citation-or-nothing scored per the catalog contract (an answer with no on-screen
quote scores `unsupported` regardless of correctness).

### 8.2 Fixture staging (concrete steps)

1. Standard seed: `docker/seed.sh` (idempotent) — the baseline `demoFailingPayment` (large
   volume) and `acmeApiOutage` (small) classes already exist per the standard corpus.
2. Plant ≥2 additional current-generation classes so the fleet carries **≥4 distinct
   classes live concurrently** (mirrors the §7 gate's own G2 shape, but this is a STAGED
   test condition, not a claim the gate is met): deploy-and-fail one more ACME definition
   and one more `demoFailingRetry`-style timer-backed failure under a fresh business-key
   prefix (`uxrun-m13-*`, the same F-G10 sacrificial-tag convention every other mission
   uses), started over REST per `docker/seed.sh`'s own idiom — never an `ACT_*` table
   insert (CLAUDE.md iron rule).
3. Pick ONE freshly-planted class as **the planted costly class**: small member count
   (3-5 instances, so it is never the largest by raw count), started immediately before
   the run — maximizes `R` (recency) and, per §4.1's F3 correction, its own birth counts
   as its whole population's arrival, maximizing `F` too.
4. Give the planted class a real, non-neutral **M (historic cost) factor**: open→resolve
   it **three times** (`min-closed-episodes: 3`, `InspectorProperties`) via the ledger's
   own human lifecycle verb (`POST /api/incidents/{id}/resolve`, reason ≥10 chars per
   R-NFR-06), leaving each incident open for a genuinely longer wall-clock stretch than
   the fleet's other classes before resolving (M is a real elapsed-time ratio — minutes,
   not milliseconds) so its `medMTTR` clamps toward `mttr-clamp-high` (2.0) relative to
   the fleet median. Re-seed the failure over REST between each resolve so a fresh episode
   opens each time.
5. **The canonical "large self-healing class demoted below a small costly one" story is
   now stageable** (superseded — #359/PR #368 shipped the fixture that makes `S`
   non-neutral; see §8's own correction note and §8.6). The concrete recipe, 6.8+ engines
   only (`demoSelfHealing`'s boundary-timer construct is gated the same as the rest of the
   6.8+ fixture set):
   a. **Inflate the class's raw count** — the class that must OUTRANK the planted small
      costly class under naive count-only ordering. Run `PI_SEED_SELF_HEALING=1
      docker/seed.sh` (or POST additional `demoSelfHealingBaseline` instances directly,
      same body seed.sh uses) repeatedly to accumulate a genuinely large standing
      dead-letter count on the shared `selfHealGhost` signature.
   b. **Commit its self-heal lane to `SELF_HEAL_LIKELY` at the REAL, unlowered production
      floor** (`inspector.selfheal.floor`, default 10 — do not lower it for this run; a
      lowered floor tests a threshold the shipped deployment never actually uses). Start
      ≥10 ADDITIONAL `demoSelfHealing` instances one at a time, directly over REST (the
      same body seed.sh uses: `{"processDefinitionKey":"demoSelfHealing","variables":
      [{"name":"healDelay","type":"string","value":"PT20S"}]}`), STAGGERED so each
      instance's own retrying→healed transition is a genuinely distinct, non-overlapping
      spell in the class's fleet-wide occurrence series — `RetrySpellExtractor` reads the
      AGGREGATE series for the signature, so two instances retrying concurrently collapse
      into one spell, not two. Confirm the class's live `retryingCount` has returned to 0
      (one full sampler cycle after the previous instance healed) before starting the
      next. `SELF_HEAL_MIXED` stages the same way with a deliberate minority of instances
      given a `healDelay` LONGER than the retry cascade's own exhaustion time, so they
      dead-letter before healing — a genuine organic escalation, no operator retry, no
      audit row.
   c. **Budget real wall-clock — the timing reality #368's own fix commit measured, not
      nominal-cycle arithmetic**: the async executor's timer-job acquire poll adds
      **~9–10 s to EVERY retry** regardless of the nominal interval, so even a
      short-looking cascade burns real minutes, and a single heal spell is realistically
      **~20–30 s+ of wall-clock, not the ~3 s a naive nominal-cycle calculation would
      suggest**. Add the sampler's own 60 s cadence between spells (§5.6) so the
      occurrence series actually records the zero-bucket that closes one spell before the
      next begins. Ten sequential, non-overlapping spells at the real floor is realistically
      **~15–20+ minutes of dedicated pre-run staging** — schedule it BEFORE tester
      dispatch, as its own staging pass, never live during the mission.
   d. Verify before dispatch: `curl -su viewer:dev http://localhost:8085/api/incidents`
      (or the matching `GET /api/triage` error-group entry) shows the `selfHealGhost`
      class's `selfHeal.lane == "SELF_HEAL_LIKELY"` — don't dispatch the tester against a
      lane the fixture only INTENDED to reach.
6. Confirm at least one live `SelfHealBadge` exists for task /c. Any of the four lanes now
   satisfies this (§8.6); the fixture in step 5 gives `SELF_HEAL_LIKELY` specifically —
   the lane task /c's example copy illustrates — but `INSUFFICIENT_HISTORY` (default, no
   staging needed) or `SELF_HEAL_UNLIKELY` (the `demoSelfHealingBaseline` cohort alone, no
   opt-in fixture required) are legitimate, much cheaper fallbacks if the full step-5
   staging budget isn't available for a given run — the verdict (§8.8) must record which
   lane(s) were actually live either way.
7. Validate staging BEFORE dispatching testers: `curl -su viewer:dev
   http://localhost:8085/api/triage?refresh=true` against arm B's BFF and confirm the
   planted class's `attention.score` ranks it above at least one larger class — if it
   doesn't, adjust step 3/4 (more elapsed OPEN time, or a fresher start) and re-check
   rather than dispatch a mission the fixture can't actually support.

### 8.3 Dev-ladder user & entry point

`viewer` — VIEWER floor covers every M13 task (read-only comprehension probe, no
mutation). Sign-in and navigation exactly per the standard tester protocol
(`.claude/workflows/usability-run.js` `testerPrompt`/`PROTOCOL`): the tester enters at
`/`, never at a destination tab.

### 8.4 Per-arm flag mechanics

The flag is a Spring Boot property (`inspector.triage.attention-ordering`,
`InspectorProperties`), not a runtime toggle — flipping it means starting the arm's BFF
process with a different environment override:

- **Arm A (control, flag off)**: `cd backend && ENGINE_A_PASSWORD=test
  ENGINE_B_PASSWORD=test ENGINE_7_PASSWORD=test mvn spring-boot:run`
  (`docs/usability/RUNBOOK.md` prerequisite #2, unchanged — `attention-ordering` already
  defaults false).
- **Arm B (flag on)**: the same command with `INSPECTOR_TRIAGE_ATTENTION_ORDERING=true`
  exported first (Spring relaxed binding onto `inspector.triage.attention-ordering`).
  Confirm it took before staging: `curl -su viewer:dev
  http://localhost:8085/api/triage | grep -o '"attention"'` must return a hit.
- The two arms run against **separate BFF process lifetimes** — sequential restarts on the
  same dev stack are fine, concurrent is not: M13 is config-staged/exclusive (§ RUN
  PROTOCOL note in `GOAL-CATALOG.md`), and flipping the flag mid-run would silently
  re-order every OTHER mission sharing that BFF.

### 8.5 Metric derivation — time-to-first-relevant-card

`TESTER_SCHEMA` and `testerPrompt()` in `.claude/workflows/usability-run.js` carry the
wiring: `tasks[].timings` (optional, absent for every mission but M13 — the standard
12-mission run's schema validation is unaffected) and an `M13_TIMING_INSTRUCTION` block
appended to the tester protocol only when `mission === 'M13'`:

- Tester instruction: immediately after `browser_navigate('/')` succeeds, call
  `browser_evaluate(() => new Date().toISOString())` and record the result as
  `landingIso`; immediately after the first successful navigation into the planted
  class's detail page (task 1's drill), call it again and record `firstDrillIso`. Both
  ride on M13 task 1's own result as `timings: {landingIso, firstDrillIso}`.
- `time-to-first-relevant-card = firstDrillIso − landingIso`, computed by the evaluator
  (reconciler agent) per tester, per arm.
- This is wall-clock as the AGENT experienced it (its own "thinking" time included) — an
  honest proxy for "how many dead-end interactions before landing on the right one," not a
  claim about human-operator seconds. Report it alongside `interactions` (already
  tracked) and treat a step-count regression the same way nightly stats already do
  (GOAL-CATALOG.md RUN PROTOCOL "Nightly statistics": "a 4-step task becoming 9 steps is a
  regression even while green").

### 8.6 Self-heal lane reachability — how to stage each lane (superseded #359 note below)

**Superseded, kept for provenance (repo correction convention, §13 / RETRYING-RISK-LANE.md
§10 precedent):** this section originally read "`SELF_HEAL_LIKELY`/`SELF_HEAL_MIXED` are
NOT reachable in fixtures until issue #359 lands" — TRUE when first authored (2026-08-04,
before #368 merged), FALSE now. Issue #359 landed the same day as `85342e1`/PR #368. All
four lanes are reachable today; the table below is now "how to stage each," not "what's
blocked."

| Lane | Reachable today? | How |
|---|---|---|
| `INSUFFICIENT_HISTORY` | **yes, no staging** | default state; needs no completed spells at all |
| `SELF_HEAL_UNLIKELY` | **yes, no opt-in fixture needed** | every STANDARD seeded process fails PERMANENTLY by construction — spells complete, none heal |
| `SELF_HEAL_MIXED` | **yes, opt-in fixture** | `PI_SEED_SELF_HEALING=1` (§8.2 step 5) with a mix of healed and organically-escalated `demoSelfHealing` instances (short vs. long `healDelay`) |
| `SELF_HEAL_LIKELY` | **yes, opt-in fixture** | `PI_SEED_SELF_HEALING=1`, ≥10 healed spells at the real production floor (§8.2 step 5) — `SelfHealLikelyLaneIT` proves this exact lane reachable end-to-end against real engine state |

**Mechanism (issue #359, PR #368, `85342e1`):** `docker/processes/demo-self-healing.bpmn20.xml`
is CLOCK-driven — a non-interrupting boundary timer sets `healed=true` in its own,
always-committing transaction (a variable set inside the failing attempt's own transaction
rolls back with it, so a counter incremented across a job's own retries cannot survive —
proven live against flowable-rest 6.8). Its `-baseline` companion shares the same
`selfHealGhost` error signature but fails fast and PERMANENTLY, keeping the class
observable between spells (the ledger skips a zero-total group entirely). **Opt-in only**
— `PI_SEED_SELF_HEALING=1` in `docker/seed.sh`, never on the default path — and, by
design, this run's staging must never be mistaken for moving either data-maturity gate
(§8's own correction note above; §8.2 step 5's own reminder). Gated 6.8+ (the
boundary-timer construct).

Task /c's example copy (RETRYING-RISK-LANE.md §4.1's worked example, "usually self-heals
(12/14, typically ≤ 8 min)") illustrates `SELF_HEAL_LIKELY` specifically — that lane, and
the copy it illustrates, are now stageable and should be the target for arm B whenever the
§8.2 step 5 staging budget (~15–20+ minutes) is available; `INSUFFICIENT_HISTORY`/
`SELF_HEAL_UNLIKELY` remain legitimate, cheaper fallbacks (§8.2 step 6). The verdict
(§8.8) MUST state which lane(s) were actually met that run, never silently claim full
coverage regardless of which lane was reached.

### 8.7 Pass/fail arithmetic (as the executor applies it)

1. **Comprehension gate** (/b + /c combined, arm B only): count correct, citation-grounded
   restatements ÷ total /b+/c tasks attempted across all N ≥ 5 testers. **Pass iff ≥80%.**
   An uncited answer scores `unsupported`, counted as NOT correct (citation-or-nothing),
   regardless of semantic correctness. Arm A's /b+/c tasks are expected to answer
   `blocked-by-environment` (the badge/tooltip render nothing, flag off) — recorded as the
   control observation, excluded from the comprehension denominator, never scored as
   failures.
2. **Benefit gate** (/a, arm A vs arm B): median `time-to-first-relevant-card`(arm B) vs
   median `time-to-first-relevant-card`(arm A) over N ≥ 5 testers per arm. **"Arm B ≥
   arm A" means arm B's median is NOT SLOWER than arm A's** (lower elapsed time is
   better). The existing ship-gate bar for a CLEAR recommend stays: arm B improving by
   **≥25%**, no regression on the existing M1 step-1 overview verdicts, and a stability
   check — the same fixture re-rendered across 3 refreshes keeps an identical order (the
   DMKD stability requirement applied to ordering).
3. **Decision rule (verbatim, as specified for this run): flag-on is recommended only on
   (comprehension pass) AND (arm B ≥ arm A); a worse arm B is a real possible outcome per
   Laberge — it must be recorded honestly if it happens.** Meeting the ≥25% bar
   strengthens a "recommend" verdict but is not itself the gate; failing "arm B ≥ arm A" —
   even by a small margin — fails the gate outright, comprehension notwithstanding. This
   doc's own §5.5 already recorded one null result honestly on the pilot data; a negative
   §8 result gets the identical treatment, not a quiet rerun until it looks better.
4. Neither gate substitutes for §7's own numeric data-maturity gate. This run measures the
   USABILITY of the ordering when it IS live; it does not re-measure whether the pilot's
   ledger has matured enough to flip the flag in production. **Both must pass before
   activation.**
5. **This arithmetic is unchanged by §8.2 step 5/§8.6 now being able to stage a
   non-neutral `S`.** Gates 1-4 above are stated purely in terms of the OBSERVED metrics
   (comprehension pass rate, median time-to-first-relevant-card) — nothing in them assumes
   which of `F`/`R`/`M`/`S` is doing the discriminating work in a given fixture. A
   `SELF_HEAL_LIKELY`-staged run (§8.2 step 5) makes /a's benefit measurement MORE
   representative of the design's own headline claim (a large, count-dominant class
   genuinely demoted because it self-heals) than an `F`/`R`/`M`-only fixture would, but it
   changes nothing about how gates 1-3 are computed or the decision rule in point 3 — the
   arithmetic holds identically either way. It also does not change gate 4's boundary: a
   staged `S`-driven fixture is still usability evidence, never §7/§7.2 gate evidence
   (§8's own correction note).

### 8.8 Verdict — NOT YET RUN

| Arm | N testers | Median time-to-first-relevant-card | Comprehension pass rate (/b+/c) | Stability check | Verdict |
|---|---|---|---|---|---|
| A (flag off, control) | — | — | n/a (badge absent) | — | **NOT YET RUN** |
| B (flag on) | — | — | — | — | **NOT YET RUN** |

**Decision (per §8.7's rule): NOT YET RUN — no recommendation until this table is filled
from a real execution.** Self-heal lanes actually exercised: **NOT YET RUN**. Do not
populate this table from anything other than a real `usability-run` execution against a
live dev stack; a fabricated or extrapolated verdict here is exactly the failure mode this
doc's own §5/§13 correction history exists to prevent.

## 9. Doctrine compliance & non-goals

- **Stage 0 iron rule verbatim**: aggregations stay count-only/`size=1` + the dedicated DLQ
  scan, never the grid-search plan; the score is a pure DB-side join over existing outputs —
  zero new engine calls (the INCIDENT-LEDGER §9 posture).
- **R-BAU-01 untouched**: acknowledged-collapse (labeled, never hidden), the three
  resurface triggers, and un-ack semantics are unchanged; §3.3 only re-derives the
  threshold *value*, behind the same config key.
- **Ordering only, never hides**: no card is filtered, no section membership changes.
- **#106 stays untouched** (issue non-goal): this track orders and expires attention only;
  it prescribes no interventions and changes nothing about remediation playbooks.
- **Episode-shape matching (scoped, parked — do not port this algorithm)**: if the incident
  ledger ever grows a "this spike looks like the 07-14 payment-gateway outage" feature, it is
  **not** a Smith-Waterman/sequence-alignment problem. The two flood-matching papers score
  alarm-*type* identity across thousands of distinct tags (Lai, Yang, Chen, *Online pattern
  matching and prediction of incoming alarm floods*, J. Process Control 56, 2017,
  [10.1016/j.jprocont.2017.01.003](https://doi.org/10.1016/j.jprocont.2017.01.003); Parvez,
  Hu, Chen, *Real-time pattern matching and ranking for early prediction of industrial alarm
  floods*, Control Engineering Practice, 2022,
  [10.1016/j.conengprac.2021.105004](https://doi.org/10.1016/j.conengprac.2021.105004)) —
  that mechanism **degenerates on a single error class's count curve**; the actual problem is
  DTW/cross-correlation over a count series, not sequence alignment. What *would* transfer if
  this is ever built: Gaussian time-weighting, incremental append-only computation, staged
  candidate pruning, and FDR/MDR < 5 % as the quality bar (Lai 2017). Follow-on refs the
  literature review flags as central, not yet held in this repo: Alinezhad, Roohi & Chen, *A
  review of alarm root cause analysis*, Chemical Engineering Research & Design, 2022 (DOI
  unresolved at time of writing — not cited here rather than guessed); Zhou, *Advanced
  Methods for Alarm Monitoring and Alarm Flood Analysis*, University of Alberta thesis, 2021
  (open access, no DOI).
- **No notification channels** (none exist; out of scope).
- **Truncation honesty (R-SEM-12)**: estimators treat truncated rows as floors; a card
  whose score inputs were truncated carries the same badge doctrine as its counts.
- **Blind-cycle honesty (#302)**: a row written while any registry engine was unreachable is
  marked `cycle_complete = false` and is never differenced against — an outage's
  drop-and-recover is not arrival volume. Same lane as truncation, same discard rule.
- **Explainability**: rationale is the one-sentence tooltip (§4.3) with per-card numbers —
  a score no tooltip can explain is a rejected design by construction.
- Spec-sync: this doc introduces no behavior change; SPECIFICATION/ARCHITECTURE/
  IMPLEMENTATION-PLAN deltas land with the build slices (#353/#354) per their DoD, citing
  this doc as the locked design.

## 10. Panel review (repo convention — two independent seats, honest ledger)

| Seat | Model | Verdict | Findings & disposition |
|---|---|---|---|
| Architecture/data | Gemini `gemini-2.5-flash` (2026-08-04; `gemini-2.5-pro` was quota-blocked 429 — same tier-fallback precedent as INCIDENT-LEDGER §0) | **APPROVE-WITH-CHANGES** | **BLOCKER (adopted)**: the §3.3 `k` estimator was undefined at 0 real acks ("ack-days" had no data) → re-specified as counterfactual-ack replay over `incident_occurrence` (fit needs no real acks), with G4 real-lifecycle validation before activation. **MAJOR (adopted)**: `eff(c)`'s weak per-class discriminatory power (single-verb attribution blocked by redacted payloads) was implicit → §3.1 now states explicitly that `eff` is NOT a v1 score factor and why. **MINOR (adopted)**: tooltip sentence tightened (§4.3). |
| Product/ops | GitHub Models (via the `copilot` MCP) | **SEAT UNAVAILABLE** | The service answered HTTP **410 Gone** at the service level (catalog AND inference endpoints, retried, 2026-08-04) — not a per-model or quota error. Per the standing rule (no unauthorized substitute reviewers, no self-grading in a seat's place) the seat was NOT filled by another model. **The second seat is owed**: it must be taken before or at the design-lock PR, and its findings folded in before the doc's status moves past DESIGN. |

Exact review exchanges are preserved in the implementation session transcript; the gate
status in §7 and the measured numbers in §5 were not altered by review — only the three
adopted fixes above.

## 11. Build-slice record — #353 backend (★ SHIPPED)

What landed, and where it deviates from or sharpens this design. **Nothing here changes any
default behavior**: with `inspector.triage.attention-ordering` false (the shipped default) the
whole surface is inert.

- **Score (§4.1)** — `io.inspector.attention.AttentionScoreCalculator`, pure/static:
  `A(c) = F·R·M·S` exactly as specified. Joined at render time in `AttentionScoreService`,
  LAST in the `GET /api/triage` pipeline (scope projection → ack decoration → attention), and
  served per card as `ErrorGroup.attention {score, factors, rationale,
  suggestedAckExpirySeconds?}`. Same block on `IncidentSummary` (list + detail).
- **Zero new engine calls, as designed** — the Stage 0 count-only/`size=1` + dedicated-DLQ-scan
  rule is untouched; nothing was added to the aggregation. **Cost, RE-MEASURED 2026-08-04 after
  the ledger blind-cycle fix and this review round** (`org.hibernate.SQL=DEBUG`, dev BFF, real
  pilot ledger, `GET /api/incidents` with `attention-ordering=true`, 7 scored classes):
  **25 statements cold, 1 warm.** The composition, and why the original one-line claim ("three
  bounded aggregates … cached whole") was only two-thirds of the story:
  - **The attention MODEL is exactly three fleet-wide reads, cached whole for
    `attention.model-ttl` (5 m)** — `incident` rows with `last_seen ≥ now−28 d` (R; window-scoped,
    no longer `findAll()`), ONE native window aggregate over `incident_occurrence` (F — a DB-side
    `SUM(GREATEST(total − LAG(total), 0))` plus its sample counts, rather than differencing ~40 k
    minute-buckets per class in Java, discarding any delta touching a truncated OR blind bucket
    per §6), and closed `incident_episode` durations (M + the §3.2 P75 expiry suggestion). That
    part of the claim held, and still holds.
  - **The `S` factor is NOT part of that model, and is NOT on that cache.** It is consumed from
    R2 per CLASS via `SelfHealStatsService.get`, whose own Caffeine TTL is the SAMPLER BEAT (60 s),
    not 5 minutes; a miss costs **three more bounded reads per class** — the incident row, the
    ≤ 5 000-row `RetryAuditPoint` confound projection, and the ≤ 10 000-row spell-shape occurrence
    read. Measured: `1 (ledger list) + 3 (model) + 7 × 3 (per class) = 25`. All ten of those legs
    are bounded, which is what the base branch's ledger fix changed — before it, the per-class
    occurrence read was the unbounded 90-day window (~129 600 rows per class per call). Within
    both TTLs the marginal cost of the whole decoration is **zero** statements (measured: a second
    call inside 60 s issued exactly one query, the ledger list itself), and with
    `inspector.selfheal.enabled` on the 60 s dwell tick pre-fills the per-class cache anyway.
- **Ordering** — `AttentionOrdering.BY_ATTENTION`: `score DESC → total DESC → signatureHash
  ASC`. The incident LIST keeps its server order (`lastSeen DESC`; the #308 hard cap must drop
  the oldest rows) and its client-derived sections — the score orders within the live sections,
  where they are actually formed, per §2.
- **Adapter to R2 (§4.2), as actually built.** #351 ships no standalone statistics endpoint and
  no single "stabilized rate" field — stats are an embedded `selfHeal` block whose one
  hysteresis-stabilized artifact is the server-dwelled DISPLAYED `lane` (its Wilson bounds are a
  per-read point statistic, and are absent below the floor). The join therefore maps
  **lane → `p_heal`** at the §4.1 band midpoints (LIKELY 0.75, MIXED 0.50, UNLIKELY 0.15;
  `INSUFFICIENT_HISTORY`/absent ⇒ neutral 1) — honoring "consumes the stabilized value, never a
  raw per-cycle rate" literally. `SELF_HEAL_LIKELY` lands exactly on the 0.25 floor, so the
  design's "demoted at most 4×, never zeroed" is arithmetic rather than a separate clamp.
- **`eff(c)` excluded from the v1 score**, per §3.1. Not implemented, not referenced.
- **Rationale (§4.3)** — `AttentionRationale`, server-side, one `·`-separated sentence on one
  line: `21 failing · last seen 2 min ago · typically takes 4 h to resolve · no self-heal
  history.` Sub-floor estimates render "no resolve-time history"/"no self-heal history", never
  a number.
- **Model-derived knobs.** C6 (τ) and the §4.1 clamps/floors are config with the design's
  selected values as defaults (`inspector.triage.attention.*`). C2 (ack-expiry suggestion) is
  computed as the class P75 closed-episode duration and served on the attention block — absent
  below `min-closed-episodes`, which is today's behavior (no suggestion). C3 (resurface
  factor) moved its PROVENANCE to `ResurfaceThresholdEstimator` but **not its value**:
  `derived-resurface-threshold` defaults false per §3.3, so `ack-resurface-threshold-pct` (20)
  stays in force and its per-deployment override works unchanged. **C4 (`regression-min-count`)
  and C5 (R-NFR-04 thresholds) were deliberately NOT touched**, per §3.4.
- **The counterfactual-ack replay (§3.3) is implemented** (`CounterfactualAckReplay`, pure):
  stable segments = maximal runs of non-truncated non-zero buckets; jitter = median per-segment
  CV; `k` fit by grid search to the ≤1-false-resurface-per-30-ack-days budget, smallest
  qualifying `k` winning so an ack can never become a permanent mute. It reads
  `incident_occurrence` ALONE — the adopted panel BLOCKER fix, executable at the pilot's zero
  recorded acks.
- **No Flyway migration.** Pure derive-on-read aggregation over the existing V18 tables, exactly
  as §6 anticipated.
- **Neutrality is proven, not asserted** — `AttentionOrderingNeutralityTest`: flag off ⇒ the
  decorator returns the VERY SAME object, runs zero queries, and serializes byte-for-byte
  identically with no `attention` key; flag ON with an empty ledger ⇒ every score is 0.0 and
  500 randomized corpora land in count-only order, which is §5.5's measured result restated as
  an executable invariant. Ordering-only is proven too (every card survives; an acknowledged
  card keeps its overlay identically).
- **Deferred, NOT built in this slice:** the §8 usability goal/fixture/A-B protocol (that is
  #354's surface plus a `usability-run`), and any re-measurement of §7 — the gate status
  recorded above stands until the PR that flips the flag re-measures it with the §5 method.
  **§8 is now fully AUTHORED (issue #366) but still NOT EXECUTED** — the plan, fixture
  recipe, comprehension probe (catalog R-SEM-25, mission M13), and pass/fail arithmetic are
  specified; §8.8's verdict table stays NOT YET RUN until a real A/B execution fills it.

## 12. Build-slice record — #354 frontend (★ SHIPPED)

What landed, and where a semantic conflict on the `research/phase-2-integration` integration
base was reconciled deliberately.

- **Stage 0 (`ErrorGroupCard.tsx`) does no client-side ordering at all.** The backend already
  reorders `TriageDashboardResponse#errorGroups` itself (`AttentionScoreService#decorate` →
  `AttentionOrdering.order`, §11); the existing `ErrorGroupSections`/`splitAcknowledged` split
  was already order-preserving over its input array, so rendering the server's order required no
  new client sort — only the tooltip (below) needed building.
- **The conflict, on the Incident Ledger.** ALARM-COST-MODEL.md §11 says the incident LIST keeps
  its server order (`lastSeen DESC`) and the score orders WITHIN the client-derived sections —
  and those sections are exactly what #352 already forms in `incidents/sections.ts`, sorted by a
  CLIENT-side `compareSelfHealRisk` (RETRYING-RISK-LANE.md §10). The two builds integrated
  textually without conflict (different lines of the same file, different commits) but were
  semantically incompatible: `A(c) = F·R·M·S`'s `S` factor is ALREADY the self-heal signal
  (§11's `lane → p_heal` band map), so layering `compareSelfHealRisk` on top would double-count
  self-heal and — worse — silently override the server's considered order with a client-only
  re-derivation, exactly what §3.1 says the design exists to prevent ("the server ordering must
  win").
- **Resolution** — `incidents/attention.ts#compareIncidentOrder`, used by
  `sections.ts#bucketIncidents` in place of the bare `compareSelfHealRisk` on the REGRESSED/OPEN/
  QUIET sections: ranks by the server `attention.score` (mirroring the backend's own `score DESC
  → total DESC → signatureHash ASC` tie-break, `AttentionOrdering.BY_ATTENTION`) whenever BOTH
  sides of a comparison carry one; falls back to EXACTLY #352's original `compareSelfHealRisk`
  otherwise. Since the flag ships off (§7/§9) and `attention` is therefore absent on every
  response today, this is a no-behavior-change for #352 in practice — proven by
  `sections.test.ts` still asserting the identical self-heal-lane order on that path, plus new
  coverage of the attention-present path (which deliberately orders the OPPOSITE way the lane
  alone would, to prove the score wins outright rather than blending) and a never-hide assertion
  across every bucket. `compareSelfHealRisk` and the self-heal badge rendering (`SelfHealBadge`)
  are otherwise UNTOUCHED — only the ordering role is superseded, per the issue's explicit
  instruction to preserve badge rendering.
- **Rationale tooltip** — `components/AttentionBadge.tsx`, a visible `"ranked by attention"` chip
  shared by `ErrorGroupCard` (Stage 0) and `IncidentCard`/`IncidentDetail` (Incident Ledger),
  since both `ErrorGroup` and `IncidentSummary` carry the same optional `attention` block. Renders
  NOTHING when `attention` is absent (the shipped, flag-off, expected-today case) — no fabricated
  "why". Its `title` tooltip (this codebase's glossary convention — no `/glossary` route) joins a
  FIXED constant sentence (§4.3's generic explanation of what the ordering means) with the
  SERVER's own one-sentence per-card rationale (`attention.rationale`), rendered VERBATIM —
  never recomposed from `factors` client-side, per the issue's explicit rule.
- **No ordering toggle built.** §3.1/§11 specify a single server-computed order behind one
  deployment-wide flag, never an operator-facing attention-vs-count choice; §8's A/B protocol is
  a `usability-run` measurement harness (arm A/B are test conditions), not a shipped UI control.
  The issue is explicit that a toggle is built ONLY if the design specifies one — it does not.
- **New generated-type usage, no regeneration.** `frontend/src/api/model.ts` gained
  `AttentionScore`/`AttentionFactors` aliases over the schema #353 already committed
  (`frontend/src/api/schema.d.ts`) — `npm run gen:api` was NOT re-run (not needed; the schema
  already carried these types).
- **Deferred, NOT built in this slice:** the §8 usability goal/fixture/A-B protocol and any
  re-measurement of §7 remain open (unchanged from §11's own deferral) — #354 is the ordering +
  tooltip UI only, not the usability-harness proof of benefit. **§8 is now fully AUTHORED
  (issue #366)** — see §11's own note; still not executed.

## 13. Correction round — adversarial review of the shipped #353/#354 code (★ LANDED)

An adversarial review of the code #353/#354 put on `main` confirmed seven defects by execution.
All seven are fixed on `fix/attention-scoring-correctness`; the sections above carry the
per-claim `Correction (post-ship)` notes (the RETRYING-RISK-LANE §10 precedent — a false claim
is corrected in place and *named as having been false*, never silently rewritten). Behaviour
with `inspector.triage.attention-ordering` OFF is unchanged and still provably inert.

| # | Defect | Severity | Fix | Failing-before proof |
|---|---|---|---|---|
| F1 | `compareIncidentOrder` picked its rule PER PAIR (attention path only when BOTH sides carried `attention`), which is non-transitive and admits a strict cycle. V8 does not throw on a broken comparator, so it was a SILENT garbage sort of the REGRESSED/OPEN/QUIET sections. Reachable in production: `AttentionScoreService.forClass` catches `RuntimeException` per class → `null` → `@JsonInclude(NON_NULL)` omits the block, so one poisoned row or a mid-page model-TTL expiry serves a mixed array | **HIGH** | The rule is now a property of the ARRAY: `incidentOrderComparator(rows)` decides ONCE (`rows.every(r => r.attention !== undefined)`) and `bucketIncidents` applies that one comparator to all three live sections. Both branches are lexicographic total orders | The reviewer's exact triple, on the shipped code: `cmp(A,C) = −4`, `cmp(C,B) = −1`, `cmp(B,A) = −1` (i.e. `A < C < B < A`) and **3 distinct orderings across the 6 input permutations**. `attention.test.ts` now sorts all 6 and asserts ONE |
| F2 | Scan-cap truncation zeroed `arrivals` for exactly the largest classes. `isTruncated` marks a group truncated if ANY engine it touches capped, so a permanently-capped engine discarded every delta in every bucket ⇒ `arrivals = 0` ⇒ `F = 0` ⇒ `A = 0` regardless of R/M/S. Nothing signalled it | **HIGH** | `arrivalsSince` returns `observedSamples`/`trustedSamples`; a wholly untrusted window degrades `F` to the neutral **1**, and `factors.arrivalsUnknown` / `discardedArrivalSamples` + a rationale clause say so. Covers BOTH untrust reasons (truncation and #302 blindness) | `LedgerNativeQueriesIT` ×2 (`aWindowWhoseEverySampleWasTruncated…`, `…WasBlind…`) error on the base SQL (2 columns); `AttentionScoreCalculatorTest.aWhollyUntrustedArrivalWindowReadsNeutralRatherThanZeroingTheWholeScore` + `theBigTruncatedClassNoLongerSortsBelowTheOneMemberClassWithOneArrival` |
| F3 | A class's initial population was structurally invisible — `LAG` is NULL for the first row and it was filtered out, so an incident's first-ever occurrence row (the arrival of its whole population) could never be counted. `0 → 5000` in one bucket scored 0 arrivals, forever | **HIGH** | Join `incident.first_seen`; seed the baseline at 0 for the incident's OWN first row only (`sampled_at <= first_seen` selects exactly it, since the row is written at the bucket floor of `first_seen` and every later row is a strictly later bucket). A window starting mid-life is unchanged | `LedgerNativeQueriesIT.aClassesFirstEverBucketCountsItsWholePopulationAsArriving` (0 vs 5 000 on the base) + its guard `aWindowThatMerelyStartsMidLifeDoesNotCountTheStandingPopulationAsArrivals` |
| F4 | `derived-resurface-threshold: true` silently HALVED the threshold (20 % → 10 %) on the measured pilot state, via a fit satisfied by having no data | **CONFIRMED** | Vacuous fits are unfittable → constant; a fitted value is floored at the constant. §3.3 correction note | `ResurfaceThresholdEstimatorTest.aZeroJitterClassKeepsTheConstantInsteadOfSILENTLYHalvingIt` (base returns 10) and `aFittedValueBelowTodaysConstantIsFlooredAtTheConstantNeverAppliedAsIs` (base returns 140 against a 200 constant) |
| F5 | `growthHeld` truncated a censored settle window instead of discarding the sample, so a resurface on the segment's LAST index ALWAYS judged genuine — under-counting false resurfaces one way | **CONFIRMED** | An ack whose settle window would run past the segment end is skipped entirely (neither side of the budget). §3.3 correction note | `CounterfactualAckReplayTest.anAckWhoseSettleWindowRunsPastTheSegmentEndIsDroppedInsteadOfJudgedGenuine` — on the base, `[[100, 130]]` at 20 % accrued `ackDays = 6.94e-4` and banked the resurface as genuine |
| F6 | Nearest-rank "P75" at the `min-closed-episodes = 3` floor IS the maximum, while the copy said "episodes of this class usually resolve within X" | LOW | **Copy fixed, statistic kept** — see §3.2's correction note for the full reasoning (safe direction, shared estimator, no invented precision) | Doc/javadoc change only; `theAckExpirySuggestionIsTheClassP75ClosedEpisodeDuration` unchanged |
| F7 | The detail sparkline rendered a blind-cycle dip UNMARKED — `IncidentDetail.OccurrencePoint` carried `truncated` but not `cycleComplete` | honesty gap | `cycleComplete` added to the DTO (`npm run gen:api` re-run, diff committed); `timeline.ts` derives `blind` (an ABSENT field fails toward blind, mirroring V21's fail-closed backfill) and `IncidentTimeline` draws a distinct SQUARE marker + its own legend line — shape, never colour alone (SPEC §10a) | `IncidentTimeline.test.tsx` (3 cases) + `timeline.test.ts`'s blind case |

**Scope discipline.** No new Flyway migration (V21 is the latest and already carries
`cycle_complete`); no new engine call anywhere; no card hidden (R-BAU-01 ordering-only holds);
`inspector.triage.attention-ordering` still defaults false and `AttentionOrderingNeutralityTest`
still proves the flag-off path returns the very same object with zero queries.

## 14. Amendment round — burst-aware frequency (#365, 2026-08-04, DESIGN)

Formula and invariants in §4.1a; this section is the auditable record: provenance, the
required measurement (§5's method re-run), the synthetic hypothesis proofs, the build-slice
specification, and the panel. **Design only — no production code changes in this round.** The
measurement script is `scripts/replay-burst-attention.py` (REST-only, VIEWER, `--cache`
preserves the exact response bytes a quoted number derives from).

### 14.1 Provenance
Issue #365, minted from the full-text calibration digest (#356 comment 5182803157). Literature
in §0 (amendment paragraph): Beebe et al. 2013 (10.1002/prs.11539) for peaks-not-averages;
ISA-18.2 for the 10-minute flood window and the asymmetric onset-10/end-5 thresholds the
defaults adopt verbatim; EEMUA 191 for the same ceilings as benchmarks. Evidence grades stated
in §0 — the Beebe paper is practitioner-grade (4 uncontrolled sites) and is cited for framing
and consensus ceilings, not causal proof.

### 14.2 Sampler cadence & coverage (MEASURED 2026-08-04 ≈ 18:27–20:40 Z, §5 method)
REST-only against `https://pi.naumann.cloud` as dev-ladder `viewer` (HTTP Basic, no DB
access): `GET /api/incidents`, `GET /api/incidents/{4,5}?window=720`, `GET /api/triage`.
Current-generation series: **21,909 points per class over 21,949 min** (99.81 % bucket
coverage — §5.1's 99.8 % re-verified, not copied). Spacing mode **60 s** (21,869 of 21,908
intervals); **39 gaps > 60 s, max 4.0 min, 80 min total**; **zero truncated rows**.

**New measured fact the §5 round did not surface: 21,741 of 21,909 rows (99.2 %) are BLIND
(`cycle_complete = false`).** Every row before **2026-08-04T15:39 Z** is blind; complete
cycles begin exactly when the declared `engine-7` slot got a real engine (commit 015c9e1) —
before that, a registered-but-unreachable engine made every cycle incomplete, and the V21
fail-closed backfill marks all pre-V21 rows blind by design. The trusted era was therefore
**168 minutes old at extraction**. This is the trust discipline working as specified (#302),
and it is the honest headline of the feasibility note: **on this pilot the binding constraint
on burst measurement is the trust discipline, not the cadence.**

### 14.3 Data-feasibility note — the finest honest burst window (MEASURED)
Bin honesty measured on the full minute grid (a bin is evaluated at model-build time, which
lands anywhere — sample-anchored positions structurally cannot be empty and were not used):

| W | empty bins (whole grid) | untrusted bins (whole series) | untrusted bins (trusted era) |
|---|---|---|---|
| 2 min | 0.009 % | 99.23 % | 0.000 % |
| 5 min | 0.000 % | 99.24 % | 0.000 % |
| 10 min | 0.000 % | 99.24 % | 0.000 % |
| 15 min | 0.000 % | 99.24 % | 0.000 % |

The whole-series untrusted fraction is the blind-prefix artifact above, not a cadence
property — inside the trusted era every candidate window is 0.000 % untrusted. **The finest
honest burst window this sampler supports is 5 minutes**: the max recorded gap is 4.0 min, so
a 5-min bin always contains ≥ 1 sample, while 2-min bins measurably go empty (0.009 % of grid
instants) and a 1–2-sample bin cannot distinguish "quiet" from "blind" robustly. **The
default W is 10 minutes anyway** — it is ISA-18.2's own flood window, it guarantees ≥ 6
samples across the worst recorded gap, and the gap-attribution slop (a delta spanning a gap
banks up to 4 min of arrivals into one bucket, shifting them across a bin edge by at most
that much) stays well under the window. Sub-hour resolution is comfortably supported;
sub-5-minute is not honest on the measured cadence.

### 14.4 Replay & synthetic proofs (MEASURED — the required simulation)
**Pilot replay** (proposed burst-aware `A(c)` vs shipped `A(c)` vs count-only, every recorded
bucket, R = M = S = 1 exactly as §5.5 measured them): **21,909 / 21,909 buckets identical —
Kendall τ = 1.0000, 0 top-N position changes, 0 flood-gate activations.** During the blind
prefix both classes read `arrivalsUnknown` ⇒ `F = 1` (a tie), and ties fall to the count-only
tie-break; in the trusted era arrivals are genuinely 0. **A NULL RESULT, as expected and as
§5.5 already found for the base score**: 2 near-static classes give ordering no room to move.
No benefit claim is made from the pilot. A **birth-trusted counterfactual** (the one
untrusted-birth bucket treated as trusted) exercises the gate on real data: incident 4's
birth (22 ≥ onset) holds the gate for exactly the 10 in-window buckets, incident 5 (8 <
onset) never fires, and the ordering STILL changes at 0 buckets — the gate moves scores, not
this pilot's order.

**Synthetic scenarios** (the hypothesis proof the pilot cannot supply; script §4, exact
numbers):

| Scenario | Shipped F | Proposed F | Verdict |
|---|---|---|---|
| (a) trickle: 100 arrivals / 28 d | 6.66 | 6.66 (gate off) | indistinguishable pair… |
| (a) flood: same 100, all in last 10 min | 6.66 | **9.65** (gate on) | …now ranks the flood first — the #365 gap, closed |
| (a) sub-onset: 9 in W | 6.66 | 6.66 (gate off) | byte-identical below the ISA onset |
| (a) hysteresis: 7 in W, prior 12 / 4 in W, prior 12 | — | gate **on** / gate **off** | ISA asymmetric onset 10 / exit 5 |
| (a) back-door probe: 6 in W + 6 prior | 6.66 | 6.66 (gate off) | 12/20 min is not a 10-min flood — hold leg needs a genuine onset |
| (b) birth flood: 0 → 5000 in one bucket | 12.29 | **15.29** = log2(1 + 8·5000) | counted ONCE at weight γ (partition) — F3, no double-banking; the rejected multiplier shape would have re-banked it |
| (c) wholly-untrusted 28 d window | 1 (`arrivalsUnknown`) | 1 (`arrivalsUnknown`, gate off) | F2 verbatim — neutral, never a fake zero |
| (c) burst-bin-only untrusted | 5.67 | 5.67 (`burstUnknown`, gate off) | an unknown bin suppresses a promotion, never demotes |
| (d) empty-history corpus | all 0.0 | all 0.0 | order == count-only exactly (§5.5 guarantee) |

### 14.5 Build-slice specification (the contract for the #365 build agent)
No re-derivation should be needed; deviations get named here per the §11/§12 precedent.

- **Aggregate** — extend `IncidentOccurrenceRepository.arrivalsSince` (same single pass, new
  params `:burstSince = asOf−W`, `:priorBurstSince = asOf−2W`, half-open bins `(from, to]`):
  four new columns `burst_arrivals`, `prior_burst_arrivals` (both
  `COALESCE(SUM(GREATEST(d.delta, 0)) FILTER (WHERE d.trusted AND <bin predicate>), 0)`) and
  `burst_observed_samples`, `burst_trusted_samples` (`COUNT(*) FILTER` over differenceable
  rows in the current bin; the prior bin needs no honesty counts — it only feeds the gate,
  and an untrusted prior can only fail to hold a promotion). The inner projection additionally
  exposes `o.sampled_at`. Invariant: `burst_arrivals ≤ arrivals` (same filters, narrower
  time) — assert it in the IT.
- **`ClassHistory`** — add `burstArrivals`, `priorBurstArrivals`, `burstUnknown`
  (`burst_observed > 0 AND burst_trusted = 0`), `discardedBurstSamples`.
- **Config** — `InspectorProperties.Attention` + `AttentionConfig`: `burst-window` (PT10M),
  `burst-onset` (10), `burst-exit` (5), `burst-weight` (8.0), with `OrDefault` accessors like
  every sibling knob. Two validation rails at binding: `burst-exit ≤ burst-onset` (an exit
  above onset inverts the Schmitt semantics — refuse, don't reinterpret), and a startup
  WARN when `burst-window ≤ model-ttl` (the §4.1a dwell argument assumes W > TTL; a smaller
  W is legal but loses the a-flood-survives-one-rebuild property).
- **Calculator** — `AttentionScoreCalculator.frequency(...)` grows the gate + decomposition
  exactly as §4.1a; pure/static, no new dependencies.
- **Wire** — `AttentionFactors` + `flooding` (boolean), `burstArrivals` (long),
  `burstWindowSeconds` (long), `burstUnknown` (boolean), `discardedBurstSamples` (long);
  `npm run gen:api` re-run and the diff committed.
- **Rationale** — `AttentionRationale`: flooding ⇒ `spiking: {burstArrivals} in the last
  {W as minutes} min`; `burstUnknown` ⇒ `recent arrival rate unknown`.
- **Test rungs** (the same rungs as the F2/F3 correction round): `LedgerNativeQueriesIT` —
  bin split inside/outside W; birth row inside W counts once in BOTH `arrivals` and
  `burst_arrivals` (containment, exact values); wholly-untrusted bin ⇒ `burst_trusted_samples
  = 0` with `burst_observed_samples > 0`; prior-bin column; mid-life window start still
  discards its first row. Pure-static `AttentionScoreCalculatorTest` — flood-vs-trickle
  ordering; sub-onset byte-identity with the shipped formula; Schmitt entry/hold/exit + the
  back-door probe; `burstUnknown` forces gate off and never lowers F; whole-window unknown
  still reads 1; `AttentionOrderingNeutralityTest` extended for the new factor fields, its
  empty-ledger count-only assertions unchanged.
- **Spec-sync** — this amendment is design-only and owes no doc delta beyond this file;
  the build slice owes: SPECIFICATION §4 (attention-score sentence gains the burst clause),
  the ARCHITECTURE §4 `GET /api/triage` row (factor list + burst-gate summary), and the
  IMPLEMENTATION-PLAN research-track record — per that slice's DoD, the §9 posture.

### 14.6 Panel review (repo convention — two independent seats, honest ledger)

| Seat | Model | Verdict | Findings & disposition |
|---|---|---|---|
| Architecture/data | Gemini `gemini-2.5-flash` (2026-08-04; `gemini-2.5-pro` quota-blocked 429 — the §10 tier-fallback precedent) | **APPROVE** | Reviewed the full §4.1a + §14 text against the five invariants, the Schmitt edge cases, the SQL shape and the wire fields. Explicitly cleared: no double-counting path (partition), no demote/fake-zero path (gate is promote-only; unknown bin suppresses promotion only), neutrality intact, back-door-entry probe correct, W/onset/exit justified by ISA-18.2 + the feasibility measurement, filtered-column SQL shape standard and low-risk. **One MINOR (adopted)**: γ = 8 had no stated rationale — §4.1a now derives it from the bounded-inflation ceiling (≈ 1.87×, matched to the M clamp's 2× order) and flags it un-fittable until real floods exist. |
| Product/ops | GitHub Models (`copilot` MCP) | **SEAT UNAVAILABLE** | The endpoint is permanently gone (HTTP 410, GitHub Models catalog sunset — verified 2026-08-04, not quota). Per the standing rule the seat was NOT filled by an unauthorized substitute and nobody self-graded in its place. **The second seat is owed** before or at the design-lock PR, same as §10. |

Exact review exchange preserved in the amendment session transcript. Author's own adversarial
pass (recorded because it changed the design before review): the first draft's gate hold leg
read `burst_W + prior_W ≥ onset`, which admits a back-door ENTRY (6+6 across 20 min gates as
a flood that never had an ISA onset) — corrected to `prior_W ≥ onset` alone and pinned by the
scenario-(a) back-door probe in §14.4. Two post-review validation rails (exit ≤ onset,
W-vs-TTL WARN) were added to §14.5 on the author's judgment, not panel findings.

## 15. Build-slice record — #365 burst-aware frequency (★ BUILT)

What landed against the §14.5 contract, what the failing-before runs actually printed, and the
two places the build deviated from or sharpened the spec. **Nothing here changes any default
behavior**: `inspector.triage.attention-ordering` still ships false, and below the ISA onset `F`
is byte-identical to the shipped formula, so even with the flag ON the amendment is inert
outside flood conditions.

### 15.1 Failing-before proof (the §13 convention — a wrong value, not "it did not compile")

| Rung | Test | What the BASE produced |
|---|---|---|
| Pure static | flood-100 must read `log2(1 + 8·100) = 9.6457` | `AttentionScoreCalculator.frequency(100, false)` returned **6.6582114827517955** — the trickle value. The base cannot distinguish the two shapes at all (the trickle control passed at the same number, which is the point) |
| Pure static | birth-5000 inside W must read `log2(1 + 8·5000) = 15.2877` | returned **12.288000889707574** |
| Rung 4 (real Postgres) | the bin-split fixture (5 arrivals at 09:00, +10 at 09:10, +3 at 09:25, +10 at 09:35, +12 at 09:40; `asOf` 09:40, W = 10 min) must return 8 columns with `burst_arrivals = 22`, `prior_burst_arrivals = 3` | the shipped SQL returned the 4-column row **`[1, 40, 5, 5]`** — 40 arrivals over 40 minutes, with no column in which the 22 that landed in the last ten could ever appear |

Everything else in the new suites is a guard, and each was written to fail on the base by
construction (the burst columns and `AttentionFactors` fields did not exist).

### 15.2 What landed

- **Aggregate** — `IncidentOccurrenceRepository.arrivalsSince(since, burstSince, priorBurstSince)`:
  the SAME single native pass, four more `FILTER`ed columns, `o.sampled_at` exposed on the inner
  projection. **No Flyway migration** (the bins are computed, not stored) and **zero new engine
  calls** — the Stage 0 count-only/`size=1` + dedicated-DLQ-scan rule is untouched. One statement
  before, one statement after; the bins are anchored on the model-build instant, asserted by
  `AttentionScoreServiceTest.theBurstBinsAreAnchoredOnTheModelBuildInstantOneWindowAndTwoWindowsBack`
  (one captured call, three anchors).
- **No double-banking, arithmetically rather than by convention.** `burst_arrivals` is not a
  second measurement: it is the IDENTICAL aggregate expression over the IDENTICAL row set with
  one extra time predicate, so it is a strict SUBSET of `arrivals` and `arrivals = outside_W +
  burst_W` is a partition by construction. The calculator then derives `outside` by SUBTRACTION
  (`long outside = arrivals - burst`) and weighs each half once — there is no code path in which
  a delta can be added twice, and the containment invariant `burst_arrivals ≤ arrivals` is
  asserted on EVERY fixture in `LedgerNativeQueriesIT` (the assertion lives in the shared helper,
  so a future fixture inherits it). The bins are half-open `(from, to]`, pinned by
  `theBinsAreHalfOpenSoASampleExactlyOnAnEdgeBelongsToTheOLDERBin`: an edge sample falls out of
  the newer bin and into the older one, so no delta is ever claimed twice at a seam.
- **`ClassHistory`** — `burstArrivals`, `priorBurstArrivals`, `burstUnknown`,
  `discardedBurstSamples`, with the pre-#365 5-arg constructor retained (no call-site churn).
- **Calculator** — `frequency(ClassHistory, AttentionConfig)` + a separate pure
  `flooding(ClassHistory, AttentionConfig)`. The non-flooding branch returns the shipped
  expression itself, not a re-derivation of it.
- **Config** — `burst-window` PT10M, `burst-onset` 10, `burst-exit` 5, `burst-weight` 8.0 with
  `OrDefault` accessors; `burst-exit ≤ burst-onset` refused at binding (`@AssertTrue`, proven in
  `InspectorPropertiesValidationTest`), `burst-window ≤ model-ttl` logs a startup WARN.
- **Wire** — `AttentionFactors` + `flooding`/`burstArrivals`/`burstWindowSeconds`/`burstUnknown`/
  `discardedBurstSamples`; `npm run gen:api` re-run against the running BFF and the 8-line
  `schema.d.ts` diff committed. No frontend component needed changing: the tooltip renders the
  SERVER's rationale verbatim (§12) rather than recomposing it from `factors`.
- **Rationale** — the flooding clause is `spiking: 40 in the last 10 min` (absolute count +
  window, per #365's "beats a bare ratio"); the unknown-bin clause is `recent arrival rate
  unknown`.

### 15.3 Byte-identity below the onset, and how it is proven

`AttentionScoreCalculatorTest.belowTheOnsetTheAmendmentIsByteIdenticalToTheShippedFormula`
sweeps 12 arrival volumes × every sub-onset burst (0–9) × every sub-onset prior (0–9) and
compares `Double.doubleToRawLongBits` against a test-local re-implementation of the SHIPPED
formula — bit patterns, not `isCloseTo`, and against a fixture that reads no production code, so
the claim is about the amendment rather than a tautology. `AttentionOrderingNeutralityTest` keeps
its empty-ledger count-only assertions unchanged and gains
`aSubOnsetBurstReordersNOTHINGBecauseTheAmendmentIsInertBelowTheISAFloodThreshold` (200
randomised corpora scored twice — with and without sub-onset burst evidence — landing in the
identical order), plus assertions that an empty ledger reports `flooding=false` /
`burstUnknown=false` rather than an unknown bin. The flag-off proofs (same object, zero queries,
byte-identical serialization) are untouched and still pass.

### 15.4 Deviations from §14.5, named

1. **`AttentionRationale.sentence` now takes the `AttentionFactors` block** instead of a growing
   parameter list (it would have reached nine primitives). The sentence is therefore composed
   from the EXACT numbers that produced the score rather than from a second derivation that could
   drift from them. Test call sites moved to a test-local factory, per the unit-test-patterns
   "no constructor churn" remedy.
2. **`burstUnknown`'s clause is suppressed when `arrivalsUnknown` is also set.** §14.5 specifies
   both clauses but not their interaction; a wholly-unknown 28-day window already implies the
   last ten minutes are unknown, and §4.3's hard requirement is ONE glanceable sentence. It says
   it once, at the widest scope actually measured. Pinned by
   `anUnknownBurstBinSaysUnknownRatherThanQuietAndNeverBothWithTheWiderWindowsClause`.
3. **Two arithmetic backstops §14.5 did not ask for.** The calculator clamps
   `burst = min(max(0, burstArrivals), arrivals)` and the gate refuses to fire when
   `arrivalsUnknown` — both are unreachable through the SQL (the bin is a subset of the window by
   construction), and both are guards on the ONE thing that must never happen: a negative
   `outside` term would be double-banking with the sign flipped, and an unknown window must never
   host a knowable flood. Tested as explicitly-degenerate inputs, labelled as backstops.
4. **§4.1a's inflation ceiling 1.867 is a BOUND, not an attained value** — measured while
   writing the test: the tightest real case (a flood sitting exactly on the onset with nothing
   outside W) inflates `F` by **1.833×**, and the ratio falls as volume grows (1.449× at
   flood-100). The doc's figure drops the `+1` inside both logarithms, so it over-states the true
   supremum slightly and remains a correct upper bound. The test now asserts the ceiling FORMULA,
   the attained maximum, and their ordering; a companion test proves the unconditional form
   (`F` grows by at most `log2(γ) = 3` bits) over a swept corpus including degenerate bins.

### 15.5 Same-slice correction, not part of the amendment

The stale "earliest G5 satisfaction ≈ 2026-09-14" was duplicated in two files the §5/§7 docs
round could not touch — `AttentionScoreService`'s class javadoc and the
`inspector.triage.attention-ordering` comment block in `application.yml`. Both now read
**≈ 2026-09-29** and say **TRUSTED** span, matching §7's correction (G5 counts trusted span; the
pilot's history was 99 % blind until the declared `engine-7` slot got a real engine at
2026-08-04T15:39 Z). A repo-wide sweep found no third occurrence. The neighbouring "21,229
recorded pilot buckets" figure was deliberately LEFT ALONE: it is the §5.5 extraction's own
count, the §5/§7 correction did not restate it, and replacing it with §14.2's later re-extraction
figure would have introduced a second, different error rather than removing one.
