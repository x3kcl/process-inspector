package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
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
 * Rung 3 (#165) — the R-SAFE-05 protect/unprotect doors over real HTTP (dev basic-auth ladder,
 * `test` registry twins). Proves the ADMIN-per-engine floor is a REAL server-side gate (not
 * just the UI badge), the reason floor, the already/not-protected conflicts, and fail-closed
 * audit compensation on the unprotect path (rung-1 covers the same for protect).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class ProtectedInstanceRbacSpringTest {

    private static final String PROTECT = "/api/instances/probe-dev/pi-1/protect";
    private static final String UNPROTECT = "/api/instances/probe-dev/pi-1/unprotect";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ProtectedInstanceRepository protectedInstances;

    @Autowired
    AuditEntryRepository auditEntries;

    @AfterEach
    void resetMocks() {
        reset(protectedInstances, auditEntries);
    }

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private static Map<String, Object> body(String reason) {
        return Map.of("reason", reason);
    }

    @Test
    void unauthenticatedIsShut() {
        assertThat(rest.postForEntity(PROTECT, body("regulatory hold pending review"), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN); // cookie-less POST dies at the CSRF gate — still shut
    }

    @Test
    void viewerResponderAndOperatorAreDeniedAtTheDoor() {
        for (String user : new String[] {"viewer", "responder", "operator"}) {
            assertThat(as(user).postForEntity(PROTECT, body("regulatory hold pending review"), String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(as(user).postForEntity(UNPROTECT, body("hold lifted, resuming"), String.class)
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
        verify(protectedInstances, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void adminProtectsAndTheConfigEventIsWritten() {
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());

        ResponseEntity<String> response =
                as("admin").postForEntity(PROTECT, body("regulatory hold pending review"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(protectedInstances).saveAndFlush(any());
        verify(auditEntries).saveAndFlush(any()); // the instance-protect config event
    }

    @Test
    void protectingAnAlreadyProtectedInstanceIsANamed409() throws Exception {
        when(protectedInstances.findById(any()))
                .thenReturn(
                        Optional.of(new ProtectedInstance("probe-dev", "pi-1", "prior hold", "admin", Instant.now())));

        ResponseEntity<String> response =
                as("admin").postForEntity(PROTECT, body("regulatory hold pending review"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("already-protected");
        verify(protectedInstances, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void unprotectingAnUnprotectedInstanceIsANamed409() throws Exception {
        when(protectedInstances.findById(any())).thenReturn(Optional.empty());

        ResponseEntity<String> response =
                as("admin").postForEntity(UNPROTECT, body("hold lifted, resuming"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("not-protected");
    }

    @Test
    void adminUnprotectsAndTheConfigEventIsWritten() {
        when(protectedInstances.findById(any()))
                .thenReturn(
                        Optional.of(new ProtectedInstance("probe-dev", "pi-1", "prior hold", "admin", Instant.now())));

        ResponseEntity<String> response =
                as("admin").postForEntity(UNPROTECT, body("hold lifted, resuming"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(protectedInstances).delete(any());
        verify(auditEntries).saveAndFlush(any()); // the instance-unprotect config event
    }

    @Test
    void auditStoreDownOnUnprotectMeansFailClosed503AndTheRowIsReinserted() {
        when(protectedInstances.findById(any()))
                .thenReturn(
                        Optional.of(new ProtectedInstance("probe-dev", "pi-1", "prior hold", "admin", Instant.now())));
        when(auditEntries.saveAndFlush(any())).thenThrow(new RuntimeException("audit store down"));

        ResponseEntity<String> response =
                as("admin").postForEntity(UNPROTECT, body("hold lifted, resuming"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(protectedInstances).saveAndFlush(any()); // the deleted row was re-inserted (compensation)
    }
}
