package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditService;
import io.inspector.client.ExecutionPage;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.VariableRecord;
import io.inspector.client.VariableWriteRequest;
import io.inspector.registry.EngineHealthService;
import io.inspector.support.NoDbTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #742 (the #730 facts test, #737, flipped to the masked expectation): a variable whose NAME matches {@code SecretNames} is
 * MASKED on every read surface, for every role — {@code masked: true}, no value, the type still
 * visible. Rung 3: the real controller → service → JSON path over HTTP, with only the engine
 * client (and the audit writer) stubbed.
 *
 * <p>Also pinned here: the search value-oracle refusal, the edit-variable CAS by an opaque
 * {@code valueToken} (the old value never travels), the masked 409, and the cURL render.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class SecretNamedVariableReadFactsTest {

    private static final String INSTANCE = "pi-730";
    private static final String BASE = "/api/instances/probe-dev/" + INSTANCE;

    /** Name → value as the engine holds it. */
    private static final Map<String, String> VARIABLES = new LinkedHashMap<>();

    private static final List<String> SECRET = List.of("apiToken", "authorization", "clientSecret", "password");

    static {
        VARIABLES.put("apiToken", "tok-730-plain");
        VARIABLES.put("authorization", "Bearer abc730");
        VARIABLES.put("clientSecret", "cs-730-plain");
        VARIABLES.put("orderId", "ORD-730");
        VARIABLES.put("password", "pw-730-plain");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    ProcessApiClient flowable;

    @MockitoBean
    AuditService audit;

    /**
     * #756: the scheduled health probe ({@code @Scheduled probeAll}) would call this class's mocked
     * {@link ProcessApiClient} from the scheduler thread. Mockito keeps the invocation being stubbed
     * per mock, not per thread, so a probe call landing between {@code when(flowable.x())} and
     * {@code thenReturn(..)} re-targets the stub ({@code WrongTypeOfReturnValue}). Mocked, the probe
     * never touches the client in this slice.
     */
    @MockitoBean
    EngineHealthService healthProbe;

    @BeforeEach
    void runningInstanceHoldingSecretNamedVariables() {
        when(flowable.getHistoricProcessInstance(any(), any(), eq(INSTANCE)))
                .thenReturn(ProcessInstanceRecords.instance(Map.of(
                        "id", INSTANCE,
                        "processDefinitionId", "payment:1:abc",
                        "startTime", "2026-11-17T07:00:00Z")));
        List<VariableRecord> rows = VARIABLES.entrySet().stream()
                .map(e -> new VariableRecord(e.getKey(), "string", e.getValue(), "global", null))
                .toList();
        when(flowable.listInstanceVariables(any(), any(), eq(INSTANCE))).thenReturn(rows);
        when(flowable.listExecutions(any(), any(), eq(INSTANCE), anyInt())).thenReturn(ExecutionPage.empty());
        rows.forEach(row -> when(flowable.getInstanceVariable(any(), any(), eq(INSTANCE), eq(row.name())))
                .thenReturn(row));
        when(flowable.getExecutionVariable(any(), any(), eq("ex-1"), eq("userPin")))
                .thenReturn(new VariableRecord("userPin", "string", "4711", "local", null));
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuditEntry(
                        UUID.nameUUIDFromBytes("a742".getBytes(StandardCharsets.UTF_8)),
                        "corr-742",
                        "operator",
                        Instant.parse("2026-11-17T07:00:00Z"),
                        "probe-dev",
                        null,
                        INSTANCE,
                        "edit-variable",
                        null,
                        null,
                        null,
                        false));
    }

    @Test
    void theDenylistMatchesThreeOfTheFourSecretShapedNamesAndNotAuthorization() {
        assertThat(AuditService.isSecretName("apiToken")).isTrue(); // "token"
        assertThat(AuditService.isSecretName("password")).isTrue();
        assertThat(AuditService.isSecretName("clientSecret")).isTrue(); // "secret"
        assertThat(AuditService.isSecretName("authorization")).isTrue(); // on the list since #741
        // ...and it IS applied to an audit payload (the write side)
        assertThat(AuditService.redact(Map.of("password", "pw-730-plain")))
                .containsEntry("password", AuditService.REDACTED);
    }

    private static boolean noValue(JsonNode row) {
        return row.path("value").isMissingNode() || row.path("value").isNull();
    }

    /* ---------------- reads ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "responder", "operator", "admin"})
    void theLedgerMasksSecretNamedValuesForEveryRoleAndKeepsTheType(String user) throws Exception {
        ResponseEntity<String> res = rest.withBasicAuth(user, "dev").getForEntity(BASE + "/variables", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = mapper.readTree(res.getBody()).get("processVariables");
        assertThat(rows).hasSize(VARIABLES.size());
        for (JsonNode row : rows) {
            String name = row.get("name").asText();
            assertThat(row.path("type").asText()).as(name + " type").isEqualTo("string");
            if (SECRET.contains(name)) {
                assertThat(row.path("masked").asBoolean())
                        .as(user + " " + name + " masked")
                        .isTrue();
                assertThat(noValue(row))
                        .as(user + " " + name + " carries no value")
                        .isTrue();
            } else {
                assertThat(row.path("masked").asBoolean()).as(name).isFalse();
                assertThat(row.get("value").asText()).isEqualTo(VARIABLES.get(name));
            }
        }
        SECRET.forEach(name -> assertThat(res.getBody()).doesNotContain(VARIABLES.get(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "admin"})
    void theFullValueReadMasksASecretNamedValueAndIssuesAnOpaqueToken(String user) throws Exception {
        ResponseEntity<String> res =
                rest.withBasicAuth(user, "dev").getForEntity(BASE + "/variables/password", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.path("masked").asBoolean()).isTrue();
        assertThat(noValue(body)).isTrue();
        assertThat(body.path("type").asText()).isEqualTo("string");
        assertThat(body.path("valueToken").asText()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(res.getBody()).doesNotContain("pw-730-plain");
    }

    @Test
    void theStepLocalFullReadMasksASegmentMatchedName() throws Exception {
        ResponseEntity<String> res = rest.withBasicAuth("viewer", "dev")
                .getForEntity(BASE + "/variables/userPin?executionId=ex-1", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(res.getBody()).path("masked").asBoolean()).isTrue();
        assertThat(res.getBody()).doesNotContain("4711");
    }

    @Test
    void aNonSecretFullReadIsUnchangedAndCarriesNoToken() throws Exception {
        ResponseEntity<String> res =
                rest.withBasicAuth("viewer", "dev").getForEntity(BASE + "/variables/orderId", String.class);

        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.get("value").asText()).isEqualTo("ORD-730");
        assertThat(body.path("masked").asBoolean()).isFalse();
        assertThat(body.path("valueToken").isMissingNode()).isTrue();
    }

    /* ---------------- search: the value oracle ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"equals", "notEquals", "like"})
    void aSearchFilterOnASecretNamedVariableIsRefusedBeforeAnyEngineCall(String op) throws Exception {
        Map<String, Object> body = Map.of(
                "processDefinitionKey",
                "payment",
                "variables",
                List.of(Map.of("name", "apiToken", "value", "tok-guess-742", "operation", op)));

        ResponseEntity<String> res =
                rest.withBasicAuth("admin", "dev").postForEntity("/api/search", body, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(res.getBody()).path("code").asText()).isEqualTo("secret-variable-filter");
        assertThat(res.getBody()).doesNotContain("tok-guess-742");
        // (the background health probe also uses this client — pin the search queries, not silence)
        verify(flowable, never()).queryRuntimeProcessInstances(any(), any(), any());
        verify(flowable, never()).queryHistoricProcessInstances(any(), any(), any());
    }

    /* ---------------- edit-variable on a masked variable ---------------- */

    private static Map<String, Object> edit(Map<String, Object> variable) {
        return Map.of("reason", "rotate the leaked credential", "variable", variable);
    }

    @Test
    void anExpectedOldValueOnASecretNamedVariableIsRefusedItWouldBeAnOracle() throws Exception {
        ResponseEntity<String> res = rest.withBasicAuth("operator", "dev")
                .postForEntity(
                        BASE + "/actions/edit-variable",
                        edit(Map.of(
                                "name", "password",
                                "type", "string",
                                "value", "pw-730-plain",
                                "expectedOldValue", "pw-730-plain")),
                        String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(res.getBody()).path("code").asText()).isEqualTo("secret-variable-needs-token");
        verify(flowable, never()).putInstanceVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void theEditCasesAgainstTheTokenFromTheMaskedReadAndEchoesNoValue() throws Exception {
        String token = mapper.readTree(rest.withBasicAuth("operator", "dev")
                        .getForEntity(BASE + "/variables/password", String.class)
                        .getBody())
                .path("valueToken")
                .asText();

        ResponseEntity<String> res = rest.withBasicAuth("operator", "dev")
                .postForEntity(
                        BASE + "/actions/edit-variable",
                        edit(Map.of(
                                "name", "password",
                                "type", "string",
                                "value", "pw-new-742",
                                "expectedValueToken", token)),
                        String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(flowable)
                .putInstanceVariable(
                        any(),
                        any(),
                        eq(INSTANCE),
                        eq("password"),
                        eq(VariableWriteRequest.of("password", "string", "pw-new-742")));
        assertThat(res.getBody()).doesNotContain("pw-new-742").doesNotContain("pw-730-plain");
    }

    @Test
    void aStaleTokenIsACasConflictThatShowsNeitherValue() throws Exception {
        ResponseEntity<String> res = rest.withBasicAuth("operator", "dev")
                .postForEntity(
                        BASE + "/actions/edit-variable",
                        edit(Map.of(
                                "name", "password",
                                "type", "string",
                                "value", "pw-new-742",
                                "expectedValueToken", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")),
                        String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(res.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("cas-conflict");
        assertThat(problem.path("masked").asBoolean()).isTrue();
        assertThat(problem.has("currentValue")).isFalse();
        assertThat(res.getBody()).doesNotContain("pw-730-plain").doesNotContain("pw-new-742");
        verify(flowable, never()).putInstanceVariable(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void theCurlRenderOfASecretEditCarriesNeitherValueAndSaysSo() {
        ResponseEntity<String> res = rest.withBasicAuth("operator", "dev")
                .postForEntity(
                        BASE + "/actions/edit-variable/curl",
                        edit(Map.of(
                                "name", "password",
                                "type", "string",
                                "value", "pw-new-742",
                                "expectedValueToken", "tok")),
                        String.class);

        assertThat(res.getBody()).doesNotContain("pw-new-742").contains("secret-named");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }
}
