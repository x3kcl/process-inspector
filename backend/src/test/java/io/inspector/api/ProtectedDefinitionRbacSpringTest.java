package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
 * Rung 3 (#172) — the definition-key half of R-SAFE-05's protect/unprotect doors over real HTTP.
 * Mirrors {@link ProtectedInstanceRbacSpringTest} exactly (same rails, same conflict codes) since
 * both write paths share {@code ProtectedInstanceService}'s reason/RBAC/audit-compensation logic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class ProtectedDefinitionRbacSpringTest {

    private static final String PROTECT = "/api/definitions/probe-dev/payment/protect";
    private static final String UNPROTECT = "/api/definitions/probe-dev/payment/unprotect";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ProtectedDefinitionRepository protectedDefinitions;

    @Autowired
    AuditEntryRepository auditEntries;

    @AfterEach
    void resetMocks() {
        reset(protectedDefinitions, auditEntries);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private static Map<String, Object> body(String reason) {
        return Map.of("reason", reason);
    }

    @Test
    void unauthenticatedIsShut() {
        assertThat(rest.postForEntity(PROTECT, body("freeze during the v3 rollout incident"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // cookie-less POST dies at the CSRF gate — still shut
    }

    @Test
    void viewerResponderAndOperatorAreDeniedAtTheDoor() {
        for (String user : new String[] {"viewer", "responder", "operator"}) {
            assertThat(as(user).postForEntity(PROTECT, body("freeze during the v3 rollout incident"), String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(as(user).postForEntity(UNPROTECT, body("rollout stabilized, resuming"), String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void shortReasonIsANamed400BeforeAnyStoreWrite() throws Exception {
        ResponseEntity<String> response = as("admin").postForEntity(PROTECT, body("meh"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("reason-too-short");
        verify(protectedDefinitions, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void adminProtectsAndTheConfigEventIsWritten() {
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());

        ResponseEntity<String> response =
                as("admin").postForEntity(PROTECT, body("freeze during the v3 rollout incident"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(protectedDefinitions).saveAndFlush(any());
        verify(auditEntries).saveAndFlush(any()); // the definition-protect config event
    }

    @Test
    void protectingAnAlreadyProtectedDefinitionIsANamed409() throws Exception {
        when(protectedDefinitions.findById(any()))
                .thenReturn(Optional.of(
                        new ProtectedDefinition("probe-dev", "payment", "prior freeze", "admin", Instant.now())));

        ResponseEntity<String> response =
                as("admin").postForEntity(PROTECT, body("freeze during the v3 rollout incident"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("already-protected");
        verify(protectedDefinitions, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void unprotectingAnUnprotectedDefinitionIsANamed409() throws Exception {
        when(protectedDefinitions.findById(any())).thenReturn(Optional.empty());

        ResponseEntity<String> response =
                as("admin").postForEntity(UNPROTECT, body("rollout stabilized, resuming"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("not-protected");
    }

    @Test
    void adminUnprotectsAndTheConfigEventIsWritten() {
        when(protectedDefinitions.findById(any()))
                .thenReturn(Optional.of(
                        new ProtectedDefinition("probe-dev", "payment", "prior freeze", "admin", Instant.now())));

        ResponseEntity<String> response =
                as("admin").postForEntity(UNPROTECT, body("rollout stabilized, resuming"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(protectedDefinitions).delete(any());
        verify(auditEntries).saveAndFlush(any()); // the definition-unprotect config event
    }

    @Test
    void auditStoreDownOnUnprotectMeansFailClosed503AndTheRowIsReinserted() {
        when(protectedDefinitions.findById(any()))
                .thenReturn(Optional.of(
                        new ProtectedDefinition("probe-dev", "payment", "prior freeze", "admin", Instant.now())));
        when(auditEntries.saveAndFlush(any())).thenThrow(new RuntimeException("audit store down"));

        ResponseEntity<String> response =
                as("admin").postForEntity(UNPROTECT, body("rollout stabilized, resuming"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(protectedDefinitions).saveAndFlush(any()); // the deleted row was re-inserted (compensation)
    }
}
