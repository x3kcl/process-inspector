# R4 — ErrorSignatureNormalizer (algo v2) grouping-quality baseline (issue #350)

**Date:** 2026-08-04
**Verdict: NO MATERIAL DEFICIT. Track ends here — no v3 algorithm issue filed.**

> **⚠️ Fleet-provenance note (added 2026-08-04, same day).** The corpus behind this verdict
> is a **static, committed fixture**
> (`backend/src/test/resources/grouping-quality/corpus.json`), so nothing below is
> invalidated by later fleet changes — the measurement re-runs identically forever. Recorded
> here only so the next reader knows the fleet moved after publication: the demo gained a
> third engine (`engine-7`, Flowable 7.1.0), and #359 added an opt-in transiently-failing
> fixture (`PI_SEED_SELF_HEALING=1`, **off by default**, so it is absent from every default
> seed and from this corpus).
>
> If the corpus is ever **re-harvested**, both facts matter: a 7.x engine appends runtime IDs
> to exception messages (a known wire-shape difference), and the self-healing fixture would
> contribute a new signature. Re-harvesting is therefore a deliberate act that changes the
> baseline — not a refresh.

This is a measurement-only track (#350, part of the 2026-08 literature sweep, #356). Its
whole point is to prove or disprove a hypothesis with real numbers before anyone is allowed
to change `ErrorSignatureNormalizer` — no normalizer change, no `ALGO_VERSION` bump, and no
new dependency were made as part of this work.

## What the issue requires

Apply the Drain (He et al., ICWS 2017) / dedupT (Mamun et al., 2025) evaluation methodology
to the current `ErrorSignatureNormalizer` (R-SEM-03, algo v2, `#270`): measure

- **over-grouping** — distinct root causes fused into one signature, and
- **under-grouping** — one root cause split across multiple signatures (the exact defect
  class algo v2 fixed for `#270`),

against a hand-labeled corpus, and file a v3 issue **only if** the numbers show a material
deficit. If v2 measures fine, the issue explicitly says that is a **success**, not a
disappointment — and instructs against manufacturing a deficit to justify follow-on work.

## Threshold, set in advance

Before running anything against real data, these thresholds were fixed (see
`GroupingQualityBaselineTest`'s own doc comment, committed in the same change as this
report — the constants and this text were written together, not adjusted after seeing the
numbers below):

| Metric | Material-deficit threshold (entry-weighted) | Why this number |
|---|---|---|
| Under-grouping | **> 5%** | Strict: eliminating exactly this defect class was the entire point of `#270`/algo v2. Any material rate here means the fix regressed or never fully worked. |
| Over-grouping | **> 20%** | Looser: `ErrorSignatureNormalizer`'s own doc comment already documents over-grouping (two causes sharing one wrapper message) as an ACCEPTED, deliberate v2 design cost. This harness exists to confirm that cost stays *bounded* on a real corpus, not to demand zero — driving it to zero is exactly what a v3 algorithm (Drain-style template mining, dedupT-style similarity linking) would be for. |

Entry-weighted (fraction of harvested *occurrences* affected) is the primary reading in both
cases — a rare defect touching a huge cluster matters more than one touching a handful of
entries. Group/cluster-weighted (does the defect happen at all) is reported alongside for
context.

## Corpus

**Script:** `scripts/harvest-grouping-quality-corpus.py` (REST-only against the dockerized
engines — never reads `ACT_*` tables; distinct from, and never a replacement for,
`docker/capture-error-corpus.py`, which captures the *normative CI-gating* golden corpus for
the normalizer itself under `backend/src/test/resources/error-signatures/`).

**Fixture:** `backend/src/test/resources/grouping-quality/corpus.json` (checked in).

**Primary (organic) corpus — 50 entries, 5 hand-labeled true root causes:**

| `groupId` | Root cause | Source |
|---|---|---|
| `arithmetic-family` | EL `%` arithmetic with `divisor=0` → `ArithmeticException: / by zero` (both the dead-letter lane `demoFailingPayment` and the pinned-RETRYING timer lane `demoFailingRetry` — same underlying bug, two job kinds, a real cross-lane under-grouping check) | dockerized engines (`:8081`, `:8082`), real REST harvest |
| `string-index` | `substring()` past the end of a variable-length string → `StringIndexOutOfBoundsException` (length varied per instance — the ID-stripping/under-grouping proof) | dockerized engines |
| `missing-property` | expression references `ghost`, which no variable/bean provides → `PropertyNotFoundException` | dockerized engines |
| `method-not-found` | expression calls a method no type provides → `MethodNotFoundException` | dockerized engines |
| `acme-api-outage` | async HTTP task to a reserved-`.invalid` host → `UnknownHostException` (the ACME suite's one organically-failing process — a real HTTP-connector shape, not a JUEL-expression zoo fixture) | dockerized engines + 2 confirmed rows pulled live from the demo deployment's own read API (`GET /api/incidents`, `viewer`/`dev`), current-generation (`algoVersion==2`) only |

4 instances × 2 engines per zoo/demo kind (8 for the two-lane arithmetic family), all
Flowable 6.8.0 (the only engine majors reachable in this session — see Limitations). The
demo cross-check confirms the same two signatures independently observed on a separate,
longer-running deployment.

**Adversarial corpus — 16 entries, 4 hand-picked groups, reported separately (never mixed
into the primary metrics — see below for why):**

| Pair | Hypothesis | Measured |
|---|---|---|
| `missing-property` (`'ghost'`) vs `missing-property-variant` (`'phantom'`, new fixture `docker/processes/error-zoo-missing-property-variant.bpmn20.xml`) | `PropertyNotFoundException`'s message quotes the identifier (`"Cannot resolve identifier 'ghost'"`), and the normalizer's sanitizer collapses quoted literals to `#` — so two *different* missing-property bugs might hash identically. | **Does NOT collide.** v2's identity hash is computed from the job row's own `exceptionMessage` *snippet* (`"Unknown property used in expression: ${ghost.total}"` / `"${phantom.balance}"`), which still embeds the literal expression text. Only the stacktrace-*refined* message quotes and strips the identifier — and per the `#270` display-only contract, refinement never re-keys a group. A genuine near-miss, not a defect. |
| `acme-billing-outage` (existing `acmeApiOutage`) vs `acme-shipping-outage` (new fixture `docker/processes/acme-shipping-outage.bpmn20.xml`, a second HTTP task pointed at a different reserved-invalid host) | Two operationally distinct downstream integrations (billing vendor vs shipping vendor — different teams, different fixes) both failing with `UnknownHostException`. | **DOES collide.** Flowable's `flowable:type="http"` connector's job-level `exceptionMessage` is the generic literal string `"execution exception"` regardless of which host failed — confirmed by REST harvest on both fixtures. This is a real, organically-confirmed instance of the exact tradeoff `ErrorSignatureNormalizer`'s own doc comment already names as an accepted v2 cost. |

Why these are reported separately: the harness exists to measure the current algorithm
against an *organically-sampled* distribution of real causes, not to let one or two
hand-picked stress cases dominate a headline number — giving an engineered pair equal
weight to the organic corpus would itself be a way to manufacture a deficit, which the issue
explicitly says not to do. `GroupingQualityBaselineTest` pins both outcomes (the near-miss
and the confirmed collision) as regression fixtures so a future normalizer change that shifts
either behavior shows up as a visible diff.

Every harvested string is passed through an anonymization pass (email/IPv4 redaction) before
being written; 0 redactions fired, expected for this fully synthetic, engine-internal corpus
(no real hostnames, credentials, or customer identifiers — `acme-billing.invalid` /
`acme-shipping.invalid` are RFC 2606 reserved-invalid hostnames, not real infrastructure).

## Metrics

Implemented in `backend/src/test/java/io/inspector/triage/quality/GroupingQualityMetrics.java`
(pure static, zero dependencies — rung 1 of the unit-test-patterns ladder) per the
issue's own definitions:

- **Over-grouping**: a predicted cluster (one normalizer signature) is "impure" if it
  contains entries from more than one true `groupId`.
- **Under-grouping**: a true group is "split" if its entries land on more than one
  predicted signature.

Both reported group/cluster-weighted and entry-weighted. `GroupingQualityBaselineTest` reads
the corpus fixture, runs it through the CURRENT `ErrorSignatureNormalizer` (algo v2,
`ALGO_VERSION` read from the class itself, never hand-pinned), and prints a report tagged
with the algo version — so a future v3 candidate's run would be directly comparable.

## Results (algo v2, `ALGO_VERSION=2`)

**Primary (organic corpus), against the thresholds fixed above:**

| Metric | Group/cluster-weighted | Entry-weighted | Threshold | Verdict |
|---|---|---|---|---|
| Over-grouping | 0.0% (0/5 impure clusters) | **0.0%** | ≤ 20% | ✅ well within |
| Under-grouping | 0.0% (0/5 split groups) | **0.0%** | ≤ 5% | ✅ well within |

Zero over-grouping and zero under-grouping across all 5 organic root causes, both engines,
and the two independent demo-confirmed rows. The two-lane `arithmetic-family` check (same
bug, dead-letter vs pinned-RETRYING timer lanes) collapsed to one signature, and the
`string-index` per-instance length variation collapsed to one signature — both direct
confirmations that the `#270` fix holds on real payloads, not just the normative golden
corpus it was originally proven against.

**Adversarial case studies (excluded from the verdict, reported for completeness):**

| Metric | Group/cluster-weighted | Entry-weighted |
|---|---|---|
| Over-grouping | 33.3% (1/3 impure clusters) | 50.0% |
| Under-grouping | 0.0% | 0.0% |

The non-zero over-grouping here is entirely the one confirmed ACME billing/shipping
collision (a known, already-documented v2 design cost) — not evidence against the primary
verdict, since these two groups were hand-picked specifically to probe that exact tradeoff
and never appear in the organic corpus.

## Conclusion

**No material deficit.** Algo v2 measures cleanly (0%/0%) on an organically-harvested,
hand-labeled corpus spanning 5 genuinely distinct root causes, two job lanes, per-instance
noise variation, and a live independent deployment. The one confirmed over-grouping case
found during this work (ACME billing vs shipping outage sharing Flowable's generic HTTP
connector wrapper message) is real, but it is exactly the class of cost
`ErrorSignatureNormalizer`'s own doc comment already names as deliberately accepted, and it
does not appear in — let alone dominate — the organic corpus the primary verdict is measured
against.

Per issue #350's own exit criterion: **this track ends here.** No v3 algorithm issue
(Drain-style template mining or dedupT-style similarity linking) is filed. If the ACME-style
generic-connector-message collision is ever judged to matter enough to fix on its own
(independent of any broader v3 push — e.g. adding the failing HTTP task's target host into
the signature), that would be a narrowly-scoped follow-up to evaluate on its own merits, not
evidence for a wholesale algorithm change.

## Limitations / what could not be verified

- Only the `flowable-6` profile engines (`:8081`, `:8082`, both Flowable 6.8.0) were
  reachable in this session; `:8083` (flowable-7) and `:8084` (legacy 6.3.1) were not up, so
  the corpus has no 7.x cross-major entries (unlike the normative golden corpus, which does).
  The harvest script probes for them and skips gracefully — re-run it with those profiles up
  to widen the corpus across engine majors.
- The live demo (`pi.naumann.cloud`) cross-check used its own read API
  (`GET /api/incidents`), not the demo's underlying engines directly (not exposed) — by
  design (REST-only, never touches engine internals of a deployment this session doesn't
  own). It only widened the corpus by 2 confirmatory rows; its dataset is small and
  dominated by the same synthetic seed processes as the dev stack, so it added confirmation
  rather than materially new failure diversity.
