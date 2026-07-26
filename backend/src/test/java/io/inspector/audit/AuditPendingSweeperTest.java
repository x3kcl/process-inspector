package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.bulk.BulkJob;
import io.inspector.bulk.BulkJobRepository;
import io.inspector.config.InspectorProperties;
import io.inspector.support.TestEngines;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 for the R-SEM-18 reconciler: the PENDING → unknown sweep against a mocked
 * repository, and (#303) its exclusion of a bulk envelope whose job is still genuinely
 * live — the headline fix for the "healthy long bulk run cries wolf mid-run" defect.
 */
class AuditPendingSweeperTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final String ENGINE = "engine-a";

    private final AuditEntryRepository repository = mock(AuditEntryRepository.class);
    private final BulkJobRepository bulkJobs = mock(BulkJobRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AuditPendingSweeper sweeper;

    @BeforeEach
    void setUp() {
        InspectorProperties properties = new InspectorProperties(
                null, null, null, null, null, null, List.of(TestEngines.engine(ENGINE, "http://localhost:1")));
        sweeper = new AuditPendingSweeper(repository, bulkJobs, properties, Clock.fixed(NOW, ZoneOffset.UTC), mapper);
    }

    private static AuditEntry pendingEntry(String action, String payloadJson) {
        return new AuditEntry(
                UUID.randomUUID(),
                "corr",
                "resp",
                NOW.minusSeconds(3600), // well past any write budget + grace
                ENGINE,
                null,
                "pi-1",
                action,
                "ops-4711 incident",
                null,
                payloadJson,
                false);
    }

    private static String bulkPayload(UUID bulkJobId) {
        return "{\"schema\":\"bulk/suspend/v1\",\"bulkJobId\":\"" + bulkJobId + "\",\"items\":2}";
    }

    /* ---------------- baseline: a stale non-bulk row is swept exactly as before ---------------- */

    @Test
    void sweepsAStaleNonBulkPendingRowToUnknown() {
        AuditEntry entry = pendingEntry("suspend", "{\"processInstanceId\":\"pi-1\"}");
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(entry));

        sweeper.sweep();

        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.unknown);
        verify(repository).saveAndFlush(entry);
        // A non-bulk row never triggers the bulk-liveness lookup at all.
        verify(bulkJobs, never()).findByStateIn(any());
    }

    /* ---------------- #303 headline fix: a live bulk envelope is spared ---------------- */

    @Test
    void spareASBulkEnvelopeWhoseJobIsStillRunning() {
        UUID jobId = UUID.randomUUID();
        AuditEntry envelope = pendingEntry("bulk:suspend", bulkPayload(jobId));
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(envelope));
        BulkJob runningJob = new BulkJob(jobId, "resp", NOW.minusSeconds(4000), "suspend", null, null, 280, null);
        runningJob.markRunning();
        when(bulkJobs.findByStateIn(any())).thenReturn(List.of(runningJob));

        sweeper.sweep();

        // Still PENDING — a healthy long-running job is never stamped unknown mid-run.
        assertThat(envelope.getOutcome()).isEqualTo(AuditOutcome.PENDING);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void spareASBulkEnvelopeWhoseJobIsStillPending() {
        UUID jobId = UUID.randomUUID();
        AuditEntry envelope = pendingEntry("bulk:suspend", bulkPayload(jobId));
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(envelope));
        BulkJob pendingJob = new BulkJob(jobId, "resp", NOW.minusSeconds(3700), "suspend", null, null, 5, null);
        when(bulkJobs.findByStateIn(any())).thenReturn(List.of(pendingJob)); // never even reached run() yet

        sweeper.sweep();

        assertThat(envelope.getOutcome()).isEqualTo(AuditOutcome.PENDING);
        verify(repository, never()).saveAndFlush(any());
    }

    /** Crash recovery still works: once the job itself is no longer live, the envelope sweeps normally. */
    @Test
    void aBulkEnvelopeIsSweptOnceItsJobIsNoLongerLive() {
        UUID jobId = UUID.randomUUID();
        AuditEntry envelope = pendingEntry("bulk:suspend", bulkPayload(jobId));
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(envelope));
        // The job itself was already reconciled away from RUNNING/PENDING (crash-restart or the
        // stale-RUNNING sweep) — bulkJobs.findByStateIn(LIVE) no longer returns it at all.
        when(bulkJobs.findByStateIn(any())).thenReturn(List.of());

        sweeper.sweep();

        assertThat(envelope.getOutcome()).isEqualTo(AuditOutcome.unknown);
        verify(repository).saveAndFlush(envelope);
    }

    /** A mixed batch: the live bulk envelope is spared, the stale single-target row still sweeps. */
    @Test
    void aMixedBatchSparesOnlyTheLiveBulkEnvelope() {
        UUID jobId = UUID.randomUUID();
        AuditEntry envelope = pendingEntry("bulk:suspend", bulkPayload(jobId));
        AuditEntry singleTarget = pendingEntry("retry-job", "{\"processInstanceId\":\"pi-1\"}");
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(envelope, singleTarget));
        BulkJob runningJob = new BulkJob(jobId, "resp", NOW.minusSeconds(4000), "suspend", null, null, 280, null);
        runningJob.markRunning();
        when(bulkJobs.findByStateIn(any())).thenReturn(List.of(runningJob));

        sweeper.sweep();

        assertThat(envelope.getOutcome()).isEqualTo(AuditOutcome.PENDING);
        assertThat(singleTarget.getOutcome()).isEqualTo(AuditOutcome.unknown);
        verify(repository).saveAndFlush(singleTarget);
        verify(repository, never()).saveAndFlush(envelope);
    }

    /* ---------------- fail-safe store handling ---------------- */

    @Test
    void skipsTheWholeSweepWhenTheAuditStoreItselfIsUnavailable() {
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenThrow(new RuntimeException("db down"));

        sweeper.sweep(); // must not throw

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void fallsBackToSweepingWhenTheBulkJobLivenessCheckItselfIsUnavailable() {
        UUID jobId = UUID.randomUUID();
        AuditEntry envelope = pendingEntry("bulk:suspend", bulkPayload(jobId));
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(envelope));
        when(bulkJobs.findByStateIn(any())).thenThrow(new RuntimeException("bulk store down"));

        sweeper.sweep(); // must not throw

        // Can't tell if the job is live — fails toward the pre-#303 behavior (sweep it); the
        // job's own eventual finish() self-heals the outcome if this guess was wrong.
        assertThat(envelope.getOutcome()).isEqualTo(AuditOutcome.unknown);
    }

    /** A row that never sets the guard trigger's mutable columns is untouched by a failed close. */
    @Test
    void aFailureClosingOneRowDoesNotStopTheRestOfTheSweep() {
        AuditEntry ok = pendingEntry("suspend", "{}");
        AuditEntry broken = mock(AuditEntry.class);
        when(broken.getPayload()).thenReturn(null);
        when(broken.getAction()).thenReturn("suspend");
        when(broken.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new RuntimeException("write failed"))
                .when(broken)
                .close(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        when(repository.findByOutcomeAndTsBefore(eq(AuditOutcome.PENDING), any()))
                .thenReturn(List.of(broken, ok));

        sweeper.sweep();

        assertThat(ok.getOutcome()).isEqualTo(AuditOutcome.unknown);
        verify(repository).saveAndFlush(ok);
    }
}
