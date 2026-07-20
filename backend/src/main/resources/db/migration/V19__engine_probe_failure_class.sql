-- ============================================================================
-- V19__engine_probe_failure_class.sql — a coarse, UI-safe failure class for the
-- most recent failed registry probe (issue #275).
--
-- The probe audit trail (V7 engine_registry + AuditService.close's response_snippet,
-- issue #223/#231) already persists the FULL connection error server-side — that part
-- was fixed already. What was still missing was any UI-legible signal on the row
-- itself for WHY the last probe failed: the admin table only ever showed the generic
-- lifecycle label `probe_failed`, forcing every failure (SSRF-rejected, missing
-- secret, credential rejection, unreachable) to read identically.
--
-- `last_probe_failure_class` carries one of a small closed set of coarse buckets
-- (ssrf_rejected | missing_secret_ref | auth_rejected | unexpected_response |
-- unreachable) — never the raw exception text (that stays audit-only, topology
-- oracle risk per AdminEnginesController's probe() doc comment). Nullable: null on
-- every non-probe_failed row (including a row that has never been probed), and
-- cleared back to null the moment a subsequent probe succeeds (recordProbe).
-- ============================================================================
ALTER TABLE engine_registry
    ADD COLUMN last_probe_failure_class varchar(40);
