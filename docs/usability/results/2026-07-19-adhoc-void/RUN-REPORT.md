# Usability run report — runId `adhoc` (2026-07-19)

Catalog v1.0 · seedFingerprint `4809f46e2922dc0a65c571eb393b8a29072329e8a95b3989a206e9817c890fc4`
· bffSha `134f0faab44fd086dcd5061ec505c27527b1a70f` (git HEAD fallback — `GET /api/meta`
returned empty) · reconciled from 11 tester traces + 4 ground-truth/restoration hooks.
**ARCHIVED, NOT `latest` (decision 2026-07-20).** The draft of this report claimed to
supersede `2026-07-17-spec-synced-full-recert` (PASS) as `latest`. It does not: a run that
is void on a harness defect must not become the fleet's standing certification. `latest/`
stays on the last valid recert until a clean re-run replaces it. This run's value is its
verified findings (below) and its negative-path evidence, not its gate verdict.

## Gate verdict: **FAIL — invalidated by a run-wide harness defect (rerun required)**

- **Not** `fail-expected-gaps-only`: the "6 known gaps" are the catalog's 6
  OIDC-only-deferred items (R-GOV-06, R-SAFE-06/07/11/15/17 — prod-like-leg territory,
  excluded from numerator and denominator). No mission targeted them and no tester
  claimed them, correctly. This run's misses are environment blocks, not those gaps, so
  the expected-gaps-only verdict is unavailable.
- The BFF serving this run was launched **without the engine password env vars**
  (health detail verbatim: `Secret env var not set: ENGINE_A_PASSWORD` /
  `ENGINE_B_PASSWORD`), so **0 of 3 registered engines were servable for the entire run**
  (engine-a/b unreachable at the application layer while their containers were up;
  engine-7 registry-disabled). This is a *repeat* of a documented harness defect with a
  documented fix (RUNBOOK line 14 — export the engine password env vars when starting
  the BFF). Per RUN PROTOCOL, blocked-by-environment escalates as a harness defect and
  is never counted as a UX failure.
- Consequence: no spine step beyond FIND was exercised end-to-end;
  ORIENT/DIAGNOSE/FIX/OUTCOME/RECOVER have **zero** passing coverage this run. 44 of 63
  tasks (≈70%) ended `blocked-by-environment`.
- Of the few gate-population goals that WERE exercisable, one MUST-v1 goal failed on the
  merits (**R-AUD-04** — no visible correlation ID anywhere, T3) and one
  mission-contract mismatch surfaced (**R-SEM-24** operator publish, T4).
- **Zero Sev1 app findings**: no quiet lie, no guard bypass, no wrong-target, no
  invisible apply. Every ground-truth hook is consistent with every tester's claims —
  no tester claimed a fix the engine contradicts, and no hallucination canary fired
  (no success claimed on any BUILT-no surface). The honesty rails (lower-bound caveats,
  unsearched-engine enumeration, policy-vs-outage register split) held up impressively
  under a total outage.

## Ground-truth cross-check (hallucination/quiet-lie audit)

| Hook | Engine reality | Tester claim | Verdict |
|---|---|---|---|
| M3 vars (`amount`/`note`/`retry.enabled`) | amount=100 unchanged, note present, per-instance audit `[]`, no edit landed | blocked-by-environment, no edit claimed | **consistent — no quiet lie** |
| M3 `uxrun-m3-2` termination (OOB arc) | still RUNNING; OOB command never ran | tester never opened the case, no kill claimed | consistent |
| M4 `uxrun-m4-*` DLQ cohort | 8/8 dead-letter jobs untouched at seed-time state | blocked, no retry claimed | consistent |
| M6 (`uxrun-m6-3` kill, `uxrun-m6-mig` migrate, registry flips) | no termination, still v1, staged flips never persisted | blocked, nothing claimed | consistent |
| M5 restoration | engine-legacy container restarted + verified; but it was **never registered** in this run's registry (staging mismatch, see env notes) | tester saw a 3-engine fleet, reported it faithfully | consistent (staged-degraded) |

## Per-mission task table

**M1 · 3am payments pager (responder) — 1/7 attempted, 6 blocked**
| # | Verdict | Evidence (one line) |
|---|---|---|
| 1 | yes-with-struggle | Correctly concluded the inspector itself is blind ("every count below is a lower bound (this engine is excluded)") and escalated the outage, not the rumor |
| 2–7 | blocked-by-environment | Every ID resolve: "resolved against 0 of 2 engines"; honest refusal to call the garbage ID a true negative ("search coverage was zero, not a true negative") |

**M2 · stuck multi-part order (responder) — 1/5, 4 blocked**
| # | Verdict | Evidence |
|---|---|---|
| 1–4 | blocked-by-environment | "seed-1784455062 … not found on any reachable engine — but some registered engines were not searched" |
| 5 | yes-with-struggle | Jargon survey: inline self-definitions found for "lower bound (≥)"/"Stage-0 triage cards"; NO in-app definition for "dead-letter", "R-BAU-10", "algo generation"; zero help/glossary affordance |

**M3 · bad data, careful hands (operator) — 0/7, all blocked.** Ground truth confirms no
mutation reached the engine. One protocol violation (guessed route → framework 404 page).

**M4 · bad deploy cleanup (operator) — 0/7 data tasks; freshness-stamp leg passed**
(as-of stamp + "BFF caches ~20s" legible, Refresh re-stamps; bulk button correctly
disabled on an empty set; no cap copy reachable without a cohort — see Known-gap notes).

**M5 · fleet-dark viewer — 4/5, 1 blocked (staged-degraded: total, not half, outage)**
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Read all 3 engine states from text; noted "reality looks worse" than the one-host-down rumor |
| 2 | yes | Answered "I cannot know right now" citing "resolved against 0 of 2 engines" — refused the wrong ticket-closing "does not exist" |
| 3 | yes-with-struggle | Only found `x-request-id` via network inspection — **nothing on-screen** (→ T3) |
| 4 | blocked-by-environment | No healthy engine-a existed to reconcile; badge click is dead (no engine detail page) |
| 5 | yes | "⚠ 0 of 2 engines answered" correctly read as total search failure, not a clean fleet |

**M6 · prod with the safety on (operator/admin) — 1/5**
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked (expected) | #215/#227 structural: prod tag needs https; runner pre-answered |
| 2,4,5 | blocked-by-environment | Targets unreachable; ground truth: nothing migrated/killed |
| 3 | yes | engine-7 policy-vs-breakage proven harmlessly: "disabled in the registry by the engine owner — not an operable target" vs a/b's outage register |

**M7 · morning handover (operator/admin) — 5 pass-ish, 1 no, 3 blocked**
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Full 4-row ops log after clearing the silently-pre-filled "My shift" preset |
| 2 | no | Zero attribution possible: resolver dark + zero matching audit rows — honestly reported as zero-confidence |
| 3 | yes | Real `operations-log.csv`, 12-column header, matches grid |
| 4 | yes-with-struggle | "Copy shift report" produced **zero visible feedback** twice (→ T5) |
| 5 | blocked | Right surface + doctrine quoted (incident survives drain/resolve/regression) but zero groups to acknowledge |
| 6 | yes-with-struggle | Active>30d assembled from Status+Started-before, cURL echo confirmed; no one-click ACTIVE leak view (known R-BAU-02 nuance) |
| 7 | blocked | No failed case reachable to explain-status against |
| 8 | yes-with-struggle | Wildcard publish refused legibly; **engine-narrowed operator publish ALSO refused** (→ T4); admin publish + unpublish-with-reason + author-copy-preserved all verified |
| 9 | blocked | /tasks found unprompted; "No engines answered — nothing to show." |

**M8 · platform day (registry-admin/access-admin/admin) — 2 yes, 1 no, 2 blocked**
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked-by-environment | Lifecycle narrated Draft→probe-failed; enable unreachable (secret ref genuinely unset); probe failure did NOT name the missing ref (→ T6) |
| 2 | yes | 169.254.169.254 rejected at submit naming the egress rule + support request ID; localhost:1 correctly passed SSRF, failed at probe with a class-ambiguous message |
| 3 | yes | Plain admin: nav inert with grant named; direct URL renders explained refusal, no crash |
| 4 | blocked-by-environment | Access CRUD file-pinned shut — both grants hit the same config gate before four-eyes logic could differentiate; remedies named but not self-serviceable |
| 5 | no | **No Remove affordance for a Draft/Probe-failed engine** — only appears after Active→Disabled (→ T7); ops-log trace renders engine-c meaningfully (R-SEM-17 positive half) |

**M9 · keyboard-only (responder) — 1/4**
| # | Verdict | Evidence |
|---|---|---|
| 1–3 | blocked-by-environment | Clean 5-keystroke skip-link path to the FAILED view, reproducibly "0 instances"; timestamp mechanism verified (absolute+zone+relative, keyboard-operable UTC toggle) |
| 4 | yes | Painted text "DEV" on every tier badge (screenshot-confirmed); operability plain-text; elaboration only in accessible name (Sev4) |

**M10 · digging past page one (viewer) — 1/4** (t1 yes: the no-filterless-search rule and
"⚠ 0 of 2 engines answered" honesty; paging/limit unreachable; only newest-first sort
exists — the mission's oldest-first ask is unsatisfiable, Sev4).

**M11 · routine sweep (viewer) — 2/5**
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | **R-SEM-05 pass**: compiled criteria (`Status: SUSPENDED` + `Started before: …`) confirm the name's "(by start time)" parenthetical — metadata-grounded |
| 2,3 | blocked-by-environment | Dup-key disambiguation and unique-key narrowing unobservable |
| 4 | yes-with-struggle (partial) | Search cURL found on first attempt under Filters→Compiled criteria→As cURL; action-side cURL unreachable |
| 5 | yes-with-struggle (partial) | `<img src=x onerror=alert(1)>` rendered inert, quoted verbatim in the omnibox resolve message, 0 console errors; grid/detail legs unreachable |

## Themes (root-cause clusters, ranked)

**T1 · Sev1 (harness, run-invalidating) — BFF launched without engine secrets; 0/3
engines servable all run.** Every mission, every data surface (11/11 missions, all
search/resolve/detail/action surfaces). Repeat occurrence of a documented defect with a
documented fix (RUNBOOK line 14). Compounding staging mismatches: this run's registry
was seeded with only engine-a/b/engine-7 — `engine-legacy` was never registered, making
M5's F-G7 half-dark stage structurally unsatisfiable (its "poll until legacy
unreachable" criterion could never pass either); and M6's registry flips never persisted
(audit chain holds only the three seed rows). **Action: fix the launcher; add a
pre-flight gate that refuses to dispatch testers when `/api/engines` reports 0 reachable
engines; make stage scripts assert their own writes; align the uxrun registry seed with
MISSIONS.md F-G7.**

**T2 · Sev2 — raw backend config internals shown verbatim to every role, with no next
move.** `Secret env var not set: ENGINE_A_PASSWORD` rendered to viewer/responder/
operator across ≥5 surfaces (header health badges, omnibox resolve panel, search alert,
dashboard banners, incidents context); flagged confusing in 8 of 11 missions. A
non-admin gets an env-var name they cannot act on and no "contact an admin" guidance —
the only offered control (Retry/Refresh) repeats the identical failure. R-UXQ-05
violation: [what] yes, [why] partially, [next move] absent for every non-admin tier.

**T3 · Sev2 — no visible correlation/request ID on any error surface (R-AUD-04,
MUST-v1, exercised and FAILED).** M5 t3: banners, technical-detail tooltips and the ops
log carry no per-request identifier; the only handle is the `x-request-id` HTTP response
header, found via network inspection — exactly what the goal says a user must not need.
One goal, but the gap spans every error envelope (≥3 surfaces).

**T4 · Sev2 — operator publish scope never narrows below `engine/*`; mission contract
contradicted.** M7 t8: refusal wording is identical in shape for `*/*`, `engine-a/*`,
and `engine-a/*`-with-definitionKey — filters are not reflected in the computed scope,
so no OPERATOR-side narrowing can ever unlock publish; only ADMIN published. MISSIONS.md
("this one should go through") and the #234 arc expect the engine-narrowed operator
publish to succeed. Either the brief drifted (fix MISSIONS.md) or the scope computation
regressed (app defect). Fail-closed, so not Sev1 — but triage before the rerun: it
re-fails M7 t8b regardless of environment.

**T5 · Sev2 — "Copy shift report" succeeds silently (R-UXQ-06).** Two attempts, zero
feedback (no toast/banner/DOM change, snapshot+screenshot verified). The outcome of a
user's own action has no record anywhere — the user cannot know whether the clipboard
holds the report or stale content.

**T6 · Sev2 — registry probe failure: one generic message for distinct failure classes,
plus a promise the audit trail doesn't keep.** M8: missing-secret vs nothing-listening
both render the identical "could not reach or version-check" text; the missing secret is
NOT surfaced with its ref named at probe time (an explicit R-SAFE-13 goal leg — the
dashboard badges DO name it, the registry surface doesn't); and the tooltip's "The
specific connection error is recorded server-side in this engine's audit trail" resolved
to an audit row holding only `outcome=failed` — the promised specificity is absent.

**T7 · Sev2 — registry lifecycle dead-end: Draft/Probe-failed engines cannot be
removed.** M8 t5: `Remove` only appears after Active→Disabled; an engine that can never
pass its probe is permanently stuck in the registry (3 strategies exhausted, raw-DOM
confirmed). Any typo'd or abandoned onboarding becomes an immortal row.

**T8 · Sev3 — no in-app glossary/help; internal IDs and internals-speak in user copy.**
"dead-letter" used bare with zero definition; "R-BAU-10" (spec ID) and "the current algo
generation" in user-facing copy; regex scan for help|glossary|documentation over
rendered pages: zero matches (M2, M9 corpora). Confirms R-SEM-01 BUILT-partial is thin.

**T9 · Sev3 — not-found headline vs not-searched reality: skim hazard.** The headline
"…was not found on any reachable engine" and the count "0 instances" read as true
negatives; the coverage-zero truth lives in secondary lines ("resolved against 0 of 2
engines", "⚠ 0 of 2 engines answered"). Every tester who read carefully got it right —
but three independently flagged the ordering as a 3am mis-close risk (M1 t2, M5 t5,
M10 t1). The honesty is present; its visual hierarchy is inverted.

**T10 · Sev3 — "an unexpected error — see technical detail" undersells a precisely
diagnosed cause, and the engine badge is a dead end.** The cause (missing secret) is
specific and stable, yet labelled "unexpected"; clicking the engine badge navigates
nowhere (M5 t4 expected an engine detail page); elaborations live only in
title/accessible-name strings (M9 DEV badge).

**T11 · Sev3 — unprompted, undrillable "YAML drift ignored (DB is authoritative):
added 0, removed 2, changed 0."** Appeared after an unrelated add-engine action; nothing
explains what "removed 2" refers to or where to look (M8).

**T12 · Sev4 — polish:** guessed-route 404 renders the raw React-Router default error
page (no route errorElement; found via a protocol-violating URL guess, M3); no
ascending/oldest-first sort option on /search (M10); saved-view caveat lives in a
skimmable parenthetical (M11 — name is honest, layout invites misreading).

## Known-gap evidence (expected-fails and adjacent genuine gaps)

The 6 expected-fail known gaps are the OIDC-only-deferred items (R-GOV-06,
R-SAFE-06/07/11/15/17): correctly untargeted by every brief and unclaimed by every
tester — clean, as in the prior recert. (The catalog's original 8 MUST-v1 BUILT-no
gaps are all BUILT since 2026-07-17; there is no BUILT-no MUST-v1 surface left for the
hallucination canary to fire on.) Evidence-quality paragraphs for the genuine gaps this
run DID brush against:

- **R-SEM-01 glossary (BUILT partial):** M2's tester hunted a definition for
  "dead-letter" via tooltips, links, and a rendered-text regex for
  help/glossary/documentation across the dashboard and Incident Ledger — zero hits.
  Expected the affordance as a tooltip on the failure-lane chips or a help link in the
  header. Plausible search, honest give-up, expectation located.
- **R-AUD-04 correlation ID (BUILT yes, sliver failed):** M5's tester checked every
  error banner, expanded every "technical detail", swept `title` attributes DOM-wide,
  and checked /audit columns before resorting to the network panel. Expected it on the
  error envelope or a copy-for-ticket control.
- **R-AUD-05/a shift-note nuance (known partial):** M7's tester expected a free-text
  handover composition surface, found only Export CSV + Copy shift report, and
  improvised the narrative by hand — consistent with the 2026-07-16 recert nuance.
- **R-NFR-01 bulk-cap copy:** M4's tester expected cap help text near the bulk control
  or in its dialog; with an empty result set the dialog never opens, so the cap is
  invisible exactly when an operator is planning ahead ("would 5,000 work?") — worth a
  static-copy fix independent of fixtures.

## Rubric-corpus verdict (R-UXQ-05/06)

**Zero Sev1 rubric violations** (no bare Success/Failed/HTTP-code as sole record; no
uninvited modal; no stacked banners). Violations found:

- R-UXQ-05 (Sev2): `Secret env var not set: ENGINE_A_PASSWORD` — internals as user
  copy, no next move for non-admins (T2).
- R-UXQ-05 (Sev2): probe-failed tooltip promises audit-trail specificity that is not
  there (T6) — a message asserting evidence that doesn't exist.
- R-UXQ-06 (Sev2): "Copy shift report" — outcome of the user's own action recorded
  nowhere (T5).
- R-UXQ-05 (Sev3): "⚠ UNREACHABLE — an unexpected error — see technical detail" next
  to a fully diagnosed cause (T10); "No incidents recorded yet in the current algo
  generation."; "Persisted history for every failure class ever seen (R-BAU-10)" (T8);
  "YAML drift ignored (DB is authoritative): added 0, removed 2, changed 0." (T11);
  raw React-Router error page on unknown routes (T12).
- Positively noted (style exemplars): "This engine is disabled in the registry by the
  engine owner — not an operable target."; "engine-a, engine-b: unreachable or capped —
  every count below is a lower bound (≥)."; "The request was refused before the engine
  could be asked — this says nothing about whether the instance exists."; the
  publish/grant refusals naming scope + role + a support request ID.

## Protocol violations

- **M3:** guessed route `/instances/{engine}/{id}` (forbidden move). Finding kept
  (framework 404 page) but demoted to Sev4/polish given the illegitimate entry path.
- **M9:** first task pass used the mouse in the keyboard-only mission — self-detected,
  disclosed, re-run keyboard-only from a fresh load; keyboard narration accepted.
- **M7:** read harness scratchpad staging files (out-of-band knowledge). Disclosed and
  explicitly not used for citations — answers spot-checked as UI-grounded; no
  devaluation.
- **Cross-tester pattern (not per-tester fault):** several testers attempted to
  diagnose or kill/restart the misconfigured BFF process; all denied by the permission
  classifier, none circumvented, all disclosed. Environment remediation is outside the
  tester mandate — the correct fix is T1's pre-flight gate. M9 additionally reported an
  apparent post-denial classifier cooldown (two unrelated browser calls denied
  immediately after a denied kill) — a harness observation worth tracking.
- **M5:** used Playwright network inspection to find `x-request-id`.
  Borderline-allowed (inspecting traffic the UI itself generated; the finding is
  precisely that the UI lacks the ID); accepted with the caveat recorded.
- **No hallucination canaries.** No tester claimed success on any BUILT-no surface; all
  success claims are citation-backed; all fix claims consistent with ground truth.

## Environment / staging notes

degraded:
- "ACME suite absent on engine-legacy (engine < 6.8 gating in seed.sh) — expected, engine-a carries the full ACME fixtures."
- "No natural json-typed variable existed anywhere on engine-a, so the JSON mission uses the prescribed fallback config variable on ACTIVE_ID rather than an acmeVendorEnrichment instance."

Additional (this run):
- **Run-invalidating:** the uxrun BFF (:8085) was started without the engine password
  env vars (an env-ref problem — secret values themselves never belong in config or in
  this report), so engine-a/b were unreachable at the application layer for the whole
  window while their docker containers were verifiably up. Fix per RUNBOOK line 14,
  then full re-stage + rerun.
- **Registry seed mismatch:** the uxrun registry contained only engine-a/b/engine-7;
  `engine-legacy` was never registered, so M5's F-G7 stage (stop legacy, observe a
  half-dark fleet) could not be satisfied even pre-stage. The restoration hook confirmed
  the container itself was stopped/restarted cleanly; the `/api/engines` recovery leg
  was structurally unsatisfiable. Align the seed with MISSIONS.md before rerun.
- **M6 stage flips never persisted** (audit chain shows only the three seed rows) — the
  read-only/prod staging silently no-op'd; stage scripts should assert their own writes.
- **Access mapping file-pinned** in this sandbox — R-SAFE-14/08 write arcs are
  un-exercisable until the run environment uses the DB mapping store.
- Restoration status: all four hooks report clean (no engine mutations to revert;
  engine-legacy container restarted and verified; no registry writes leaked).

## Recommendation

Fix T1 (launcher + pre-flight gate + registry seed + stage-write assertions), triage T4
(brief-vs-app publish scope) and T2/T3/T5/T6/T7 (all cheap, surface-local), then re-run
the full 11-mission matrix. The honesty architecture demonstrably survived a worst-case
total outage — this run is a strong negative-path certification and a void gate result,
not evidence of UX regression against the 2026-07-17 PASS baseline.

---

## Post-run reconciliation (2026-07-20)

Every Sev2 above was verified against the running app and the source before filing, which
changed two of them.

| Draft finding | Verified outcome |
|---|---|
| T1 (`↳ child` badge, no separator) | Confirmed — filed [#271](https://github.com/x3kcl/process-inspector/issues/271) |
| T2 / T5 (copy buttons, no confirmation) | Confirmed but **narrower** than drafted: the shared `CopyButton` *does* render "Copied ✓"; these two buttons bypass it. "Copy shift report" additionally mutates the grid filter as a side effect. Filed [#274](https://github.com/x3kcl/process-inspector/issues/274) |
| T3 (requestId absent) | Confirmed, reproduces run 1's #118-class gap — filed [#272](https://github.com/x3kcl/process-inspector/issues/272) |
| T4 (operator publish) | **Not a product bug.** See below. |
| "ops log hiding audit rows" | **Not a defect** — it was T2's "Copy shift report" silently applying the my-shift filter. Folded into #274; no audit truncation occurs. |

Also filed from this run: [#273](https://github.com/x3kcl/process-inspector/issues/273)
("Load more" can't distinguish exhausted from capped) and
[#275](https://github.com/x3kcl/process-inspector/issues/275) (engine-probe failure reason
hidden in a tooltip).

### T4 resolved: the brief drifted, and the drafted reason was wrong twice

The draft offered a fork — brief drift, or a scope-computation regression. It is brief
drift: `SharedViewService.publishFloor` escalates to ADMIN for any wildcard scope, exactly
as SHARED-VIEWS.md §4.3 / R-SAFE-14 specifies. MISSIONS.md M7 t8b was corrected (it no
longer asserts the narrowed operator publish succeeds).

Two corrections to the reasoning, both worth recording because each is a plausible wrong
answer that survives casual inspection:

1. The draft's mechanism — "filters are not reflected in the computed scope" — is wrong.
   Scope *is* computed from content (`SharedViewScope.referencedEngines`). Narrowing to one
   engine does change the derived engine.
2. The first triage pass then concluded "operator holds no publish grant for that scope."
   Also wrong. Form-login users get a synthesized `ScopeGrant.global(role)`
   (`RbacAuthorizer.java:160-164`), which *does* cover the scope. The caller is outranked at
   the **floor**, not missing a grant.

What actually blocks it: `SharedViewScope.isWildcard` is engine `*` **OR** tenant `*`, and
`scope_tenant_id` is derived from the engine's registry pin — untenanted engines derive `*`.
So on this fleet a one-engine view still scopes to `engine-x/*`, still counts as wildcard,
and still demands ADMIN.

That is spec-conformant, but it has a consequence the spec never states: on any untenanted
fleet the OPERATOR publish floor is **unreachable** — no amount of narrowing lets an
operator publish. Raised as [#276](https://github.com/x3kcl/process-inspector/issues/276)
for a design decision; no code changed here.
