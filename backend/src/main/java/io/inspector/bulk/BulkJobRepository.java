package io.inspector.bulk;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BulkJobRepository extends JpaRepository<BulkJob, UUID> {

    List<BulkJob> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    List<BulkJob> findByStateIn(List<BulkJob.State> states);
}
