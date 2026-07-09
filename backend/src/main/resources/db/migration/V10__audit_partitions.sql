-- ============================================================================
-- V10__audit_partitions.sql — carve audit_entry's DEFAULT partition into months
-- (M4-CLOSEOUT §A2 / S5a). The create-ahead substrate for retention (S5b).
--
-- audit_entry is PARTITION BY RANGE (ts) but V1 shipped only the audit_entry_default
-- safety net — so there are no monthly partitions to drop, and "drop old partitions"
-- would drop nothing. This migration:
--   1. carves any rows already sitting in audit_entry_default into monthly children
--      (existing deployments), and
--   2. creates the current + next month ahead so ongoing writes never fall to DEFAULT
--      before AuditPartitionMaintainer's first run (which then keeps them rolling).
-- On a fresh deployment the DEFAULT is empty and step 1 is a no-op.
--
-- APPEND-ONLY IS NEVER RELAXED. The V1 guard trigger `audit_entry_append_only` is
-- BEFORE UPDATE OR DELETE — the carve uses only DETACH / CREATE / INSERT / TRUNCATE /
-- ATTACH, none of which the trigger blocks, so it stays ENABLED throughout. The
-- tamper-evidence hash chain is preserved verbatim: rows move by `INSERT ... SELECT *`
-- (id, seq, chain_hash and every insert-time column copied exactly), and `seq` is a
-- global sequence, so seq-ordered chain verification is unaffected by which partition a
-- row lives in. Runs as the schema owner (Flyway). Atomicity depends on Flyway's
-- DEFAULT single-transaction-per-migration mode (DETACH / ATTACH PARTITION and
-- TRUNCATE are all transactional in PG16): a failure anywhere rolls the DETACH back
-- and DEFAULT is never left orphaned. Do NOT set executeInTransaction=false for this
-- migration.
--
-- Naming is audit_entry_YYYY_MM (matches deploy/sql/audit-roles.sql + AuditRoleGrantsIT
-- + AuditPartitions.name()). Owner-created children auto-grant SELECT/INSERT (never
-- UPDATE/DELETE) to inspector_app via the ALTER DEFAULT PRIVILEGES in audit-roles.sql,
-- so append-only survives the new partitions.
-- ============================================================================

-- 1. Carve existing DEFAULT rows into monthly partitions (no-op when DEFAULT is empty).
DO $$
DECLARE
    lo    date;
    hi    date;
    m     date;
    moved bigint := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM audit_entry_default) THEN
        SELECT date_trunc('month', min(ts))::date,
               date_trunc('month', max(ts))::date
          INTO lo, hi
          FROM audit_entry_default;

        -- Detach so month partitions can be created without the DEFAULT-overlap rejection.
        ALTER TABLE audit_entry DETACH PARTITION audit_entry_default;

        m := lo;
        WHILE m <= hi LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_entry FOR VALUES FROM (%L) TO (%L)',
                'audit_entry_' || to_char(m, 'YYYY_MM'),
                m::timestamptz,
                (m + interval '1 month')::timestamptz);
            m := (m + interval '1 month')::date;
        END LOOP;

        -- Route the historical rows into their monthly children, then empty + reattach DEFAULT.
        -- SELECT * is deliberate and safe HERE: a partition is column-identical to its parent by
        -- construction, and nothing ALTERs the table between the DETACH and this INSERT in the same
        -- migration — so the columns cannot drift. (A hand-listed 19-column list would only add a
        -- transcription-error surface with no added safety.) seq/chain_hash are copied verbatim, so
        -- the explicit values override the DEFAULT nextval and the tamper-evidence chain is intact.
        INSERT INTO audit_entry SELECT * FROM audit_entry_default;
        GET DIAGNOSTICS moved = ROW_COUNT;
        TRUNCATE audit_entry_default;
        ALTER TABLE audit_entry ATTACH PARTITION audit_entry_default DEFAULT;

        RAISE NOTICE 'V10 carve: moved % audit rows from DEFAULT into monthly partitions [%..%]', moved, lo, hi;
    END IF;
END $$;

-- 2. Create-ahead the current + next month (idempotent with step 1 on populated deployments).
DO $$
DECLARE
    base date := date_trunc('month', now())::date;
    m    date;
BEGIN
    FOR i IN 0..1 LOOP
        m := (base + (i || ' month')::interval)::date;
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_entry FOR VALUES FROM (%L) TO (%L)',
            'audit_entry_' || to_char(m, 'YYYY_MM'),
            m::timestamptz,
            (m + interval '1 month')::timestamptz);
    END LOOP;
END $$;
