# S0 re-check — R-GOV-08 playbook demand trigger (issue #106, re-run for #479)

**Date:** 2026-11-13
**Verdict: TRIGGER NOT MET — and it fails on BOTH clauses, not only the span.** Do not start
#106 parts 2/3 (record-from-exemplar MVP / replay-as-bulk-job). The build decision stays with
the product owner.

Supersedes the July verdict ([S0-PLAYBOOK-DEMAND-2026-07.md](S0-PLAYBOOK-DEMAND-2026-07.md)),
which was "not evaluable" for lack of data. This time there is data, and it does not clear the gate.

## What R-GOV-08 requires

> Playbooks (v2) build trigger: audit analysis over ≥3 pilot months shows repeated identical
> ≥2-verb sequences on ≥10 instances of one signature.

Three clauses: (1) **volume floor** — one signature on ≥10 distinct instances; (2) **span** —
the qualifying rows cover ≥3 distinct calendar months; (3) **repeat-sequence** — the sequence is
a repeated identical ≥2-verb sequence.

## Data source and pull date

Read-only pull by the operator on 2026-11-13, `scripts/pilot-data-pull.sh` at script rev
`39c6b34` (`00-run-meta.txt`: `pulled_utc: 2026-11-13T06:25:04Z`), run inside the container
`process-inspector-demo-postgres-1` (PostgreSQL 16.15, `00-run-meta.txt`). It is the **demo**
store, not a separate customer deployment. Raw files are not committed (demo operational data);
every figure below names the pulled file it comes from (folder `pilot-data-2026-11-13/`).

Method is unchanged from July: `scripts/mine-playbook-demand.sql` — one sequence = one
instance's ordered successful (`outcome='ok'`), instance-scoped corrective-action verbs; only
sequences of ≥2 verbs; signature = exact ordered verb list; `registry-*`, `view-*`,
`error-group-*`, `access-read`, `break-glass-login` excluded. `trigger_fires` is
`instance_count >= 10 AND distinct calendar months of the qualifying rows >= 3`.

## Exclusions

The demo-seed window in `presentation/DEMO-SEED-WINDOW.md` (`2026-09-14T15:02:30Z ..
2026-09-14T15:40:00Z`, also `00-run-meta.txt`) was recorded by a video recorder acting as user
`operator`, not by an operator working incidents. `playbook/11-demand-excl-seed.csv` removes
rows in that window **and every instance touched in it** (or carrying the `PAY-OK-`/`PAY-FIX-`
marker, per `scripts/pilot-data-pull.sh`). That file is the verdict basis; `playbook/10-demand-raw.csv`
is the unfiltered comparison only.

In-window rows (`playbook/13-seed-window-rows.csv`): `retry-job` 29 rows / 11 instances,
`bulk:retry-job` 3 rows / 1 instance, `edit-variable` 2 rows / 2 instances — 34 rows, all by
`operator`, all `ok`. Of the 112 `audit_entry` rows (`01-table-spans.csv`), 34 are therefore
seed-window rows and 78 are not (112 − 34, arithmetic on those two files). The pull does not
report how many further rows were dropped because their instance was merely touched in the window;
that count is missing, not estimated.

## The unfiltered number is a seed artifact

`playbook/10-demand-raw.csv` reports `{retry-job,retry-job,retry-job}` on **10** instances with
`pilot_period_months = 3` and `trigger_fires = t`. That is the only row anywhere that fires, and
it should not be read as demand: with the seed window excluded the same signature is on **1**
instance (`playbook/11-demand-excl-seed.csv`), so 9 of the 10 are the on-camera recording, and the
third "month" in the raw run is September, i.e. the recording day (the only September rows in
`01-table-spans.csv` / `13-seed-window-rows.csv` are in the seed window). Both the volume and the
span of the raw row come from the video recording.

## Result with the seed window excluded (`playbook/11-demand-excl-seed.csv`)

| sequence | instances | earliest | latest | months | fires |
|---|---|---|---|---|---|
| `retry-job, retry-job` | 6 | 2026-07-19 | 2026-07-21 | 2 | f |
| `edit-variable, retry-job` | 4 | 2026-07-08 | 2026-07-19 | 2 | f |
| `edit-variable, retry-job, restart-as-new` | 1 | 2026-07-08 | 2026-07-08 | 2 | f |
| `edit-variable, trigger-timer` | 1 | 2026-08-04 | 2026-08-04 | 2 | f |
| `retry-job, edit-variable, retry-job` | 1 | 2026-07-21 | 2026-07-22 | 2 | f |
| `retry-job, retry-job, retry-job` | 1 | 2026-07-12 | 2026-07-21 | 2 | f |
| `retry-job x4` | 1 | 2026-07-19 | 2026-07-27 | 2 | f |

Action context (`playbook/12-audit-actions.csv`, all rows, seed included): `retry-job` 77 rows /
47 instances, `edit-variable` 9 / 9, `bulk:retry-job` 6 rows / 3 instances, `activate` 3,
`restart-as-new` 1, `terminate-delete` 1, `trigger-timer` 1, plus `registry-*` governance rows
(excluded from sequences by the query).

## Volume floor (≥10 instances of one signature)

**Not met.** The largest signature in the verdict file is `retry-job, retry-job` on 6 instances
(`11-demand-excl-seed.csv`), against a floor of 10. Next is `edit-variable, retry-job` on 4. The
remaining five signatures are on 1 instance each. This differs from July's "no data" finding: the
shortfall is now a measured shortfall, not an absence of evidence.

## Span (≥3 pilot months)

**Not met — and the audit trail has been silent since early August.** `01-table-spans.csv` shows
`audit_entry` holds 112 rows spanning 2026-07-08 11:09 to 2026-09-14 15:38, and the pull was
taken 2026-11-13. So there is **no audited operator action in the roughly two months before the
pull**. Excluding the seed window, the last audit row of any kind is 2026-08-05 04:43
(`registry-enable`, `12-audit-actions.csv`) and the last qualifying remediation row is
2026-08-04 22:35 (`trigger-timer`, `11-demand-excl-seed.csv` latest_occurrence). The last
`audit_entry` row at all (2026-09-14) is the video recording. Counting only what the query counts
(distinct calendar months of the qualifying rows), the answer is **2** — July and August —
(`pilot_period_months = 2` in `11-demand-excl-seed.csv`), of which August contains one signature
instance. Real elapsed time since the first row is four months, but elapsed time is not what the
clause measures: it measures months in which operators were demonstrably acting, and the data
does not show that for September, October or November.

What this means, honestly:

- Waiting longer fixes nothing by itself. The July re-check expected the span clause to become
  satisfiable by the calendar; it did not, because the evidence stopped accumulating. A further
  re-run on this store with the same silence would return the same two months.
- The pull cannot say *why* it is silent. Other tables kept growing: `triage_snapshot` to
  2026-11-13 06:24 (3,586,736 rows), `incident_occurrence` to 2026-11-13 06:24 (476,445 rows)
  (`01-table-spans.csv`) — the BFF was observing the fleet the whole time. Whether operators
  stopped using the corrective actions, never had failures worth acting on, or acted on engines
  directly (which bypasses the audit golden master) is not distinguishable from this pull. Not
  inferred here.
- Read-only access (`access-read`) is excluded by design, so someone looking at incidents
  without acting would not show up in this measurement either.

## Repeat-sequence candidates

Only two signatures have more than 1 instance after exclusion, and neither is convincing:

- `retry-job, retry-job` (6 instances) — all 6 fall in a two-day window, 2026-07-19 to
  2026-07-21 (`11-demand-excl-seed.csv`), the same days the incident ledger and bulk-job tables
  begin (`incident` and `bulk_job` min timestamps 2026-07-19, `01-table-spans.csv`). That looks
  like one remediation episode retried repeatedly, not recurrence across months; also the same
  caution as in July (deterministically failing jobs retried a fixed number of times) applies.
  Not shown to be organic.
- `edit-variable, retry-job` (4 instances) — spans 2026-07-08 to 2026-07-19, the most
  playbook-shaped (fix data, then retry), but 4 of 10 and confined to July.

Neither signature recurs in August; nothing recurs after 2026-08-04.

## Verdict against each gate clause

| clause | result | evidence |
|---|---|---|
| ≥10 instances of one signature | NOT MET (best: 6) | `11-demand-excl-seed.csv` |
| ≥3 distinct pilot months | NOT MET (2: Jul, Aug; no operator audit since 2026-08-05) | `11-demand-excl-seed.csv`, `01-table-spans.csv`, `12-audit-actions.csv` |
| repeated identical ≥2-verb sequences | WEAK (two repeated pairs, both clustered in July) | `11-demand-excl-seed.csv` |
| overall | **TRIGGER NOT MET** | |

The only `trigger_fires = t` row in the pull (`10-demand-raw.csv`) is the seed recording and is
disregarded under `DEMO-SEED-WINDOW.md`.

## What would change the verdict

- Operator corrective actions actually flowing through the BFF again (and being audited) for
  enough calendar months that the qualifying rows span 3 months **excluding** the seed window —
  i.e. real use, not elapsed time. With the current silence, the earliest that could be true is
  3 months after operators resume.
- A signature reaching ≥10 distinct instances in that data. The nearest today is 6.
- Alternatively, the product owner explicitly amending the R-GOV-08 gate (shorter span, a
  smaller instance floor, or a different evidence source such as a real customer deployment).
  That is a governance change for the product owner, not something this review does; the
  gate was bypassed once for shared views and should not be loosened implicitly.
- Re-run recipe: `scripts/pilot-data-pull.sh` (docs/OPERATIONS.md, "Pilot data pull"), judge
  `playbook/11-demand-excl-seed.csv`; keep any new recording windows in `DEMO-SEED-WINDOW.md` so
  they can be excluded.

Not changed by this review: #106 stays open with only the S0 slice done; the R-TEST-09 test
charter (a build precondition) is also still open.
