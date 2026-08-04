package io.inspector.incident;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The incident time-series store (V18, INCIDENT-LEDGER.md §3.3). Writes are the idempotent
 * {@code ON CONFLICT} upsert below, keyed on the bucket-floored business PK — mirroring
 * {@code SnapshotCountRepository}: a re-fire in the same bucket (scheduler overlap / restart)
 * overwrites the same {@code (incident_id, sampled_at)} row rather than inserting a duplicate.
 * A poll is not a mutation: no audit rail.
 */
public interface IncidentOccurrenceRepository extends JpaRepository<IncidentOccurrence, IncidentOccurrenceId> {

    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO incident_occurrence
                        (incident_id, sampled_at, total, dead_letter_count, retrying_count, truncated,
                         cycle_complete)
                    VALUES (:incidentId, :sampledAt, :total, :deadLetterCount, :retryingCount, :truncated,
                            :cycleComplete)
                    ON CONFLICT (incident_id, sampled_at)
                    DO UPDATE SET total = EXCLUDED.total,
                                  dead_letter_count = EXCLUDED.dead_letter_count,
                                  retrying_count = EXCLUDED.retrying_count,
                                  truncated = EXCLUDED.truncated,
                                  cycle_complete = EXCLUDED.cycle_complete
                    """, nativeQuery = true)
    int upsert(
            @Param("incidentId") long incidentId,
            @Param("sampledAt") Instant sampledAt,
            @Param("total") long total,
            @Param("deadLetterCount") long deadLetterCount,
            @Param("retryingCount") long retryingCount,
            @Param("truncated") boolean truncated,
            @Param("cycleComplete") boolean cycleComplete);

    /** One incident's series ascending — the S2 windowed read path (and the IT assertions). */
    List<IncidentOccurrence> findByIdIncidentIdOrderByIdSampledAtAsc(long incidentId);

    /** The S2 detail read: one incident's series inside a clamped window, ascending. */
    List<IncidentOccurrence> findByIdIncidentIdAndIdSampledAtGreaterThanEqualOrderByIdSampledAtAsc(
            long incidentId, Instant since);

    /**
     * The attention score's F factor (ALARM-COST-MODEL.md §4.1/§6, #353): per incident, the sum
     * of POSITIVE {@code total} deltas over a trailing window — "how many new members arrived",
     * not "how big is it".
     *
     * <p>Deliberately a DB-side AGGREGATE, one row per incident: the alternative (fetching the
     * minute-bucket rows and differencing them in Java) is ~40k rows per class per 28-day window.
     * This is the BFF's OWN Postgres, never an engine — the Stage 0 count-only/{@code size=1}
     * iron rule is about ENGINE queries and is untouched by design (§9: the score is a pure
     * DB-side join over the aggregation's existing output, zero new engine calls).
     *
     * <p><b>Truncation honesty (R-SEM-12, §6):</b> a truncated sample is a FLOOR, not a level, so
     * a delta touching a truncated point is discarded outright rather than being allowed to
     * manufacture a phantom arrival when the scan cap stops biting. Returns {@code Object[]{
     * incidentId, arrivals}}; incidents with no qualifying delta are simply absent (⇒ 0).
     *
     * <p><b>Blind-cycle honesty (#302, V21):</b> exactly the same rule, second marker. A row
     * written while an engine was unreachable is missing that engine's members, so a
     * multi-engine class's {@code total} DROPS and RECOVERS with the outage — and
     * {@code SUM(GREATEST(δ,0))} would bank the whole recovery as arrivals (1000→100→1000 =
     * +900 phantom arrivals from a two-minute blip, enough to rocket the class to the top of
     * the attention order for the full window and to compound on every flap). A delta is
     * therefore counted only when BOTH its endpoints were observed completely; the outage's
     * recovery edge is discarded, not banked.
     */
    @Query(value = """
                    SELECT d.incident_id, COALESCE(SUM(GREATEST(d.delta, 0)), 0)
                    FROM (
                        SELECT incident_id,
                               total - LAG(total) OVER w                AS delta,
                               truncated                                AS truncated,
                               LAG(truncated) OVER w                    AS prev_truncated,
                               cycle_complete                           AS cycle_complete,
                               LAG(cycle_complete) OVER w               AS prev_cycle_complete
                        FROM incident_occurrence
                        WHERE sampled_at >= :since
                        WINDOW w AS (PARTITION BY incident_id ORDER BY sampled_at)
                    ) d
                    WHERE d.delta IS NOT NULL
                      AND d.truncated = false
                      AND d.prev_truncated = false
                      AND d.cycle_complete
                      AND d.prev_cycle_complete
                    GROUP BY d.incident_id
                    """, nativeQuery = true)
    List<Object[]> arrivalsSince(@Param("since") Instant since);

    /**
     * The RETRYING-lane's spell substrate (RETRYING-RISK-LANE.md §3.1, #351) — one class's
     * SPELL-SHAPE-RELEVANT occurrence rows inside the window, newest first, hard-capped.
     *
     * <p><b>Why not the plain windowed finder</b> (the defect this replaces): {@code
     * inspector.selfheal.window-days} is 90 and the sampler beat is 60s, so the naive
     * "fetch the window and difference it in Java" read is ~129 600 rows PER CLASS PER CALL —
     * on the default-ON {@code GET /api/incidents} path, once per listed incident (list cap
     * 500) and again for every class on every 60s dwell tick. That is the same unbounded-fetch
     * class issue #308 hardened the ledger list against, and the sibling {@link #arrivalsSince}
     * rejects by construction. So the row REDUCTION is pushed DB-side here too.
     *
     * <p><b>What the extractor actually needs</b>, and therefore all this returns: every sample
     * INSIDE a spell ({@code retrying_count > 0}), the closing zero-count sample that ends it
     * ({@code prev_retrying > 0}), and the +1-bucket outcome look-ahead past that zero
     * ({@code prev2_retrying > 0}). Everything else is a quiet bucket the extractor skips
     * without reading. Dropping those quiet rows is shape-preserving: spell boundaries, the
     * internal gap check ({@code start..zeroIdx}) and the look-ahead tolerance all live inside
     * the retained span, and a dropped quiet row can never sit between two retained ones that
     * the extractor compares. In the measured pilot (RETRYING-RISK-LANE §8: retrying spells are
     * rare and short) this collapses the per-class read to tens of rows.
     *
     * <p><b>The cap is a floor on honesty, not a silent truncation:</b> rows come back NEWEST
     * first so the cap drops the OLDEST; the caller reverses to chronological order, and a spell
     * left straddling the cut starts at index 0 with {@code retrying_count > 0}, which
     * {@code RetrySpellExtractor} marks LEFT-CENSORED and excludes from {@code n} (its
     * {@code dlqAtStart} would be mid-spell). A cap can therefore only shrink an honest {@code
     * n}; it can never manufacture an outcome.
     */
    @Query(value = """
                    SELECT o.incident_id, o.sampled_at, o.total, o.dead_letter_count,
                           o.retrying_count, o.truncated, o.cycle_complete
                    FROM (
                        SELECT incident_id, sampled_at, total, dead_letter_count, retrying_count,
                               truncated, cycle_complete,
                               LAG(retrying_count) OVER w    AS prev_retrying,
                               LAG(retrying_count, 2) OVER w AS prev2_retrying
                        FROM incident_occurrence
                        WHERE incident_id = :incidentId AND sampled_at >= :since
                        WINDOW w AS (ORDER BY sampled_at)
                    ) o
                    WHERE o.retrying_count > 0
                       OR COALESCE(o.prev_retrying, 0) > 0
                       OR COALESCE(o.prev2_retrying, 0) > 0
                    ORDER BY o.sampled_at DESC
                    LIMIT :limit
                    """, nativeQuery = true)
    List<IncidentOccurrence> findSpellShapeRowsDescending(
            @Param("incidentId") long incidentId, @Param("since") Instant since, @Param("limit") int limit);
}
