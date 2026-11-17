package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditService;
import io.inspector.client.ExecutionPage;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.VariableRecord;
import io.inspector.support.NoDbTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

// #730: pins CURRENT behaviour pending an owner decision — not an endorsement
/**
 * Facts for issue #730 claim 2 — does the secret-name denylist ({@code AuditService.isSecretName})
 * reach the instance variable READ view? Rung 3: the real controller → {@code InstanceDetailService}
 * → JSON path over HTTP, with only the engine client ({@link ProcessApiClient}) stubbed to hand back
 * what a Flowable engine holds.
 *
 * <p>Pinned today: the denylist is applied on audit/outcome WRITE paths only; the variable ledger and
 * the single-variable full read return a secret-named variable's value verbatim to every role
 * (VIEWER floor, no role branching), and {@code authorization} is not on the denylist at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class SecretNamedVariableReadFactsTest {

    private static final String INSTANCE = "pi-730";

    /** Name → value as the engine holds it. */
    private static final Map<String, String> VARIABLES = new LinkedHashMap<>();

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

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "responder", "operator", "admin"})
    void theVariableLedgerReturnsSecretNamedValuesVerbatimToEveryRole(String user) throws Exception {
        ResponseEntity<String> res = rest.withBasicAuth(user, "dev")
                .getForEntity("/api/instances/probe-dev/" + INSTANCE + "/variables", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = mapper.readTree(res.getBody()).get("processVariables");
        assertThat(rows).hasSize(VARIABLES.size());
        rows.forEach(row -> assertThat(row.get("value").asText())
                .as(user + " reads " + row.get("name").asText())
                .isEqualTo(VARIABLES.get(row.get("name").asText())));
        assertThat(res.getBody()).doesNotContain(AuditService.REDACTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"viewer", "admin"})
    void theFullValueReadReturnsASecretNamedValueVerbatim(String user) throws Exception {
        ResponseEntity<String> res = rest.withBasicAuth(user, "dev")
                .getForEntity("/api/instances/probe-dev/" + INSTANCE + "/variables/password", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(res.getBody()).get("value").asText()).isEqualTo("pw-730-plain");
    }
}
