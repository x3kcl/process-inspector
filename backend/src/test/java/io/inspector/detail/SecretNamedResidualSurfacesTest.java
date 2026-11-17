package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.client.DecisionExecutionRecord;
import io.inspector.client.DmnApiClient;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessDefinitionRecord;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.support.NoDbTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
 * #742 review: the two read surfaces outside the {@code VariableDto} choke point that still carried
 * or compared a secret-named value. The DMN audit is the engine's JSON verbatim, and its
 * {@code inputVariables} holds EVERY process variable the decision saw (measured on 7.1:
 * {@code "inputVariables":{"amount":5,"apiToken":"…"}}) — a VIEWER read. The migration cohort
 * filter is an {@code equals} on a variable value whose count comes back — the search oracle again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class SecretNamedResidualSurfacesTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    DmnApiClient dmn;

    @MockitoBean
    ProcessApiClient flowable;

    @Test
    void theDecisionAuditMasksSecretNamedInputsAndOutputsForAViewer() throws Exception {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("amount", 5);
        inputs.put("apiToken", "tok-742-dmn");
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("decisionKey", "probe");
        audit.put("inputVariables", inputs);
        audit.put("inputVariableTypes", Map.of("amount", "number", "apiToken", "string"));
        audit.put("decisionResult", List.of(Map.of("ok", "yes", "clientSecret", "cs-742-dmn")));
        when(dmn.auditData(any(), any(), eq("dec-742"))).thenReturn(audit);
        // #756: the audit is served only for a decision execution of the path instance
        when(dmn.decisionExecution(any(), any(), eq("dec-742")))
                .thenReturn(new DecisionExecutionRecord("dec-742", "probe", null, null, false, null, null, "pi-742"));

        ResponseEntity<String> res = rest.withBasicAuth("viewer", "dev")
                .getForEntity("/api/instances/probe-dev/pi-742/decisions/dec-742/audit", String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).doesNotContain("tok-742-dmn").doesNotContain("cs-742-dmn");
        JsonNode body = mapper.readTree(res.getBody());
        assertThat(body.at("/inputVariables/amount").asInt()).isEqualTo(5); // ordinary input untouched
        assertThat(body.at("/inputVariableTypes/apiToken").asText()).isEqualTo("string"); // type stays
        assertThat(body.at("/decisionResult/0/ok").asText()).isEqualTo("yes");
        assertThat(mapper.convertValue(body.get("maskedVariables"), List.class))
                .containsExactlyInAnyOrder("apiToken", "clientSecret");
    }

    @Test
    void aCohortFilterOnASecretNamedVariableIsRefusedBeforeAnyEngineCall() throws Exception {
        when(flowable.queryRuntimeProcessInstances(any(), any(), any()))
                .thenReturn(ProcessInstanceRecords.page(List.of(Map.of("id", "pi-hit")), 1));
        when(flowable.getProcessDefinition(any(), any(), eq("payment:1:abc")))
                .thenReturn(new ProcessDefinitionRecord(
                        "payment:1:abc", "payment", null, 1, false, null, null, null, null, null, null));
        Map<String, Object> body = Map.of(
                "fromDefinitionId", "payment:1:abc", "variableName", "apiToken", "variableValue", "tok-guess-742");

        ResponseEntity<String> res = rest.withBasicAuth("admin", "dev")
                .postForEntity("/api/migration/cohorts/preview?engineId=probe-dev", body, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(res.getBody()).path("code").asText()).isEqualTo("secret-variable-filter");
        assertThat(res.getBody()).doesNotContain("tok-guess-742");
        verify(flowable, never()).queryRuntimeProcessInstances(any(), any(), any());
    }
}
