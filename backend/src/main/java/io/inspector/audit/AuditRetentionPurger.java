package io.inspector.audit;

import io.inspector.audit.AuditPartitions.Bounds;
import io.inspector.config.AuditProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The audit retention purge orchestrator (M4-CLOSEOUT §A2 / §5 / S5b). Drops monthly {@code
 * audit_entry} partitions older than {@code inspector.audit.retention-days} — but is the
 * <b>single writer</b> of the tamper-evidence chain and never holds raw {@code DROP}. It:
 *
 * <ol>
 *   <li>enumerates aged-out, un-held monthly partitions (oldest first);</li>
 *   <li>writes a <b>started</b> config event;</li>
 *   <li>for each partition, writes a per-partition <b>checkpoint</b> config event — the {@code
 *       chain_hash}+{@code seq} of the last dropped row and the first surviving row, so the chain
 *       stays cryptographically stitched across the gap — <b>before</b> calling {@code
 *       purge_audit()} (fail-closed ordering: if the audit write fails, the DROP does not run);</li>
 *   <li>writes a <b>terminal</b> event with the actual {@code partitionsDropped}.</li>
 * </ol>
 *
 * The DROP is delegated to the {@code SECURITY DEFINER purge_audit(partition, cutoff)} function,
 * which independently re-enforces the retention floor, whole-partition age, and legal holds in the
 * DB — so a bug here can never purge recent or held audit. Runs as a BFF {@code @Scheduled} job so a
 * <i>stopped</i> purge is visible (dead-man alert on a missing {@code audit-retention-purge} event),
 * never a silent external cron. Gated off in docker-free test profiles (needs a real {@code
 * JdbcTemplate}).
 */
@Component
@ConditionalOnProperty(name = "inspector.audit.retention-purge.enabled", havingValue = "true", matchIfMissing = true)
public class AuditRetentionPurger {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionPurger.class);
    private static final String PARENT = "audit_entry";
    private static final String ACTION = "audit-retention-purge";
    /** Automated scheduled job — not a human actor (legal-hold set/release carry the human). */
    private static final String ACTOR = "system";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AuditProperties properties;
    private final Clock clock;

    public AuditRetentionPurger(JdbcTemplate jdbc, AuditService audit, AuditProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Daily, first run 1h after startup (never fires inside a short test run). */
    @Scheduled(fixedDelayString = "P1D", initialDelayString = "PT1H")
    public void purge() {
        int retentionDays = properties.retentionDaysOrDefault();
        LocalDate cutoffDate =
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(retentionDays);
        Instant cutoff = cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        List<String> candidates;
        try {
            candidates = expiredUnheldPartitionsOldestFirst(cutoffDate);
        } catch (RuntimeException e) {
            log.warn("audit retention purge skipped — store unavailable: {}", e.toString());
            return;
        }
        if (candidates.isEmpty()) {
            return; // nothing aged out — the common case
        }

        try {
            audit.recordConfigEvent(ACTION, ACTOR, true, startedPayload(cutoff, retentionDays, candidates));

            int dropped = 0;
            for (String partition : candidates) {
                // Checkpoint the chain BEFORE the DROP. If this audit write throws, we abort with
                // nothing further dropped — the already-dropped partitions were each pre-audited.
                audit.recordConfigEvent(ACTION, ACTOR, true, checkpointPayload(partition, cutoff));
                try {
                    Map<String, Object> result =
                            jdbc.queryForMap("SELECT * FROM purge_audit(?, ?)", partition, Timestamp.from(cutoff));
                    dropped++;
                    log.info(
                            "audit retention: dropped partition {} (range {}..{}, cutoff {})",
                            partition,
                            result.get("range_from"),
                            result.get("range_to"),
                            cutoff);
                } catch (RuntimeException dropEx) {
                    // Pre-filtered as un-held + aged, so a refusal here is a race or DB-floor catch —
                    // record it and stop; the un-dropped partition stays, safe.
                    audit.recordConfigEvent(ACTION, ACTOR, false, dropFailedPayload(partition, dropEx));
                    log.error(
                            "AUDIT_RETENTION_PURGE_REFUSED partition={} — purge_audit refused after checkpoint: {}",
                            partition,
                            dropEx.toString());
                    break;
                }
            }

            audit.recordConfigEvent(ACTION, ACTOR, true, completePayload(cutoff, dropped));
        } catch (AuditUnavailableException e) {
            // Fail-closed ordering: a config event could not be written → the DROP did not run.
            log.error(
                    "AUDIT_RETENTION_PURGE_ABORTED — config event unrecordable; purge NOT run (fail-closed): {}",
                    e.toString());
        }
    }

    /** Aged-out monthly partitions with no overlapping active legal hold, oldest month first. */
    private List<String> expiredUnheldPartitionsOldestFirst(LocalDate cutoffDate) {
        List<String> children = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                """, String.class, PARENT);
        return children.stream()
                .filter(name -> AuditPartitions.isExpired(name, cutoffDate))
                .filter(name -> !isHeld(name))
                .sorted() // audit_entry_YYYY_MM sorts chronologically
                .toList();
    }

    /** True if any ACTIVE legal hold's window overlaps this partition's month range (half-open). */
    private boolean isHeld(String partition) {
        Bounds b = AuditPartitions.boundsFor(AuditPartitions.monthOf(partition).orElseThrow());
        Long overlaps = jdbc.queryForObject(
                "SELECT count(*) FROM legal_hold WHERE released_at IS NULL"
                        + " AND from_ts < ?::timestamptz AND to_ts > ?::timestamptz",
                Long.class,
                b.toExclusive(),
                b.fromInclusive());
        return overlaps != null && overlaps > 0;
    }

    private Map<String, Object> startedPayload(Instant cutoff, int retentionDays, List<String> candidates) {
        Map<String, Object> p = base(cutoff);
        p.put("phase", "started");
        p.put("retentionDays", retentionDays);
        p.put("candidatePartitions", candidates);
        return p;
    }

    /** The chain-boundary checkpoint for a partition about to be dropped. */
    private Map<String, Object> checkpointPayload(String partition, Instant cutoff) {
        Map<String, Object> p = base(cutoff);
        p.put("phase", "checkpoint");
        p.put("partition", partition);

        // {seq, chain_hash} of the last row in the partition being dropped (null if it is empty).
        // chain_hash may be null, so extract into an array — Map.of rejects null values.
        Object[] lastDropped = jdbc.query(
                "SELECT seq, chain_hash FROM " + partition + " ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? new Object[] {rs.getLong("seq"), rs.getString("chain_hash")} : null);

        if (lastDropped == null) {
            p.put("lastDroppedSeq", null);
            p.put("lastDroppedChainHash", null);
            p.put("firstSurvivingSeq", null);
            p.put("firstSurvivingChainHash", null);
        } else {
            Long lastSeq = (Long) lastDropped[0];
            p.put("lastDroppedSeq", lastSeq);
            p.put("lastDroppedChainHash", lastDropped[1]);
            // The first row that SURVIVES the drop — its hash still links back to the dropped row's,
            // so the chain stays verifiable across the gap.
            Object[] firstSurviving = jdbc.query(
                    "SELECT seq, chain_hash FROM " + PARENT + " WHERE seq > ? ORDER BY seq ASC LIMIT 1",
                    rs -> rs.next() ? new Object[] {rs.getLong("seq"), rs.getString("chain_hash")} : null,
                    lastSeq);
            p.put("firstSurvivingSeq", firstSurviving == null ? null : firstSurviving[0]);
            p.put("firstSurvivingChainHash", firstSurviving == null ? null : firstSurviving[1]);
        }
        return p;
    }

    private Map<String, Object> dropFailedPayload(String partition, RuntimeException e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phase", "drop-failed");
        p.put("partition", partition);
        p.put("error", e.getMessage());
        return p;
    }

    private Map<String, Object> completePayload(Instant cutoff, int dropped) {
        Map<String, Object> p = base(cutoff);
        p.put("phase", "complete");
        p.put("partitionsDropped", dropped);
        return p;
    }

    private static Map<String, Object> base(Instant cutoff) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cutoff", cutoff.toString());
        return p;
    }
}
