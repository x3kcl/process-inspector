package io.inspector.bulk;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BulkJobRepository extends JpaRepository<BulkJob, UUID> {

    List<BulkJob> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    /**
     * S2 (R-SAFE-17) scoped variant of {@link #findAllByOrderBySubmittedAtDesc}: a job carries no
     * engine of its own (its items do — a job can span several engines), so "readable" means "has
     * at least one item on an engine the caller may read" — a job touching NO readable engine is
     * omitted entirely, never listed with an empty item set. The predicate is pushed into THIS
     * query (a correlated {@code EXISTS} against {@code bulk_job_item}), never applied by
     * filtering an already-fetched page in memory — the same non-negotiable as the audit log's
     * {@code findLogScoped}. {@code engineIds} must never be an empty collection (see {@code
     * BulkJobService}'s empty-set short-circuit — it never reaches this query).
     */
    @Query("""
            select distinct j from BulkJob j
            where exists (
                select 1 from BulkJobItem i where i.jobId = j.id and i.engineId in :engineIds
            )
            order by j.submittedAt desc
            """)
    List<BulkJob> findRecentByReadableEngineIds(@Param("engineIds") Collection<String> engineIds, Pageable pageable);

    List<BulkJob> findByStateIn(List<BulkJob.State> states);

    /** Backs the {@code bulk_jobs_running} gauge (issue #96, OPERATIONS.md §2). */
    long countByState(BulkJob.State state);
}
