# Housekeeping wire shape — 2026-10-22

Spike for #527. Live 100/1000 timing was not run: no engine harness was attached to this session. The recommendation follows the response shape, which does not need a clock.

## What the routes return

| Path | Both 6.8 and 7.1 | Per-item result |
|---|---|---|
| `POST history/historic-process-instances/delete` `{action:delete, instanceIds, deleteReason}` | id list, not a query | no per-id body |
| `DELETE history/historic-process-instances/{id}?deleteReason=` | one id | HTTP status of that id |
| `DELETE cmmn-history/historic-case-instances/{id}` | one id, no `deleteReason` parameter | HTTP status of that id |

A bulk POST that answers 204 or a single status cannot tell a housekeeping run which id was refused because it was still running. That is the same reason bulk `POST management/deadletter-jobs` was rejected for the job dispatcher.

## Recommendation for #488

Use per-id `DELETE` through the existing bulk job. Each item is `ok`, `failed`, `skipped`, or `not_run`. Stop sets the cancel flag and the next item is left `not_run`; the call already in flight finishes.

Cap a run at 5,000 ids. If the finished-before query still has more, refuse and ask for a narrower definition or date. Do not delete a silent prefix of a larger set.

Deletes use the `housekeeping` breaker and bulkhead (1 permit), not the interactive lane and not the sampler lane.

Chunked bulk POST is the upgrade only after a timed measurement shows the per-id fan-out is too slow and the engine documents a per-id failure body.
