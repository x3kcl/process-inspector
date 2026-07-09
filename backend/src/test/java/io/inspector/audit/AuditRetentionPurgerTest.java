package io.inspector.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.config.AuditProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Rung 1 for the retention orchestrator: it checkpoints the chain and audits BEFORE it calls
 * {@code purge_audit} (fail-closed ordering), and it drops nothing if the checkpoint cannot be
 * recorded. The DB-side age/hold enforcement is {@link AuditRetentionPurgeIT}.
 */
class AuditRetentionPurgerTest {

    // Fixed "now" so the 400-day cutoff lands in 2025 and the 2019 partition is aged out.
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private AuditRetentionPurger purger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        purger = new AuditRetentionPurger(jdbc, audit, new AuditProperties(null, null, null), clock);

        // One aged-out child + the DEFAULT (never a candidate) + a future month (not expired).
        when(jdbc.queryForList(contains("pg_inherits"), eq(String.class), any()))
                .thenReturn(List.of("audit_entry_2019_06", "audit_entry_default", "audit_entry_2099_01"));
        // No legal hold overlaps.
        when(jdbc.queryForObject(contains("legal_hold"), eq(Long.class), any(), any()))
                .thenReturn(0L);
        // Checkpoint reads: last-dropped row, then first-surviving row.
        when(jdbc.query(contains("ORDER BY seq DESC"), any(ResultSetExtractor.class)))
                .thenReturn(new Object[] {100L, "hash-last"});
        when(jdbc.query(contains("seq >"), any(ResultSetExtractor.class), any()))
                .thenReturn(new Object[] {101L, "hash-first"});
        when(jdbc.queryForMap(contains("purge_audit"), any(), any()))
                .thenReturn(Map.of(
                        "dropped_partition",
                        "audit_entry_2019_06",
                        "range_from",
                        "2019-06-01",
                        "range_to",
                        "2019-07-01"));
    }

    @Test
    void auditsAStartedCheckpointAndTerminalEventAroundTheDrop() {
        purger.purge();

        // started → checkpoint(with the chain boundary) → terminal, all tagged audit-retention-purge.
        verify(audit)
                .recordConfigEvent(
                        eq("audit-retention-purge"),
                        eq("system"),
                        eq(true),
                        argThat(p -> "started".equals(p.get("phase"))));
        verify(audit)
                .recordConfigEvent(
                        eq("audit-retention-purge"),
                        eq("system"),
                        eq(true),
                        argThat(p -> "checkpoint".equals(p.get("phase"))
                                && "audit_entry_2019_06".equals(p.get("partition"))
                                && Long.valueOf(100L).equals(p.get("lastDroppedSeq"))
                                && "hash-last".equals(p.get("lastDroppedChainHash"))
                                && Long.valueOf(101L).equals(p.get("firstSurvivingSeq"))
                                && "hash-first".equals(p.get("firstSurvivingChainHash"))));
        verify(audit)
                .recordConfigEvent(
                        eq("audit-retention-purge"),
                        eq("system"),
                        eq(true),
                        argThat(p -> "complete".equals(p.get("phase"))
                                && Integer.valueOf(1).equals(p.get("partitionsDropped"))));
        // Exactly the one aged, un-held partition was dropped (not DEFAULT, not the future month).
        verify(jdbc).queryForMap(contains("purge_audit"), eq("audit_entry_2019_06"), any());
    }

    @Test
    void dropsNothingWhenTheCheckpointCannotBeAudited() {
        // Fail-closed: the checkpoint config event throws → purge_audit must NEVER be called.
        when(audit.recordConfigEvent(
                        anyString(), anyString(), anyBoolean(), argThat(p -> "checkpoint".equals(p.get("phase")))))
                .thenThrow(new AuditUnavailableException(new RuntimeException("audit db down")));

        purger.purge(); // must not throw

        verify(jdbc, never()).queryForMap(contains("purge_audit"), any(), any());
    }

    @Test
    void doesNothingWhenNoPartitionIsAgedOut() {
        when(jdbc.queryForList(contains("pg_inherits"), eq(String.class), any()))
                .thenReturn(List.of("audit_entry_default", "audit_entry_2099_01"));

        purger.purge();

        verify(audit, never()).recordConfigEvent(anyString(), anyString(), anyBoolean(), any());
        verify(jdbc, never()).queryForMap(contains("purge_audit"), any(), any());
    }
}
