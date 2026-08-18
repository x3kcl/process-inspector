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
- **RTO ≤ 15 min** (one-command redeploy, image pinned by digest). **RPO = 24 h today**
  (the nightly logical-dump interval — §5 below has the full picture: Docker-native
  WAL/PITR tooling exists but isn't yet activated against the live demo, so don't quote a
  ≤5-min figure until that's actually live and drilled). Graceful shutdown window 30 s.
  Deploys: single instance, accepted gap ≤ 2 min, announce in the support channel.
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
(Postgres). **Fix Postgres; do not look for a way to bypass the audit.** There isn't one,
deliberately.

> **Metric note (P1 #12; wired 2026-07-13, issue #96):** `/actuator/health` +
> `/actuator/prometheus` are live, and the **custom** `audit_insert_failures_total` counter
> referenced here and in §7 is now **emitted** (tagged `site` — `beginPending` for the mutation
> fail-closed gate, `recordConfigEvent` for a config-event insert failure; see OPERATIONS §2), with
> `deploy/prometheus/alert-rules.yml`'s `AuditInsertFailures` rule firing on it. Readiness still
> stays UP regardless (the DB indicator only flips when Postgres is unreachable, not when an
> insert is refused by the guard) — the fail-closed 5xx on every mutation and the audit-failure log
> line remain the fastest signal at the terminal, the counter is for the alert pipeline.

Until fixed, urgent engine mutations go
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
break-glass — page the platform admin instead. How the grant reaches them depends on
`inspector.security.mapping-source` (IDP-SECURITY.md §6):

- **`file`** (default): a scope grant is a hot-reloaded mounted-file edit, effective ≤5 min
  (SPEC §2, R-SAFE-12); the `/admin/access` CRUD UI 403s.
- **`db`**: grants are CRUD'd via `/admin/access` (ACCESS_ADMIN, audited fail-closed; any
  widening change — role ≥OPERATOR with a wildcard engine/tenant, or a fleet-grant create/remove
  — requires a second independent `ACCESS_ADMIN` approval) and resolve within the same ≤60 s
  cache TTL — no file edit or restart needed.

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
   **with evidence**. Do this _before_ any re-run: an unknown terminate that actually
   succeeded must not be "retried" onto a sibling.
3. Use **"continue as new job"** on the banner — it creates a fresh tracked job pre-scoped
   to exactly the `not_run` + `failed` items (never the `unknown` ones). New job, new audit
   envelope, linked via `continuedFrom`.
4. Nothing is ever silently resumed; if the operator does nothing, the INTERRUPTED report
   simply persists. Unresolved `unknown`s surface in the shift report under
   NEEDS VERIFICATION until dealt with.

Circuit-open during a bulk run pauses dispatch (items stay `pending`) — that resolves
itself when the engine recovers; it is not INTERRUPTED and needs no recovery action.

### 3a. A job settles INTERRUPTED without a BFF restart (#303)

Two more paths land a job in the SAME INTERRUPTED state above — recover them exactly like
a crash (steps 1–4), even though the BFF never restarted:

- **A store blip mid-dispatch.** Every statement in the dispatch loop (not just the
  per-item engine calls) is guarded — a Postgres blip at submit, at dispatch-start, or at
  finish settles the job INTERRUPTED immediately, honestly (it can no longer claim a
  COMPLETED it never confirmed was persisted), instead of leaving it RUNNING until you
  notice and restart the BFF yourself.
- **A genuinely stuck job (deadlock, an uncaught `Error`) with the process still alive.** A
  periodic sweep (independent of the startup one) settles a `RUNNING`/`PENDING` job with NO
  item-state progress for longer than a conservative bound — the largest configured engine
  write budget plus one circuit-pause wait, times a margin, plus a grace window — exactly
  as the startup sweep would. **A healthy job, however large or slow-staggered, keeps
  making steady per-item progress and is never caught by this bound** — if you see a job
  swept INTERRUPTED this way, treat it as a real stuck-process signal worth investigating
  (not routine noise), the same as an unexplained restart.

**A healthy long-running bulk job's audit envelope is no longer falsely flagged either.**
The separate audit PENDING reconciler (SPECIFICATION §7, R-SEM-18) used to sweep ANY stale-PENDING row —
including a bulk job's envelope — to `unknown` purely because the job hadn't finished yet,
which used to land a perfectly healthy, still-running job in the shift report's NEEDS
VERIFICATION section with a scary WARN. That reconciler now skips a bulk envelope for as
long as `bulk_job.state` is `PENDING`/`RUNNING` — the two sweeps above are what actually
close it out, honestly, the moment the job is genuinely done or genuinely stuck.

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

> **Run the chain-walk integrity verifier after every restore (issue #304).** A row-count
> check alone can't tell you the chain wasn't altered mid-restore (a bad PITR target, a
> partial WAL replay, a manual row fix) — walk it: `GET /api/admin/audit/integrity`
> (ADMIN session; itself an audited read, `AuditChainVerifier`). Read `intact`, `rowsChecked`,
> `partitionsSpanned`, and `firstProblemSeq` in the response. A clean `intact: true` after a
> restore is the actual proof the golden master survived, not just its row count. Also run it
> **periodically** (a monthly cron hitting the endpoint under an ADMIN service account is
> enough — there is no `@Scheduled` job for this, deliberately: unlike the retention purge,
> a stopped verifier fails silently with no dead-man to page on, so treat "nobody ran it in
> N months" as the same kind of gap issue #304 itself called out — an unrun chain-walk proves
> nothing). Remember what a finding actually means (OPERATIONS §6/§7): a `HASH_MISMATCH` means
> a row was altered by someone who could NOT recompute the forward chain (in practice, anyone
> without direct Postgres superuser access) — investigate as a real incident. An
> `UNEXPLAINED_GAP` at a seq with no matching `AuditRetentionPurger` checkpoint likewise
> deserves investigation. A `RETENTION_BOUNDARY` finding is expected and benign (a normal
> retention-purge drop, checkpointed before the DROP) — not an alert.
>
> **PITR tooling status (issue #201, Docker-native since #201-followup).**
> `deploy/pitr-drill.sh` reads from the `inspector-basebackups`/`inspector-wal-archive` named
> Docker volumes (written by the `audit-basebackup`/`wal-receiver` compose services — no host
> bind-mount, no systemd timer) and was rehearsed end-to-end against disposable `docker
compose` infrastructure, including a kill/restart resumption proof for the continuous
> `wal-receiver` stream — but this mechanism is **not yet activated** on the live demo
> container (`deploy/README.md`'s "Activating Docker-native backups" has the exact turn-on
> steps). Until that activation has happened and a drill has run against real accumulated WAL
> history, the executable restore path at 3 AM is still `deploy/restore-drill.sh` (the nightly
> logical dump, from the `inspector-logical-dumps` volume) — do not assume a WAL-based PITR
> window that isn't live yet.
>
> **Decommissioning `wal-receiver`:** it holds a PERMANENT physical replication slot
> (`wal_receiver`, by default) — Postgres retains WAL indefinitely for that slot regardless of
> whether the receiver is running. If this service is ever permanently removed, drop the slot
> first or the data volume grows unbounded:
> `docker exec process-inspector-demo-postgres-1 psql -U inspector -d inspector -c "SELECT pg_drop_replication_slot('wal_receiver');"`

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

Prometheus rule definitions for the alerts below live in `deploy/prometheus/alert-rules.yml`
(issue #96) — hand that file to whichever monitoring stack scrapes `/actuator/prometheus` (none is
deployed by this repo's `docker/*.yml`). `Readiness failing > 2 min` and `disk > 80%` are written
but need an exporter this repo does not deploy (`blackbox_exporter`/`node_exporter` — see
`deploy/README.md`); every other row already fires off a metric this app emits today.

| Alert                                                    | First move                                                                                                                                                                                                                                                                                                                                                                                    |
| -------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `InspectorDown` (liveness)                               | §1                                                                                                                                                                                                                                                                                                                                                                                            |
| Readiness failing > 2 min                                | §2a                                                                                                                                                                                                                                                                                                                                                                                           |
| `audit_insert_failures_total > 0`                        | §2b — Postgres first; mutations are refusing by design                                                                                                                                                                                                                                                                                                                                        |
| No `audit-retention-purge` event in > ~2 days (dead-man) | The `@Scheduled` purge is stopped/failing. Check the BFF is up and `inspector.audit.retention-purge.enabled` is not `false`; look for `AUDIT_RETENTION_PURGE_ABORTED` (audit DB down → purge correctly did not run) and `AUDIT_DEFAULT_PARTITION_NONEMPTY` (rows in DEFAULT → create-ahead broken, nothing to drop). Data is over-retained, never wrongly deleted — safe to triage unhurried. |
| Postgres unreachable / disk > 80%                        | §2a / §5; disk: check partition growth vs the sizing worksheet; confirm the retention purge is running (dead-man above) before extending                                                                                                                                                                                                                                                      |
| All circuit breakers open                                | §2c — BFF egress, not the engines                                                                                                                                                                                                                                                                                                                                                             |
| Break-glass login alert                                  | Confirm it's expected (active P1 + IdP outage); otherwise treat as compromise: lock the account, rotate, review `breakGlass` audit rows                                                                                                                                                                                                                                                       |
| SSE emitter errors spiking                               | Reverse-proxy buffering misconfig after a deploy (OPERATIONS §9 `prod-like` profile tests this); clients degrade to polling — not user-facing-critical                                                                                                                                                                                                                                        |

Paging route for everything above: the workflow platform team. Engine-health alarms (DLQ
growth, executor starvation) route to the **engine's** owning team — the Inspector is the
messenger.

## 8. Rolling back the demo deploy (pi.naumann.cloud)

**Applies to the demo only** (`docker/docker-compose.demo.yml`) — a customer's own prod
deployment via `docker/docker-compose.release.yml` pins `PI_VERSION` to a released tag and
is out of scope here. Since issue #92, the demo's `backend`/`frontend` are pinned by
**digest** (`docker/.env.demo`'s `PI_BFF_DIGEST`/`PI_WEB_DIGEST`), never a floating tag or a
local build — every deploy commits that file and tags the commit `demo-YYYY-MM-DD-<shortsha>`,
so `git log docker/.env.demo` is the full attribution record of what has ever run there.

**Symptom:** a demo deploy regressed something (a bad `:edge` build, a config drift) and the
fastest fix is going back to the last-known-good build rather than forward-fixing.

```bash
docker/rollback-demo.sh --list                  # recent demo deploy tags, newest first
docker/rollback-demo.sh demo-2026-07-12-a1b2c3d  # restores that tag's exact digest pair, redeploys, verifies
git push origin HEAD                            # publish the rollback commit
```

**Deploy from the durable checkout only.** `deploy-demo.sh` refuses to run from a
`.claude/worktrees/*` path and prints the compose bind-mount root it froze into the
containers (issue #396 — a deploy from an auto-cleaned worktree silently killed the demo's
audit backups for 11 days and left postgres unable to restart). If you are staring at
dangling `/scripts` mounts or `backup-once.sh: not found`, the recovery commands are in
DEMO-DEPLOY.md §"Deploy only from a durable checkout".

This restores the _exact_ previously-running images (no re-resolution of a floating tag —
`:edge` may have moved since) and verifies the standard `/api/engines` 401 probe (DEMO-DEPLOY.md
§"Troubleshooting a 502 / 504") before considering the rollback done. If that probe still
fails, escalate to §2/§5 as normal — a rollback fixes a bad _image_, not a bad database state
or a Traefik/network issue.

**Drilled:** 2026-07-13, `docker/deploy-demo.sh`/`docker/rollback-demo.sh` verified end-to-end
against the real published `:edge` images (digest resolution, fail-closed `docker compose
config` on an unpinned `.env.demo`, successful `config` with real digests substituted). The
live cutover of pi.naumann.cloud itself and an actual against-prod rollback drill are tracked
separately — this repo's automation is ready, but running it against the live host is a
deliberate, human-confirmed step, not something this change performs on its own.
