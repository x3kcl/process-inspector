package io.inspector.detail;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.StatusEvidence;
import io.inspector.dto.StatusEvidence.FlagFinding;
import io.inspector.registry.EngineRegistry;
import io.inspector.support.TestEngines;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Rung 2 (unit-test-patterns): "Explain this status" evidence assembled from a REAL
 * {@link FlowableEngineClient} against WireMock — so the {@link io.inspector.client.EngineCallRecorder}
 * genuinely captures each leg's URL/method/body/status (R-L3-01, SPEC §3). The join semantics
 * themselves are proven at rung 4 (never mocked); here we prove the derivation is transcribed
 * into falsifiable, re-derived-and-labeled evidence.
 */
class StatusEvidenceServiceTest {

    private static final String ENGINE = "e";

    private WireMockServer wm;
    private StatusEvidenceService service;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(Map.of(
                "engine",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(10)
                        .build()));
        BulkheadRegistry bulkheads = BulkheadRegistry.of(Map.of(
                "engine",
                BulkheadConfig.custom()
                        .maxConcurrentCalls(8)
                        .maxWaitDuration(Duration.ofSeconds(5))
                        .build()));
        FlowableEngineClient client = new FlowableEngineClient(env, breakers, bulkheads);
        EngineConfig engine = TestEngines.engine(ENGINE, wm.baseUrl());
        EngineRegistry registry = mock(EngineRegistry.class);
        when(registry.require(ENGINE)).thenReturn(engine);
        InstanceDetailService detail =
                new InstanceDetailService(registry, client, new InspectorProperties(null, null, null, null, List.of()));
        service = new StatusEvidenceService(registry, detail);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void endedInstance_shortCircuits_singleAnchorLeg_labeledRederived() {
        wm.stubFor(get(urlPathEqualTo("/history/historic-process-instances/pi-done"))
                .willReturn(okJson("{\"id\":\"pi-done\",\"endTime\":\"2026-07-11T09:00:00.000Z\"}")));

        StatusEvidence evidence = service.explain(ENGINE, "pi-done");

        assertThat(evidence.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(evidence.plan()).isEqualTo("ENDED_SHORT_CIRCUIT");
        assertThat(evidence.rederived()).isTrue();
        assertThat(evidence.rederivedAt()).isNotBlank();
        assertThat(evidence.note()).contains("does not retain");
        // The ended path scans nothing beyond the anchor — exactly one captured leg.
        assertThat(evidence.legs()).hasSize(1);
        assertThat(evidence.legs().get(0).label()).isEqualTo("historic anchor (ended?)");
        assertThat(evidence.legs().get(0).status()).isEqualTo(200);
        assertThat(evidence.legs().get(0).source()).isEqualTo("live");
        assertThat(finding(evidence, "ended").value()).isTrue();
        assertThat(finding(evidence, "hasDeadLetterJobs").source()).isEqualTo("not scanned");
    }

    @Test
    void failedInSubprocess_isExplained_withDeepLinkAndChildJob() {
        // Root is clean on its own lanes but a call-activity CHILD carries a dead-letter job —
        // the retest status-contradiction (grid parent ACTIVE vs detail FAILED "in subprocess").
        wm.stubFor(get(urlPathEqualTo("/history/historic-process-instances/pi-root"))
                .willReturn(okJson("{\"id\":\"pi-root\"}")));
        wm.stubFor(get(urlPathEqualTo("/runtime/process-instances/pi-root"))
                .willReturn(okJson("{\"id\":\"pi-root\",\"suspended\":false}")));
        emptyLane("/management/deadletter-jobs", "pi-root");
        emptyLane("/management/timer-jobs", "pi-root");
        emptyLane("/management/jobs", "pi-root");
        // Descendant walk: root has one open call-activity child pi-child.
        wm.stubFor(post(urlPathEqualTo("/query/historic-process-instances"))
                .withRequestBody(containing("pi-root"))
                .willReturn(okJson("{\"data\":[{\"id\":\"pi-child\"}],\"total\":1,\"start\":0,\"size\":1}")));
        // The child holds a dead-letter job — this sets failedInSubprocess on the root.
        wm.stubFor(get(urlPathEqualTo("/management/deadletter-jobs"))
                .withQueryParam("processInstanceId", equalTo("pi-child"))
                .willReturn(okJson("{\"data\":[{\"id\":\"job-9\"}],\"total\":1,\"start\":0,\"size\":1}")));

        StatusEvidence evidence = service.explain(ENGINE, "pi-root");

        assertThat(evidence.plan()).isEqualTo("LIVE_LANE_SCAN");
        assertThat(evidence.status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(evidence.flags().failedInSubprocess()).isTrue();
        assertThat(evidence.flags().hasDeadLetterJobs()).isFalse();

        FlagFinding subprocess = finding(evidence, "failedInSubprocess");
        assertThat(subprocess.value()).isTrue();
        assertThat(subprocess.deepLinkInstanceId()).isEqualTo("pi-child");
        assertThat(subprocess.detail()).contains("pi-child").contains("job-9");

        // Raw legs really captured: the child query is a POST whose body carried the parent id.
        StatusEvidence.Leg childQuery = evidence.legs().stream()
                .filter(leg -> "descendant walk — child query".equals(leg.label()))
                .findFirst()
                .orElseThrow();
        assertThat(childQuery.method()).isEqualTo("POST");
        assertThat(childQuery.requestBody()).contains("pi-root");
        assertThat(childQuery.status()).isEqualTo(200);
        assertThat(evidence.legs()).anyMatch(leg -> "dead-letter lane".equals(leg.label()));
    }

    private void emptyLane(String path, String instanceId) {
        wm.stubFor(get(urlPathEqualTo(path))
                .withQueryParam("processInstanceId", equalTo(instanceId))
                .willReturn(okJson("{\"data\":[],\"total\":0,\"start\":0,\"size\":50}")));
    }

    private static FlagFinding finding(StatusEvidence evidence, String flag) {
        return evidence.findings().stream()
                .filter(f -> f.flag().equals(flag))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding for flag " + flag));
    }
}
