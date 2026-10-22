# Start here

Two pages for a new reader. The long docs stay the spec. This page only says where each kind of truth lives and how to run the thing.

## What it is

Process Inspector is a management plane for **many independent Flowable engines**. It searches, explains, and fixes process instances through each engine's REST API. It does not open an engine database.

The BFF (Spring Boot, `backend/`) fans out. The SPA (React, `frontend/`) is the console. Postgres holds the Inspector's own audit, views, and bulk jobs.

## The three stages

1. **Triage** (`/`) — what is broken, how much, on which engine. Counts and error groups, not a grid of instances.
2. **Search** (`/search`) — the URL is the query. A shared link replays the same filter.
3. **Detail** (`/inspect/{engineId}/{id}`, and `/case/…` for CMMN) — one instance, full page, deep-linkable. `?tab=` picks the tab.

Ctrl+K focuses the header omnibox from any page. Paste an id or a business key.

## Do-no-harm rails

Engines are often already in trouble when someone opens this tool. Calls are bounded and circuit-broken. A dead engine is an error envelope, not a hung page.

Mutations go through the guard ladder: role, tier, reason, and a confirm token on production for the destructive verbs. Nothing retries on its own. Every mutation is audited. The audit chain fails closed.

A disabled engine stays readable and stays out of search. "Disabled" is not "deleted".

## Where the truth lives

| Question | Document |
|---|---|
| What should it do? | [SPECIFICATION.md](SPECIFICATION.md) |
| How is it built, and why? | [ARCHITECTURE.md](ARCHITECTURE.md) |
| What landed, in what order? | [IMPLEMENTATION-PLAN.md](IMPLEMENTATION-PLAN.md) |
| How do I operate it? | [OPERATIONS.md](OPERATIONS.md) and [RUNBOOK.md](RUNBOOK.md) |
| How do I use the console? | [PRODUCT-GUIDE.md](PRODUCT-GUIDE.md) |

If a behavior change does not update the matching doc, the change is incomplete.

## How to run it

Backend and the dev engines: `docker compose` from `docker/` (see [OPERATOR-QUICK-START.md](OPERATOR-QUICK-START.md)). The BFF listens on port 8080 in the runbook topology; the dev compose publishes its own ports.

Frontend, from `frontend/`:

```bash
npm ci
npm test
npm run dev
```

Dev sign-in is the built-in ladder (`viewer`, `responder`, `operator`, `admin`, password `dev`).

Before a pull request, mirror the cheap CI gates with `scripts/ci-local.sh` and make sure a runner slot is up (`scripts/ci-runner.sh ensure`). `main` accepts changes only through a pull request. The admin bypass on the ruleset is the emergency path, written in [RUNBOOK.md](RUNBOOK.md) §0.

## How to change it

- Engine HTTP calls: read `.claude/skills/flowable-rest` first. Whitelisted paths only. No generic proxy.
- A mutating endpoint: read `.claude/skills/corrective-actions`. Audit, role, guard, no auto-retry.
- An integration test: read `.claude/skills/engine-harness`. Seed over REST, never insert into `ACT_*`.
- Secrets are env refs. They do not go in config values, logs, or API responses.
- Do not remove the bpmn.io watermark.

Contribution steps, the security contact, and the issue templates are in [CONTRIBUTING.md](../CONTRIBUTING.md) and [SECURITY.md](../SECURITY.md).
