package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditService;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ExecutionRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.VariableRecord;
import io.inspector.registry.EngineHealthService;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
 * #756: the step-local READ ({@code GET …/variables/{name}?executionId=}) must refuse an execution of
 * ANOTHER instance — otherwise instance B's value is served under instance A's path. Same check as
 * the reveal (#743) and the step-local edit. Same mock set as {@code VariableRevealExecutionOwnerSpringTest}
 * so both share one cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class StepLocalReadExecutionOwnerSpringTest {

    private static final String INSTANCE = "pi-756";
    private static final String OTHER = "pi-756-other";
    private static final String VALUE = "note-of-another-instance-756";

    @Autowired
    TestRestTemplate rest;

    @MockitoBean
    ProcessApiClient flowable;

    @MockitoBean
    CmmnApiClient cmmn;

    @MockitoBean
    AuditService audit;

    @MockitoBean
    ReadScopeGate gate;

    @MockitoBean
    EngineHealthService healthProbe;

    @BeforeEach
    void twoInstancesEachWithAStepLocalVariable() {
        when(gate.canRead(any(), anyString())).thenReturn(true);
        when(flowable.getHistoricProcessInstance(any(), any(), eq(INSTANCE)))
                .thenReturn(ProcessInstanceRecords.instance(Map.of(
                        "id", INSTANCE,
                        "processDefinitionId", "payment:1:abc",
                        "startTime", "2026-11-17T07:00:00Z")));
        when(flowable.getExecution(any(), any(), eq("ex-other")))
                .thenReturn(new ExecutionRecord("ex-other", OTHER, OTHER, null, "pay", false, null));
        when(flowable.getExecutionVariable(any(), any(), eq("ex-other"), eq("stepNote")))
                .thenReturn(new VariableRecord("stepNote", "string", VALUE, "local", null));
        when(flowable.getExecution(any(), any(), eq("ex-own")))
                .thenReturn(new ExecutionRecord("ex-own", INSTANCE, INSTANCE, null, "pay", false, null));
        when(flowable.getExecutionVariable(any(), any(), eq("ex-own"), eq("stepNote")))
                .thenReturn(new VariableRecord("stepNote", "string", "own-note", "local", null));
    }

    private ResponseEntity<String> read(String executionId) {
        return rest.withBasicAuth("viewer", "dev")
                .getForEntity(
                        "/api/instances/probe-dev/" + INSTANCE + "/variables/stepNote?executionId=" + executionId,
                        String.class);
    }

    @Test
    void anExecutionOfAnotherInstanceIsRefusedAndItsValueIsNeverRead() {
        ResponseEntity<String> res = read("ex-other");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("execution-instance-mismatch").doesNotContain(VALUE);
        verify(flowable, never()).getExecutionVariable(any(), any(), eq("ex-other"), any());
    }

    @Test
    void anExecutionOfTheSameInstanceIsStillServed() {
        ResponseEntity<String> res = read("ex-own");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("own-note").contains("\"executionId\":\"ex-own\"");
    }
}
