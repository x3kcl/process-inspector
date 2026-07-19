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
}
