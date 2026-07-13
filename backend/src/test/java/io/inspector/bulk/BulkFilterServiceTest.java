package io.inspector.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * Rung 1 for the v1.x #2 filter bulk: the BINDING server-side re-resolution contract. The
 * browser sends the search criteria it is looking at; this service must (a) refuse
 * pre-flight on unusable criteria without touching the resolver, (b) refuse fail-closed
 * when any engine's resolution degraded OR any failure-lane scan truncated ("all matching"
 * must never be a silent subset), (c) dedupe + cap at the 5000-item query-bulk hard cap,
 * and (d) hand the M5 door a submit carrying the criteria provenance under the raised cap.
 * The resolver (SearchService) and the M5 rails are OUR proven seams — mocked here, wire
 * truth on rung 4 ({@code BulkFilterIT}).
 */
class BulkFilterServiceTest {

    private static final String ENGINE = "engine-a";

    private final SearchService search = mock(SearchService.class);
    private final BulkJobService bulk = mock(BulkJobService.class);
    private final EngineRegistry registry = new EngineRegistry(new InspectorProperties(
            null,
            null,
            null,
            null,
            List.of(TestEngines.engine(ENGINE, "http://localhost:1", EngineEnvironment.DEV, EngineMode.READ_WRITE))));
    private final Authentication responder = new TestingAuthenticationToken("resp", "n/a", "ROLE_RESPONDER");

    private final BulkFilterService service = new BulkFilterService(search, bulk, registry);

    /* ------------------------- pre-flight refusals (no resolution) ------------------------- */

    @Test
    void missingCriteriaRefused() {
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkFilterRequest(null, "retry-job", "ops-4711 incident", null), responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("filter-criteria-required"));
        verifyNoInteractions(search, bulk);
    }

    @Test
    void shortReasonRefusedBeforeAnyResolution() {
        assertThatThrownBy(() -> service.submit(request(criteria(InstanceStatus.FAILED), "too short"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("reason-too-short");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(search, bulk);
    }

    @Test
    void openEndedStatusSetRefused() {
        assertThatThrownBy(() -> service.submit(request(criteria(), "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("filter-statuses-required"));
        verifyNoInteractions(search, bulk);
    }

    @Test
    void completedChipRefusedAsNotActionable() {
        assertThatThrownBy(() -> service.submit(
                        request(criteria(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED), "ops-4711 incident"),
                        responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class,
                        e -> assertThat(e.code()).isEqualTo("filter-completed-not-actionable"));
        verifyNoInteractions(search, bulk);
    }

    @Test
    void unknownEngineRefusedPreFlight() {
        SearchRequest bad = new SearchRequest(
                List.of("nope"),
                List.of(InstanceStatus.FAILED),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> service.submit(
                        new BulkDtos.BulkFilterRequest(bad, "retry-job", "ops-4711 incident", null), responder))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(search, bulk);
    }

    /* ------------------------- resolution outcomes ------------------------- */

    @Test
    void resolvesExhaustivelyDedupesAndDelegatesUnderTheFilterCap() {
        when(search.resolveAllMatching(any(), eq(BulkJob.FILTER_ITEM_CAP)))
                .thenReturn(response(
                        List.of(row("p-1"), row("p-2"), row("p-1")), // p-1 twice: dedupe
                        Map.of(ENGINE, EngineResult.success(3, 3))));
        BulkDtos.BulkJobDto dto = mock(BulkDtos.BulkJobDto.class);
        when(bulk.submit(any(), eq(responder), anyMap(), eq(BulkJob.FILTER_ITEM_CAP), any()))
                .thenReturn(dto);

        SearchRequest criteria = criteria(InstanceStatus.FAILED);
        BulkDtos.BulkJobDto out = service.submit(request(criteria, "ops-4711 incident"), responder);

        assertThat(out).isSameAs(dto);
        ArgumentCaptor<SearchRequest> query = ArgumentCaptor.forClass(SearchRequest.class);
        verify(search).resolveAllMatching(query.capture(), eq(BulkJob.FILTER_ITEM_CAP));
        // The criteria pass through verbatim — minus the grid's display page size.
        assertThat(query.getValue().statuses()).containsExactly(InstanceStatus.FAILED);
        assertThat(query.getValue().processDefinitionKey()).isEqualTo("payment");
        assertThat(query.getValue().pageSize()).isNull();

        ArgumentCaptor<BulkDtos.BulkSubmitRequest> submit = ArgumentCaptor.forClass(BulkDtos.BulkSubmitRequest.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> scopeLabel = ArgumentCaptor.forClass(String.class);
        verify(bulk)
                .submit(
                        submit.capture(),
                        eq(responder),
                        meta.capture(),
                        eq(BulkJob.FILTER_ITEM_CAP),
                        scopeLabel.capture());
        assertThat(submit.getValue().verb()).isEqualTo("retry-job");
        assertThat(submit.getValue().reason()).isEqualTo("ops-4711 incident");
        assertThat(submit.getValue().items())
                .extracting(BulkDtos.BulkTarget::instanceId)
                .containsExactly("p-1", "p-2");
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) meta.getValue().get("filter");
        assertThat(filter).containsEntry("criteria", criteria).containsEntry("resolvedCount", 2);
        // E1-back: scope provenance — the compact criteria summary (statuses + defKey +
        // engines), ported from the FilterBulkModal chip notion, ≤120 chars.
        assertThat(scopeLabel.getValue()).isEqualTo("FAILED · payment · engines: engine-a");
    }

    @Test
    void scopeLabelOmitsEmptyPartsAndTruncatesAt120Chars() {
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(List.of(row("p-1")), Map.of(ENGINE, EngineResult.success(1, 1))));
        when(bulk.submit(any(), eq(responder), anyMap(), anyInt(), any())).thenReturn(mock(BulkDtos.BulkJobDto.class));
        SearchRequest noDefKeyNoEngines = new SearchRequest(
                null,
                List.of(InstanceStatus.FAILED, InstanceStatus.ACTIVE),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        service.submit(request(noDefKeyNoEngines, "ops-4711 incident"), responder);

        ArgumentCaptor<String> scopeLabel = ArgumentCaptor.forClass(String.class);
        verify(bulk).submit(any(), eq(responder), anyMap(), anyInt(), scopeLabel.capture());
        assertThat(scopeLabel.getValue()).isEqualTo("FAILED + ACTIVE");
        assertThat(scopeLabel.getValue().length()).isLessThanOrEqualTo(120);
    }

    @Test
    void degradedEngineResolutionRefusedFailClosed() {
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(
                        response(List.of(row("p-1")), Map.of(ENGINE, EngineResult.failure("timeout after 8000ms"))));
        assertThatThrownBy(
                        () -> service.submit(request(criteria(InstanceStatus.FAILED), "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("filter-resolution-degraded");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
        verifyNoInteractions(bulk);
    }

    @Test
    void truncatedScanRefusedNeverASilentSubset() {
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(
                        List.of(row("p-1")), Map.of(ENGINE, EngineResult.success(1, 1, "truncated@5000", null))));
        assertThatThrownBy(
                        () -> service.submit(request(criteria(InstanceStatus.FAILED), "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("filter-scan-truncated");
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(bulk);
    }

    @Test
    void drainedFilterRefusedAsConflict() {
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(List.of(), Map.of(ENGINE, EngineResult.success(0, 0))));
        assertThatThrownBy(
                        () -> service.submit(request(criteria(InstanceStatus.FAILED), "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(GuardRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo("filter-drained");
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(bulk);
    }

    @Test
    void overTheQueryBulkHardCapRefusedNeverPartiallyDispatched() {
        List<ProcessInstanceRow> rows = IntStream.rangeClosed(1, BulkJob.FILTER_ITEM_CAP + 1)
                .mapToObj(i -> row("p-" + i))
                .toList();
        when(search.resolveAllMatching(any(), anyInt()))
                .thenReturn(response(rows, Map.of(ENGINE, EngineResult.success(rows.size(), rows.size()))));
        assertThatThrownBy(
                        () -> service.submit(request(criteria(InstanceStatus.FAILED), "ops-4711 incident"), responder))
                .isInstanceOfSatisfying(
                        GuardRefusedException.class, e -> assertThat(e.code()).isEqualTo("bulk-cap-exceeded"));
        verifyNoInteractions(bulk);
    }

    /* ------------------------- fixtures ------------------------- */

    private static BulkDtos.BulkFilterRequest request(SearchRequest criteria, String reason) {
        return new BulkDtos.BulkFilterRequest(criteria, "retry-job", reason, null);
    }

    /** Criteria as the grid sends them — including a display pageSize the resolver must strip. */
    private static SearchRequest criteria(InstanceStatus... statuses) {
        return new SearchRequest(
                List.of(ENGINE),
                List.of(statuses),
                "payment",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                50);
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
                null,
                null);
    }
}
