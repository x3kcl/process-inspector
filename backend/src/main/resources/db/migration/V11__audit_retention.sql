-- ============================================================================
-- V11__audit_retention.sql — retention purge machinery (M4-CLOSEOUT §A2 / §5 / S5b).
--
-- Two objects, both owned by the schema owner (Flyway):
--   1. legal_hold — engine/tenant/time-window holds, consulted BY THE DB inside
--      purge_audit (a cooperating job could ignore a config file; the DB cannot).
--   2. purge_audit(partition, cutoff) — a SECURITY DEFINER function that DROPs a
--      single aged-out monthly audit partition, but ONLY after enforcing, in the
--      DB and independent of the caller:
--        (a) a hard retention floor — it refuses any cutoff newer than the floor,
--            so a compromised caller cannot wipe recent audit;
--        (b) the whole partition is older than the cutoff; and
--        (c) no ACTIVE legal_hold overlaps the partition's time range.
--      The BFF connects as the non-owner inspector_app and holds EXECUTE on this
--      function only — never raw DROP (deploy/sql/audit-roles.sql). So DROP
--      capability is not ambient in the most-attacked process, and age/holds are
--      DB-enforced. The BFF orchestrator (AuditRetentionPurger) writes the
--      fail-closed audit-retention-purge config event WITH the chain checkpoint
--      BEFORE calling this — the single-writer chain stays stitched across the gap.
--
-- Whole-partition hold skip over-retains unrelated subjects by up to a partition
-- width (DP MAJOR-5) — documented in DATA-CLASSIFICATION §3; row-scoped enforcement
-- is a v-next. legal_hold is a justified Flyway touch (it backs the DB-enforced
-- check — a config file cannot).
-- ============================================================================

CREATE TABLE legal_hold (
    id          uuid PRIMARY KEY,
    engine_id   text,          -- NULL = all engines (scope is advisory metadata; the
    tenant_id   text,          -- NULL = all tenants   purge skips the whole overlapping partition regardless)
    from_ts     timestamptz NOT NULL,
    to_ts       timestamptz NOT NULL,
    reason      text NOT NULL CHECK (char_length(reason) >= 10),
    created_by  text NOT NULL,
    created_at  timestamptz NOT NULL,
    released_at timestamptz,   -- NULL = active
    released_by text,
    CONSTRAINT legal_hold_window CHECK (to_ts > from_ts)
);

-- Active holds only — the overlap check in purge_audit filters on this.
CREATE INDEX idx_legal_hold_active ON legal_hold (from_ts, to_ts) WHERE released_at IS NULL;

-- ---------------------------------------------------------------------------
-- purge_audit — drop ONE aged-out monthly audit partition, DB-enforced.
-- ---------------------------------------------------------------------------
CREATE FUNCTION purge_audit(p_partition text, p_cutoff timestamptz)
    RETURNS TABLE (dropped_partition text, range_from timestamptz, range_to timestamptz)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = public, pg_catalog
AS $$
DECLARE
    -- Hard retention floor (M4-CLOSEOUT §A2). Matches the default
    -- inspector.audit.retention-days; LOWERING retention below this requires a new
    -- migration, deliberately — the app config alone can never purge younger data.
    v_floor_days constant int := 400;
    v_from timestamptz;
    v_to   timestamptz;
    v_bound text;
BEGIN
    -- (a) Floor: never purge data younger than the retention floor, whatever the caller passes.
    -- `>` (not `>=`) is deliberate — a cutoff exactly at the floor purges data exactly at the
    -- retention limit, which is correct. now()/timestamptz compare as absolute instants, so a
    -- caller's session timezone cannot move this boundary.
    IF p_cutoff > now() - make_interval(days => v_floor_days) THEN
        RAISE EXCEPTION 'purge_audit refused: cutoff % is newer than the %-day retention floor',
            p_cutoff, v_floor_days;
    END IF;

    -- Resolve the target's REAL partition bound from the catalog (never trust a caller-supplied
    -- range). A non-child, the parent, or the DEFAULT partition (bound = 'DEFAULT', no FROM/TO)
    -- yields no match → refused. So the DEFAULT safety net can never be dropped here.
    SELECT pg_get_expr(c.relpartbound, c.oid)
      INTO v_bound
      FROM pg_class c
      JOIN pg_inherits i ON i.inhrelid = c.oid
      JOIN pg_class p ON p.oid = i.inhparent
     WHERE p.relname = 'audit_entry' AND c.relname = p_partition;

    IF v_bound IS NULL THEN
        RAISE EXCEPTION 'purge_audit refused: % is not a monthly partition of audit_entry', p_partition;
    END IF;

    -- The DEFAULT partition's bound is 'DEFAULT' (no FROM/TO) → unparseable → refused. The safety
    -- net is never dropped through this path.
    v_from := (regexp_match(v_bound, 'FROM \(''([^'']+)''\)'))[1]::timestamptz;
    v_to   := (regexp_match(v_bound, 'TO \(''([^'']+)''\)'))[1]::timestamptz;
    IF v_from IS NULL OR v_to IS NULL THEN
        RAISE EXCEPTION 'purge_audit refused: % is the DEFAULT partition or has no range bound (%)',
            p_partition, v_bound;
    END IF;

    -- (b) The WHOLE partition must be older than the cutoff.
    IF v_to > p_cutoff THEN
        RAISE EXCEPTION 'purge_audit refused: partition % [%..%) is not entirely older than cutoff %',
            p_partition, v_from, v_to, p_cutoff;
    END IF;

    -- (c) No ACTIVE legal hold may overlap the partition's time range (half-open overlap).
    -- Lock legal_hold in SHARE mode first so a hold committed concurrently — between this check
    -- and the DROP below — cannot slip a protected partition through: SHARE conflicts with the
    -- INSERT/UPDATE a set/release takes, so the check + DROP are atomic wrt hold changes for the
    -- brief life of this call (closes the TOCTOU the app-side pre-filter cannot).
    LOCK TABLE legal_hold IN SHARE MODE;
    IF EXISTS (
        SELECT 1 FROM legal_hold
         WHERE released_at IS NULL
           AND from_ts < v_to
           AND to_ts   > v_from
    ) THEN
        RAISE EXCEPTION 'purge_audit refused: an active legal hold overlaps partition % [%..%)',
            p_partition, v_from, v_to;
    END IF;

    -- All checks passed — drop the partition (owner privilege via SECURITY DEFINER).
    EXECUTE format('DROP TABLE %I', p_partition);

    dropped_partition := p_partition;
    range_from := v_from;
    range_to := v_to;
    RETURN NEXT;
END $$;

-- Lock the function down: EXECUTE is granted explicitly in audit-roles.sql (to inspector_app,
-- the BFF's runtime role). Revoke the PUBLIC default so no other role can invoke the DROP path.
REVOKE ALL ON FUNCTION purge_audit(text, timestamptz) FROM PUBLIC;
