package io.inspector.detail;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.registry.EngineHealthService;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 4 (v1.x #7) against the legacy profile (Flowable 6.3.1 — pre-external-workers): the
 * capability gate must REFUSE the fifth-queue endpoint in the BFF with a ProblemDetail, never
 * a masking empty list and never a confusing 404 from the sibling context that does not exist
 * on this engine. Mirrors the capability-cliff proof of {@link io.inspector.registry.EngineHealthLegacyIT}.
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile legacy up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_LEGACY_PASSWORD=test")
@ActiveProfiles("it-legacy")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NoDbTestSupport.class)
class ExternalWorkerJobLegacyIT {

    private static final String ENGINE = "http://localhost:"
            + System.getenv().getOrDefault("PI_ENGINE_LEGACY_PORT", "8084") + "/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EngineHealthService healthService;

    @BeforeAll
    void probe() {
        rest = rest.withBasicAuth("admin", "dev");
        EngineSeed.requireReachable(ENGINE, "--profile legacy");
        healthService.probeAll(); // externalWorkerJobs resolves to FALSE on 6.3
    }

    @Test
    void theFifthQueueEndpointIsRefusedWithAnUnsupportedVersionProblem() {
        // The capability gate fires BEFORE the instance is even resolved, so any id is fine —
        // the point is that nothing reaches the (non-existent) external-job-api on this engine.
        ResponseEntity<String> response =
                rest.getForEntity("/api/instances/engine-legacy/any-instance/jobs/external-worker", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("capability-unavailable").contains("Unsupported Engine Version");
    }
}
