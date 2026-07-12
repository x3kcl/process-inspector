-- Registry S4b (docs/REGISTRY-CRUD.md §9, R-SAFE-08, #91): the four-eyes proposal store for the
-- registry admin surface. A prod enable-read-write, a remove, or a purge is not applied directly:
-- it becomes a PENDING proposal that a SECOND, independent REGISTRY_ADMIN (proposer's own
-- REGISTRY_ADMIN group(s) excluded, proposer != approver) must approve before it applies. Mirrors
-- V14 access_grant_proposal's shape; one table covers all three change kinds since the "change" is
-- fully described by (kind, engine_id) with no extra parameters.
CREATE TABLE registry_write_proposal (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposer        text NOT NULL,
    proposer_groups text NOT NULL DEFAULT '',  -- comma-joined REGISTRY_ADMIN groups the proposer held
    engine_id       text NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('ENABLE_READ_WRITE', 'REMOVE', 'PURGE')),
    summary         text NOT NULL,             -- human-legible one-liner for the R-SAFE-08 inbox
    reason          text NOT NULL,
    status          text NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    approver        text,
    decided_at      timestamptz
);

CREATE INDEX idx_registry_write_proposal_status ON registry_write_proposal (status);
