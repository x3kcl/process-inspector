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
 * Rung 3 (#106 S0): the door check for {@code GET /api/admin/remediation-demand} is "ADMIN
 * somewhere" — the sequence findings surface cross-engine operator behavior patterns, a more
 * sensitive view than the raw per-row operations log (VIEWER-gated). The mining logic itself
 * is covered at rung 1 by {@code RemediationDemandAnalysisServiceTest}; the mocked
 * {@code AuditEntryRepository} here (via {@link NoDbTestSupport}) answers an empty audit log,
 * so this only proves the door and the response shape, not the mining behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class RemediationDemandRbacSpringTest {

    private static final String PATH = "/api/admin/remediation-demand";

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void adminReachesItEveryLesserRoleIsForbidden() {
        assertThat(as("admin").getForEntity(PATH, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String lesser : new String[] {"viewer", "responder", "operator"}) {
            assertThat(as(lesser).getForEntity(PATH, String.class).getStatusCode())
                    .as("%s must not reach the remediation-demand analysis", lesser)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void unauthenticatedIsRejected() {
        assertThat(rest.getForEntity(PATH, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anEmptyAuditLogAnswersAnHonestNoDemandVerdict() {
        String body = as("admin").getForEntity(PATH, String.class).getBody();
        assertThat(body).contains("\"demandTriggerFired\":false").contains("\"spanSufficient\":false");
    }
}
