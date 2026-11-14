# Usability run report: `2026-11-14-aug-surfaces` (issue #478)

Catalog v1.0 + the M14–M16 additions from this change · BFF/SPA at `origin/main` `7091fc9`
plus this branch's docs commit · engines engine-a/engine-b (Flowable 6.8) and engine-7 (7.1),
the dev compose stack · testers: `claude-sonnet-5-5`, naive, briefs only, one browser
(playwright MCP), run **one at a time** · orchestrated and ground-truth verified by the
issue's model · 2 testers per mission, 6 traces, 32 briefed tasks, 30 graded (M15 task 5
returned by neither tester as a task row, see the protocol slip below).

## Gate verdict: **PASS on completion, with 2 Sev2 defects filed**

- Exit gate (usability-testing skill): every critical task `canComplete = yes`. Result:
  **30/30 graded tasks completed** (of 32 briefed), 19 `yes`, 11 `yes-with-struggle`,
  **0 `no`**, 0 `blocked-by-environment`. The two ungraded rows are M15 task 5 (the reply
  to the colleague); the M15 decision itself (tasks 3–4) is graded `yes` for both testers.
- No tester claimed something the BFF/engine contradicts (ground truth below). Zero
  invisible applies and zero guard bypasses. No tester confirmed a mutation:
  `audit_entry` holds no tester-originated row after staging ended (11:54:22Z).
- The pass is about **whether operators can complete the arcs**, not about whether the
  surfaces are right: two Sev2 defects (#660 fabricated recency, #661 rank vs card text)
  made both M14 testers trust the ordering less, and both are product defects.
- Scope note: all three goals are R-SEM-25 / R-BAU-10 extensions on a **flag-on BFF**
  (`inspector.triage.attention-ordering` ships default-off; ALARM-COST-MODEL.md §7 gate NOT
  MET). This run certifies the surfaces' ergonomics if and when the flag flips. It is no
  evidence for flipping it.

## Mission verdicts

| Mission | Goal | User | T1 | T2 | Result |
|---|---|---|---|---|---|
| M14 attention badge + rationale | R-SEM-25/d | viewer | 2 yes · 3 struggle | 1 yes · 4 struggle | **pass with struggle.** Both read the visible rationale correctly and named the thin-evidence clauses; both found the ordering rule only via the hover title; both caught the rank-vs-text contradiction (#661); T2 caught the stale "last seen just now" (#660). |
| M15 decide on a LIKELY class | R-SEM-25/e | responder | 4 yes | 4 yes | **clean pass.** Both quoted the badge with its #388 suffix, called the rate historic, chose "act on the 22 dead-letters, nothing is retrying", reached the Retry-group confirmation for the right scope and cancelled. The #388 copy fix is confirmed working on the card face. |
| M16 attributed actions | R-BAU-10/b | operator | 4 yes · 2 struggle | 4 yes · 2 struggle | **pass with struggle.** Both reconstructed episode 1's actions exactly (2 single retries by operator, 1 bulk retry of 4 by responder, all failed again) and episode 2's absence of actions, but only from the global Ops log by timestamp and job id (#662). |

Protocol slip: both M15 testers returned only 4 task rows. M15-T1 skipped task 5 (the reply to the colleague). M15-T2 answered it in
`protocolNotes`, and the reply holds up ("will not clear on its own. The 22 need a
retry"). Not counted against the surface.

## Themes (clustered by root cause)

1. **The attention score says one thing, the card text says another (Sev2, 2 surfaces:
   `/` ranking + `/incidents` rationale).** Two separate causes with the same effect: the
   operator cannot trust the order.
   - #661: S demotes `selfHealGhost` (22 failing, DLQ 22, retrying 0, flooding) to last,
     below a 3-member class, while the same card says
     `— not the 22 dead-lettered (no retries left)`. 2/2 M14 testers flagged it unprompted.
     It revisits #388's rejected option 2 with post-fix evidence.
   - #660: classes outside the 28-day model window (quiet, resolved, last seen 2026-08-09)
     render `N failing · last seen just now` on `/incidents`, beside the correct
     `last seen … 96d ago`. Found by evaluator API sweep, independently seen by M14-T2.
2. **The ordering is explained only by hover-only text (Sev3, `/`).** #663: both M14
   testers found the rule only via the `title` of the `ranked by attention` pill. Both read
   `no resolve-time history · no self-heal history` as a warning or an unexplained rank
   input. The "normal state" gloss exists only on `/incidents`.
3. **Episode attribution is computed but not shown (Sev3, `/incidents/:id` + Ops log).**
   #662: `actionsDuringEpisode` is served per episode and rendered nowhere. The arc
   completes only by cross-referencing the Ops log, whose rows carry no incident or
   definition link.
4. **Copy (Sev4).** #664: the ledger intro points at "Stage-0 triage cards above" that are
   on another page, and shows the register ID `R-BAU-10`.

Observed once, not filed (single tester, low value or by design):
- "Recent bulk retries … · error class responder": the scope label runs into the actor
  name (M16-T2).
- The normalized heading shows `gt #` while the sample shows `gt 0`. This is by design
  (signature normalization, R-SEM-03), but 3 testers mentioned it.
- `🔒 Requires OPERATOR on every engine in this group — missing on engine-a` sits next to
  an enabled "Retry group". Both M15 testers asked whether the retry would be allowed. It
  is (RESPONDER floor; the lock belongs to Acknowledge). Watch it in the next run.
- The `/incidents` sections (Regressed → Open → Quiet) order differently from `/`. This is
  by design (PRODUCT-GUIDE §5). Both M14 testers worked it out.

## Ground-truth cross-check

| Claim (tester) | Verified against | Result |
|---|---|---|
| M14/M15: `selfHealGhost` 22 failing, DLQ 22, retrying 0, rank 4 of 4 | `GET /api/triage` errorGroups + `attention.factors` (S = 0.2529, flooding true) | ✔ |
| M14: order 47 → 12 → 3 → 22 | `GET /api/triage` scores 6.02 / 3.17 / 2.32 / 1.92 | ✔ |
| M14-T2: "last seen just now" vs "96d ago" | `GET /api/incidents`: incidents 5/6/7/8/23 `quiet:true`, `lastSeen` 2026-08, rationale "last seen just now", `ageSeconds 0` | ✔ (#660) |
| M15: badge `usually self-heals (12/13 past spells, typically ≤ 1 min)` | `GET /api/incidents/3` selfHeal `{lane SELF_HEAL_LIKELY, n 13, healed 12, wilsonLow 0.667}` | ✔ |
| M15: Retry-group dialog "21 failing instances in this scope" on Engine A | countsByEngine engine-a demoSelfHealingBaseline v1 = 21, engine-b = 1 | ✔ |
| M15: nothing confirmed | `audit_entry` rows after 11:55Z: none | ✔ |
| M16: 2 episodes; ep 1 11:47:11–11:52:57 resolved by operator, "partner quota config corrected, stuck syncs cleared", OPS-4711, peak 4; ep 2 ongoing from 11:54:22, peak 3 | `GET /api/incidents/24` episodes 30/31 | ✔ |
| M16: ep 1 = 2 operator `retry-job` + responder `bulk:retry-job` ×4 items, all re-failed | `audit_entry` 11:49:16 ×2 (operator), 11:50:17 bulk + 4 items (responder); `actionsDuringEpisode {count 6, retry-job 6, ok 6}`; DLQ back to 4 at 11:51:47 | ✔ |
| M16: no action in ep 2 | `actionsDuringEpisode {count 0}`; no audit rows after 11:54:22 | ✔ |
| M16-T2: out-of-band termination has no Ops-log row | staging deleted the instances engine-side (`deleteReason=partner-team-cleanup`) | ✔ (by design of the fixture) |

Hallucination canaries: none fired. No tester claimed a per-episode action list on the
incident page, which does not exist (#662). Both said they had to go to the Ops log.

## Staging (what the testers actually saw)

- **BFF** started from the issue worktree with `INSPECTOR_TRIAGE_ATTENTION_ORDERING=true`
  (M13-arm-B mechanics: a dedicated process, never a live flip). **Two deliberate
  deviations to fit the session budget:** `INSPECTOR_SNAPSHOT_SAMPLE_INTERVAL=20s` /
  `BUCKET_WIDTH=20s` and `INSPECTOR_SELFHEAL_DWELL_CYCLES=3` (≈1 min of dwell instead of
  ≈10). The self-heal **floor was not lowered** (10). Neither deviation changes any copy
  or rendering path the testers exercised. Both make the lane commit sooner, which is why
  this fixture is usability evidence only, never gate evidence (RETRYING-RISK-LANE §7.2).
- **M15 fixture:** `PI_SEED_SELF_HEALING=1 docker/seed.sh` on engine-a/b, then 13
  sequential non-overlapping `demoSelfHealing` spells (`uxrun-478-heal-1..13`,
  `healDelay PT20S`) → LIKELY at n=12/12 (Wilson LB 0.757). Then 20
  `demoSelfHealingBaseline` instances (`uxrun-478-base-*`) as the §8.2 step 5a count
  inflation, one escalated spell, n=12/13, LB 0.667 (above the 0.60 exit).
- **M16 fixture:** run-unique definition `uxrunPartnerSync` (copy of
  `error-zoo-missing-property` failing on `${partnerQuota.total}`; scratch file, not
  committed) on engine-a. Lifecycle as in MISSIONS.md M16 STAGING: incident 24.
  Its BFF actions ran **after** the self-heal spells finished, so no audit row could
  confound a spell (the §3.3 engine+timestamp join).
- **Placeholders:** `ATTN_HIGH_CLASS` = `execution exception` (UnknownHostException, 12),
  `ATTN_LOW_CLASS` = the `selfHealGhost` expression (22): the divergent pair (higher rank,
  smaller count). `HEAL_CLASS` = `selfHealGhost`. `LEDGER_CLASS` = `partnerQuota`.
- **Shared-stack context:** the dev ledger held 23 incidents from August (IT residue,
  quiet). That residue is what exposed #660. `demoFailingPayment` grew 19 → 47 during the
  run (other agents on the shared engines). The stack was left running.
- **Tester harness:** `claude -p --model claude-sonnet-5-5` with only the playwright MCP
  allowed (`--strict-mcp-config`, file/shell tools disallowed, empty working dir), the
  usability-run PROTOCOL verbatim, and the TESTER_SCHEMA as `--json-schema`. This stands
  in for the Workflow runner, which this session could not invoke. Total tester cost
  ≈ $1.16.

## Files

- `results.jsonl`: one row per tester × task (30 rows).
- `traces/M1{4,5,6}-T{1,2}.json`: each tester's structured output, verbatim.
- `traces/brief-M1{4,5,6}.txt`: the briefs exactly as handed over, with placeholders
  filled.

## Issues filed (label `usability`)

#660 (Sev2) · #661 (Sev2) · #662 (Sev3) · #663 (Sev3) · #664 (Sev4)
