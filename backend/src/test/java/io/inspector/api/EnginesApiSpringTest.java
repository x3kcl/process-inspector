package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.InspectorProperties.EngineMode;
import io.inspector.registry.EngineHealthService;
import io.inspector.support.NoDbTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Rung 3: wiring only — registry YAML binding (enums, defaults, truncation knobs) and
 * the graceful /api/engines contract with both test engines pointing at a closed port.
 * Engine wire-shape behavior is rung 4 (EngineHealthIT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// M4: docker-free profile — DB autoconfig excluded, repositories mocked (NoDbTestSupport).
@Import(NoDbTestSupport.class)
class EnginesApiSpringTest {

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void authenticate() {
        // M4: the BFF now requires auth on every /api route
        rest = rest.withBasicAuth("admin", "dev");
    }

    @Autowired
    InspectorProperties props;

    @Autowired
    EngineHealthService healthService;

    @Autowired
    ObjectMapper mapper;

    @Test
    void registryBindsEnumsAndKnobsFromYaml() {
        assertThat(props.engines()).hasSize(2);
        var dev = props.engines().get(0);
        var prod = props.engines().get(1);

        assertThat(dev.id()).isEqualTo("probe-dev");
        assertThat(dev.environment()).isEqualTo(EngineEnvironment.DEV);
        assertThat(dev.modeOrDefault()).isEqualTo(EngineMode.READ_WRITE);
        assertThat(prod.environment()).isEqualTo(EngineEnvironment.PROD);
        assertThat(prod.modeOrDefault()).isEqualTo(EngineMode.READ_ONLY);

        // truncation knobs from the test profile
        assertThat(dev.maxPageSizeOrDefault()).isEqualTo(10);
        assertThat(dev.dlqScanCapOrDefault()).isEqualTo(50);
        // defaults where the YAML is silent
        assertThat(dev.timeoutsOrDefault().write())
                .isEqualTo(dev.timeoutsOrDefault().read());
        assertThat(dev.alarmsOrDefault().oldestJobWarnMinOrDefault()).isEqualTo(5);
        assertThat(dev.alarmsOrDefault().oldestJobCritMinOrDefault()).isEqualTo(15);
        assertThat(dev.alarmsOrDefault().overdueTimerGraceSOrDefault()).isEqualTo(60);
    }

    @Test
    void unreachableEnginesDegradeInsideA200List() throws Exception {
        healthService.probeAll(); // deterministic — no schedule waiting

        ResponseEntity<String> response = rest.getForEntity("/api/engines", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode engines = mapper.readTree(response.getBody());
        assertThat(engines).hasSize(2);
        for (JsonNode engine : engines) {
            assertThat(engine.get("reachable").asBoolean()).isFalse();
            assertThat(engine.get("healthError").isNull()).isFalse();
            assertThat(engine.get("lastHealthCheck").isNull()).isFalse();
        }
        assertThat(engines.get(0).get("environment").asText()).isEqualTo("dev");
        assertThat(engines.get(1).get("environment").asText()).isEqualTo("prod");
        assertThat(engines.get(1).get("mode").asText()).isEqualTo("read-only");
        // W1#4 (theme T6): lifecycle rides the same list so policy is visible at point of action.
        assertThat(engines.get(0).get("lifecycle").asText()).isEqualTo("active");
        assertThat(engines.get(1).get("lifecycle").asText()).isEqualTo("active");
    }

    @Test
    void apiPayloadNeverLeaksSecretMaterial() {
        String body = rest.getForObject("/api/engines", String.class);

        // No secrets, no secret REFS, no env-var names — R-SEC doctrine.
        assertThat(body).doesNotContainIgnoringCase("password");
        assertThat(body).doesNotContain("passwordRef", "tokenRef", "ENGINE_");
    }
}
