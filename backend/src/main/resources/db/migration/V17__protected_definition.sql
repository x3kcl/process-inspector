-- ---------------------------------------------------------------------------
-- protected_definition — R-SAFE-05 (#172): the definition-key-level half of
-- protected-instance scope deferred from #165. A SIBLING table, not columns
-- bolted onto protected_instance — that table's PK (engine_id, instance_id)
-- is NOT NULL on both, and a definition-scope row has no instance_id at all;
-- nullable-PK-column modeling is worse than a second table for a genuinely
-- distinct concept. Same shape as protected_instance otherwise.
-- ---------------------------------------------------------------------------
CREATE TABLE protected_definition (
    engine_id      text        NOT NULL,
    definition_key text        NOT NULL,
    reason         text        NOT NULL,
    created_by     text        NOT NULL,
    ts             timestamptz NOT NULL,
    PRIMARY KEY (engine_id, definition_key),
    CONSTRAINT protected_definition_reason_min_length CHECK (char_length(reason) >= 10)
);
