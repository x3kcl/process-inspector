-- ============================================================================
-- V22__incident_occurrence_fleet.sql — persist the observation SCOPE of the
-- pass that wrote the row (ALARM-COST-MODEL.md §16, issue #372).
--
-- WHY: V21 gave the series its SECOND QUALITY marker (`cycle_complete` —
-- "did every engine we are watching answer this pass?"). Neither marker says
-- anything about WHICH engines we were watching. A registry DISABLE simply
-- removes an engine from `EngineRegistry.all()`, so the pass never fans out to
-- it, `cycle_complete` stays TRUE — the cycle really is complete for its
-- (now smaller) scope — and nothing on the row records that the scope shrank.
-- Two rows can therefore both read `cycle_complete = true` while describing
-- DIFFERENT FLEETS, and differencing one against the other is exactly the
-- non-comparable-level hazard the `truncated` (R-SEM-12) and `cycle_complete`
-- (#302) disciplines exist to prevent:
--   * arrivalsSince (the attention F factor + the #365 burst bins) banks a
--     re-enable's level shift as hundreds of phantom ARRIVALS, on two rows
--     both stamped trusted;
--   * RetrySpellExtractor reads a DISABLE as a RETRYING spell ENDING (the
--     disabled engine held the class's retrying jobs), compares the surviving
--     engine's UNCHANGED dead-letter count against spell start, finds no
--     growth and records SELF_HEALED — fabricated self-heal evidence minted by
--     a routine, audited-but-unremarkable admin action. Every existing rail is
--     blind to it: `truncationTainted` is the scan cap, `hasBlindSample` does
--     not fire (the rows are honestly complete FOR THEIR SCOPE) and
--     `hasInternalGap` does not fire (the rows exist).
-- Quality stays `cycle_complete`'s job; SCOPE becomes `fleet`'s. The two are
-- orthogonal and are read TOGETHER at difference time.
--
-- VALUE: the canonical form of the enabled-engine id set the writing pass
-- fanned out over — ids sorted lexicographically and joined with ','. Sorting
-- matters: `perEngine` is registry-ordered and a pure REORDER is not a
-- composition change. Engine ids cannot contain ',' on any write path
-- (InspectorProperties.ENGINE_ID_PATTERN on the `config` path,
-- EngineRegistryStore.ID_PATTERN on the `db` path), so the join is unambiguous;
-- the canonicalizer still asserts-and-warns as belt-and-braces.
--
-- DEFAULT/BACKFILL CHOICE — '' ("scope was never recorded"), and NO UPDATE
-- pass, deliberately:
--   * Seeding existing rows with the CURRENT fleet would bake an out-of-band,
--     deploy-time observation into schema history as if those rows had recorded
--     it — the exact fabrication V21's own backfill note refuses. Scope at
--     write time cannot be reconstructed after the fact (the registry audit
--     trail does not exist at all under `inspector.registry.source: config`,
--     and event-time is not sample-time even where it does).
--   * '' is therefore comparable to NOTHING — not even to an adjacent '' (two
--     unrecorded scopes are not KNOWN to be the same scope). V21's fail-closed
--     rule applied unchanged: an unknown marker resolves to "untrusted", never
--     to "trusted".
--   * An EMPTY enabled fleet writes no occurrence rows at all (no engines ⇒ no
--     groups), so '' never legitimately occurs on a written row and is
--     unambiguous as the unrecorded sentinel.
-- The DEFAULT is KEPT (not dropped) after the migration so any future insert
-- path that forgets to state scope degrades SAFE; the ledger's own upsert
-- always passes it explicitly.
--
-- Adding a NOT NULL column with a non-volatile DEFAULT is metadata-only on
-- Postgres 11+ (no table rewrite) and cascades from the partitioned parent to
-- every existing monthly child + the DEFAULT catch-all — the V21 precedent on
-- this same table.
--
-- ddl-auto=validate holds (iron rule): io.inspector.incident.IncidentOccurrence
-- gains the matching mapped field in the same change.
-- ============================================================================

ALTER TABLE incident_occurrence
    ADD COLUMN fleet text NOT NULL DEFAULT '';

COMMENT ON COLUMN incident_occurrence.fleet IS
    'Canonical sorted comma-joined ids of the enabled engines the writing pass fanned out '
    'over (#372) — the row''s observation SCOPE, orthogonal to the two quality markers. '
    'Two rows are difference-comparable only when both carry the SAME non-empty fleet. '
    'Empty string = unrecorded (pre-V22 backfill, or a write path that failed to state '
    'scope): never comparable to anything, itself included.';
