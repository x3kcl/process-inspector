-- ============================================================================
-- V12__shared_view.sql — v2 shared (team-wide) saved views (SHARED-VIEWS.md,
-- R-SEM-24 / R-SAFE-16). An operator/admin publishes curated canon the team
-- inherits.
--
-- This is a SEPARATE governed store, NOT a `visibility` flag on `saved_view`
-- (SHARED-VIEWS.md §4.1 W2/F3): keeping team canon in its own table preserves
-- `saved_view`'s "owner-keyed prefs, no rails" invariant, makes the
-- preference→governance line the TABLE boundary, and keeps this migration a pure
-- additive CREATE (no risky ALTER/DROP-CONSTRAINT on the already-merged V6 work).
--
-- Publish = snapshot-copy a private view's (name, canonical search) into here
-- (create-only) — so editing the private bookmark never mutates team canon, and
-- the inherited owner-blind upsert-by-name (the canon-hijack path) has no reach
-- into this namespace.
--
-- Scope (`scope_engine_id`/`scope_tenant_id`) is DERIVED from the view's own
-- `search` string at publish time, never authored free-hand (§4.2): it gates BOTH
-- the `covers()` publish check and the `overlaps()` read-visibility filter. `'*'`
-- (never NULL) is the global wildcard — NOT NULL is load-bearing: a NULL scope
-- would fall outside the UNIQUE index (`NULL != NULL`) and let duplicate canon in.
--
-- ddl-auto=validate holds (iron rule): the JPA entity aligns to THIS file.
-- ============================================================================

CREATE TABLE shared_view (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The publishing user (Authentication#getName()), server-derived — the same
    -- identity audit_entry keys on as `actor`. Attribution only; never a read gate.
    author          text        NOT NULL,
    name            text        NOT NULL,
    -- The canonical URL search string (the ENTIRE Stage-1 view state), snapshot-
    -- copied at publish time. Replayed through the URL codec exactly like a private
    -- view (view == URL invariant).
    search          text        NOT NULL,
    -- Derived governance scope. `'*'` = global wildcard (visible to all, requires a
    -- global grant to publish). NOT NULL — see header.
    scope_engine_id text        NOT NULL DEFAULT '*',
    scope_tenant_id text        NOT NULL DEFAULT '*',
    -- Optional runbook affordances (R-BAU-03 model) that turn a bookmark into canon.
    -- Length caps + text-is-data are enforced app-side (R-OPS-08); NULL = absent.
    description     text,
    runbook_url     text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    -- One canon per (name, scope): create-only publish, overwrite = a moderation act.
    CONSTRAINT shared_view_name_scope UNIQUE (name, scope_engine_id, scope_tenant_id),
    CONSTRAINT shared_view_name_not_blank CHECK (char_length(btrim(name)) > 0)
);

-- The read path fetches the small team-canon set and filters by the caller's
-- overlapping grants in the BFF (wildcard overlap does not push cleanly to SQL);
-- this index keeps the ordered scan + the uniqueness probe cheap.
CREATE INDEX idx_shared_view_scope ON shared_view (scope_engine_id, scope_tenant_id);
