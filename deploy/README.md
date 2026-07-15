# `deploy/` — provisioning artifacts (not schema, not app code)

Environment-setup steps that live **outside** the application and **outside** Flyway. Flyway
owns _schema_ (`ddl-auto=validate`, iron rule); this directory owns _principals and grants_ —
things Flyway cannot bootstrap because it runs **as** the owner role it would need to constrain.

## `sql/audit-roles.sql` — audit golden-master role separation (M4-CLOSEOUT §5a / §A2)

Splits the single `inspector` role into an owner and a locked-down runtime role so a compromised
BFF — already admin on every registered engine (OPERATIONS §7) — still **cannot rewrite or
destroy the human-accountability record**.

| Role                      | Purpose                                                           | On `audit_entry`                                                                                                                                         |
| ------------------------- | ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `inspector` (owner)       | Flyway migrate; owns the audit objects + (at S5b) `purge_audit()` | full (owner)                                                                                                                                             |
| `inspector_app` (runtime) | the BFF's datasource in prod                                      | `INSERT`, `SELECT`, column-scoped `UPDATE` on the four outcome columns only, `USAGE` on `audit_entry_seq`; **no `DELETE`/`TRUNCATE`**, no other `UPDATE` |
| `inspector_ops` (purge)   | runs retention purge at S5b                                       | `EXECUTE purge_audit()` only — **never raw `DROP`**                                                                                                      |

The grant layer and the V1 append-only guard trigger are **defense-in-depth on each other**:
`AuditRoleGrantsIT` asserts the app role hits `42501 insufficient_privilege` (the grant refusing)
on `DELETE`/`TRUNCATE`/non-outcome `UPDATE`/partition-`DROP`, _before_ the trigger's own check.

### How to run

Two phases (idempotent; re-runnable every deploy):

1. **Before** the BFF first connects — create the roles.
2. **After** `flyway migrate` — apply the grants (the objects must exist).

Running the whole file at both points is safe. `ALTER DEFAULT PRIVILEGES` means objects added by
**later** migrations (e.g. `legal_hold` at S5b) auto-grant to the runtime role — `DELETE` is
deliberately excluded so future audit monthly partitions never become app-deletable.

```bash
psql "$INSPECTOR_DB_ADMIN_URL" \
  -v owner=inspector -v db=inspector \
  -v app_role=inspector_app -v ops_role=inspector_ops \
  -v app_password="$INSPECTOR_APP_PASSWORD" \
  -v ops_password="$INSPECTOR_OPS_PASSWORD" \
  -f deploy/sql/audit-roles.sql
```

Passwords are set **only** when passed (a routine re-run never rotates a live credential —
rotation is a deliberate re-run _with_ the password vars). Secrets come from the environment /
a secret manager, never committed.

### Restore-drill note (ops m3)

Role passwords and grants are **cluster-global** (`pg_authid` / ACLs) — a logical `pg_dump` does
**not** carry them. The quarterly restore drill (OPERATIONS §4) must `pg_dumpall --globals`
**and** re-run this script, then verify grant-level enforcement (re-run `AuditRoleGrantsIT`'s
assertions against the restored cluster), not merely that rows + the hash chain survived.

## `docker/backup/` — Docker-native backups (issue #201-followup) — THE CURRENT MECHANISM

issue #201 first closed the audit golden-master's "zero copies" gap and then added continuous
WAL/PITR tooling, but both relied on **host-level** pieces the compose-based demo deploy
doesn't actually have available in its normal operating mode: `sudo`-installed systemd timers
and a `sudo`-created, uid-70-chowned host bind-mount directory. This follow-up **replaces**
those host-dependent pieces with fully Docker-native equivalents — new services in
`docker/docker-compose.demo.yml`, scheduled **internally** (busybox crond baked into each
service's own container — confirmed present in `postgres:16-alpine`), storing backups in
**named Docker volumes** (no host bind-mount, no host root needed at all), connecting to
Postgres **over the network** (`postgres:5432`) rather than `docker exec`-ing into the
Postgres container. Same safety properties as the scripts they replace (see below) — this is
a delivery-mechanism change, not a backup-strategy redesign.

- **`audit-backup`** service (`docker/backup/audit-backup/`) — nightly `pg_dump -Fc` against
  `postgres:5432` over the network (`PGHOST=postgres`, `PGUSER`/`PGPASSWORD`/`PGDATABASE` — all
  standard libpq env vars `pg_dump` reads natively), to the named volume
  `inspector-logical-dumps`. Same 02:30 UTC cadence, same `.partial`→`mv` crash-safety, same
  `sha256sum` sidecar, same 35-day retention (`PI_BACKUP_RETENTION_DAYS`) as the script it
  replaces. On-demand run (bypasses the cron wait):
  ```bash
  docker compose -f docker/docker-compose.demo.yml run --rm audit-backup /scripts/backup-once.sh
  ```
- **`audit-basebackup`** service (`docker/backup/audit-basebackup/`) — the PHYSICAL half PITR
  needs (a `pg_dump` cannot be replayed forward through WAL). Weekly (Sunday 04:00 UTC)
  `pg_basebackup -Ft -z -X none -c fast` over the network, to the named volume
  `inspector-basebackups`, same checksum + newest-3 count-based retention
  (`PI_BASEBACKUP_RETENTION_COUNT`) as the script it replaces.
  ```bash
  docker compose -f docker/docker-compose.demo.yml run --rm audit-basebackup /scripts/basebackup-once.sh
  ```
- **`wal-receiver`** service (`docker/backup/wal-receiver/`) — continuous WAL capture via
  `pg_receivewal`, **replacing** issue #201's `archive_mode`/`archive_command` mechanism
  entirely (removed from `docker/docker-compose.demo.yml`'s `postgres` service — WAL capture
  now needs only `wal_level=replica`, already pinned there). Streams the replication protocol
  against `postgres:5432`, writing segments to the named volume `inspector-wal-archive`,
  backed by a **permanent physical replication slot** (`wal_receiver`, auto-created,
  `--if-not-exists`) so Postgres retains WAL for as long as the service is down, however long
  that is — the property that makes it safe to run as a long-lived, restartable container
  service. **Proven via a kill/restart rehearsal** (see docs/IMPLEMENTATION-PLAN.md's
  #201-followup entry): killed mid-stream, more WAL generated while down, restarted — resumed
  with zero gap, zero duplicate/corrupt segments (`pg_waldump` clean on every segment,
  `pg_replication_slots.restart_lsn` held the floor while `active=f`).
  Runs as the `postgres` container's uid (70), not root — `pg_receivewal` writes each segment
  `0600`, and a root-owned segment is unreadable to the `postgres`-uid recovery process in
  `pitr-drill.sh`'s throwaway container (hit this exact `Permission denied` empirically before
  fixing it — see `docker/backup/wal-receiver/entrypoint.sh`'s header).
- **`docker/backup/postgres/pg_hba-demo.conf`** — a custom `pg_hba.conf`, wired into the
  `postgres` service via `command: -c hba_file=...`, extending the upstream image's own
  generated default with a `replication` entry for the internal docker network (same
  `scram-sha-256` password requirement the image's existing ordinary-connection catch-all
  already applies). REQUIRED for `audit-basebackup`/`wal-receiver`: verified empirically that
  the upstream default's replication trust is `127.0.0.1`/`::1`-only, so a plain network
  `pg_basebackup`/`pg_receivewal` fails closed (`no pg_hba.conf entry for replication
  connection`) without this override.

**Legacy / non-Compose deployments:** `backup-audit-db.sh` / `basebackup-audit-db.sh` /
`deploy/systemd/pi-audit-{backup,basebackup}.*` still exist, now marked **SUPERSEDED** in their
own headers for this repo's actual (Compose-based) demo — kept because they document a real
prior design and may still be the right shape for a bare-host (non-Compose) Postgres reached
via `docker exec`. Do not install the systemd units alongside the compose services — they'd
race for the same `PI_BACKUP_DIR`.

## `restore-drill.sh` / `pitr-drill.sh` — now read from the named volumes above

Both drill scripts were updated (issue #201-followup) to read from the new named-volume
storage **directly** — no host-path copy needed; `docker run`/`docker exec -v <volume>:...`
mounts a named volume by name regardless of host-path visibility:

- **`restore-drill.sh`** — restores the newest dump from `inspector-logical-dumps` (or a
  specific in-volume filename, or — for the superseded host mechanism — an explicit host path)
  into a **throwaway** Postgres (never the live DB), `pg_restore`d straight from the
  volume-mounted path inside that throwaway container. Asserts `audit_entry` returns
  **partitioned, with partitions + rows**.
  ```bash
  deploy/restore-drill.sh                    # newest dump in inspector-logical-dumps
  deploy/restore-drill.sh inspector-2026...   # a specific filename within that volume
  deploy/restore-drill.sh /path/to.dump       # legacy host-path mode
  ```
- **`pitr-drill.sh`** — mirrors `restore-drill.sh`'s safe-by-construction pattern (throwaway
  container + throwaway named volume for the unpacked base backup, `trap cleanup EXIT` removes
  both, never touches the live DB). Restores the newest base backup from `inspector-basebackups`,
  mounts `inspector-wal-archive` **read-only** into the recovery container, replays via
  Postgres 16's current recovery mechanism (`recovery.signal` + `restore_command` — not the
  pre-12 `recovery.conf`, which Postgres removed outright), either to "everything available"
  or an explicit `--target-time`. Same integrity checks as `restore-drill.sh`.
  ```bash
  deploy/pitr-drill.sh                                          # replay everything available
  deploy/pitr-drill.sh --target-time '2026-07-15 03:00:00Z'     # replay up to a specific instant
  deploy/pitr-drill.sh base-20260715T040000Z.tar.gz               # a specific in-volume base backup
  ```

**Verified so far:** the FULL mechanism — `wal-receiver` streaming + persisting, the kill/
restart/resume rehearsal above, `audit-backup`/`audit-basebackup` succeeding over the network
with correct auth, retention pruning's 0/N/N+1 edge cases for both backup types, and both
drill scripts end-to-end against the new volume-based storage (`pitr-drill.sh` in BOTH
"replay everything" and `--target-time` modes, `restore-drill.sh` restoring a logical dump) —
was rehearsed against **disposable** `docker compose` infrastructure (its own throwaway
Postgres + the four new services), built and torn down solely for this verification, never the
live demo container/volumes. One methodology note worth keeping for future rehearsals: batching
an `INSERT` and `SELECT pg_switch_wal()` into a single multi-statement `psql -c` call sends
them as ONE implicit transaction, so the switch's WAL record can be written **before** the
insert's own COMMIT record — always run them as separate `psql` invocations when timing a
target-time drill (this is the exact same "instructive rather than a script bug" artifact
issue #201's own rehearsal already documented). NOT yet verified: a drill against REAL
accumulated production WAL history from the live system. Until that real-world drill has run,
`docs/OPERATIONS.md` §4's RPO claim deliberately stays at 24 h.

### Activating Docker-native backups against the LIVE demo (deliberately NOT done by this PR)

Shipping the code is this PR's capability delivery. Turning it on against the LIVE
`process-inspector-demo-postgres-1` is reserved as an explicit, separately-confirmed follow-up,
exactly like issue #201's own activation was — split into a no-restart step and a
restart-required step:

1. **No Postgres restart needed** — `audit-backup` connects as an ORDINARY network client, and
   the live `postgres` container's CURRENT `pg_hba.conf` (from issue #201's already-completed
   manual activation) already trusts that. Bring it up and prove it immediately:
   ```bash
   docker compose -f docker/docker-compose.demo.yml up -d audit-backup
   docker compose -f docker/docker-compose.demo.yml run --rm audit-backup /scripts/backup-once.sh
   ```
2. **`audit-basebackup` and `wal-receiver` need the `pg_hba` override first** — both connect
   over the REPLICATION protocol, which the live container's CURRENT `pg_hba.conf` (predating
   this PR) does not yet trust from the network. That requires recreating `postgres` with this
   PR's `command:` (which ALSO removes the now-superseded `archive_mode`/`archive_command`
   config from issue #201's own manual activation — one restart accomplishes both the
   pg_hba fix and the archiving-mechanism cutover):
   ```bash
   docker compose -f docker/docker-compose.demo.yml up -d postgres   # RESTARTS Postgres — brief
                                                                       # connection drop for backend;
                                                                       # it reconnects via Hikari.
   ```
3. **Verify before trusting it** — don't skip straight to the base backup on faith:
   ```bash
   docker compose -f docker/docker-compose.demo.yml up -d audit-basebackup wal-receiver
   docker logs process-inspector-demo-wal-receiver-1        # expect "starting log streaming"
   docker exec process-inspector-demo-postgres-1 psql -U inspector -d inspector \
     -c "SELECT slot_name, active, restart_lsn FROM pg_replication_slots;"   # active=t
   docker compose -f docker/docker-compose.demo.yml run --rm audit-basebackup /scripts/basebackup-once.sh
   ```
4. **The pre-existing host WAL archive** (`${PI_BACKUP_DIR:-/var/backups/pi-audit}/wal-archive`,
   from issue #201's manual `archive_command` activation) is NOT migrated into the new
   `inspector-wal-archive` volume — this is a clean cutover point, not a merge. The host archive
   remains as a historical/last-resort copy; all FUTURE PITR drills use the volume going
   forward.
5. Once real WAL history has accumulated (days, not minutes) in `inspector-wal-archive`, run
   `deploy/pitr-drill.sh` against the LIVE volumes for real (their actual names are prefixed by
   the compose project, e.g. `process-inspector-demo_inspector-wal-archive` — pass
   `PI_WAL_ARCHIVE_VOLUME`/`PI_BASEBACKUPS_VOLUME` accordingly) and only THEN update
   `docs/OPERATIONS.md` §4's RPO claim — this PR's own disposable-infrastructure rehearsal
   proves the MECHANISM, not the live deploy.

## `prometheus/alert-rules.yml` — RUNBOOK §7's alert contract (issue #96, OPERATIONS §3)

The alert side of RUNBOOK.md §7's "Alerts → actions" table, in standard Prometheus alerting-rule
YAML — hand this to whatever Prometheus/Alertmanager stack scrapes the BFF's auth-gated
`/actuator/prometheus` (no monitoring stack is deployed by THIS repo's `docker/*.yml`). Every
expression is checked against a real running instance's scrape output, not assumed.

Two rules are marked **infra-dependent** in the file itself: `InspectorReadinessFailing` needs
`blackbox_exporter` probing `GET /actuator/health/readiness` (Prometheus has no native readiness
concept — a scrape target being reachable at all only proves liveness), and `DiskSpaceHigh` needs
`node_exporter`. Neither exporter is deployed here; the rules are included for completeness and
should be wired up alongside whichever monitoring stack a real deploy adds, not treated as
already-live. Everything else (`AuditInsertFailures`, `AllCircuitBreakersOpen`,
`SseEmitterErrorsSpiking`, and `DatabaseConnectionTimeoutsSpiking` as the honest proxy for
Postgres-unreachable pending a `postgres_exporter`) fires off metrics THIS app already emits.

## Still to land in S0 (tracked)

- **`prod-like` compose profile** (OPERATIONS §9): release image + external Postgres provisioned
  with these roles (BFF connects as `inspector_app`) + OIDC stub + reverse proxy with
  forwarded-headers-on / SSE-buffering-off. It exercises the split, the dual-auth path, and
  (later) the S4 `X-Forwarded-User` ingress scrub before prod. `AuditRoleGrantsIT` already proves
  the grant regime in CI-adjacent isolation; the compose profile adds the full runtime wiring.
- **CI gating**: `AuditRoleGrantsIT` is currently local-only (matching existing Testcontainers
  ITs, not in `ci.yml` `itClass`). It joins the Testcontainers-Postgres suite when that lands as
  a merge gate (OPERATIONS §8, "still to land M4+").
