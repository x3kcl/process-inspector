# Bulk dead-letter move — spike (#516)

Date: 2026-10-21. No production code.

Both 6.8 and 7.1 expose `POST /management/deadletter-jobs` with `{action: move|moveToHistoryJob, jobIds[]}`. The published contract has no response body (HTTP 204). There is no per-id result.

Today's error-class retry is one call per job so each item can be `ok`, `failed`, or `skipped (already resolved)`. A bulk call cannot say which id moved. Reconstructing that means a follow-up `GET management/deadletter-jobs/{id}` per id. That is the current fan-out plus the bulk call, so it does not reduce engine load.

Verdict: **reject** the bulk endpoint as the dispatcher. Per-item reporting cannot be honoured from the response. A later chunked strategy (chunks of N, then per-id verification) is the upgrade only if a timed run shows the bulk call is cheaper even with those GETs. This note does not include a 100 / 1,000 / 5,000 timing; that measurement is the gate before any code change.
