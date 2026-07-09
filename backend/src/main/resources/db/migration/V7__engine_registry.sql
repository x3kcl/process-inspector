-- ============================================================================
-- V7__engine_registry.sql — v2 Registry CRUD (docs/REGISTRY-CRUD.md §10, R-OPS-15).
--
-- The engine registry moves from the `inspector.engines` YAML list to a DB table so ops
-- can onboard / retire / tune engines at runtime without a redeploy. DB-authoritative once
-- initialized: an empty table imports the YAML as a one-time audited seed (`source =
-- 'yaml-seed'`), a non-empty table wins over YAML (drift is reported, never silent), and
-- `inspector.registry.source: config` pins to config-only (restart semantics restored).
--
-- Secrets stay env-refs (iron rule): only the password/token env-var NAME is stored, never a
-- value. `id` is the immutable slug baked into composite instance IDs / audit rows / saved
-- views (R-SEM-08) — edit changes everything EXCEPT id; delete is a soft `removed_at` tombstone
-- so id→name resolution survives for historical references.
--
-- ddl-auto=validate holds (iron rule): the EngineRegistryRow entity aligns to THIS file.
-- Additive migration (R-SEM-10). Deferred columns (advisories / four-eyes / forward-user-header
-- / jurisdiction) are added when their features build — never a lie now.
-- ============================================================================

CREATE TABLE engine_registry (
    id            text PRIMARY KEY
                    CHECK (id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),   -- ENGINE_ID_PATTERN, immutable
    name          text NOT NULL,
    base_url      text NOT NULL,
    environment   text NOT NULL CHECK (environment IN ('dev','test','prod')),
    mode          text NOT NULL DEFAULT 'read-only'
                    CHECK (mode IN ('read-write','read-only')),
    lifecycle     text NOT NULL DEFAULT 'draft'
                    CHECK (lifecycle IN ('draft','probed','probe_failed','active','disabled','removed')),
    accent_color  text,
    tenant_id     text,
    telemetry_url_template text,
    auth_type     text NOT NULL CHECK (auth_type IN ('basic','bearer','none')),
    auth_username text,
    password_ref  text,          -- env-var NAME only, never a secret value
    token_ref     text,          -- env-var NAME only
    -- do-no-harm knobs (nullable → code defaults in EngineConfig):
    connect_ms int, read_ms int, write_ms int,
    max_page_size int, dlq_scan_cap int,
    alarm_oldest_warn_min int, alarm_oldest_crit_min int, alarm_overdue_grace_s int,
    -- provenance:
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    removed_at  timestamptz,     -- tombstone; NULL = live
    source      text NOT NULL DEFAULT 'ui' CHECK (source IN ('ui','yaml-seed'))
);

-- Live rows only (tombstones excluded) — the fan-out / lookup path filters on this.
CREATE INDEX idx_engine_registry_live ON engine_registry (lifecycle) WHERE removed_at IS NULL;
