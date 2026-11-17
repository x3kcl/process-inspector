package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.springframework.mock.env.MockEnvironment;

/**
 * What the last three body characterizations of #720 share ({@code IdentityLinkBodyWireShapeTest},
 * {@code ExecutionActionBodyWireShapeTest}, {@code PlanItemVerbBodyWireShapeTest}): the real
 * clients against a WireMock engine, the two ways a sent body is pinned, and the adapter for the
 * one client call whose body parameter changes type ({@code executionAction}). Kept out of the
 * frozen RED files so each test states inputs and expected bytes only. Before the typed record the
 * adapter built the {@code Map.of} bodies {@code CorrectiveActionService.eventBody} sent on
 * {@code main}; now it builds the records.
 */
final class RemainingBodyWire implements AutoCloseable {

    static final CallPriority P = CallPriority.INTERACTIVE;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    final WireMockServer wm = new WireMockServer(options().dynamicPort());
    final ProcessApiClient process;
    final CmmnApiClient cmmn;
    final EngineConfig engine;

    RemainingBodyWire() {
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
        process = new ProcessApiClient(guarded);
        cmmn = new CmmnApiClient(guarded);
        engine = TestEngines.engine(
                "t",
                wm.baseUrl(),
                TestEngines.basicAuth("rest-admin", "ENGINE_T_PASSWORD"),
                new Timeouts(1000, 1000, null));
    }

    @Override
    public void close() {
        wm.stop();
    }

    /* ---------- executionAction ---------- */

    void messageEvent(String executionId, String messageName) {
        process.executionAction(engine, P, executionId, ExecutionActionRequest.message(messageName));
    }

    void signalEvent(String executionId, String signalName) {
        process.executionAction(engine, P, executionId, ExecutionActionRequest.signal(signalName));
    }

    void trigger(String executionId) {
        process.executionAction(engine, P, executionId, ExecutionActionRequest.trigger());
    }

    /* ---------- pins ---------- */

    /** The one request sent was {@code method path} with exactly {@code body}; the record is that body. */
    void pins(String method, String path, String body, Object record) {
        pins(method, path, body, record, true);
    }

    /**
     * For a body {@code main} built with a multi-key {@code Map.of}: its key order is salted per
     * JVM, so the WIRE is compared as canonical JSON (same keys, same values, any order). The
     * record is still compared as the exact string — its order is fixed from here on.
     */
    void pinsCanonical(String method, String path, String body, Object record) {
        pins(method, path, body, record, false);
    }

    void sentNothing() {
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    private void pins(String method, String path, String body, Object record, boolean exactWire) {
        assertThat(wm.getAllServeEvents()).hasSize(1);
        LoggedRequest sent = wm.getAllServeEvents().get(0).getRequest();
        SoftAssertions.assertSoftly(s -> {
            s.assertThat(sent.getMethod().getName()).as("method").isEqualTo(method);
            s.assertThat(sent.getUrl()).as("path").isEqualTo(path);
            s.assertThat(sent.getHeader("Content-Type")).as("content type").startsWith("application/json");
            if (exactWire) {
                s.assertThat(sent.getBodyAsString()).as("body on the wire").isEqualTo(body);
            } else {
                s.assertThat(tree(sent.getBodyAsString()))
                        .as("body on the wire (canonical JSON)")
                        .isEqualTo(tree(body));
            }
            s.assertThat(json(record))
                    .as(record.getClass().getSimpleName() + " serialised")
                    .isEqualTo(body);
        });
    }

    private static Object tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
