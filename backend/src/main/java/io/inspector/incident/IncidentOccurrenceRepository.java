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
                        (incident_id, sampled_at, total, dead_letter_count, retrying_count, truncated)
                    VALUES (:incidentId, :sampledAt, :total, :deadLetterCount, :retryingCount, :truncated)
                    ON CONFLICT (incident_id, sampled_at)
                    DO UPDATE SET total = EXCLUDED.total,
                                  dead_letter_count = EXCLUDED.dead_letter_count,
                                  retrying_count = EXCLUDED.retrying_count,
                                  truncated = EXCLUDED.truncated
                    """, nativeQuery = true)
    int upsert(
            @Param("incidentId") long incidentId,
            @Param("sampledAt") Instant sampledAt,
            @Param("total") long total,
            @Param("deadLetterCount") long deadLetterCount,
            @Param("retryingCount") long retryingCount,
            @Param("truncated") boolean truncated);

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
     */
    @Query(value = """
                    SELECT d.incident_id, COALESCE(SUM(GREATEST(d.delta, 0)), 0)
                    FROM (
                        SELECT incident_id,
                               total - LAG(total) OVER w                AS delta,
                               truncated                                AS truncated,
                               LAG(truncated) OVER w                    AS prev_truncated
                        FROM incident_occurrence
                        WHERE sampled_at >= :since
                        WINDOW w AS (PARTITION BY incident_id ORDER BY sampled_at)
                    ) d
                    WHERE d.delta IS NOT NULL
                      AND d.truncated = false
                      AND d.prev_truncated = false
                    GROUP BY d.incident_id
                    """, nativeQuery = true)
    List<Object[]> arrivalsSince(@Param("since") Instant since);
}
