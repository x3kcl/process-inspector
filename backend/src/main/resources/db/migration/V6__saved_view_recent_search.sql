-- ============================================================================
-- V6__saved_view_recent_search.sql — v2/M4: v1 localStorage payloads (Saved Views
-- and Recent Searches, SPEC §8) migrate to per-user relational tables.
--
-- The BFF is a stateful aggregator now, so a user's saved views and recents follow
-- them across browsers instead of living in one device's localStorage. Every row is
-- OWNED by the authenticated user (`owner` = Authentication#getName(), the same
-- identity audit_entry keys on as `actor`); the BFF scopes every read/write to the
-- caller's owner — a user can never see or touch another's rows. System views
-- (R-SEM-05 minute-floored relative windows) stay client-derived — they materialize
-- at render time and are never persisted.
--
-- ddl-auto=validate holds (iron rule): the JPA entities align to THIS file. Schema is
-- additive (R-SEM-10).
-- ============================================================================

-- A named saved view = the owner's canonical URL search string under a unique name.
-- Saving under an existing name REPLACES it (the client's upsert-by-name semantics),
-- enforced by the UNIQUE (owner, name).
CREATE TABLE saved_view (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner      text        NOT NULL,
    name       text        NOT NULL,
    search     text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT saved_view_owner_name UNIQUE (owner, name),
    CONSTRAINT saved_view_name_not_blank CHECK (char_length(btrim(name)) > 0)
);
CREATE INDEX idx_saved_view_owner ON saved_view (owner, created_at DESC);

-- The owner's recent searches — newest-first, deduped by search, capped per user in the
-- BFF (matching the client's cap of 10) rather than in SQL. UNIQUE (owner, search) makes
-- "record this search" an upsert that bumps the timestamp instead of duplicating a row.
CREATE TABLE recent_search (
    id     bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner  text        NOT NULL,
    search text        NOT NULL,
    label  text        NOT NULL,
    at     timestamptz NOT NULL,
    CONSTRAINT recent_search_owner_search UNIQUE (owner, search)
);
CREATE INDEX idx_recent_search_owner ON recent_search (owner, at DESC);
