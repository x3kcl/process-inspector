-- ============================================================================
-- V2 — M5 bulk operations (SPEC §7): grid-selection bulk as a PERSISTED tracked
-- job from day one (R-SEM-10). Schema-first per the iron rule: this file is the
-- truth, the JPA entities align to it (ddl-auto=validate everywhere).
--
-- Job state machine (normative):  PENDING → RUNNING → (COMPLETED | CANCELLED |
-- INTERRUPTED).  Item states:     pending → dispatched → (ok | failed | skipped
-- | skipped_protected | unknown | not_run).
-- On BFF startup a reconciliation sweep marks RUNNING → INTERRUPTED; the item in
-- flight at crash becomes unknown (never re-fired); undispatched become not_run.
-- ============================================================================

CREATE TABLE bulk_job (
    id             uuid        PRIMARY KEY,
    submitted_by   text        NOT NULL,
    submitted_at   timestamptz NOT NULL,
    verb           text        NOT NULL,
    reason         text,
    ticket_id      text,
    state          text        NOT NULL,
    total_items    int         NOT NULL,
    finished_at    timestamptz,
    -- "continue as new job" lineage (SPEC §7): the INTERRUPTED/partial job this
    -- one re-scopes; NULL for first-run jobs.
    continued_from uuid        REFERENCES bulk_job (id),
    CONSTRAINT bulk_job_state CHECK (state IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'INTERRUPTED')),
    CONSTRAINT bulk_job_cap CHECK (total_items BETWEEN 1 AND 200)
);

CREATE INDEX idx_bulk_job_recency ON bulk_job (submitted_at DESC);
CREATE INDEX idx_bulk_job_state ON bulk_job (state);

CREATE TABLE bulk_job_item (
    job_id      uuid        NOT NULL REFERENCES bulk_job (id),
    ordinal     int         NOT NULL,
    engine_id   text        NOT NULL,
    instance_id text        NOT NULL,
    -- verb-specific sub-target (the dead-letter/timer job id for retry verbs).
    job_ref     text,
    state       text        NOT NULL,
    -- operator-facing outcome detail: engine words, skip reason, verify evidence.
    detail      text,
    -- the per-item audit_entry row (one audit row per item + one for the envelope).
    audit_id    uuid,
    finished_at timestamptz,
    PRIMARY KEY (job_id, ordinal),
    CONSTRAINT bulk_item_state CHECK (
        state IN ('pending', 'dispatched', 'ok', 'failed', 'skipped', 'skipped_protected', 'unknown', 'not_run')
    )
);

CREATE INDEX idx_bulk_item_instance ON bulk_job_item (engine_id, instance_id);
