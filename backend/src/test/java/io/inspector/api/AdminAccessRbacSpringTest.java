package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3 (R-SAFE-14, R-TEST-01): the apex mapping-admin surface is reachable ONLY by
 * {@code ACCESS_ADMIN}. A ladder ADMIN, a {@code REGISTRY_ADMIN}, and an unauthenticated caller are
 * all refused — a quiet reachability by any lesser role would be a Sev1 privilege escalation. Under
 * the default (file) mode the write service bean is absent, so writes 409 (CRUD disabled), while the
 * read works via the mapping seam.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class AdminAccessRbacSpringTest {

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void onlyAccessAdminReachesTheMappingRead() {
        assertThat(as("access-admin")
                        .getForEntity("/api/admin/access", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        for (String lesser : new String[] {"viewer", "responder", "operator", "admin", "registry-admin"}) {
            assertThat(as(lesser)
                            .getForEntity("/api/admin/access", String.class)
                            .getStatusCode())
                    .as("%s must not reach the apex mapping surface", lesser)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void unauthenticatedIsRejected() {
        assertThat(rest.getForEntity("/api/admin/access", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void writesAreDisabledUnderTheFileSource() {
        // No DB write service bean in file mode → 409, never a silent apply.
        Map<String, Object> body = Map.of(
                "type",
                "ladder",
                "group",
                "orders-l1",
                "role",
                "OPERATOR",
                "engineId",
                "orders-prod",
                "tenantId",
                "t1",
                "reason",
                "grant l2 operator access");
        assertThat(as("access-admin")
                        .postForEntity("/api/admin/access/grants", body, String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registryAdminCannotReachAccessAdmin() {
        // The two fleet grants are orthogonal — REGISTRY_ADMIN is NOT ACCESS_ADMIN.
        assertThat(as("registry-admin")
                        .getForEntity("/api/admin/access/proposals", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
