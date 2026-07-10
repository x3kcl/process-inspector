# 🔁 USABILITY RUN — how to spin it up (on demand or nightly)

The reusable, goal-based usability harness. Three artifacts, one entry point:

| Artifact | Role |
|---|---|
| `docs/usability/GOAL-CATALOG.md` | **Evaluator-only** rubric registry: one goal per register requirement (120 IDs), testability class, BUILT state, fixture gaps, run protocol, exit gate, result schema. Version-bump on every change — verdicts are only comparable within one catalog version. |
| `docs/usability/MISSIONS.md` | **Tester-visible** briefs: 9 incident narratives (M1–M9) bundling the UI-class goals into realistic arcs, with `{{PLACEHOLDER}}` tokens the runner fills from live engine state. Never show testers the catalog. |
| `.claude/workflows/usability-run.js` | The runner: stages fixtures → runs missions serially with naive Sonnet testers over the playwright MCP → ground-truth-verifies mutations over engine REST → restores fleet staging → reconciles to a gate verdict + `results.jsonl` + `RUN-REPORT.md`. |

## Prerequisites
1. Engine matrix + Postgres up: `docker compose -f docker/docker-compose.dev.yml up -d`
   (engines :8081/:8082/:8083/:8084, PG :5433).
2. BFF up on :8085: `cd backend && ENGINE_A_PASSWORD=test ENGINE_B_PASSWORD=test ENGINE_7_PASSWORD=test mvn spring-boot:run`.
3. Frontend dev server on :5173: `cd frontend && npm run dev` (or pass `app` arg pointing
   at a packaged BFF serving the SPA).
4. The `playwright` MCP server connected (`.mcp.json`, dockerized, host network).
5. Seeding is handled BY the run (stage phase runs `docker/seed.sh`, idempotent — it also
   seeds the usability fixtures F-G1 wide parent + F-G6 hostile instance).

## On-demand (any Claude Code session in this repo)
Ask: **"run the usability-run workflow"** — or explicitly:
```
Workflow({ name: "usability-run",
           args: { runId: "<date>-adhoc",
                   resultsDir: "docs/usability/results/<date>-adhoc" } })
```
Useful args: `missions: ["M1","M3"]` (subset re-test after a fix — re-test with the SAME
missions that found the defects, per the skill's exit gate), `testerModel`, `app`, `bff`.

## Nightly
The run needs a live Claude session (agents + MCP), so it rides Claude Code scheduling,
not GitHub Actions:
- **Scheduled agent (recommended):** `/schedule` a nightly routine in this repo with the
  prompt: *"Ensure the dev stack is up (compose + BFF + frontend, see
  docs/usability/RUNBOOK.md), then run the usability-run workflow with runId
  nightly-<date> and resultsDir docs/usability/results/nightly-<date>; compare
  results.jsonl against the previous nightly (same catalogVersion): any yes→no flip or
  new Sev1/Sev2 fails the night — open a summary + push the report."*
- **Or host cron:** `claude -p '<same prompt>' --permission-mode acceptEdits` from the
  repo at 02:00.
- Statistics: nightly gate missions run N≥3 testers (pass `missions` repeated / bump N in
  the script's loop — see GOAL-CATALOG "Nightly statistics"); single-tester passes are
  flukes with stochastic agents. Track step-count drift, not just verdicts.

## Safety invariants (the run enforces these — don't relax them)
- Wave order is load-bearing: read-only → mutating serialized → fleet-staging exclusive;
  M7 last of wave 2 (consumes audit rows). One shared browser ⇒ missions never parallel.
- Destructive verbs ONLY against `uxrun-*`-tagged sacrificial fixtures.
- Fleet stages (engine stop, prod flip, read-only flip) are restored AND verified by
  post-hooks; a run that can't verify restoration must say so in the report.
- Testers get briefs only; catalog + BUILT flags stay evaluator-side (hallucination
  canaries depend on it).
- Prod engines: NEVER. This harness is dev-stack-only by construction (S3 rule,
  TEST-STRATEGY §10).

## Relationship to the register
This harness is the standing rehearsal for **R-TEST-08** (UAT) and the seed of
**R-BAU-09** (training profile: seeded scenarios + scripted reset = `seed.sh` + waves).
The M6 human UAT still runs with practicing engineers; agents keep it honest in between.
