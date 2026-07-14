package io.inspector.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only access to the audit golden master. INSERT + outcome close-out + reads —
 * the V1__init.sql guard trigger enforces append-only at the DB, whatever Java does.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    /** Per-instance Audit tab: what did the last shift already try (SPEC §9). */
    List<AuditEntry> findByEngineIdAndInstanceIdOrderByTsDesc(String engineId, String instanceId, Pageable page);

    /** Chain head for the tamper-evidence hash (insert path is single-writer serialized). */
    Optional<AuditEntry> findTopByOrderBySeqDesc();

    /**
     * Global operations log (SPEC §9): null string filters mean "any"; {@code since} is
     * mandatory (pass {@code Instant.EPOCH} for "ever") — a nullable timestamptz
     * parameter is untypable for Postgres ("could not determine data type").
     */
    @Query("""
            select a from AuditEntry a
            where (:actor is null or a.actor = :actor)
              and (:action is null or a.action = :action)
              and (:engineId is null or a.engineId = :engineId)
              and (:ticketId is null or a.ticketId = :ticketId)
              and a.ts >= :since
            order by a.ts desc
            """)
    List<AuditEntry> findLog(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("engineId") String engineId,
            @Param("ticketId") String ticketId,
            @Param("since") Instant since,
            Pageable page);

    /** The startup/periodic reconciler's sweep set (SPEC §6): stale PENDING → unknown. */
    List<AuditEntry> findByOutcomeAndTsBefore(AuditOutcome outcome, Instant cutoff);

    /**
     * The #106 S0 remediation-demand mining scan (R-GOV-08): every instance-scoped audit
     * row (definition-scoped verbs and BFF-store-only config events, both null instanceId,
     * are excluded by construction), ordered so one instance's full history is always
     * contiguous within and across pages — {@link io.inspector.audit.RemediationDemandAnalysisService}
     * accumulates per-instance verb chains incrementally as it scans.
     */
    @Query("""
            select a from AuditEntry a
            where a.instanceId is not null
            order by a.engineId asc, a.instanceId asc, a.ts asc, a.id asc
            """)
    List<AuditEntry> findInstanceScopedForSequenceMining(Pageable page);
}
