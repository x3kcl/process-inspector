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

**§8's usability A/B run (issue #366) is ★ EXECUTED, 2026-08-04, N = 5 per arm — both §8.7
gates met, so flag-on is RECOMMENDED (§8.8).** Under count-only every tester went to the
biggest class; under attention ordering every tester went to the costliest one. But the
reordering ALONE did not do the work: 3 of 5 arm-B testers still picked the largest class on
first glance and switched only after reading the rationale tooltip — Laberge's display-alone
finding reproduced on our own UI, and the argument for shipping the operator note WITH any
flip. `S` was neutral and the §4.1a burst term inactive throughout, so neither was exercised
(§8.8). The §7 data-maturity gate still governs the actual flip and is still NOT MET.
Issue #359 (the transiently-failing
self-heal seed fixture) landed 2026-08-04 as `85342e1`/PR #368 — ALL FOUR self-heal lanes,
including `SELF_HEAL_LIKELY`/`SELF_HEAL_MIXED`, are now stageable for §8 (§8.6); the fixture
is opt-in (`PI_SEED_SELF_HEALING=1`) and, by design, counts toward neither this doc's own §7
gate nor RETRYING-RISK-LANE.md's §7.2 gate (§8's own sequencing note explains why).

**Post-ship correction round (#374, 2026-08-05): the reasoning must survive without a
hover — ★ BUILT, ADDRESSED (§12's own correction note; re-run owed).** §8.8's "finding that
matters more than the pass" is addressed in code: the per-card rationale sentence is now
VISIBLE on the card face, not hover-only in the pill's `title` — the fixed generic mechanism
sentence stays hover-only, the per-card evidence sentence does not. Pure rendering change, no
new server field, no ordering toggle, flag default untouched. **The comprehension problem
itself has not been re-measured** — §8.8's numbers predate this change; a fresh A/B run against
the fixed component is still owed before the fix can be called PROVEN rather than merely
plausible. Panel: gemini seat attempted repeatedly this session across three model IDs, all 429
quota-blocked — **owed**, same disclosure as the standing copilot 410.

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
A(c) = F(c) · R(c) · S(c)                 M ≡ 1 in v1 — see the #399 correction below

F(c) = log2(1 + arrivals_28d(c))          frequency — positive occurrence-total deltas,
                                          28d trailing, from incident_occurrence;
                                          1 (neutral) when the window was WHOLLY UNTRUSTED
R(c) = 2^(−age(lastSeen(c)) / τ)          recency — τ default 24h = the existing
                                          quiet-window constant (C6)
M(c) ≡ 1                                  NOT an ordering term (#399, §17). The estimator
       clamp(medMTTR(c) / medMTTR(fleet), 0.5, 2)
                                          is retained, still MEASURED and still shipped as
                                          factors.mttr evidence; < 3 closed episodes ⇒ 1
                                          (neutral) + "no history"
S(c) = max(1 − p_heal(c)·w(c), floor)     self-heal demotion — R2 statistic (§4.2);
       w(c) = 2^(−t_heal(c) / τ_heal)     null/insufficient lane ⇒ 1 (neutral);
                                          absent t_heal ⇒ w = 0 ⇒ 1 (neutral), §4.1b
                                          τ_heal default PT1H; floor default 0.25, INERT
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
- A reliably-self-healing class is demoted **at most 4×, never zeroed** — a mass self-heal
  class stays visible (same doctrine as never-hide). That bound is **lane-quantisation
  arithmetic, not the `self-heal-floor` clamp**: `S` bottoms out at `1 − p_heal(LIKELY) =
  1 − 0.75 = 0.25`, exactly the default floor, reached from above and therefore never
  selected. `inspector.triage.attention.self-heal-floor` is a provable no-op at **every value
  ≤ 0.25** and binds only strictly above it (see the §18 correction — this bullet used to
  credit the floor with a mechanism it does not deliver).
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

**Correction (post-ship, #399 / epic #398 — the full round is §17): `M ≡ 1` in the v1 ordering,
and the definition debt #382 named is paid here.**

*What `medMTTR` actually measures, in the estimator's own terms.* The number is produced by
`IncidentEpisodeRepository.closedEpisodeDurationSeconds()` — `EXTRACT(EPOCH FROM (ended_at −
started_at))` over rows `WHERE ended_at IS NOT NULL`, i.e. CLOSED episodes only. `started_at` is
stamped by `IncidentLedgerService` at the sampler's **first sighting** of the class (the episode
is created in the same pass that first records the incident, and again on each automatic
regression). `ended_at` is stamped by exactly one thing: the **S3 resolve verb**
(`IncidentLifecycleService.resolve` → `IncidentEpisodeRepository.closeEpisode`), an OPERATOR
click; nothing in the sampler, the ledger or any scheduled job ever closes an episode. So
`medMTTR(c)` is the median **first-sighting-to-operator-resolve** duration — time-to-resolution
measured from incident BIRTH, not from the moment anyone started working on it.

*Therefore it is neither of the two things #382 asked between.* It is not a clean operator
service time (that would license Smith's rule and make the shipped multiply backwards), and it
is not a clean severity weight (that would license the multiply). It **contains the operator's
queue wait** — the very quantity this ordering exists to allocate — which makes it ENDOGENOUS to
its own output: rank a class low ⇒ it waits longer ⇒ its measured MTTR rises ⇒ `M` rises ⇒ it
ranks higher next cycle. Under the shipped multiply that loop is *negative* (self-correcting,
which is why nothing was ever on fire), but it means `M` was substantially measuring the QUEUE
rather than the class.

*So the v1 ordering does not consume it.* `M ≡ 1`. The estimator, the `min-closed-episodes`
floor and `inspector.triage.attention.mttr-clamp-{low,high}` are all RETAINED; `factors.mttr`
keeps reporting the real clamped ratio as evidence, and the rationale keeps quoting the median
(with its span now named — §4.3's own correction). Only the SCORE stopped reading it. Precedent:
§3.1's exclusion of `eff(c)` from the v1 score for an analogous honesty problem. Re-entry
condition and the falsifier that would reverse this call: §17.

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

### 4.1b Timing-aware self-heal demotion (amendment #400, ★ BUILT)

**The gap this closes.** `S` consumed the self-heal *probability* and never its *timing*, so a
class that reliably heals **eventually** was demoted exactly as hard as one that heals **in a
minute**. Measured, against the covariate the harm report pre-registered for precisely this
question (`docs/reviews/R-ATTENTION-HARM-2026-08.md` §4c, `t_heal / mttr` swept):

| `t_heal / mttr` | HARM | help |
|---|---|---|
| ≤ 4.00 | 15–33 % | 45–65 % |
| **8.00** | **80.73 %** | **0.00 %** |
| 16.00 | 78.60 % | 0.00 % |

A clean phase change. While the heal lands inside the operator's own horizon the demotion is a
large win; once it lands after the operator would have finished the whole board, deferring the
class buys nothing and costs everything. The evidence needed to tell the two cases apart was
**already on the wire** — `SelfHealStats.ttsP50Seconds`, the median observed duration of the
class's SELF_HEALED spells (RETRYING-RISK-LANE.md §3.2) — so this amendment adds no query, no
new R2 work and no engine call.

**The term.**

```
S(c) = max(floor, 1 − p_heal(c) · w(c))
w(c) = 2^(−t_heal(c) / τ_heal)          t_heal = SelfHealStats.ttsP50Seconds
                                        τ_heal = attention.self-heal-horizon, default PT1H
                                        t_heal absent ⇒ w = 0 ⇒ S = 1 (neutral)
```

**Why `τ_heal` is its own knob and not a constant already in the model.**

- **Not `medMTTR`** (and nothing derived from it). #398 establishes that `medMTTR` is measured
  from incident *birth* and therefore contains the operator's own queue wait — endogenous to the
  ordering, and the whole reason `M` was neutralised. `t_heal` is engine-side and *not*
  endogenous; that asymmetry is the point of the fix, and dividing the one by the other would
  re-import exactly the contamination the round removed.
- **Not the recency half-life τ (C6, 24 h).** Reusing it is arithmetically attractive and
  substantively wrong: at τ = 24 h a class whose median self-heal is eight hours still keeps
  **79 %** of its full demotion — i.e. the table row measured at 80.73 % harm / 0.00 % help would
  be left almost exactly where it was. A constant chosen to express "how long until a *sighting*
  goes stale" cannot also express "how long a *heal* is worth waiting for".
- **PT1H, defended.** `τ_heal` is a **policy** statement — how far ahead this deployment is
  willing to bet on a heal — not an estimate, so it carries no estimator's bias and no
  endogeneity. One hour sits an order of magnitude above the 60 s sampler beat and the PT5M model
  TTL (a heal the ordering could never have reacted to reads as immediate) and an order of
  magnitude below the 24 h quiet window (a heal nobody will still be waiting for reads as never).
  Curve: heal inside one bucket ⇒ `w = 1` (full shipped demotion); 1 h ⇒ 0.50; 8 h ⇒ 0.0039.
  **Named limitation:** the harm search's phase change is at ~4–8 × *service time*, and a fixed
  horizon cannot track a fleet's service time — but the only available service-time estimator is
  the contaminated one, so a policy constant is the honest instrument. A deployment that knows
  its own hands-on service time should set `self-heal-horizon ≈ 4 ×` it.

**Two safety properties, by construction** (the §4.1a discipline: an amendment must be provably
inert where the shipped behaviour was already right).

1. `w ∈ [0, 1]` ⇒ `S ∈ [1 − p_heal, 1]`. The timing term can only ever **weaken** a demotion,
   never deepen one. No class can be pushed below where the shipped formula put it.
2. At `t_heal = 0`, `w = 2^0 = 1` and `S` is **byte-identical** to the shipped expression. That
   is not an edge case: the spell resolution floor is one sampler bucket
   (RETRYING-RISK-LANE.md §3.1), so every class healing inside 60 s reports `ttsP50Seconds = 0`.
   The amendment is therefore exactly inert across the whole regime (`≤ 4 ×` rows) the harm
   search says `S` genuinely wins in, and bites only in the regime it measured as harmful.

**Degradation — `w = 0`, not `w = 1`, and the rule it follows.** `tts*` is absent whenever
`healed = 0` or the raw sample sits below the `n < 10` floor, while the **displayed** lane can
still be a dwelled risk lane at that moment, so "lane known, timing unknown" is reachable (a
`SELF_HEAL_UNLIKELY` class with zero healed spells is the routine case). §4.1's degradation rule
— *an unknown factor reads as the multiplicative identity* — gives `S = 1`. The rejected
alternative, `w = 1` ("preserve today's behaviour"), asserts *this class heals immediately*, the
single most demoting value available, on no evidence at all: the §13 F2 defect class exactly, and
it fails toward burying a card rather than surfacing it.

**Stabilization contract (§4.1), restated precisely.** `p_heal` still comes from the server-dwelled
lane, so the discrete part of `S` is unchanged and cannot flap while the badge holds.
`ttsP50Seconds` is **not** dwell-stabilized — `SelfHealStatsService` runs only the *lane* through
`DwellStateMachine` and pairs it with the raw per-cycle statistic; §4.1's clause is written as if
the whole R2 artifact were stabilized, and it is not (see §18). It is admissible here because the
contract exists to prevent **discontinuous** reordering: the lane is a *classification*, so an
epsilon move in the underlying rate flips `p_heal` 0.75 → 0.50 and jerks the ordering while the
badge says nothing changed. `w` has no threshold to cross — continuous and monotone in `t_heal`,
bounding the response at `|ΔS| ≤ p_heal · ln2/τ_heal · Δt` (at PT1H, a whole sampler bucket of
movement in the median moves `S` by < 0.009) — and it is not a per-cycle rate but a 90-day order
statistic over ≥ 10 completed spells, so moving it one bucket needs a whole spell to enter or
leave the window *and* to cross the middle of the distribution. If a future estimator ever makes
the duration jumpy, the fix is to dwell it in `SelfHealStatsService` next to the lane — never to
re-derive or smooth it in the calculator (§4.2: adapt the join, never re-derive the statistic).

**Rationale (§4.3): no new clause.** The `SELF_HEAL_LIKELY` clause already renders the timing
evidence this factor now consumes — `"usually self-heals (21/23 past spells, typically ≤ 1 min)"`
(#387, from `ttsP90Seconds`) — so the sentence's one-line cap is untouched and the reader can
already see *why* a self-healer was or was not demoted. MIXED/UNLIKELY clauses stay timing-free:
their demotion is 2× and 1.18× respectively, and adding a second timing string would break the
locked frontend/backend parity on `selfHeal.ts` for no proportionate gain (named limitation).

**Wire:** unchanged. No new `AttentionFactors` field, therefore no `schema.d.ts` regeneration and
no frontend change — `factors.selfHeal` already carries the amended value and `factors.selfHealLane`
the lane it came from.

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

### 4.3 Rationale — one glanceable sentence (hard requirement, issue #348)
> "Ordered by the expected cost of waiting: how recently this class was seen and how fast it is
> growing — proven self-healers rank lower, and nothing is hidden."

(Tightened per panel: one sentence, glanceable.) Per-card variant substitutes the numbers:
*"21 failing · last seen 2 min ago · typically 4 h from first sighting to resolve · no
self-heal history."*

**Correction (post-ship, issue #374 — §12's own correction note).** "One tooltip sentence"
was the original heading, and it shipped both sentences above hover-only. The measured §8.8
usability run found that hid the per-card evidence sentence from any non-hovering reader,
directly contradicting the visible card-face numbers for 3 of 5 testers. The per-card variant
(the second sentence, with real numbers) is now rendered as VISIBLE text on the card face; the
generic mechanism sentence (the first, constant, quoted above) stays hover-only — it explains
what the ordering means in the abstract, not per-card evidence, and duplicating a second fixed
sentence onto every card would itself be the "paragraph" outcome this section's own hard
requirement forbids. The one-sentence CAP itself is unchanged; only the per-card sentence's
render location moved.

**Correction (post-ship, #387 — §8.9 finding 1).** The per-card self-heal clause originally
composed as a bare `"usually self-heals (H/N)"` — the §8.9 tester run's T2/T3 found this
dropped both the "spells" unit word (T3 misread "21/23" as the live failing count, not a
lifetime historical spell count) and the timing half (`ttsP90Seconds`) that `/incidents`'
`SelfHealBadge` already rendered for the identical `SELF_HEAL_LIKELY` lane on the same class —
two surfaces, two strings, from the same served statistic. Fixed by mirroring the badge's
own format exactly (RETRYING-RISK-LANE.md §4.1, same correction cross-referenced there): a
`SELF_HEAL_LIKELY` clause now reads `"usually self-heals (21/23 past spells, typically ≤ 1
min)"` — timing omitted only when the server has none to give (§6 honesty rails; never a
fabricated number). Per-card variant, updated: *"23 failing · last seen just now · typically
takes 2 min to resolve · usually self-heals (21/23 past spells, typically ≤ 1 min)."* Still
one sentence, still glanceable — the CAP this section states is unchanged; MIXED/UNLIKELY
clause copy is unaffected (out of #387's scope).

**Correction (post-ship, #388 — §8.9 finding 2, ★ SHIPPED).** T4's own catch: a
`SELF_HEAL_LIKELY` class showing `DLQ 25 / retrying 0` on the card face still read "usually
self-heals (21/23)" with no signal that its CURRENT standing population had already
exhausted retries — the historic rate describes past retrying spells, not the members
visible right now. Fixed by appending, ONE code path, never a second string, a suffix to
the SAME `SELF_HEAL_LIKELY` clause whenever the class's live `deadLetterCount > 0` under
trusted counts (RETRYING-RISK-LANE.md §4.1/§4.2 carry the full trust-rule and stability
detail): `" — not the N dead-lettered (no retries left)"`. Per-card variant, T4's own
scenario: *"25 failing · last seen just now · typically takes 4 h to resolve · usually
self-heals (21/23 past spells, typically ≤ 1 min) — not the 25 dead-lettered (no retries
left)."* Still one sentence. Scope is deliberately narrow (v1): the Stage 0 dashboard's
`AttentionScoreService.decorate()` path only — never the `/incidents` `SelfHealBadge` text
(that surface's tooltip instead gained a static, unconditional teaching sentence) and never
the `forClass()`-composed rationale that also renders on `/incidents` (always the split
absent, so always the base clause there) — a known limitation named in full in
RETRYING-RISK-LANE.md §10.

**Correction (post-ship, #399 / epic #398 — §17). Both sentences made a claim the score no longer
makes, and one of them made a claim the ledger never measured.**

- *The glossary sentence said the ranking was "weighted by this class's historic
  time-to-resolve".* That became FALSE the moment `M ≡ 1` (§4.1 correction): the v1 score is
  `F · R · S`. It is corrected above, and in `frontend/src/components/AttentionBadge.tsx`'s
  `GLOSSARY_SENTENCE` — the one place it renders — to name only what the ordering actually reads.
  This is a copy fix, not a scope reduction: nothing about "ordered by the expected cost of
  waiting" or "nothing is hidden" changes.
- *The per-card clause said "typically takes 4 h to resolve".* That reads as a pure fix-time
  claim — "this class takes 4 h of work" — while the statistic behind it is the median
  **first-sighting-to-operator-resolve** duration, which includes however long the class sat in
  the queue before anyone looked at it (§4.1 correction). **The statistic is kept, the claim is
  corrected** — the same disposition as §3.2's P75 copy fix (F6): the number is the honest one to
  show, it just has to say what it spans. The clause now reads *"typically 4 h from first sighting
  to resolve"*, still one `·`-separated clause on one line, and the sub-floor case is unchanged
  ("no resolve-time history"). Per-card variant, updated: *"21 failing · last seen 2 min ago ·
  typically 4 h from first sighting to resolve · no self-heal history."*
- The #387 and #388 correction notes above quote worked examples carrying the OLD clause
  ("typically takes 2 min to resolve", "typically takes 4 h to resolve"). They are the record of
  those rounds and are deliberately not rewritten; the clause they show is superseded by this
  note, and their own subject (the self-heal / dead-letter clauses) is untouched by #399.

## 5. MEASURED BASELINE — pilot-ledger extraction & ordering simulation (auditable)

**Method.** 2026-08-04 ≈ 07:05–07:10 Z, against the live demo `https://pi.naumann.cloud`,
authenticated as dev-ladder user `viewer` (HTTP Basic, VIEWER floor), **REST only** (no DB
access; `docker exec` into the demo Postgres is out of bounds and was not attempted).
Endpoints: `GET /api/incidents`, `GET /api/incidents/{1..5}?window=720` (episodes + 30-day
occurrence series), `GET /api/triage`, `GET /api/engines`, `GET /api/audit?size=100`
(returned 64 rows = the whole log), `GET /api/triage/trends`. Analysis: occurrence-series
delta scan + audit-timestamp cross-matching (scripted, reproducible from the responses).

**⚠️ Discontinuity note (dated 2026-08-05, issue #377 — read this before differencing across
2026-08-05T03:30 Z).** The demo's three Flowable engines had no persistent volume until
issue #377's fix; a `--force-recreate` run at ~03:30 Z that day (repairing dropped DNS
aliases, unrelated to engine data) silently destroyed every engine-side process instance,
job and history row. The engines came back healthy and EMPTY, and the seed container
quietly re-seeded a fresh minimal set, so the pilot ledger's own occurrence series shows a
real, non-error discontinuity at that instant rather than a smooth continuation — measured
impact: `ArithmeticException` (incident 4) 33→6, `UnknownHostException` (incident 5) 12→2,
incidents 1–3 went `quiet=true`. The MEASURED figures below (§5.1–§5.6, extracted
2026-08-04, i.e. entirely BEFORE the discontinuity) are historical records and remain valid
exactly as stated — nothing here is retroactively adjusted, per the §13 correction
convention: a fact is corrected in place and named as having changed, never silently
rewritten. What changes is how any FUTURE re-extraction must be read: any measurement that
spans 2026-08-05T03:30 Z is differencing across an artificial cliff in the engines'
underlying state, not real signal about the alarm population. The BFF's own Postgres (audit
log, incident/episode/occurrence rows) is on a named volume and was NOT affected — the
ledger's bookkeeping of what happened before the cliff is intact; only the engines' live
process/job state under it was reset. A re-extraction of this section, if performed, must
say explicitly whether its window spans the cliff and, if so, must not report a single
before/after delta across it without flagging the artifact.

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
| G1 | ≥ 20 closed episodes fleet-wide AND ≥ 3 classes with ≥ 3 closed episodes each (closed-episode statistics — see the #399 note below for what this now gates) | 0 closed episodes, 0 classes | **NO** |
| G2 | ≥ 6 distinct current-generation classes live concurrently at least once in trailing 28 d (ordering has room to matter) | max concurrent = 2 | **NO** |
| G3 | #351 shipped; R2's own sufficiency rail passed for ≥ 25 % of live classes (S term) | not built | **NO** |
| G4 | ≥ 10 completed ack lifecycles (ack → expiry/resurface/un-ack) recorded (C2/C3 calibration) | 0 acks ever | **NO** |
| G5 | ≥ 56 d of **TRUSTED CURRENT-ERA** ledger span (28 d fit + 28 d holdout; redefined #365, scoped to one fleet by #372 §16.7) | **0 d** — V22 (#372) records observation scope from its deploy forward and does NOT backfill a guessed fleet, so every pre-V22 row is `fleet = ''` (comparable to nothing) and the era clock starts at V22 deploy | **NO** |

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
not this docs round. (iii) A registry edit can RESTART this clock without any observability
changing (§14.2 correction; issue #372) — the design that makes the span composition-aware,
and amends this gate to the CURRENT-ERA trusted span, is §16.

**What G1 gates since #399 (§17) — restated, because it was written as the `M`-term gate.** `M`
is no longer an ordering term, so G1 no longer gates a factor of the score. It is NOT retired,
because every OTHER consumer of closed-episode statistics is still gated by this table and still
needs exactly this sample:
1. **The C2 ack-expiry suggestion (§3.2)** — the class's P75 closed-episode duration. Its own
   per-class floor (`min-closed-episodes = 3`) is a *within-class* rail; G1's fleet-wide 20 is
   what makes the suggestion credible across the fleet rather than in one lucky class.
2. **The `factors.mttr` diagnostic and the rationale's resolve-time clause** — both render
   measured numbers to an operator, and both are meaningless off a two-episode sample.
3. **`M`'s eventual re-entry into the ordering** — for which G1 is now NECESSARY BUT NOT
   SUFFICIENT. Re-entry additionally requires an uncontaminated estimator (measure from
   first-operator-touch, or subtract the queue wait) *or* the §17 falsifier measured and passed.
   A future round must not read "G1 met" as "turn `M` back on".
G1's threshold, its measured value and its status are all unchanged by #399.

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
G5 needed redefinition.

**Re-measurement (build round #372, 2026-08-05) — G5 is now 0 d, and that is the honest
number.** §16.7 amends G5 to the trusted span **of the current ERA**: an era boundary is any
point where the recorded `fleet` differs from its predecessor's, or where scope is unrecorded.
V22 records `fleet` from its deploy forward and, per §16.5, deliberately refuses to backfill a
guessed fleet onto existing rows — scope at write time cannot be reconstructed afterwards, and
asserting it would be exactly the fabrication the column exists to prevent. Every pre-V22 row
therefore carries `fleet = ''`, which is comparable to nothing (itself included), so **the
current era begins at the first occurrence row written after V22 deploys** and G5's measured
value resets to **0 d** with earliest satisfaction **≈ V22 deploy + 56 d**. This is a
MECHANICAL reset, not a conventional one (§16.9's distinction): no analyst argument can talk
past a `NOT NULL` column whose fail-closed value the trusted predicate treats as incomparable.
It is also not a NEW cost — under the standing §14.2 rail the clock had already restarted at
the 2026-08-04T15:39 Z era start (earliest ≈ 2026-09-29), so shipping promptly costs the days
between the two and nothing more; deferring would have cost the full 56. The exact post-deploy
era-start instant is **owed as a measurement**: it cannot be extracted before the migration
runs, and this round records the rule rather than inventing the value. Re-measure with the §5
REST-only method (now walking `until` per §16.8 item 7) once V22 is deployed, and record the
number in the PR that flips the flag. Until the gate: the score computes with neutral M/S (provably
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

> **Second correction, from the 2026-08-04 EXECUTED run (§8.8) — "stageable" is not
> "cheap".** This section says all four lanes are reachable post-#368, and that is true. The
> run then measured what it actually costs at the **production 60 s `sample-interval`**:
> `SELF_HEAL_LIKELY` needs 10 unconfounded spells **plus** `dwell-cycles: 10` (~10 min)
> before the lane even displays, while a spell is only ~20–30 s of real retrying — so spells
> are barely separable in the series and one was already excluded as confounded. #368's ITs
> get there by driving `sampler.sampleOnce()` against bucket boundaries, NOT via the
> scheduled sampler. **Budget a dedicated 30–40 min staging pass, or accept a neutral `S` and
> say so** — the executed run took the second option and recorded it. Do not read this
> section as promising a cheap `S`.

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
(12/14 past spells, typically ≤ 8 min)") illustrates `SELF_HEAL_LIKELY` specifically — that lane, and
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

### 8.8 Verdict — ★ EXECUTED 2026-08-04 (dev stack, hp04)

| Arm | N testers | Median time-to-first-relevant-card | Comprehension pass rate (/b+/c) | Stability check | Verdict |
|---|---|---|---|---|---|
| A (flag off, control) | **5** | **planted class NEVER REACHED** (5/5 drilled the largest class instead; median time to *any* first drill 108.4 s, n=4 — A2 never drilled) | n/a (badge absent — 4/5 scored `unsupported`, 1 `partial`, all reporting no ordering text exists) | n/a | **control observed as designed** |
| B (flag on) | **5** | **79.7 s** (66.4 / 66.8 / 79.7 / 85.4 / 184.5) | **10/10 = 100 %** | **PASS** — identical order across 3 refreshes | **both gates met** |

**Decision (per §8.7's rule): flag-on is RECOMMENDED — (comprehension pass) AND (arm B ≥ arm A) are both satisfied.** This is a recommendation to the session owner, not an activation: `inspector.triage.attention-ordering` remains **default-false**, and the §7 data-maturity gate is **still NOT MET (0 of 5)** and governs the actual flip independently of this usability result.

**Fixture (dev stack, `uxrun-m13-*`).** 6 live classes. Planted costly class = `MethodNotFoundException` (`zooMethodNotFound`), **15 instances**, `M = 2.0` from 3 closed episodes (median 241 s) against a deliberately depressed fleet median (93 s, held down by 3 × ~12 s episodes on `StringIndexOutOfBoundsException`). Largest class = `ArithmeticException`, **34 instances**, `M = 1`. Server-computed order at dispatch: **15-member planted class (score 8.0) above the 34-member class (5.13)** — count-only would invert them. Rationale served: *"15 failing · last seen just now · typically takes 4 min to resolve · no self-heal history."*

**Result in one line: under count-only every tester went to the biggest number; under attention ordering every tester went to the costliest class.** Arm A: 5/5 picked the 34-member class. Arm B: 5/5 finished on the planted 15-member class, 4/5 drilling into it.

#### The finding that matters more than the pass — the ordering alone did not do the work

**3 of 5 arm-B testers still picked the 34-member class on first glance** and only switched *after* hovering the rationale tooltip (B5, torn between the two, said so explicitly). The reordering by itself did not redirect them; **the explanation did**. That is Laberge's result reproduced in miniature on our own UI — display-alone is not the intervention, display-plus-strategy is — and it is the empirical argument for the operator note (§8.1) being shipped *with* any flag flip rather than after it.

Sharpened by task /c (restate the ranking from the card face **without** re-reading the tooltip): **B3 `unsupported`, B1 and B2 `partial`** — testers could not reconstruct the ranking from visible text. B5 named the mechanism precisely: *"[the `ranked by attention` pill] names the mechanism but gives zero indication that hovering unlocks the actual reasoning, and a fast-scanning user … will default to raw instance/DLQ counts, which point at a different card than the one the ranking actually favors."* **The reasoning lives only in a hover.** A 3am reader who does not hover — or is on a device that cannot — gets a re-ordered list with no visible justification, and their instinct points elsewhere. Filed as a follow-up rather than silently absorbed into a green verdict.

**Forward pointer (post-ship, #399 — see §17): this fixture's reordering was driven by `M`, and
`M` is now identically 1, so this exact fixture would no longer reorder anything.** Recomputed
against the corrected score (`F·R·S`, the same served numbers): planted class `8.0 / 2.0 = 4.0`,
largest class `5.13 / 1 = 5.13` — i.e. the count-only order, un-inverted. The measured RESULT
above stands as what happened on the day and is not rewritten; what it no longer is, is a live
recipe. **A re-run must build its separation out of `F` and `R`** — a genuinely faster-growing or
fresher costly class (or a `SELF_HEAL_LIKELY` demotion on the large one), not a depressed fleet
MTTR median. The run's headline finding — that the reordering alone did not redirect testers, the
explanation did (§12.1/#374) — is about the rationale, not about `M`, and is unaffected.

**Forward pointer (post-ship, #374 — see §12.1):** this whole subsection describes the UI as
it stood when this run executed (hover-only `title`). That hover-only behaviour has since been
changed — the per-card rationale now renders as visible page text — and this verdict predates
that change. Read the paragraph above as the diagnosis that motivated §12.1's fix, not as a
description of the shipped UI today; it has not been re-run against the fixed component.

#### What this run did NOT exercise (do not read it as covered)

- **`S` was neutral throughout.** Every class sat at `INSUFFICIENT_HISTORY` (`0`–`1 of 10 spells observed`); no class reached `SELF_HEAL_LIKELY`/`MIXED`, so **the self-heal demotion story was not tested**. Reaching the floor organically needs 10 unconfounded spells **plus** `dwell-cycles: 10` (~10 min at the 60 s beat) while a spell is only ~20–30 s of real retrying — which is precisely why #368's ITs drive `sampler.sampleOnce()` against bucket boundaries instead of the scheduled sampler. §8.6 lists these lanes as stageable; **at the 60 s production cadence that is true only with a dedicated staging pass this run did not spend.**
- **The §4.1a burst term was not active.** `flooding: false`, `burstArrivals: 0` at dispatch — the staged burst aged out of `W = PT10M` before arm B started. The discriminator was `M` alone. **#365's burst gate is proven by simulation and unit/IT coverage, not by this run.**
- **Ordering divergence here is attributable to `F`/`R`/`M` only**, exactly as §8.2 step 5 requires this case to be reported.

#### Honesty notes
- Arm B's 184.5 s outlier (B2) is **self-flagged by that tester as captured ~59 s late**, and B2 drilled a non-planted incident; kept in the median unadjusted rather than discarded.
- Both arms ran the same fixture; the two arms are separate BFF process lifetimes (`INSPECTOR_TRIAGE_ATTENTION_ORDERING` set at start, per §8.4) — not a live flip.
- This run is **usability evidence only**. The `PI_SEED_SELF_HEALING` fixture and every `uxrun-m13-*` instance count toward **neither** the R4 grouping-quality corpus **nor** this doc's §7 gate **nor** RETRYING-RISK-LANE.md's §7.2 gate.
- Incidental defect surfaced by arm A (A3): the landing card read `34 instances` while the drilled grid read `42 instances`. Documented behaviour (the card's own tooltip warns the live query can disagree) but jarring on first read.
- Recurring copy complaint across **8 of 10 testers**: *"1 spell excluded from this statistic (operator-confounded, a sampling gap, …)"* and `R-BAU-10` / `BFF` / `Stage-0` as unglossed jargon.

### 8.9 S-factor supplement — ★ EXECUTED 2026-08-07 (dev stack, hp04; closes §8.8's "S was neutral throughout" gap)

A dedicated staging pass + 5-tester comprehension run exercising the self-heal demotion
story end-to-end for the first time. **This is usability/testability evidence only** — the
`PI_SEED_SELF_HEALING` fixture counts toward NEITHER this doc's §7 gate NOR
RETRYING-RISK-LANE.md §7.2 (both still NOT MET), and the flag stays default-off.

**Fixture defect found & fixed first.** The shipped `demo-self-healing.bpmn20.xml` could
never self-heal standalone: its non-interrupting boundary timer was created inside the
async attempt's own always-rolling-back transaction, so the clock never armed (zero
`healTimer` jobs engine-wide while instances retried — the ITs bypass the timer BY DESIGN
and structurally could not catch this). Fixed in this change: parallel-fork +
timer-intermediate-catch (armed in the committing start transaction) + a terminate end so
the ITs' externally-healed path still completes. See RETRYING-RISK-LANE.md's corrected G12
note. First genuine engine-driven standalone self-heal: 2026-08-07, ~30s end-to-end.

**Staging (all REST, no `ACT_*` access, unlowered floor 10):** ~33 staggered `PT10S`/`PT75S`
spells + 15 standing baseline members + fresh comparator births. Committed:
`SELF_HEAL_MIXED` first (9/11 observed), then **`SELF_HEAL_LIKELY` — n=23 observed spells,
21 healed, Wilson LB 0.732 ≥ the real 0.70 enter threshold, 2 spells excluded, ttsP50 0s /
ttsP90 60s** — dwell + Schmitt behavior observed live across the MIXED→LIKELY progression.

**Sampler-aliasing finding (measured, new):** at the 60s beat, spells whose retrying window
is shorter than one cycle are UNDER-OBSERVED — only ~11 of the first ~21 staged `PT10S`
spells (retrying window ~15s) registered; `PT75S` spells (window > one beat) registered
reliably. §8.2 step 5c's wall-clock note stands, but staging must ALSO size `healDelay`
against the sampler cadence, not just the retry cascade. (Organic implication, honest but
minor: very-short organic spells are systematically under-sampled by the 60s beat — the
statistic sees the slower-healing tail, which biases the observed heal rate DOWN, the
conservative direction.)

**Ordering ground truth at dispatch:** the 25-member LIKELY class scored `S = 0.25` (full
floor) and ranked **below a 7-member and a 2-member class** (score 1.35 vs 2.58/1.58),
rationale "usually self-heals (21/23)", card visible at #4 of 7 — never hidden. The §8.2
step-5 canonical story, live.

**Tester run (5 naive Sonnet testers, sequential, `viewer`, M13 /b + /c + an S-focused
triage-choice /a variant — NOT a full M13 A/B: no arm-A control, no timing metric):**

| Graded task | Result |
|---|---|
| /b ordering-rule restatement (citation-or-nothing) | **5/5 correct** — every tester rejected biggest-count-first and quoted ≥2 real factors |
| /c self-heal badge restatement (citation-or-nothing) | **5/5 correct** — historic-not-promise unanimous; postures graded per what each tester actually saw |
| Combined vs the ≥80% bar | **10/10 = 100% — PASS** |

All 5 chose the top-ranked card first, and all who saw the LIKELY class chose it as the one
to leave alone (T5's rushed first glance picked wrongly from the truncated viewport, then
self-corrected on scroll — recorded, not graded away).

**Findings that survive the pass (follow-ups filed: #387, #388):**
1. **Badge copy is inconsistent across surfaces** (T2/T3 → #387): Stage 0's inline rationale says
   "usually self-heals (21/23)" — no unit, no timing — while `/incidents` renders the full
   "(21/23, typically ≤ 1 min)" + the exclusions tooltip. The bare fraction invites reading
   "21 of the 23 currently failing" (T3 named exactly this misread), and "no resolve-time
   history · usually self-heals" juxtaposed reads as a contradiction (T2).
2. **The badge's posture doesn't key on the visible population** (T4, the sharpest catch → #388):
   with `DLQ 25 / retrying 0` on the card face, the CURRENT standing population has
   exhausted retries and cannot self-heal — the spell statistic applies to future retrying
   spells, not the standing dead-letters. T4 correctly derived "escalate, don't wait" from
   the on-screen counts; partially fixture-shaped (the baseline members are permanent by
   construction) but reachable organically whenever a LIKELY class sits between spells.
3. (Recorded, no new issue): the generic ordering explanation remains hover-only by design
   (#374 promoted only the per-card rationale), and T1 could not reconcile the demoted
   LIKELY class ranking ABOVE three zero-score cards from visible fields alone —
   zero-score cards' position is explained by nothing on their faces. The §8.8 A3
   count-drift complaint (91 → 111 on drill) recurred verbatim (T4).

**Honesty notes.** Grading judgment call, recorded: T4's /c posture ("escalate") diverges
from the lane's canonical "leave it" but is the CORRECT derivation from the on-screen
retrying-0 state — graded correct; finding 2 exists precisely because the rubric and the
card face disagree. The #358 attribution agent's `mvn verify` ran ITs against the shared
engines during early staging — its SelfHealSeed classes use run-unique tokens, so the
`selfHealGhost` series is isolated by construction, but its residue classes were visible in
the fleet the testers saw. Browser session persisted across testers (read-only, same
`viewer` user; T2–T5 skipped sign-in). Spell tally: 33 staged heal-spells, 21 observed
healed + 2 v1-era escalations observed (n=23); the unobserved remainder is the aliasing
finding above, not silent loss.

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
- **Explainability**: rationale is one glanceable `·`-joined sentence with per-card numbers
  (§4.3) — a score no rationale can explain is a rejected design by construction.
  **Correction (post-ship, #374):** "the one-sentence tooltip" was true as first shipped by
  #354 (hover-only `title`) but is no longer accurate — §12.1 promoted the same sentence to
  visible page text on the card face after §8.8 measured that a hover-only reasoning path
  actively hurt comprehension. The one-sentence cap this bullet describes is unchanged; only
  where the sentence renders moved.
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
  design's "demoted at most 4×, never zeroed" is arithmetic rather than a separate clamp —
  which also means the floor is INERT at its default, corrected everywhere it was miscredited
  in §18/S1. **Extended by #400 (§4.1b):** the same `selfHeal` block's `ttsP50Seconds` now also
  feeds a continuous timing weight `w`; the *stabilized* lane still supplies `p_heal`, and
  §4.1b states exactly why a non-dwelled duration is admissible as a weight but would not be as
  a threshold.
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
  SERVER's own one-sentence per-card rationale (`attention.rationale`), never recomposed from
  `factors` client-side, per the issue's explicit rule. **Correction (post-ship, #374 — see
  §12.1):** the claim above, that `title` joins the fixed sentence WITH the per-card rationale
  "rendered VERBATIM", was true only as #354 first shipped it and is now superseded — §8.8
  measured that hover-only reasoning actively hurt comprehension, so the per-card rationale was
  promoted to visible text on the card face; `title` now carries ONLY the fixed glossary
  sentence. Do not re-fold the rationale back into `title` on the strength of this paragraph.
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
  rationale-display UI only (at ship time, hover `title`; superseded by #374's visible-text
  promotion, §12.1 — "tooltip UI" here describes #354 as it shipped, not the mechanism as it
  stands today), not the usability-harness proof of benefit. **§8 is now fully AUTHORED
  (issue #366)** — see §11's own note; still not executed.

### 12.1 Correction (post-ship) — issue #374: the reasoning must survive without a hover (★ BUILT)

§8.8's own executed run (PR #373) found the finding that matters more than the pass: the
per-card rationale this slice shipped lived ONLY in the pill's `title` (hover tooltip). Every
raw number visible on the card face — instance count, DLQ jobs, retrying jobs — can be LARGER
on the class the ranking places LOWER, so the visible evidence actively contradicts the order
and only a hover reconciled them. 3 of 5 arm-B testers picked the wrong (bigger-number) card on
first glance; on the free-recall task (restate the ranking WITHOUT re-reading the tooltip) B3
scored `unsupported`, B1/B2 `partial`. Tester B5: *"[the pill] names the mechanism but gives
zero indication that hovering unlocks the actual reasoning ... a fast-scanning user will
default to raw instance/DLQ counts, which point at a different card."* A hover is also not
universally available — touch, keyboard-only, and most screen-reader flows never trigger a
`title`.

**The question.** What is the MINIMUM visible signal that reconciles the order with the
numbers already on the card face, without violating §4.3's one-glanceable-sentence rule or
§12's rejected-ordering-toggle precedent?

**Shapes considered, and why three were rejected:**

1. **Dominant-factor inline** (e.g. "4 min to resolve," picking whichever of `F`/`R`/`M`/`S`
   most explains the ranking for this specific card pair) — **REJECTED**. Naming "the"
   dominant factor requires NEW client-side logic that inspects `factors` and decides which
   ingredient wins for a given comparison. That is exactly the "recompose the rationale from
   `factors` client-side" this slice's own §12 explicitly forbids ("never recomposed from
   `factors` client-side ... real numbers, not vibes the SERVER computed"). It is also a
   second, possibly-drifting derivation of a claim the server's `rationale` string already
   states correctly — on a different fixture pair `S` or the §4.1a burst term could be the
   actual discriminator, and a client heuristic for "dominant" has no guarantee of agreeing
   with the server's own composed sentence.
2. **Rank affordance** (an explicit ordinal — "#1", "#2" — or a stated position) —
   **REJECTED**. It answers "what order is this in," which the list position already answers
   implicitly; it does not answer "why," which is what the measured failure is about. A
   reader still sees `#1` sitting on a smaller number than `#2` and has no more grounds to
   trust it — task /b (restate the ranking without re-reading) would score identically.
3. **Interactive pill** (cursor affordance, chevron, or similar cue that invites hovering or
   clicking) — **REJECTED**. It improves discoverability of the SAME hover-gated mechanism,
   but the issue's own named failure mode is that hover is not universally available — a
   cursor/chevron cue is itself invisible or meaningless on touch, keyboard-only, and most
   screen-reader flows. It treats "nobody thought to hover" as the defect, when the actual
   constraint is "hovering cannot be the only path to the reasoning at all."
4. **Bare score number** (e.g. "8.0" beside the card) — **REJECTED, and probably harmful**.
   An unlabelled float means nothing to a 3am reader without a scale anchor, and it is a
   FOURTH number added to a card that already carries three that all point the wrong way —
   more noise for exactly the failure mode measured ("go by the big number"), not less. It
   also inverts the usual "bigger number wins" convention along yet another axis (8.0 > 5.13
   here, but nothing on the card explains why a bigger score should trump a bigger count),
   adding a second unexplained contradiction rather than resolving the first.
5. **CHOSEN — promote the existing server-computed rationale sentence from `title` (hover) to
   real visible text on the card face**, leaving the FIXED generic glossary sentence (what the
   ranking mechanism means — not per-card evidence) in `title` on the pill, unchanged.

**Why this is the minimum change that satisfies both hard constraints:**
- **§4.3's one-glanceable-sentence rule is satisfied by construction, not by new discipline.**
  `AttentionRationale` (backend, §11) already caps the rationale at one `·`-joined sentence.
  Moving it to visible text does not grow it — the identical string moves out of an attribute
  into a text node. No new composition, no new server field: the DTO already carried
  `attention.rationale` (#353); this is a pure rendering change, exactly as the issue asked.
- **It targets the diagnosed defect literally.** The measured problem statement is "the
  reasoning lives only in a hover" — not "the mechanism name is invisible" (`ranked by
  attention` already rendered visibly) and not "the order is unclear" (list position already
  shows it). The one thing that was hover-gated was the per-card EVIDENCE sentence, and that
  is the one thing this change moves.
- **No ordering toggle is reintroduced.** Nothing becomes optional or user-togglable; every
  card carrying a server score shows the identical one line it always could have shown on
  hover. §12's rejected-toggle precedent is untouched.
- **Fixes keyboard/touch/screen-reader for free, not as an add-on.** A `title` attribute has
  no reliable keyboard-focus trigger in most browsers, no touch trigger at all, and
  inconsistent screen-reader exposure. Ordinary rendered text needs none of that: it is
  already part of the element's accessible name/text content the moment it renders — no ARIA,
  no focus management, no extra affordance required.
- **The glossary/rationale split is deliberate, not an oversight.** The FIXED constant
  sentence ("Ordered by the expected cost of waiting...") explains the MECHANISM once, in the
  abstract; it does not vary per card and carries no per-card evidence. Promoting it to visible
  text on every card too would be the actual "turn every card into a paragraph" outcome §4.3/
  §12 warn against — a second, longer, unchanging sentence repeated on every one of what could
  be dozens of Stage-0 cards. Leaving it hover-only (discoverable, not load-bearing for the
  per-card judgment) while making the per-card EVIDENCE sentence load-bearing-visible is the
  narrowest cut that fixes the measured defect without also violating the size constraint the
  defect's own fix must respect.

**Panel review (repo convention — two independent seats, honest ledger).** The `gemini` MCP
seat was attempted repeatedly on 2026-08-05 across three model IDs
(`gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-2.0-flash-001`) — **7 attempts total, at
intervals spanning roughly 20 minutes**; `gemini_list_models` (a different, unmetered endpoint)
succeeded throughout, but every `generateContent` call returned HTTP 429 (quota), including the
final retry made after the rest of this build was already complete. Seven attempts across three
model IDs all failing the same way reads as real exhausted project-level quota for the session,
not a broken connection and not a per-model block (contrast the pro-only 429 tier-fallback
precedent in §10/§14.6, where flash succeeded). **Seat 2 (product/ops, `copilot` MCP) remains
the STANDING SEAT-UNAVAILABLE from §10/§14.6** — the endpoint is permanently gone (HTTP 410,
GitHub Models catalog sunset), not a quota condition.

Because seat 1 stayed unavailable on every retry, the subsequent adversarial review of this
shipped correction (the findings folded into this section and into §13-adjacent code/doc
fixes) was carried out by a **substitute independent seat — Claude Opus 5 — explicitly
authorised by the session owner for that review only**. Recorded as exactly that, not as
`gemini` and not as a new standing MCP seat: it is a one-time authorised substitution, not a
change to the panel roster, and it does not discharge seat 1 — a real `gemini` re-attempt is
still owed. **The second standing seat (product/ops) is still owed** and unaffected by this
substitution — it stays unfilled per the same standing rule until the copilot endpoint is
replaced. This correction's status therefore remains: one seat filled by an authorised
substitute, one seat owed — not yet PANEL-REVIEWED by the repo's normal two-MCP-seat
convention.

**What landed.** `components/AttentionBadge.tsx`: the per-card rationale renders as a sibling
`<span className="attention-rationale">` (real text content) instead of being folded into the
pill's `title`; the pill's `title` now carries ONLY the fixed glossary sentence. No change to
when the badge renders (still nothing when `attention` is absent — the shipped, flag-off,
expected-today case) or to what triggers it. `styles.css` gained `.attention-rationale` (same
weight/rhythm as `.incident-meta-line`/`.ack-meta`'s existing "extra glanceable fact"
treatment). No DTO change, no `npm run gen:api` re-run (the schema already carried
`attention.rationale`; this is rendering-only). `inspector.triage.attention-ordering` stays
default **false** and the badge still renders nothing when `attention` is absent, so the
change is inert exactly as before whenever the flag is off — nothing here touches
`AttentionOrderingNeutralityTest`'s server-side guarantee, which this change does not go near.
**Correction (post-ship, adversarial review):** the claim that "no layout change was needed at
any of the three call sites" was wrong, and dropped the third call site's class name entirely.
`AttentionBadge` returns a bare Fragment, so its pill and rationale span land as direct flex
items of whichever container renders it. `ErrorGroupCard`'s `.error-signature` and
`IncidentCard`'s `.incident-signature` are indeed `display: flex; gap: 8px` and needed no
change. `IncidentDetail`'s call site — `.self-heal-line`, the third container, correctly named
here — was NOT flex (`margin: 4px 0` only), so the pill text and the rationale span rendered
flush with no gap between them. Fixed by making `.self-heal-line` itself
`display: flex; gap: 8px; flex-wrap: wrap`, matching the other two containers' own pattern
(chosen over a margin on `.attention-rationale` so the shared span class does not double up
with the gap the other two containers already provide).

**Failing-before proof.** `components/AttentionBadge.test.tsx` gained
`#374: the per-card rationale is real VISIBLE text, not reachable only via a title/hover
attribute`, asserting `container.textContent` (which never includes attribute values) contains
the rationale string. Against the pre-fix component this failed — `textContent` was exactly
`'ranked by attention'`, with the rationale reachable only via `getAttribute('title')`.
Two more pre-existing tests updated to the same visible-text assertion also failed on the base
for the identical reason (`renders the visible marker and a tooltip carrying the SERVER
rationale verbatim`; `never composes the rationale from factors`). A new
`triage/ErrorGroupCard.test.tsx` case reproduces the §8.8 fixture SHAPE directly — two cards
whose raw counts contradict the ranking (15 vs 34 instances/DLQ, matching §8.8's planted pair)
— and asserts BOTH per-card rationale sentences are reachable via `screen.getByText` (rendered
content), which a hover-only regression fails exactly as the real UI failed 3 of 5 testers.
`IncidentCard.test.tsx`'s existing coverage was updated the same way. All are described in
full in the PR; every test passes against the fixed component.

**Known-unreachable shape (adversarial review, not a defect today).**
`AttentionBadge`'s render guard is `attentionRationale(attention) === undefined`
(`incidents/attention.ts`); `attentionRationale` returns `attention?.rationale`. A `rationale`
of `''` (empty string) would pass that guard and render an empty `<span
className="attention-rationale">` plus the flex `gap` next to it — a visible blank gap with
nothing in it. This is NOT reachable from the current server: `AttentionRationale.sentence`
(§11) always emits at least four `·`-joined clauses and a trailing period, so it can never
return `''`. Recorded here rather than defended against in code — adding an `=== undefined ||
=== ''` guard for a shape the server structurally cannot produce would be speculative
defensiveness with no failing test to justify it.

**Scope discipline.** No score, factor, tie-break, or the §4.1a burst term touched. No new
server field. No ordering toggle. No card hidden or filtered — R-BAU-01 untouched. The flag
default is untouched and still governed by §7 (NOT MET). Spec-sync: `docs/SPECIFICATION.md`
(the `AttentionBadge` description), `docs/OPERATOR-QUICK-START.md` ("Reading the attention
ranking"), and `docs/usability/GOAL-CATALOG.md`/`MISSIONS.md` (R-SEM-25/M13's references to
"the tooltip" as the citation source for /a and /b) are corrected in the same change — the
rubric's PASS BAR and citation-or-nothing grading are unchanged, only the UI mechanism the
citation comes from. **That the bar (≥80 %, the Laberge calibration) is unchanged is NOT a
neutrality guarantee between a pre-#374 and a post-#374 run.** Before this fix, the citation
source (the rationale sentence) was reachable only by hovering; after it, the identical
sentence is unconditionally visible on every card. A tester's pass/fail no longer measures the
same thing it measured in §8.8 — it now also measures whatever benefit or distraction the
always-visible sentence itself creates, independent of the ordering. §8.8's recorded numbers
and any future re-run against the fixed component are therefore **not directly comparable**;
a higher (or lower) post-fix pass rate cannot be attributed to the ordering change alone.

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
(`cycle_complete = false`).** Every row before **2026-08-04T15:39 Z** is blind; there is
exactly ONE transition in the whole series (`false → true` at that instant, re-verified on a
later extraction: 21,741 / 21,990 blind, 249 trusted rows, no flap back). Before it, a
registered engine that did not come back `ok()` made every cycle incomplete, and the V21
fail-closed backfill marks all pre-V21 rows blind by design. The trusted era was
**168 minutes old at first extraction**.

**Correction (2026-08-04, same round — the CAUSE first recorded here was wrong).** This note
originally read "complete cycles begin exactly when the declared `engine-7` slot got a real
engine (commit 015c9e1)". That is **not** what happened, and the contradicting evidence was
available over the same REST surface: `GET /api/engines` reports `engine-7` as
`lifecycle: "disabled"`, `reachable: false`, `healthError: "not probed yet"` — still, *after*
the trusted era began — and `GET /api/triage`'s `perEngine` envelope contains only `engine-a`
and `engine-b`. `PollingSnapshotSource` computes `cycleComplete` by iterating exactly that
envelope, so the era turned trusted when `engine-7` **left the aggregation scope** (disabled
in the registry), not when it became reachable. Note also that #369 ("allowlist in-network
engine hosts so `engine-7` is actually reachable") had merged by this re-check and `engine-7`
is *still* disabled and unprobed on the demo — so it is not the cause either.

**What this changes, and what it does not.** The measured boundary is unchanged, so §7's G5
redefinition over TRUSTED span and its ≈ 2026-09-29 date stand exactly as stated — they rest
on the transition instant, not on its cause. What it does change is the *meaning* of the
trusted era: those 249 rows are trusted about a **two-engine fleet**. A cycle is "complete"
when every engine still IN scope answered, so disabling an unreachable engine converts blind
cycles into trusted ones without any observability actually being recovered. That is arguably
correct (a disabled engine is not part of the fleet) but it is a sharp edge worth naming:
**the trusted-span clock can be restarted by a registry edit.** If `engine-7` is re-enabled
and carries load, rows spanning that change are not differenceable against these, and the
G5 clock should be re-measured rather than assumed to have run continuously. (This
operational rail is the issue #372 status quo; the design that records scope on the row and
retires the rail is §16.)

> **SUPERSEDED 2026-08-05 by the #372 build slice (§16.11) — kept in place, unrewritten, per the
> §13 correction convention.** The paragraph above is left exactly as written because it is the
> record of what was believed and enforced between 2026-08-04 and the V22 deploy, and because
> §16.4's rejection of option 3 rests on reading it as it stood. What supersedes it: the
> "re-measure §7 after any registry change" **convention** is no longer the mechanism — every
> occurrence row now RECORDS its own observation scope (`incident_occurrence.fleet`, V22), the
> derived readers refuse to difference across a scope change, and G5 is measured over the
> CURRENT ERA (§16.7). The rail's *finding* stands verbatim and is the reason the column exists;
> only its remedy is retired. The rail remains the ONLY protection for rows written BEFORE V22 —
> those carry `fleet = ''` and are comparable to nothing, so in practice they are simply not
> fittable rather than fittable-by-convention.

This is still the trust discipline working as specified (#302), and the honest headline of
the feasibility note is unchanged: **on this pilot the binding constraint on burst measurement
is the trust discipline, not the cadence.**

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
pilot's history was 99 % blind until 2026-08-04T15:39 Z — see §14.2's correction for why the
cause is *not* the engine-7 slot getting a real engine). A repo-wide sweep found no third
occurrence. The neighbouring "21,229
recorded pilot buckets" figure was deliberately LEFT ALONE: it is the §5.5 extraction's own
count, the §5/§7 correction did not restate it, and replacing it with §14.2's later re-extraction
figure would have introduced a second, different error rather than removing one.

## 16. Amendment round — observation SCOPE / fleet composition (#372, 2026-08-05, DESIGN + ★ BUILT)

Design record for issue #372 ("the trusted-span clock can be restarted by a registry
edit"), the R1 seat's answer to the three candidate shapes the issue lists. **Design only —
no production code changes in this round**; the build contract is §16.8, the
build-or-defer recommendation §16.9. This section EXTENDS §14.2's correction (which named
the cause); it does not re-litigate it.

### 16.1 Provenance

Issue #372, minted from the #365 blind-prefix verification against the live pilot (PR #371
follow-up). Grounding for every claim below is the SHIPPED code at `d8ab553`
(`PollingSnapshotSource`, `IncidentLedgerService`, `IncidentOccurrenceRepository.arrivalsSince`
/ `findSpellShapeRowsDescending`, `RetrySpellExtractor`, `DwellStateMachine`,
`SelfHealStatsService`, `EngineRegistry`, `EngineRegistryStore`, `AuditService`) — each
statement about a consumer or a data source below was read from source in this round, not
assumed from the docs. **One exception, found and closed in the substitute-seat review
(§16.10, F3):** §16.5(ii)'s id-charset claim was written from judgment, not source —
corrected in place, now citing `InspectorProperties.ENGINE_ID_PATTERN` (`:34`, applied via
`@Pattern` on `EngineConfig.id` at `:499`) and `EngineRegistryStore.ID_PATTERN` (`:62`,
enforced in `add()` at `:224`).

### 16.2 The gap, stated precisely — quality vs scope

`cycleComplete` answers "did every engine **we are watching** answer this pass?". Its scope
is the enabled-engine set: `TriageAggregationService.aggregate` fans out over
`registry.all()` (enabled only — the disabled filter moved into `all()` at Registry-CRUD S3)
and puts an envelope into `perEngine` for EVERY engine it fanned out to, ok or not. An
engine disabled in the registry is not in `registry.all()`, so it never enters the envelope
— and `cycleComplete` goes true the moment the remaining engines all answer (§14.2's
measured pilot fact: the ONE `false → true` transition in 21,990 rows coincides with the
`engine-7` DISABLE, not with any observability recovering).

That is #302 working as specified — for the two PER-CYCLE consumers, "did everyone in
scope answer" is exactly right. The defect class lives in the PERSISTED series: two rows
can both read `cycle_complete = true` while describing **different fleets**, and nothing on
the row says so. Differencing a level against a non-comparable level is the exact hazard
the `truncated` (R-SEM-12) and `cycle_complete` (#302/V21) disciplines exist to prevent —
but both guard observation **quality**; neither guards observation **scope**. Concretely,
both derived readers have a live scope hole today:

- **Arrivals (F / burst bins):** re-enable `engine-7` with load on it and the growth edge
  differences a two-engine level against a three-engine one — banked as arrivals by
  `SUM(GREATEST(δ,0))`, with `trusted = true` on both endpoints. Symmetrically, a DISABLE
  mid-window makes the drop edge a phantom "quiet" (clamped, not banked — but the level
  shift silently survives as the new baseline).
- **RETRYING spells (R2):** disable the engine that holds a class's RETRYING jobs while
  another engine holds its dead-letters: `retrying_count` drops to 0 on a row that is
  `cycle_complete = true`, the spell appears to END, and the look-ahead compares the
  surviving engine's unchanged DLQ against spell start — **fabricated `SELF_HEALED`
  evidence via a registry edit**, the same forged-edge mechanism the V21 javadoc describes
  for unreachability, entering through the door V21 cannot see. `truncationTainted` does
  not cover it, `hasBlindSample` does not fire (the rows are honestly complete for their
  scope), `hasInternalGap` does not fire (the rows exist).
- **G5 (§7):** the trusted-span clock is a gate input since #365, and a registry edit
  moves it (measured: one edit flipped 99 % of the pilot history's trust status).

**Recurrence — verified over REST while this design was being written (2026-08-05).**
`GET /api/engines` on the live demo now reports `engine-7` as `lifecycle: active`,
`reachable: true`, `engineVersion: 7.1.0` (health probe 04:38 Z): the fleet went from the
§14.2 two-engine trusted era to THREE engines within a day of the DISABLE that started
that era. Two composition changes inside 24 hours, on a quiet pilot, both routine admin
operations — the hazard is recurring, not hypothetical. Under the shipped boolean, every
post-re-enable row is indistinguishable from the two-engine era's trusted rows; under the
standing §14.2 rail the G5 era must be re-measured and restarts ≈ 2026-08-05, moving
earliest satisfaction to ≈ **2026-09-30** with or without this design. (The lifecycle flip
is VERIFIED; the exact era-start instant in the occurrence series was not re-extracted this
round — the ≈ date is arithmetic on the verified flip, and §7's G5 row now needs its
scheduled rail-driven re-measurement regardless.)

**Status (substitute-seat review, F5): demoted to corroboration.** This live-demo
observation cannot be reproduced from the repo and sits in tension with `15cca3d`'s own
body, which says engine-7 "stays `lifecycle=disabled` until someone enables it" — that
commit does not itself assert the flip happened. §16.9's BUILD NOW argument no longer
depends on it: it is re-anchored on §14.2's repo-verifiable era start, and this paragraph
is kept only as corroborating color (the flip, if real, moves the date by one day, not
fifteen).

### 16.3 Consumer inventory (read from source — the contract for §16.6)

Two DISTINCT consumption surfaces exist and they must not be conflated: the **in-memory
per-cycle flag** (`AggregationSample.cycleComplete()`) and the **persisted column**
(`incident_occurrence.cycle_complete`, V21).

| # | Consumer | Reads | What it needs | Scope-aware change? |
|---|---|---|---|---|
| C1 | Zero-state regression gate — `IncidentLedgerService.ingest` → `sweepZeroState` (#302, R-BAU-10) | in-memory flag | "did everyone in CURRENT scope answer" — per-cycle, scope-relative BY DESIGN | **NO CHANGE** (issue non-goal, verbatim) |
| C2 | Self-heal dwell tick — `SelfHealStatsService.tick` → `DwellStateMachine.advance` rule 3 (§4.2) | in-memory flag | same per-cycle meaning; incomplete cycles neither advance nor reset the dwell | **NO CHANGE** (§16.6 C2 records why) |
| C3 | Arrivals aggregate — `IncidentOccurrenceRepository.arrivalsSince` (F factor #353 + burst bins #365) | column, in the `trusted` LAG predicate | "may this row be DIFFERENCED against its predecessor?" — needs quality AND scope comparability | **YES** — the gap lives here |
| C4 | Spell substrate — `findSpellShapeRowsDescending` → `SpellSample` → `RetrySpellExtractor` gap-voiding (R2, #351) | column | "is this retrying-count EDGE a job event or an artifact?" — same two-part need | **YES** — the gap lives here |
| C5 | API honesty surface — `IncidentDetail.OccurrencePoint.cycleComplete` (`dto/IncidentDetail.java:72`; sparkline markers; the §5/§14.2 REST-only measurement method reads this) | column via DTO | render/measure per-row honesty without DB access | **YES** — gains the scope field |
| C6 | §7 G5 measurement (`scripts/replay-burst-attention.py` method — VIEWER, REST-only) | column via C5 | a trusted span that is also SAME-FLEET comparable | **YES** — definition amended (§16.7); REST *reachability* past the 30 d window clamp is a separate, pre-existing gap — corrected in §16.7 and closed in §16.8 item 7 |

C1 and C2 are the "two existing consumers of `cycle_complete`" the issue protects: both
consume the flag PER CYCLE, where scope-relativity is correct, and both are untouched.

### 16.4 Shape decision — option 1, scope ON THE ROW; options 2 and 3 rejected

**Chosen: option 1 — record fleet composition per occurrence row**, as a new column
`incident_occurrence.fleet`: the canonical string of the enabled-engine id set the pass
actually fanned out over. `cycle_complete` keeps its exact semantics for everyone; quality
and scope become two orthogonal markers read together at difference time, precisely the way
`truncated` and `cycle_complete` are already read together.

Why the row and not the estimator — the same reason V21 moved the blind flag onto the row:
the consumers that need it are a **native SQL window aggregate** (C3) and a **pure
list-transform** (C4). A marker on the row lets both apply the discard rule locally (one
more term in an existing `LAG` predicate; one more equality check in an existing span scan)
and lets the REST-only measurement method (C6) see era boundaries *within whatever window
that surface can reach* — corrected here (substitute-seat review, F1): today that window is
hard-capped at 30 d (§16.7), so the marker makes boundaries visible, it does not by itself
make the whole trusted-span history reachable. Scope-awareness anywhere OFF the row has to
be **reconstructed** instead of **recorded**, which is where option 2 fails.

**Option 2 (estimator reads the registry audit trail) — REJECTED, with its data source
VERIFIED rather than assumed.** The trail EXISTS: `EngineRegistryStore.transition()` writes
fail-closed audit entries (`registry-enable` / `registry-disable`, engine id in the entry's
own `engine_id` column, payload `{id, from, to, mode}`), `registry-add/remove/purge/seed`
likewise; audit retention MUST be ≥ 400 d (application.yml), matching occurrence retention.
So the option is *implementable* — and still wrong, for four verified reasons:
1. **`source: config` deployments have NO trail at all.** Under `inspector.registry.source:
   config` (`RegistryBootstrap`), composition changes are YAML edits + restart — no audit
   event is ever written. The estimator would be composition-aware on `db`-sourced
   deployments and silently blind on config-pinned ones — a correctness property that
   varies by deployment mode is not a gate rail.
2. **Payload minimization:** `from`/`to` are not in `AuditService.SKELETON_KEYS`, so under
   the DEFAULT `redacted` payload mode their values are masked. Direction survives via the
   action name, but the reconstruction depends on parsing action names out of a
   hash-chained, partitioned store designed for accountability, not for joining into a
   60-second-beat aggregate.
3. **Event-time ≠ sample-time.** The sampler reads a `volatile` registry snapshot;
   `EngineRegistry.reload` swaps it post-commit while a cycle may be in flight. An audit
   `ts` cannot say which snapshot a given occurrence row was actually written from —
   reconstruction is an approximation at exactly the boundary rows where it matters.
4. **It leaves C4 and C6 unserved.** `RetrySpellExtractor` is deliberately pure (rung-1
   testable) and the G5 measurement is deliberately VIEWER/REST-only; both would need new
   plumbing (an audit-derived segmentation feed; an audit-reading API surface) that is
   strictly more machinery than one column.

**Option 3 (status quo: the §14.2 documented rail) — REJECTED as the end state, KEPT as
the interim.** "Re-measure §7 after any registry change" leaves a GATE INPUT movable by a
routine, audited-but-unremarkable admin action, with nothing in the data to catch a missed
re-measurement — convention-dependent in exactly the way #302 refused to leave blindness.
And unlike a display feature, a RECORDING gap is unrecoverable: every trusted row written
between now and the build is a row whose scope can never be attached retroactively (that
is option 2's lesson). The rail stays in force until the build lands, then §14.2 gains a
superseded-note (§16.8), corrected in place per the §13 convention.

**Sub-decisions inside option 1** (the issue's "set hash, or a count"):
- **A count — rejected**: cannot see a swap (disable A + enable B is a composition change
  at constant count).
- **A hash — rejected**: detects every change but destroys the "what changed" answer;
  §14.2-style forensics and the REST measurement would be staring at an opaque token. The
  literal canonical set is self-describing, a few dozen bytes for any realistic fleet, and
  equality-comparable — the only operation any consumer needs.
- **A normalized `fleet_era` table + FK — rejected**: adds a join to every consumer, an
  era-management write path, and era-identity races for zero information gain over the
  denormalized string on a table whose whole design is "narrow, partitioned, append-mostly".

### 16.5 The marker — definition and write path

- **Value:** the ids of the engines the pass INTENDED to observe — `perEngine.keySet()`,
  which is `registry.all()` (enabled engines) at fan-out time, **including** engines whose
  envelope came back not-ok (in scope, unobserved ⇒ that is `cycle_complete`'s job, not
  `fleet`'s). Canonical form: ids sorted lexicographically (`String.compareTo`), joined
  with `,`. Sorting matters: `perEngine` is registry-ordered and a pure REORDER is not a
  composition change. **Correction (substitute-seat review, F4): "at fan-out time" is not
  exactly true today.** `TriageAggregationService.aggregate` reads `registry.all()` TWICE —
  once to fan out (`:99`) and again to collect (`:109`), and `perEngine.put()` happens in
  the second loop (`:113`), so `perEngine.keySet()` is actually the **collect-time** set,
  not the fan-out set. A registry edit landing between the two reads (the same volatile-swap
  window Option 2's ground 3 below already names, just narrower here) means `fleet` would
  record what the pass *finished* observing, not quite what it *set out* to. Harmless either
  way for §16.6's consumers (both readings are equally valid "scope of this row"), but the
  §16.4 phrase "the engines the pass INTENDED to observe" overstates precision the code does
  not have. §16.8 item 2 adds the one-line hardening (capture the list once, drive both
  loops from it) so the claim becomes exact rather than merely harmless.
- **Carriage:** `AggregationSample` gains `Set<String> fleetEngineIds` (filled by
  `PollingSnapshotSource` from the envelope it already iterates — **zero new engine
  calls**, the Stage 0 iron rule untouched); one static canonicalizer produces the string;
  `IncidentLedgerService.upsertOccurrence` persists it on every row — INCLUDING blind
  ones (scope is the intent set and is always known, even when quality is not).
- **Migration `V22__incident_occurrence_fleet.sql`** (V22 verified next free):

  ```sql
  ALTER TABLE incident_occurrence
      ADD COLUMN fleet text NOT NULL DEFAULT '';
  COMMENT ON COLUMN incident_occurrence.fleet IS
      'Canonical sorted comma-joined ids of the enabled engines the writing pass fanned out '
      'over (#372) — the row''s observation SCOPE, orthogonal to the two quality markers. '
      'Two rows are difference-comparable only when both carry the SAME non-empty fleet. '
      'Empty string = unrecorded (pre-V22 backfill, or a write path that failed to state '
      'scope): never comparable to anything, itself included.';
  ```

  Metadata-only on Postgres ≥ 11 (non-volatile default — the V21 precedent), cascades to
  every monthly partition + the DEFAULT catch-all. `ddl-auto=validate` holds:
  `IncidentOccurrence` gains the mapped field in the same change. **Nit (substitute-seat
  review):** the migration sets no `lock_timeout`. Catalog-only, so the exposure is
  milliseconds, and `V21__incident_occurrence_cycle_complete.sql` shipped the identical
  pattern on the identical table with no `lock_timeout` either — this is a repo-wide
  migration-authoring question, not something this design introduces or needs to solve.
- **Backfill = the fail-closed default, no UPDATE pass.** `''` means "scope was never
  recorded", and the trusted predicates treat `''` as comparable to NOTHING — not even to
  an adjacent `''` (two unrecorded scopes are not known to be the same scope). This is
  V21's own rule applied unchanged: an unknown marker resolves to "untrusted", never to
  "trusted". The DEFAULT is KEPT after migration so any future insert path that forgets to
  state scope degrades safe; the ledger's upsert always passes it explicitly.
- **The temptation explicitly refused:** seeding current-era rows (post
  2026-08-04T15:39 Z) with the current fleet, on the strength of the §14.2 measurement
  that no composition change occurred since. That would bake an out-of-band, deploy-time
  observation into schema history as if the rows had recorded it — the exact fabrication
  V21's backfill note refuses. Cost of refusing: the G5 era clock restarts at V22 deploy
  (arithmetic in §16.9 — small now, growing with every week of deferral).
- **Edge cases, named:** (i) an EMPTY enabled fleet writes no occurrence rows at all (no
  engines ⇒ no groups), so `''` never legitimately occurs on a written row and is
  unambiguous as the unrecorded sentinel; (ii) engine ids containing `,` could in theory
  alias two different sets onto one string — **verified-closed, not merely judged**
  (corrected in the substitute-seat review, F3; see §16.1): every write path enforces
  `InspectorProperties.ENGINE_ID_PATTERN = "^[a-z0-9][a-z0-9._-]{0,63}$"` (`:34`), which
  forecloses `,` on every path that can mint or edit an id — `@Pattern` on
  `EngineConfig.id` (`:499`, reached via `@Valid List<EngineConfig> engines`, the
  `source: config` YAML path) and `EngineRegistryStore.ID_PATTERN` on `add()` (`:224`, id
  immutable on edit — the `source: db` path, whose YAML seed comes from the same validated
  list). The build slice still adds the cheap assert-and-warn in the canonicalizer as
  belt-and-braces, not as the only defense.

### 16.6 Per-consumer changes (C1–C6 — including the ones that must NOT change)

- **C1 zero-state gate — UNCHANGED, verbatim.** Still driven by the in-memory
  `sample.cycleComplete()`; still scope-relative. Note for the record: after a DISABLE, a
  complete cycle "observes absent" classes that lived only on the disabled engine and arms
  their zero-state flag. That is today's semantics and the issue's explicit non-goal —
  **but the earlier claim that this is self-limiting was wrong and is retracted**
  (substitute-seat review, F2): `IncidentLedgerService.ingest` gates the sweep on the
  in-memory `cycleComplete` (`:159`), which is **true** after a disable (the disabled
  engine simply left `registry.all()`, so the pass never fails to hear from anyone it
  actually fanned out to); `sweepZeroState` arms `seen_zero_since_resolve` on that
  unobserved absence (`:294-304`); and `regressionMinCountOrDefault()` floors at **1**
  (`InspectorProperties.java:392`), so `gateOpen = row.isSeenZeroSinceResolve() &&
  group.total() >= regressionMinCount` (`:242`) opens on the very first count back, not on
  a re-enable "at which point the regression is real evidence." Two routine registry edits
  (disable, then re-enable) mint a false REGRESSED with a fresh episode and a fail-closed
  audit row — no faulty job ever having existed.

  **★ SUPERSEDED (issue #380, 2026-08-07) — "C1 stays UNCHANGED" was true for THIS design
  round and is no longer true of the code.** This section previously concluded: *"The scope
  call stands: C1 stays UNCHANGED, and fixing this is not this design's job"*, dispositioned
  accepted / out-of-scope / tracked as #380. That was the correct call for the #372 slice —
  which is why #372's build (PR #384) left `sweepZeroState` byte-untouched and proved it so.
  **#380 then fixed it as a separate, separately-justified change, using the `fleet` column
  #372 had just landed.** The arming rule is now **scope-gated**:
  - the sweep is skipped entirely when the current pass recorded **no** observation scope
    (`fleet` unrecorded) — fail-closed, since an absence you cannot attribute to an
    observation is not evidence of absence;
  - an absent RESOLVED incident is armed **only** when the current pass's `fleet` equals the
    fleet it was **last observed under**; a differing or unrecorded last-observed scope
    leaves it unarmed (and logged, counted, never silent);
  - the adjacent case is closed at source: with ZERO enabled engines
    `PollingSnapshotSource` now sets `cycleComplete = false`, because an EMPTY scope cannot
    be a complete observation of anything.

  **`cycleComplete`'s meaning is untouched** — an engine that IS in scope and did not come
  back `ok()` still makes the cycle blind, exactly as #302 requires. What changed is only
  that a *scope change* no longer counts as an *observation*. A genuine drain on a stable
  fleet arms and regresses exactly as before, and `regression-min-count` keeps its default
  of 1 (raising it would have masked this, not fixed it, and would have altered genuine
  regressions).
- **C2 dwell tick — UNCHANGED.** Considered and rejected — the reason stated here is
  corrected (substitute-seat review, nit): the original text overstated the cost of gating
  `advance` on fleet-vs-previous-cycle as freezing every class's lane for `dwellCycles`
  after each registry edit. In fact a boundary gate shaped like the existing blind-cycle
  gate would skip only **one** cycle: `DwellStateMachine.advance` returns `state` unchanged
  for a single non-complete tick (`DwellStateMachine.java:46-54`), and a scope-boundary gate
  would follow the same shape. The conclusion is unchanged, but rests on the correct reason
  instead: the evidence feeding the statistic is already scope-cleaned upstream by C4's
  voiding, so an extra pass-through gate here buys no additional honesty (the §4.2 rule-3
  freeze bug remains a live warning against under-specified gates in this state machine
  generally, just not evidence for a `dwellCycles`-long freeze here).
- **C3 `arrivalsSince` — the `trusted` predicate gains the scope terms**, same shape as
  the quality terms it joins:

  ```sql
  (o.truncated = false
       AND o.cycle_complete
       AND o.fleet <> ''
       AND COALESCE(LAG(o.truncated) OVER w, false) = false
       AND COALESCE(LAG(o.cycle_complete) OVER w, true)
       AND COALESCE(LAG(o.fleet) OVER w, o.fleet) = o.fleet) AS trusted
  ```

  A delta is trusted only when both endpoints carry the SAME recorded fleet. The birth row
  (LAG NULL, `sampled_at <= first_seen`) stays an arrival — `COALESCE(..., o.fleet)`
  self-compares — because a birth differences against the seeded 0, not against another
  fleet's level; it now additionally requires its OWN scope recorded (`fleet <> ''`). The
  burst bins (#365) inherit the discipline automatically — they FILTER the same `d.trusted`
  — and the F2 counting rule is untouched: a scope-discarded delta still counts in
  `observed_samples` and not in `trusted_samples`, so a wholly scope-broken window degrades
  to `F = 1` (unknown), never to a fake 0. Invariant `burst_arrivals ≤ arrivals` is
  unaffected (same filters, narrower time).
- **C4 spell substrate — scope joins the blind rule, same lane.**
  `findSpellShapeRowsDescending` projects `fleet`; `SpellSample` gains it;
  `RetrySpellExtractor` extends the existing #302 check: a judged span (the run, its
  closing zero-count sample, and the look-ahead actually consulted) containing an
  UNRECORDED fleet or MORE THAN ONE distinct fleet is **gap-voided** — an edge at a
  composition boundary may be an artifact of the boundary, and the outcome test would
  compare DLQ levels across different fleets. Unobservable shape, never a guess (§5 of
  RETRYING-RISK-LANE) — identical disposition to `hasBlindSample`, one more reason feeding
  the same `gapVoided` flag. Monotonicity note for the record: this change can only VOID
  more spells (toward `INSUFFICIENT_HISTORY`), never mint one. **Named consequence,
  previously unsaid (substitute-seat review, nit):** voiding shrinks the observed spell
  count `n` feeding the self-heal statistic (§4.1), and because C2's dwell tick has no gate
  of its own on this input series, a registry edit can make the DISPLAYED self-heal lane
  visibly move over the next few complete cycles — not because retry behavior changed, but
  because the class's evidence window was just scope-cleaned. This is honest (§5's "never a
  guess" still holds — nothing is fabricated) but was not previously stated as a visible
  side effect of an ordinary registry edit.
- **C5 DTO — `IncidentDetail.OccurrencePoint` gains `fleet`** (the canonical string,
  verbatim);
  springdoc → `npm run gen:api` regen committed. Frontend RENDERING of era boundaries on
  the sparkline is a named non-goal of the build slice (the field exists for measurement
  and honesty; a timeline marker is a follow-up if ever asked for).
- **C6 measurement method — §16.7.**

### 16.7 G5, amended — the CURRENT-ERA trusted span

An **era boundary** is any point in the fleet-wide series where the recorded fleet differs
from the previous recorded fleet, or where scope is unrecorded (`''`). G5's span is
measured **within the current era only**: from the first quality-trusted
(`cycle_complete = true AND truncated = false`) row after the last era boundary, to
present. §7's two definitional notes carry over intact: a blind interval INSIDE an era
thins but does not reset (deltas across it are discarded; trusted rows both sides stay
fittable and same-fleet); an era boundary DOES reset, because the 28 d fit + 28 d holdout
must both difference within one fleet.

**Correction (substitute-seat review, F1) — the method named above is not reachable by the
surface it names.** `IncidentQueryService` is the only REST surface carrying occurrence
rows (`IncidentDetail.series`, behind the incident-detail endpoint), and it hard-clamps the
window: `MAX_WINDOW_HOURS = 24 * 30` (`IncidentQueryService.java:65`), applied by
`clampWindow` (`Math.max(1, Math.min(hours, MAX_WINDOW_HOURS))`, `:339-341`) to a query that
is always bounded to `[now − clamped, now]` with no `before`/`until` cursor
(`findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc`, `:179-182`). Rows
older than 30 days are structurally unreachable over REST — `scripts/replay-burst-attention.py`
already carries this as a known limit (`WINDOW_H = 720  # server clamps to 30 days`, `:43`).
G5 needs **≥ 56 d** of trusted span (28 d fit + 28 d holdout). Once the current era's start
passes 30 days of age — ≈ **2026-09-04**, thirty days after the 2026-08-05 re-enable that
opened this era (§16.2's recurrence note) — a VIEWER running this method sees 30 days of
uniformly same-`fleet` rows and can conclude "at least 30 d", never "≥ 56 d", and never
*where* the era began, because the earlier rows simply are not there to fetch. Recording
`fleet` (C5) makes era boundaries visible only **inside** whatever window the surface
reaches; it does not, by itself, extend that window. This is a pre-existing §5/§7 defect —
the clamp and the script's own comment both predate this round — that does not bite until
the era is 30 days old; but this is the round that redefines G5 and asserts the method is
REST-only sufficient, so it is this round's defect to own, not a future one's.

**Fix, chosen: extend the REST surface (option (a)), not the honesty claim (option (b)).**
§16.8 gains a build item (item 7): a `before`/`until` cursor on the occurrence-series read,
so a caller — the script, or a future UI — can page backward past the clamp in bounded
chunks instead of the surface staying globally capped at 30 days. Each individual call
stays time-bounded (the "no window scans unbounded" property is preserved; only the
*reachable total span* changes), which is exactly what the G5 method needs to actually
walk back to an era boundary and confirm ≥ 56 d. Chosen over admitting G5 needs DB access
(option (b)) because the entire point of C5/C6 was to keep this measurement VIEWER/REST-only
(§16.4); quietly re-introducing an ADMIN/DB-access dependency for a narrow, partitioned,
already-time-ordered per-incident series is a worse trade than a bounded cursor. Until the
cursor ships, §7's G5 row and the §14.2 operational rail stand exactly as written, and the
method named in this section should be read as reaching only the most recent 30 d of any
era — not the full trusted span.

### 16.8 Build-slice contract (option 1 — the #372 build agent's spec)

Scope: ONE slice (worktree → local CI → PR → second-seat review → green CI on SHA).
Recommended build seat per the issue: Opus 5, high effort (Flyway + the #365-amended
aggregate).

1. `V22__incident_occurrence_fleet.sql` exactly as §16.5; `IncidentOccurrence` +
   `IncidentOccurrenceId` untouched PK; entity field + javadoc.
2. `AggregationSample.fleetEngineIds` + `PollingSnapshotSource` fill +
   canonicalizer (+ the comma assert-and-warn); `IncidentLedgerService.upsertOccurrence`
   passes it; repository upsert takes the new column. **Hardening added by the
   substitute-seat review (F4):** `TriageAggregationService.aggregate` currently reads
   `registry.all()` twice (fan-out `:99`, collect `:109`), and `perEngine.put()` happens in
   the second loop (`:113`) — so `perEngine.keySet()` is collect-time, not fan-out-time.
   Capture `List<EngineConfig> fleet = registry.all()` **once** and drive both loops from
   it, so `fleet` (and `cycleComplete`) describe one atomic snapshot and §16.5's "the
   engines the pass INTENDED to observe" is exact, not approximate.
3. C3 predicate change; C4 projection + `SpellSample` + extractor rule; C5 DTO +
   `gen:api` regen.
4. **Tests (the rung ladder, failing-before per the §13/§15.1 convention — each new
   assertion must produce a WRONG VALUE on the base, not a compile error):**
   - Rung 1 pure: `RetrySpellExtractorTest` — fleet change inside the run ⇒ voided; at the
     closing zero ⇒ voided; look-ahead fleet ≠ span fleet ⇒ voided (base: fabricated
     `SELF_HEALED` — the §16.2 scenario, now a named fixture); `''` in span ⇒ voided;
     constant fleet ⇒ every existing fixture's outcome unchanged.
     `PollingSnapshotSourceTest` — fleet = envelope keyset incl. a not-ok engine; canonical
     string stable under registry reorder.
   - Rung 4 (real Postgres): `LedgerNativeQueriesIT` — re-enable edge across a fleet
     change NOT banked (base banks it: the failing-before number); disable edge not
     negative-banked and the post-change baseline correctly re-seeds on the next
     same-fleet pair; adjacent `''`/`''` pair NOT trusted; birth row with recorded fleet
     still counts once (F3), birth row with `''` does not; burst bins inherit;
     `burst_arrivals ≤ arrivals` on all new fixtures; constant-fleet fixtures byte-identical
     to pre-change results.
   - Must-not-change proofs: `IncidentLedgerServiceTest` zero-state suite untouched and
     green; `DwellStateMachine` suite untouched; `AttentionOrderingNeutralityTest`
     untouched (flag-off byte-identity holds — C3 runs only under the flag).
   - `IncidentLedgerIT`: `fleet` persisted on blind AND complete cycles.
5. **Spec-sync owed by the build slice** (this amendment is design-only and owes no delta
   beyond this file): INCIDENT-LEDGER §3.3 (DDL block + "two quality markers, one scope
   marker" wording), RETRYING-RISK-LANE §3.1 + §5 (scope voiding), SPECIFICATION /
   ARCHITECTURE wherever the two-marker sentence appears (grep `cycle_complete`),
   IMPLEMENTATION-PLAN research-track record, `application.yml` comment if any wording
   references the marker pair, §7 G5 row re-measured + §14.2 rail superseded-note (in
   place, named as superseded, never rewritten) + a §16 build record subsection here (the
   §15 precedent). `AttentionScoreService` javadoc G5 date refreshed against the
   post-deploy measured era start.
6. **Behavior honesty note for the PR:** flag-off surfaces are untouched by C3 (provably —
   neutrality test), but C4 feeds the DEFAULT-ON self-heal computation; the change is
   monotonically conservative (voids, never mints) and on the pilot measurably ≈ nothing
   (0 unconfounded completed spells exist to void, RETRYING-RISK-LANE §8). Say so in the
   PR body; do not call the slice "no behavior change".
7. **Added by the substitute-seat review (F1) — a `before`/`until` cursor on the
   occurrence-series read.** `IncidentQueryService`'s incident-detail query is capped at
   `MAX_WINDOW_HOURS = 24 * 30` with no cursor (`:65`, `:179-182`, `:339-341`), which makes
   G5's ≥ 56 d determination structurally unreachable over REST once the current era passes
   30 days old (§16.7). Add an optional `before`/`until` request parameter so a caller can
   page backward past the clamp in bounded (still time-limited, still ≤ `MAX_WINDOW_HOURS`
   per call) chunks; update `scripts/replay-burst-attention.py` to walk the cursor when
   hunting an era boundary older than one window. Test: a synthetic fixture whose trusted
   span exceeds 30 d is reachable in full only via chained cursor calls, and a single call
   without the cursor still returns exactly the most recent `MAX_WINDOW_HOURS`-bounded slice
   (no accidental behavior change to the existing single-call path).

### 16.9 Build now, or defer? — BUILD NOW, and the reason is the clock

**Re-anchored (substitute-seat review, F5) on repo-verifiable ground.** The earlier version
of this argument leaned on the §16.2 live-demo `GET /api/engines` observation, which cannot
be reproduced from the repo and sits in tension with `15cca3d`'s own body ("stays
`lifecycle=disabled` until someone enables it" — i.e. that commit alone does not claim
anyone flipped it). The argument is stronger without leaning on it. Re-anchored primarily on
§14.2's published, repo-verifiable era start of **2026-08-04T15:39 Z** — hours before this
design's own commit, computed in §7 as earliest G5 ≈ **2026-09-29** (2026-08-04T15:39 Z +
56 d). The §16.2 engine-7 flip, if and when it is independently confirmed, only moves that
date to ≈ **2026-09-30** — **one day**, not the fifteen the original §7 correction was about.
The observation is kept below as corroboration, not as a load-bearing premise.

The honest case for deferral exists: §7 is NOT MET 0-of-5, G1–G4 are all also unmet, and
nothing consuming these markers ships enabled today. If this were a display or estimator
feature, deferral would win.

It is a **recording** feature, and that inverts the answer:

1. **The gap is unrecoverable retroactively.** Scope-at-write-time cannot be reconstructed
   later (§16.4's option-2 findings are the proof). Every trusted row written before V22
   deploys is a row the eventual G5 fit can use only by CONVENTION ("we believe no registry
   edit happened") — the §14.2 rail, permanently, for that data.
2. **The fail-closed backfill makes deferral cost LINEAR — and today it is ≈ ZERO.** The
   era clock restarts at V22 deploy (§16.5). But the pilot's era already restarted at (or
   near) the current clock start computed above: under the standing rail, earliest G5 is
   already ≈ 2026-09-29/09-30 with or without V22. Deployed promptly, V22's fail-closed
   reset is absorbed into a reset that has effectively already happened — the marginal cost
   is the days until deploy. Deferred to late September, the same migration costs the full
   56 days and pushes G5 to ≈ late November. There will never be a cheaper moment than now.

   **Named distinction the earlier draft elided:** "absorbed" is generous, and the two
   resets are not the same kind. The §14.2 rail reset is a **conventional** one — an analyst
   can argue past it with evidence ("we believe no registry edit happened between these two
   rows," the exact convention point 1 above names as fragile but not impossible to contest).
   V22's `''` backfill reset (§16.5) is **mechanical and irrevocable** — a NOT NULL column
   with a fail-closed default that the trusted predicate treats as comparable to nothing,
   full stop, no argument admissible. Calling the second "absorbed into" the first
   overstates how alike they are. It does not change the answer: either way the earliest
   buildable clock start is now, not September.
3. **R2 is default-on today, and the trigger is routine.** The C4 scope hole (fabricated
   `SELF_HEALED` via a registry edit) sits in a computation that runs on every deployment
   now, gate or no gate — and the pilot's fleet composition has changed on short notice by
   ordinary registry operations before (§16.2). This is not a tail risk waiting for an
   unusual event; it is the normal operating rhythm of the registry surface.
4. **The slice is small** — one metadata-only migration, one predicate term, one extractor
   rule, no new surfaces, no flag changes; §16.8 is implementable without re-derivation.
5. **Dominance (added, F5): the answer does not depend on any of the above being right.**
   The scope-recording gap is unrecoverable (point 1) independent of exactly when the clock
   started or whether the live-demo observation holds — so the migration must happen
   eventually regardless. Given that it must happen, the earliest possible BUILD date is
   bounded below only by the earliest possible CLOCK-START date, and every day of deferral
   after that is a day of unrecoverable data loss for zero offsetting benefit (nothing
   consumes these markers today, so there is no cost to shipping early and no benefit to
   waiting). Therefore BUILD NOW dominates defer **whether or not** the §14.2 date, the
   §16.2 recurrence observation, or any other premise here turns out to be exactly right —
   the only way defer wins is if the recording gap were somehow recoverable later, which
   §16.4 already rules out.

Deferred explicitly: frontend era-boundary rendering (C5 note), any audit-trail
cross-check of recorded fleets (option-2 machinery — YAGNI once the row records scope),
and any change to C1/C2 (protected, not deferred).

### 16.10 Panel review (repo convention — two independent seats, honest ledger)

**0 of 2 standing seats completed this round — both owed.** Recorded exactly as it
happened, per the §10 precedent for an incomplete panel:

| Seat | Model | Verdict | Findings & disposition |
|---|---|---|---|
| Architecture/data | Gemini (`gemini` MCP) | **SEAT UNAVAILABLE THIS ROUND** | Attempted 3 times on 2026-08-05: `gemini-2.5-pro`, `gemini-2.5-flash` (the §10/§14.6 tier-fallback), then `gemini-3.1-pro-preview` — every `generateContent` call returned HTTP **429** while `gemini_list_models` succeeded on the same key, i.e. session-level quota exhaustion, not a transient rate blip (a sibling session independently measured 7 consecutive 429s across three model tiers the same day). Per the standing rule the seat was NOT filled by a substitute model and nobody self-graded in its place. The design lands without it **at the session owner's explicit direction**; the seat is owed before the doc's status moves past DESIGN. |
| Product/ops | GitHub Models (`copilot` MCP) | **SEAT UNAVAILABLE** | Endpoint permanently gone (HTTP 410, GitHub Models catalog sunset — verified 2026-08-04, not quota; do not re-diagnose). Not filled by a substitute; nobody self-graded. **Owed**, same as §10/§14.6. |

The session owner arranged a **substitute independent review seat** (Claude Opus 5,
session-owner authorised **for this review only** — the same handling as #374, **not** a
new standing seat and **not** gemini) to run against the committed text at `85f701c`. Its
findings and this document's dispositions are recorded below, labelled as the substitute
seat, explicitly not as either standing seat above — both of which remain owed.

**Substitute seat — Claude Opus 5, 2026-08-05. Verdict: APPROVE WITH NITS.**

| # | Sev | Finding | Disposition |
|---|---|---|---|
| F1 | MEDIUM, required | §16.7's amended G5 cannot be measured by the REST-only method it names — `IncidentQueryService` hard-clamps the occurrence-series window to 30 d (`MAX_WINDOW_HOURS`, `:65`/`:339-341`) with no `before`/`until` cursor (`:179-182`), while G5 needs ≥ 56 d; rows past 30 d are structurally unreachable over REST | **Fixed.** §16.7 corrected (three wrong claims: the C6 table row in §16.3, the "lets the REST-only method see era boundaries" line in §16.4, and §16.7's own closing line); chose **option (a)** — a `before`/`until` cursor, added as build item 7 in §16.8 — over admitting DB access is needed, to keep C5/C6's VIEWER/REST-only property real rather than nominal. Noted as this round's defect to own, not a pre-existing one to defer. |
| F2 | MEDIUM, required | §16.6 C1's "self-limiting" justification is false: `cycleComplete` is true after a disable (disabled engine just left `registry.all()`), `sweepZeroState` arms on unobserved absence, and `regressionMinCountOrDefault()` floors at **1** — two routine registry edits (disable, re-enable) mint a false REGRESSED, not a re-enable-gated real one | **Fixed.** False justification retracted and replaced with honest accepted/out-of-scope/tracked, citing **issue #380** (mechanism + the adjacent zero-enabled-engines vacuous-`cycleComplete` case). C1 itself is unchanged — the review confirmed leaving it alone is still the right scope call, only the reasoning was wrong. |
| F3 | LOW | §16.5(ii)'s id-charset claim was JUDGMENT, not verified, but the rule exists: `InspectorProperties.ENGINE_ID_PATTERN` forecloses `,` on both the `config` (`@Pattern` on `EngineConfig.id`, `:499`) and `db` (`EngineRegistryStore.ID_PATTERN` in `add()`, `:224`) paths | **Fixed.** Upgraded from accepted-on-judgment to verified-closed with citations; assert-and-warn kept as belt-and-braces. §16.1's "read from source in this round" claim corrected to name this one exception. |
| F4 | LOW | The Option-2 kill's ground 3 (volatile registry snapshot) is correct, but the same race, in miniature, also touches Option 1: `TriageAggregationService.aggregate` reads `registry.all()` twice (fan-out `:99`, collect `:109`; `perEngine.put()` in the second loop, `:113`), so `fleet` would record collect-time scope, not fan-out-time scope | **Fixed.** §16.5's "Value" bullet corrected to name the imprecision; one-line hardening (capture the list once, drive both loops from it) added to §16.8 item 2 so the claim becomes exact. |
| F5 | LOW | §16.9's BUILD NOW argument leaned on a live-demo `GET /api/engines` observation that cannot be reproduced from the repo and is in tension with `15cca3d`'s own body; the argument is stronger without it, and a dominance argument was never stated | **Fixed.** §16.9 re-anchored on §14.2's repo-verifiable era start (2026-08-04T15:39 Z, earliest G5 ≈ 2026-09-29); the demo observation demoted to corroboration in §16.2 (moves the date by one day, not fifteen); added the dominance argument (point 5) and the conventional-vs-mechanical reset distinction ("absorbed" softened). |
| Nit | — | `IncidentDetail.Occurrence` should read `IncidentDetail.OccurrencePoint` (§16.3, §16.6 C5) | Fixed, both occurrences, with the `dto/IncidentDetail.java:72` citation added. |
| Nit | — | §16.6 C2's rejection reason ("freeze every class's lane for `dwellCycles`") is overstated — a boundary gate skips ONE cycle (`DwellStateMachine.java:46-54`) | Fixed — conclusion kept, reasoning replaced with the correct one (C4 already scope-cleans the evidence upstream). |
| Nit | — | An unnamed C2 consequence: after a fleet change, C4 voiding drops spells, `n` falls, and the displayed self-heal lane can visibly move because of a registry edit | Added to §16.6 C4 as a named, honest consequence. |
| Nit | — | §16.5's migration has no `lock_timeout` | Noted in §16.5 as a repo-wide question (V21 shipped the identical gap on the identical table), explicitly not this design's to solve. |

Author's own adversarial notes, recorded because they shaped the design before any review
(the §14.6 precedent — these are not a seat and grade nothing): (i) the first draft's C3
predicate would have let two adjacent backfilled `''` rows compare equal — closed by the
"comparable to nothing, itself included" rule in §16.5; (ii) scope-gating the C2 dwell tick
was drafted and withdrawn as a lane-freezer (§16.6 C2); (iii) the birth-row COALESCE
self-compare was checked against the F3 seeding rule specifically so a birth inside a
recorded fleet still counts once (§16.6 C3, pinned by a §16.8 fixture).

### 16.11 Build-slice record — #372 observation SCOPE (★ BUILT)

What landed against the §16.8 contract, what the failing-before runs actually printed, and the
deviations, named. **This slice is NOT "no behavior change"** (§16.8 item 6): C3 runs only under
the default-false flag and `AttentionOrderingNeutralityTest` is untouched and green, but **C4
feeds the DEFAULT-ON self-heal computation**. The C4 change is monotonically conservative — it
can only VOID spells toward `INSUFFICIENT_HISTORY`, never mint one — and on the pilot it is
measurably ≈ nothing (zero unconfounded completed spells exist to void, RETRYING-RISK-LANE §8).

#### 16.11.1 Failing-before proof (the §13/§15.1 convention — a wrong value, not "it did not compile")

Rung-1 and rung-4 numbers below were produced by running the new fixtures against the SHIPPED
predicates (the `fleet` terms removed from `arrivalsSince`, the scope checks disabled in
`RetrySpellExtractor`), so every one is a WRONG VALUE the base actually printed, not an
absence-of-symbol.

| Rung | Fixture | What the BASE produced |
|---|---|---|
| Pure static | **the headline** — §16.2's scenario: retrying jobs on `engine-b`, dead-letters on `engine-a`, `engine-b` DISABLED at the closing zero; every quality marker clean | `outcome=SELF_HEALED gapVoided=false countable=true excluded=false truncationTainted=false` — a **fabricated self-heal minted by a registry edit**, fully countable into `n` |
| Pure static | fleet change INSIDE the run ⇒ voided | `gapVoided=false` (spell judged normally) |
| Pure static | look-ahead from a different fleet ⇒ voided | `gapVoided=false`, outcome judged against a non-comparable DLQ level |
| Pure static | unrecorded `''` anywhere in the shape ⇒ voided | `gapVoided=false`; the all-`''` series returned `SELF_HEALED` |
| Rung 4 (real Postgres) | re-enable edge across a fleet change must NOT be banked (settled 100 → 1000 when a second engine returns) | **`arrivals = 900`, `observed = 3`, `trusted = 3`** — 900 phantom arrivals on rows all stamped trusted |
| Rung 4 | disable edge: growth in the NEW era still measured, the boundary delta not trusted | `trusted_samples = 3` (the boundary counted as trusted); expected 2 |
| Rung 4 | adjacent `''`/`''` pair not comparable (birth +10, then two unrecorded rows growing by 20) | **`arrivals = 30`** — the `''`-to-`''` delta banked; expected 10 |
| Rung 4 | a birth row that never recorded its OWN scope is not an arrival | **`arrivals = 5000`**; expected 0 |
| Rung 4 | the #365 burst bins inherit the discipline (re-enable inside the flood window) | **`arrivals = 900`** with the 880 landing in `burst_arrivals` — a registry edit read as an alarm FLOOD, i.e. top-of-order promotion |

Everything else in the new suites is a guard written to fail on the base by construction (the
`fleet` column, `AggregationSample.fleetEngineIds`, `FleetScope` and the `until` finder did not
exist). `burst_arrivals ≤ arrivals` is asserted on every fixture by the shared helper, including
all the new ones.

#### 16.11.2 What landed

- **`V22__incident_occurrence_fleet.sql`** exactly as §16.5 — `ADD COLUMN fleet text NOT NULL
  DEFAULT ''` (metadata-only on PG ≥ 11, cascading to the monthly partitions and the DEFAULT
  catch-all), the column COMMENT verbatim, **no backfill UPDATE**. V22 verified next free.
  `IncidentOccurrence` gains the mapped field (`ddl-auto=validate` holds); the `IncidentOccurrenceId`
  PK is untouched.
- **`FleetScope`** — the one static canonicalizer (sorted, de-duplicated, comma-joined;
  `UNRECORDED = ""`), with the comma guard degrading to `UNRECORDED` + WARN rather than recording
  an aliasable string. `AggregationSample` gains `fleetEngineIds` + `canonicalFleet()`;
  `PollingSnapshotSource` fills it from the envelope key set it already iterates (**zero new engine
  calls**); `IncidentLedgerService` canonicalizes once per group and stamps it on every occurrence
  row, blind ones included.
- **The atomic snapshot (§16.8 item 2 / F4).** `TriageAggregationService.aggregate` now captures
  `List<EngineConfig> fleet = registry.all()` ONCE and drives BOTH the fan-out loop and the collect
  loop from that list, so `fleet` and `cycleComplete` describe one registry read. `registry.all()`
  builds a fresh immutable list off a volatile map on every call, so the previous double read could
  genuinely straddle an `EngineRegistry.reload` — precisely at the composition boundary where the
  marker matters most.
- **C3** — three terms added to the existing `trusted` predicate (`o.fleet <> ''`, and
  `COALESCE(LAG(o.fleet) OVER w, o.fleet) = o.fleet`), same single native pass, no new statement.
  The birth row self-compares so FIX 3 survives; the #365 burst bins inherit by filtering the same
  `d.trusted`; the F2 counting rule is untouched (a scope-discarded delta counts in
  `observed_samples`, not in `trusted_samples`, so a wholly scope-broken window degrades to
  `F = 1`, never to a fake 0).
- **C4** — `findSpellShapeRowsDescending` projects `fleet`; `SpellSample` carries it;
  `RetrySpellExtractor` gap-voids a span containing an unrecorded or a second distinct fleet, and
  refuses to judge an outcome against a look-ahead from a different fleet. One more reason feeding
  the existing `gapVoided` flag — no new outcome, no new field.
- **C5** — `IncidentDetail.OccurrencePoint.fleet`; `npm run gen:api` re-run against a running BFF
  and the two-line `schema.d.ts` diff committed. No frontend component changed (era-boundary
  rendering is the named non-goal).
- **Item 7** — an optional `until` cursor on `GET /api/incidents/{id}`: `[until − clamped, until)`,
  half-open so chained pages never repeat a row at the seam; a call WITHOUT it takes the identical
  pre-#372 path (pinned by a test that verifies the old finder and asserts the new one is never
  touched). `scripts/replay-burst-attention.py` gained an era-boundary section that walks the
  cursor backward (`ERA_PAGES`, default 3) when no boundary is visible in the newest window.
- **C1 and C2 UNCHANGED**, as §16.6 requires. `DwellStateMachineTest` and
  `AttentionOrderingNeutralityTest` are byte-untouched and green.

#### 16.11.3 Deviations from §16.8, named

1. **`IncidentLedgerServiceTest` could not stay byte-untouched.** §16.8 item 4 asks for the
   zero-state suite "untouched and green". `IncidentOccurrenceRepository.upsert` gained a column,
   and that class asserts on it through Mockito — including the zero-state suite's own
   `verify(occurrences, never()).upsert(...)`, whose matcher list is arity-coupled to the method.
   The edit is therefore **arity-only**: every positive verification gained the expected scope
   string (`""` — the samples in that class state no scope, so the fail-closed value is the
   correct expectation) and the matcher-based ones gained `anyString()`. No assertion's meaning,
   subject or expected behavior changed, and the suite is green. There is no way to add a column
   to a mocked method's signature and leave its verifications textually unchanged; a `default`
   7-arg overload would have compiled but made the verifications assert a call production no
   longer makes, which is strictly worse.
2. **§7's G5 row is re-measured as a RULE, not as a date.** §16.8 item 5 asks for the G5 row
   "re-measured" and `AttentionScoreService`'s javadoc "refreshed against the post-deploy measured
   era start". That instant does not exist yet — V22 has not run — and inventing it would be the
   fabrication §16.5 refuses. Both places therefore state the mechanically-certain part (G5 resets
   to 0 d; earliest ≈ V22 deploy + 56 d; the reset is mechanical, not conventional) and record the
   exact era-start extraction as OWED, to be run with the §5 REST method once the migration has
   deployed.
3. **The comma guard fails CLOSED rather than merely warning.** §16.5 specifies an
   "assert-and-warn". `FleetScope` warns AND returns `UNRECORDED`, because a scope string that
   cannot be decoded unambiguously must not be recorded as if it could: an unrecorded scope
   discards deltas (safe), an aliased one would silently compare two different fleets as equal
   (the exact failure the column exists to prevent). Unreachable in practice — both id patterns
   foreclose `,` — so this only changes what happens if that invariant is ever broken.
4. **`scripts/replay-burst-attention.py` prints an era-boundary section rather than only walking
   the cursor.** §16.8 item 7 asks the script to "walk the cursor when hunting an era boundary";
   the boundary detection it needs to decide when to stop paging is the same logic a reader wants
   reported, so it is emitted as section 0 of the replay output.

## 17. Correction round — `M ≡ 1` in the v1 ordering (#399, epic #398, ★ LANDED)

Successor to **#382**, which was closed `not_planned` on 2026-08-05 with three findings
deliberately left on the record ("reopen this, or open a successor, if the flag is ever
reconsidered"). This is that successor's first slice. Evidence base:
`docs/reviews/R-ATTENTION-HARM-2026-08.md` (PR #383, `3e17f9f`), reproducible via
`scripts/attention-harm-search.py`. **`inspector.triage.attention-ordering` stays default-false
for the whole epic** — nothing here flips the flag or moves the §7 gate; this round makes the
shipped-but-inert score honest before the gate conversation may be reopened.

### 17.1 What was wrong

§4.1 shipped `A(c) = F·R·M·S` with `M = clamp(medMTTR(c)/medMTTR(fleet), 0.5, 2)`, and never said
what `medMTTR` *is*. #382 framed that as a two-way ambiguity — operator **service time** (⇒ the
multiply is backwards and Smith's rule applies, i.e. shortest-job-first, i.e. `1/M`) or a
**severity weight** (⇒ the multiply is right) — and could not decide it from the document.

**Read from source, it is neither.** The three stamps that define the statistic:

| Stamp | Written by | Meaning |
|---|---|---|
| `started_at` | `IncidentLedgerService` — `new IncidentEpisode(id, OPEN, seenAt, total)` on the pass that first records the class (and again on each automatic regression) | the sampler's **first sighting** |
| `ended_at` | `IncidentLifecycleService.resolve` → `IncidentEpisodeRepository.closeEpisode` — the **S3 resolve verb**, and nothing else in the codebase | an **operator click** |
| the value | `closedEpisodeDurationSeconds()`: `EXTRACT(EPOCH FROM (ended_at − started_at))`, `WHERE ended_at IS NOT NULL` | first-sighting → operator-resolve |

So `medMTTR` is time-to-resolution measured from incident BIRTH, and it therefore **contains the
operator's queue wait** — the very quantity the ordering allocates. Two consequences, neither of
which depends on the harm search's invented distributions (its §8):

1. **It is endogenous.** Rank a class low → it waits longer → its measured MTTR rises → `M` rises
   → next cycle it ranks higher. Under the shipped multiply that loop is **negative**
   (self-correcting, so nothing explodes — this is why the shipped behavior was never an
   incident) — but it means `M` was substantially measuring **the queue**, not the class.
2. **Inverting to `1/M` flips that loop positive.** Rank low → longer TTR → smaller `1/M` → rank
   lower still. That is a **starvation** mechanism, and it would have been introduced by the very
   fix the harm search appears to recommend. The report's Smith reading is therefore **rejected**
   — on the endogeneity ground, independently of any argument about its simulated distributions.

### 17.2 The decision, and the seat that checked it

**Option C: `M ≡ 1` in the v1 ordering; estimator and config knobs retained; `M` returns only
behind an uncontaminated estimator.** Precedent is already in this document: §3.1 excludes
`eff(c)` from the v1 score for an analogous honesty problem ("a constant factor with an honesty
problem"), while keeping it in the conceptual model `B(c)`.

Verified by an independent second panel seat (§10 convention; `gemini-3.5-flash` —
`gemini-3.1-pro-preview` returned 429 and the Copilot seat is permanently 410/dead). It reached
the same verdict, called option D ("split the estimator in this same round") a fantasy because
first-operator-touch needs new telemetry plus a schema change, and named the falsifier recorded
in §17.4. **One correction to that seat's reasoning, recorded for honesty:** it described the
option-A loop as "chaotic"; the sign is negative/self-correcting, which is precisely why nothing
is on fire today.

### 17.3 What landed

- **`AttentionScoreCalculator`** — `double score = frequency * recency * selfHealFactor`. `M` is
  not a term. `mttrFactor()` is unchanged and still called; the class javadoc carries the
  endogeneity argument rather than pointing at this document alone.
- **`factors.mttr` keeps reporting the real clamped ratio** — the deliberate call, not an
  omission. It is honest evidence about the class (and the rationale already quotes its median),
  the retained estimator is what makes re-entry a one-line change, and reporting a hardcoded
  `1.0` would have hidden a measurement to describe a rule that is documented instead. The wire
  shape is unchanged: no field added, none removed, `schema.d.ts` untouched. The single consumer
  that DID assert this factor moved a card — the `AttentionBadge` glossary tooltip — is corrected
  (§4.3); no other consumer of `AttentionFactors.mttr()` makes an ordering claim (grepped across
  `backend/src` and `frontend/src`; the frontend never renders `factors.mttr` at all).
- **Rationale copy** — "typically takes 4 h to resolve" → "typically 4 h from first sighting to
  resolve" (§4.3 correction). Same statistic, same one-line `·`-separated shape; the claim now
  matches what was measured.
- **Config** — `min-closed-episodes`, `mttr-clamp-low`, `mttr-clamp-high` all retained, with
  `application.yml` and `InspectorProperties` now saying what they do and do NOT move.
- **Docs in lockstep** — §4.1 (definition debt paid), §4.3 (both copy corrections), §7 (what G1
  gates now), SPECIFICATION §2.4 and ARCHITECTURE §4's `GET /api/triage` row (the formula and the
  `M` clause), OPERATOR-QUICK-START's "Reading the attention ranking".
- **Untouched, deliberately:** the flag, the §7 gate values, `AttentionOrdering`, the S factor
  (slice 2), `scripts/attention-harm-search.py` (slice 3 re-pins it to the corrected Java), and
  every Stage 0 query. Zero new engine calls, zero new statements, no migration.

**Failing-before proof (the §13/§15.1 convention — a wrong value, not "it did not compile"):**

| Rung | Test | What the BASE produced |
|---|---|---|
| Pure static | `twoClassesDifferingOnlyInClosedEpisodeDurationsScoreIDENTICALLYBecauseTheOrderingDoesNotConsumeM` — two classes identical in arrivals (5), age (0) and lane (none), differing ONLY in their three closed-episode durations (3×999 999 s vs 3×1 s against a 3 600 s fleet median), must now both score `log2(6) = 2.584962500721156` | **5.169925001442312** vs **1.292481250360578** — the full 4× spread of the `[0.5, 2]` clamp, produced by nothing but how long an operator had taken to click resolve |
| Pure static | `theScoreIsTheProductOfFrequencyRecencyAndSelfHealWithMDeliberatelyLeftOut` — `F=2 · R=0.5 · S=0.5` must be `0.5` | **1.0** — the same fixture, doubled by `M = clamp(7200/3600) = 2` |

`AttentionOrderingNeutralityTest` is **byte-untouched and green** (12 tests): an empty ledger
still collapses every score to 0.0 and the tie-break still reproduces count-only ordering exactly.
It could not have caught this change either way — with no closed episodes `M` was already 1 — which
is the point of pinning the new proof on a populated fixture instead.

### 17.4 Re-entry condition, and the falsifier that would reverse this

`M` returns to the ordering only behind an **uncontaminated** estimator: measure from
**first-operator-touch** (a new telemetry point — nothing today records when an operator first
looked at a class), or subtract the queue wait from the existing span. Either is a schema plus
ingestion change and is explicitly out of scope for this epic.

**The falsifier, recorded so a future round can execute it rather than re-argue it:** if median
**time-to-touch** (first sighting → first operator interaction with the class) turns out to be
**< 5 % of median episode duration**, then the queue wait is a rounding error inside `medMTTR`,
the endogeneity is noise, and the report's Smith reading becomes live again — at which point the
correct move is `1/M`, not the status quo ante `M`. That measurement is not possible today (see
above: the touch timestamp does not exist), and G1 is 0 anyway. Deriving a touch proxy from the
audit tail (first corrective action on an engine the class touches, the same over-inclusive join
`EpisodeActionAttributionService` already runs) is the cheapest honest approximation and is the
suggested first step — as an *approximation*, named as one, not as the estimator itself.

### 17.5 What this round deliberately does NOT do

- **It does not flip the flag, and it does not move the §7 gate.** Both stay exactly where they
  were; §7's measured values are unchanged by this round.
- **It does not remove the M estimator, the clamp knobs or the wire field.** Deleting them would
  make re-entry an archaeology exercise and would drop honest evidence from the operator's view.
- **It does not touch `eff(c)`, `S`, or the burst gate.** `S` is slice 2 of #398, in a parallel
  branch.
- **It does not re-run the §8 A/B usability arm.** The §8.8 fixture separated its two classes
  with `M` alone, so it can no longer produce the reordering it was built to test (forward
  pointer recorded there, with the recomputed numbers). Rebuilding that fixture on `F`/`R`/`S`
  separation is a follow-up for whoever next executes §8, not this slice.
- **It does not re-run the harm search.** Slice 3 re-pins `scripts/attention-harm-search.py` to
  the corrected Java and records the delta in `docs/reviews/R-ATTENTION-HARM-2026-08.md`; until
  then that review's tables describe the PRE-#399 score and say so nowhere — read them as the
  record of the round that produced this one.
- **It does not rewrite earlier sections' worked examples.** §8, §11 and §12's records quote
  `A(c) = F·R·M·S` as the formula shipped at the time. They are records of their rounds; §4.1 and
  this section carry the current formula.

## 18. Correction round — the `S` factor: an inert floor and a blind spot (#400, ★ LANDED)

Slice 2 of the #398 correction round (slice 1, the `M` neutralization, is §17). Two defects in one factor, both left on the record by #382
and both re-verified against the shipped source before anything was changed. Behaviour with
`inspector.triage.attention-ordering` OFF is unchanged and still provably inert;
`AttentionOrderingNeutralityTest` is untouched and green.

| # | Defect | Severity | Fix | Failing-before proof |
|---|---|---|---|---|
| S1 | **The `self-heal-floor` knob is inert and was advertised as a rail it does not deliver.** `S = max(floor, 1 − p_heal)` with `P_HEAL_LIKELY = 0.75` bottoms out at `max(0.25, 0.25)` — the default floor is *exactly* the lowest value lane quantisation can produce, reached from above, so it is never selected. Removing it entirely was bit-identical across all 30 700 simulated fleets. The "demoted at most 4×, never zeroed" guarantee is **true**, but delivered by quantisation, not by the clamp | honesty gap (no behaviour change) | **Knob kept, claim corrected in all six places** (§4.1's bullet, `OPERATOR-QUICK-START.md`, `application.yml`, `InspectorProperties`, `AttentionScoreCalculator`, `RetrySpellExtractor`). A floor **strictly above 0.25** *does* bind and weakens demotion — a legitimate deployment lever, and why deleting it was refused | Part A changes no behaviour, so its test **passes on the base by construction** — and that is the point: `theSelfHealFloorIsAProvableNoOpAtOrBelowTheLaneQuantisationMinimumAndBindsOnlyStrictlyAbove` pins *both* halves of the boundary (`≤ 0.25` ⇒ byte-identical over 4 floors × 4 lanes × 5 timings via `doubleToRawLongBits`; `> 0.25` ⇒ binds and raises `S`) so the false attribution cannot drift back into the copy |
| S2 | **`S` read `p_heal` and never `t_heal`** — a class that heals *eventually* was demoted exactly as hard as one that heals *in a minute*. Measured at 80.73 % harm / 0.00 % help past ~8 service times (§4c), with count-only outright optimal there | **HIGH** (latent — flag-off) | §4.1b: `S = max(floor, 1 − p_heal · 2^(−t_heal/τ_heal))` over the already-served `SelfHealStats.ttsP50Seconds`, `τ_heal = attention.self-heal-horizon` (new, PT1H). Only ever weakens a demotion; byte-identical at `t_heal = 0` | On the base, `selfHealFactor(SELF_HEAL_LIKELY, …)` returned **0.25** where an 8-hour median heal must read **0.9970703125**, and **0.25** again where an *absent* `ttsP50` must read **1.0**; a MIXED class healing at the horizon returned **0.5** where it must read **0.75**. Three wrong values, not a compile error |

**Named correction to the evidence base.** `docs/reviews/R-ATTENTION-HARM-2026-08.md` §6 states
the `self-heal-floor` key "does nothing at any value below 0.75". The binding boundary is
**0.25**, not 0.75: at `floor = 0.5` the clamp already selects for both `SELF_HEAL_LIKELY` (0.25)
and `SELF_HEAL_MIXED` (0.50). The report's *headline* claim — the shipped 0.25 default is inert,
bit-identically — is correct and is what this section acts on; only the stated width of the
inert band was too wide. Recorded here rather than rewritten there: the report is evidence of
record, and slice 3 (#398) re-runs it against the corrected Java.

**Named correction to RETRYING-RISK-LANE.md §4.2.** That section closed with "only the LANE
itself (the base clause's fraction/timing) is governed by rules 1-5's dwell/stability machinery".
Read from source, the parenthetical is false: `SelfHealStatsService.get()` runs **only** the lane
through `DwellStateMachine` and pairs it with the raw per-cycle `n`/`healed`/`ttsP50`/`ttsP90`
straight off the 60 s Caffeine cache. This was harmless while the timing was pure copy; it stops
being harmless the moment §4.1's stabilization contract has to be honoured by a *score*
consumer, which is what #400 part B makes it. Corrected in place there, and §4.1b above states
exactly which half of the artifact is stabilized, which is not, and why the non-stabilized half
is admissible as a continuous weight (never as a threshold).

**Scope discipline.** No Flyway migration, no new engine call, no new DTO field (therefore no
`gen:api` regeneration and no frontend change), no card hidden.
`inspector.triage.attention-ordering` still defaults false. `mttrFactor` / the `M` term (#399)
and `scripts/attention-harm-search.py` (slice 3) are untouched by this slice.
