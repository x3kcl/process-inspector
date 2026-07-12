package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3 (issue #96, OPERATIONS.md §2): {@code GET /api/diag} is global ADMIN, not per-engine —
 * fleet-wide BFF-process diagnostics, so a lesser role (including an engine-scoped one) is
 * refused regardless of which engines it can operate on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class DiagRbacSpringTest {

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void adminReachesDiagEveryLesserRoleIsForbidden() {
        assertThat(as("admin").getForEntity("/api/diag", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        for (String lesser : new String[] {"viewer", "responder", "operator"}) {
            assertThat(as(lesser).getForEntity("/api/diag", String.class).getStatusCode())
                    .as("%s must not reach fleet-wide diagnostics", lesser)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void unauthenticatedIsRejected() {
        assertThat(rest.getForEntity("/api/diag", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void responseShapeCarriesEveryDiagnosticSection() {
        String body = as("admin").getForEntity("/api/diag", String.class).getBody();
        assertThat(body)
                .contains("\"breakers\"")
                .contains("\"caches\"")
                .contains("\"bulkPermits\"")
                .contains("\"recentErrors\"");
    }
}
