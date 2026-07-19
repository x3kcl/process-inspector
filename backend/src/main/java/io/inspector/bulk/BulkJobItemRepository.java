package io.inspector.bulk;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobItemRepository extends JpaRepository<BulkJobItem, BulkJobItem.Key> {

    List<BulkJobItem> findByJobIdOrderByOrdinal(UUID jobId);

    /** Batch read for cross-job tallies (the incident related-jobs join) — ONE query, never per-job. */
    List<BulkJobItem> findByJobIdIn(Collection<UUID> jobIds);

    List<BulkJobItem> findByJobIdAndStateIn(UUID jobId, List<BulkJobItem.State> states);
}
