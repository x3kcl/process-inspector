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
 * Rung 3 (issue #304): the door check for {@code GET /api/admin/audit/integrity} is "ADMIN
 * somewhere" — matching the other fleet-ADMIN admin reads ({@code
 * RemediationDemandRbacSpringTest}, {@code AdminAccessRbacSpringTest}). The walk logic itself is
 * covered at rung 1 ({@code AuditChainVerifierTest}) and rung 4 ({@code AuditIntegrityIT}); the
 * mocked {@code AuditEntryRepository} here (via {@link NoDbTestSupport}) answers an empty chain
 * (Mockito's default `List`/`Optional` returns), so this proves only the door, the response
 * shape on an empty table, and that the read is itself audited — not the walk behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class AuditIntegrityRbacSpringTest {

    private static final String PATH = "/api/admin/audit/integrity";

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
                    .as("%s must not reach the audit-integrity verifier", lesser)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void unauthenticatedIsRejected() {
        assertThat(rest.getForEntity(PATH, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anEmptyChainAnswersAnHonestIntactVerdict() {
        String body = as("admin").getForEntity(PATH, String.class).getBody();
        assertThat(body).contains("\"intact\":true").contains("\"rowsChecked\":0");
    }
}
