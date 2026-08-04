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
flipping it requires re-measuring §7 with the §5 method. **§8's usability A/B run plan is now
AUTHORED (issue #366, 2026-08-04) — comprehension probe + operator doc (R-SEM-25,
`docs/usability/MISSIONS.md` M13, `docs/OPERATOR-QUICK-START.md`) specified end to end, run
NOT YET EXECUTED (§8.8): sequenced to follow issue #365 (a burst-term amendment to this same
score, IN PROGRESS) and gated further by issue #359 (transiently-failing seed corpus, IN
PROGRESS) for full self-heal-lane coverage.**

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
  rule #347 owns, inherited here by construction).
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

### 5.6 What the pilot CAN already calibrate (MEASURED)
Sampler cadence 60 s and 99.8 % series coverage (estimation windows in §6 are trustworthy);
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
| G5 | ≥ 56 d of current-generation ledger span (28 d fit + 28 d holdout) | 14.8 d | **NO** |

**Gate status: NOT MET (0 of 5).** Earliest G5 satisfaction ≈ 2026-09-14; G1/G4 depend on
operators actually resolving/acking — which the pilot's audit tail (no action in 8 days)
shows is not yet routine. Until the gate: the score computes with neutral M/S (provably
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
5. **What is NOT achievable pre-#359, and must not be faked**: the canonical "large
   self-healing class demoted below a small costly one" story needs the `S` factor
   non-neutral, which needs a class actually reaching `SELF_HEAL_LIKELY`/`MIXED` —
   blocked on #359 (§8.6). Until #359 lands, every live class's `S` stays neutral (1) in
   this fixture, same as the measured pilot (§5.5) — so a pre-#359 run's ordering
   divergence is attributable to `F`/`R`/`M` alone. The run's own report must state this
   explicitly; do not claim the S-driven story was exercised until it actually was.
6. Confirm at least one live `SelfHealBadge` exists for task /c (any reachable lane — see
   §8.6): the standard `demoFailingRetry` cohort or the freshly-planted timer-backed class
   will show `INSUFFICIENT_HISTORY` or, once ≥1 spell completes, `SELF_HEAL_UNLIKELY`
   (every seeded process fails permanently, so it never heals).
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

The existing `TESTER_SCHEMA` (`.claude/workflows/usability-run.js`) has no timestamp
field today; whoever wires this run adds one (a one-line schema+prompt addition, not a
product behavior change):

- Tester instruction: immediately after `browser_navigate('/')` succeeds, call
  `browser_evaluate(() => new Date().toISOString())` and record the result as
  `landingIso`; immediately after the first successful navigation into the planted
  class's detail page (task /a's drill), call it again and record `firstDrillIso`. Both
  ride on M13's task entry as `timings: {landingIso, firstDrillIso}`.
- `time-to-first-relevant-card = firstDrillIso − landingIso`, computed by the evaluator
  (reconciler agent) per tester, per arm.
- This is wall-clock as the AGENT experienced it (its own "thinking" time included) — an
  honest proxy for "how many dead-end interactions before landing on the right one," not a
  claim about human-operator seconds. Report it alongside `interactions` (already
  tracked) and treat a step-count regression the same way nightly stats already do
  (GOAL-CATALOG.md RUN PROTOCOL "Nightly statistics": "a 4-step task becoming 9 steps is a
  regression even while green").

### 8.6 Self-heal lane reachability — the #359 dependency (record honestly)

| Lane | Reachable today (pre-#359)? | Why |
|---|---|---|
| `INSUFFICIENT_HISTORY` | **yes** | default state; needs no completed spells at all |
| `SELF_HEAL_UNLIKELY` | **yes** | every seeded process fails PERMANENTLY by construction — spells complete, none heal |
| `SELF_HEAL_MIXED` | **no — blocked on #359** | needs some spells to heal and some not; no seed process ever heals today |
| `SELF_HEAL_LIKELY` | **no — blocked on #359** | needs most spells to heal; #359 (the transiently-failing seed corpus) is IN PROGRESS on another branch, not yet landed |

Task /c's example copy (RETRYING-RISK-LANE.md §4.1's worked example, "usually self-heals
(12/14, typically ≤ 8 min)") illustrates `SELF_HEAL_LIKELY` specifically — a run before
#359 lands can only exercise /c against `INSUFFICIENT_HISTORY` or `SELF_HEAL_UNLIKELY`
copy, and the S-driven ordering story (§8.2 step 5) stays neutral throughout. The verdict
(§8.8) MUST state which lane(s) were actually met that run, never silently claim full
coverage.

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
