package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.action.GuardRefusedException;
import io.inspector.aggregate.SearchService;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.inspector.triage.ErrorSignatureNormalizer;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rung 1 for the v1.x #1 group retry: the SERVER-SIDE RESOLUTION contract. The browser
 * sends coordinates; this service must (a) refuse pre-flight on bad coordinates without
 * touching the scan, (b) refuse fail-closed when any engine's resolution leg degraded,
 * (c) dedupe + cap honestly, and (d) hand the M5 door a retry-job submit carrying the
 * group's provenance for the envelope audit row. The scan itself (SearchService) and the
 * M5 rails (BulkJobService) are OUR proven seams — mocked here, wire truth on rung 4
 * ({@code BulkErrorClassIT}).
 */
class BulkErrorClassServiceTest {

    private static final String ENGINE = "engine-a";
    private static final String HASH = "a".repeat(64);
    private static final int ALGO = ErrorSignatureNormalizer.ALGO_VERSION;

    private final SearchService search = mock(SearchService.class);
    private final BulkJobService bulk = mock(BulkJobService.class);
    private final EngineRegistry registry = new EngineRegistry(new InspectorProperties(
            null,
            null,
            null,
            null,
            List.of(TestEngines.engine(ENGINE, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
    private final Authentication responder = new TestingAuthenticationToken("resp", "n/a", "ROLE_RESPONDER");

    private final BulkErrorClassService service = new BulkErrorClassService(search, bulk, registry);

    /* ------------------------- pre-flight refusals (no scan) ------------------------- */

    @Test
    void shortReasonRefusedBeforeAnyScan() {
        assertThatThrownBy(() -> service.submit(request(HASH, ALGO, "payment", 3, ENGINE, "too short"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("reason-too-short");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(search, bulk);
    }

    @Test
    void missingSignatureRefused() {
        assertThatThrownBy(
                        () -> service.submit(request(" ", ALGO, "payment", 3, ENGINE, "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("error-class-signature-required"));
        verifyNoInteractions(search, bulk);
    }

    @Test
    void staleAlgoVersionRefusedLoudly() {
        assertThatThrownBy(() ->
                        service.submit(request(HASH, ALGO + 1, "payment", 3, ENGINE, "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("error-class-algo-mismatch");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(search, bulk);
    }

    @Test
    void missingDefinitionVersionRefused() {
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkErrorClassRequest(
                                HASH, ALGO, "payment", null, ENGINE, "ops-4711 incident", null),
                        responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("error-class-definition-required"));
        verifyNoInteractions(search, bulk);
    }

    @Test
    void unknownEngineRefusedPreFlight() {
        assertThatThrownBy(
                        () -> service.submit(request(HASH, ALGO, "payment", 3, "nope", "ops-4711 incident"), responder))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(search, bulk);
    }

    /* ------------------------- resolution outcomes ------------------------- */

    @Test
    void resolvesMembersServerSideAndDelegatesWithProvenance() {
        when(search.search(any()))
                .thenReturn(response(
                        List.of(row("p-1"), row("p-2"), row("p-1")), // p-1 twice: dedupe
                        Map.of(ENGINE, EngineResult.success(3, 3))));
        BulkDtos.BulkJobDto dto = mock(BulkDtos.BulkJobDto.class);
        when(bulk.submit(any(), eq(responder), anyMap(), any())).thenReturn(dto);

        BulkDtos.BulkJobDto out =
                service.submit(request(HASH, ALGO, "payment", 3, ENGINE, "ops-4711 incident"), responder);

        assertThat(out).isSameAs(dto);
        ArgumentCaptor<SearchRequest> query = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(query.capture());
        assertThat(query.getValue().engineIds()).containsExactly(ENGINE);
        assertThat(query.getValue().statuses()).containsExactly(InstanceStatus.FAILED);
        assertThat(query.getValue().signatureHash()).isEqualTo(HASH);
        assertThat(query.getValue().processDefinitionKey()).isEqualTo("payment");
        assertThat(query.getValue().definitionVersion()).isEqualTo(3);
        assertThat(query.getValue().pageSize()).isEqualTo(BulkJob.ITEM_CAP + 1);

        ArgumentCaptor<BulkDtos.BulkSubmitRequest> submit = ArgumentCaptor.forClass(BulkDtos.BulkSubmitRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> scopeLabel = ArgumentCaptor.forClass(String.class);
        verify(bulk).submit(submit.capture(), eq(responder), meta.capture(), scopeLabel.capture());
        assertThat(submit.getValue().verb()).isEqualTo("retry-job");
        assertThat(submit.getValue().reason()).isEqualTo("ops-4711 incident");
        assertThat(submit.getValue().items())
                .extracting(BulkDtos.BulkTarget::instanceId)
                .containsExactly("p-1", "p-2");
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) meta.getValue().get("errorClass");
        assertThat(group)
                .containsEntry("signatureHash", HASH)
                .containsEntry("algoVersion", ALGO)
                .containsEntry("definition", "payment:v3")
                .containsEntry("engineId", ENGINE)
                .containsEntry("resolvedCount", 2)
                .containsEntry("scanTruncated", false);
        // E1-back: scope provenance — the door's own defKey:version label, so the drawer
        // shows "what was targeted" without re-deriving it from the envelope payload.
        assertThat(scopeLabel.getValue()).isEqualTo("payment v3 · error class");
    }

    @Test
    void drainedGroupRefusedAsConflict() {
        when(search.search(any())).thenReturn(response(List.of(), Map.of(ENGINE, EngineResult.success(0, 0))));
        assertThatThrownBy(
                        () -> service.submit(request(HASH, ALGO, "payment", 3, ENGINE, "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("error-class-drained");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(bulk);
    }

    @Test
    void overCapRefusedNeverPartiallyDispatched() {
        List<ProcessInstanceRow> rows = IntStream.rangeClosed(1, BulkJob.ITEM_CAP + 1)
                .mapToObj(i -> row("p-" + i))
                .toList();
        when(search.search(any()))
                .thenReturn(response(rows, Map.of(ENGINE, EngineResult.success(rows.size(), rows.size()))));
        assertThatThrownBy(
                        () -> service.submit(request(HASH, ALGO, "payment", 3, ENGINE, "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("bulk-cap-exceeded"));
        verifyNoInteractions(bulk);
    }

    @Test
    void degradedEngineResolutionRefusedFailClosed() {
        when(search.search(any()))
                .thenReturn(
                        response(List.of(row("p-1")), Map.of(ENGINE, EngineResult.failure("timeout after 8000ms"))));
        assertThatThrownBy(
                        () -> service.submit(request(HASH, ALGO, "payment", 3, ENGINE, "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("error-class-resolution-degraded");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
        verifyNoInteractions(bulk);
    }

    @Test
    void truncatedScanRecordedInProvenance() {
        when(search.search(any()))
                .thenReturn(response(
                        List.of(row("p-1")), Map.of(ENGINE, EngineResult.success(1, 1, "truncated@500", null))));
        when(bulk.submit(any(), eq(responder), anyMap(), any())).thenReturn(mock(BulkDtos.BulkJobDto.class));

        service.submit(request(HASH, ALGO, "payment", 3, ENGINE, "ops-4711 incident"), responder);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(bulk).submit(any(), eq(responder), meta.capture(), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) meta.getValue().get("errorClass");
        assertThat(group).containsEntry("scanTruncated", true);
    }

    @Test
    void nullEngineFansOutToAllEnginesTheGroupSpans() {
        when(search.search(any()))
                .thenReturn(response(List.of(row("p-1")), Map.of(ENGINE, EngineResult.success(1, 1))));
        when(bulk.submit(any(), eq(responder), anyMap(), any())).thenReturn(mock(BulkDtos.BulkJobDto.class));

        service.submit(request(HASH, ALGO, "payment", 3, null, "ops-4711 incident"), responder);

        ArgumentCaptor<SearchRequest> query = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).search(query.capture());
        assertThat(query.getValue().engineIds()).isNull();
    }

    /* ------------------------- fixtures ------------------------- */

    private static BulkDtos.BulkErrorClassRequest request(
            String hash, int algo, String defKey, Integer version, String engineId, String reason) {
        return new BulkDtos.BulkErrorClassRequest(hash, algo, defKey, version, engineId, reason, null);
    }

    private static SearchResponse response(List<ProcessInstanceRow> rows, Map<String, EngineResult> perEngine) {
        return new SearchResponse(rows, perEngine, Map.of(), null, null);
    }

    private static ProcessInstanceRow row(String instanceId) {
        return new ProcessInstanceRow(
                ENGINE + ":" + instanceId,
                ENGINE,
                null,
                null,
                instanceId,
                null,
                null,
                "payment",
                null,
                3,
                null,
                "bpmn",
                InstanceStatus.FAILED,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
