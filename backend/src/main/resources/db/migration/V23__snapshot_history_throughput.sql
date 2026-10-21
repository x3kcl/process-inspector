-- History backlog and per-bucket throughput (#484). A missing history route writes no row.
ALTER TABLE triage_snapshot DROP CONSTRAINT triage_snapshot_lane_valid;
ALTER TABLE triage_snapshot ADD CONSTRAINT triage_snapshot_lane_valid CHECK (lane IN (
    'ACTIVE', 'SUSPENDED', 'COMPLETED', 'FAILED', 'RETRYING', 'OUT_OF_SCOPE_DLQ',
    'HISTORY_BACKLOG', 'PROC_STARTED', 'PROC_FINISHED'));
