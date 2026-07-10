package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: the observability endpoints are REAL, not documented fiction (IMPROVEMENT-PLAN P1 #12 /
 * Q1). Before this, RUNBOOK/OPERATIONS pointed the 3am engineer at {@code /actuator/health} and
 * {@code /actuator/prometheus} while actuator was not even a dependency (both 404'd). Proves: the
 * health probe answers unauthenticated (the container/k8s gate), the Prometheus scrape endpoint is
 * auth-gated (OPERATIONS §8) and emits real metrics, and — the drift-gate guard — the actuator
 * paths do NOT leak into the springdoc contract the frontend types are generated from (R-SEM-15).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// @SpringBootTest disables real metrics export (management.defaults.metrics.export.enabled=false) so
// tests never ship to a backend — this re-enables it so the Prometheus scrape endpoint is exercised
// for real, exactly as it registers when the app runs normally.
@AutoConfigureObservability
@Import(NoDbTestSupport.class)
class ActuatorEndpointsSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void healthProbeAnswersUnauthenticatedForTheContainerGate() {
        ResponseEntity<String> res = rest.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");

        // The liveness probe (the docker/k8s readiness gate) is also up and unauthenticated.
        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void prometheusScrapeIsGatedAndEmitsRealMetricsToAnAuthenticatedScraper() {
        // Unauthenticated: the metrics never leak. `/actuator/prometheus` is NOT in the permitAll
        // set (only `/actuator/health/**` is), so an anonymous GET is sent to authenticate first
        // (the dev chain's form-login redirect / a 401 on the oidc chain) — either way the body is
        // NOT the scrape output. A Prometheus scraper supplies basic-auth creds (OPERATIONS §8).
        ResponseEntity<String> anon = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(anon.getBody() == null || !anon.getBody().contains("jvm_memory_used_bytes"))
                .as("anonymous caller must not receive scrape metrics")
                .isTrue();

        // Authenticated: 200 with real metrics — the JVM binder is always present, and every meter
        // carries the application tag. Proves the endpoint is live, not a documented 404.
        ResponseEntity<String> res =
                rest.withBasicAuth("viewer", "dev").getForEntity("/actuator/prometheus", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("jvm_memory_used_bytes");
        assertThat(res.getBody()).contains("application=\"process-inspector\"");
    }

    @Test
    void actuatorPathsDoNotLeakIntoTheOpenApiContract() {
        // springdoc scans @RestControllers, not actuator — the committed schema.d.ts must not gain
        // actuator paths, or the CI drift gate (R-SEM-15) would break on an unrelated concern.
        ResponseEntity<String> apiDocs = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiDocs.getBody()).doesNotContain("/actuator");
    }
}
