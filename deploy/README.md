# `deploy/` — provisioning artifacts (not schema, not app code)

Environment-setup steps that live **outside** the application and **outside** Flyway. Flyway
owns *schema* (`ddl-auto=validate`, iron rule); this directory owns *principals and grants* —
things Flyway cannot bootstrap because it runs **as** the owner role it would need to constrain.

## `sql/audit-roles.sql` — audit golden-master role separation (M4-CLOSEOUT §5a / §A2)

Splits the single `inspector` role into an owner and a locked-down runtime role so a compromised
BFF — already admin on every registered engine (OPERATIONS §7) — still **cannot rewrite or
destroy the human-accountability record**.

| Role | Purpose | On `audit_entry` |
|---|---|---|
| `inspector` (owner) | Flyway migrate; owns the audit objects + (at S5b) `purge_audit()` | full (owner) |
| `inspector_app` (runtime) | the BFF's datasource in prod | `INSERT`, `SELECT`, column-scoped `UPDATE` on the four outcome columns only, `USAGE` on `audit_entry_seq`; **no `DELETE`/`TRUNCATE`**, no other `UPDATE` |
| `inspector_ops` (purge) | runs retention purge at S5b | `EXECUTE purge_audit()` only — **never raw `DROP`** |

The grant layer and the V1 append-only guard trigger are **defense-in-depth on each other**:
`AuditRoleGrantsIT` asserts the app role hits `42501 insufficient_privilege` (the grant refusing)
on `DELETE`/`TRUNCATE`/non-outcome `UPDATE`/partition-`DROP`, *before* the trigger's own check.

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
rotation is a deliberate re-run *with* the password vars). Secrets come from the environment /
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
