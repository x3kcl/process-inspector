# R2 measurement — self-heal baseline for the RETRYING risk lane (issue #347)

**Date:** 2026-08-04
**Verdict: DATA-MATURITY GATE NOT MET — the lane's rate display must not ship to the pilot
on today's history; build slices #351/#352 may build the machinery, which will honestly
show "insufficient history" for every class (see `RETRYING-RISK-LANE.md` §7).**

Everything in this file is a **measured fact** extracted from the live pilot deployment's
own read APIs — no design proposals here (those live in `RETRYING-RISK-LANE.md`, which
cites these numbers). Reproduce with:

```bash
BASE=https://pi.naumann.cloud USER_=admin PASS=dev \
  python3 scripts/measure-selfheal-baseline.py
```

## Methodology

Source: **https://pi.naumann.cloud** (the pilot/demo deployment), read APIs only —
`GET /api/incidents`, `GET /api/incidents/{id}?window=720`, `GET /api/bulk` — over Basic
auth (dev ladder). No database access: `docker exec` into the demo Postgres is
classifier-blocked and was **not attempted**; every number below is derivable from the
HTTP surface alone, and where a needed aggregate is NOT exposed by any read API that is
stated explicitly rather than estimated.

- One **RETRYING spell** = a maximal run of occurrence samples (`series[]` of the incident
  detail, 60 s sampler buckets) with `retryingCount > 0` for one `(signatureHash,
  algoVersion)` class, ended by a sample with `retryingCount = 0`. Outcome: **ESCALATED**
  if `deadLetterCount` grew over the spell, else **SELF-HEALED**.
- A spell is **confounded** when it overlaps (±2 min) a retry-shaped bulk job from
  `GET /api/bulk` — the retrying jobs were operator-moved out of the DLQ, so the spell is
  evidence about *operator retries*, not about autonomous self-healing.
- A spell is **gap-voided** when the series has a >5 min sampling gap inside it (the
  sampler/BFF was down; the spell's shape is unobserved).

## Results (extracted 2026-08-04T07:09Z)

### Ledger inventory

| # | sig (12) | algo | exception class | state | episodes | closed | series pts | relatedBulkJobs |
|---|---|---|---|---|---|---|---|---|
| 1 | b80efe71c06d | v1 | java.lang.ArithmeticException | OPEN | 1 | 0 | 1,766 | 0 |
| 2 | faa3c2ee5946 | v1 | java.net.UnknownHostException | OPEN | 1 | 0 | 3 | 2 |
| 3 | e939c160ede4 | v1 | java.net.UnknownHostException | OPEN | 1 | 0 | 1,763 | 0 |
| 4 | faf637824c40 | v2 | java.lang.ArithmeticException | OPEN | 1 | 0 | 21,232 | 0 |
| 5 | 3a2d45936c0f | v2 | java.net.UnknownHostException | OPEN | 1 | 0 | 21,232 | 0 |

- **5 incidents, 5 episodes, ZERO episodes ever closed** (all live, `endedAt=null`; no
  resolve has ever been performed on this deployment; `regressionCount=0` everywhere).
  The issue-#347 statistic "episodes closed without a corrective action vs. with" is
  therefore **0 vs 0 — uncomputable on today's history**.
- Ledger span: **2026-07-19 07:08Z → 2026-08-04 07:03Z = 16.0 days** (episodes exist only
  since the ledger landed 2026-07-19). The v1-generation incidents (1–3) end 2026-07-20
  12:37Z — the R-SEM-03 algo v2 bump (#270) orphaned them and re-keyed the same two
  underlying failures as incidents 4–5, so the 5 incident rows represent **2–3 distinct
  underlying failure classes**.
- Occurrence sampling is minute-bucketed and near-continuous: 45,996 points total, one
  4-minute gap (2026-07-28 05:42Z) across the whole span.
- Corrective actions on record: **3 `retry-job` bulk jobs** — two ERROR_CLASS-scoped
  (2026-07-19 07:11Z, admin, 4 items each, joinable to incident 2 via `relatedBulkJobs`)
  and one FILTER-scoped (2026-07-21 04:15Z, operator, 37 items, 30 ok / 7 skipped —
  joinable to **no** incident via any read API, see the attribution gap below).

### RETRYING spells

| class | start | end | duration | max retrying | DLQ over spell | outcome | confounded |
|---|---|---|---|---|---|---|---|
| e939c160ede4/v1 | 07-19 07:11Z | 07-19 07:12Z | 1 min | 4 | 4 → 8 | ESCALATED | yes (ERROR_CLASS retries 07:11Z) |
| faf637824c40/v2 | 07-21 04:16Z | 07-21 06:16Z | 120 min | 8 | 14 → 22 (drain 22→14 at 04:15Z) | ESCALATED | yes (FILTER retry 04:15:33Z) |

- **Completed spells: 2. Unconfounded: 0. Self-healed: 0.** Both observed spells were
  operator-induced retries that re-failed back to the DLQ. The measured autonomous
  self-heal rate distribution is **empty**; time-to-self-heal p50/p90 is **undefined** (no
  self-heal events exist to measure).
- Spell arrival rate: 2 completed spells / 16.0 days ≈ **0.125/day fleet-wide**.
- **Observability floor (measured):** the 2026-07-21 FILTER retry moved 8 of incident 5's
  jobs too, yet incident 5's series shows **no** retrying spell and a flat
  `deadLetterCount=8` — the demo's accelerated `R1/PT1S`-style retry cycles re-fail and
  re-dead-letter **within one 60 s bucket**, invisible to the occurrence series. Spells
  shorter than the sampler beat cannot be observed; any spell statistic is conditioned on
  "lasted ≥1 sampler bucket".
- **Structural bias (known by construction):** the demo's seed processes are
  *deliberately failing* BPMN (`validate-bpmn` skill — they exist to produce dead-letter
  jobs). An autonomous self-heal rate measured here is structurally ~0 and says nothing
  about a real workload's transient-error classes.

### Attribution gap (read-API limitation, stated not worked around)

Per-signature attribution of corrective actions is only exposed for **ERROR_CLASS-scoped
bulk retries** (the incident detail's `relatedBulkJobs` joins the submit's envelope audit
payload). A FILTER/selection-scoped bulk retry or a single-job retry touching the same
class is **not attributable to a signature via any existing read API** — proven live: the
2026-07-21 FILTER job drained incident 4's DLQ 22→14 while incident 4's
`relatedBulkJobs=0`. The confound rule above therefore over-excludes conservatively
(any retry-shaped job overlapping the spell window, regardless of scope). The #351 stats
service must widen confound detection audit-side (it can read `audit_entry` directly);
episode-level "closed with/without action" additionally requires closed episodes, of
which there are none.

### Sample-size-floor arithmetic (Wilson score interval, z = 1.96)

| n | perfect-record lower bound | zero-record upper bound |
|---|---|---|
| 5 | 0.566 | 0.434 |
| 8 | 0.676 | 0.324 |
| **9** | **0.701** | **0.299** |
| 10 | 0.722 | 0.278 |
| 15 | 0.796 | 0.204 |

n = 9 is the smallest sample where even a *perfect* record clears a 0.70 lower bound (and
symmetrically a zero record clears a 0.30 upper bound) — below that, no evidence can
honestly claim either decisive lane. Boundary behavior at the chosen floor of 10: a
perfect 10/10 record has LB 0.722; one contrary outcome (10/11) drops LB to 0.623 —
below a naive 0.70 exit (flip), **above** the 0.60 hysteresis exit (no flip); a second
contrary outcome (10/12, LB 0.552) exits legitimately. Symmetric on the unlikely side
(0/10 UB 0.278 → 1/11 UB 0.377 < 0.40 exit → 2/12 UB 0.448 exits).

### Hysteresis backtest (replay of the full recorded history)

Replaying all 45,996 occurrence samples chronologically per class, recomputing the badge
each sampler cycle:

| rule | displayed lane flips | final lanes |
|---|---|---|
| **naive** (no floor, no interval, point-estimate 0.70/0.30, counts confounded spells) | **2** (e939c160ede4/v1 and faf637824c40/v2 each flip NO-DATA→UNLIKELY at n=1) | 2× UNLIKELY, 3× NO-DATA |
| **proposed** (floor 10 unconfounded + Wilson + 0.10 hysteresis band + 10-cycle dwell) | **0** | 5× INSUFFICIENT_HISTORY |

Flip reduction: **2 → 0 (100 % of displayed flips suppressed)**. Both suppressed naive
flips were *wrong in kind* as well as premature: verdicts rendered from n=1
operator-confounded spells, branding classes "unlikely to self-heal" from evidence about
operator retries. Honest caveat: with only 2 spells on record this backtest demonstrates
the rule's behavior at the insufficient-history boundary, not oscillation damping under
volume — the DMKD-style stability evaluation at volume is pre-registered in
`RETRYING-RISK-LANE.md` §9 and MUST be re-run when the §7 gate's volumes exist.

### Gate check (against the gate defined in `RETRYING-RISK-LANE.md` §7)

| gate axis | required | measured 2026-08-04 | met? |
|---|---|---|---|
| history span | ≥ 42 days (≥ 95 % complete-cycle bucket coverage) | 16.0 days | ❌ |
| classes at per-class floor | ≥ 3 with n ≥ 10 unconfounded completed spells | 0 (max n = 0) | ❌ |
| fleet-wide completed spells | ≥ 50 | 2 (0 unconfounded) | ❌ |
| both outcome kinds observed fleet-wide | ≥ 1 SELF-HEALED **and** ≥ 1 ESCALATED | 0 / 2 | ❌ |

At the measured demo arrival rate (0.125 completed spells/day, 0 unconfounded), the
fleet-wide volume axis alone extrapolates to **~400 days** — and the unconfounded and
self-healed axes extrapolate to **never**, because the deliberately-failing seeds cannot
self-heal by design. **The gate is unsatisfiable on the demo workload; it requires a real
pilot workload with genuinely transient error classes.** That is the finding, not a
blocker for the build slices (which ship the honest INSUFFICIENT_HISTORY state).

## Sources / limitations

- Demo read APIs as above. The local dev BFF (:8085) was not running and the
  `inspector-postgres` MCP was not available in the measuring session — no secondary
  dataset was inspected; the demo ledger is the sole real pilot history and is quoted as
  such.
- The 30-day server clamp on `window=` did not truncate anything (whole ledger is 16 days
  old); a future re-measurement past day 30 must read the occurrence store via #351's
  service (repository-level, 400-day retention), not the presentational HTTP window.
