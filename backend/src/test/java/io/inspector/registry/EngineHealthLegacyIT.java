package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 4 against the legacy compose profile (Flowable 6.3.1 — pre every ARCH §2.5
 * version cliff): the probe must report the capabilities the engine really lacks, and
 * the lane/alarm legs must answer without 400s on the old wire shapes. No seeding —
 * this engine exists to prove the CLIFF, not the DLQ arc (that's the 6.8/7.x ITs).
 *
 * Requires: docker compose -f docker/docker-compose.dev.yml --profile legacy up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ENGINE_LEGACY_PASSWORD=test")
@ActiveProfiles("it-legacy")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// M4: docker-free profile — DB autoconfig excluded, repositories mocked (NoDbTestSupport).
@Import(NoDbTestSupport.class)
class EngineHealthLegacyIT {

    private static final String ENGINE = "http://localhost:8084/flowable-rest/service";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    EngineHealthService healthService;

    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    void requireEngine() {
        rest = rest.withBasicAuth("admin", "dev"); // M4: the BFF now requires auth on every /api route
        EngineSeed.requireReachable(ENGINE, "--profile legacy");
    }

    @Test
    void probeReportsMissingCapabilitiesOnPreCliffEngine() throws Exception {
        healthService.probeAll();

        JsonNode dto =
                mapper.readTree(rest.getForObject("/api/engines", String.class)).get(0);
        assertThat(dto.get("id").asText()).isEqualTo("engine-legacy");
        assertThat(dto.get("reachable").asBoolean()).isTrue();
        assertThat(dto.get("engineVersion").asText()).startsWith("6.3");
        assertThat(dto.get("healthError").isNull()).isTrue();

        // Pre-6.4: every version-derived capability is absent — the BFF gates, the UI greys.
        JsonNode caps = dto.get("capabilities");
        assertThat(caps.get("changeState").asBoolean()).isFalse();
        assertThat(caps.get("migration").asBoolean()).isFalse();
        assertThat(caps.get("externalWorkerJobs").asBoolean()).isFalse();
        assertThat(caps.get("scopeType").asBoolean()).isFalse();

        // Lane and alarm legs answered on the 6.3 wire shapes (no 400s, no nulls).
        JsonNode lanes = dto.get("jobLanes");
        for (String lane : new String[] {"executable", "timer", "suspended", "deadletter"}) {
            assertThat(lanes.get(lane).asLong()).as(lane).isGreaterThanOrEqualTo(0);
        }
        assertThat(dto.get("overdueTimers").isNumber()).isTrue();
        assertThat(dto.get("mode").asText()).isEqualTo("read-only");
    }
}
