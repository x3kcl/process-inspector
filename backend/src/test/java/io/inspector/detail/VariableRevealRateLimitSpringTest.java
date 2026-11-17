package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditOutcome;
import io.inspector.audit.AuditService;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstanceRecords;
import io.inspector.client.VariableRecord;
import io.inspector.config.RevealProperties;
import io.inspector.security.ReadScopeGate;
import io.inspector.support.NoDbTestSupport;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
 * #743 — the per-user reveal rate limit ({@code inspector.reveal.max-per-minute}, default 10,
 * validated ≥ 1): the call past the limit is a 429 {@code reveal-rate-limited} with no value, the
 * breach is audited ONCE per window (not per refused call), and another user is unaffected.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "inspector.reveal.max-per-minute=2")
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
@AutoConfigureTestRestTemplate
class VariableRevealRateLimitSpringTest {

    private static final String INSTANCE = "pi-743-rate";
    private static final String VALUE = "tok-743-rate";
    private static final String PATH = "/api/instances/probe-dev/" + INSTANCE + "/variables/apiToken/reveal";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    ProcessApiClient flowable;

    @MockitoBean
    AuditService audit;

    @MockitoBean
    ReadScopeGate gate;

    @Test
    void theCallPastTheLimitIs429AndTheBreachIsAuditedOncePerWindow() throws Exception {
        when(gate.canRead(any(), anyString())).thenReturn(true);
        when(flowable.getHistoricProcessInstance(any(), any(), eq(INSTANCE)))
                .thenReturn(ProcessInstanceRecords.instance(
                        Map.of("id", INSTANCE, "processDefinitionId", "p:1:a", "startTime", "2026-11-17T07:00:00Z")));
        when(flowable.getInstanceVariable(any(), any(), eq(INSTANCE), eq("apiToken")))
                .thenReturn(new VariableRecord("apiToken", "string", VALUE, "global", null));
        AuditEntry entry = new AuditEntry(
                UUID.randomUUID(),
                "corr",
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

        ResponseEntity<String> first = post("operator");
        ResponseEntity<String> second = post("operator");
        ResponseEntity<String> third = post("operator");
        ResponseEntity<String> fourth = post("operator");
        ResponseEntity<String> otherUser = post("admin");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(mapper.readTree(third.getBody()).path("code").asText()).isEqualTo("reveal-rate-limited");
        assertThat(third.getBody()).doesNotContain(VALUE);
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.OK);
        // two reveals + ONE breach row for operator, one reveal for admin
        verify(audit, times(4))
                .beginPending(any(), any(), any(), any(), eq("reveal-variable"), any(), any(), any(), any());
        verify(audit, times(1)).close(any(), eq(AuditOutcome.failed), eq(429), any(), eq(false));
        verify(flowable, times(3)).getInstanceVariable(any(), any(), eq(INSTANCE), eq("apiToken"));
    }

    @Test
    void theLimitDefaultsToTenAndRefusesZero() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(new RevealProperties(null).maxPerMinuteOrDefault()).isEqualTo(10);
        assertThat(new RevealProperties(7).maxPerMinuteOrDefault()).isEqualTo(7);
        assertThat(validator.validate(new RevealProperties(0))).isNotEmpty();
        assertThat(validator.validate(new RevealProperties(1))).isEmpty();
    }

    private ResponseEntity<String> post(String user) {
        return rest.withBasicAuth(user, "dev").postForEntity(PATH, Map.of(), String.class);
    }
}
