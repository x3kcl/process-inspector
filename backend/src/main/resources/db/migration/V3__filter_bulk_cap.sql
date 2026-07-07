-- ============================================================================
-- V3 — v1.x fast follow #2 (SPEC §7): select-all-matching-filter bulk. The
-- filter-resolved path may carry up to 5000 items (the query-bulk hard cap);
-- the grid-selection and error-class paths keep their 200-item cap in the
-- service layer — this constraint is the outer DB backstop for ALL paths.
-- ============================================================================

ALTER TABLE bulk_job DROP CONSTRAINT bulk_job_cap;
ALTER TABLE bulk_job ADD CONSTRAINT bulk_job_cap CHECK (total_items BETWEEN 1 AND 5000);
