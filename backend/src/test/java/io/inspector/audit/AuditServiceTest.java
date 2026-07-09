package io.inspector.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Rung 1 for the audit write path: fail-closed insert (R-AUD-01), the R-SEM-18 close-out
 * distinction, hash chaining, snippet truncation and secret redaction — all against a
 * mocked repository (our OWN store; the engine-harness iron rule concerns Flowable).
 */
class AuditServiceTest {

    private final AuditEntryRepository repository = mock(AuditEntryRepository.class);
    private final AuditService service = new AuditService(
            repository, new ObjectMapper(), Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void passThroughSave() {
        when(repository.saveAndFlush(any(AuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void beginPendingInsertsPendingRowChainedToPreviousHash() {
        AuditEntry previous = entry("prior");
        previous.setChainHash("prior-hash");
        when(repository.findTopByOrderBySeqDesc()).thenReturn(Optional.of(previous));

        AuditEntry entry = service.beginPending(
                "operator", "engine-a", null, "pi-1", "retry-job", "stuck payment retry", null, Map.of("jobId", "j1"));

        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.PENDING);
        assertThat(entry.getActor()).isEqualTo("operator");
        assertThat(entry.getChainHash()).isNotBlank().isNotEqualTo("prior-hash");
        // determinism: same previous hash + same immutable fields → same chain input shape
        assertThat(entry.getPayload()).contains("jobId");
    }

    @Test
    void beginPendingFailsClosedOnAnyPersistenceFailure() {
        when(repository.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AuditEntry.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() ->
                        service.beginPending("operator", "engine-a", null, "pi-1", "retry-job", null, null, Map.of()))
                .isInstanceOf(AuditUnavailableException.class)
                .hasMessageContaining("NOT sent");
    }

    @Test
    void recordConfigEventInsertsATerminalSentinelRowChainedToPrevious() {
        AuditEntry previous = entry("prior");
        previous.setChainHash("prior-hash");
        when(repository.findTopByOrderBySeqDesc()).thenReturn(Optional.of(previous));

        AuditEntry entry = service.recordConfigEvent(
                "config-scope-mapping-reload", "system", true, Map.of("sha256", "abc", "groupCount", 2));

        // R-AUD-10: sentinel engine, no instance/tenant, single-shot TERMINAL outcome (no PENDING).
        assertThat(entry.getEngineId()).isEqualTo(AuditService.CONFIG_ENGINE_ID);
        assertThat(entry.getActor()).isEqualTo("system");
        assertThat(entry.getInstanceId()).isNull();
        assertThat(entry.getTenantId()).isNull();
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.ok);
        assertThat(entry.getChainHash()).isNotBlank().isNotEqualTo("prior-hash");
        assertThat(entry.getPayload()).contains("sha256");
    }

    @Test
    void recordConfigEventMarksAFailedEventAsFailedNotOk() {
        when(repository.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        AuditEntry entry = service.recordConfigEvent(
                "config-scope-mapping-reload", "system", false, Map.of("errorClass", "ParseException"));
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.failed);
    }

    @Test
    void recordConfigEventThrowsAuditUnavailableOnPersistenceFailure() {
        when(repository.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AuditEntry.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        assertThatThrownBy(() -> service.recordConfigEvent("audit-retention-purge", "system", true, Map.of()))
                .isInstanceOf(AuditUnavailableException.class);
    }

    @Test
    void closeAfterEngineSuccessTranslatesFailureToOutcomeVerificationFailed() {
        AuditEntry entry = entry("e1");
        when(repository.saveAndFlush(any(AuditEntry.class)))
                .thenThrow(new DataAccessResourceFailureException("gone away"));

        assertThatThrownBy(() -> service.close(entry, AuditOutcome.ok, 200, null, true))
                .isInstanceOf(OutcomeVerificationFailedException.class)
                .hasMessageContaining("Action dispatched — outcome verification failed");
    }

    @Test
    void closeAfterEngineFailureNeverMasksTheEngineError() {
        AuditEntry entry = entry("e1");
        when(repository.saveAndFlush(any(AuditEntry.class)))
                .thenThrow(new DataAccessResourceFailureException("gone away"));

        // nothing state-changing happened → the original engine error must dominate, no throw here
        assertThatCode(() -> service.close(entry, AuditOutcome.failed, 500, "boom", false))
                .doesNotThrowAnyException();
    }

    @Test
    void closeTruncatesSnippetsToTheByteCapAndFlagsThem() {
        AuditEntry entry = entry("e1");
        String huge = "x".repeat(AuditEntry.SNIPPET_MAX_BYTES + 500);

        service.close(entry, AuditOutcome.failed, 500, huge, false);

        ArgumentCaptor<AuditEntry> saved = ArgumentCaptor.forClass(AuditEntry.class);
        org.mockito.Mockito.verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getResponseSnippet().getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(AuditEntry.SNIPPET_MAX_BYTES);
        assertThat(saved.getValue().isResponseTruncated()).isTrue();
    }

    @Test
    void truncationNeverSplitsACodePoint() {
        String twoByteChars = "é".repeat(10); // 2 bytes each in UTF-8
        String truncated = AuditService.truncateToBytes(twoByteChars, 5);
        assertThat(truncated).isEqualTo("éé"); // 4 bytes — the 3rd é would need 6
    }

    @Test
    void redactReplacesValuesOfSecretNamedKeysRecursively() {
        Map<String, Object> payload = Map.of(
                "businessKey", "ORD-1",
                "apiToken", "s3cr3t",
                "nested", Map.of("dbPassword", "hunter2", "plain", "ok"));

        Map<String, Object> clean = AuditService.redact(payload);

        assertThat(clean).containsEntry("businessKey", "ORD-1").containsEntry("apiToken", AuditService.REDACTED);
        assertThat(clean.get("nested")).isEqualTo(Map.of("dbPassword", AuditService.REDACTED, "plain", "ok"));
    }

    private static AuditEntry entry(String id) {
        return new AuditEntry(
                java.util.UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)),
                "corr",
                "operator",
                Instant.parse("2026-07-06T11:59:00Z"),
                "engine-a",
                null,
                "pi-1",
                "retry-job",
                null,
                null,
                null,
                false);
    }
}
