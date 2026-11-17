package io.inspector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.Timeouts;
import io.inspector.support.TestEngines;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Characterization of the JOB verb request bodies (#720): the exact bytes the BFF sends for
 * retry / fire-timer / move-to-history / reschedule (7.x) of a process job and for the CMMN
 * dead-letter retry. Each case pins BOTH what the client puts on the wire and what {@link
 * JobActionRequest} serialises to, so the record cannot drift from the body {@code main} sent as
 * a map. {@code dueDate} is absent — not null — on every verb but reschedule. The delete verbs
 * send no body at all.
 *
 * <p>One caveat on "byte-for-byte": the two-key reschedule map on {@code main} was a {@code
 * Map.of}, whose iteration order is salted per JVM — {@code main} sent either key order. The
 * record fixes it to {@code action} first.
 */
class JobVerbBodyWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CallPriority P = CallPriority.INTERACTIVE;

    private WireMockServer wm;
    private ProcessApiClient client;
    private CmmnApiClient cmmnClient;
    private EngineConfig engine;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        wm.stubFor(post(anyUrl()).willReturn(noContent()));
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ENGINE_T_PASSWORD", "test");
        GuardedCaller guarded = new GuardedCaller(
                env,
                CircuitBreakerRegistry.of(Map.of("engine", CircuitBreakerConfig.ofDefaults())),
                BulkheadRegistry.of(Map.of("engine", BulkheadConfig.ofDefaults())),
                new SimpleMeterRegistry(),
                new RecentEngineErrors(Clock.systemUTC()));
        client = new ProcessApiClient(guarded);
        cmmnClient = new CmmnApiClient(guarded);
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
    void retryDeadLetterJobSendsActionMoveAndNothingElse() {
        client.moveDeadLetterJob(engine, P, "j1");

        pins("/management/deadletter-jobs/j1", "{\"action\":\"move\"}", JobActionRequest.move());
    }

    @Test
    void fireTimerSendsActionMoveAndNothingElse() {
        client.moveTimerJob(engine, P, "t1");

        pins("/management/timer-jobs/t1", "{\"action\":\"move\"}", JobActionRequest.move());
    }

    @Test
    void moveDeadLetterToHistoryJobSendsItsActionAndNothingElse() {
        client.moveDeadLetterToHistoryJob(engine, P, "j1");

        pins(
                "/management/deadletter-jobs/j1",
                "{\"action\":\"moveToHistoryJob\"}",
                JobActionRequest.moveToHistoryJob());
    }

    @Test
    void rescheduleTimerSendsActionThenTheDueDateVerbatim() {
        client.rescheduleTimer(engine, P, "t1", "2026-12-01T00:00:00Z");

        pins(
                "/management/timer-jobs/t1",
                "{\"action\":\"reschedule\",\"dueDate\":\"2026-12-01T00:00:00Z\"}",
                JobActionRequest.reschedule("2026-12-01T00:00:00Z"));
    }

    @Test
    void retryCmmnDeadLetterJobSendsActionMoveAndNothingElse() {
        cmmnClient.moveCmmnDeadLetterJob(engine, P, "c1");

        pins("/cmmn-api/cmmn-management/deadletter-jobs/c1", "{\"action\":\"move\"}", JobActionRequest.move());
    }

    @Test
    void rescheduleWithoutADueDateIsRefusedBeforeAnyEngineByte() {
        // A null dueDate never became {"dueDate":null} (which a 7.x engine reads as "now"): the
        // map body threw, and the record factory must too.
        assertThatThrownBy(() -> client.rescheduleTimer(engine, P, "t1", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(wm.getAllServeEvents()).isEmpty();
        assertThatThrownBy(() -> JobActionRequest.reschedule(null)).isInstanceOf(NullPointerException.class);
    }

    /** The one request on the wire went to {@code path} with exactly {@code body}; the record is that body. */
    private void pins(String path, String body, JobActionRequest record) {
        assertThat(wm.getAllServeEvents()).hasSize(1);
        LoggedRequest sent = wm.getAllServeEvents().get(0).getRequest();
        SoftAssertions.assertSoftly(s -> {
            s.assertThat(sent.getUrl()).as("path").isEqualTo(path);
            s.assertThat(sent.getHeader("Content-Type")).as("content type").startsWith("application/json");
            s.assertThat(sent.getBodyAsString()).as("body on the wire").isEqualTo(body);
            s.assertThat(json(record)).as("JobActionRequest serialised").isEqualTo(body);
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
