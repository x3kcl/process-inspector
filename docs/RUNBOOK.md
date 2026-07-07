# 📟 RUNBOOK — Flowable Process Inspector (BFF)

For the Tier 2/3 engineer paged at 3 AM. Symptom-first; every section ends in a decision.
Deliverable per OPERATIONS §10 / SPEC §13. Deep background: OPERATIONS.md; who-did-what:
[AUDIT-ATTRIBUTION.md](AUDIT-ATTRIBUTION.md).

## 0. Facts you need before touching anything

- **Topology**: single BFF instance (Spring Boot, port 8080) + its own Postgres (external
  to the BFF host in prod) + N registered Flowable engines reached over REST.
- **Liveness** (`/actuator/health/liveness`) = JVM only.
  **Readiness** (`/actuator/health/readiness`) = Postgres migrated+reachable + registry
  parsed. **Engine reachability is NEVER part of readiness** — an Inspector with every
  engine down is READY and serves the triage page with error envelopes. Do not "fix" the
  Inspector because engines are red.
- **Restarting the BFF is safe.** No in-memory state matters: audit and bulk jobs are in
  Postgres, sessions re-authenticate, caches (triage, 20 s TTL) rebuild themselves. The
  one consequence of a restart is bulk-job interruption — §3 below, by design.
- **RTO ≤ 15 min** (one-command redeploy, image pinned by digest). **RPO ≤ 5 min**
  (Postgres WAL/PITR). Graceful shutdown window 30 s. Deploys: single instance, accepted
  gap ≤ 2 min, announce in the support channel.
- **Never** mitigate by pointing the Inspector at an engine database or by widening the
  path whitelist. Both are load-bearing security boundaries, not tuning knobs.

## 1. "Inspector down" (liveness failing / connection refused)

1. `curl -fsS localhost:8080/actuator/health/liveness` from the BFF host.
2. Dead process / OOM? Check container status and the OOM heap dump volume
   (`-XX:MaxRAMPercentage=75`; dump lands on the mounted volume). Save the dump path in the
   ticket, then restart:
   redeploy by IaC/one-command (preferred) or restart the container. **Do not** restart in
   a loop — two failed starts means read the logs, not retry.
3. Watch startup logs for the two legitimate startup blockers:
   - **Flyway**: `Migrating schema` errors → §5 (bad migration / restore).
   - **Registry parse**: malformed engine registry YAML → fix config, redeploy. The BFF
     refuses to start with an unparseable registry (better down than mis-pointed).
4. Verify recovery: readiness 200 → `GET /api/engines` returns the engine list (red engines
   are fine) → triage page renders → **operations drawer: handle any INTERRUPTED banner
   (§3)**.

## 2. "Inspector up but degraded"

### 2a. Readiness failing >2 min, liveness fine
That's Postgres or its schema — the only readiness components. `pg_isready` against the
Postgres host; check disk (>80% alert), connections (Hikari pool default 20 — exhaustion is
alerted and visible in `/actuator/prometheus`). Postgres down has one more consequence:

### 2b. "Every mutation fails with an audit/Postgres error" — fail-closed is WORKING
The audit write is **fail-closed for all tiers**: no audit row ⇒ no mutation issued,
reads still work. This is not the outage — it is the designed response to the real outage
(Postgres / `audit_insert_failures_total > 0`). **Fix Postgres; do not look for a way to
bypass the audit.** There isn't one, deliberately. Until fixed, urgent engine mutations go
through §6 (direct cURL, hand-logged).

### 2c. One engine red / searches partial
Per-engine envelope errors ("timeout", "circuit open — engine shedding load", "credential
rejected") are the Inspector doing its job on a struggling engine. Distinguish:
- **`credential rejected` (401)** — the engine rotated `inspector-svc`'s password or the
  secret file/env is stale. Update the mounted secret (`password-file` refs re-read per
  attempt — no restart needed) or fix the env and restart.
- **`unreachable` / timeouts** — engine-side or network problem: escalate to the engine's
  owning team; the Inspector will recover on its own (circuit half-open probes).
- **ALL circuits open simultaneously** — that is an Inspector-side egress problem (host
  network, egress allowlist, DNS), not N engines failing at once. Check from the BFF host:
  `curl -fsS -u "inspector-svc:$ENGINE_A_PASSWORD" "$ENGINE_A_BASE_URL/management/engine"`.
- Engine version/capability gaps render as greyed verbs with a reason — not an incident.

### 2d. Users can't log in, engines fine
IdP (OIDC) outage → break-glass, §4. A single user missing a permission is **not**
break-glass: scope grants are a hot-reloaded mounted-file edit, effective ≤5 min
(SPEC §2, R-SAFE-12) — page the platform admin instead.

## 3. Recovering a crashed bulk job (INTERRUPTED)

Bulk jobs are persisted, restart-safe, and **never auto-resumed**. State machine:
job `PENDING → RUNNING → (COMPLETED | CANCELLED | INTERRUPTED)`; item
`pending → dispatched → (ok | failed | skipped | skipped (protected) | unknown | not_run)`.

What happens on a BFF crash/restart mid-job — automatically, in the startup sweep:
- The RUNNING job is marked **INTERRUPTED**.
- The single item in flight at the moment of the crash becomes **`unknown`** — it is
  **never re-fired** (it may have succeeded on the engine).
- All undispatched items become **`not_run`**.

Operator procedure (any OPERATOR can do this; you as Tier 2/3 just make sure it happens):
1. After any restart, the operations drawer **banners INTERRUPTED jobs on next login** —
   also visible via `GET /api/bulk` or the `/audit` ops-log page.
2. For each `unknown` item, click **Verify now** — the BFF re-runs the verb's precondition
   ("is job 8123 still in the DLQ?") and reclassifies to ok / still-pending / needs-L3
   **with evidence**. Do this *before* any re-run: an unknown terminate that actually
   succeeded must not be "retried" onto a sibling.
3. Use **"continue as new job"** on the banner — it creates a fresh tracked job pre-scoped
   to exactly the `not_run` + `failed` items (never the `unknown` ones). New job, new audit
   envelope, linked via `continuedFrom`.
4. Nothing is ever silently resumed; if the operator does nothing, the INTERRUPTED report
   simply persists. Unresolved `unknown`s surface in the shift report under
   NEEDS VERIFICATION until dealt with.

Circuit-open during a bulk run pauses dispatch (items stay `pending`) — that resolves
itself when the engine recovers; it is not INTERRUPTED and needs no recovery action.

## 4. Break-glass (IdP down during a P1)

Exists for exactly one scenario: **operators cannot authenticate (IdP outage) while an
engine incident needs hands.** Not for missing grants (§2d), not for speed.

- **Activate**: sign in with the sealed local ADMIN account at the `/break-glass` path.
  Credential lives in the org's secret manager / envelope per OPERATIONS §7 provisioning —
  location is deployment-specific, in the on-call handbook, **not in this repo**.
- **You get**: a 4 h session with ADMIN-global scope (bypasses authentication and the
  group→scope mapping only — including protected-instance floors).
- **Still fully in force**: the guard ladder (typed tokens, server-fresh restatement),
  read-only engine mode, the engine path whitelist, and **fail-closed audit** (if Postgres
  is also down, break-glass does not help you mutate — §6 is the only path).
- **Extra obligations**: a reason ≥ 10 chars is mandatory on **every** verb including
  tier 0; `require-second-approval` is waived but flagged on the audit row.
- **Visibility is the deterrent**: every action carries `breakGlass: true`, a banner sits
  on every page, the alert channel fires **on login**, and break-glass rows sort first in
  the shift report.
- **Afterwards (mandatory)**: rotate the break-glass credential, and review every
  `breakGlass: true` audit row in the incident postmortem.

## 5. Postgres restore / bad migration

Flyway is forward-only — **no down-migrations**. A bad migration means: stop the BFF,
restore Postgres (PITR to just before the migration; automated backup gate runs before
every migrate), fix the migration forward, redeploy. The quarterly restore drill
(OPERATIONS §4) keeps this executable from the drill script — if you are reading this at
3 AM and the drill script location is unknown, restore via the platform's standard PITR
procedure and verify `audit_entry` row counts against the pre-incident sizing worksheet.
The audit golden master is the thing being protected: verify its most recent rows survived
(`SELECT max(ts) FROM audit_entry;`) and note any gap in the incident ticket.

## 6. Last resort: direct cURL against an engine

When the Inspector is down/blocked but an engine needs an urgent fix. Only from hosts
inside the ARCHITECTURE §6 network fence (the engines' auth is IP-scoped); credentials are
the per-engine `inspector-svc` secrets.

> ⚠️ **Direct engine mutations bypass the audit golden master.** Flowable will attribute
> the action to `inspector-svc` and the Inspector will have **no record at all**. You are
> the audit trail: hand-log actor, time, engine, instance/job ID, verb, and reason in the
> incident ticket — before you run the command, not after you forget.

```bash
E="$ENGINE_BASE_URL"          # full base URL incl. context path, from the registry entry
AUTH=(-u "inspector-svc:$ENGINE_PASSWORD")

# list dead-letter jobs for an instance
curl -fsS "${AUTH[@]}" "$E/management/deadletter-jobs?processInstanceId=$PID"

# retry: move a dead-letter job back to the executable queue
curl -fsS "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  -d '{"action":"move"}' "$E/management/deadletter-jobs/$JOB_ID"

# suspend / activate a process instance
curl -fsS "${AUTH[@]}" -X PUT -H 'Content-Type: application/json' \
  -d '{"action":"suspend"}' "$E/runtime/process-instances/$PID"   # or "activate"
```

Full verb catalog with exact engine paths: the REST Parity Appendix (R-L3-02) — every UI
verb documents its equivalent REST call; in the UI, tier-2 confirms and "copy as cURL"
show the same bodies.

## 7. Alerts → actions

| Alert | First move |
|---|---|
| `InspectorDown` (liveness) | §1 |
| Readiness failing > 2 min | §2a |
| `audit_insert_failures_total > 0` | §2b — Postgres first; mutations are refusing by design |
| Postgres unreachable / disk > 80% | §2a / §5; disk: check partition growth vs the sizing worksheet before extending |
| All circuit breakers open | §2c — BFF egress, not the engines |
| Break-glass login alert | Confirm it's expected (active P1 + IdP outage); otherwise treat as compromise: lock the account, rotate, review `breakGlass` audit rows |
| SSE emitter errors spiking | Reverse-proxy buffering misconfig after a deploy (OPERATIONS §9 `prod-like` profile tests this); clients degrade to polling — not user-facing-critical |

Paging route for everything above: the workflow platform team. Engine-health alarms (DLQ
growth, executor starvation) route to the **engine's** owning team — the Inspector is the
messenger.
