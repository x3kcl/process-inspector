package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.audit.InstanceNote;
import io.inspector.audit.InstanceNoteRepository;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the RBAC tier ladder and the guard order over real HTTP against the `test`
 * registry twins (probe-dev = dev/read-write, probe-prod = prod/read-only, both on a
 * closed port). A 502 engine-unreachable is the PROOF that every BFF-side guard passed —
 * the request died at the engine hop, after RBAC/mode/protection cleared it. Postgres
 * repositories are the NoDbTestSupport mocks; real-DB behavior is CorrectiveActionIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class ActionRbacGuardSpringTest {

    private static final String RETRY = "/api/instances/probe-dev/pi-1/actions/retry-job";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ProtectedInstanceRepository protectedInstances;

    @Autowired
    InstanceNoteRepository notes;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    private static Map<String, Object> retryBody() {
        return Map.of("jobId", "j1");
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        assertThat(rest.getForEntity("/api/engines", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Cookie-less POST dies at the CSRF gate (403) before authentication — still shut.
        assertThat(rest.postForEntity(RETRY, retryBody(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewerIsDeniedEveryMutation() {
        ResponseEntity<String> response = as("viewer").postForEntity(RETRY, retryBody(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void responderMayRetryButTheDeadEngineAnswersUnreachable() throws Exception {
        ResponseEntity<String> response = as("responder").postForEntity(RETRY, retryBody(), String.class);
        // RBAC + mode + protection all passed; the request died at the (closed-port) engine.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("engine-unreachable");
        assertThat(problem.path("title").asText()).contains("nothing happened");
    }

    @Test
    void responderIsDeniedTierOneVariableEdits() {
        Map<String, Object> body = Map.of("variable", Map.of("name", "amount", "value", 1, "expectedOldValue", 0));
        ResponseEntity<String> response = as("responder")
                .postForEntity("/api/instances/probe-dev/pi-1/actions/edit-variable", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operatorIsDeniedTierThree() {
        ResponseEntity<String> response = as("operator")
                .postForEntity(
                        "/api/instances/probe-dev/pi-1/actions/terminate-delete",
                        Map.of("reason", "cleanup of stuck case"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void readOnlyEngineRefusesEvenAdmins() throws Exception {
        ResponseEntity<String> response = as("admin")
                .postForEntity("/api/instances/probe-prod/pi-1/actions/retry-job", retryBody(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("engine-read-only");
    }

    @Test
    void protectedInstanceBlocksBelowAdmin() throws Exception {
        when(protectedInstances.findById(any()))
                .thenReturn(Optional.of(new ProtectedInstance(
                        "probe-dev", "pi-guarded", "regulatory hold — case 4711", "admin", Instant.now())));
        try {
            ResponseEntity<String> response = as("operator")
                    .postForEntity("/api/instances/probe-dev/pi-guarded/actions/retry-job", retryBody(), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            JsonNode problem = mapper.readTree(response.getBody());
            assertThat(problem.path("code").asText()).isEqualTo("instance-protected");
            assertThat(problem.path("detail").asText()).contains("regulatory hold");
        } finally {
            when(protectedInstances.findById(any())).thenReturn(Optional.empty());
        }
    }

    @Test
    void unknownVerbIsNotFoundNotForbidden() {
        ResponseEntity<String> response = as("admin")
                .postForEntity("/api/instances/probe-dev/pi-1/actions/reticulate-splines", Map.of(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void definitionVerbRejectsTheInstanceRoute() {
        ResponseEntity<String> response = as("admin")
                .postForEntity(
                        "/api/instances/probe-dev/pi-1/actions/suspend-definition",
                        Map.of("reason", "bad deploy brake"),
                        String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingVerbFieldIsANamed400() throws Exception {
        ResponseEntity<String> response = as("responder").postForEntity(RETRY, Map.of(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).isEqualTo("missing-field");
        assertThat(problem.path("detail").asText()).contains("jobId");
    }

    @Test
    void notesAreWritableFromResponderUpOnly() {
        when(notes.save(any(InstanceNote.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<String> viewer = as("viewer")
                .postForEntity(
                        "/api/instances/probe-dev/pi-1/notes", Map.of("body", "tried nothing yet"), String.class);
        assertThat(viewer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> responder = as("responder")
                .postForEntity(
                        "/api/instances/probe-dev/pi-1/notes",
                        Map.of("body", "retried job j1, waiting on DLQ"),
                        String.class);
        assertThat(responder.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void auditReadsAreOpenToViewers() {
        ResponseEntity<String> response =
                as("viewer").getForEntity("/api/instances/probe-dev/pi-1/audit", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
