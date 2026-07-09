-- ============================================================================
-- V8__audit_payload_mode.sql — per-engine audit-payload minimization mode
-- (R-AUD-03, M4-CLOSEOUT §4 / S2).
--
-- Each engine declares how much of a mutation's payload its audit rows retain:
--   full          — names + values (the secret-name denylist still applies)
--   redacted      — names + structure; value-bearing leaves → «redacted» (DEFAULT)
--   metadata-only — only the skeleton coordinates; value-bearing keys dropped
--
-- Default 'redacted' = minimization by default (DATA-CLASSIFICATION §2); 'full' is
-- the deliberate opt-in. engine_registry is DB-authoritative-once-seeded, so
-- existing rows take the DEFAULT — they were implicitly denylist-only ("full")
-- before, and redacting them going forward is the safe posture.
-- ============================================================================
ALTER TABLE engine_registry
    ADD COLUMN audit_payload text NOT NULL DEFAULT 'redacted'
        CHECK (audit_payload IN ('full', 'redacted', 'metadata-only'));
