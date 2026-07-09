-- ============================================================================
-- audit-roles.sql — DB role separation for the audit golden master.
--
-- PROVISIONING, NOT SCHEMA (M4-CLOSEOUT.md §5a, S0). Run by a DBA / IaC step,
-- NOT by Flyway: role names + passwords are environment-specific and
-- secret-managed, and Flyway runs AS the owner role so it cannot bootstrap the
-- very separation meant to constrain it. Keep it out of db/migration/ (the iron
-- rule "schema comes from Flyway only" is about tables, not principals).
--
-- Two-phase ordering (ops M2):
--   1. Create the roles BEFORE the BFF datasource first connects.
--   2. Apply the grants AFTER `flyway migrate` (the objects must exist).
-- Idempotent; safe to run at both phases and on every deploy. Re-running never
-- rotates a password unless one is passed (ops m1).
--
-- The threat it closes (OPERATIONS §7, security M8): compromise of the BFF is
-- admin on every registered engine already; we do NOT also want it able to
-- rewrite or destroy the human-accountability record. So the runtime role is a
-- NON-OWNER of the audit table with INSERT/SELECT + a column-scoped UPDATE that
-- matches EXACTLY what the append-only guard trigger permits, and no DELETE /
-- TRUNCATE. The guard trigger (V1) is defense-in-depth ON TOP of these grants.
--
-- Implementation note: psql variables do NOT interpolate inside DO/dollar-quoted
-- blocks, so idempotent role creation uses the SELECT ... \gexec pattern (plain
-- SQL, where :'var' does interpolate). Every backslash meta-command sits on its
-- own line with no trailing SQL or -- comment.
--
-- psql variables (defaults shown); override per environment:
--   -v owner=inspector          the migration/owner role Flyway runs as
--   -v db=inspector             the database to GRANT CONNECT on
--   -v app_role=inspector_app   the BFF runtime role (non-owner of audit)
--   -v ops_role=inspector_ops   the purge role (gains EXECUTE purge_audit at S5b)
--   -v app_password=...         set ONLY to (re)set the runtime role's password
--   -v ops_password=...         set ONLY to (re)set the purge role's password
--
-- Example:
--   psql "$INSPECTOR_DB_ADMIN_URL" \
--     -v owner=inspector -v db=inspector -v app_role=inspector_app \
--     -v ops_role=inspector_ops -v app_password="$INSPECTOR_APP_PASSWORD" \
--     -v ops_password="$INSPECTOR_OPS_PASSWORD" -f deploy/sql/audit-roles.sql
-- ============================================================================

\set ON_ERROR_STOP on

\if :{?owner}
\else
\set owner inspector
\endif
\if :{?app_role}
\else
\set app_role inspector_app
\endif
\if :{?ops_role}
\else
\set ops_role inspector_ops
\endif
-- The database to GRANT CONNECT on: the one we are connected to, NOT the owner
-- ROLE name (they coincide in our envs but must not be assumed — Gemini S0).
\if :{?db}
\else
SELECT current_database() AS db
\gset
\endif

-- ---------------------------------------------------------------------------
-- Phase 1 — roles. CREATE only when absent (Postgres has no CREATE ROLE IF NOT
-- EXISTS). A password is (re)set only when the caller passes one, so a routine
-- re-run never silently rotates or clears a live credential.
-- ---------------------------------------------------------------------------
SELECT format('CREATE ROLE %I LOGIN', :'app_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_role')
\gexec
SELECT format('CREATE ROLE %I LOGIN', :'ops_role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'ops_role')
\gexec

\if :{?app_password}
ALTER ROLE :"app_role" WITH LOGIN PASSWORD :'app_password';
\endif
\if :{?ops_password}
ALTER ROLE :"ops_role" WITH LOGIN PASSWORD :'ops_password';
\endif

-- ---------------------------------------------------------------------------
-- Phase 2 — grants (safe once the schema exists).
-- ---------------------------------------------------------------------------

-- Connect + schema usage.
GRANT CONNECT ON DATABASE :"db" TO :"app_role", :"ops_role";
GRANT USAGE ON SCHEMA public TO :"app_role", :"ops_role";

-- === The audit golden master — locked down for the runtime role ============
-- INSERT + SELECT + a COLUMN-SCOPED UPDATE matching the guard trigger's mutable
-- set EXACTLY (http_status, outcome, response_snippet, response_truncated). No
-- other column is UPDATE-able; no DELETE; no TRUNCATE. Kept in ONE place with
-- the trigger's allow-list — AuditRoleGrantsIT asserts they agree (security M7).
REVOKE ALL ON audit_entry FROM :"app_role";
GRANT INSERT, SELECT ON audit_entry TO :"app_role";
GRANT UPDATE (http_status, outcome, response_snippet, response_truncated) ON audit_entry TO :"app_role";
-- TRUNCATE bypasses the DELETE guard trigger and is NOT covered by REVOKE DELETE
-- (security M8). REVOKE ALL above dropped both; we restate the intent so it
-- survives a careless edit.
REVOKE DELETE, TRUNCATE ON audit_entry FROM :"app_role", PUBLIC;
-- Every INSERT calls nextval() on the chain sequence — without USAGE the runtime
-- role cannot write a single audit row (ops M1, the sharpest omission).
GRANT USAGE ON SEQUENCE audit_entry_seq TO :"app_role";

-- === Operational + config tables — the runtime role manages these fully =====
-- These carry no human-accountability guarantee. (triage_snapshot's partition
-- maintainer additionally needs DDL — when prod flips the app connection to the
-- runtime role, either that table is reassigned to the app role or its
-- CREATE/DROP moves behind a SECURITY DEFINER helper, mirroring purge_audit.
-- Tracked for S5a; not required at S0 because dev/test still connect as owner.)
GRANT SELECT, INSERT, UPDATE, DELETE ON
    engine_registry, triage_snapshot, bulk_job, bulk_job_item,
    saved_view, recent_search, instance_note, protected_instance
    TO :"app_role";
GRANT USAGE ON SEQUENCE triage_snapshot_seq TO :"app_role";
-- USAGE only (not SELECT — reading a counter's currval is a needless grant,
-- Gemini S0). Identity-column sequences need no explicit grant; this blanket is
-- a safety net for any plain DEFAULT nextval() sequence a later migration adds.
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO :"app_role";

-- === Default privileges — survive schema evolution WITHOUT re-granting ======
-- (ops M2). Objects a LATER Flyway migration creates auto-grant to the runtime
-- role. Only SELECT + INSERT: both UPDATE and DELETE are DELIBERATELY EXCLUDED
-- because future audit monthly partitions (S5a) are created by the owner and
-- would otherwise inherit a DIRECT, full-column UPDATE/DELETE grant on the
-- child — letting the app `UPDATE audit_entry_2026_08 SET actor=…` straight at a
-- partition, bypassing the parent's column-scoped grant and reopening the
-- append-only hole (Gemini S0). Legitimate audit writes route through the PARENT
-- (privilege checked on the parent, so the column-scoped UPDATE + INSERT there
-- suffice); direct INSERT/SELECT on a child is append-only-safe. A future
-- OPERATIONAL table that needs UPDATE/DELETE (e.g. legal_hold at S5b) gets an
-- explicit grant in its own provisioning step.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
    GRANT SELECT, INSERT ON TABLES TO :"app_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
    GRANT USAGE ON SEQUENCES TO :"app_role";

-- === Retention purge + legal hold (S5b) ====================================
-- legal_hold is an OPERATIONAL table the app fully manages (set/release). The
-- ALTER DEFAULT PRIVILEGES above auto-granted only SELECT+INSERT (append-only
-- posture for audit children); legal_hold additionally needs UPDATE (release) and
-- DELETE (the fail-closed compensating rollback of a set whose audit event failed),
-- so grant them explicitly — exactly the "future operational table needs
-- UPDATE/DELETE" case the default-privileges comment names.
GRANT SELECT, INSERT, UPDATE, DELETE ON legal_hold TO :"app_role";
GRANT SELECT ON legal_hold TO :"ops_role";

-- The BFF orchestrator (AuditRetentionPurger) is the single-writer purge caller. It
-- connects as the runtime role and invokes the SECURITY DEFINER purge_audit(), which
-- runs as the owner and DROPs the partition after DB-enforcing the retention floor,
-- whole-partition age, and legal holds. The app therefore holds EXECUTE on this
-- function — NEVER raw DROP on the audit tables (asserted in AuditRoleGrantsIT). ops
-- gets EXECUTE too, for a manual owner-adjacent purge.
GRANT EXECUTE ON FUNCTION purge_audit(text, timestamptz) TO :"app_role", :"ops_role";
