package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.audit.AuditUnavailableException;
import io.inspector.audit.OutcomeVerificationFailedException;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.VariableRecord;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineHealthService;
import io.inspector.registry.EngineRegistry;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #743 — the audited reveal of ONE secret-named variable value. Rung 3: the real controller → service
 * → JSON path, only the engine clients, the audit writer and the read-scope gate stubbed.
 *
 * <p>Pins: POST only, {@code Cache-Control: no-store}; OPERATOR and above on the engine (VIEWER and
 * RESPONDER get the verbs' {@code rbac-denied}); out-of-scope refused under enforcement; a
 * non-secret-named variable 400; an unknown one the read's 404; the audit row (actor, engine,
 * instance, name, outcome — never the value) written BEFORE the value leaves, fail-closed on both
 * the insert and the close; refused attempts audited; reason optional on dev, required (≥10) on prod;
 * the case route; and the value string nowhere in a log line or error body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
@ExtendWith(OutputCaptureExtension.class)
class VariableRevealSpringTest {

    private static final String INSTANCE = "pi-743";
    private static final String CASE = "case-743";
    private static final String VALUE = "tok-743-plain";
    private static final String BASE = "/api/instances/probe-dev/" + INSTANCE + "/variables/";
    private static final UUID AUDIT_ID = UUID.nameUUIDFromBytes("a743".getBytes(StandardCharsets.UTF_8));

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    EngineRegistry registry;

    @MockitoBean
    ProcessApiClient flowable;

    @MockitoBean
    CmmnApiClient cmmn;

    @MockitoBean
    AuditService audit;

    @MockitoBean
    ReadScopeGate gate;

    @MockitoBean
    EngineHealthService healthProbe; // no background probe overwriting the CMMN capability below

    private AuditEntry entry;

    @BeforeEach
    void anInstanceHoldingASecretNamedVariable() {
        when(gate.canRead(any(), anyString())).thenReturn(true);
        when(flowable.getHistoricProcessInstance(any(), any(), eq(INSTANCE)))
                .thenReturn(ProcessInstanceRecords.instance(Map.of(
                        "id", INSTANCE,
                        "processDefinitionId", "payment:1:abc",
                        "startTime", "2026-11-17T07:00:00Z")));
        when(flowable.getInstanceVariable(any(), any(), eq(INSTANCE), eq("apiToken")))
                .thenReturn(new VariableRecord("apiToken", "string", VALUE, "global", null));
        when(flowable.getExecutionVariable(any(), any(), eq("ex-1"), eq("userPin")))
                .thenReturn(new VariableRecord("userPin", "string", "4711-743", "local", null));
        when(cmmn.getCaseVariable(any(), any(), eq(CASE), eq("apiToken")))
                .thenReturn(new VariableRecord("apiToken", "string", VALUE, "global", null));
        entry = new AuditEntry(
                AUDIT_ID,
                "corr-743",
                "operator",
                Instant.parse("2026-11-17T07:00:00Z"),
                "probe-dev",
                null,
                INSTANCE,
                "reveal-variable",
                null,
                null,
                null,
                false);
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(entry);
    }

    private ResponseEntity<String> reveal(String user, String path, Map<String, Object> body) {
        return rest.withBasicAuth(user, "dev").postForEntity(path, body, String.class);
    }

    private ResponseEntity<String> reveal(String user, String name) {
        return reveal(user, BASE + name + "/reveal", Map.of());
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        return mapper.readTree(res.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditedPayload() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .beginPending(
                        any(), any(), any(), any(), eq("reveal-variable"), any(), any(), payload.capture(), any());
        return payload.getValue();
    }

    /* ---------------- allowed ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"operator", "admin"})
    void anOperatorOrAdminGetsTheValueWithNoStore(String user, CapturedOutput output) throws Exception {
        ResponseEntity<String> res = reveal(user, "apiToken");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body = json(res);
        assertThat(body.path("name").asText()).isEqualTo("apiToken");
        assertThat(body.path("type").asText()).isEqualTo("string");
        assertThat(body.path("value").asText()).isEqualTo(VALUE);
        assertThat(body.path("auditId").asText()).isEqualTo(AUDIT_ID.toString());
        assertThat(output.getAll()).doesNotContain(VALUE);
    }

    @Test
    void theAuditRowNamesActorEngineInstanceAndVariableButNeverTheValue() {
        reveal("operator", "apiToken");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit)
                .beginPending(
                        eq("operator"),
                        eq("probe-dev"),
                        any(),
                        eq(INSTANCE),
                        eq("reveal-variable"),
                        reason.capture(),
                        isNull(),
                        any(),
                        any());
        Map<String, Object> payload = auditedPayload();
        assertThat(payload).containsEntry("name", "apiToken").containsEntry("scope", "global");
        assertThat(String.valueOf(payload)).doesNotContain(VALUE);
        assertThat(reason.getValue()).isNull();
        ArgumentCaptor<String> snippet = ArgumentCaptor.forClass(String.class);
        verify(audit).close(eq(entry), eq(AuditOutcome.ok), eq(200), snippet.capture(), eq(true));
        assertThat(String.valueOf(snippet.getValue())).doesNotContain(VALUE);
    }

    @Test
    void aStepLocalVariableRevealsThroughTheExecutionIdParameterLikeTheRead() throws Exception {
        ResponseEntity<String> res = reveal("operator", BASE + "userPin/reveal?executionId=ex-1", Map.of());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(res);
        assertThat(body.path("value").asText()).isEqualTo("4711-743");
        assertThat(body.path("scope").asText()).isEqualTo("local");
        assertThat(body.path("executionId").asText()).isEqualTo("ex-1");
        assertThat(auditedPayload()).containsEntry("executionId", "ex-1").containsEntry("scope", "local");
    }

    @Test
    void aCaseVariableRevealsOnTheCaseRoute() throws Exception {
        EngineHealth before = registry.healthOf("probe-dev");
        registry.updateHealth(
                "probe-dev",
                new EngineHealth(
                        true, "6.8.0", null, 0, EngineCapabilities.fromVersion("6.8.0", true), null, null, null));
        try {
            ResponseEntity<String> ok =
                    reveal("operator", "/api/cases/probe-dev/" + CASE + "/variables/apiToken/reveal", Map.of());
            ResponseEntity<String> viewer =
                    reveal("viewer", "/api/cases/probe-dev/" + CASE + "/variables/apiToken/reveal", Map.of());

            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(ok).path("value").asText()).isEqualTo(VALUE);
            assertThat(viewer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(viewer.getBody()).doesNotContain(VALUE);
            verify(audit, org.mockito.Mockito.times(2))
                    .beginPending(any(), any(), any(), eq(CASE), eq("reveal-variable"), any(), any(), any(), any());
        } finally {
            registry.updateHealth("probe-dev", before);
        }
    }

    /* ---------------- refused ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "responder"})
    void viewerAndResponderGetTheTierRefusalAndTheAttemptIsAudited(String user, CapturedOutput output)
            throws Exception {
        ResponseEntity<String> res = reveal(user, "apiToken");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(res).path("code").asText()).isEqualTo("rbac-denied");
        assertThat(json(res).path("detail").asText()).contains("requires OPERATOR");
        assertThat(res.getBody()).doesNotContain(VALUE);
        assertThat(output.getAll()).doesNotContain(VALUE);
        verify(audit).close(eq(entry), eq(AuditOutcome.failed), eq(403), any(), eq(false));
        verify(flowable, never()).getInstanceVariable(any(), any(), any(), any());
    }

    @Test
    void anOutOfScopeEngineIsRefusedUnderEnforcement() throws Exception {
        when(gate.canRead(any(), eq("probe-dev"))).thenReturn(false);

        ResponseEntity<String> res = reveal("admin", "apiToken");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).doesNotContain(VALUE);
        verify(flowable, never()).getInstanceVariable(any(), any(), any(), any());
    }

    @Test
    void aNonSecretNamedVariableIsRefusedWith400BeforeAnyEngineRead() throws Exception {
        ResponseEntity<String> res = reveal("operator", "orderId");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(res).path("code").asText()).isEqualTo("variable-not-secret");
        verify(flowable, never()).getInstanceVariable(any(), any(), any(), any());
        verify(audit).close(eq(entry), eq(AuditOutcome.failed), eq(400), any(), eq(false));
    }

    @Test
    void anUnknownSecretNamedVariableAnswersTheReadsNotFound() throws Exception {
        ResponseEntity<String> read =
                rest.withBasicAuth("operator", "dev").getForEntity(BASE + "missingToken", String.class);
        ResponseEntity<String> res = reveal("operator", "missingToken");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(res).path("detail").asText())
                .isEqualTo(json(read).path("detail").asText());
        verify(audit).close(eq(entry), eq(AuditOutcome.failed), eq(404), any(), eq(false));
    }

    @Test
    void aGetOnTheRevealRouteIsNotAllowed() {
        ResponseEntity<String> res =
                rest.withBasicAuth("operator", "dev").getForEntity(BASE + "apiToken/reveal", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(String.valueOf(res.getBody())).doesNotContain(VALUE);
    }

    /* ---------------- fail-closed ---------------- */

    @Test
    void noAuditRowMeansNoValue() throws Exception {
        when(audit.beginPending(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new AuditUnavailableException(new IllegalStateException("db down")));

        ResponseEntity<String> res = reveal("operator", "apiToken");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json(res).path("code").asText()).isEqualTo("audit-unavailable");
        assertThat(res.getBody()).doesNotContain(VALUE);
        verify(flowable, never()).getInstanceVariable(any(), any(), any(), any());
    }

    @Test
    void aFailedAuditCloseWithholdsTheValue(CapturedOutput output) throws Exception {
        doThrow(new OutcomeVerificationFailedException(AUDIT_ID, new IllegalStateException("db down")))
                .when(audit)
                .close(any(), eq(AuditOutcome.ok), any(), any(), anyBoolean());

        ResponseEntity<String> res = reveal("operator", "apiToken");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).doesNotContain(VALUE);
        assertThat(output.getAll()).doesNotContain(VALUE);
    }

    /* ---------------- reason ---------------- */

    @Test
    void aReasonIsOptionalOnDevAndRecordedWhenGiven() {
        ResponseEntity<String> res =
                reveal("operator", BASE + "apiToken/reveal", Map.of("reason", "INC-743 checking the token"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(audit)
                .beginPending(
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("reveal-variable"),
                        eq("INC-743 checking the token"),
                        any(),
                        any(),
                        any());
    }

    @Test
    void aProdEngineRequiresAReasonOfTenCharacters() throws Exception {
        String prod = "/api/instances/probe-prod/" + INSTANCE + "/variables/apiToken/reveal";

        ResponseEntity<String> none = reveal("operator", prod, Map.of());
        ResponseEntity<String> tooShort = reveal("operator", prod, Map.of("reason", "short"));
        Map<String, Object> given = new HashMap<>();
        given.put("reason", "INC-743 rotate the token");
        ResponseEntity<String> ok = reveal("operator", prod, given);

        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(none).path("code").asText()).isEqualTo("reason-required");
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(tooShort).path("code").asText()).isEqualTo("reason-too-short");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(ok).path("value").asText()).isEqualTo(VALUE);
    }
}
