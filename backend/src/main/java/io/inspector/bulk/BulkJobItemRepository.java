package io.inspector.bulk;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobItemRepository extends JpaRepository<BulkJobItem, BulkJobItem.Key> {

    List<BulkJobItem> findByJobIdOrderByOrdinal(UUID jobId);

    /**
     * S2 (R-SAFE-17) scoped variant of {@link #findByJobIdOrderByOrdinal}: pushes the
     * engine-scope predicate into the query rather than filtering the full item list in memory
     * afterward — a bulk item carries the concrete {@code engineId:instanceId} target, exactly
     * the leak the caller's readable-engine set must never expose across engines. {@code
     * engineIds} must never be an empty collection (see {@code BulkJobService}'s empty-set
     * short-circuit — it never reaches this query).
     */
    List<BulkJobItem> findByJobIdAndEngineIdInOrderByOrdinal(UUID jobId, Collection<String> engineIds);

    /** Batch read for cross-job tallies (the incident related-jobs join) — ONE query, never per-job. */
    List<BulkJobItem> findByJobIdIn(Collection<UUID> jobIds);

    /**
     * S2 (R-SAFE-17) scoped variant of {@link #findByJobIdIn} for the incident related-jobs batch
     * tally (issue #329): same "push the predicate into the query, never filter an already-fetched
     * batch in memory" rule as {@link #findByJobIdAndEngineIdInOrderByOrdinal}. {@code engineIds}
     * must never be an empty collection (the caller short-circuits before reaching this query).
     */
    List<BulkJobItem> findByJobIdInAndEngineIdIn(Collection<UUID> jobIds, Collection<String> engineIds);

    List<BulkJobItem> findByJobIdAndStateIn(UUID jobId, List<BulkJobItem.State> states);
}
