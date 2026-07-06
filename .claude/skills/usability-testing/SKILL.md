---
name: usability-testing
description: The multi-agent test→fix→implement loop that proves an inspector surface is ergonomic before it ships — goal-based task scripts (find→diagnose→fix→verify arcs), N naive tester agents against the real rendered UI, reconcile into root-cause themes, a fix panel, re-test to an exit gate. Read before shipping or substantially changing the Search panel, Results grid, Details panel, action toolbar, or bulk-operation flow — or on an explicit "prove the ergonomics" ask.
---
# Usability testing loop (process-inspector)

*Ported from the flap `usability-testing` skill; re-grounded in this tool's user — a support
engineer under incident pressure, often at 3am, who did NOT build the tool.*

## Entry trigger (gate the cost)
Spin the loop only for: a net-new surface (panel/dialog/flow), a substantial change to an
existing one (grid re-architecture, a new destructive path, bulk-flow changes), or an explicit
"prove the ergonomics" ask. Copy tweaks ride a diff review.

## The loop
1. **AUTHOR panel** (support-lead + UX + product seats — each its OWN agent, never one agent
   ventriloquising all seats) writes GOAL-based task scripts. A case is a goal/outcome arc —
   *"a first-time on-call engineer must be able to ___ and KNOW it worked, without a manual"*
   — never a widget checklist ("sees the Retry button" is invalid).
2. **N tester agents** (cheaper model tier) execute the scripts as naive first-time operators
   against the REAL rendered surface (run the dev stack; drive/inspect the actual UI, not the
   source). Each emits element-cited findings + a per-task `canComplete` verdict.
3. **UX reconciliation**: cluster N reports by shared root cause (not by page); a theme
   hitting ≥3 surfaces outranks any single-surface major.
4. **FIX panel** (designer + UX + lead dev seats) scores value/feasibility/fit, converges on
   a fix that EXTENDS shared components — a second confirm-dialog/banner/grid variant is
   itself a rejected finding.
5. **Implement** with a red-first regression test, **re-test with the SAME scripts** that
   found the defects, iterate to the exit gate: every critical case `canComplete=yes`.

## The standing spine — pre-stamped on every inspector surface
1. **FIND** — from a symptom ("order 4711 is stuck"), locate the instance across engines
   without knowing which engine owns it.
2. **ORIENT** — within ~10s of opening Details, state what the instance is doing and WHY it's
   stuck, from on-screen text (status, failing node, error snippet — no jargon-only labels).
3. **DIAGNOSE** — reach the root evidence (stacktrace, variable values, prior audit actions)
   in ≤2 clicks from Details.
4. **FIX** — perform the correct action; before commit, read back the blast radius (what,
   how many, which engine, reversible or not).
5. **OUTCOME** — after the action, learn succeeded/failed and the instance's NEW state
   without hunting (fire-and-forget invisible-apply is the classic critical).
6. **RECOVER** — a partial bulk failure shows exactly which items failed and offers
   retry-failed-only; an engine-down search visibly degrades instead of blanking.

## Standing probes (the classics that always bite)
- Invisible apply: action POST succeeds, UI shows nothing → operator clicks again (double
  terminate). Every action needs an outcome banner + state re-fetch.
- Mis-armed destructive confirm: dialog defaults to the dangerous choice, or doesn't state
  the count/environment. Prod engines require typed confirmation (`corrective-actions` §3).
- Empty-selection submit: bulk toolbar enabled with 0 rows selected.
- Error-state blindness: engine badge shows red but the search results give no hint that
  results are partial.
