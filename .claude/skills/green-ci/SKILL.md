---
name: green-ci
description: A commit to main is not "done" until GitHub Actions is green on it. The mirror→push→watch→fix loop — run the CI gates locally first (scripts/ci-local.sh), then block on the real run (scripts/ci-watch.sh) and auto-fix any red before considering the commit successful. Read before committing or pushing anything to main.
---
# Keeping CI green (process-inspector)

## 🎯 When to use
**Before every push to `main`, and after it.** Any commit that lands on `main` is incomplete
until the GitHub Actions `ci` workflow is **green on that commit's SHA**. "It builds locally"
and "the push succeeded" are NOT done — only a green remote run is. This skill is the
definition of done for a commit.

Read it before you `git push`, and whenever `main` is red.

## 🛑 Primary directive
> **A commit to `main` is not successful until CI passes on GitHub for its SHA.**
> If the push turns CI red, you own it: diagnose from the run logs, fix, re-push, and
> re-watch — in the SAME work session — until it is green. Never leave `main` red and walk
> away, and never report a push as "done" without a green run to point to.

Red `main` blocks every parallel session (this repo has several — see the memory index), so
a formatting slip that reddens the `frontend` job costs everyone. Catch it locally.

## The loop
```
   ┌─ 0. RUNNER ─────────────────────────────────────────────────┐
   │  scripts/ci-runner.sh ensure                                  │
   │  # the dockerized self-hosted runner must be ONLINE (and be   │
   │  # the ONLY one online) or every run below queues forever.    │
   └──────────────────────────────────────────────────────────────┘
              │ exactly one runner online
              ▼
   ┌─ 1. MIRROR ──────────────────────────────────────────────────┐
   │  scripts/ci-local.sh            # fast gates, cheapest-first  │
   │  scripts/ci-local.sh --fix      # auto-apply spotless+prettier│
   │  scripts/ci-local.sh --full     # + backend unit ladder       │
   └──────────────────────────────────────────────────────────────┘
              │ green locally
              ▼
   ┌─ 2. PUSH ───────────────────────────────────────────────────┐
   │  git push origin main                                         │
   └──────────────────────────────────────────────────────────────┘
              │
              ▼
   ┌─ 3. WATCH ──────────────────────────────────────────────────┐
   │  scripts/ci-watch.sh $(git rev-parse HEAD)                    │
   │  # blocks until the run finishes; exit 0 = green, else dumps  │
   │  # the failing job's steps + a log tail. Run it backgrounded  │
   │  # so the harness re-invokes you when the ~15-min matrix ends.│
   └──────────────────────────────────────────────────────────────┘
              │ red?
              ▼
   ┌─ 4. FIX ────────────────────────────────────────────────────┐
   │  read the dumped failing step → reproduce the SAME gate       │
   │  locally (table below) → fix → back to step 1.                │
   └──────────────────────────────────────────────────────────────┘
```

## The CI gates, and how to reproduce each locally
`.github/workflows/ci.yml` has five jobs. Map a red job to its local command:

| CI job / step | Reproduce locally | Covered by `ci-local.sh`? |
|---|---|---|
| `lint` → Spotless check | `cd backend && mvn -B spotless:check` (fix: `spotless:apply`) | ✅ (fast) |
| `lint` → Security audit | `./scripts/security-audit.sh` | ✅ (fast) |
| `frontend` → ESLint | `cd frontend && npm run lint` | ✅ (fast) |
| `frontend` → Prettier | `cd frontend && npm run format:check` (fix: `npm run format`) | ✅ (fast) |
| `frontend` → vitest | `cd frontend && npm test` | ✅ (fast) |
| `frontend` → Build | `cd frontend && npm run build` (watermark+enterprise+tsc+vite) | ✅ (fast) |
| `frontend` → **OpenAPI drift** | boot the BFF, `npm run gen:api`, `git diff --exit-code src/api/schema.d.ts` | ❌ needs running BFF |
| `unit` → unit ladder | `cd backend && mvn -B test` | ✅ (`--full`) |
| `docker` → image build | `docker build .` | ❌ needs docker |
| `integration` (6/7/legacy) | the **engine-harness** skill: compose up → `smoke-test.sh` → `seed.sh` → `mvn -B verify -Dit.test=…` | ❌ needs the engine matrix |

`ci-local.sh` covers the gates that cause **almost every** real red-main incident (Spotless,
Prettier, ESLint, tsc, unit tests). The three it can't cover cheaply — the OpenAPI drift gate,
the Docker image build, and the dockerized integration matrix — are what `ci-watch.sh` is for:
you let the real run prove them and react to the result. If you changed a backend DTO, run the
drift gate yourself before pushing (boot the BFF, `npm run gen:api`, commit the regenerated
`schema.d.ts` — never hand-edit it). If you touched engine-call/join logic, run the relevant
IT under the engine-harness skill first.

## scripts/ci-runner.sh — the runner must exist before the run can
CI executes on a **dockerized self-hosted runner** (`docker/ci-runner/`, container
`pi-ci-runner`, `restart: unless-stopped` so it survives reboots). GitHub queues jobs
silently when no runner is online — a push then looks "stuck", not failed. So **step 0 of
every push/PR**: `scripts/ci-runner.sh ensure` (needs `$GITHUB_PERSONAL_ACCESS_TOKEN`
exported; env-ref only). It starts the compose service if the container is down, waits
bounded for GitHub to report it online, and **fails if more than one runner is online** —
the CI harness ports are fixed remaps, so a second concurrent runner (e.g. a stray
bare-metal `~/actions-runner/run.sh`) would collide matrix legs. `status`/`logs`/`stop`
subcommands for diagnosis. The runner is ephemeral (one job per container, fresh
re-registration each time) — a brief `offline` blip between jobs of one workflow is normal.

## scripts/ci-watch.sh — the remote feedback primitive
There is **no `gh` CLI** on this box. `ci-watch.sh` talks to the Actions REST API directly with
`curl` + `$GITHUB_PERSONAL_ACCESS_TOKEN` (the PAT is env-ref only — never echo it, never pass it
as an arg; iron rule). It:
- resolves the workflow run for a SHA (expands a short SHA/ref to the full 40-char form the
  `?head_sha=` filter requires; waits up to 90s for the run to register after a push),
- blocks until the run completes (default 25-min ceiling; the integration matrix caps at 20),
- prints a per-job pass/fail table, names the failing **step**, and dumps a filtered tail of
  each failing job's log so you can diagnose without opening a browser,
- exits `0` green / `1` red / `2` no-run-or-error / `3` timeout.

Run it **backgrounded** (`run_in_background`) so the harness re-invokes you when the long matrix
finishes, instead of blocking the session for 15 minutes:
```bash
scripts/ci-watch.sh $(git rev-parse HEAD) --timeout 1500 --interval 30
```
The harness blocks a bare foreground `sleep`, but the script's internal sleep loop runs fine
inside a backgrounded task.

## Scoping a fix to committed code (parallel sessions)
Multiple Claude sessions share this working tree, so it usually holds **uncommitted work that
isn't yours**. When you fix red `main`:
- CI runs on the **committed SHA**, not your working tree. A gate can be red on `main` purely
  from *committed* files while your local `format:check` also flags *uncommitted* parallel work.
  Separate the two: find which offending files are unmodified vs. HEAD (`git status --porcelain`)
  — those are the committed cause — and fix/commit **only** those. Leave others' uncommitted
  edits untouched (see the "surface contradictions before overwriting" rule).
- Prettier config resolves by file location: checking a `git show HEAD:…` blob in `/tmp` uses
  *default* config and lies. Verify with `--stdin-filepath=<repo-relative-path>` or by running
  the real `npm run format:check` in `frontend/`.

## Optional: a local pre-push guard (defense in depth)
`ci-local.sh` is the mechanism; if you want it enforced for **every** push (human or agent),
install it as a git hook — opt-in, not committed, because it slows every push:
```bash
printf '#!/usr/bin/env bash\nexec "$(git rev-parse --show-toplevel)/scripts/ci-local.sh"\n' \
  > .git/hooks/pre-push && chmod +x .git/hooks/pre-push
```
Bypass a known-safe push with `git push --no-verify`. Prefer running `ci-local.sh` explicitly in
the loop above over relying on the hook — the hook is a backstop, not the plan.

## Anti-patterns
- ❌ Push and move on without watching the run. A "successful push" ≠ green CI.
- ❌ "Fix" red main by reformatting the whole tree — that sweeps in other sessions' uncommitted
  work. Scope to the committed offenders.
- ❌ Re-run/retry the failing job hoping for green without a diagnosis. The gates are
  deterministic (zero-flake doctrine); a failure is a real defect in the committed code.
- ❌ Put the PAT in a command line, a log, or the script's args. Env-ref only.
- ❌ Skip the OpenAPI drift gate after a DTO change because "ci-local was green" — ci-local
  doesn't run it. Regenerate `schema.d.ts` from a booted BFF and commit it.
