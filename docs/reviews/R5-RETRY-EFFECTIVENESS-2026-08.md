# R5 — retry-vs-data-fix effectiveness, partitioned (issue #358)

**Date:** 2026-08-04
**Verdict: STILL UNMEASURABLE, for a subtler reason than the one that motivated this
track.** Partitioning removes the always-fail-fixture confound the issue set out to fix,
but the deployment's one "organic" class (`acmeApiOutage`) is *itself* a deterministic,
permanent failure by construction (an HTTP call to an RFC 2606 reserved-`.invalid` host).
Removing the confound the issue named exposes a second one underneath it, one level
deeper. No number in this report should be read as evidence for or against R-SEM-13 on
its own merits — see §6.

> **Lineage.** Spun out of the #348 measurement pass (`docs/ALARM-COST-MODEL.md` §5.4,
> landed in #357), which measured retry-only effectiveness at 1/41 (≈2.4%) on the whole
> pilot audit log and flagged the number as confounded by the demo's always-fail seed
> corpus — the same "biased toward zero by construction" shape #347 found for the
> self-heal rate on this same corpus (`docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md`).

## 0. Pre-registered floor (set before computing the organic-partition result)

Per the issue's own instruction ("set any sample-size floor BEFORE looking at results,
exactly as #350 did"): this track reuses, unchanged, the **n ≥ 10** floor already fixed
in `docs/RETRYING-RISK-LANE.md` §7.1 for a per-class Bernoulli success rate on this same
pilot ledger — derived there from Wilson-interval arithmetic (z = 1.96): n = 9 is the
smallest sample where even a perfect record's lower bound clears a 0.70 decisive
threshold, and the floor is set to 10 (one spare observation) so a floor-entry verdict
never sits exactly on that boundary. This is not a new number invented for this report —
it is inherited from a design that predates this session and was not fitted to the retry
numbers below. The floor is written into `scripts/measure-retry-effectiveness.py` itself
(`FLOOR_N = 10`) before the script's results section, and the script prints a per-row
`MEETS FLOOR` / `BELOW FLOOR` verdict rather than a bare rate.

## 1. Partition scheme (issue point 1)

Every retry-shaped intervention is attributed to the **process definition key** of its
target instance, then bucketed:

| Bucket | Members | Basis |
|---|---|---|
| **ORGANIC** | `acmeApiOutage`, `acmeShippingOutage` | The issue's own text names `acmeApiOutage` as "the one confirmed organic case today"; `docs/reviews/R4-GROUPING-QUALITY-2026-08.md` independently documents it as "the ACME suite's one organically-failing process — a real HTTP-connector shape, not a JUEL-expression zoo fixture" and names `acmeShippingOutage` as its adversarial-corpus sibling (same failure family, not yet observed live) |
| **SYNTHETIC** | `demoFailingPayment`, `demoFailingRetry`, `zooMethodNotFound`, `zooMissingProperty`, `zooMissingPropertyVariant`, `zooStringIndex` | `docs/TEST-SCENARIOS.md` / the `validate-bpmn` skill document these as deliberately, permanently failing by construction (JUEL expression bugs) |
| **UNCLASSIFIED** | anything else observed | reported, never silently bucketed either way |

This is a two-bucket scheme today, but the codebase already anticipates a third: #359
added an opt-in transiently-failing fixture (`PI_SEED_SELF_HEALING=1`, off by default,
absent from the demo) that would be neither an always-fail fixture nor a
deterministic-but-realistic outage — a genuinely third kind. It contributes nothing here
(it is not enabled on the demo, by design — the same reasoning `RETRYING-RISK-LANE.md`
§7.2 gives for excluding it from the self-heal gate applies here identically: this
deployment's measurement must read organic history only, and a harness fixture is not
organic history no matter how realistically it fails). If it were ever enabled on a real
pilot, this script's partition dict would need a third bucket, not a repurposed one.

## 2. Attribution method — what closes, what doesn't (issue point 2)

`#351` (`docs/RETRYING-RISK-LANE.md` §3.3/§10) already solved a **narrow** version of
attribution: self-heal confound *detection* — did *any* successful retry land on an
engine a class touches, in a time window — via
`AuditEntryRepository#findSuccessfulRetryJobPoints`, a constructor projection
(`RetryAuditPoint(engineId, ts)`) that never selects `AuditEntry.payload`. That machinery
answers a different question than this track needs (it never resolves *which* class a
retry targeted) and **is reused conceptually, not rebuilt**: this script also never reads
`payload` anywhere, for the same R-AUD-03 reason.

`#348`/§5.4 reported full per-class attribution as unreachable over the read API, because
`relatedBulkJobs` only joins **ERROR_CLASS**-scoped bulk envelopes (proven live there: a
FILTER-scoped bulk retry drained a class's DLQ while `relatedBulkJobs` stayed empty), and
`payload` is null over the API (R-AUD-03 minimization). This script closes a **materially
wider slice** of that gap — not by touching payload, but by composing three read
endpoints that were simply never combined for this purpose before:

1. `GET /api/audit` already carries `instanceId` on every instance-scoped row (identity,
   not payload — R-AUD-03 governs the payload column specifically, and `instanceId` is
   not it).
2. `GET /api/bulk/{id}` (the job **detail**, not the list) returns a full per-item
   breakdown with `instanceId` and the originating `auditId` for **every** item of
   **any** scope kind, including FILTER-scoped jobs — this was the missing piece; the
   list endpoint used by `docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md` never surfaces
   `items`, but the detail endpoint does (proven live 2026-08-04, `/api/bulk/{id}` on
   the FILTER-scoped 2026-07-21 job returns all 37 items with instance ids).
3. `GET /api/instances/{engineId}/{instanceId}` is historic-first (a completed or
   dead-lettered instance still resolves, proven live on an instance that ended
   2026-07-22) and returns `definitionKey` plus the instance's **current**
   `flags.hasDeadLetterJobs`/`status` — again, no payload read.

Composed, this attributes 46 of the 46 ledger-window interventions with a resolvable
`instanceId` to a definitionKey (0 unresolved within the ledger window; 8 pre-ledger rows
from 2026-07-08, before the incident ledger existed, are excluded by the same convention
`ALARM-COST-MODEL.md` §5.4 uses, and reported rather than silently dropped — most 404 on
lookup, consistent with predating the current engine containers).

**What remains open.** This is still not *general* per-signature attribution: a
`definitionKey` can in principle span more than one exception message/signature, and it
does not survive being told apart from a hypothetical second class sharing the same
definition key. For *this* deployment's actual catalog, every observed `definitionKey`
happens to map to exactly one partition bucket, which is all issue #358 needs — but a
future deployment with, say, two structurally different failure modes on the same
process definition would need a real per-job signature join (job `exceptionMessage` →
`ErrorSignatureNormalizer`), which no endpoint returns keyed by instance today. Stated
plainly rather than shipped as a wrong join, per the issue's explicit instruction.

## 3. Method — success signal

A retry's outcome is read from the **target instance's current state**
(`flags.hasDeadLetterJobs`), not the retry call's own audit `outcome` (which only means
the API call was accepted — proven live: every retry row in the corpus has
`outcome=ok`, including ones whose instance is dead-lettered again right now). When an
instance was retried more than once, every attempt but the chronologically last is
inferred **failed** (a later retry would not have been needed otherwise, and in every
such case here the instance is *also* still dead-lettered right now, so this is not
purely an inference — it is directly confirmed). A `data-fix-then-retry` pair is a
`retry-job` whose immediately preceding event on the same instance was a successful
`edit-variable`.

Full method, corpus, and reproduction commands: `scripts/measure-retry-effectiveness.py`
(stdlib-only, same shape as `scripts/measure-selfheal-baseline.py`). Reproduce with:

```bash
BASE=https://pi.naumann.cloud USER_=viewer PASS=dev python3 scripts/measure-retry-effectiveness.py
```

## 4. Results (extracted 2026-08-04T18:54Z)

Ledger window: 2026-07-19T07:08:47Z onward (incident ledger's first sighting) → today;
64 audit rows total, 46 attributable within the window, 3 bulk job envelopes (2
ERROR_CLASS-scoped at `acmeApiOutage v1`, 1 FILTER-scoped spanning both partitions).

### definitionKey inventory

| definitionKey | interventions | partition |
|---|---|---|
| `acmeApiOutage` | 18 | ORGANIC |
| `demoFailingPayment` | 16 | SYNTHETIC |
| `demoFailingRetry` | 10 | SYNTHETIC |
| `demoUserTask` | 2 (`activate`, not a DLQ retry — excluded from the rate below) | UNCLASSIFIED |

### Raw-attempt counts

| Partition | Kind | n | successes | rate | vs. floor |
|---|---|---|---|---|---|
| **ORGANIC** | retry-only | 18 | 0 | 0.0% | meets floor |
| **ORGANIC** | data-fix-then-retry | 0 | 0 | n/a | below floor |
| SYNTHETIC | retry-only | 22 | 0 | 0.0% | meets floor (meaningless) |
| SYNTHETIC | data-fix-then-retry | 2 | 2 | 100.0% | below floor (meaningless) |

### Distinct-target-instance counts (the more defensible n — see §5)

| Partition | Kind | n | successes | rate | vs. floor |
|---|---|---|---|---|---|
| **ORGANIC** | retry-only | **8** | 0 | 0.0% | **below floor** |
| **ORGANIC** | data-fix-then-retry | 0 | 0 | n/a | below floor |
| SYNTHETIC | retry-only | 21 | 0 | 0.0% | meets floor (meaningless) |
| SYNTHETIC | data-fix-then-retry | 2 | 2 | 100.0% | below floor (meaningless) |

Zero data-fix-then-retry attempts were ever made against the organic partition — no
operator ever paired `edit-variable` with a retry on an `acmeApiOutage` instance. This is
itself informative: editing a process variable is not a plausible remedy for an
unreachable external host, so the intervention pattern #348 found suggestive
("data-fix-then-retry beats retry-only") was never even tried against the one class
where "data-fix" doesn't apply.

## 5. Why the raw-attempt count (18) is not the number to trust

18 retry-job attempts landed on only **8 distinct `acmeApiOutage` instances** — some
retried up to 4 times (2026-07-19 twice, 2026-07-21, 2026-07-27, on the same instance).
Repeated retries against the *same* permanently-unreachable host are not independent
Bernoulli trials: the second attempt carries no new information the first didn't already
carry, because nothing about the target changed between attempts (the host is
deterministically invalid, not intermittently flaky). Counting instances once — the
statistically honest unit here — the organic partition's true sample size is **n = 8**,
below the pre-registered floor of 10. Under either counting convention the observed rate
is 0%, but only the inflated raw-attempt count nominally clears the floor; the
defensible count does not.

## 6. Why 0% at n = 8 (or even a floor-clearing n) still would not answer the question

`acmeApiOutage`'s HTTP task posts to a reserved `.invalid` host (RFC 2606) — documented
in `docs/TEST-SCENARIOS.md` (FIX-ACME-06) as producing a **deterministic** integration
dead-letter, explicitly parallel to the arithmetic zoo's JUEL bugs, just with a realistic
connector-failure *shape* rather than an expression bug. `.invalid` is reserved
specifically so it can never resolve, by IETF design — not "usually fails", but formally
guaranteed to always fail. A retry against it cannot succeed, structurally, exactly like
the synthetic partition it was meant to be measured apart from.

So the partition the issue asks for (fixture-vs-organic) is the right instrument, and it
does what it was built to do — it correctly separates `acmeApiOutage` from the JUEL zoo,
and the organic-partition number computed through it is honest. But **the one class
currently labeled organic in this codebase's docs is not, in the sense this measurement
needs, actually retriable-in-principle** — the R4 doc's "organic" label describes
*failure shape* (a realistic HTTP-connector exception vs. a synthetic expression bug),
not *failure mode* (permanent vs. transient). Those are different axes, and this track
assumed they'd line up. They don't, for the one class that exists today. The confound the
issue named (always-fail fixtures dominating the corpus) is real and is now removed; a
second, subtler version of the identical problem (the "organic" class *also* being
always-fail, just less obviously so) sits directly underneath it.

## 7. Verdict

**Still unmeasurable — for a reason one layer deeper than the one #358 was written to
fix.** Partitioning by fixture-vs-organic is necessary and was executed correctly
(§§1–4), closing a real, previously-unclosed slice of the attribution gap (§2) with
R-AUD-03 respected throughout (no payload read, anywhere, verified by construction — the
same pattern `RetryAuditPoint` established). But it is not *sufficient*: the sole
class in the organic bucket is deterministic-by-construction, so its 0% rate is a
restatement of "you cannot fix an RFC 2606 reserved host by retrying", not evidence
about whether retry helps against real, intermittently-recoverable errors. No claim about
retry-vs-data-fix effectiveness for genuinely transient organic errors can be made from
this pilot corpus today, at any n.

**What would change this:**
- A genuinely **intermittent** organic error class actually appearing in real pilot
  traffic (a flaky downstream dependency, a rate limit that clears, a lock contention
  that resolves) — something whose retry outcome is not knowable in advance from the
  BPMN alone. None exists in the current seed catalog; `acmeShippingOutage` (the R4
  adversarial-corpus sibling) would not help either — same reserved-host construction.
- Reaching the pre-registered floor (n ≥ 10 distinct instances) on such a class, the same
  discipline applied here.
- The #359 self-healing fixture does not count toward this by design (per
  `RETRYING-RISK-LANE.md` §7.2's identical reasoning, restated in §1 above) — it is a
  harness proof that the self-heal *machinery* works, not organic pilot evidence, and it
  is off on the demo specifically so it cannot contaminate a measurement like this one.
- In short: this requires a real pilot workload with a real transient failure class, not
  more mining of the existing seed corpus. Re-running `scripts/measure-selfheal-baseline.py`
  and `scripts/measure-retry-effectiveness.py` periodically as real usage accrues is the
  correct way to notice when that happens.

## 8. UI hint recommendation (issue point 4)

**Do not build it.** The candidate hint ("retries rarely work for this class — try
fixing the data first") would be trained on exactly the confounded number this report
declines to certify. Building it now would either (a) hard-code doctrine that is already
stated as doctrine in R-SEM-13 with no new evidence behind the specific wording, or (b)
imply a per-class statistic the corpus cannot support at any n today. Revisit only if §7's
"what would change this" is satisfied — i.e., a genuinely transient organic class reaches
the floor. Until then this stays exactly what R-SEM-13 already is: doctrine, not a
measured per-class signal.

## 9. Non-goals honored

No change to R-SEM-13 doctrine (unchanged; this only continues to fail to move it, same
as #348). No new automatic retry behavior of any kind — this is a read-only measurement
script. No audit payload de-minimization — verified by construction: `payload` is never
read by this script, and every field it does read (`instanceId`, `definitionKey`,
`flags.hasDeadLetterJobs`, `status`, bulk-item `auditId`) is a non-payload field on an
endpoint that was already serving it for an unrelated purpose.

## 10. Limitations / what could not be verified

- The success-signal method (§3) assumes the deployment is quiescent since the retries
  under measurement — true for this run (no audit activity in the 8+ days before
  extraction) but not something the script detects; a re-run against a live, actively
  operated deployment would need to discount any instance whose last recorded retry is
  not actually the most recent thing that happened to it.
- `docker exec` into the demo Postgres was not attempted (classifier-blocked, and out of
  bounds regardless per the task's own instruction) — every number above is derived from
  the BFF's own read API, reproducibly, per the script.
- The three archived (algo-v1) incidents complicate reading the incident *ledger* UI
  directly (an `acmeApiOutage` retry from 2026-07-19 shows against a since-orphaned v1
  incident row, not the current v2 one) — this script sidesteps that entirely by working
  from `definitionKey`, which is stable across the `ALGO_VERSION` bump, rather than from
  incident/signature identity. Worth noting for any future reader trying to reconcile
  this report against the incident list by eye.
- This report does not attempt episode-level "closed with/without corrective action"
  (the other statistic #347/#358 both gesture at) — zero episodes have ever closed on
  this deployment (`docs/reviews/R2-SELFHEAL-BASELINE-2026-08.md` §"Ledger inventory"),
  so it remains 0 vs. 0, uncomputable, unchanged from that report.
