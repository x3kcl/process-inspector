# RETRYING risk lane — self-heal evidence for "it may self-heal" (R-BAU-11 candidate)

Status: **DESIGN** — research track R2 (#347, umbrella #356); panel pass 2026-08-04 (one
seat reviewed, one honestly vacant — §12); gates build slices #351 (stats API) / #352
(badge + risk-ranked view) · **data-maturity gate NOT MET as of 2026-08-04** (§7; measured
baseline `reviews/R2-SELFHEAL-BASELINE-2026-08.md`)

## 0. Provenance

Drafted 2026-08-04 against a live measurement of the pilot deployment's incident ledger
(the §8 baseline — method and raw numbers in
[reviews/R2-SELFHEAL-BASELINE-2026-08.md](reviews/R2-SELFHEAL-BASELINE-2026-08.md),
reproducible via `scripts/measure-selfheal-baseline.py`). Literature basis (DOIs verified,
#356 bibliography):

- **Teinemaa et al., *Outcome-Oriented Predictive Process Monitoring: Review & Benchmark*,
  ACM TKDD 13(2), 2019** ([10.1145/3301300](https://doi.org/10.1145/3301300)) — across 11
  methods, simple aggregate encodings are a strong baseline and fancy models rarely justify
  themselves. This is the argument that **v1 is descriptive statistics, NOT ML**: a trained
  model is vNext, gated on §9 proving descriptive stats insufficient.
- **Teinemaa et al., *Temporal Stability in Predictive Process Monitoring*, DMKD 32, 2018**
  ([10.1007/s10618-018-0575-9](https://doi.org/10.1007/s10618-018-0575-9)) — predictions
  shown to humans must be optimized for stability across refreshes, not accuracy alone.
  This makes the §4 stability rules **normative**: a flapping badge is worse than no badge.

Panel review (repo convention): see §12 — one seat reviewed (Gemini 2.5 Flash; Pro
quota-blocked), one seat honestly vacant (copilot MCP endpoint down, no substitute used);
adopted/rejected dispositions recorded there.

## 1. Problem

SPEC §0 defines **RETRYING** as "the engine will retry automatically; it may self-heal" —
and gives the operator zero evidence about whether this class actually does. The 3am
question the chip cannot answer: *"can I ignore this RETRYING card, or is it a FAILED card
in waiting?"* Everything needed to answer it descriptively is already persisted:

- `incident_occurrence` records `retrying_count`/`dead_letter_count` per class per 60 s
  sampler bucket (INCIDENT-LEDGER §3.3) — the class's transitions through the RETRYING
  state are reconstructible after the fact.
- `incident_episode` records open→resolve cycles; bulk/audit rows record which classes
  operators acted on.

What is missing is a **derived, per-class, honestly-caveated statistic** — not new data.
The measured baseline (§8) additionally shows the pilot history is far too thin to show a
rate today, which is itself a design input: the feature must be built around an explicit
"insufficient history" state that is expected to be the common case for a long time.

## 2. What already exists (REUSED, not rebuilt)

| Capability | Where | Reused as |
|---|---|---|
| Fingerprint identity `(signature_hash, algo_version)` | `ErrorSignatureNormalizer` (R-SEM-03) | the class key, verbatim — same generation-orphaning rules as acks/incidents |
| Per-class minute time-series with `retrying_count` + `truncated` | `incident_occurrence` (INCIDENT-LEDGER §3.3, 400 d retention) | the ONLY evidence substrate for spell extraction — zero new engine calls, zero new tables |
| Episode records (open→resolve, MTTR) | `incident_episode` | the "closed with/without action" statistic once resolves exist |
| Corrective-action provenance | envelope + per-item audit rows (R-AUD-10); `relatedBulkJobs` join | confound detection (§3.3) — audit-side, wider than the ERROR_CLASS-only HTTP join |
| Truncation honesty end-to-end | occurrence `truncated`, `lastTruncated`, R-SEM-12 | spell taint + badge caveat propagation (§5) |
| Sampler beat + complete-cycle marker | `AggregationSampledEvent`, `cycleComplete` (#302) | the refresh clock for the §4 dwell rule; gap detection |
| Scope-filtered reads | R-SAFE-17 projection doctrine | the badge renders only where the class is already visible |

## 3. The statistic — v1 is descriptive, NOT ML

### 3.1 Unit of evidence: the completed RETRYING spell

A **retrying spell** for class `(signature_hash, algo_version)` is a maximal run of
occurrence samples with `retrying_count > 0`, ended by a sample with `retrying_count = 0`.
Its **outcome**, judged over the spell **plus one bucket after its end** (a job exhausting
its last retry leaves RETRYING and lands in the DLQ around the bucket boundary; without
the +1 look-ahead that escalation could be misread as a heal — panel G2):

- **SELF-HEALED** — `dead_letter_count` did not increase: the retrying jobs left the
  failing state without dead-lettering. A non-zero DLQ *at spell start* does NOT disqualify
  (a class routinely carries standing dead-letters; the delta is the evidence — requiring a
  clean DLQ would exclude nearly every real spell).
- **ESCALATED** — `dead_letter_count` increased: retries exhausted into the DLQ.

A spell is **excluded from the statistic** when it is:

- **confounded** — it overlaps (±2 sampler buckets) a retry-shaped corrective action that
  could have produced or drained it (§3.3): evidence about operator retries, not autonomous
  healing;
- **gap-voided** — the series has a sampling gap > 5 buckets inside it (sampler/BFF down;
  the spell's shape is unobserved);
- **truncation-tainted** — any sample in the spell has `truncated = true` (a truncated
  sample is a floor, not a count — the spell's outcome cannot be trusted).

Exclusions are counted and surfaced (§5), never silently dropped.

**Resolution floor (measured, §8):** a spell shorter than one 60 s sampler bucket is
invisible — proven live by 8 retried jobs re-dead-lettering within one bucket and never
appearing as `retrying_count > 0` — and a single zero-bucket dip can split one logical
spell into two (spells are NOT merged across a zero bucket; the unit is observational,
not a reconstruction of engine internals). Every statistic is conditioned on "lasted
≥ 1 bucket"; durations carry ±1-bucket uncertainty; the badge copy never claims to count
retries or sub-minute heals, only observed spells (panel G1/G4).

### 3.2 Per-class statistics (the whole v1 model)

Over a trailing window (`inspector.selfheal.window-days`, default 90, ≤ the 400-day
occurrence retention), per class:

- `n` — unconfounded completed spells; `healed` — those that SELF-HEALED.
- **Self-heal rate** — `healed / n` with a **Wilson 95% score interval** (never a bare
  point estimate; the interval is what the §4 lanes read). Known caveat, stated on
  purpose: spells of one class are not independent Bernoulli trials (one underlying
  outage produces correlated spells), so the interval is an honest *heuristic* bound, not
  an exact one — another reason the lanes read intervals with a hysteresis band rather
  than acting on point estimates (panel G13).
- **Time-to-self-heal** — p50/p90 of *observed* spell duration among SELF-HEALED spells,
  at the 60 s resolution (±1 bucket; sub-minute heals are unobservable and absent from
  the distribution — copy therefore always says "typically ≤ X", never an exact time).
- **Episode statistic** (secondary, once resolves exist): episodes closed with zero
  attributable corrective actions vs. with — the coarse-grained complement, currently
  uncomputable (zero episodes have ever closed, §8), and subject to the SAME §3.3
  attribution limitation (an unattributable FILTER-scope retry can make a
  human-remediated episode look untouched — the statistic must carry that caveat
  wherever rendered; panel G6).

Computation is **derive-on-read** from the occurrence + audit stores (repository-level,
not the presentational 30-day HTTP window), Caffeine-cached per class. The cache key must
cover EVERYTHING the statistic reads — (class, last completed spell, latest relevant
audit row) — since a late-arriving retry audit row can retroactively confound a spell; a
short TTL aligned to the sampler beat (60 s) is the acceptable simple implementation, a
key on the last spell alone is not (panel G5). No new tables, no new engine calls, no new
persistence in v1.

### 3.3 Confound attribution (and its measured limitation)

The live `relatedBulkJobs` join only attributes **ERROR_CLASS-scoped** bulk retries to a
signature (the envelope audit payload carries the hash). Proven live (§8): a FILTER-scoped
bulk retry drained incident 4's DLQ while its `relatedBulkJobs` stayed empty. The #351
stats service therefore detects confounds **audit-side**, conservatively: any successful
`retry-job` audit row (single or bulk item, any scope) whose engine hosts the class,
overlapping the spell window ±2 buckets, confounds the spell. This over-excludes (a retry
against a *different* class on the same engine also confounds) — accepted for v1: a
too-small honest `n` beats a contaminated rate. The residual bias is unquantified in both
directions (exclusion drops genuinely-autonomous spells that merely coincided with
unrelated retries; a missed confound would inflate apparent autonomy) — the displayed
rate is therefore explicitly *"of spells with no operator interference observed"*, and
the exclusion count is always surfaced beside `n` (panel G3). Exact per-signature
attribution of non-ERROR_CLASS retries would require stamping signatures into per-item
audit rows at action time — a candidate #351 follow-up, not assumed here.

### 3.4 Why not ML (v1)

The TKDD benchmark's headline is that simple aggregate baselines are strong and complex
models rarely justified. Here the "model" is a per-class Bernoulli rate with an honest
interval — trivially auditable, explainable in one badge line, and cheap. A trained model
(feature-based, cross-class generalization) is **vNext, explicitly gated** on §9 showing
the descriptive rate fails calibration *at adequate data volume* (§7). No training
infrastructure, no model registry, nothing speculative ships in v1.

## 4. Lanes & stability rules (normative — the DMKD requirement)

### 4.1 Lanes

| Lane | Enter | Exit (hysteresis) | Badge copy |
|---|---|---|---|
| `SELF_HEAL_LIKELY` | Wilson LB ≥ 0.70 | LB < 0.60 | "usually self-heals (12/14, typically ≤ 8 min)" |
| `SELF_HEAL_MIXED` | otherwise (n ≥ floor) | — | "mixed self-heal record (6/11)" |
| `SELF_HEAL_UNLIKELY` | Wilson UB ≤ 0.30 | UB > 0.40 | "rarely self-heals (1/12) — treat like FAILED" |
| `INSUFFICIENT_HISTORY` | n < 10 (the floor, §7.1) | n ≥ 10 | "no reliable self-heal history yet (3 of 10 spells observed)" |

### 4.2 Stability rules (all five are normative for #352)

1. **Completed-evidence-only.** The badge derives from *completed* spells exclusively; the
   live spell never contributes to its own class's rate (a provisional outcome flipping at
   spell end is exactly the refresh-instability DMKD warns against). Evidence — and hence
   the lane — can change at most once per completed spell, not per poll.
2. **Hysteresis (Schmitt trigger).** Lane enter/exit thresholds are asymmetric per the
   §4.1 table (band width 0.10). Arithmetic at the floor (§7.1): one contrary outcome
   after a perfect 10/10 record moves the LB 0.722 → 0.623 — below the naive 0.70 boundary
   (would flip), above the 0.60 exit (holds); a second contrary outcome (LB 0.552) exits
   legitimately. One outcome never flips a lane at the floor; two may.
3. **Minimum dwell — SERVER-side.** A displayed lane change requires the newly computed
   lane to hold for ≥ 10 consecutive **complete** sampler cycles (~10 min at the 60 s
   beat; incomplete cycles — `cycleComplete=false` — don't advance the dwell counter,
   mirroring the regression gate's "observed" doctrine). A computation that reverts
   mid-dwell resets it. The dwell state machine lives in `SelfHealStatsService` and the
   API serves the **displayed** lane, never the raw one — client-side dwell would reset
   on every refresh/tab (the exact instability DMKD forbids) and would let two operators
   see different lanes for the same class (panel G7). Single-instance BFF ⇒ in-memory
   dwell state is acceptable; it re-arms conservatively (from the served lane, at zero
   dwell progress) on restart.
4. **Mid-spell monotonicity.** Risk order for this rule: LIKELY < MIXED < UNLIKELY.
   While a class has a live spell, displayed changes may only move up that order (or
   strengthen the elapsed-time copy per rule 5); risk-decreasing changes are deferred to
   spell end + dwell. Transitions into/out of INSUFFICIENT_HISTORY sit outside the risk
   order (it is an evidence state, not a risk claim) and are governed by the floor +
   dwell only (panel G14). An operator mid-diagnosis never sees the badge relax under
   them.
5. **Elapsed-vs-typical copy.** During a live spell, a LIKELY-lane badge shows "still
   within typical observed self-heal window (p90 X min)" and strengthens — once,
   monotonically — to "exceeded typical observed self-heal window" when elapsed > p90
   ("observed" because the p90 is of ≥ 1-bucket spells, §3.1/§3.2 — panel G8). Copy
   within a spell is a monotone sequence by construction; it never oscillates.

**Backtested (§8):** replaying the full recorded pilot history (45,996 samples), a naive
per-cycle rule produced 2 displayed flips — both premature n=1 verdicts rendered from
operator-confounded evidence; the rule above produced **0** (everything correctly stays
INSUFFICIENT_HISTORY). The measured reduction (2 → 0) demonstrates the
insufficient-history boundary, not damping under volume — §9 pre-registers the at-volume
stability evaluation.

## 5. Honesty rails

- **INSUFFICIENT_HISTORY is a first-class state**, the default and the expected common
  case (§7/§8) — never an empty badge, never a rate rendered below the floor. Copy names
  the progress ("3 of 10 spells observed") so the state is legible, not mysterious.
- **Truncation propagates (SPEC design principle 2, R-SEM-12).** Truncation-tainted spells
  are excluded from `n` (§3.1); when any tainted/excluded spell exists in the window, the
  badge carries the standard truncation marker and its tooltip counts the exclusions
  ("2 spells unmeasurable: truncated scan"). A rate computed over a partially-observed
  window never presents as complete.
- **Informational only — hard rail.** The lane must never gate, relax, reorder, or
  pre-fill any corrective-action rail: no verb availability, RBAC tier, guard,
  confirmation, cap, or bulk behavior reads it; no auto-retry/auto-resolve/auto-ack is
  ever derived from it (corrective-actions doctrine: mutations are never automatic). It is
  render-layer decoration plus a sort key on a read-only view (#352). To be precise about
  the line (panel G10): *informing operator attention is the feature's entire purpose* —
  the risk-ranked view exists to steer human judgment; what is forbidden is any
  machine-side coupling to the safety rails. Enforced by review against the
  corrective-actions checklist; `CorrectiveActionService` and the guard chain must have
  zero references to the stats service.
- **No change to status derivation.** ARCHITECTURE §2.3 is untouched: `hasFailingJobs`
  and the RETRYING chip derive exactly as today; the lane *annotates* the chip, it never
  computes or overrides it.
- **Scope projection (R-SAFE-17).** The badge renders only where the class is already
  visible post-projection. The statistic itself is fleet-wide per class (the
  `lastTruncated` precedent: a fleet-level observation carried on a visible class) — v1
  does not decompose rates per engine; a scoped viewer's badge may therefore reflect
  spells on engines outside their scope. Accepted for v1 (the rate leaks no engine names,
  ids, or counts beyond an aggregate) — and the badge tooltip for a scope-restricted
  caller says so explicitly ("fleet-wide statistic; may include engines outside your
  scope"), never presenting a fleet observation as a scoped one (panel G11).
- **Generation honesty.** An `ALGO_VERSION` bump orphans spell history exactly like acks
  and incidents — a new-generation class restarts at INSUFFICIENT_HISTORY; no cross-
  generation splicing.

## 6. Glossary & SPEC §0 copy additions (spec-sync scope for the build slices)

Ready-to-apply on #351/#352 (this design PR is docs-only and does not touch SPEC):

- **Self-heal** — a RETRYING class leaving the failing state through engine-scheduled
  retries alone, with no operator verb involved. Measured per error class from recorded
  history; displayed as a lane on the RETRYING chip.
- **Retrying spell** — one observed contiguous period (≥ 1 sampler bucket, 60 s) in which
  an error class held jobs in the RETRYING state; ends by self-healing or by escalation to
  dead-letter. The unit of self-heal evidence.
- **Self-heal lane** — the informational badge `usually self-heals / mixed record / rarely
  self-heals / no reliable history yet`, derived from completed retrying spells with a
  minimum-sample floor and hysteresis (RETRYING-RISK-LANE.md §4). Informational only: it
  never enables, disables, or softens any corrective action.
- RETRYING glossary entry gains one sentence: *"The chip may carry a self-heal lane badge
  summarizing this class's recorded tendency to self-heal; 'RETRYING (n/m, auto)' copy is
  unchanged."*

## 7. Minimum sample floor & data-maturity gate (chosen FROM the measurement)

### 7.1 Per-class floor: n ≥ 10 unconfounded completed spells

From the Wilson arithmetic (measured table, baseline §"floor arithmetic"): **n = 9** is
the smallest sample where even a perfect record's lower bound clears the 0.70 LIKELY
threshold (0.701), and symmetrically where a zero record's upper bound clears 0.30
(0.299). Below 9, no evidence whatsoever can honestly claim a decisive lane — showing a
rate there is structurally noise. The floor is **10**, not the mathematical minimum 9,
deliberately: at n=9 a decisive lane sits exactly at its enter threshold (0.701 vs 0.70),
where the very next outcome forces a boundary decision; one spare observation puts the
floor-entry state inside the §4.2-rule-2 hysteresis band's designed behavior (one
contrary outcome holds, two exit) instead of on the enter threshold's knife edge
(panel G15 wording).

### 7.2 Deployment-level data-maturity gate (pilot ship gate for the lane)

The *rate display* ships to the pilot only when the deployment's ledger history satisfies
ALL of:

| axis | threshold | rationale |
|---|---|---|
| history span | ≥ 42 days elapsed with ≥ 95 % of expected sampler buckets present from complete cycles (panel G16) | ≥ 6 weeks covers weekly seasonality in workload/ops patterns; also 2.6× the span that produced today's degenerate baseline |
| class coverage | ≥ 3 classes at the §7.1 floor | one class over the floor proves arithmetic, not a lane worth a view; 3 is the minimum for the risk-*ranked* view (#352) to rank anything |
| fleet volume | ≥ 50 completed spells | keeps the §9 backtest's test split non-trivial (≥ ~15 held-out spells) |
| outcome coverage | ≥ 1 SELF-HEALED **and** ≥ 1 ESCALATED spell | calibration is unmeasurable when only one outcome kind has ever been observed |

**Measured 2026-08-04: NOT MET on every axis** — 16.0 days span, 0 classes at floor
(max n = 0 unconfounded), 2 completed spells fleet-wide (both confounded), 0 self-heals.
At the measured demo arrival rate (0.125 spells/day) the volume axis alone extrapolates to
~400 days, and the outcome-coverage axis to **never**: the demo's seed processes are
deliberately failing by design and structurally cannot self-heal. **The gate is
unsatisfiable on the demo workload; it requires a real pilot workload with genuinely
transient error classes.** Full numbers: `reviews/R2-SELFHEAL-BASELINE-2026-08.md`.

Consequences for phasing — this does NOT block the build slices: #351/#352 build and ship
the machinery, which honestly renders INSUFFICIENT_HISTORY for every class until real
volume accrues (per-class floor self-gates the display). What the gate governs is
*announcing the lane to the pilot* as a triage signal and enabling the risk-ranked view by
default (`inspector.selfheal.enabled`, default `true` for computation, view promotion per
deployment once the gate check — re-run `scripts/measure-selfheal-baseline.py` — passes).

**Testability follow-up (panel G12, adopted):** because the demo seeds cannot self-heal,
the lane's LIKELY/MIXED paths and the gate check itself are currently unreachable
end-to-end. #351 adds a **transiently-failing seed process** to the engine-harness set
(`validate-bpmn` doctrine: e.g. an HTTP task against a harness stub that recovers after
N attempts, retry cycle long enough to span ≥ 2 sampler buckets) so integration tests can
drive real SELF-HEALED spells through the real sampler — the pilot gate itself is
unchanged (test coverage is not pilot evidence).

## 8. Measured baseline 2026-08-04 (MEASURED FACTS, not proposals)

Method + full tables: [reviews/R2-SELFHEAL-BASELINE-2026-08.md](reviews/R2-SELFHEAL-BASELINE-2026-08.md)
(extraction via the deployment's own read APIs only; the demo Postgres was not accessed).
Headline numbers this design is built on:

- Pilot ledger: **5 incidents / 5 episodes, ZERO ever closed**, span **16.0 days**
  (2026-07-19 → 2026-08-04; episodes exist only since the ledger landed 2026-07-19). The
  5 rows are 2–3 underlying classes (the algo-v2 bump re-keyed them mid-span).
- **2 completed RETRYING spells** total (1 min and 120 min, both ESCALATED), **both
  operator-confounded** (bulk retries at spell start, proven from `GET /api/bulk`), so
  **0 unconfounded spells, 0 self-heals observed** — self-heal rate distribution: empty;
  time-to-self-heal p50/p90: undefined.
- "Episodes closed without vs. with corrective action": **0 vs 0 — uncomputable** (no
  episode has ever closed). Stated per the honesty mandate rather than estimated.
- Attribution gap proven live: a FILTER-scoped bulk retry that drained a class's DLQ is
  joinable to that class by **no existing read API** (`relatedBulkJobs` = ERROR_CLASS
  envelopes only) — drives the §3.3 audit-side confound rule.
- Observability floor proven live: retry cycles completing within one 60 s bucket are
  invisible to the occurrence series (§3.1).
- Hysteresis backtest over all 45,996 recorded samples: naive per-cycle badge = **2
  displayed flips** (both premature-and-wrong n=1 verdicts); proposed §4 rule = **0**
  (100 % suppression; correctly INSUFFICIENT_HISTORY everywhere). Method precisely (panel
  G9): both rules replay the SAME per-sample recomputation over the same series; the
  naive baseline uses point-estimate thresholds, no floor, and counts every completed
  spell including confounded ones; the proposed rule applies §3.1 exclusions + floor +
  hysteresis + dwell — constants and code in `scripts/measure-selfheal-baseline.py`.
  Demonstrates the insufficient-history boundary; at-volume stability is pre-registered
  below.

## 9. Evaluation protocol (pre-registered NOW, run when §7.2 volumes exist)

Registered before the data exists so thresholds cannot be fitted to it afterwards.
Harness: extend `scripts/measure-selfheal-baseline.py` (same read-only extraction) —
prequential backtest over recorded occurrence/episode history: at each completed spell,
compute the badge from strictly-prior history, compare against the spell's realized
outcome; temporal split, never random (DMKD).

**Calibration (accept ALL to ship the rate display):**
- Per-lane realized frequency: spells that started while their class displayed
  `SELF_HEAL_LIKELY` self-healed with frequency ≥ 0.60; `SELF_HEAL_UNLIKELY` ≤ 0.40.
  The exit bounds — not the stricter enter bounds — are the correct calibration target
  *by design* (panel G17, rejected as-filed): a hysteretic displayed lane legitimately
  persists anywhere inside its band, so the band edge is exactly what the display
  promises. The displayed lane must mean what it says.
- Brier score of the per-class rate ≤ Brier of the fleet-wide base rate (climatology) —
  the per-class split must not be *worse* than no split.
- Calibration-in-the-large: |mean predicted rate − realized frequency| ≤ 0.10 over the
  test spells.
- p90 coverage: realized fraction of SELF-HEALED test spells finishing within the
  displayed p90 ∈ [0.80, 0.97].

**Temporal stability (DMKD metric, accept ALL):**
- Zero risk-decreasing displayed transitions during any live spell (structural — verified
  by replay, not assumed).
- Displayed lane changes per class-week ≤ 0.25 (≈ one change per class per month).
- The §4 rule's replayed flip count ≤ ⅓ of the naive per-cycle baseline's on the same
  history (today: 0 vs 2; must hold at volume).

**vNext ML gate:** a trained model is considered ONLY if the calibration block fails while
the §7.2 volumes are satisfied (i.e., the simple statistic is provably insufficient at
adequate data, not merely data-starved) — and must then beat the v1 rate's Brier score by
≥ 10 % relative on the same prequential backtest to justify its pipeline cost (TKDD:
expect it not to).

## 10. Build-slice surface sketch (#351 / #352 — binding shape, not binding field names)

- **#351 backend:** `SelfHealStatsService` (derive-on-read per §3.2, Caffeine-cached per
  §3.2's key rule, audit-side confound rule §3.3) — it owns the §4.2 dwell/monotonicity
  state machine and serves the **displayed** lane; `GET /api/incidents` list items +
  detail gain an optional `selfHeal` block `{lane, n, healed, wilsonLow, wilsonHigh,
  ttsP50Seconds?, ttsP90Seconds?, excludedSpells, truncationTainted}` (rate/interval
  fields absent below the floor — the DTO cannot express a sub-floor rate; `tts*` absent
  when `healed = 0`, panel G18); config `inspector.selfheal.{enabled, window-days=90,
  floor=10, dwell-cycles=10}`; the §7.2 transiently-failing harness seed + IT arc;
  springdoc + `gen:api` regen; VIEWER floor (read-only, no new mutating surface — the
  corrective-actions skill's rails are untouched by construction).
- **#352 frontend:** the lane badge on RETRYING-bearing error-group cards + incident
  detail (chip copy per §4.1/§6); the risk-ranked RETRYING view ordered
  `SELF_HEAL_UNLIKELY` → `MIXED` → `INSUFFICIENT_HISTORY` → `LIKELY` (attention-first;
  ties by live total); truncation marker + exclusion tooltip + fleet-wide-scope tooltip
  per §5. The client RENDERS the served displayed lane and never recomputes it (§4.2
  rule 3 — stability state is server-side; a refresh cannot reset it).

## 11. Non-goals & explicitly rejected

- **No ML pipeline, training infra, or cross-class generalization** (vNext, gated — §9).
- **No automation of any verb** from the lane (no auto-retry, auto-ack, auto-resolve,
  suggested-bulk preselection). The lane is evidence, never an actor.
- **No change to ARCHITECTURE §2.3 status derivation** or any corrective-action rail.
- **No new persistence** — no spell table, no stats snapshots (derive-on-read; revisit
  only if the 90-day window scan measurably hurts, with the occurrence store's partition
  pruning making that unlikely at current cardinality).
- **No per-engine rate splits in v1** (§5 scope note records the accepted aggregate).
- **No sub-bucket retry counting** — the 60 s resolution floor is documented, not worked
  around (engine-side retry telemetry would need new engine calls, violating do-no-harm).

## 12. Panel review (2026-08-04)

Per repo convention the draft was sent to the two independent review MCPs. **One seat was
lost honestly:** the copilot MCP (GitHub Models) answered `410 Gone` on both its catalog
and completion endpoints — the service, not a model quota — so that seat is vacant; no
substitute model was used and no self-grade recorded (repo rule). Gemini's Pro tier was
quota-blocked (`429`); the Flash tier reviewed instead (the INCIDENT-LEDGER §0 precedent).

| Seat | Model | Outcome |
|---|---|---|
| Architecture + statistics | Gemini 2.5 Flash (Pro quota-blocked) | 18 findings (1 blocker, 11 major, 5 minor, 1 nit) — dispositions below |
| Product/ops + statistics | *(vacant — copilot MCP endpoint 410 Gone at review time)* | re-run this seat before the #351 build starts |

Adopted (references `G<n>` inline where applied): **G2** outcome judged through spell end
+1 bucket, standing-DLQ-at-start explicitly allowed; **G1/G4/G8** observational-unit +
±1-bucket wording, "observed" qualifiers in duration copy; **G3/G6** confound-bias
direction stated, "no operator interference observed" framing, episode-statistic carries
the same caveat; **G5** cache key must cover audit reads (or 60 s TTL); **G7** dwell/
monotonicity state machine moved server-side, API serves the displayed lane; **G9**
backtest method clarified in §8; **G10** informs-attention vs gates-rails line drawn
explicitly; **G11** fleet-wide-statistic tooltip for scoped viewers; **G12** (the
blocker) transiently-failing harness seed added to #351 so LIKELY paths and the gate
check are testable end-to-end; **G13** Wilson non-independence caveat; **G14**
INSUFFICIENT_HISTORY placed outside the monotonicity risk order; **G15/G16/G18** floor
rationale, coverage definition (≥ 95 % buckets), `tts*`-absence condition made precise.

Rejected, with reasons: **G1's probabilistic sub-60s model** (v1 is descriptive; engine-
side retry telemetry would need new engine calls — documented as a resolution floor
instead); **G2's clean-DLQ-at-start requirement** (would exclude nearly every real spell;
the delta is the evidence); **G10's opt-in-by-default risk-ranked view** (attention
ordering is the feature's purpose and its default-enablement is already gated by §7.2);
**G17's enter-bound calibration targets** (a hysteretic displayed lane legitimately spans
its band — the exit bound is what the display promises; documented in §9 instead).
