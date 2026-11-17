package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.ExecutionRecord;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.VariableRecord;
import io.inspector.registry.EngineHealthService;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
 * #743 review: a step-local reveal names an {@code executionId} — it must belong to the instance in
 * the path. Otherwise the value of ANOTHER instance's execution leaves while the audit row (and the
 * instance audit trail) points at the wrong instance. Same check as the step-local edit verb.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class VariableRevealExecutionOwnerSpringTest {

    private static final String INSTANCE = "pi-743";
    private static final String OTHER = "pi-743-other";
    private static final String VALUE = "pin-of-another-instance";

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

    private AuditEntry entry;

    @BeforeEach
    void anExecutionOfAnotherInstance() {
        when(gate.canRead(any(), anyString())).thenReturn(true);
        when(flowable.getHistoricProcessInstance(any(), any(), eq(INSTANCE)))
                .thenReturn(ProcessInstanceRecords.instance(Map.of(
                        "id", INSTANCE,
                        "processDefinitionId", "payment:1:abc",
                        "startTime", "2026-11-17T07:00:00Z")));
        when(flowable.getExecution(any(), any(), eq("ex-other")))
                .thenReturn(new ExecutionRecord("ex-other", OTHER, OTHER, null, "pay", false, null));
        when(flowable.getExecutionVariable(any(), any(), eq("ex-other"), eq("userPin")))
                .thenReturn(new VariableRecord("userPin", "string", VALUE, "local", null));
        entry = new AuditEntry(
                UUID.randomUUID(),
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

    @Test
    void anExecutionOfAnotherInstanceIsRefusedAndNothingIsRevealed() {
        ResponseEntity<String> res = rest.withBasicAuth("operator", "dev")
                .postForEntity(
                        "/api/instances/probe-dev/" + INSTANCE + "/variables/userPin/reveal?executionId=ex-other",
                        Map.of(),
                        String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("execution-instance-mismatch").doesNotContain(VALUE);
        verify(audit).close(eq(entry), eq(AuditOutcome.failed), eq(400), eq("execution-instance-mismatch"), eq(false));
        verify(flowable, never()).getExecutionVariable(any(), any(), any(), any());
    }
}
