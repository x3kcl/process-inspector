-- ============================================================================
-- V18__incident_ledger.sql — the Incident Ledger substrate (R-BAU-10,
-- docs/INCIDENT-LEDGER.md §3).
--
-- The snapshot store (V5) remembers COUNTS; this ledger remembers FAILURE
-- CLASSES: one long-lived `incident` row per fleet-wide (signature_hash,
-- algo_version) — the R-SEM-03 binding contract, exactly as error_group_ack
-- (V15) — plus `incident_episode` rows (one per open→resolve cycle, the MTTR
-- substrate) and the narrow `incident_occurrence` time-series (sparklines).
--
-- DESIGN (locked, INCIDENT-LEDGER.md §3, panel-reviewed):
--  * incident/incident_episode are MUTABLE state ⇒ deliberately NO guard
--    triggers on any of the three tables (unlike audit_entry); lifecycle
--    history = episodes + R-AUD-10 config-event audit rows.
--  * Every incident transition is optimistic-locked (the `version` column,
--    JPA @Version) AND state-conditional in the sampler's UPDATEs
--    (`... WHERE state = :expected`) so an interleaved human resolve/reopen
--    makes a sampler write MISS rather than clobber.
--  * incident_occurrence's PRIMARY KEY is the business key
--    (incident_id, sampled_at) — a surrogate id would not be unique across
--    partitions and invites unsafe id-only references (panel: o3 BLOCKER fix).
--    Range-partitioned monthly like triage_snapshot; the DEFAULT catch-all
--    guarantees an INSERT never fails on partition housekeeping; retention is
--    DROP-PARTITION at 400 days (IncidentOccurrencePartitionMaintainer).
--  * FK kept on the partitioned child (panel P4): incident rows are never
--    deleted, months are created AHEAD while EMPTY (zero-cost FK validation),
--    and a partition DROP is metadata-only.
--
-- ddl-auto=validate holds (iron rule): the io.inspector.incident entities
-- align to THIS file.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- incident — identity + live state (long-lived, mutable, never deleted)
-- ---------------------------------------------------------------------------
CREATE TABLE incident (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    signature_hash     text NOT NULL,
    algo_version       int  NOT NULL,              -- R-SEM-03 binding contract, as in acks
    exception_class    text,                        -- nullable (no stacktrace refinement)
    normalized_message text NOT NULL,
    sample_raw_message text NOT NULL,
    state              text NOT NULL CHECK (state IN ('OPEN','RESOLVED','REGRESSED')),
    first_seen         timestamptz NOT NULL,
    last_seen          timestamptz NOT NULL,
    last_total         bigint NOT NULL,             -- lower bound when truncated
    last_truncated     boolean NOT NULL,
    counts_by_engine   jsonb NOT NULL,              -- latest engineId → "defKey:vN" → count (display blob, no GIN)
    seen_zero_since_resolve boolean NOT NULL DEFAULT false,  -- regression zero-state gate (§5)
    regression_count   int NOT NULL DEFAULT 0,
    last_regressed_at  timestamptz,
    version            bigint NOT NULL DEFAULT 0,   -- JPA @Version optimistic lock
    CONSTRAINT uq_incident UNIQUE (signature_hash, algo_version)
);
CREATE INDEX idx_incident_state ON incident (state, last_seen DESC);

-- ---------------------------------------------------------------------------
-- incident_episode — one row per open→resolve cycle (the MTTR substrate)
-- ---------------------------------------------------------------------------
CREATE TABLE incident_episode (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id    bigint NOT NULL REFERENCES incident(id),
    start_state    text NOT NULL CHECK (start_state IN ('OPEN','REGRESSED')),
    started_at     timestamptz NOT NULL,
    peak_total     bigint NOT NULL DEFAULT 0,       -- max observed live total this episode
    ended_at       timestamptz,                     -- NULL while episode is live
    resolved_by    text,
    resolve_reason text CHECK (resolve_reason IS NULL OR char_length(resolve_reason) >= 10),
    ticket_id      text
);
CREATE INDEX idx_incident_episode_incident ON incident_episode (incident_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- incident_occurrence — narrow time-series (sparkline/timeline)
-- ---------------------------------------------------------------------------
CREATE TABLE incident_occurrence (
    incident_id       bigint NOT NULL REFERENCES incident(id),
    sampled_at        timestamptz NOT NULL,
    total             bigint NOT NULL,
    dead_letter_count bigint NOT NULL,
    retrying_count    bigint NOT NULL,
    truncated         boolean NOT NULL,
    PRIMARY KEY (incident_id, sampled_at)            -- business key IS the PK (panel: o3 BLOCKER fix)
) PARTITION BY RANGE (sampled_at);

-- Safety-net catch-all so an INSERT never fails for a missing monthly
-- partition; IncidentOccurrencePartitionMaintainer creates the dated months
-- ahead of time (while empty) and drops the ones past retention.
CREATE TABLE incident_occurrence_default PARTITION OF incident_occurrence DEFAULT;
