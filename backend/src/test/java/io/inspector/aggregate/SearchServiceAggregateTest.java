package io.inspector.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchRequest.VariableFilter;
import io.inspector.dto.SearchResponse;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import io.inspector.triage.ErrorSignatureNormalizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 2 (#464): {@link SearchService}'s OWN responsibilities — request validation, engine targeting
 * and exclusion labels, the per-engine envelope (success / engine error), the cross-engine merge order,
 * the deep-paging entry cursor and the protected-instance decoration — over a real
 * {@link ProcessApiClient} whose engines are WireMocks serving RECORDED flowable-rest 6.8 / 7.1
 * documents ({@code src/test/resources/wire/}). Deliberately asserts nothing about the derived status
 * flags: the stubs answer by path, not by filter, so the status join is NOT exercised faithfully here —
 * that is {@link StatusJoin}'s rung-1 suite and {@code SearchServiceIT}'s dockerized rung.
 */
class SearchServiceAggregateTest {

    private static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
            .ignoreExceptions(HttpClientErrorException.class)
            .build();
    private static final BulkheadConfig BULKHEAD = BulkheadConfig.custom()
            .maxConcurrentCalls(16)
            .maxWaitDuration(Duration.ofSeconds(5))
            .build();

    private final List<WireMockServer> servers = new ArrayList<>();
    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProtectedInstanceRepository protectedRepo = mock(ProtectedInstanceRepository.class);
    private final ProcessApiClient client = new ProcessApiClient(new GuardedCaller(
            new MockEnvironment().withProperty("ENGINE_T_PASSWORD", "test"),
            CircuitBreakerRegistry.of(Map.of("engine", BREAKER, "deep-page", BREAKER)),
            BulkheadRegistry.of(Map.of("engine", BULKHEAD, "deep-page", BULKHEAD)),
            new SimpleMeterRegistry(),
            new RecentEngineErrors(Clock.systemUTC())));
    private final SearchService service = new SearchService(
            registry, client, new InspectorProperties(4, 10, null, null, null, List.of()), protectedRepo);

    @AfterEach
    void stop() {
        service.shutdown();
        servers.forEach(WireMockServer::stop);
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static String json(String major, String name) {
        try (InputStream in =
                SearchServiceAggregateTest.class.getResourceAsStream("/wire/" + major + "/" + name + ".json")) {
            if (in == null) throw new IllegalStateException("missing fixture wire/" + major + "/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A WireMock engine answering the search legs with one major's recorded documents. */
    private WireMockServer recordedEngine(String major, String historicFixture) {
        WireMockServer wm = new WireMockServer(options().dynamicPort());
        wm.start();
        servers.add(wm);
        wm.stubFor(any(anyUrl()).atPriority(10).willReturn(okJson(json(major, "jobs")))); // a recorded empty page
        wm.stubFor(post(urlPathEqualTo("/query/historic-process-instances"))
                .willReturn(okJson(json(major, historicFixture))));
        wm.stubFor(post(urlPathEqualTo("/query/process-instances"))
                .willReturn(okJson(json(major, "query-process-instances"))));
        wm.stubFor(get(urlPathMatching("/history/historic-activity-instances"))
                .willReturn(okJson(json(major, "historic-activity-instances"))));
        return wm;
    }

    private WireMockServer sickEngine() {
        WireMockServer wm = new WireMockServer(options().dynamicPort());
        wm.start();
        servers.add(wm);
        wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
        return wm;
    }

    private EngineConfig engine(String id, WireMockServer wm) {
        return TestEngines.engine(
                id,
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(2000, 2000, null));
    }

    private void fleet(EngineConfig... engines) {
        when(registry.all()).thenReturn(List.of(engines));
        for (EngineConfig e : engines) {
            when(registry.resolve(e.id())).thenReturn(Optional.of(e));
        }
    }

    private static SearchRequest req(
            List<String> engineIds,
            List<InstanceStatus> statuses,
            String definitionKey,
            Integer definitionVersion,
            String sortBy,
            Integer pageSize,
            String signatureHash,
            Integer signatureAlgoVersion,
            List<VariableFilter> variables) {
        return new SearchRequest(
                engineIds,
                statuses,
                definitionKey,
                definitionVersion,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                signatureHash,
                null,
                variables,
                sortBy,
                pageSize,
                null,
                signatureAlgoVersion);
    }

    private static SearchRequest active(List<String> engineIds, Integer pageSize) {
        return req(
                engineIds,
                List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED),
                null,
                null,
                null,
                pageSize,
                null,
                null,
                null);
    }

    /* ------------------------------ validation ------------------------------ */

    @Test
    void malformedRequestsAreRefusedBeforeAnyEngineIsTouched() {
        WireMockServer wm = recordedEngine("6.8", "query-historic-process-instances");
        fleet(engine("eng", wm));

        assertThatThrownBy(() -> service.search(req(null, null, null, null, "name", 50, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sortBy must be");
        assertThatThrownBy(() -> service.search(req(null, null, null, 3, null, 50, null, null, null)))
                .hasMessageContaining("definitionVersion requires processDefinitionKey");
        assertThatThrownBy(() -> service.deepPage(req(null, null, null, 3, null, 50, null, null, null), null, 0L))
                .hasMessageContaining("definitionVersion requires processDefinitionKey");
        assertThatThrownBy(() ->
                        service.deepPage(req(null, null, null, null, "failureTime", 50, null, null, null), null, 0L))
                .hasMessageContaining("startTime-ordered");
        assertThatThrownBy(() -> service.deepPage(
                        req(null, List.of(InstanceStatus.FAILED), null, null, null, 50, null, null, null), null, 0L))
                .hasMessageContaining("FAILED/RETRYING-only");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void aProvablyStaleSignatureLinkShortCircuitsToAnHonestEmptyAnswer() {
        WireMockServer wm = recordedEngine("6.8", "query-historic-process-instances");
        fleet(engine("eng", wm));
        int stale = ErrorSignatureNormalizer.ALGO_VERSION - 1;

        SearchResponse res = service.search(req(null, null, null, null, null, 50, "abc123", stale, null));
        SearchService.DeepPage page =
                service.deepPage(req(null, null, null, null, null, 50, "abc123", stale, null), null, 0L);

        assertThat(res.rows()).isEmpty();
        assertThat(res.perEngine()).isEmpty();
        assertThat(res.signatureGeneration().provablyEmpty()).isTrue();
        assertThat(page.rows()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /* ------------------------------ fan-out + merge ------------------------------ */

    @Test
    void twoRecordedEnginesMergeIntoOneNewestFirstOrderWithPerEngineEnvelopes() {
        EngineConfig six = engine("six", recordedEngine("6.8", "query-historic-process-instances"));
        EngineConfig seven = engine("seven", recordedEngine("7.1", "query-historic-process-instances"));
        fleet(six, seven);
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());

        SearchResponse res = service.search(active(null, 50));

        assertThat(res.rows()).isNotEmpty();
        assertThat(res.rows()).extracting(ProcessInstanceRow::engineId).contains("six", "seven");
        List<Instant> starts = res.rows().stream()
                .map(r -> StatusJoin.parseInstant(r.startTime()))
                .toList();
        assertThat(starts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(res.rows()).allSatisfy(r -> {
            assertThat(r.compositeId()).isEqualTo(r.engineId() + ":" + r.processInstanceId());
            assertThat(r.protectedInstance()).isFalse();
        });
        for (String id : List.of("six", "seven")) {
            SearchResponse.EngineResult er = res.perEngine().get(id);
            assertThat(er.ok()).isTrue();
            assertThat(er.fetched())
                    .isEqualTo(res.rows().stream()
                            .filter(r -> r.engineId().equals(id))
                            .count());
        }
        assertThat(res.nextCursor()).isNull(); // every engine returned its whole result
    }

    @Test
    void aSickEngineBecomesAFailureEnvelopeAndNamedButUnavailableEnginesAreLabelled() {
        EngineConfig good = engine("good", recordedEngine("6.8", "query-historic-process-instances"));
        EngineConfig sick = engine("sick", sickEngine());
        EngineConfig disabled =
                TestEngines.builder("off", "http://localhost:1").enabled(false).build();
        when(registry.all()).thenReturn(List.of(good, sick));
        when(registry.resolve("off")).thenReturn(Optional.of(disabled));
        when(registry.resolve("gone")).thenReturn(Optional.empty());
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());

        SearchResponse res = service.search(active(List.of("good", "sick", "off", "gone", " "), 50));

        assertThat(res.perEngine().get("good").ok()).isTrue();
        assertThat(res.perEngine().get("sick").ok()).isFalse();
        assertThat(res.perEngine().get("sick").error()).isNotBlank();
        assertThat(res.perEngine().get("off").error()).contains("currently disabled");
        assertThat(res.perEngine().get("gone").error()).contains("no longer registered");
        assertThat(res.perEngine()).doesNotContainKey(" ");
        assertThat(res.rows())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.engineId()).isEqualTo("good"));
    }

    @Test
    void protectedRowsAreDecoratedAndARegistryOutageLeavesTheFlagUnknown() {
        EngineConfig eng = engine("eng", recordedEngine("6.8", "query-historic-process-instances"));
        fleet(eng);
        SearchResponse first = service.search(active(null, 50));
        ProcessInstanceRow guarded = first.rows().get(0);
        when(protectedRepo.findAllById(anyIterable()))
                .thenReturn(List.of(new ProtectedInstance(
                        "eng", guarded.processInstanceId(), "legal hold", "auditor", Instant.EPOCH)));

        SearchResponse marked = service.search(active(null, 50));

        assertThat(marked.rows())
                .filteredOn(r -> r.processInstanceId().equals(guarded.processInstanceId()))
                .singleElement()
                .satisfies(r -> assertThat(r.protectedInstance()).isTrue());
        assertThat(marked.rows())
                .filteredOn(r -> !r.processInstanceId().equals(guarded.processInstanceId()))
                .allSatisfy(r -> assertThat(r.protectedInstance()).isFalse());

        when(protectedRepo.findAllById(anyIterable())).thenThrow(new IllegalStateException("postgres down"));
        SearchResponse outage = service.search(active(null, 50));
        assertThat(outage.rows())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.protectedInstance()).isNull());
    }

    @Test
    void anOverflowingEngineMintsTheDeepPagingEntryCursorWhichTheNextPageAccepts() {
        // The recorded size=2 page: 2 of 7 historic rows — the engine has more than this page shows.
        WireMockServer wm = recordedEngine("6.8", "query-historic-process-instances-first-2");
        wm.stubFor(post(urlPathEqualTo("/query/historic-process-instances"))
                .withRequestBody(equalToJson("{\"start\":2}", true, true))
                .willReturn(okJson(json("6.8", "query-historic-process-instances-next-2"))));
        EngineConfig eng = engine("eng", wm);
        fleet(eng);
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());
        SearchRequest request = active(null, 2);

        SearchResponse res = service.search(request);

        assertThat(res.perEngine().get("eng").total())
                .isGreaterThan(res.perEngine().get("eng").fetched());
        assertThat(res.nextCursor()).isNotNull();

        SearchService.DeepPage next = service.deepPage(request, res.nextCursor(), System.currentTimeMillis());
        assertThat(next.perEngine().get("eng").ok()).isTrue();
        assertThat(next.rows())
                .isNotEmpty()
                .noneMatch(r ->
                        res.rows().stream().anyMatch(seen -> seen.compositeId().equals(r.compositeId())));
    }

    @Test
    void deepPageFirstPageAndSickEngineEnvelopeAndScopedTargets() {
        EngineConfig good = engine("good", recordedEngine("7.1", "query-historic-process-instances"));
        EngineConfig sick = engine("sick", sickEngine());
        fleet(good, sick);
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());

        SearchService.DeepPage page = service.deepPage(
                active(List.of("good", "sick"), 500), "", System.currentTimeMillis(), Set.of("good", "sick"));

        assertThat(page.rows())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.engineId()).isEqualTo("good"));
        assertThat(page.rows().size()).isLessThanOrEqualTo(200); // global deep page size is clamped
        assertThat(page.perEngine().get("good").ok()).isTrue();
        assertThat(page.perEngine().get("sick").ok()).isFalse();
        assertThat(page.depthCapped()).isFalse();
    }

    @Test
    void variableSearchWithoutADefinitionStillRunsAndResolveAllMatchingHarvestsEveryRow() {
        WireMockServer wm = recordedEngine("6.8", "query-historic-process-instances");
        fleet(engine("eng", wm));
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());
        List<VariableFilter> vars = List.of(new VariableFilter("amount", 100, "equals", "integer"));

        SearchResponse viaVariables = service.search(req(
                null,
                List.of(InstanceStatus.ACTIVE, InstanceStatus.COMPLETED),
                null,
                null,
                null,
                50,
                null,
                null,
                vars));
        SearchResponse all = service.resolveAllMatching(active(null, 50), 1000);

        assertThat(viaVariables.perEngine().get("eng").ok()).isTrue();
        wm.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                        urlPathEqualTo("/query/historic-process-instances"))
                .withRequestBody(equalToJson("{\"variables\":[{\"name\":\"amount\"}]}", true, true)));
        assertThat(all.perEngine().get("eng").ok()).isTrue();
        assertThat(all.nextCursor()).isNull(); // exhaustive resolution never mints a Load-more cursor
    }

    @Test
    void definitionKeysAndVersionsAreParsedFromTheRecordedDefinitionIds() {
        EngineConfig eng = engine("eng", recordedEngine("7.1", "query-historic-process-instances"));
        fleet(eng);
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());

        SearchResponse res = service.search(active(null, 50));

        assertThat(res.rows()).allSatisfy(r -> {
            assertThat(r.processDefinitionKey()).doesNotContain(":");
            assertThat(r.definitionVersion()).isEqualTo(1);
        });
        assertThat(SearchService.likePattern("abc")).isEqualTo("%abc%");
        assertThat(SearchService.likePattern("a%c")).isEqualTo("a%c");
    }

    @Test
    void anEmptyStatusListMeansEveryStatusAndAFailureTimeSortIsAccepted() {
        WireMockServer wm = recordedEngine("6.8", "query-historic-process-instances");
        fleet(engine("eng", wm));
        when(protectedRepo.findAllById(anyIterable())).thenReturn(List.of());
        when(registry.resolve(anyString())).thenReturn(Optional.empty());

        SearchResponse res = service.search(req(null, List.of(), null, null, "failureTime", 50, null, null, null));

        assertThat(res.perEngine().get("eng").ok()).isTrue();
    }
}
