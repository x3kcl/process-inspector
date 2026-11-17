package io.inspector.client;

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
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.StartProcessInstanceRequest.Variable;
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
 * Characterization of the INSTANCE verb request bodies (#720): the exact bytes the BFF sends for
 * suspend / activate of a process instance, delete (no body — the reason travels as a query
 * parameter), change-state (flow surgery), restart-as-new (the start-instance body) and suspend /
 * activate of a process DEFINITION. Each case pins BOTH what the client puts on the wire and what
 * the request record serialises to, so a record cannot drift from the body {@code main} sent as a
 * map. Keys with no value are absent — never {@code null}: {@code date} on an immediate definition
 * suspend, {@code businessKey} / {@code variables} / the other definition reference on a start. A
 * carried variable whose VALUE is null keeps its {@code "value":null} — the engine needs the key.
 *
 * <p>Every multi-key body here was an insertion-ordered map on {@code main} (no {@code Map.of}),
 * so the key order pinned below is the order {@code main} sends, not a new choice.
 */
class InstanceVerbBodyWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CallPriority P = CallPriority.INTERACTIVE;

    private WireMockServer wm;
    private ProcessApiClient client;
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
    void suspendInstanceSendsActionSuspendAndNothingElse() {
        client.suspendOrActivateInstance(engine, P, "pi1", "suspend");

        pins(
                "PUT",
                "/runtime/process-instances/pi1",
                "{\"action\":\"suspend\"}",
                new ProcessInstanceActionRequest("suspend"));
    }

    @Test
    void activateInstanceSendsActionActivateAndNothingElse() {
        client.suspendOrActivateInstance(engine, P, "pi1", "activate");

        pins(
                "PUT",
                "/runtime/process-instances/pi1",
                "{\"action\":\"activate\"}",
                new ProcessInstanceActionRequest("activate"));
    }

    @Test
    void instanceActionWithoutAnActionIsRefusedBeforeAnyEngineByte() {
        // The map body threw on a null action; it never became {} or {"action":null}.
        assertThatThrownBy(() -> client.suspendOrActivateInstance(engine, P, "pi1", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
        assertThatThrownBy(() -> new ProcessInstanceActionRequest(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deleteInstanceSendsNoBodyAndTheReasonAsAQueryParameter() {
        client.deleteProcessInstance(engine, P, "pi1", "operator cleanup");

        assertThat(wm.getAllServeEvents()).hasSize(1);
        LoggedRequest sent = wm.getAllServeEvents().get(0).getRequest();
        SoftAssertions.assertSoftly(s -> {
            s.assertThat(sent.getMethod().getName()).as("method").isEqualTo("DELETE");
            s.assertThat(sent.getUrl())
                    .as("path and query")
                    .isEqualTo("/runtime/process-instances/pi1?deleteReason=operator%20cleanup");
            s.assertThat(sent.getBodyAsString()).as("body on the wire").isEqualTo("");
        });
    }

    @Test
    void suspendDefinitionNowSendsActionThenTheCascadeFlagAndNoDate() {
        client.suspendOrActivateDefinition(engine, P, "def1", "suspend", false);

        pins(
                "PUT",
                "/repository/process-definitions/def1",
                "{\"action\":\"suspend\",\"includeProcessInstances\":false}",
                new ProcessDefinitionActionRequest("suspend", false, null));
    }

    @Test
    void scheduledDefinitionActivateSendsTheDateVerbatimAsTheLastKey() {
        client.suspendOrActivateDefinition(engine, P, "def1", "activate", true, "2026-12-01T00:00:00Z");

        pins(
                "PUT",
                "/repository/process-definitions/def1",
                "{\"action\":\"activate\",\"includeProcessInstances\":true,\"date\":\"2026-12-01T00:00:00Z\"}",
                new ProcessDefinitionActionRequest("activate", true, "2026-12-01T00:00:00Z"));
    }

    @Test
    void changeStateSendsTheCancelIdsThenTheStartIds() {
        InstanceVerbWire.changeState(client, engine, P, "pi1", List.of("stepOne"), List.of("stepTwo", "stepThree"));

        pins(
                "POST",
                "/runtime/process-instances/pi1/change-state",
                "{\"cancelActivityIds\":[\"stepOne\"],\"startActivityIds\":[\"stepTwo\",\"stepThree\"]}",
                new ChangeActivityStateRequest(List.of("stepOne"), List.of("stepTwo", "stepThree")));
    }

    @Test
    void restartOnTheLatestVersionSendsTheKeyTheBusinessKeyAndEachVariableWithItsValue() {
        InstanceVerbWire.start(
                client, engine, P, false, "demoOrder", "bk-9", new Object[] {"amount", "integer", 42}, new Object[] {
                    "note", "string", null
                });

        pins(
                "POST",
                "/runtime/process-instances",
                "{\"processDefinitionKey\":\"demoOrder\",\"businessKey\":\"bk-9\",\"variables\":["
                        + "{\"name\":\"amount\",\"type\":\"integer\",\"value\":42},"
                        + "{\"name\":\"note\",\"type\":\"string\",\"value\":null}]}",
                StartProcessInstanceRequest.latest(
                        "demoOrder",
                        "bk-9",
                        List.of(new Variable("amount", "integer", 42), new Variable("note", "string", null))));
    }

    @Test
    void pinnedRestartWithNothingToCarrySendsTheDefinitionIdAndNothingElse() {
        InstanceVerbWire.start(client, engine, P, true, "demoOrder:3:v3", null);

        pins(
                "POST",
                "/runtime/process-instances",
                "{\"processDefinitionId\":\"demoOrder:3:v3\"}",
                StartProcessInstanceRequest.pinned("demoOrder:3:v3", null, List.of()));
    }

    /** The one request on the wire was {@code method path} with exactly {@code body}; the record is that body. */
    private void pins(String method, String path, String body, Object record) {
        assertThat(wm.getAllServeEvents()).hasSize(1);
        LoggedRequest sent = wm.getAllServeEvents().get(0).getRequest();
        SoftAssertions.assertSoftly(s -> {
            s.assertThat(sent.getMethod().getName()).as("method").isEqualTo(method);
            s.assertThat(sent.getUrl()).as("path").isEqualTo(path);
            s.assertThat(sent.getHeader("Content-Type")).as("content type").startsWith("application/json");
            s.assertThat(sent.getBodyAsString()).as("body on the wire").isEqualTo(body);
            s.assertThat(json(record))
                    .as(record.getClass().getSimpleName() + " serialised")
                    .isEqualTo(body);
        });
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
