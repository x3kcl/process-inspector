# Usability Run Report — 2026-07-16-post-fix-full-recert-v2

Catalog v1.0 · seedFingerprint
`3d36e21332092cbc820ff49680309a5c367118d1f5af8cdf0be232de0c03ec37` · appVersion
(bffSha/spaSha, BFF unreachable at reconciliation time — `git rev-parse HEAD` in
`pi-usability` used as fallback) `aaf3c45597f183c788309a572af29b46d16374d5` · 11/11
missions run · 63 goal-arc rows in `results.jsonl` (50 in the MUST-v1 gate population).

> **Supersedes** the prior file at this path
> (`2026-07-16-post-fix-full-recert`, no "v2"): that run **failed on a harness defect**,
> not an app defect — all 3 live engines were unreachable for its entire duration
> (`Secret env var not set: ENGINE_A_PASSWORD/ENGINE_B_PASSWORD/ENGINE_7_PASSWORD`),
> collapsing 28 of 42 gate arcs to `blocked-by-environment`. This v2 run is the fixed
> re-run: all three engines answered normally throughout, and every mission produced a
> full transcript with rich, citation-backed evidence.

## Gate verdict: **FAIL**

Not `fail-expected-gaps-only` — the one miss that fails this run is a **newly-found
defect**, not one of the 6 known MUST-v1 gaps (all 6 re-confirmed built and working
this run — see below). Two things are true at once:

- **The numeric pass bar clears easily.** Of 50 MUST-v1-gate-population arcs (BUILT
  yes/partial ∧ CLASS UI/feasible UI-STAGED), **48/50 (96%) landed `yes` or
  `yes-with-struggle`**; the remaining 2 are `blocked-by-environment`
  (M5 tasks 1–2, R-UXQ-04, reclassified from the tester's own affirmative — see
  Themes #2), and **zero** landed a real, non-environmental `no`.
- **One open critical-class finding fails the gate outright.** M4 task 1
  (R-SEM-12, "truncation badges + drill echo", a MUST-v1 **BUILT-yes** surface):
  clicking the landing overview's own per-version drill link — `"v42: 35"` — silently
  drops the version constraint from the resulting query and opens a grid holding
  **~204** rows, not the 35 the link named. Per the R-TEST-03 taxonomy this is a
  **wrong-target** finding (Sev1): the UI takes the operator to materially different
  data than what they clicked expecting, with no warning at the click or in the
  resulting grid. RUN PROTOCOL requires "zero open critical-class findings" as a hard,
  independent gate condition — this alone is sufficient to fail the run regardless of
  the otherwise-strong pass rate. The tester recovered by hand-building an exact filter
  (final answer was correct and ground-truth-consistent), so this is graded
  `yes-with-struggle` at the arc level, but the underlying wrong-target defect is
  tracked separately as a blocking finding.

No hallucination canary fired. All six of the catalog's previously-documented MUST-v1
BUILT-no gaps (R-BAU-01, R-BAU-02, R-AUD-05, R-AUD-08, R-AUD-09, R-L3-01) were
independently re-exercised via M7 this run and matched their catalogued "fixed
2026-07-13" status exactly, every claim carrying element-level citations (see "The 6
known MUST-v1 gaps" below). One adjacent, **catalog-staleness** finding surfaced
in cross-referencing (not a canary): R-AUD-07's ticket-capture field, which the
catalog (as of 2026-07-13) explicitly says is "dark in the UI... capture half
NOT-FOUND expected," was independently found and used in **two** separate missions
(M3 task 5, M4 task 3) with the identical verbatim UI string `"Ticket ID (optional —
recorded with the audit row and linked in the operations log)"`. Two independent
missions citing the same exact accessibility-tree string is strong corroboration of a
real, recently-shipped field, not confabulation — recommend a `spec-sync` pass to
correct R-AUD-07's and R-SAFE-05's catalog entries (see Themes #6).

---

## Per-mission task tables

### M1 — "3am pager: payments failing" (responder)
| # | Verdict | Evidence (one line) |
|---|---|---|
| 1 | yes | "Failures by error class · 5", ArithmeticException/demoFailingPayment (547/604), version+engine breakdown all cited from the landing card. |
| 2 | yes | Correct triage: FAILED/dead-letter escalated, RETRYING (9 retries left, ~50m) left alone — zero mis-triage. |
| 3 | yes | "Currently at: chargePayment" + JUEL alert box gave the ORIENT facts zero-click; stacktrace button found the divide-by-zero root cause. |
| 4 | yes-with-struggle | "Retry job" succeeded unaided, re-failed because the root fix needs OPERATOR — honestly disclosed via the "Fire timer now" caveat and audit trail, not a bug. |
| 5 | yes | Handover note saved in per-instance "Operator notes", attributed + timestamped on reopen. |
| 6 | yes | "copy for ticket" produced all 6 required facts + a working deep link in one click. |
| 7 | yes | Honest, engine-naming negative result for the garbage ID; caveat about excluded unreachable engines flagged as a Theme. |

### M2 — "The stuck multi-part order" (responder)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes (report-only, SHOULD-v1.x) | Root/child correctly told apart via the "↳ child" prefix. |
| 2 | yes | One-click roll-up from parent's "explain this status" straight to the failing child's dead-letter job; retried it there. |
| 3 | yes | 60 children stated exact via "Fan out to 60 children" + an explicit, honest "+10 more... cap 50/node" note. |
| 4 | yes | Raw JSON download + copy affordances found and non-empty on the Variables tab. |
| 5 | yes | Every jargon term self-defined in-app except the raw JUEL exception text itself (minor R-SEM-01 gap). |

### M3 — "Bad data, careful hands" (operator)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Plain-English verify sentence pre-commit; ground truth confirms the final chain lands on amount=300. |
| 2 | yes | Explicit empty-vs-null choice; ground truth confirms engine variable `note`=null, not empty string. |
| 3 | yes | Form-mode-only nested JSON leaf flip; scoped diff proved the sibling field untouched. |
| 4 | yes-with-struggle | CAS collision blocked the commit cleanly, nothing overwritten; ground truth confirms 2 distinct actor rows and a final value of 300. |
| 5 | yes | "fix" rejected pre-commit with no lost work; Ticket ID field captured INC-4711 (contradicts stale R-AUD-07 catalog note — see Gate verdict). |
| 6 | yes-with-struggle | Suspend/Activate reversibility correctly predicted+confirmed; Terminate dialog unreachable under OPERATOR (role gate, not a missing affordance — mission brief assumption mismatch). |
| 7 | yes | Already-ended guard refused a second terminate with a distinct message; WHO/WHEN found via Audit tab, ground-truth-matched to the millisecond. |

### M4 — "Bad deploy cleanup" (operator)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Exact 8-row batch eventually isolated by hand — but the landing's own "v42: 35" drill link silently drops its version filter and opens ~204 rows instead. **Sev1 wrong-target finding — see Gate verdict.** |
| 2 | yes | "~20s BFF cache" staleness disclosed; Refresh visibly advances the "as of" stamp. |
| 3 | yes-with-struggle | Justified bulk retry dispatched with INC-4712 + Ticket ID, after resolving a confusing filter-vs-selection guard message. |
| 4 | yes | Bulk progress live via SSE/Operations drawer, no manual refresh needed for the job itself. |
| 5 | yes-with-struggle | Per-item report correctly named the protected-skip; Verify-now showed the real recurrence — ground truth confirms all 8 instances still hold exactly 1 dead-letter job each. |
| 6 | yes | Per-case outcome report is server-backed and survives F5 byte-for-byte. |
| 7 | yes | 5,000-instance cap + "narrow it and run in slices" guidance both stated up front, without executing anything. |

### M5 — "Half the fleet is dark" (viewer)
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked-by-environment | FIXTURE_DRIFT — F-G7's engine-legacy stop/verify never landed (registry row already soft-deleted 2026-07-11); tester's reasoning was sound but never exercised the true amber zero-state banner. Reclassified per RUN PROTOCOL ground-truth-over-optimism. |
| 2 | blocked-by-environment | Same drift; tester correctly answered "I cannot know right now," but from the omnibox's documented scope, not a genuine partial-outage signal. |
| 3 | yes | Correlation ID directly on-screen, no devtools needed; minor finding — 3 different request IDs on one page load, no primary designated. |
| 4 | yes-with-struggle | Fleet numbers fully reconciled via the tool's own "lane numbers are JOB counts, not instance counts" caption; also caught an unrelated off-by-one ("+38 more versions" renders 37). |
| 5 | yes | "confirmed zero across 3 engines" — complete, honest zero with the 4 excluded engines disclosed in the filter panel itself. |

### M6 — "Prod, with the safety on" (operator, then admin)
| # | Verdict | Evidence |
|---|---|---|
| 1 | blocked-by-environment (pre-excluded) | R-SAFE-03's engine-b→prod flip is a permanent structural block (issue #227); runner correctly pre-answered and started the tester at task 2. |
| 2 | yes | Protected-instance guard locked every verb with the R-SAFE-05 tooltip; catalog's "no production write path" note is stale (fixed 2026-07-14, #165/#172/#184). |
| 3 | yes-with-struggle | Engine C's read-only policy proven harmlessly and correctly framed as policy — but co-badged with an unrelated PROBE FAILED tag, conflating two different reasons. |
| 4 | yes (report-only, COULD-v2) | Migration honesty banner correctly restated; instance now demonstrably runs v2. |
| 5 | yes | Correct target itemized and confirmed among 3 near-identical business keys before the irreversible kill; twin verified still ACTIVE afterward. |

### M7 — "Morning handover" (operator, then admin)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | Complete 17-row night-shift inventory from `/audit`, including the still-unconfirmed 8th bulk-retry case flagged as unresolved. |
| 2 | yes-with-struggle | R-AUD-09 caveat found verbatim; residual limitation (shared-login attribution) honestly disclosed, not hidden. |
| 3 | yes | "Export CSV" produced a genuine `operations-log.csv` with correct headers. |
| 4 | yes-with-struggle | "Copy shift report" is the real one-click artifact, but no dedicated fleet-wide free-text note surface exists — operator had to improvise a supplement. |
| 5 | yes | Acknowledge muted the noisy group without hiding it; auto-resurface guarantee stated pre-commit. |
| 6 | yes-with-struggle | ACTIVE>30d answerable via Search filters (definitive zero) — but the purpose-built "Leak views" home widget only covers SUSPENDED despite its name. |
| 7 | yes | "Explain this status" gave a full falsifiable evidence trail: 6 named engine calls, timestamps, flag-by-flag verdict. |
| 8 | yes-with-struggle (report-only, COULD-v2) | Two-actor publish/unpublish arc completed correctly, but the OPERATOR rejection message misnames its own gate reason ("scope" instead of "role") — R-UXQ-05 violation. |

### M8 — "Platform day: onboard engine-c" (registry-admin, admin, access-admin) — entirely report-only (COULD-v2/SHOULD-v1.x)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Full lifecycle ladder completed with no lockout at any stage — **re-confirms the prior full-run's Sev1 "Test connection traps the row irreversibly" finding is now FIXED.** |
| 2 | yes-with-struggle | Metadata-IP fat-finger blocked with a specific, fixable message; bad-port fat-finger fails silently later with no diagnostic cause. |
| 3 | yes | Plain `admin` cleanly refused with a named reason, surface neither hidden nor broken. |
| 4 | blocked-by-environment | Access-mapping store is file-pinned in this environment — all grant CRUD rejected before the intended four-eyes distinction can run. |
| 5 | yes-with-struggle | Removal stuck pending (single-REGISTRY_ADMIN accepted gap, F-G4); removed-engine-reference half passes cleanly via a real proxy fixture. |

### M9 — "Hands off the mouse" (responder, keyboard-only)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Full FIND→FIX arc completed keyboard-only, zero dead ends, but ~46 raw key presses — far beyond budget; only one shortcut exists ("Skip to main content"). |
| 2 | yes | Every diagram-borne fact has a focusable textual twin outside the SVG; the SVG's own accessible name doesn't self-identify the failing node (minor gap). |
| 3 | yes-with-struggle | Absolute+ISO/Z timestamp given unprompted, UTC toggle functional; sandbox's own OS-UTC timezone prevented observing an actual re-zoning. |
| 4 | yes | Every engine textually labeled "DEV" with an aria-hidden decorative dot — zero color-only meanings found. |
| 5 | yes | Watermark presence is CI-enforced (`check-bpmn-watermark.mjs`); runner-asserted, not independently re-verified by this tester's transcript. |

### M10 — "Digging past page one" (viewer) — entirely report-only (COULD-v2/SHOULD-v1.x)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes-with-struggle | Widest search correctly signaled as a first page via 3 simultaneous cues; Status-group empty-selection silently no-ops (no "(all when none checked)" hint, unlike Engines). |
| 2 | yes | Internal cap (3,201 rows) correctly distinguished from a true end, evidenced only by comparing per-engine counters against the vanished "Load more" button. |
| 3 | no | No next-step guidance appears specifically at the cap; only static "narrow your filter" boilerplate present since page one. |
| 4 | yes | 3,201 unique IDs, zero duplicates, zero out-of-order pairs across the full virtualized-grid scroll. |

### M11 — "Routine sweep: views, an escalation kit, and a suspicious ticket" (viewer)
| # | Verdict | Evidence |
|---|---|---|
| 1 | yes | The view's own "(by start time)" name qualifier is confirmed accurate and necessary — no suspended-since timestamp exists anywhere in the tool. |
| 2 | yes | Ambiguous business key falls back to the ordinary grid (2 rows), no auto-navigate/no picker; only full process ID + ms start-time distinguish the pair. |
| 3 | yes | Unique business key lands directly on a single-row results view, no extra filtering needed. |
| 4 | yes-with-struggle | Both cURL affordances easy to find, but the search cURL carries no auth header/cookie — wouldn't trust it to work unmodified; action-level cURL additionally RBAC-gated for VIEWER. |
| 5 | yes | Hostile business key rendered fully inert (HTML-entity-escaped) everywhere, confirmed via DOM inspection, not just visual read; zero console errors. |

---

## Themes ranked by severity

**1. [Sev1 — wrong-target, R-TEST-03, gate-blocking] Landing overview's per-version drill
link silently drops its own scope (M4).**
`clicked "v42: 35" expecting a 35-row grid; got ~204 rows, version constraint silently
dropped` — the link's target URL
(`/search?engines=engine-a&status=FAILED,RETRYING&definitionKey=demoFailingPayment&sortBy=failureTime`)
never carried a version parameter at all, so the number in the link text has no
corresponding filter in the grid it opens. Three different counts get shown for what a
user believes is one click's worth of scope: the link says 35, the resulting grid
renders 200, the per-engine status line says "204... narrow your filter," and the bulk
toolbar says "~204 matching filter." This is exactly the failure mode R-SEM-12 exists
to prevent ("drilling a per-version count must echo the filter it is about to apply")
on a surface the catalog rates BUILT **yes**. Single surface, but the R-TEST-03
taxonomy classifies wrong-target as Sev1 regardless of recurrence count, and the exit
gate's "zero open critical-class findings" bar is a hard, independent condition — this
alone fails the run. **Action: either drop the version number from the link's own
label until the link actually carries it, or add the missing `version=` query param to
the generated href.**

**2. [Sev2 — harness/fixture drift, not a UX bug] F-G7's engine-legacy stop/verify
never actually staged (M5).**
Ground-truth restoration hook: the `engine_registry` row for `engine-legacy-ux` was
soft-deleted (`lifecycle=removed`, `removed_at=2026-07-11`) **before this run started**
— five days before M5 dispatched — and has no "undelete" path
(`AdminEnginesController` has none). The runner's container stop/restart around M5
therefore had zero observable effect in the app: no tester this run saw an
"engine-legacy" entry anywhere in the visible fleet (3 live + 4 static
`PROBE FAILED` test entries only). M5 tasks 1–2 (R-UXQ-04, the "zero-under-partial ≠
true zero" honesty bar) never got to exercise the actual amber-banner condition the
goal targets; the tester's epistemic caution ("I cannot know right now") is sound
reasoning from what *was* on screen, not evidence the R-UXQ-04 UI treatment itself
works. **This is the second run in a row this exact fixture has failed to stage
correctly** (the prior `2026-07-16-m3-m6-recert` report notes the identical drift for
M5/M6's F-G7 dependency) — per RUN PROTOCOL, a fixture failing to stage twice on the
same requirement should be escalated as a harness defect, not silently re-attempted a
third time. **Action: re-add `engine-legacy` as a fresh REGISTRY_ADMIN-approved
registry entry (four-eyes, since F-G4's single-identity gap blocks self-approval)
before the next F-G7-dependent run, or retarget F-G7 at a currently-registered
scratch engine instead.**

**3. [Sev2] Coverage-disclosure ambiguity: "resolved/confirmed against N of N
(reachable) engines" doesn't name the excluded engines in the same sentence.**
Surfaces (≥3, cross-mission): M1-T7 (garbage-ID omnibox negative), M5-T1 (engine-health
inference), M5-T2 (case-existence omnibox negative — explicitly consequential per the
mission brief: "a wrong 'does not exist' closes a real customer's ticket"). The phrase
"resolved against 3 of 3 engines" / "confirmed zero across 3 engines" is *technically*
honest (3 of the 3 *reachable* engines), but a reader has no way to learn from that
sentence alone that 4 further registered engines exist and were excluded — that fact
only surfaces separately, in the Engines/Filters panel. Testers in every instance
independently flagged this as `confusing`. Every tester who hit it reasoned correctly
in spite of the ambiguity (M5-T2 in particular reached the intended
"I cannot know right now" answer) — so this is a clarity gap, not a correctness bug,
but it recurs across 3 independent surfaces and touches the one MUST-v1 answer the
catalog calls out as consequential. **Action: append the excluded-engine count/names
inline, e.g. "resolved against 3 of 3 reachable engines (4 more registered but
unreachable — see Engines)."**

**4. [Sev3, recurring pattern — 2 surfaces] Guard/rejection messages name the wrong
referent for their own gate.** M4-T3: "🔒 Blocked: filter includes RETRYING instances"
reads as "one of your selected rows is RETRYING" but actually means "the RETRYING
status checkbox is still ticked in the filter, independent of what's selected."
M7-T8: "You don't have permission to publish this scope — publishing a wildcard scope
needs ADMIN" fired identically on a single-engine-scoped view, misnaming a flat
role gate as a scope problem (**also filed as an explicit R-UXQ-05 message-style
violation**, see Rubric verdict). Different mechanisms, same shape of defect: the
message's stated "why" doesn't match the code path that actually fired. Two
independent surfaces; watch for a third before treating as a systemic message-authoring
gap.

**5. [Sev3, single-surface] `Terminate/delete` role-gate at the M3 mission-brief
seam.** M3 task 6's brief assumes an operator can open the Terminate confirmation
dialog and Cancel out of it to complete the destructive-comprehension check; under the
OPERATOR role the button is hard-disabled (needs ADMIN) and no dialog exists to open at
all. Not a product bug (the RBAC gate itself is correct and well-labeled — "🔒 Requires
ADMIN — you are OPERATOR" was tagged `fine` in every corpus that met it), but the
mission narrative's assumption doesn't hold at this RBAC tier; the arc's "read the
dialog wording" sub-goal was never reachable this run.

**6. [Catalog-maintenance, not a live defect] Two catalog entries are stale relative to
what this run actually observed.** (a) **R-AUD-07**: catalog says the ticket-capture
field is "dark in the UI... NOT-FOUND expected" (as of 2026-07-13); this run
independently found and used a "Ticket ID (optional — recorded with the audit row and
linked in the operations log)" field in **two** missions (M3-T5, M4-T3) with identical
verbatim copy — real, corroborated, shipped. (b) **R-SAFE-05**: catalog says (as of
2026-07-13) "no WRITE path anywhere in production code... a usability MISSION cannot
exercise this goal's FIXTURE at all"; this run's M3 (protected-instance skip in a bulk
job) and M6 (protected-instance block on every verb) both cleanly exercised a live,
correctly-marked protected instance — consistent with the repo's protected-instance
milestone (#165/#172/#184) shipping 2026-07-14, after the catalog's last update.
**Action: `spec-sync` pass on GOAL-CATALOG.md to correct both entries' BUILT status and
retire their "expected NOT-FOUND" success criteria.**

**7. [Sev4, minor, positive-leaning] Engine lifecycle badge ambiguity + off-by-one +
diagram accessible-name gaps** — three independent Sev3/Sev4 single-surface findings
(M8-T1 stale-looking initial "Probe failed" badge with no cause disclosed; M5-T4's
"+38 more versions" rendering only 37 rows; M9-T2's diagram SVG accessible name not
naming the failing activity) are recorded in `results.jsonl` per-arc but too minor and
too scattered to rank as their own themes — listed here for completeness per the
"findings, most-severe first" convention, not because they threaten the gate.

---

## The 6 known MUST-v1 gaps — this run's evidence

All six were independently re-exercised live this run (via M7, which requires prior
missions' audit rows to exist — the mission ran last per RUN PROTOCOL wave discipline)
and **all six passed with citations**:

- **R-AUD-05/a (shift report, produce)** — RE-CONFIRMED BUILT, with a nuance. Tester
  found and used "Copy shift report" on `/audit`; it generated a structured report
  text. No dedicated fleet-wide free-text "my shift note" composition surface exists
  beyond that — the operator had to combine it with their own improvised summary.
  Genuine partial-coverage evidence for the plan, not a regression.
- **R-AUD-05/b (shift report, consume)** — RE-CONFIRMED BUILT. Full 17-row night-shift
  inventory produced from `/audit`, including a correctly-flagged still-unresolved item
  (the 8th protected-skip case from M4's bulk retry).
- **R-AUD-08 (audit CSV export)** — RE-CONFIRMED BUILT. "Export CSV" downloaded a
  genuine `operations-log.csv` with correct `content-type`/`content-disposition`
  headers.
- **R-AUD-09 (attribution caveat)** — RE-CONFIRMED BUILT. The exact caveat text
  ("Engine-side history attributes these actions to the shared service account — this
  log is the authoritative WHO") is present on the per-instance Audit & Notes tab.
- **R-BAU-01 (error-group acknowledge)** — RE-CONFIRMED BUILT. Group muted into
  "Acknowledged (N)" without hiding data; the dialog states the auto-resurface
  guarantee (growth / new version / expiry) before commit.
- **R-BAU-02 (leak views)** — RE-CONFIRMED BUILT, with a nuance. "Active > 30 days"
  is answerable via Search's Status+Started-before filters (definitive
  cross-engine-confirmed zero returned) — but the purpose-built one-click home-page
  "Leak views" widget covers SUSPENDED only, despite its name/description implying
  ACTIVE coverage too. Worth a copy fix or a companion ACTIVE-leak link.
- **R-L3-01 ("Explain this status")** — RE-CONFIRMED BUILT. Full falsifiable evidence
  trail: a named Plan, 6 logged engine calls (URL/status/latency/timestamp), and a
  flag-by-flag verdict table, all reached from the status chip itself.

No hallucination canary fired anywhere in this set — every claim carried an
element-level citation, and none contradicted a genuinely-still-absent surface.

---

## Rubric-corpus verdict (R-UXQ-05 / R-UXQ-06)

**R-UXQ-05 (message style) — one confirmed violation, Sev2, not Sev1.**
M7-T8's publish-rejection alert states the wrong reason for its own gate: *"You don't
have permission to publish this scope — publishing a wildcard scope needs ADMIN."*
fired identically on a view scoped to exactly one engine, when the actual gate is a
flat ADMIN-role check with no scope logic involved at all — verified by the tester
submitting three different scope shapes (all rejected identically) then succeeding
immediately as `admin` at an equivalent scope. This fails the [what happened][why/gate]
half of the triple: the "why" given is not the real why. Two further messages were
independently flagged `confusing` by testers for naming the wrong referent (filter vs.
selection, M4-T3) or for splitting one page's correlation ID across 3 different values
with no primary designated (M5-T3) — both borderline/Sev3, recorded under Themes #4
and the findings list rather than as separate R-UXQ-05 tallies, since the [what][why]
structure itself is intact in both; only the referent/precision is off.

**R-UXQ-06 (notification budget) — zero violations found.** No tester in this corpus
reported an unsolicited modal, a stacked banner, or an outcome that lived only in a
vanished toast. Every mutating action this run left a persistent, re-readable trace
(status badge, grid row, or audit/operations-drawer entry) in addition to any toast —
consistent with a full pass.

**Gate rubric line ("zero Sev1 R-UXQ-05/06 violations") — holds.** The one confirmed
R-UXQ-05 violation is Sev2. (The run still fails the gate overall, via the separate
R-SEM-12 wrong-target finding under Themes #1 — the rubric line and the
critical-finding line are independent gate conditions and both are evaluated here on
their own terms.)

---

## Protocol violations

None found. All 11 missions' transcripts were reviewed for the forbidden-move list
(hand-editing URLs, guessing routes, reading page source/JS, calling the API directly,
re-submitting a mutation): zero instances. One prior-run protocol violation (M3
guessing a raw `/instances/engine-a/<id>` route, from the `2026-07-16-m3-m6-recert`
history) does not recur in this run's M3 transcript — the search path and the omnibox
resolver both worked normally throughout, so no fallback-route guess was ever needed.

The give-up budget (15 interactions) was exceeded once, by design, with the deviation
explicitly logged: M9 task 1 ran ~46 raw key presses. The tester's own protocol note
explains why it continued past 15 — every individual Tab press was succeeding
predictably (no dead ends, no repeated failed attempts), so length itself, not
confusion, was the obstacle, and completing the arc produced materially higher-value
data (proof the retry genuinely works keyboard-only) than aborting mid-arc. Flagged
here per the tester's own request; not treated as a protocol violation since the
give-up rule's actual triggers (3 strategies exhausted / 2 consecutive interactions
yielding nothing new / same element retried twice) never fired.

---

## Environment / staging notes

- **degraded**: [
  "F-G7 (M5's engine-legacy stop/verify fixture) did not stage as intended — the
  registry row was already soft-deleted (lifecycle=removed, removed_at=2026-07-11)
  five days before this run, unrelated to the runner's own container stop/start.
  Restoration hook confirms the underlying Docker container itself answers its REST
  management endpoint fine after `docker start`, but `GET /api/engines` correctly shows
  no 'legacy' entry since the registry row is gone, not merely disabled. This is the
  second consecutive run this exact requirement has failed to stage (see also the
  2026-07-16-m3-m6-recert report) — escalate as a harness defect (re-add the registry
  entry via a fresh four-eyes REGISTRY_ADMIN flow) rather than re-attempting silently a
  third time.",
  "Historic Flowable REST businessKey/businessKeyLike filters are confirmed
  silently-ignored on this deployment (ground-truth ticket #226): the runner's own
  staging for M3's uxrun-m3-2 arc had to fall back to full client-side pagination
  through all 1,644 historic instances on engine-a to locate the target by exact
  string match. Any BFF/mission-staging code path relying on historic
  businessKeyLike search should be treated as unverified until this is fixed upstream
  or worked around — runtime process-instances?businessKey= exact-match is unaffected
  and works correctly.",
  "M6's engine-b -> prod env-tag flip (F-G2, feeding R-SAFE-03) remains a permanent
  structural limitation, not a re-stageable gap: RegistryUrlValidator rejects
  environment=PROD unless scheme=https, checked before any hostname resolution, and no
  dev-only engine in this compose stack serves https. Correctly pre-excluded by the
  runner before tester dispatch (issue #227/#215) — zero interactions were spent
  probing it this run.",
  "GOAL-CATALOG.md's 'Known-absent surfaces' section (R-AUD-07 ticket-capture field,
  R-SAFE-05 protected-instance write path) is stale relative to this run's live
  evidence and relative to the repo's own shipped milestones (protected-instance
  #165/#172/#184, landed 2026-07-14, after the catalog's last update) — see Themes #6.
  Not an app defect; a documentation-lag finding for the next spec-sync pass."
  ]

---

## Mission verdicts (raw task-level, yes/yes-with-struggle out of total)

- M1: 7/7 yes(-with-struggle) — clean sweep; one coverage-disclosure clarity note (Theme #3).
- M2: 5/5 yes — clean sweep; one minor glossary-coverage gap (raw JUEL text).
- M3: 7/7 yes(-with-struggle) — all ground-truth-verifiable claims matched exactly; zero quiet lies.
- M4: 7/7 yes(-with-struggle) at the arc level, but task 1 carries the run's one Sev1 wrong-target finding — see Gate verdict.
- M5: 3/5 yes(-with-struggle), 2/5 blocked-by-environment (F-G7 fixture drift, R-UXQ-04) — the app-side reasoning was sound in both blocked cases, just never exercised the intended condition.
- M6: 3/5 yes(-with-struggle), 1/5 correctly pre-excluded (structural, issue #227), 1/5 is COULD-v2 report-only.
- M7: 8/8 yes(-with-struggle) — all 6 known MUST-v1 gaps re-confirmed built; one R-UXQ-05 message-style violation on the (COULD-v2, report-only) team-view publish flow.
- M8: 5/5 yes(-with-struggle) or blocked-by-environment (file-pinned access store) — entirely report-only (no MUST-v1 items); **positively re-confirms the prior run's Sev1 "Test connection" invisible-apply finding is now fixed.**
- M9: 4/4 + 1 runner-asserted yes(-with-struggle) — keyboard mechanics fully functional, one real budget-overrun finding (46 interactions for one arc).
- M10: 3/4 yes, 1/4 a genuine `no` (no next-step guidance at the paging cap) — entirely report-only (no MUST-v1 items).
- M11: 5/5 yes(-with-struggle) — clean sweep; one cURL-trust nuance (missing auth in the copy-paste command).
