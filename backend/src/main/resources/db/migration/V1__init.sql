-- ============================================================================
-- V1__init.sql — the BFF-owned Postgres baseline (M4).
--
-- BUILD-ORDER CONSTRAINT (IMPLEMENTATION-PLAN M4, binding): this file is the
-- single source of schema truth. The JPA entities align to THIS file, never
-- the reverse; ddl-auto=validate in every profile. Partitioning, the guard
-- trigger and the hash-chain column below are exactly the things auto-DDL
-- could never author.
--
-- Tables:
--   audit_entry        — append-only audit golden master (SPEC §9, R-AUD-01/02)
--   instance_note      — operator notes per composite ID (SPEC §9)
--   protected_instance — the R-SAFE-05 protected-instance guard store
-- ============================================================================

-- ---------------------------------------------------------------------------
-- audit_entry — normative schema per SPEC §9 (R-AUD-02).
--
-- * "user" is a reserved word in Postgres; the spec field `user` is column
--   `actor`.
-- * `bulk_job_id` references the M5 bulk_job table; the FK constraint lands
--   with that table's migration (schema is additive — R-SEM-10).
-- * Range-partitioned by ts (monthly partitions are an operations job,
--   OPERATIONS.md §6); the DEFAULT partition guarantees an INSERT never fails
--   because a monthly partition is missing — fail-closed must never fire
--   because of partition housekeeping.
-- * `seq` is a plain sequence default (identity columns are not supported on
--   partitioned tables until PG 17) and orders the tamper-evidence hash chain.
-- * `chain_hash` covers the IMMUTABLE insert-time columns + the previous
--   row's hash; the mutable outcome columns (outcome, http_status,
--   response_snippet, response_truncated) are excluded because the
--   PENDING → ok|failed|unknown lifecycle (R-SEM-18) updates them in place.
-- ---------------------------------------------------------------------------
CREATE SEQUENCE audit_entry_seq;

CREATE TABLE audit_entry (
    id                 uuid        NOT NULL,
    seq                bigint      NOT NULL DEFAULT nextval('audit_entry_seq'),
    correlation_id     text        NOT NULL,
    bulk_job_id        uuid,
    actor              text        NOT NULL,
    ts                 timestamptz NOT NULL,
    engine_id          text        NOT NULL,
    tenant_id          text,
    instance_id        text,
    action             text        NOT NULL,
    reason             text,
    ticket_id          text,
    payload            jsonb,
    http_status        integer,
    outcome            text        NOT NULL,
    response_snippet   text,
    response_truncated boolean     NOT NULL DEFAULT false,
    break_glass        boolean     NOT NULL DEFAULT false,
    approved_by        text,
    chain_hash         text,
    PRIMARY KEY (id, ts),
    CONSTRAINT audit_outcome_valid
        CHECK (outcome IN ('PENDING', 'ok', 'failed', 'unknown')),
    -- Reasons are ≥10 chars WHEN present (SPEC §2: reasons ≥10 chars); which
    -- tiers require one is guard-ladder logic (SPEC §6), enforced in the BFF.
    CONSTRAINT audit_reason_min_length
        CHECK (reason IS NULL OR char_length(reason) >= 10)
) PARTITION BY RANGE (ts);

CREATE TABLE audit_entry_default PARTITION OF audit_entry DEFAULT;

-- R-AUD-02 indexes: the per-instance Audit tab and the global operations log.
CREATE INDEX idx_audit_engine_instance_ts ON audit_entry (engine_id, instance_id, ts);
CREATE INDEX idx_audit_ts ON audit_entry (ts);

-- ---------------------------------------------------------------------------
-- Tamper-evidence guard (OPERATIONS.md §6): the audit log is append-only.
--  * DELETE is blocked outright (the audited retention purge runs with the
--    trigger disabled under a dedicated ops role — never the app role).
--  * UPDATE may only move outcome forward from PENDING (dual-write close-out)
--    or from unknown (the R-SAFE-09 "Verify now" reconciliation), and may only
--    touch the mutable outcome columns — every insert-time column is frozen.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_entry_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_entry is append-only: DELETE is not permitted';
    END IF;
    IF OLD.outcome NOT IN ('PENDING', 'unknown') THEN
        RAISE EXCEPTION 'audit_entry outcome % is final and cannot be updated', OLD.outcome;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.seq IS DISTINCT FROM OLD.seq
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
        OR NEW.bulk_job_id IS DISTINCT FROM OLD.bulk_job_id
        OR NEW.actor IS DISTINCT FROM OLD.actor
        OR NEW.ts IS DISTINCT FROM OLD.ts
        OR NEW.engine_id IS DISTINCT FROM OLD.engine_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.instance_id IS DISTINCT FROM OLD.instance_id
        OR NEW.action IS DISTINCT FROM OLD.action
        OR NEW.reason IS DISTINCT FROM OLD.reason
        OR NEW.ticket_id IS DISTINCT FROM OLD.ticket_id
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.break_glass IS DISTINCT FROM OLD.break_glass
        OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
        OR NEW.chain_hash IS DISTINCT FROM OLD.chain_hash THEN
        RAISE EXCEPTION 'audit_entry insert-time columns are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_entry_append_only
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit_entry_guard();

-- ---------------------------------------------------------------------------
-- instance_note — BFF-owned notes per composite ID (SPEC §9): author +
-- timestamp; the grid's "has notes" marker and copy-for-ticket read from here.
-- ---------------------------------------------------------------------------
CREATE TABLE instance_note (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    engine_id   text        NOT NULL,
    instance_id text        NOT NULL,
    author      text        NOT NULL,
    ts          timestamptz NOT NULL,
    body        text        NOT NULL,
    CONSTRAINT note_body_not_blank CHECK (char_length(btrim(body)) > 0)
);

CREATE INDEX idx_note_engine_instance ON instance_note (engine_id, instance_id);

-- ---------------------------------------------------------------------------
-- protected_instance — R-SAFE-05: composite IDs marked protected by L3+.
-- Below the ADMIN floor every verb on a protected target is refused with the
-- protection reason. Marking/unmarking is itself a tier-3 audited action.
-- ---------------------------------------------------------------------------
CREATE TABLE protected_instance (
    engine_id   text        NOT NULL,
    instance_id text        NOT NULL,
    reason      text        NOT NULL,
    created_by  text        NOT NULL,
    ts          timestamptz NOT NULL,
    PRIMARY KEY (engine_id, instance_id),
    CONSTRAINT protected_reason_min_length CHECK (char_length(reason) >= 10)
);
