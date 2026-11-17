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
import io.inspector.client.TaskActionRequest.Variable;
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
 * Characterization of the TASK verb request bodies (#720): the exact bytes the BFF sends for
 * complete (with and without variables), claim / unclaim, delegate, resolve, reassign / unassign,
 * update-task, delete (no body) and the task identity-link writes. Each case pins BOTH what the
 * client puts on the wire and what the request record serialises to, so a record cannot drift from
 * the body {@code main} sent as a map.
 *
 * <p>Null versus absent is the contract here. {@code assignee} is SENT as {@code null} on an
 * unclaim and on an unassign (the engine clears it) and is ABSENT on resolve and complete. An
 * update-task field is absent when left unchanged and {@code null} when cleared. A complete
 * variable keeps {@code "value":null} and drops a null {@code type}; an empty variable list is
 * absent, never {@code []}.
 *
 * <p>Key order: every multi-key body was an insertion-ordered map on {@code main}, except complete
 * WITH variables — a two-key {@code Map.of}, whose order is salted per JVM, so {@code main} sends
 * {@code action, variables} or {@code variables, action}. The record fixes {@code action,
 * variables}; that one case pins the new fixed order.
 */
class TaskVerbBodyWireShapeTest {

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
    void completeWithoutVariablesSendsActionCompleteAndNothingElse() {
        TaskVerbWire.complete(client, engine, P, "t1");

        pins("POST", "/runtime/tasks/t1", "{\"action\":\"complete\"}", TaskActionRequest.complete(List.of()));
    }

    @Test
    void completeWithANullVariableListIsTheSameBody() {
        client.completeTask(engine, P, "t1", null);

        pins("POST", "/runtime/tasks/t1", "{\"action\":\"complete\"}", TaskActionRequest.complete(null));
    }

    @Test
    void completeWithVariablesSendsActionThenEachVariableWithItsValueAndNoNullType() {
        TaskVerbWire.complete(
                client, engine, P, "t1", new Object[] {"amount", "integer", 42}, new Object[] {"note", null, null});

        pins(
                "POST",
                "/runtime/tasks/t1",
                "{\"action\":\"complete\",\"variables\":["
                        + "{\"name\":\"amount\",\"type\":\"integer\",\"value\":42},"
                        + "{\"name\":\"note\",\"value\":null}]}",
                TaskActionRequest.complete(
                        List.of(new Variable("amount", "integer", 42), new Variable("note", null, null))));
    }

    @Test
    void claimSendsTheAssignee() {
        client.taskAction(engine, P, "t1", "claim", "bob");

        pins(
                "POST",
                "/runtime/tasks/t1",
                "{\"action\":\"claim\",\"assignee\":\"bob\"}",
                TaskActionRequest.of("claim", "bob"));
    }

    @Test
    void unclaimSendsTheAssigneeKeyWithANullValue() {
        client.taskAction(engine, P, "t1", "claim", null);

        pins(
                "POST",
                "/runtime/tasks/t1",
                "{\"action\":\"claim\",\"assignee\":null}",
                TaskActionRequest.of("claim", null));
    }

    @Test
    void delegateSendsTheAssignee() {
        client.taskAction(engine, P, "t1", "delegate", "gonzo");

        pins(
                "POST",
                "/runtime/tasks/t1",
                "{\"action\":\"delegate\",\"assignee\":\"gonzo\"}",
                TaskActionRequest.of("delegate", "gonzo"));
    }

    @Test
    void resolveSendsNoAssigneeKey() {
        client.taskAction(engine, P, "t1", "resolve", null);

        pins("POST", "/runtime/tasks/t1", "{\"action\":\"resolve\"}", TaskActionRequest.of("resolve", null));
    }

    @Test
    void reassignSendsOnlyTheAssignee() {
        client.setTaskAssignee(engine, P, "t1", "gonzo");

        pins("PUT", "/runtime/tasks/t1", "{\"assignee\":\"gonzo\"}", TaskUpdateRequest.assignee("gonzo"));
    }

    @Test
    void unassignSendsTheAssigneeKeyWithANullValue() {
        client.setTaskAssignee(engine, P, "t1", null);

        pins("PUT", "/runtime/tasks/t1", "{\"assignee\":null}", TaskUpdateRequest.assignee(null));
    }

    @Test
    void updateSendsEachPatchedFieldInOrderAClearedOneAsNull() {
        TaskVerbWire.update(client, engine, P, "t1", "Review", "", "kermit", "2026-12-01T00:00:00Z", "", 80);

        pins(
                "PUT",
                "/runtime/tasks/t1",
                "{\"name\":\"Review\",\"description\":null,\"owner\":\"kermit\","
                        + "\"dueDate\":\"2026-12-01T00:00:00Z\",\"category\":null,\"priority\":80}",
                new TaskUpdateRequest(
                        null,
                        new TaskField("Review"),
                        new TaskField(null),
                        new TaskField("kermit"),
                        new TaskField("2026-12-01T00:00:00Z"),
                        new TaskField(null),
                        80));
    }

    @Test
    void updateOfOneFieldSendsThatKeyAndNoOther() {
        TaskVerbWire.update(client, engine, P, "t1", null, null, null, null, null, 80);

        pins(
                "PUT",
                "/runtime/tasks/t1",
                "{\"priority\":80}",
                new TaskUpdateRequest(null, null, null, null, null, null, 80));
    }

    @Test
    void updateOfTheDueDateAloneSendsItVerbatim() {
        TaskVerbWire.update(client, engine, P, "t1", null, null, null, "2026-12-01T00:00:00Z", null, null);

        pins(
                "PUT",
                "/runtime/tasks/t1",
                "{\"dueDate\":\"2026-12-01T00:00:00Z\"}",
                new TaskUpdateRequest(null, null, null, null, new TaskField("2026-12-01T00:00:00Z"), null, null));
    }

    @Test
    void addAGroupLinkSendsGroupIdThenType() {
        client.addIdentityLink(engine, P, "t1", "groups", "hr", "candidate");

        pins(
                "POST",
                "/runtime/tasks/t1/identitylinks",
                "{\"groupId\":\"hr\",\"type\":\"candidate\"}",
                TaskIdentityLinkRequest.of("groups", "hr", "candidate"));
    }

    @Test
    void addAUserLinkSendsUserIdThenType() {
        client.addIdentityLink(engine, P, "t1", "users", "kermit", "candidate");

        pins(
                "POST",
                "/runtime/tasks/t1/identitylinks",
                "{\"userId\":\"kermit\",\"type\":\"candidate\"}",
                TaskIdentityLinkRequest.of("users", "kermit", "candidate"));
    }

    @Test
    void deleteTaskSendsNoBody() {
        client.deleteTask(engine, P, "t1");

        pinsBodyless("/runtime/tasks/t1");
    }

    @Test
    void deleteAnIdentityLinkSendsNoBodyAndTheLinkInThePath() {
        client.deleteIdentityLink(engine, P, "t1", "groups", "hr", "candidate");

        pinsBodyless("/runtime/tasks/t1/identitylinks/groups/hr/candidate");
    }

    private void pinsBodyless(String path) {
        assertThat(wm.getAllServeEvents()).hasSize(1);
        LoggedRequest sent = wm.getAllServeEvents().get(0).getRequest();
        SoftAssertions.assertSoftly(s -> {
            s.assertThat(sent.getMethod().getName()).as("method").isEqualTo("DELETE");
            s.assertThat(sent.getUrl()).as("path").isEqualTo(path);
            s.assertThat(sent.getBodyAsString()).as("body on the wire").isEqualTo("");
        });
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
