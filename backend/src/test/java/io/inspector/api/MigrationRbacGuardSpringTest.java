package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.NoDbTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the migration preview's door RBAC (tier-3 ADMIN floor, unconditional) and guard
 * order, over real HTTP against the `test` registry twins on a closed port. Migration is
 * capability-gated (Flowable ≥ 6.5); against a never-probed closed-port engine the capability
 * gate refuses with 409 capability-unknown, which — reached only by an ADMIN — proves RBAC
 * cleared first. The deeper guards (writable, restate, cross-key, same-version) need a real
 * engine and live in MigrationIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class MigrationRbacGuardSpringTest {

    private static final String PREVIEW = "/api/instances/probe-dev/pi-1/migrate/preview";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void unauthenticatedPreviewIsShut() {
        // Cookie-less POST dies at the CSRF gate (403) before authentication.
        assertThat(rest.postForEntity(PREVIEW, Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void viewerIsDeniedAtTheAdminDoor() {
        assertThat(as("viewer").postForEntity(PREVIEW, Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operatorIsDeniedAtTheTierThreeAdminDoor() {
        // Migration is ADMIN-floor every environment (unlike change-state's OPERATOR door).
        assertThat(as("operator").postForEntity(PREVIEW, Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminClearsRbacAndIsStoppedAtTheCapabilityGate() throws Exception {
        ResponseEntity<String> response = as("admin").postForEntity(PREVIEW, Map.of(), String.class);
        // ADMIN cleared the door + service RBAC; the never-probed engine has no verified
        // migration capability, so the gate refuses rather than sending blind.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode problem = mapper.readTree(response.getBody());
        assertThat(problem.path("code").asText()).startsWith("capability-");
    }
}
