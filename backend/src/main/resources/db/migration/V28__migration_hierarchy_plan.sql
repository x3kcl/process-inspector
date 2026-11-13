-- Hierarchy-aware migration (#497). Schema is the truth (ddl-auto=validate).
--
-- A plan is the call-activity tree under its root(s), enumerated once and stored: the bulk job
-- takes its scope from this row, never from a browser list. The tree does not change after
-- create; the operator's choice per child definition lives in migration_hierarchy_group.
CREATE TABLE migration_hierarchy_plan (
    id          uuid        PRIMARY KEY,
    engine_id   text        NOT NULL,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    payload     jsonb       NOT NULL
);

-- One row per decided definition of a plan. No row = the group is still pending.
-- CONFIGURED carries the cohort analysis (V27) that pins the target, the mapping document and
-- each instance's compare-and-set digest.
CREATE TABLE migration_hierarchy_group (
    plan_id              uuid        NOT NULL REFERENCES migration_hierarchy_plan (id),
    definition_id        text        NOT NULL,
    decision             text        NOT NULL,
    analysis_id          uuid        REFERENCES migration_analysis (id),
    decided_by           text        NOT NULL,
    decided_at           timestamptz NOT NULL,
    PRIMARY KEY (plan_id, definition_id),
    CONSTRAINT migration_hierarchy_group_decision CHECK (decision IN ('CONFIGURED', 'SKIPPED')),
    CONSTRAINT migration_hierarchy_group_analysis CHECK ((decision = 'CONFIGURED') = (analysis_id IS NOT NULL))
);

-- Dispatch order of a hierarchy job: the deepest level runs first, and an item waits for the
-- items that name it as parent. Both NULL for every other bulk job.
ALTER TABLE bulk_job_item
    ADD COLUMN level int,
    ADD COLUMN parent_instance_id text;
