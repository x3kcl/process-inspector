# Usability run — TARGETED re-certification, M3 + M6 only (issue #215)

Catalog v1.0 · repo `b41bdb0` · seedFingerprint
`5034db7bebf0222e44698f166605350b1de3b2ccca98d5153fef2425ebf42dee` · 2 missions dispatched
(M3, M6) — a re-run of exactly the two missions the 2026-07-16 full run
(`docs/usability/results/latest/RUN-REPORT.md`) could not certify: M3 died before any task
ran, and M6's `engine-b` → `prod` env-tag flip (F-G2) was never applied. This report does
**not** supersede the full-run report — it is a separate, narrower artifact scoped to the
two gate items that run left open: **R-SEM-09/a/b + R-UXQ-13/a/b/c** (M3) and **R-SAFE-03**
(M6).

## Gate verdict: **FAIL** (M3 + M6 specifically)

Both re-run targets made real progress, but neither is fully certified:

- **M3 recovered almost entirely** — a live tester ran all 6 tasks this time, and 5 of 6
  produced strong, ground-truth-corroborated evidence: R-UXQ-13/a/b/c (form-first variable
  editor, all three arcs) and R-SEM-09/a (CAS conflict — nothing overwritten, colleague
  actor identified in the audit log) now have **valid passing evidence**. But
  **R-SEM-09/b (already-resolved-verb double-mutation guard) still has zero evidence** —
  not because the tester failed, but because the mission brief the harness actually
  dispatched never contains a task that exercises it. See "New findings" #2.
- **M6's R-SAFE-03 is unchanged: still zero valid evidence — but now definitively
  understood as PERMANENT, not a re-stageable miss.** `engine-b`'s env tag was never
  flipped to `prod`; this run's stage:M6 hook plus a direct read of
  `RegistryUrlValidator.java` confirm this specific fixture (a plain-http, localhost-only
  engine) **structurally cannot** pass the `prod` registration rails (Rail 3 requires
  `https`; Rail 6's address-denylist does real DNS resolution, not string matching) —
  a deliberate SSRF guardrail (R-OPS-13), not a bug or a staging oversight. See "New
  findings" #1 for the full analysis and the recommended R-OPS-16 deferral. Everything
  else M6 was asked to prove this time around (R-GOV-04 read-only-engine policy, R-SEM-21
  migration honesty, and the destructive-kill / spare-the-neighbor arc under R-SAFE-02)
  **did** land with strong ground-truth corroboration, including task 5's terminate —
  which the prior run never even reached (blocked by the test harness's own
  tool-permission layer that time, not the app).

**Net for the two gate items named in the recert brief:**

| Gate item | Prior status | This run | Verdict |
| --- | --- | --- | --- |
| R-SEM-09/a (CAS conflict) | zero evidence (tester died) | full task, ground-truth match | **now has valid evidence** |
| R-SEM-09/b (already-resolved guard) | zero evidence (tester died) | **no task exercises it — mission-brief gap, not tester failure** | **still no valid evidence — fixable, see #226** |
| R-UXQ-13/a/b/c (variable editor) | zero evidence (tester died) | all 3 arcs, ground-truth match | **now has valid evidence** |
| R-SAFE-03 (prod friction floor) | invalidated (fixture never staged) | **confirmed structurally impossible in this dev fixture set** | **deferred to R-OPS-16, not a re-run target** |

Because one of the four gate-population items this recert was dispatched to resolve is a
genuine, fixable coverage gap (a missing mission task, tracked as #226) rather than the
catalog's accepted "known-absent" list, this is **`fail`**, not `fail-expected-gaps-only`
— but it is no longer a mystery-`fail`: three of the four items are now either passing
(R-SEM-09/a, R-UXQ-13/a/b/c) or correctly reclassified as a permanent, understood,
out-of-scope-in-dev limitation (R-SAFE-03), leaving exactly one real, actionable gap
(R-SEM-09/b) between this recert and full certification.

---

## Ground truth reconciliation

### M3 — instance `336482de-8114-11f1-b839-3210ba03f0d0` (engine-a)

Cross-checked every task's claimed outcome against the live engine variable read and the
BFF audit chain (`GET /api/instances/engine-a/{id}/audit`).

| Claim (tester) | Ground truth | Match? |
| --- | --- | --- |
| amount 100→250 (task 1) | audit row #5 (oldest): var=amount, reason cites INC-4711, outcome=ok | yes |
| note cleared to null, not empty string (task 2) | live engine variable `note` = null, present, no type field | yes — exact distinction preserved |
| config2.retry.enabled → false only, retry.max untouched (task 3) | audit row #3: var=config2, outcome=ok; other fields unaffected | yes |
| amount 250→300 blocked, colleague's 275 not overwritten, re-committed 275→300 (task 4) | audit row #2: actor=**admin**, var=amount, reason cites the colleague-simulation (INC-9999); audit row #1 (newest): actor=**operator**, var=amount, reason cites the conflict recovery, ticket=INC-4711; live engine value = **300** | yes — two distinct actor rows, final value matches, nothing silently clobbered |
| ticket INC-4711 attachable everywhere (task 5) | audit rows #1, #3, #4, #5 all carry ticket=INC-4711 | yes |

No Sev1 quiet-lie, wrong-target, or invisible-apply finding anywhere ground-truth-checkable
in M3. One caveat: the payload old/new values themselves came back `«redacted»` under the
credentials used for the check (R-AUD-03 per-engine role gate) — the *outcome* (final
engine state + row existence/ordering/actor/reason) was independently verifiable and
matched in every case; the exact numeric before/after inside the audit payload was not.

Task 6 (comprehension check on `uxrun-m3-1`) surfaced a **fixture defect**: the business
key resolves to **three** simultaneous ACTIVE instances, not the one "sacrificial case" the
mission narrative assumes. The tester picked the most-recently-started one and completed
the arc against it; no ground-truth hook was provided for this specific sub-instance so the
pause/resume/cancel sequence itself could not be independently re-verified this run, but the
citations are internally consistent (REVERSIBLE badge language, RBAC lock, no dialog
reachable for terminate as OPERATOR).

### M6 — engine-b `uxrun-m6-*` cohort + registry flips

| Claim (tester) | Ground truth | Match? |
| --- | --- | --- |
| Timer fired on `uxrun-m6-1`, one click, no confirmation (task 1) | `waitForDue` ended 09:55:45.912Z, instance completed 09:55:45.915Z, audit action=trigger-timer outcome=ok | yes |
| Both engines read identically "DEV — Low stakes", no PROD label anywhere (task 1) | `engine_registry` row for engine-b: `environment=dev`, unchanged since the 2026-07-09 seed, **zero** environment-changing audit rows | yes — confirms the fixture (F-G2 prod flip) was never applied |
| Engine 7 blocks every mutating verb, policy not role (task 3) | `engine-7` flipped to `mode=read-only` via audit_entry `registry-enable` at 13:07:53Z (the M6 stage), restored to `mode=read-write` post-run, re-verified live | yes — this flip WAS correctly staged and restored this time |
| Migration to v2 completed, honesty banner shown pre-execute (task 4) | `uxrun-m6-mig` now runs `demoMigration:2:bd4ed99f-...`; audit action=migrate-instance outcome=ok | yes |
| `uxrun-m6-3` terminated permanently, `uxrun-m6-3t` spared (task 5) | `uxrun-m6-3` (3edfec63-...) terminated 13:27:31.858Z, actor=admin, historic deleteReason names INC-4713 and explicitly confirms it is not the "-3t lookalike"; `uxrun-m6-3t` (3ee5439c-...) confirmed still ACTIVE, untouched | yes — no wrong-target, no quiet failure |

**Verdict: zero confirmed Sev1 findings under R-TEST-03** across everything
ground-truth-checkable in this recert. The one genuine open issue (R-SAFE-03) is a staging
failure, not a product defect — but per RUN PROTOCOL a fixture that fails to stage a
**second consecutive time** on the same requirement stops being an ordinary
"blocked-by-environment, re-run once" case and becomes the harness defect the protocol says
to escalate.

---

## Per-task table

### M3 — "Bad data, careful hands" (operator)

| # | Verdict | Notes |
| --- | --- | --- |
| 1 | yes | R-UXQ-13/a. Parsed-echo + plain-English verify sentence before commit; ground-truth confirms the chain ends at amount=300. |
| 2 | yes | R-UXQ-13/b. Explicit empty-vs-null choice; ground truth confirms `note=null`, not empty string. |
| 3 | yes | R-UXQ-13/c. Form-mode boolean toggle, never raw JSON; scoped diff ("1 of 2 fields... the other unchanged") shown at verify. |
| 4 | yes | R-SEM-09/a. Blocked-commit screen, reload-current-value recovery, nothing overwritten — ground-truth confirms two distinct actor rows and a final value of 300. |
| 5 | yes-with-struggle | R-NFR-06 / R-AUD-07. Lazy reason correctly rejected inline with no lost work; ticket INC-4711 captured on every edit — but the field's own label ("optional, ≥10 chars when given") reads as skippable, which is itself a minor copy defect (see findings). |
| 6 | yes-with-struggle | R-SAFE-02 (partial). Suspend/Activate correctly predicted+confirmed reversible from the badge; Terminate correctly inferred irreversible from the absent badge + RBAC lock, but the tester (operator) could never open the actual confirm dialog to read its own wording — that half of the arc needs an admin-signed-in re-run to close. Also the mission-fixture defect: `uxrun-m3-1` resolves to 3 ACTIVE instances, not 1. |

**R-SEM-09/b: not attempted — no task in the dispatched brief exercises it.** See "New
findings" #2.

### M6 — "Prod, with the safety on" (operator → admin)

| # | Verdict | Notes |
| --- | --- | --- |
| 1 | blocked-by-environment | R-SAFE-03. Timer fire itself worked (ground-truth confirmed), but the prod-vs-dev friction contrast the task exists to test never had a prod fixture to contrast against — engine-b was never flipped. Reclassified from the tester's own "yes" self-report per RUN PROTOCOL's ground-truth-over-optimism rule. |
| 2 | blocked-by-environment | R-SAFE-05. Reconfirms the already-tracked accepted gap (no production write path to protect an instance) — expected, excluded from gate numerator/denominator. Fixture also resolved to a stale M3-tagged business key rather than a fresh M6 one (see findings). |
| 3 | yes | R-GOV-04. Engine-7 read-only policy correctly explained, identical for OPERATOR and ADMIN; ground truth confirms the flip was correctly staged AND correctly restored this run. |
| 4 | yes | R-SEM-21. Honesty banner correctly restated before executing; ground truth confirms the instance now runs v2. |
| 5 | yes | R-SAFE-02 (destructive-confirm quality). Correct target itemized and confirmed before the irreversible click, mandatory ≥10-char reason held, twin spared — ground truth matches exactly. The specifically-"because-this-is-production" nuance the task asked about remains unverifiable, again because engine-b was never actually prod-tagged. |

---

## New findings this run

1. **[Sev1, gate-blocking, recurrence — root cause now confirmed STRUCTURAL, not
   transient] `engine-b`'s `prod` env-tag flip (F-G2) STILL was never applied — second
   consecutive occurrence on the exact requirement this recert was dispatched to fix.**
   Ground truth: `environment=dev`, unchanged since the 2026-07-09 registry seed, zero
   environment-changing audit rows for `engine-b` ever. This time the stage:M6 hook's own
   diagnostic (two independent PUT attempts, both HTTP 400 `{"detail":"prod engines must
   use https"}`, confirmed via a follow-up GET that neither mutated the row) plus a direct
   read of `backend/src/main/java/io/inspector/registry/RegistryUrlValidator.java` settle
   the question definitively: this is **not** a re-stageable fixture gap.
   `RegistryUrlValidator.validate()` rejects `environment=PROD` unless `scheme=https`
   (Rail 3, checked *before* any hostname resolution) — Flowable's REST API in this
   dev-compose stack only ever serves plain HTTP, so no engine in the standard dev fixture
   set can pass this rail regardless of address. And even hypothetically past that, Rail
   6's internal-address denylist does a REAL DNS resolution and IP-range check (not string
   pattern-matching a `localhost` literal — confirmed by reading `resolveHost()`), so a
   docker-internal hostname wouldn't help either; the dev-escape that lifts the denylist
   applies only to `environment=DEV`. **No amount of retrying, asserting, or re-staging
   will ever make this specific PUT succeed against `engine-b`'s real address** — this is a
   deliberate SSRF guardrail (R-OPS-13) working as designed, not a bug.
   A genuinely prod-tagged, real-https, non-internal-address fixture was explored as a
   follow-up (a temporary engine behind the project's own Traefik deploy, with a freshly
   dedicated credential — see `docs/DEMO-DEPLOY.md`'s `pi.naumann.cloud` pattern for the
   template) — DNS-level and validator-level feasibility were confirmed (a real subdomain's
   resolution to a public, non-internal IP passes both rails), but full deployment was
   deliberately not pursued given the cost/value tradeoff for a P3-adjacent, already
   well-understood gap. **Recommendation: defer R-SAFE-03 to the R-OPS-16 prod-like leg**,
   the same disposition already applied to R-SAFE-06/07/11/15's similar "needs a real
   prod-tier engine, not achievable in this dev fixture set" gaps — not a third recert
   attempt against `engine-b`. The harness-level pre-flight-assertion recommendation below
   is still worth doing (fail fast with the structural explanation instead of wasting a
   tester's interactions), but it changes M6 task 1 from `blocked-by-environment` (implying
   a fixable staging miss) to a permanently-out-of-scope-in-dev item, not a re-run target.

2. **[Sev2, mission-authoring gap] R-SEM-09/b has no exercisable task in M3's actual
   TESTER BRIEF, despite MISSIONS.md's STAGING block explicitly preparing
   `{{OOB_RESOLVE_CMD}}` ("retries the job on the second sacrificial instance as another
   user") for exactly this arc.** This was invisible in the prior run (M3 died before task
   1, so the absence of a task 7 was never noticed) and is only now visible because a
   tester actually completed all 6 numbered tasks and none of them touch this arc. This is
   not a tester failure or a product defect — it's a mission-definition bug: either
   MISSIONS.md is missing a numbered task, or the STAGING block is stale and should drop
   the placeholder. Either way, **R-SEM-09/b remains a MUST-v1 gate-population item with no
   valid evidence from any run to date**, and re-running M3 again with the same brief will
   not fix it — the brief itself needs a task added first.

3. **[Sev3, fixture hygiene] F-G10 sacrificial-cohort tagging produced ambiguous/
   cross-contaminated fixtures in both missions this run.** M3: business key `uxrun-m3-1`
   ("the sacrificial case", singular, per the mission text) resolves to **three**
   simultaneous ACTIVE instances rather than one, forcing the tester to guess which one
   the mission meant. M6: `{{PROTECTED_ID}}` (meant to be a fresh M6-tagged protected
   fixture) resolved to an ordinary instance tagged `uxrun-m3-2` — a leftover from a
   different mission's namespace, not an M6 fixture at all. Neither defect changed a
   verdict this run (M3's task 6 still completed against a defensible choice; M6's task 2
   was already an expected R-SAFE-05 no-op regardless of which instance it hit), but both
   point at the same underlying issue: sacrificial fixtures from different missions/runs
   are bleeding into each other's `uxrun-<mission>-<n>` namespace instead of staying
   cleanly separated, which will eventually produce a genuine wrong-target ambiguity on a
   destructive verb.

4. **[Sev4, copy defect] Reason-field label contradicts its own gate.** The variable-edit
   Verify-change dialog's reason field is labeled "Reason (optional, ≥10 chars when
   given)" — read literally this says the field can be skipped entirely, but the actual
   behavior (confirmed both by the tester's own attempt and by the R-NFR-06 goal's design
   intent) is that ANY non-empty text under 10 characters is rejected with "🔒 Reason too
   short — 10+ characters." The rejection-with-recovery behavior itself is correct and
   satisfies R-NFR-06's GOAL; the label wording is what's misleading. Minor, but a clean
   one-line copy fix ("Reason (leave blank, or ≥10 chars)" or similar) would remove the
   ambiguity.

---

## Rubric-corpus verdict (R-UXQ-05/06), M3 + M6 only

Zero Sev1 message-style violations in either mission's pooled `messagesCorpus`. The
recurring "DEV — a development engine. Low stakes." badge (already tracked as a Sev2 theme
in the full-run report, and reconfirmed here — see finding #1's ground-truth tie-in) is the
only cross-cutting confusion; everything else tagged `confusing` in this recert's corpus
traces to finding #4 (reason-field label) or to defensible RBAC-lock tooltips that,
while terse, correctly named the missing grant ("🔒 Requires ADMIN — you are OPERATOR").

**Rubric gate (M3 + M6): PASS** (zero Sev1).

---

## Protocol violations

None. Both missions' `protocolNotes` flag the same known harness artifact — every
Playwright MCP action call threw a spurious `ENOENT: /output/page-*.yml` error on the
action call itself, with the underlying browser action always succeeding (verified each
time via an immediate follow-up snapshot). This is a tool/harness artifact, not an
application bug or a tester protocol violation, and cost one extra snapshot call per
interaction in both missions. Both testers also correctly followed the SETUP re-auth
allowance (M3: stale registry-admin session → sign out → operator/dev; M6: operator/dev for
tasks 1-3 → sign out → admin/dev for tasks 4-5) with no forbidden moves (no hand-edited
URLs, no route-guessing, no page-source reads, no direct API calls substituting for UI
actions, no re-submission of a mutating action).

---

## Recommendation

1. **R-SAFE-03: defer to R-OPS-16, do not schedule a third M6 attempt against `engine-b`.**
   Confirmed structural (see finding #1) — no fixture change against the existing dev
   engines can ever pass `RegistryUrlValidator`'s prod rails. Tracked as issue #227
   (harness pre-flight assertion, so a future run fails this specific task fast with the
   structural explanation instead of spending a tester's interactions on it) — that issue
   is about harness hygiene, not a path to certifying R-SAFE-03 itself. Real end-to-end
   evidence would need a genuinely reachable, real-https, non-internal engine (e.g. behind
   this project's own `pi.naumann.cloud` Traefik deploy with a freshly dedicated
   credential) — explored and confirmed feasible at the DNS/validator level, deliberately
   not built out given the cost/value tradeoff for a P3-adjacent, already-well-understood
   gap. Revisit only if/when the R-OPS-16 prod-like leg is built for other reasons.
2. **Add the missing R-SEM-09/b task to M3's TESTER BRIEF** (tracked as issue #226) before
   the next M3 dispatch — re-running the existing brief again will reproduce the same gap.
3. Once #226 lands, a targeted M3-only recert should close R-SEM-09/b cleanly —
   everything else this run touched (R-UXQ-13/a/b/c, R-SEM-09/a, R-GOV-04, R-SEM-21, and
   the general R-SAFE-02 destructive-confirm quality) is gate-clean with
   ground-truth-corroborated evidence and needs no further re-run. With R-SAFE-03 deferred
   to R-OPS-16 rather than re-attempted, that M3-only recert would be the last step to a
   clean certification of everything currently in scope.
4. Fold F-G10 fixture-tagging hygiene (finding #3) into the next fixture-catalog pass —
   tracked alongside the pre-flight assertion in issue #227.
