-- Usability W3-2 (R-BAU-01, SPEC §4 Stage 0 "Acknowledge"): persisted error-group
-- acknowledgments. Keyed signature (hash + algo_version) × engine × definition KEY — the
-- R-SEM-03 binding contract: a normalizer bump changes algo_version, so acks from an older
-- generation simply stop matching (needs re-binding) instead of silently re-binding.
-- acknowledged_count / acknowledged_max_version are the BASELINE the auto-resurface
-- predicate compares live aggregation state against (growth past +threshold, or a new
-- failing definition version). Rows are state, not history — every transition writes a
-- config-event audit row (R-AUD-10); un-acknowledge deletes the rows.
CREATE TABLE error_group_ack (
    id                       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signature_hash           text NOT NULL,
    algo_version             int NOT NULL,
    engine_id                text NOT NULL,
    definition_key           text NOT NULL,
    acknowledged_by          text NOT NULL,
    reason                   text NOT NULL
        CONSTRAINT error_group_ack_reason_min_length CHECK (char_length(reason) >= 10),
    ticket_id                text,
    acknowledged_at          timestamptz NOT NULL,
    expires_at               timestamptz,
    acknowledged_count       bigint NOT NULL,
    acknowledged_max_version int,
    CONSTRAINT uq_error_group_ack UNIQUE (signature_hash, algo_version, engine_id, definition_key)
);

-- The triage render-time join reads by signature; the unique constraint above already
-- serves the per-slice upsert path.
CREATE INDEX idx_error_group_ack_signature ON error_group_ack (signature_hash, algo_version);
