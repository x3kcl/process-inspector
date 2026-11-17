package io.inspector.migration;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.CaseMigrationDocument;
import io.inspector.client.CaseMigrationRoute;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.GuardedCaller;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.RecentEngineErrors;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Characterization of the MIGRATION request bodies (#720): the exact bytes the BFF sends for a
 * process-instance migration ({@code POST /runtime/process-instances/{id}/migrate} — the only
 * process migration call; Flowable REST has no validator and the BFF sends no by-definition
 * batch, a cohort is this call per instance) and for the six CMMN migration routes. Each process
 * case pins what the client puts on the wire, what the typed document serialises to, and that the
 * map copy the preview and the audit row carry is the same text.
 *
 * <p>The top-level key is {@code activityMappings}, never {@code activityMigrationMappings}
 * (docs/INSTANCE-MIGRATION.md). {@code activityMappings} is SENT as {@code []} when there is no
 * override, never left out. A mapping carries exactly the two keys of its form — the engine's
 * converter discriminates the form by which keys are present, so an unused key is absent, never
 * {@code null} and never {@code []}. The mapping LIST keeps the caller's order: it is the order
 * the engine applies and the order the audit row records.
 *
 * <p>Key order: the process document and its mappings were insertion-ordered maps on
 * {@code main}, so they are compared as strings. The case body has one key, so a {@code Map.of}
 * had no order to salt; it is compared as a string too.
 */
class MigrationBodyWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TO = "orderProcess:2:def-2";

    private WireMockServer wm;
    private ProcessApiClient client;
    private CmmnApiClient cmmn;
    private EngineConfig engine;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(any(anyUrl()).willReturn(noContent()));
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        GuardedCaller guarded = new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC()));
        client = new ProcessApiClient(guarded);
        cmmn = new CmmnApiClient(guarded);
        engine = TestEngines.engine(
                "t",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(1000, 1000, null));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void noOverrideSendsTheTargetAndAnEmptyMappingArrayNotAnAbsentKey() {
        pinsProcess(List.of(), "{\"toProcessDefinitionId\":\"" + TO + "\",\"activityMappings\":[]}");
    }

    @Test
    void oneToOneMappingSendsFromActivityIdThenToActivityIdAndNothingElse() {
        pinsProcess(
                List.of(MigrationMapping.oneToOne("reviewTask", "approveTask")),
                "{\"toProcessDefinitionId\":\"" + TO + "\",\"activityMappings\":["
                        + "{\"fromActivityId\":\"reviewTask\",\"toActivityId\":\"approveTask\"}]}");
    }

    @Test
    void manyToOneMappingSendsFromActivityIdsThenToActivityId() {
        pinsProcess(
                List.of(new MigrationMapping(null, List.of("b", "a"), "merged", null)),
                "{\"toProcessDefinitionId\":\"" + TO + "\",\"activityMappings\":["
                        + "{\"fromActivityIds\":[\"b\",\"a\"],\"toActivityId\":\"merged\"}]}");
    }

    @Test
    void oneToManyMappingSendsFromActivityIdThenToActivityIds() {
        pinsProcess(
                List.of(new MigrationMapping("split", null, null, List.of("y", "x"))),
                "{\"toProcessDefinitionId\":\"" + TO + "\",\"activityMappings\":["
                        + "{\"fromActivityId\":\"split\",\"toActivityIds\":[\"y\",\"x\"]}]}");
    }

    @Test
    void anEmptyListOrBlankIdOfTheUnusedFormIsAbsentNotAnEmptyArrayOrNull() {
        pinsProcess(
                List.of(
                        new MigrationMapping("a", List.of(), "b", List.of()),
                        new MigrationMapping(" ", List.of("c", "d"), "e", null),
                        new MigrationMapping("f", null, "", List.of("g"))),
                "{\"toProcessDefinitionId\":\"" + TO + "\",\"activityMappings\":["
                        + "{\"fromActivityId\":\"a\",\"toActivityId\":\"b\"},"
                        + "{\"fromActivityIds\":[\"c\",\"d\"],\"toActivityId\":\"e\"},"
                        + "{\"fromActivityId\":\"f\",\"toActivityIds\":[\"g\"]}]}");
    }

    @Test
    void theMappingListKeepsTheCallersOrderAcrossForms() {
        pinsProcess(
                List.of(
                        MigrationMapping.oneToOne("zeta", "z2"),
                        new MigrationMapping(null, List.of("m2", "m1"), "merged", null),
                        MigrationMapping.oneToOne("alpha", "a2"),
                        new MigrationMapping("split", null, null, List.of("s2", "s1")),
                        MigrationMapping.oneToOne("mid", "mid2")),
                "{\"toProcessDefinitionId\":\"" + TO + "\",\"activityMappings\":["
                        + "{\"fromActivityId\":\"zeta\",\"toActivityId\":\"z2\"},"
                        + "{\"fromActivityIds\":[\"m2\",\"m1\"],\"toActivityId\":\"merged\"},"
                        + "{\"fromActivityId\":\"alpha\",\"toActivityId\":\"a2\"},"
                        + "{\"fromActivityId\":\"split\",\"toActivityIds\":[\"s2\",\"s1\"]},"
                        + "{\"fromActivityId\":\"mid\",\"toActivityId\":\"mid2\"}]}");
    }

    @Test
    void aMalformedMappingIsRefusedWhenTheTypedDocumentIsBuilt() {
        assertThatThrownBy(() ->
                        MigrationMapping.document(TO, List.of(new MigrationMapping("a", List.of("b"), "c", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of fromActivityId or fromActivityIds");
        assertThatThrownBy(() -> MigrationMapping.document(
                        TO, List.of(new MigrationMapping(null, List.of("a", "b"), null, List.of("c", "d")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("many-to-many");
    }

    @Test
    void singleCaseMigrateSendsOnlyTheTargetCaseDefinitionOn68AndOn7() {
        MigrationWire.migrateCase(cmmn, engine, "6.8.0", CaseMigrationRoute.SINGLE, "case-1", "caseDef-2");
        MigrationWire.migrateCase(cmmn, engine, "7.1.0", CaseMigrationRoute.SINGLE, "case-2", "caseDef-2");

        List<LoggedRequest> sent = sent();
        assertThat(sent).hasSize(2);
        SoftAssertions soft = new SoftAssertions();
        for (LoggedRequest request : sent) {
            soft.assertThat(request.getMethod().getName()).isEqualTo("POST");
            soft.assertThat(request.getUrl()).startsWith("/cmmn-api/cmmn-runtime/case-instances/case-");
            soft.assertThat(request.getBodyAsString()).isEqualTo("{\"toCaseDefinitionId\":\"caseDef-2\"}");
        }
        soft.assertAll();
    }

    @Test
    void everySevenOnlyCaseRouteSendsTheSameOneKeyBodyToItsOwnPath() {
        Map<CaseMigrationRoute, String> paths = Map.of(
                CaseMigrationRoute.HISTORIC, "/cmmn-api/cmmn-history/historic-case-instances/id-1/migrate",
                CaseMigrationRoute.BY_DEFINITION, "/cmmn-api/cmmn-repository/case-definitions/id-1/migrate",
                CaseMigrationRoute.BY_DEFINITION_BATCH, "/cmmn-api/cmmn-repository/case-definitions/id-1/batch-migrate",
                CaseMigrationRoute.BY_DEFINITION_HISTORIC,
                        "/cmmn-api/cmmn-repository/case-definitions/id-1/migrate-historic-instances",
                CaseMigrationRoute.BY_DEFINITION_BATCH_HISTORIC,
                        "/cmmn-api/cmmn-repository/case-definitions/id-1/batch-migrate-historic-instances");

        SoftAssertions soft = new SoftAssertions();
        for (Map.Entry<CaseMigrationRoute, String> route : paths.entrySet()) {
            wm.resetRequests();
            MigrationWire.migrateCase(cmmn, engine, "7.1.0", route.getKey(), "id-1", "caseDef-2");
            List<LoggedRequest> sent = sent();
            soft.assertThat(sent).as(route.getKey().name()).hasSize(1);
            soft.assertThat(sent.get(0).getMethod().getName()).isEqualTo("POST");
            soft.assertThat(sent.get(0).getUrl()).isEqualTo(route.getValue());
            soft.assertThat(sent.get(0).getBodyAsString()).isEqualTo("{\"toCaseDefinitionId\":\"caseDef-2\"}");
        }
        soft.assertAll();
        assertThat(paths).hasSize(CaseMigrationRoute.values().length - 1);
    }

    @Test
    void theCaseDocumentSerialisesToTheOneKeyAndRefusesANullTarget() throws JsonProcessingException {
        assertThat(MAPPER.writeValueAsString(new CaseMigrationDocument("caseDef-2")))
                .isEqualTo("{\"toCaseDefinitionId\":\"caseDef-2\"}");
        // main's Map.of threw on a null value, so a body with "toCaseDefinitionId":null never left.
        assertThatThrownBy(() -> new CaseMigrationDocument(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toCaseDefinitionId");
    }

    /** One migrate call: the bytes on the wire, the typed document, and the preview / audit map. */
    private void pinsProcess(List<MigrationMapping> overrides, String body) {
        MigrationWire.migrate(client, engine, "pi-1", TO, overrides);

        List<LoggedRequest> sent = sent();
        assertThat(sent).hasSize(1);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(sent.get(0).getMethod().getName()).isEqualTo("POST");
        soft.assertThat(sent.get(0).getUrl()).isEqualTo("/runtime/process-instances/pi-1/migrate");
        soft.assertThat(sent.get(0).getBodyAsString()).as("body on the wire").isEqualTo(body);
        try {
            soft.assertThat(MAPPER.writeValueAsString(MigrationMapping.document(TO, overrides)))
                    .as("MigrationDocument serialised")
                    .isEqualTo(body);
            soft.assertThat(MAPPER.writeValueAsString(MigrationWire.restBody(TO, overrides)))
                    .as("preview / audit map copy")
                    .isEqualTo(body);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
        soft.assertAll();
    }

    private List<LoggedRequest> sent() {
        return wm.getAllServeEvents().stream().map(e -> e.getRequest()).toList();
    }
}
