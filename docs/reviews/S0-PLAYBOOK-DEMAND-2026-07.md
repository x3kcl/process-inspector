# S0 measurement — R-GOV-08 playbook demand trigger (issue #106)

**Date:** 2026-07-14
**Verdict: TRIGGER NOT MET — do not start #106 parts 2/3 (record-from-exemplar MVP /
replay-as-bulk-job) yet.**

## What R-GOV-08 requires

> Playbooks (v2) build trigger: audit analysis over ≥3 pilot months shows repeated identical
> ≥2-verb sequences on ≥10 instances of one signature.

## Methodology

`scripts/mine-playbook-demand.sql` (run via `scripts/mine-playbook-demand.sh`, read-only
against `audit_entry`):

- One **sequence** = one instance's full ordered list of successful (`outcome='ok'`),
  instance-scoped corrective-action verbs — the "what did the operator do, start to finish,
  to fix this instance" reading, matching the product design (a playbook is recorded from one
  operator's full remediation session, per #106's "record-from-exemplar" plan). Read-only and
  governance/administrative actions (`access-read`, `registry-*`, `break-glass-login`,
  `error-group-acknowledge`, `view-publish`/`unpublish`) are excluded — they aren't
  remediation steps a playbook would replay. Only sequences of ≥2 actions count.
- A **signature** = a distinct exact sequence (verb list, order-sensitive).
- The trigger fires when any signature's distinct-instance count is ≥10, **provided** the
  underlying data spans ≥3 distinct calendar months — volume without a long-enough pilot
  window isn't trustworthy evidence of a recurring pattern rather than a one-off spike.

A sliding-window/subsequence (n-gram) mining approach was considered and rejected as
over-engineering for a yes/no gate check — revisit only if a future run produces a near-miss
that a subsequence view would resolve differently.

## Result (run against the current dev `audit_entry`, 2026-07-14)

166 total rows, spanning **1 calendar month** (2026-07-07 to 2026-07-13). Top signatures by
instance count:

| sequence | instances | months spanned | trigger |
|---|---|---|---|
| `retry-job, retry-job` | 19 | 1 | NO |
| `retry-job, retry-job, retry-job` | 7 | 1 | NO |
| `edit-variable ×5` | 3 | 1 | NO |
| (all others) | ≤1 | 1 | NO |

**No signature meets the ≥10-instance bar with ≥3 months of data — because there is no pilot
data.** This is the BFF's own dev database: a week of manually- and test-driven traffic
against dockerized dev engines, not real operator usage against a real deployment. The
`retry-job, retry-job` row's near-miss instance count (19) is very likely an artifact of
seed/test scripts retrying deterministically-failing jobs a fixed number of times, not
evidence of organic operator behavior — it should NOT be read as a near-signal.

## Conclusion

The gate is honestly not evaluable yet: **there is no real pilot deployment with audit
history to mine.** Building 2/3 of #106 now would repeat exactly the mistake the shared-views
gate-bypass already taught this project once (per [[deferred-work-tracking]]) — building
ahead of demonstrated demand.

**What to do instead:** re-run `scripts/mine-playbook-demand.sh` against real pilot
production audit data once ≥3 months of it exists (point `INSPECTOR_DB_URI` at the pilot
BFF's Postgres — read-only, no schema changes needed). Until then, #106 stays open with only
this S0 slice complete; do not start the MVP.
