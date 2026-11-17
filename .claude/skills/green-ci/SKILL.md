---
name: green-ci
description: A change is not "done" until GitHub Actions is green on the pull-request head. The mirror→PR→watch→fix loop — run the CI gates locally first (bash ci/contract.sh ci-light), open a pull request, block on the head's run (scripts/ci-watch.sh), then land it with scripts/pr-queue.sh. Read before opening or queueing a pull request. Direct pushes to main are rejected by the ruleset.
---
# Keeping CI green (process-inspector)

## 🎯 When to use
**Before every pull request, and before you merge it.** A change is incomplete until the
GitHub Actions `ci` workflow is **green on the pull-request head SHA**. "It builds locally"
and "the push succeeded" are NOT done — only a green remote run is. `main` rejects a direct
push (repository ruleset). This skill is the definition of done for a change.

Read it before you open a pull request, and whenever `main` is red.

## 🛑 Primary directive
> **A pull request is not successful until CI passes on GitHub for its head SHA and the queue has landed it.**
> If that run is red, you own it: diagnose from the run logs, fix, push the branch, and
> re-watch — in the SAME work session — until it is green. Never queue red, and never
> report a change as "done" without a green run to point to. Direct `git push origin main`
> is rejected. The admin ruleset bypass is RUNBOOK §0, for emergencies only.

Red `main` blocks every parallel session (this repo has several — see the memory index), so
a formatting slip that reddens the `frontend` job costs everyone. Catch it locally.

## The loop
1. `scripts/ci-runner.sh ensure` — at least one runner slot online, no foreign slot-less runner.
2. Mirror: `bash ci/contract.sh ci-light` (`scripts/ci-local.sh` is a wrapper; `--fix` applies Spotless + Prettier;
   `CI_PART=lint|unit|frontend|e2e` runs one part). Heavy parts: `CI_PART=docker bash ci/contract.sh ci-heavy`.
3. Open a pull request (`main` rejects a direct push, ruleset #449).
4. `scripts/ci-watch.sh <head-sha>` (background it): exit 0 = PR lane green on the head; red dumps the failing step.
5. Fix a red in the SAME session: reproduce the gate with the contract command, fix, push, re-watch.
6. `scripts/pr-queue.sh <PR>` then `scripts/pr-queue.sh --watch <PR>`: the queue builds an `mq/**` batch, runs light
   AND heavy on it and fast-forwards `main`. The batch is the gate; a red batch unlabels the culprit with a comment.
   See the `pr-queue` skill.

## The CI gates, and how to reproduce each locally
`ci/contract.sh` is the single definition; `ci.yml` calls it through ci-lib (`CI_LIB_TAG`).
- PR lane (`ci-light`): `lint` (Spotless, security-audit, shell-syntax, selftests), `unit`, `frontend`
  (ESLint, Prettier, Vitest, build, OpenAPI drift), `e2e`.
- Batch only (`ci-heavy`): `docker` image build and the `integration` legs (engine matrix; engine-harness skill).
  They never report on a pull request, so GitHub's Merge button stays blocked for non-admins: landing is the queue's job.
If you changed a backend DTO, regenerate `schema.d.ts` from a booted BFF (`npm run gen:api`), never by hand.

## scripts/ci-runner.sh — the runner slots must exist before the run can
CI executes on dockerized self-hosted **runner slots** (`docker/ci-runner/`, containers
`pi-ci-runner-s1..sN`, `restart: unless-stopped` so they survive reboots). The harness
ports are fixed remaps, so each slot carries its own disjoint port block as container env
(ci.yml honors it, slot-1 fallback); jobs serialize within a slot and parallelize across
slots collision-free. GitHub queues jobs silently when no runner is online — a push then
looks "stuck", not failed. So **step 0 of every push/PR**: `scripts/ci-runner.sh ensure`
(needs `$GITHUB_PERSONAL_ACCESS_TOKEN` exported; env-ref only). It starts the compose
services if slots are down, waits bounded for ≥1 to report online, and **fails if any
foreign slot-less runner is online** (e.g. a stray bare-metal `~/actions-runner/run.sh`) —
a runner without a port slot races whichever slot runs beside it. `status`/`logs <slot>`/
`stop` subcommands for diagnosis. Runners are ephemeral (one job per container, fresh
re-registration each time) — a brief `offline` blip between jobs is normal.

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
`ci-local.sh` (wrapper over the contract) is the mechanism; if you want it enforced for **every** push (human or agent),
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
- ❌ Press Merge or push `main` — label through `scripts/pr-queue.sh`; the batch is the gate.
