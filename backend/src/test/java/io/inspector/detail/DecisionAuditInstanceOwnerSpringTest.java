package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditService;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.DecisionExecutionRecord;
import io.inspector.client.DmnApiClient;
import io.inspector.client.ProcessApiClient;
import io.inspector.registry.EngineHealthService;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #756 review: {@code GET …/{instanceId}/decisions/{decisionExecutionId}/audit} must refuse a decision
 * execution of ANOTHER instance — otherwise instance B's decision inputs and result are served under
 * instance A's path. The engine row's {@code instanceId} is the owner; any DMN read of a single
 * {@link DecisionExecutionRecord} answers the recorded row of {@value #OTHER}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({NoDbTestSupport.class, DecisionAuditInstanceOwnerSpringTest.Dmn.class})
@AutoConfigureTestRestTemplate
class DecisionAuditInstanceOwnerSpringTest {

    private static final String INSTANCE = "pi-756";
    private static final String OTHER = "pi-756-other";
    private static final String INPUT = "input-of-another-instance-756";
    static final List<String> AUDIT_READS = new ArrayList<>();

    @TestConfiguration
    static class Dmn {
        @Bean
        @Primary
        DmnApiClient recordedDmn() {
            return Mockito.mock(DmnApiClient.class, invocation -> {
                if (invocation.getMethod().getReturnType() == DecisionExecutionRecord.class) {
                    String id = (String) invocation.getArguments()[2];
                    return new ObjectMapper()
                            .readValue(
                                    "{\"id\":\"" + id + "\",\"instanceId\":\""
                                            + (id.equals("dec-own") ? INSTANCE : OTHER) + "\"}",
                                    DecisionExecutionRecord.class);
                }
                if (invocation.getMethod().getName().equals("auditData")) {
                    AUDIT_READS.add((String) invocation.getArguments()[2]);
                    return Map.of("decisionKey", "probe", "inputVariables", Map.of("note", INPUT));
                }
                return Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }

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
    void readable() {
        when(gate.canRead(any(), anyString())).thenReturn(true);
        AUDIT_READS.clear();
    }

    private ResponseEntity<String> audit(String decisionExecutionId) {
        return rest.withBasicAuth("viewer", "dev")
                .getForEntity(
                        "/api/instances/probe-dev/" + INSTANCE + "/decisions/" + decisionExecutionId + "/audit",
                        String.class);
    }

    @Test
    void aDecisionOfAnotherInstanceIsRefusedAndItsAuditNeverRead() {
        ResponseEntity<String> res = audit("dec-other");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("execution-instance-mismatch").doesNotContain(INPUT);
        assertThat(AUDIT_READS).isEmpty();
    }

    @Test
    void aDecisionOfTheSameInstanceIsStillServed() {
        ResponseEntity<String> res = audit("dec-own");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains(INPUT);
    }
}
