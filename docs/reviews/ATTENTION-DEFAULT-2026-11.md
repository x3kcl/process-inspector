# Attention-ordering default — decision memo (#480)

**Date:** 2026-11-13 · **Flag:** `inspector.triage.attention-ordering` (default `false`) ·
**Product-owner decision: PENDING**

**Recommendation: keep the default OFF.** Do not "extend the pilot" in its current form either:
the demo ledger has not changed shape since 2026-08-05 and nobody has acted on it since
2026-09-14, so more calendar time will not move the three gates that are NOT MET (G1, G2, G4).
They need operators who resolve and acknowledge incidents on a fleet with at least 6 live
classes. Re-open the decision when a pilot like that has run.

## Evidence

Inputs: the operator's read-only pull `scripts/pilot-data-pull.sh` (rev `39c6b34`, pulled
2026-11-13T06:25:04Z from `process-inspector-demo-postgres-1`, see `docs/OPERATIONS.md` "Pilot data
pull"), referred to below by file name. The pull is not committed because it is demo operational
data. Code under test: `origin/main` at this PR's base.

**Data-maturity gate (ALARM-COST-MODEL §7, G5 as amended in §16.7).** The gate passes only if every axis passes.

| Gate | Definition | Input | Value | Met? |
|---|---|---|---|---|
| G1 | ≥ 20 closed episodes fleet-wide AND ≥ 3 classes with ≥ 3 closed each | `22-g1-closed-episodes.csv`, `31-incident-episode.csv` | **0** closed episodes (6 episodes, all `ended_at` empty); **0** classes | **NO** |
| G2 | ≥ 6 current-generation classes live concurrently at least once in trailing 28 d | `23-g2-concurrency.csv` | max **3** on every day 2026-10-16…11-13; also 3 in every one of the 139,067 current-era passes in `32-` (script aggregate) | **NO** |
| G4 | ≥ 10 completed ack lifecycles | `24-g4-acks.csv`, `01-table-spans.csv` | **0** (`error_group_ack` has 0 rows) | **NO** |
| G5 | ≥ 56 d trusted span of the CURRENT era (`cycle_complete ∧ ¬truncated`; an era starts when `fleet` changes or is `''`) | `20-era-trusted-span.csv`, `21-g5-current-era.csv` | **97.60 d** (era 2, fleet `engine-7,engine-a,engine-b`, first trusted pass 2026-08-07T16:08Z); 139,065 of 139,066 passes trusted; largest gap 35 min | **YES** |
| G3 | (not in this issue's scope) R2 sufficiency rail for ≥ 25 % of live classes | `25-retrying-spells.csv` | not computed. For a rough idea: by the pull's spell definition no class has more than 5 spells in its whole history, and the R2 floor is 10 (`selfheal.floor`) | not assessed |

Overall: **NOT MET**. The SPEC and §7 still say "0 of 5". The measured state is now 1 of 4
assessed axes (G5).

G5 caveat: §16.7 names a REST-only method (VIEWER, `until` cursor). This value comes from SQL over the
BFF's own store, which reaches the whole era. The two methods count the same rows; only the
access path differs.

**The ledger is thin, and that thinness is a finding in its own right** (`01-`, `30-`, `31-`, `33-`). The ledger holds 6 incidents
in all. Only 3 current-generation classes are live (4 `ArithmeticException`, 5 and 6
`UnknownHostException`). No class has appeared since 2026-08-05 (incident 6), there have been 0
regressions, and all 6 episodes are still OPEN.
`audit_entry` (112 rows) and `bulk_job` (6 rows) both end on 2026-09-14, inside the
demo-seed window (`presentation/DEMO-SEED-WINDOW.md`). The store has recorded no operator action
for 60 days. G1 and G4 measure operator behaviour, so on this deployment they cannot move.

**Pilot replay: on today's ledger the flag would change nothing.** Command: `scripts/replay-burst-attention.py --cache` on
a cache built from `30-` and `32-` (see Reproduce). It replayed 165,116 buckets with 3 classes and
found **0** ordering mismatches against count-only for both the pre- and post-#365 `F`, with Kendall τ = 1.0000. The
flood gate fired in 10 buckets, all for incident 4. That replay holds R = M = S = 1. This
matches the pilot: every live class was last seen at pull time, there are no closed episodes,
and no class reaches the R2 spell floor.

**Code checks.** `AttentionScoreCalculator.java` is unchanged since `8d45834` (#402), the commit
the harm-search port was re-pinned to. The `InspectorProperties.Attention` `*OrDefault()` values
(24 h, 28 d, 3, 0.5/2.0, 0.25, PT1H, 10 min, 10/5, 8.0) match the script's `AttentionCfg`.
`--selftest-only` gives **46 PASS, 0 failures**. `mvn -o test
-Dtest='AttentionOrderingNeutralityTest,AttentionScoreCalculatorTest'` gives **12/12 and 43/43 green**
(empty ledger ⇒ count-only order). Jacoco's coverage check fails on a scoped run, which is expected.

**Harm search (synthetic, seed 20260805, 10 min 18 s, `--json`).** These fleets are synthetic.
They are not the pilot ledger, and their distributions are invented (R-ATTENTION-HARM §8).
The run reproduces the 2026-08-18 addendum exactly:

- Pooled harm against count-only: **54.70 % [54.14, 55.25]**. The figure was 66.06 % before #398.
- G2-compression regime: **61.02 %**.
- Staleness regime: **89.13 %**.
- Pilot-shaped coupled regime (G0b): **0.00 %**, with the same order in 100 % of fleets.

The current score still makes the order worse on most synthetic fleets that have the
evidence the pilot lacks.

## Risks

- **Flipping now:** today nothing changes (replay 0/165,116), but nothing is gained either. Once
  a richer fleet appears, the order follows a score that the synthetic search finds harmful on
  54.70 % of fleets. No measurement from the pilot contradicts that finding.
- **Keeping it off:** count-only stays in place, including its known failure (an 8-count outage
  sits under a 300-count noisy class, SPEC §4). The score and badge code stays inert,
  and the feature keeps its maintenance cost.
- **Extending the pilot passively:** on this demo, G1, G2 and G4 stay at 0, 3 and 0 indefinitely
  (see above). The pilot would use calendar time and produce no new evidence.

## SPEC wording a flip would need (not made here)

The flag text is in **SPECIFICATION.md §4, "Stage 0 — Triage landing", the "Attention
ordering" bullet**. SPEC §7 is Bulk operations, and the gate itself is ALARM-COST-MODEL §7. A
flip would make these changes:

1. Change `FLAG-OFF by default` to `FLAG-ON by default since #<flip PR>`.
2. Replace "Behind `inspector.triage.attention-ordering` (**default false**) they can instead be
   ordered by" with "By default (`inspector.triage.attention-ordering`, **default true**; `false`
   restores count-only) they are ordered by".
3. Replace "The flag ships false because the design's numeric data-maturity gate is measured
   NOT MET (0 of 5 axes); flipping it requires re-measuring that gate." with "The flag defaults
   true because the data-maturity gate was re-measured MET on <date> (G1–G5, ALARM-COST-MODEL §7;
   values in docs/reviews/<memo>)".
4. Replace "(the shipped, flag-off, expected-today case)" with "(the flag-off case)".

The same flip PR would update ARCHITECTURE §319/§351, OPERATIONS, `application.yml`'s comment
and the §7 table, and would add a release note.

## Reproduce

```bash
python3 scripts/attention-harm-search.py --selftest-only
python3 scripts/attention-harm-search.py --json harm.json
# replay: convert ledger/30-incident.csv -> incidents.json {items:[{id,algoVersion,currentGeneration,
# firstSeen,lastTotal}]} and 32- -> inc<id>.json {incident, series:[{sampledAt,total,truncated,
# cycleComplete,fleet}]} (ISO timestamps), then:
BASE=http://127.0.0.1:9 python3 scripts/replay-burst-attention.py --cache <dir>   # no network used
```
