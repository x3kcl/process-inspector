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

## `backup-audit-db.sh` + `restore-drill.sh` — the audit golden-master's copies (P0 #4 / Q4)

The 400-day audit chain had **zero copies** — one docker volume on one host. These close that:

- **`backup-audit-db.sh`** — nightly `pg_dump -Fc` of the BFF's Postgres to `PI_BACKUP_DIR`
  (ideally a second disk), checksummed, retention-pruned. Read-only against the live DB; safe
  while serving. Schedule with `systemd/pi-audit-backup.timer` (or cron). Honest **RPO = the
  timer interval (24 h)** — continuous WAL/PITR is the documented follow-up (OPERATIONS §4).
- **`restore-drill.sh`** — restores the latest dump into a **throwaway** Postgres (never the
  live DB) and asserts `audit_entry` returns **partitioned, with partitions + rows**. Makes the
  "quarterly restore drill" executable. Complements — does not replace — the globals/roles
  verification below: this drills DATA + SCHEMA restorability (`--no-owner`); a full DR drill
  additionally restores `pg_dumpall --globals` + re-runs `audit-roles.sql` + `AuditRoleGrantsIT`.

```bash
PI_BACKUP_DIR=/mnt/backups/pi-audit deploy/backup-audit-db.sh   # nightly (timer-driven)
deploy/restore-drill.sh                                          # verify newest dump restores
```

## `basebackup-audit-db.sh` + `pitr-drill.sh` — continuous WAL archiving / PITR tooling (issue #201)

The nightly logical dump above is a real, working "no copy at all" gap-closer, but its RPO is
the 24 h timer interval. This adds the OTHER half of a PITR setup — continuous WAL archiving —
as **shipped, ready-to-activate tooling**, not yet turned on against the live demo (see
"Activating WAL archiving" below for why, and the two-step human process to actually do it).

- **`docker/docker-compose.demo.yml`**'s `postgres` service now carries the config WAL
  archiving needs (`archive_mode=on`, an idempotent `archive_command` copying segments to a
  bind-mounted `${PI_BACKUP_DIR}/wal-archive`, `wal_level=replica` pinned explicitly though it's
  already `postgres:16-alpine`'s own default) — but `archive_mode` only takes effect on a
  Postgres **restart**, which this repo deliberately does NOT trigger as part of any routine
  `docker compose up -d` reconciliation. See "Activating WAL archiving" below.
- **`basebackup-audit-db.sh`** — the PHYSICAL half PITR needs (a `pg_dump` cannot be replayed
  forward through WAL). Streams a compressed `pg_basebackup` tar to
  `PI_BACKUP_DIR/basebackups/`, checksummed. **Weekly** cadence, keeping only the newest 3
  (`PI_BASEBACKUP_RETENTION_COUNT`) — a physical base backup is the full data directory
  (much bigger than a logical dump), and every backup after the first is redundant with the
  continuously archived WAL for recovery purposes (WAL replay from any still-archived base
  backup reaches the same point, just with a longer replay) — see the script's own header for
  the full reasoning. Schedule with `systemd/pi-audit-basebackup.timer`.
- **`pitr-drill.sh`** — mirrors `restore-drill.sh`'s safe-by-construction pattern exactly
  (throwaway container + named volume, `trap cleanup EXIT`, never touches the live DB).
  Restores the latest base backup, then replays archived WAL via Postgres 16's CURRENT
  recovery mechanism (`recovery.signal` + `restore_command` in `postgresql.auto.conf` — NOT
  the pre-12 `recovery.conf`, which Postgres removed outright), either to "everything
  available" (default) or to an explicit `--target-time`. Runs the same
  partitioned/partition-count/row-count integrity checks as `restore-drill.sh`.

```bash
PI_BACKUP_DIR=/mnt/backups/pi-audit deploy/basebackup-audit-db.sh          # weekly (timer-driven)
deploy/pitr-drill.sh                                                        # replay everything available
deploy/pitr-drill.sh --target-time '2026-07-15 03:00:00Z'                   # replay up to a specific instant
```

**Verified so far:** the full mechanism (WAL archiving → base backup → PITR replay, both
"replay everything" and `--target-time`) was rehearsed end-to-end against a **disposable**
`postgres:16-alpine` container set up and torn down solely for this verification — never the
live demo container. NOT yet verified: the drill against REAL accumulated production WAL
history, because WAL archiving isn't active on the live container yet (see below). Until that
real-world drill has run, `docs/OPERATIONS.md` §4's RPO claim deliberately stays at 24 h.

### Activating WAL archiving (deliberately NOT done by this PR — a separate, human-run step)

Shipping the compose config is issue #201's capability delivery. Turning it on against the
LIVE `process-inspector-demo-postgres-1` requires a Postgres **restart** (a real, if brief,
disruption to the demo) and is reserved as an explicit follow-up, not bundled into a routine
deploy:

1. `mkdir -p "${PI_BACKUP_DIR:-/var/backups/pi-audit}/wal-archive"` on the host, owned so the
   containerized `postgres` user (uid **70** in `postgres:16-alpine`, confirmed empirically)
   can write into it — e.g. `chown 70:70 ".../wal-archive"` (or a docker helper container if
   the host shell isn't already root: `docker run --rm -v ".../wal-archive:/w" alpine chown
70:70 /w`).
2. `docker compose -f docker/docker-compose.demo.yml up -d postgres` — recreates the
   `postgres` service with the new `command:`, which **restarts Postgres** (brief connection
   drop for `backend`; it reconnects via its Hikari pool).
3. **Verify archiving is actually working before trusting it** — don't skip straight to the
   base backup on faith. Confirm both: (a) `docker exec process-inspector-demo-postgres-1 ls
/wal-archive` shows at least one file within a few minutes (Postgres archives a segment on
   its own timer even at rest, or force one: `docker exec ... psql -U inspector -d inspector
-c 'SELECT pg_switch_wal();'`); (b) `docker exec ... psql -U inspector -d inspector -c
'SELECT archived_count, failed_count, last_failed_wal, last_failed_time FROM
pg_stat_archiver;'` shows `archived_count` incrementing and `failed_count` at 0 (or not
   climbing). If `failed_count` is climbing, check `docker logs process-inspector-demo-postgres-1`
   for the `archive_command` error before proceeding — see the "known limitation" comment on
   `archive_command` in `docker-compose.demo.yml` for the one documented failure mode
   (a stuck corrupt partial file) and how to clear it.
4. Run `deploy/basebackup-audit-db.sh` once to establish the first physical base backup —
   WAL archived before this point isn't useful without an anchor to replay it onto.
5. Install the weekly timer: `sudo cp deploy/systemd/pi-audit-basebackup.* /etc/systemd/system/
&& sudo systemctl daemon-reload && sudo systemctl enable --now pi-audit-basebackup.timer`.
6. Once real WAL history has accumulated (days, not minutes), run `deploy/pitr-drill.sh`
   against it for real and only THEN update `docs/OPERATIONS.md` §4's RPO claim — this PR's
   own disposable-container rehearsal proves the MECHANISM, not the live deploy.

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
