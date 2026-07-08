package io.inspector.cmmn;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Rung 4 (Case Inspector Phase 2): the polymorphic CMMN case-detail endpoints, proven against
 * the REAL flowable-6 (6.8) engine — no mocked wire (engine-harness). Seeds the deliberately-
 * failing case, awaits its async service task dead-lettering, then asserts the three read-only
 * detail surfaces:
 *
 * <ul>
 *   <li><b>vitals</b> — historic-first: {@code state==ACTIVE}, the bare-uuid caseDefinitionId
 *       resolved to {@code demoFailingCase}, and a {@code failing} summary (≥1 dead-letter job);</li>
 *   <li><b>plan-items</b> — the RUNTIME timeline (Q6): the servicetask plan item carries
 *       {@code liveJobState==FAILED} (joined by {@code planItemInstanceId}, NOT elementId — Q7),
 *       the humanTask keeps the case alive;</li>
 *   <li><b>diagram</b> — the raw CMMN XML, {@code graphicalNotationDefined==false} (the seed has
 *       no CMMNDI — exercising the honest no-layout path), and the FAILED marker keyed by the plan
 *       item's {@code elementId} ({@code planItem_svc}), never the job's ({@code failingService}).</li>
 * </ul>
 *
 * Hermetic teardown: the case is terminated and its deployment cascade-deleted so no dead-lettered
 * CMMN case survives to skew a parallel session's pristine DLQ counts.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml up -d   (flowable-6 profile)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_A_PASSWORD=test")
@ActiveProfiles("it-triage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class CaseDetailIT {

    private static final String ENGINE = "http://localhost:8081/flowable-rest/service";
    private static final String CASE_KEY = "demoFailingCase";

    private static final WireMockServer proxy = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void registerProxy(DynamicPropertyRegistry registry) {
        proxy.start();
        proxy.stubFor(any(anyUrl()).willReturn(aResponse().proxiedFrom("http://localhost:8081")));
        registry.add("TRIAGE_PROXY_PORT", () -> proxy.port());
    }

    @Autowired
    TestRestTemplate rest;

    private RestClient cmmn;
    private String caseInstanceId;

    @BeforeAll
    void seedFailingCase() {
        rest = rest.withBasicAuth("admin", "dev"); // VIEWER floor — admin clears it
        EngineSeed.requireReachable(ENGINE, "");
        cmmn = EngineSeed.cmmnClient(ENGINE);

        EngineSeed.deployCmmnIfMissing(cmmn, CASE_KEY, EngineSeed.FAILING_CASE_CMMN);
        caseInstanceId = EngineSeed.startFailingCase(cmmn, CASE_KEY);

        // Await THIS run's case dead-lettering (keyed on its own caseInstanceId, not the shared
        // failing-expression needle, so a parallel session's same-seed residue can't short-circuit
        // the wait). Engine truth, never a sleep. The CMMN engine ignores failedJobRetryTimeCycle,
        // so it dead-letters via the default retry budget (~30-40s, measured live).
        await().atMost(75, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> EngineSeed.cmmnDeadletterPresentForCase(cmmn, caseInstanceId));

        // The detail endpoints capability-gate on scopeType, sourced from the scheduled M1 probe —
        // wait one cycle so the registry reports the engine reachable + capable before we read.
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(rest.getForObject("/api/engines", JsonNode.class)
                                .get(0)
                                .get("reachable")
                                .asBoolean())
                        .isTrue());
    }

    @AfterAll
    void teardown() {
        if (cmmn != null) {
            EngineSeed.deleteCaseQuietly(cmmn, caseInstanceId);
            EngineSeed.deleteCmmnDeploymentsQuietly(cmmn, CASE_KEY);
        }
        proxy.stop();
    }

    @Test
    void vitalsResolveTheCaseTypeAndSurfaceTheFailure() {
        JsonNode body = rest.getForObject("/api/cases/engine-a/" + caseInstanceId, JsonNode.class);

        assertThat(body.get("present").asBoolean()).isTrue();
        assertThat(body.get("ended").asBoolean())
                .as("the humanTask keeps the case active")
                .isFalse();
        assertThat(body.get("state").asText()).isEqualTo("ACTIVE");
        // the bare-uuid caseDefinitionId resolved to a readable key/name
        assertThat(body.get("caseDefinitionKey").asText()).isEqualTo(CASE_KEY);
        assertThat(body.get("caseDefinitionName").asText()).isEqualTo("Demo failing case");
        // the "why stuck" summary — at least the one seeded dead-letter job
        assertThat(body.get("failing").get("deadLetterJobCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(body.get("failing").get("firstException").asText()).contains("nonExistentBean");
    }

    @Test
    void planItemTimelineCarriesTheFailedServiceTaskJoinedByPlanItemInstanceId() {
        JsonNode body = rest.getForObject("/api/cases/engine-a/" + caseInstanceId + "/plan-items", JsonNode.class);

        assertThat(body.get("available").asBoolean())
                .as("a running case has a runtime timeline")
                .isTrue();

        JsonNode planItems = body.get("planItems");
        JsonNode servicePlanItem = null;
        boolean humanTaskAlive = false;
        for (JsonNode pi : planItems) {
            String type = pi.get("planItemDefinitionType").asText();
            if ("servicetask".equals(type)) {
                servicePlanItem = pi;
            }
            if ("humantask".equals(type) && "active".equals(pi.get("state").asText())) {
                humanTaskAlive = true;
            }
        }

        assertThat(servicePlanItem)
                .as("the failing service plan item is present")
                .isNotNull();
        // Q7: the FAILED annotation is joined by planItemInstanceId — proving it did NOT try to
        // match the job's elementId ("failingService") against the plan item's ("planItem_svc").
        assertThat(servicePlanItem.get("liveJobState").asText()).isEqualTo("FAILED");
        assertThat(servicePlanItem.get("elementId").asText()).isEqualTo("planItem_svc");
        assertThat(humanTaskAlive).as("the humanTask keeps the case alive").isTrue();
    }

    @Test
    void diagramReturnsRawCmmnXmlAndMarksTheFailedPlanItemByItsElementId() {
        JsonNode body = rest.getForObject("/api/cases/engine-a/" + caseInstanceId + "/diagram", JsonNode.class);

        assertThat(body.get("xml").asText()).contains("<case").contains("casePlanModel");
        // the seed carries no CMMNDI — the honest no-layout path (Q5)
        assertThat(body.get("graphicalNotationDefined").asBoolean()).isFalse();
        // the FAILED marker is keyed by the plan item's elementId (the DI shape key), not the job's
        assertThat(body.get("failedPlanItemElementIds"))
                .anySatisfy(id -> assertThat(id.asText()).isEqualTo("planItem_svc"));
    }
}
