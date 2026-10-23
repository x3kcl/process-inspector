-- Cohort analysis history (#495). One row per run of the §14 estimate over an S2
-- cohort filter. Schema is the truth (ddl-auto=validate). Re-GET reads this row;
-- the analysis is not recomputed.
CREATE TABLE migration_analysis (
    id                    uuid        PRIMARY KEY,
    engine_id             text        NOT NULL,
    status                text        NOT NULL,
    cohort_filter         jsonb       NOT NULL,
    source_definition_id  text        NOT NULL,
    target_definition_id  text,
    taxonomy_version      int         NOT NULL,
    code_counts           jsonb       NOT NULL,
    sample_instance_ids   jsonb       NOT NULL,
    payload               jsonb       NOT NULL,
    created_at            timestamptz NOT NULL,
    finished_at           timestamptz,
    CONSTRAINT migration_analysis_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);
