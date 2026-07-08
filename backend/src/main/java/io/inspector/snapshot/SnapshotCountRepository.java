package io.inspector.snapshot;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The snapshot time-series store (V5__triage_snapshot.sql). Writes are the idempotent
 * {@code ON CONFLICT} upsert below — a poll is not a mutation, so there is no audit rail and no
 * {@code EntityManager.persist}; reads serve the trend UI (slice 2, R-BAU-08 sparklines).
 */
public interface SnapshotCountRepository extends JpaRepository<SnapshotCount, Long> {

    /**
     * Idempotent write of one lane's count at one bucket. A re-fire in the same bucket (scheduler
     * overlap / restart) overwrites the same {@code (engine, lane, sampled_at)} row rather than
     * inserting a duplicate. {@code lane} is passed as its {@code name()} to match the
     * {@code @Enumerated(STRING)} storage and the {@code triage_snapshot_lane_valid} CHECK.
     */
    @Modifying
    @Transactional
    @Query(value = """
                    INSERT INTO triage_snapshot (engine_id, lane, count, sampled_at)
                    VALUES (:engineId, :lane, :count, :sampledAt)
                    ON CONFLICT (engine_id, lane, sampled_at)
                    DO UPDATE SET count = EXCLUDED.count
                    """, nativeQuery = true)
    int upsert(
            @Param("engineId") String engineId,
            @Param("lane") String lane,
            @Param("count") long count,
            @Param("sampledAt") Instant sampledAt);

    /** One engine+lane trend window, newest first (the slice-2 read path). */
    List<SnapshotCount> findByEngineIdAndLaneAndSampledAtGreaterThanEqualOrderBySampledAtDesc(
            String engineId, SnapshotLane lane, Instant since);

    /** All lanes an engine wrote at one bucket — used to assert a sample landed whole. */
    List<SnapshotCount> findByEngineIdAndSampledAtOrderByLane(String engineId, Instant sampledAt);
}
