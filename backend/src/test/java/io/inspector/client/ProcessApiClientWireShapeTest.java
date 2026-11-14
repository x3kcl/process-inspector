package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Rung 2 (#464): every {@link ProcessApiClient} facade call against a WireMock engine that serves
 * RECORDED flowable-rest responses — {@code src/test/resources/wire/{6.8,7.1}/}, captured from
 * throwaway {@code flowable/flowable-rest:6.8.0} / {@code 7.1.0} containers seeded over REST with the
 * {@code docker/processes} demo set ({@code scripts/record-wire-fixtures.sh}). Each read asserts the
 * request the facade puts on the wire (verb, path, the query params that carry meaning) and that the
 * real engine document parses into the returned type unchanged; each mutation asserts verb, path and
 * body. No join semantics are proven here — those stay on the dockerized {@code *IT} rung.
 */
class ProcessApiClientWireShapeTest {

    private static final List<String> MAJORS = List.of("6.8", "7.1");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer wm;
    private ProcessApiClient client;
    private EngineConfig engine;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        CircuitBreakerConfig breaker = CircuitBreakerConfig.custom()
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        client = new ProcessApiClient(new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", breaker)),
                BulkheadRegistry.of(Map.of(
                        "engine",
                        BulkheadConfig.custom()
                                .maxConcurrentCalls(8)
                                .maxWaitDuration(Duration.ofSeconds(5))
                                .build())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC())));
        engine = TestEngines.engine(
                "wire-engine",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(2000, 2000, null));
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static String text(String major, String name) {
        try (InputStream in = ProcessApiClientWireShapeTest.class.getResourceAsStream("/wire/" + major + "/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture wire/" + major + "/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object json(String major, String name) {
        try {
            return JSON.readValue(text(major, name + ".json"), Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> doc(String major, String name) {
        return (Map<String, Object>) json(major, name);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(String major, String name) {
        return (List<Map<String, Object>>) doc(major, name).get("data");
    }

    private static String firstId(String major, String name) {
        return String.valueOf(rows(major, name).get(0).get("id"));
    }

    /**
     * The path as it reaches the wire: RestClient's URI template encoding escapes the {@code :} in a
     * {@code key:version:uuid} definition id (the engine decodes it), and WireMock matches the raw path.
     */
    private static String enc(String path) {
        return path.replace(":", "%3A");
    }

    /** A read case: stub {@code method path} with a recorded document, call, compare, verify. */
    private record Read(
            String name,
            String method,
            String path,
            String fixture,
            Function<ProcessApiClient, Object> call,
            Consumer<RequestPatternBuilder> extraMatch) {}

    private static Read read(
            String name, String method, String path, String fixture, Function<ProcessApiClient, Object> call) {
        return new Read(name, method, path, fixture, call, r -> {});
    }

    private List<Read> reads(String v) {
        EngineConfig e = engine;
        CallPriority p = CallPriority.INTERACTIVE;
        String pid = String.valueOf(doc(v, "historic-process-instance").get("id"));
        String defId = firstId(v, "process-definitions-by-key");
        String deploymentId = String.valueOf(doc(v, "deployment").get("id"));
        String dlqJob = firstId(v, "deadletter-jobs");
        String taskId = String.valueOf(doc(v, "task").get("id"));
        String executionId = String.valueOf(doc(v, "execution").get("id"));
        return List.of(
                new Read(
                        "queryHistoricProcessInstances",
                        "POST",
                        "/query/historic-process-instances",
                        "query-historic-process-instances",
                        c -> c.queryHistoricProcessInstances(e, p, Map.of("finished", true, "size", 1)),
                        r -> r.withRequestBody(equalToJson("{\"finished\":true,\"size\":1}"))),
                new Read(
                        "queryRuntimeProcessInstances",
                        "POST",
                        "/query/process-instances",
                        "query-process-instances",
                        c -> c.queryRuntimeProcessInstances(e, p, Map.of("suspended", false, "size", 1)),
                        r -> r.withRequestBody(equalToJson("{\"suspended\":false,\"size\":1}"))),
                new Read(
                        "listJobs(DEADLETTER)",
                        "GET",
                        "/management/deadletter-jobs",
                        "deadletter-jobs",
                        c -> c.listJobs(e, p, JobLaneKind.DEADLETTER, Map.of("withException", "true"), 0, 50),
                        r -> r.withQueryParam("withException", equalTo("true"))
                                .withQueryParam("start", equalTo("0"))
                                .withQueryParam("size", equalTo("50"))),
                new Read(
                        "listJobs(TIMER)",
                        "GET",
                        "/management/timer-jobs",
                        "timer-jobs",
                        c -> c.listJobs(e, p, JobLaneKind.TIMER, Map.of("withException", "true"), 0, 50),
                        r -> r.withQueryParam("withException", equalTo("true"))),
                read(
                        "getHistoricProcessInstance",
                        "GET",
                        "/history/historic-process-instances/" + pid,
                        "historic-process-instance",
                        c -> c.getHistoricProcessInstance(e, p, pid)),
                read(
                        "getRuntimeProcessInstance",
                        "GET",
                        "/runtime/process-instances/" + pid,
                        "runtime-process-instance",
                        c -> c.getRuntimeProcessInstance(e, p, pid)),
                new Read(
                        "listUnfinishedActivities",
                        "GET",
                        "/history/historic-activity-instances",
                        "historic-activity-instances",
                        c -> c.listUnfinishedActivities(e, p, pid, "tenant-x", 50),
                        r -> r.withQueryParam("processInstanceId", equalTo(pid))
                                .withQueryParam("finished", equalTo("false"))
                                .withQueryParam("tenantId", equalTo("tenant-x"))),
                new Read(
                        "listProcessDefinitionsByKey",
                        "GET",
                        "/repository/process-definitions",
                        "process-definitions-by-key",
                        c -> c.listProcessDefinitionsByKey(e, p, "demoFailingPayment", 1, 5, 50),
                        r -> r.withQueryParam("key", equalTo("demoFailingPayment"))
                                .withQueryParam("version", equalTo("1"))
                                .withQueryParam("start", equalTo("5"))),
                new Read(
                        "listProcessDefinitionVersionsDesc",
                        "GET",
                        "/repository/process-definitions",
                        "process-definitions-by-key",
                        c -> c.listProcessDefinitionVersionsDesc(e, p, "demoFailingPayment", 0, 50),
                        r -> r.withQueryParam("sort", equalTo("version"))),
                new Read(
                        "listLatestProcessDefinitions",
                        "GET",
                        "/repository/process-definitions",
                        "process-definitions-by-key",
                        c -> c.listLatestProcessDefinitions(e, p, 50),
                        r -> r.withQueryParam("latest", equalTo("true"))),
                read(
                        "listExecutions",
                        "GET",
                        "/runtime/executions",
                        "executions",
                        c -> c.listExecutions(e, p, pid, 50)),
                read(
                        "listRuntimeIdentityLinks",
                        "GET",
                        "/runtime/process-instances/" + pid + "/identitylinks",
                        "runtime-identity-links",
                        c -> c.listRuntimeIdentityLinks(e, p, pid)),
                read(
                        "listHistoricIdentityLinks",
                        "GET",
                        "/history/historic-process-instances/" + pid + "/identitylinks",
                        "historic-identity-links",
                        c -> c.listHistoricIdentityLinks(e, p, pid)),
                read(
                        "listInstanceVariables",
                        "GET",
                        "/runtime/process-instances/" + pid + "/variables",
                        "variables",
                        c -> c.listInstanceVariables(e, p, pid)),
                new Read(
                        "listExecutionLocalVariables",
                        "GET",
                        "/runtime/executions/" + executionId + "/variables",
                        "execution-local-variables",
                        c -> c.listExecutionLocalVariables(e, p, executionId),
                        r -> r.withQueryParam("scope", equalTo("local"))),
                read(
                        "listHistoricVariableInstances",
                        "GET",
                        "/history/historic-variable-instances",
                        "historic-variable-instances",
                        c -> c.listHistoricVariableInstances(e, p, pid, 50)),
                read(
                        "listHistoricActivities",
                        "GET",
                        "/history/historic-activity-instances",
                        "historic-activity-instances",
                        c -> c.listHistoricActivities(e, p, pid, 0, 50)),
                read(
                        "listEventSubscriptions",
                        "GET",
                        "/runtime/event-subscriptions",
                        "event-subscriptions",
                        c -> c.listEventSubscriptions(e, p, pid, 50)),
                read(
                        "listHistoricTaskInstances",
                        "GET",
                        "/history/historic-task-instances",
                        "historic-task-instances",
                        c -> c.listHistoricTaskInstances(e, p, pid, 50)),
                new Read(
                        "listRuntimeTasks(instance)",
                        "GET",
                        "/runtime/tasks",
                        "tasks",
                        c -> c.listRuntimeTasks(e, p, pid, 50),
                        r -> r.withQueryParam("processInstanceId", equalTo(pid))),
                new Read(
                        "listRuntimeTasks(query, blanks dropped)",
                        "GET",
                        "/runtime/tasks",
                        "tasks",
                        c -> c.listRuntimeTasks(
                                e, p, new java.util.HashMap<>(Map.of("candidateGroup", "hr", "assignee", " ")), 50),
                        r -> r.withQueryParam("candidateGroup", equalTo("hr")).withoutQueryParam("assignee")),
                read(
                        "getHistoricTaskInstance",
                        "GET",
                        "/history/historic-task-instances/" + taskId,
                        "historic-task-instance",
                        c -> c.getHistoricTaskInstance(e, p, taskId)),
                read("engineInfo", "GET", "/management/engine", "engine", c -> c.engineInfo(e, p)),
                read("getMap", "GET", "/management/engine", "engine", c -> c.getMap(e, p, "/management/engine")),
                read(
                        "getList",
                        "GET",
                        "/management/tables",
                        "management-tables",
                        c -> c.getList(e, p, "/management/tables")),
                read(
                        "getJob",
                        "GET",
                        "/management/deadletter-jobs/" + dlqJob,
                        "deadletter-job",
                        c -> c.getJob(e, p, JobLaneKind.DEADLETTER, dlqJob)),
                read(
                        "getInstanceVariable",
                        "GET",
                        "/runtime/process-instances/" + pid + "/variables/amount",
                        "variable",
                        c -> c.getInstanceVariable(e, p, pid, "amount")),
                read("getTask", "GET", "/runtime/tasks/" + taskId, "task", c -> c.getTask(e, p, taskId)),
                read(
                        "listIdentityLinks(task)",
                        "GET",
                        "/runtime/tasks/" + taskId + "/identitylinks",
                        "task-identity-links",
                        c -> c.listIdentityLinks(e, p, taskId)),
                read(
                        "getExecution",
                        "GET",
                        "/runtime/executions/" + executionId,
                        "execution",
                        c -> c.getExecution(e, p, executionId)),
                read(
                        "getProcessDefinition",
                        "GET",
                        "/repository/process-definitions/" + defId,
                        "process-definition",
                        c -> c.getProcessDefinition(e, p, defId)),
                read(
                        "getProcessDefinitionModel",
                        "GET",
                        "/repository/process-definitions/" + defId + "/model",
                        "process-definition-model",
                        c -> c.getProcessDefinitionModel(e, p, defId)),
                new Read(
                        "listProcessDefinitions",
                        "GET",
                        "/repository/process-definitions",
                        "process-definitions-by-key",
                        c -> c.listProcessDefinitions(e, p, "demoFailingPayment", "%Pay%", true, deploymentId, 0, 50),
                        r -> r.withQueryParam("key", equalTo("demoFailingPayment"))
                                .withQueryParam("deploymentId", equalTo(deploymentId))),
                read(
                        "listDefinitionEventSubscriptions",
                        "GET",
                        "/runtime/event-subscriptions",
                        "event-subscriptions",
                        c -> c.listDefinitionEventSubscriptions(e, p, defId, 50)),
                read(
                        "listDeployments",
                        "GET",
                        "/repository/deployments",
                        "deployments",
                        c -> c.listDeployments(e, p, "%demo%", 0, 50)),
                read(
                        "getDeployment",
                        "GET",
                        "/repository/deployments/" + deploymentId,
                        "deployment",
                        c -> c.getDeployment(e, p, deploymentId)),
                read(
                        "listDeploymentResources",
                        "GET",
                        "/repository/deployments/" + deploymentId + "/resources",
                        "deployment-resources",
                        c -> c.listDeploymentResources(e, p, deploymentId)),
                new Read(
                        "listRuntimeInstances",
                        "GET",
                        "/runtime/process-instances",
                        "query-process-instances",
                        c -> c.listRuntimeInstances(e, p, defId, "bk-1", null, "2026-01-01T00:00:00Z", "", 50),
                        r -> r.withQueryParam("processDefinitionId", equalTo(defId))
                                .withQueryParam("businessKey", equalTo("bk-1"))
                                .withQueryParam("startedAfter", equalTo("2026-01-01T00:00:00Z"))
                                .withoutQueryParam("businessKeyLike")
                                .withoutQueryParam("startedBefore")),
                new Read(
                        "listFinishedProcesses",
                        "GET",
                        "/history/historic-process-instances",
                        "query-historic-process-instances",
                        c -> c.listFinishedProcesses(e, p, "demoOrder", null, "2026-01-01T00:00:00Z", 0, 50),
                        r -> r.withQueryParam("finished", equalTo("true"))
                                .withQueryParam("processDefinitionKey", equalTo("demoOrder"))
                                .withoutQueryParam("businessKey")));
    }

    @TestFactory
    Stream<DynamicTest> everyFacadeReadParsesTheRecordedEngineDocument() {
        return MAJORS.stream()
                .flatMap(v -> reads(v).stream()
                        .map(c -> dynamicTest(v + " " + c.name(), () -> {
                            wm.resetAll();
                            String body = text(v, c.fixture() + ".json");
                            wm.stubFor(request(c.method(), urlPathEqualTo(enc(c.path())))
                                    .willReturn(okJson(body)));

                            Object result = c.call().apply(client);

                            Object recorded = json(v, c.fixture());
                            if (result instanceof FlowablePage page && recorded instanceof List<?> bareArray) {
                                // e.g. /repository/deployments/{id}/resources: the engine answers a bare array.
                                assertThat(page.dataOrEmpty()).isEqualTo(bareArray);
                                assertThat(page.total()).isEqualTo(bareArray.size());
                            } else if (result instanceof FlowablePage page) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> recordedPage = (Map<String, Object>) recorded;
                                assertThat(page.total()).isEqualTo(((Number) recordedPage.get("total")).longValue());
                                assertThat(page.dataOrEmpty()).isEqualTo(recordedPage.get("data"));
                            } else if (result instanceof JobPage page) {
                                // Typed family (#474): compared field-for-field against the typed recording.
                                @SuppressWarnings("unchecked")
                                Map<String, Object> recordedPage = (Map<String, Object>) recorded;
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> recordedRows =
                                        (List<Map<String, Object>>) recordedPage.get("data");
                                assertThat(page.total()).isEqualTo(((Number) recordedPage.get("total")).longValue());
                                assertThat(page.dataOrEmpty()).isEqualTo(JobRecords.jobs(recordedRows));
                            } else if (result instanceof JobRecord job) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> recordedJob = (Map<String, Object>) recorded;
                                assertThat(job).isEqualTo(JobRecords.job(recordedJob));
                            } else {
                                assertThat(result).isEqualTo(recorded);
                            }
                            RequestPatternBuilder sent = new RequestPatternBuilder(
                                    com.github.tomakehurst.wiremock.http.RequestMethod.fromString(c.method()),
                                    urlPathEqualTo(enc(c.path())));
                            c.extraMatch().accept(sent);
                            wm.verify(1, sent);
                        })));
    }

    @TestFactory
    Stream<DynamicTest> textAndCountReadsUseTheRecordedEngineAnswers() {
        return MAJORS.stream()
                .map(v -> dynamicTest(v, () -> {
                    String defId = firstId(v, "process-definitions-by-key");
                    String deploymentId = String.valueOf(doc(v, "deployment").get("id"));
                    String dlqJob = firstId(v, "deadletter-jobs");
                    String xml = text(v, "process-definition-resourcedata.bpmn20.xml");
                    String stack = text(v, "deadletter-exception-stacktrace.txt");
                    wm.resetAll();
                    wm.stubFor(request(
                                    "GET",
                                    urlPathEqualTo("/management/deadletter-jobs/" + dlqJob + "/exception-stacktrace"))
                            .willReturn(ok(stack).withHeader("Content-Type", "application/octet-stream")));
                    wm.stubFor(request(
                                    "GET",
                                    urlPathEqualTo(enc("/repository/process-definitions/" + defId + "/resourcedata")))
                            .willReturn(ok(xml).withHeader("Content-Type", "application/octet-stream")));
                    wm.stubFor(request(
                                    "GET",
                                    urlPathEqualTo("/repository/deployments/" + deploymentId
                                            + "/resourcedata/processes/demo-failing-payment.bpmn20.xml"))
                            .willReturn(ok(xml).withHeader("Content-Type", "application/octet-stream")));
                    String deadletters = text(v, "deadletter-jobs.json");
                    long dlqTotal = ((Number) doc(v, "deadletter-jobs").get("total")).longValue();
                    wm.stubFor(request("GET", urlPathEqualTo("/management/deadletter-jobs"))
                            .willReturn(okJson(deadletters)));
                    wm.stubFor(request("GET", urlPathEqualTo("/management/history-jobs"))
                            .willReturn(okJson(deadletters)));
                    wm.stubFor(request("GET", urlPathEqualTo("/management/jobs"))
                            .willReturn(okJson(text(v, "jobs.json"))));
                    wm.stubFor(request("GET", urlPathEqualTo("/history/historic-process-instances"))
                            .willReturn(okJson(text(v, "query-historic-process-instances.json"))));
                    wm.stubFor(request("GET", urlPathEqualTo("/history/historic-task-instances"))
                            .willReturn(okJson(text(v, "historic-task-instances.json"))));
                    wm.stubFor(request("GET", urlPathEqualTo("/history/historic-activity-instances"))
                            .willReturn(okJson(text(v, "historic-activity-instances.json"))));
                    wm.stubFor(request("GET", urlPathEqualTo("/identity/users")).willReturn(okJson("{\"data\":[]}")));
                    wm.stubFor(request("GET", urlPathEqualTo("/identity/users/rest-admin"))
                            .willReturn(ok()));
                    CallPriority p = CallPriority.INTERACTIVE;
                    Instant after = Instant.parse("2026-01-01T00:00:00Z");
                    Instant before = Instant.parse("2026-12-31T00:00:00Z");
                    long historicTotal =
                            ((Number) doc(v, "query-historic-process-instances").get("total")).longValue();

                    assertThat(client.jobExceptionStacktrace(engine, p, JobLaneKind.DEADLETTER, dlqJob))
                            .isEqualTo(stack)
                            .startsWith("org.flowable");
                    // Recorded engines serve XML/stacktraces as octet-stream with no charset: the BPMN's
                    // non-ASCII comment survives only because the client decodes UTF-8 (#464 bug fix).
                    assertThat(xml).contains("\u2014");
                    assertThat(client.processDefinitionResourceData(engine, p, defId))
                            .isEqualTo(xml);
                    // A nested resource name is encoded segment-by-segment, never as one %2F segment.
                    assertThat(client.deploymentResourceData(
                                    engine, p, deploymentId, "processes/demo-failing-payment.bpmn20.xml"))
                            .isEqualTo(xml);
                    assertThat(client.countJobsIfSupported(engine, p, JobLaneKind.DEADLETTER))
                            .isEqualTo(dlqTotal);
                    assertThat(client.oldestHistoryJob(engine, p))
                            .isEqualTo(JobRecords.job(rows(v, "deadletter-jobs").get(0)));
                    assertThat(client.oldestExecutableJob(engine, p)).isNull(); // the recorded lane is empty
                    assertThat(client.countHistoricProcesses(engine, p, after, before, true, true))
                            .isEqualTo(historicTotal);
                    assertThat(client.countHistoricProcesses(engine, p, after, before, false, false, "demoOrder"))
                            .isEqualTo(historicTotal);
                    assertThat(client.countHistoricTasks(engine, p, after, before, true))
                            .isNotNull();
                    assertThat(client.probeActivityHistory(engine, p)).isTrue();
                    assertThat(client.probeIdm(engine, p)).isTrue();
                    assertThat(client.idmIdentityExists(engine, p, "users", "rest-admin"))
                            .isTrue();

                    wm.verify(requestedFor("GET", urlPathEqualTo("/history/historic-process-instances"))
                            .withQueryParam("startedAfter", equalTo("2026-01-01T00:00:00Z"))
                            .withQueryParam("excludeSubprocesses", equalTo("true")));
                    wm.verify(requestedFor("GET", urlPathEqualTo("/history/historic-process-instances"))
                            .withQueryParam("finishedBefore", equalTo("2026-12-31T00:00:00Z"))
                            .withQueryParam("processDefinitionKey", equalTo("demoOrder")));
                    wm.verify(requestedFor("GET", urlPathEqualTo("/management/history-jobs"))
                            .withQueryParam("sort", equalTo("createTime"))
                            .withQueryParam("order", equalTo("asc")));
                }));
    }

    @Test
    void notFoundAnswersBecomeNullOrFalseNeverAnException() {
        // 404 shape verified on both recorded engines (unknown dead-letter job id → 404).
        wm.stubFor(request("GET", anyUrl()).willReturn(notFound()));
        CallPriority p = CallPriority.INTERACTIVE;

        assertThat(client.getJob(engine, p, JobLaneKind.DEADLETTER, "nope")).isNull();
        assertThat(client.jobExceptionStacktrace(engine, p, JobLaneKind.TIMER, "nope"))
                .isNull();
        assertThat(client.countJobsIfSupported(engine, p, JobLaneKind.HISTORY)).isNull();
        assertThat(client.oldestHistoryJob(engine, p)).isNull();
        assertThat(client.countHistoricProcesses(engine, p, Instant.EPOCH, Instant.EPOCH, true, false))
                .isNull();
        assertThat(client.deploymentResourceData(engine, p, "d", "x.bpmn")).isNull();
        assertThat(client.getExecution(engine, p, "nope")).isNull();
        assertThat(client.getExecutionVariable(engine, p, "nope", "v")).isNull();
        assertThat(client.getProcessDefinitionModel(engine, p, "nope")).isNull();
        assertThat(client.idmIdentityExists(engine, p, "users", "ghost")).isFalse();
        assertThat(client.probeIdm(engine, p)).isFalse();
        assertThat(client.probeActivityHistory(engine, p)).isFalse();
    }

    /* ------------------------------ mutations ------------------------------ */

    private record Write(String name, String method, String path, String jsonBody, Consumer<ProcessApiClient> call) {}

    private List<Write> writes(String v) {
        EngineConfig e = engine;
        CallPriority p = CallPriority.INTERACTIVE;
        String pid = String.valueOf(doc(v, "historic-process-instance").get("id"));
        String job = firstId(v, "deadletter-jobs");
        String timer = firstId(v, "timer-jobs");
        String taskId = String.valueOf(doc(v, "task").get("id"));
        String executionId = String.valueOf(doc(v, "execution").get("id"));
        String defId = firstId(v, "process-definitions-by-key");
        String dep = String.valueOf(doc(v, "deployment").get("id"));
        Map<String, Object> typed = Map.of("name", "divisor", "type", "integer", "value", 1);
        return List.of(
                new Write(
                        "moveDeadLetterJob",
                        "POST",
                        "/management/deadletter-jobs/" + job,
                        "{\"action\":\"move\"}",
                        c -> c.moveDeadLetterJob(e, p, job)),
                new Write(
                        "rescheduleTimer",
                        "POST",
                        "/management/timer-jobs/" + timer,
                        "{\"action\":\"reschedule\",\"dueDate\":\"2026-12-01T00:00:00Z\"}",
                        c -> c.rescheduleTimer(e, p, timer, "2026-12-01T00:00:00Z")),
                new Write(
                        "moveDeadLetterToHistoryJob",
                        "POST",
                        "/management/deadletter-jobs/" + job,
                        "{\"action\":\"moveToHistoryJob\"}",
                        c -> c.moveDeadLetterToHistoryJob(e, p, job)),
                new Write(
                        "deleteJob",
                        "DELETE",
                        "/management/timer-jobs/" + timer,
                        null,
                        c -> c.deleteJob(e, p, JobLaneKind.TIMER, timer)),
                new Write(
                        "moveTimerJob",
                        "POST",
                        "/management/timer-jobs/" + timer,
                        "{\"action\":\"move\"}",
                        c -> c.moveTimerJob(e, p, timer)),
                new Write(
                        "deleteDeadLetterJob",
                        "DELETE",
                        "/management/deadletter-jobs/" + job,
                        null,
                        c -> c.deleteDeadLetterJob(e, p, job)),
                new Write(
                        "suspendOrActivateInstance",
                        "PUT",
                        "/runtime/process-instances/" + pid,
                        "{\"action\":\"suspend\"}",
                        c -> c.suspendOrActivateInstance(e, p, pid, "suspend")),
                new Write(
                        "deleteProcessInstance",
                        "DELETE",
                        "/runtime/process-instances/" + pid,
                        null,
                        c -> c.deleteProcessInstance(e, p, pid, "operator cleanup")),
                new Write(
                        "putInstanceVariable",
                        "PUT",
                        "/runtime/process-instances/" + pid + "/variables/divisor",
                        "{\"name\":\"divisor\",\"type\":\"integer\",\"value\":1}",
                        c -> c.putInstanceVariable(e, p, pid, "divisor", typed)),
                new Write(
                        "putExecutionVariable",
                        "PUT",
                        "/runtime/executions/" + executionId + "/variables/divisor",
                        null,
                        c -> c.putExecutionVariable(e, p, executionId, "divisor", typed)),
                new Write(
                        "completeTask",
                        "POST",
                        "/runtime/tasks/" + taskId,
                        "{\"action\":\"complete\"}",
                        c -> c.completeTask(e, p, taskId, null)),
                new Write(
                        "setTaskAssignee(null = return to team)",
                        "PUT",
                        "/runtime/tasks/" + taskId,
                        "{\"assignee\":null}",
                        c -> c.setTaskAssignee(e, p, taskId, null)),
                new Write(
                        "taskAction",
                        "POST",
                        "/runtime/tasks/" + taskId,
                        "{\"action\":\"claim\",\"assignee\":\"bob\"}",
                        c -> c.taskAction(e, p, taskId, "claim", "bob")),
                new Write(
                        "putTask",
                        "PUT",
                        "/runtime/tasks/" + taskId,
                        "{\"priority\":80}",
                        c -> c.putTask(e, p, taskId, Map.of("priority", 80))),
                new Write("deleteTask", "DELETE", "/runtime/tasks/" + taskId, null, c -> c.deleteTask(e, p, taskId)),
                new Write(
                        "addIdentityLink",
                        "POST",
                        "/runtime/tasks/" + taskId + "/identitylinks",
                        null,
                        c -> c.addIdentityLink(e, p, taskId, "groups", "hr", "candidate")),
                new Write(
                        "deleteIdentityLink",
                        "DELETE",
                        "/runtime/tasks/" + taskId + "/identitylinks/groups/hr/candidate",
                        null,
                        c -> c.deleteIdentityLink(e, p, taskId, "groups", "hr", "candidate")),
                new Write(
                        "addInstanceIdentityLink",
                        "POST",
                        "/runtime/process-instances/" + pid + "/identitylinks",
                        null,
                        c -> c.addInstanceIdentityLink(e, p, pid, "bob", "participant")),
                new Write(
                        "deleteInstanceIdentityLink",
                        "DELETE",
                        "/runtime/process-instances/" + pid + "/identitylinks/users/bob/participant",
                        null,
                        c -> c.deleteInstanceIdentityLink(e, p, pid, "bob", "participant")),
                new Write(
                        "executionAction",
                        "PUT",
                        "/runtime/executions/" + executionId,
                        "{\"action\":\"trigger\"}",
                        c -> c.executionAction(e, p, executionId, Map.of("action", "trigger"))),
                new Write(
                        "suspendOrActivateDefinition",
                        "PUT",
                        "/repository/process-definitions/" + defId,
                        "{\"action\":\"suspend\",\"includeProcessInstances\":false}",
                        c -> c.suspendOrActivateDefinition(e, p, defId, "suspend", false)),
                new Write(
                        "suspendOrActivateDefinition(scheduled)",
                        "PUT",
                        "/repository/process-definitions/" + defId,
                        "{\"action\":\"activate\",\"includeProcessInstances\":true,\"date\":\"2026-12-01T00:00:00Z\"}",
                        c -> c.suspendOrActivateDefinition(e, p, defId, "activate", true, "2026-12-01T00:00:00Z")),
                new Write(
                        "changeActivityState",
                        "POST",
                        "/runtime/process-instances/" + pid + "/change-state",
                        "{\"cancelActivityIds\":[\"chargePayment\"],\"startActivityIds\":[\"end\"]}",
                        c -> c.changeActivityState(
                                e,
                                p,
                                pid,
                                Map.of(
                                        "cancelActivityIds",
                                        List.of("chargePayment"),
                                        "startActivityIds",
                                        List.of("end")))),
                new Write(
                        "migrateInstance",
                        "POST",
                        "/runtime/process-instances/" + pid + "/migrate",
                        "{\"toProcessDefinitionId\":\"" + defId + "\"}",
                        c -> c.migrateInstance(e, p, pid, Map.of("toProcessDefinitionId", defId))),
                new Write(
                        "deleteHistoricProcessInstance",
                        "DELETE",
                        "/history/historic-process-instances/" + pid,
                        null,
                        c -> c.deleteHistoricProcessInstance(e, p, pid, "retention")),
                new Write(
                        "deleteDeployment",
                        "DELETE",
                        "/repository/deployments/" + dep,
                        null,
                        c -> c.deleteDeployment(e, p, dep, true)));
    }

    @TestFactory
    Stream<DynamicTest> everyFacadeMutationSendsItsVerbPathAndBody() {
        return MAJORS.stream()
                .flatMap(v -> writes(v).stream()
                        .map(w -> dynamicTest(v + " " + w.name(), () -> {
                            wm.resetAll();
                            wm.stubFor(request(w.method(), anyUrl())
                                    .willReturn(aResponse().withStatus(204)));

                            w.call().accept(client);

                            RequestPatternBuilder sent = new RequestPatternBuilder(
                                    com.github.tomakehurst.wiremock.http.RequestMethod.fromString(w.method()),
                                    urlPathEqualTo(enc(w.path())));
                            if (w.jsonBody() != null) {
                                sent.withRequestBody(equalToJson(w.jsonBody(), false, true));
                            }
                            wm.verify(1, sent);
                        })));
    }

    @TestFactory
    Stream<DynamicTest> startProcessInstanceReturnsTheRecordedInstance() {
        return MAJORS.stream()
                .map(v -> dynamicTest(v, () -> {
                    wm.resetAll();
                    wm.stubFor(request("POST", urlPathEqualTo("/runtime/process-instances"))
                            .willReturn(okJson(text(v, "runtime-process-instance.json"))));

                    Map<String, Object> started = client.startProcessInstance(
                            engine, CallPriority.INTERACTIVE, Map.of("processDefinitionKey", "demoFailingPayment"));

                    assertThat(started).isEqualTo(doc(v, "runtime-process-instance"));
                    wm.verify(requestedFor("POST", urlPathEqualTo("/runtime/process-instances"))
                            .withRequestBody(equalToJson("{\"processDefinitionKey\":\"demoFailingPayment\"}")));
                }));
    }
}
