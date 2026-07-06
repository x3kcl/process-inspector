package io.inspector.bulk;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobItemRepository extends JpaRepository<BulkJobItem, BulkJobItem.Key> {

    List<BulkJobItem> findByJobIdOrderByOrdinal(UUID jobId);

    List<BulkJobItem> findByJobIdAndStateIn(UUID jobId, List<BulkJobItem.State> states);
}
