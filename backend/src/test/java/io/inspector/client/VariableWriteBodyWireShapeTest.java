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
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Characterization of the VARIABLE WRITE request bodies (#720): the exact bytes the BFF sends to
 * write one variable in each engine scope — process-instance, execution-local (step-local) and
 * CMMN case. Each case runs in all three scopes and pins BOTH what the client puts on the wire and
 * what the request record serialises to, so the record cannot drift from the map {@code main}
 * sent.
 *
 * <p>The value is the dangerous part. It stays an {@code Object} and must leave exactly as it
 * came: a long above 2^31 as a JSON integer, a double as a double, a boolean as a boolean, a date
 * as the string the operator sent, a json value as a nested document, and an EXPLICIT null as
 * {@code "value":null} — null is a legal variable value, the key is never omitted. {@code type}
 * is present when the operator chose one and absent when not. {@code scope:"local"} is the last
 * key of an execution-local write and absent everywhere else.
 *
 * <p>Inputs are given as the JSON the operator's request carries and bound to {@code Object} the
 * way the BFF binds {@code ActionRequest.VariableEdit.value}, so a number is the Java type the
 * BFF really holds: {@code 12.50} is bound to the double 12.5 and leaves as {@code 12.5} — on
 * {@code main} too.
 */
class VariableWriteBodyWireShapeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CallPriority P = CallPriority.INTERACTIVE;

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
    void aLongAboveTwoToThe31StaysAJsonInteger() {
        pinsInEveryScope("long", "4294967296", "4294967296");
    }

    @Test
    void theLargestLongStaysAJsonInteger() {
        pinsInEveryScope("long", "9223372036854775807", "9223372036854775807");
    }

    @Test
    void anIntegerStaysAnInteger() {
        pinsInEveryScope("integer", "42", "42");
    }

    @Test
    void aDoubleStaysADouble() {
        pinsInEveryScope("double", "0.1", "0.1");
    }

    @Test
    void aWholeDoubleKeepsItsFraction() {
        pinsInEveryScope("double", "3.0", "3.0");
    }

    @Test
    void twelveFiftyIsBoundToADoubleAndLeavesAsTwelvePointFive() {
        pinsInEveryScope("double", "12.50", "12.5");
    }

    @Test
    void aBigDecimalValueKeepsItsScale() {
        pinsValueInEveryScope("double", new BigDecimal("12.50"), "12.50");
    }

    @Test
    void aBooleanStaysABoolean() {
        pinsInEveryScope("boolean", "true", "true");
        pinsInEveryScope("boolean", "false", "false");
    }

    @Test
    void aDateIsTheStringTheOperatorSentVerbatim() {
        pinsInEveryScope("date", "\"2026-11-17T10:15:30.000+01:00\"", "\"2026-11-17T10:15:30.000+01:00\"");
    }

    @Test
    void aJsonValueIsANestedDocumentWithItsKeyOrderAndTypes() {
        String json =
                "{\"z\":1,\"a\":{\"big\":4294967296,\"ratio\":0.5,\"ok\":true,\"none\":null},\"list\":[1,\"two\",null]}";

        pinsInEveryScope("json", json, json);
    }

    @Test
    void aJsonArrayValueIsANestedArray() {
        pinsInEveryScope("json", "[{\"k\":\"v\"},2]", "[{\"k\":\"v\"},2]");
    }

    @Test
    void aStringStaysAStringAlsoWhenItLooksLikeANumber() {
        pinsInEveryScope("string", "\"hello\"", "\"hello\"");
        pinsInEveryScope("string", "\"12.50\"", "\"12.50\"");
        pinsInEveryScope("string", "\"\"", "\"\"");
    }

    @Test
    void anExplicitNullValueIsSentAsNullNeverOmitted() {
        pinsInEveryScope("string", "null", "null");
    }

    @Test
    void withoutAChosenTypeTheTypeKeyIsAbsent() {
        pinsInEveryScope(null, "\"hello\"", "\"hello\"");
        pinsInEveryScope(null, "7", "7");
    }

    @Test
    void withoutATypeAndWithANullValueOnlyNameAndValueNullAreSent() {
        pinsInEveryScope(null, "null", "null");
    }

    /** {@code valueIn} is the JSON of the operator's value, bound to Object as the BFF binds it. */
    private void pinsInEveryScope(String type, String valueIn, String valueOut) {
        pinsValueInEveryScope(type, read(valueIn), valueOut);
    }

    private void pinsValueInEveryScope(String type, Object value, String valueOut) {
        String head = "{\"name\":\"v\"," + (type == null ? "" : "\"type\":\"" + type + "\",") + "\"value\":" + valueOut;

        wm.resetRequests();
        VariableWriteWire.instance(client, engine, P, "pi1", "v", type, value);
        pins("/runtime/process-instances/pi1/variables/v", head + "}", VariableWriteRequest.of("v", type, value));

        wm.resetRequests();
        VariableWriteWire.local(client, engine, P, "ex1", "v", type, value);
        pins(
                "/runtime/executions/ex1/variables/v",
                head + ",\"scope\":\"local\"}",
                VariableWriteRequest.local("v", type, value));

        wm.resetRequests();
        cmmn.putCaseVariable(engine, P, "c1", "v", type, value);
        pins("/cmmn-runtime/case-instances/c1/variables/v", head + "}", VariableWriteRequest.of("v", type, value));
    }

    /** The one request on the wire was a PUT to {@code path} with exactly {@code body}; the record is that body. */
    private void pins(String path, String body, Object record) {
        assertThat(wm.getAllServeEvents()).hasSize(1);
        LoggedRequest sent = wm.getAllServeEvents().get(0).getRequest();
        SoftAssertions.assertSoftly(s -> {
            s.assertThat(sent.getMethod().getName()).as("method").isEqualTo("PUT");
            s.assertThat(sent.getUrl()).as("path").endsWith(path);
            s.assertThat(sent.getHeader("Content-Type")).as("content type").startsWith("application/json");
            s.assertThat(sent.getBodyAsString()).as("body on the wire").isEqualTo(body);
            s.assertThat(json(record))
                    .as(record.getClass().getSimpleName() + " serialised")
                    .isEqualTo(body);
        });
    }

    private static Object read(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
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
