package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.support.NoDbTestSupport;
import io.inspector.triage.ErrorGroupAck;
import io.inspector.triage.ErrorGroupAckRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3 — the R-BAU-01 acknowledge doors over real HTTP (dev basic-auth ladder, `test`
 * registry twins on closed ports). The engines being unreachable means the live triage
 * aggregation holds ZERO error groups, so an operator's structurally valid acknowledge is
 * answered {@code 409 error-group-absent} — the proof that RBAC and the reason gate
 * cleared it and the refusal came from the server-side group resolution (guard order).
 * Fail-closed audit is proven on the unacknowledge path: a failing audit INSERT yields
 * 503 and the store change is compensated (rung-1 covers the same for acknowledge).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class ErrorGroupAckRbacSpringTest {

    private static final String ACK = "/api/triage/error-groups/acknowledge";
    private static final String UNACK = "/api/triage/error-groups/unacknowledge";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ErrorGroupAckRepository acks;

    @Autowired
    AuditEntryRepository auditEntries;

    @AfterEach
    void resetMocks() {
        reset(acks, auditEntries);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private static Map<String, Object> ackBody() {
        return Map.of("signatureHash", "hash-1", "algoVersion", 1, "reason", "known outage, muting overnight");
    }

    @Test
    void unauthenticatedAcknowledgeIsShut() {
        assertThat(rest.postForEntity(ACK, ackBody(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // cookie-less POST dies at the CSRF gate — still shut
    }

    @Test
    void viewerAndResponderAreDeniedAtTheDoor() {
        assertThat(as("viewer").postForEntity(ACK, ackBody(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // OPERATOR floor, not RESPONDER: muting a whole class outranks retrying one job.
        assertThat(as("responder").postForEntity(ACK, ackBody(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as("viewer").postForEntity(UNACK, ackBody(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(as("responder").postForEntity(UNACK, ackBody(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operatorClearsTheDoorAndDiesAtServerSideGroupResolution() throws Exception {
        ResponseEntity<String> response = as("operator").postForEntity(ACK, ackBody(), String.class);
        // RBAC + reason both passed; the refusal is the live-aggregation lookup (the dead
        // engines yield zero groups) — nothing was persisted, nothing audited as ok.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("error-group-absent");
        assertThat(problem.path("title").asText()).contains("nothing happened");
        verify(acks, org.mockito.Mockito.never()).saveAll(any());
        verify(acks, org.mockito.Mockito.never()).deleteAll(any());
    }

    @Test
    void shortReasonIsANamed400BeforeGroupResolution() throws Exception {
        ResponseEntity<String> response = as("operator")
                .postForEntity(ACK, Map.of("signatureHash", "hash-1", "algoVersion", 1, "reason", "meh"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("reason-too-short");
    }

    @Test
    void staleAlgoVersionIsANamed409() throws Exception {
        ResponseEntity<String> response = as("operator")
                .postForEntity(
                        ACK,
                        Map.of("signatureHash", "hash-1", "algoVersion", 99, "reason", "known outage window"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("error-class-algo-mismatch");
    }

    @Test
    void unacknowledgeSucceedsForOperatorAndWritesTheConfigEvent() {
        when(acks.findBySignatureHashAndAlgoVersion(anyString(), anyInt())).thenReturn(List.of(ackRow()));

        ResponseEntity<String> response = as("operator")
                .postForEntity(
                        UNACK,
                        Map.of("signatureHash", "hash-1", "algoVersion", 1, "reason", "outage resolved, un-muting"),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(acks).deleteAll(any());
        verify(auditEntries).saveAndFlush(any()); // the error-group-unacknowledge config event
    }

    @Test
    void auditStoreDownMeansFailClosed503AndTheStoreChangeIsCompensated() {
        when(acks.findBySignatureHashAndAlgoVersion(anyString(), anyInt())).thenReturn(List.of(ackRow()));
        when(auditEntries.saveAndFlush(any())).thenThrow(new RuntimeException("audit store down"));

        ResponseEntity<String> response = as("operator")
                .postForEntity(
                        UNACK,
                        Map.of("signatureHash", "hash-1", "algoVersion", 1, "reason", "outage resolved, un-muting"),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(acks).saveAll(any()); // the deleted rows were re-inserted (compensation)
    }

    private static ErrorGroupAck ackRow() {
        return new ErrorGroupAck(
                "hash-1", 1, "probe-dev", "orders", "op1", "known outage window", null, Instant.now(), null, 10, 3);
    }
}
