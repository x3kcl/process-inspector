-- ============================================================================
-- V4 — usability fix E1-back: scope provenance on bulk_job. Every job now
-- records WHICH DOOR it came through (ticked-row grid selection vs the
-- triage-landing error-class group retry vs the select-all-matching-filter
-- bulk) plus a short human-readable summary of what was targeted, so the
-- operations drawer can show it without re-deriving it from the envelope
-- audit payload. Plain bulk_job change — audit_entry is untouched.
-- ============================================================================

ALTER TABLE bulk_job
    ADD COLUMN scope_kind varchar NOT NULL DEFAULT 'SELECTION',
    ADD COLUMN scope_label varchar NULL;
