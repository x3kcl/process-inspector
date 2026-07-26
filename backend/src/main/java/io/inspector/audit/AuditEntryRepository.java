package io.inspector.audit;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * S2 (R-SAFE-17) scoped variant of {@link #findLog}, used ONLY when read-scope enforcement
     * is on (the caller resolved a non-null {@code readableEngineIds} from {@code ReadScopeGate}).
     * The engine-scope predicate is pushed into THIS query — never filtered in memory after
     * fetching — so the CSV export's page-boundary math ({@code AuditController}) stays correct.
     * {@code readableEngineIds} must never be an empty collection: JPQL's {@code IN ()} on an
     * empty parameter is unreliable across providers, so an empty readable set is the caller's
     * job to substitute with an impossible sentinel id before calling this. Config-event rows
     * ({@code engine_id = '_inspector'}, {@link AuditService#CONFIG_ENGINE_ID}, R-AUD-10) carry no
     * engine scope of their own, so they fall outside every per-engine readable set by
     * construction — visible here ONLY when {@code adminAnywhere} is true (fleet ADMIN), the same
     * safe default as the config-event PAYLOAD gate in {@code AuditController#payloadVisible}.
     */
    @Query("""
            select a from AuditEntry a
            where (:actor is null or a.actor = :actor)
              and (:action is null or a.action = :action)
              and (:engineId is null or a.engineId = :engineId)
              and (:ticketId is null or a.ticketId = :ticketId)
              and a.ts >= :since
              and (a.engineId in :readableEngineIds or (a.engineId = '_inspector' and :adminAnywhere = true))
            order by a.ts desc
            """)
    List<AuditEntry> findLogScoped(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("engineId") String engineId,
            @Param("ticketId") String ticketId,
            @Param("since") Instant since,
            @Param("readableEngineIds") Collection<String> readableEngineIds,
            @Param("adminAnywhere") boolean adminAnywhere,
            Pageable page);

    /** The startup/periodic reconciler's sweep set (SPEC §6): stale PENDING → unknown. */
    List<AuditEntry> findByOutcomeAndTsBefore(AuditOutcome outcome, Instant cutoff);

    /**
     * A bulk job's still-open submit-time envelope row (#303): correlates on the payload's
     * {@code bulkJobId} — the SAME join mechanics {@link #findRecentErrorClassBulkJobIds} and
     * {@code RelatedBulkJobsService} already rely on, since the entity's own {@code bulk_job_id}
     * column is never populated at write time. Used by {@code BulkJobService}'s stale-RUNNING and
     * crash-restart reconciliation sweeps to close the envelope with the real per-item tally
     * instead of leaving it for {@code AuditPendingSweeper}'s generic blanket {@code unknown}.
     * {@code LIMIT 1} because exactly one PENDING envelope can exist per bulk job (opened once at
     * submit, closed once at finish); newest-first is defensive in case more than one somehow
     * matches.
     */
    @Query(value = """
            select * from audit_entry
            where outcome = 'PENDING'
              and action like 'bulk:%'
              and payload ->> 'bulkJobId' = :bulkJobId
            order by ts desc
            limit 1
            """, nativeQuery = true)
    Optional<AuditEntry> findPendingBulkEnvelope(@Param("bulkJobId") String bulkJobId);

    /**
     * The incident detail's related-bulk-jobs join (R-BAU-10 S5, INCIDENT-LEDGER.md §6): the
     * {@code bulk_job} row itself does NOT persist the error-class signature — its V4 scope
     * descriptor is the human label ("defKey vN · error class"), while the signature lives in
     * the submit's ENVELOPE audit row ({@code BulkErrorClassService} → {@code BulkJobService}:
     * {@code payload.errorClass.signatureHash/algoVersion} + {@code payload.bulkJobId}) — so the
     * audit golden master IS the join table here, read-only. Only error-class envelopes carry
     * the {@code errorClass} key, which makes the jsonb path filter the door discriminator; the
     * {@code bulk:} action prefix narrows the scan cheaply first. {@code algoVersion} is matched
     * as jsonb text (the {@code ->>} projection of the stored number). Newest submit first,
     * bounded — the planner walks {@code idx_audit_ts} backwards and stops at the limit.
     */
    @Query(value = """
            select a.payload ->> 'bulkJobId'
            from audit_entry a
            where a.action like 'bulk:%'
              and a.payload -> 'errorClass' ->> 'signatureHash' = :signatureHash
              and a.payload -> 'errorClass' ->> 'algoVersion' = :algoVersion
              and a.payload ->> 'bulkJobId' is not null
            order by a.ts desc
            limit :limit
            """, nativeQuery = true)
    List<String> findRecentErrorClassBulkJobIds(
            @Param("signatureHash") String signatureHash,
            @Param("algoVersion") String algoVersion,
            @Param("limit") int limit);

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

    /**
     * The {@link AuditChainVerifier}'s keyset-paginated forward walk (issue #304): {@code seq > x
     * ORDER BY seq ASC LIMIT n} rather than an OFFSET page, so each batch costs the same
     * regardless of how far into the chain the walk already is — the {@code V20} index makes this
     * an index scan, not a full-table sort. Bounded batch size is the caller's job (a
     * {@link Pageable} of size N), never a full in-memory load.
     */
    List<AuditEntry> findBySeqGreaterThanOrderBySeqAsc(long seq, Pageable page);

    /**
     * The retention-purge chain-boundary checkpoint (issue #304): {@link AuditRetentionPurger}
     * writes a {@code phase: checkpoint} config event immediately BEFORE dropping a partition,
     * recording the first row that will SURVIVE the drop — its seq and its own (unaffected)
     * {@code chain_hash}. The verifier looks this up whenever it finds a seq gap, to tell an
     * AUDITED, expected partition boundary apart from an unexplained hole. Newest first (a seq can
     * in principle be checkpointed more than once only if the same boundary were purged twice,
     * which cannot happen — rows once dropped are gone — so this is a defensive tie-break, not a
     * load-bearing one).
     */
    @Query(value = """
            select a.payload ->> 'firstSurvivingChainHash'
            from audit_entry a
            where a.engine_id = '_inspector'
              and a.action = 'audit-retention-purge'
              and a.payload ->> 'phase' = 'checkpoint'
              and (a.payload ->> 'firstSurvivingSeq')::bigint = :seq
            order by a.ts desc
            limit 1
            """, nativeQuery = true)
    Optional<String> findRetentionCheckpointHashForFirstSurvivingSeq(@Param("seq") long seq);
}
