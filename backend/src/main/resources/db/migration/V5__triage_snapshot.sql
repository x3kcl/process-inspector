-- ============================================================================
-- V5__triage_snapshot.sql — the v2/M4 job-lane snapshot time-series.
--
-- The BFF shifts from a transient proxy to a stateful telemetry aggregator
-- (IMPLEMENTATION-PLAN v2/M4, decided 2026-07-07 pre-build). This table is the
-- store that keeps the trend UI (R-BAU-08 job-lane sparklines) OFF the live
-- engine: an @Scheduled virtual-thread sampler runs the existing Stage-0
-- count-only aggregation on a 30-60s cadence and upserts one narrow row per
-- (engine, lane, bucket) here; the UI reads history from Postgres, never the
-- engine.
--
-- DESIGN (locked boundary decisions, PLAN v2/M4):
--  * NARROW wide-row time-series (engine_id, lane, count, sampled_at) — NOT
--    JSONB. JSONB (+GIN) stays reserved for variable-snapshot / audit blobs.
--  * IDEMPOTENT upsert keyed on the (engine_id, lane, sampled_at) BUCKET so a
--    scheduler overlap or a restart within one bucket cannot double-count. The
--    sampler floors sampled_at to the bucket before writing (SnapshotBucket).
--    A poll is NOT a mutation — no corrective-action rails, no audit row.
--  * NOT append-only: unlike audit_entry, a bucket's count is updated in place
--    by the idempotent upsert, so there is deliberately no guard trigger here.
--  * Range-partitioned by sampled_at so retention (400-day revFADP, R-BAU-08)
--    is DROP-PARTITION, never DELETE (SnapshotPartitionMaintainer). The DEFAULT
--    partition guarantees an INSERT never fails because a monthly partition is
--    missing — sampling must never fail on partition housekeeping (mirrors the
--    audit_entry doctrine, V1__init.sql).
--  * `seq`/surrogate id is a plain sequence default (identity columns are not
--    supported on partitioned tables until PG 17), matching audit_entry.
--
-- ddl-auto=validate holds (iron rule): SnapshotCount aligns to THIS file. The
-- partitioning + the partitioned unique index are exactly what auto-DDL could
-- never author.
-- ============================================================================
CREATE SEQUENCE triage_snapshot_seq;

CREATE TABLE triage_snapshot (
    id         bigint      NOT NULL DEFAULT nextval('triage_snapshot_seq'),
    engine_id  text        NOT NULL,
    lane       text        NOT NULL,
    count      bigint      NOT NULL,
    sampled_at timestamptz NOT NULL,
    PRIMARY KEY (id, sampled_at),
    -- The lanes are the Stage-0 status chips plus the out-of-scope (CMMN)
    -- dead-letter projection (SPEC §4 Stage 0). A NULL out-of-scope count
    -- (pre-6.8, cannot discriminate scope) is NEVER stored as 0 — the sampler
    -- simply writes no OUT_OF_SCOPE_DLQ row, so the trend stays honest.
    CONSTRAINT triage_snapshot_count_nonneg CHECK (count >= 0),
    CONSTRAINT triage_snapshot_lane_valid
        CHECK (lane IN ('ACTIVE', 'SUSPENDED', 'COMPLETED', 'FAILED', 'RETRYING', 'OUT_OF_SCOPE_DLQ'))
) PARTITION BY RANGE (sampled_at);

-- The upsert arbiter (ON CONFLICT target). A unique index on a partitioned
-- table MUST include the partition key (sampled_at) — it does; sampled_at is
-- the bucket instant, so (engine_id, lane, sampled_at) is exactly one row per
-- bucket per lane per engine.
CREATE UNIQUE INDEX triage_snapshot_bucket ON triage_snapshot (engine_id, lane, sampled_at);

-- The trend read path: one engine's lanes over a time window, newest first.
CREATE INDEX idx_triage_snapshot_engine_lane_ts ON triage_snapshot (engine_id, lane, sampled_at DESC);

-- Safety-net catch-all so an INSERT never fails for a missing monthly
-- partition; SnapshotPartitionMaintainer creates the dated partitions ahead of
-- time (create-behind rows would otherwise block the CREATE) and drops the
-- ones past retention.
CREATE TABLE triage_snapshot_default PARTITION OF triage_snapshot DEFAULT;
