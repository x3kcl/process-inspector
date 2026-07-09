-- v2 IdP-Security S3 (IDP-SECURITY.md §6/§11, R-SAFE-12): the group→scope mapping moves from a
-- mounted YAML file to a DB store with per-change CRUD (S4). Two tables so a fleet grant can NEVER
-- read as a (role, engine, tenant) ladder row. DB-authoritative once seeded: an empty store imports
-- the mounted file as one-time audited seed rows, then the DB wins (mapping-source: file pins to the
-- file, CRUD off). Flyway owns the schema; ddl-auto=validate holds. No secret/token/OIDC config
-- lives here — identity comes from the IdP, secrets stay env-refs.

-- Ladder grants: a group → a role on an engine/tenant scope.
CREATE TABLE group_scope_grant (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name  text NOT NULL,
    role        text NOT NULL CHECK (role IN ('VIEWER', 'RESPONDER', 'OPERATOR', 'ADMIN')),
    engine_id   text NOT NULL DEFAULT '*'
                  CHECK (engine_id = '*' OR engine_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),  -- R-SEM-08
    tenant_id   text NOT NULL DEFAULT '*',
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    source      text NOT NULL DEFAULT 'ui' CHECK (source IN ('ui', 'file-seed')),
    UNIQUE (group_name, role, engine_id, tenant_id)
);

-- Orthogonal fleet grants: a group → REGISTRY_ADMIN | ACCESS_ADMIN (never a ladder rung).
CREATE TABLE group_fleet_grant (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_name  text NOT NULL,
    grant_kind  text NOT NULL CHECK (grant_kind IN ('REGISTRY_ADMIN', 'ACCESS_ADMIN')),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    source      text NOT NULL DEFAULT 'ui' CHECK (source IN ('ui', 'file-seed')),
    UNIQUE (group_name, grant_kind)
);

CREATE INDEX idx_group_scope_grant_group ON group_scope_grant (group_name);
CREATE INDEX idx_group_fleet_grant_group ON group_fleet_grant (group_name);
