package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3 (R-SAFE-07/R-GOV-02): the access-review export is ACCESS_ADMIN-gated (a recon oracle), and
 * emits JSON/CSV/Markdown. A lesser role or an unauthenticated caller is refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class AccessReviewSpringTest {

    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate as(String user) {
        return rest.withBasicAuth(user, "dev");
    }

    @Test
    void onlyAccessAdminMayExport() {
        assertThat(as("access-admin")
                        .getForEntity("/api/access-review", String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        for (String lesser : new String[] {"viewer", "operator", "admin", "registry-admin"}) {
            assertThat(as(lesser)
                            .getForEntity("/api/access-review", String.class)
                            .getStatusCode())
                    .as("%s must not read the access-review oracle", lesser)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(rest.getForEntity("/api/access-review", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void csvAndMarkdownFormatsAreEmitted() {
        ResponseEntity<String> csv = as("access-admin").getForEntity("/api/access-review?format=csv", String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(csv.getBody()).startsWith("grantType,group,role,engineId,tenantId,source");

        ResponseEntity<String> md = as("access-admin").getForEntity("/api/access-review?format=md", String.class);
        assertThat(md.getHeaders().getContentType().toString()).contains("text/markdown");
        assertThat(md.getBody()).contains("# Access review").contains("| Grant type |");
    }

    @Test
    void jsonCarriesTheGrantTypeColumnAndCallerGrants() {
        ResponseEntity<String> json = as("access-admin").getForEntity("/api/access-review", String.class);
        assertThat(json.getBody()).contains("\"grantType\"").contains("\"callerGrants\"");
    }
}
