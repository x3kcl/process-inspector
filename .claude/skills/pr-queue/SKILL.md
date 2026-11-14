---
name: pr-queue
description: Landing a pull request through the ci-lib merge queue with scripts/pr-queue.sh. Read when the pull-request head is green and the work is done, or when a queue step was refused or is waiting.
---
# Queue and land a pull request

`main` takes changes only through the queue (`merge-queue@process-inspector` on hp02, one-minute timer).
Never press Merge, never push `main`. Heavy checks (`docker`, `integration`) report on the `mq/**` batch,
not on the pull request, so the batch is the real gate.

1. `scripts/ci-watch.sh <head-sha>` is green (PR lane: lint, unit, frontend, e2e).
2. `scripts/pr-queue.sh <PR>` adds the `queue` label. Exit 1 = the head's `ci` run is not green: fix it, do not force it.
3. `scripts/pr-queue.sh --watch <PR>`: exit 0 merged; exit 1 the queue removed the label (its comment is printed:
   a red batch or a rebase conflict; read the failing job log before assuming it is yours); exit 3 timeout.
4. `scripts/pr-queue.sh --status <PR>` prints label, head run, merged flag.

Offline proof of the script: `scripts/pr-queue-selftest.sh` (stubbed `curl`; part of `lint`).
Standing approval to queue is the operator's call: queue only when the task or the operator says so.
Prod/demo deploy is separate. The admin bypass (RUNBOOK §0) is the emergency path only.
