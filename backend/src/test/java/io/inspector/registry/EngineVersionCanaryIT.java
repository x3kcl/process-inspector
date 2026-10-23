package io.inspector.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.EngineSeed;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Guards the flowable-72 and flowable-80 CI legs (#459). Those legs run the same {@code *7IT}
 * classes as flowable-7, and every one of them reads {@code PI_ENGINE_7_PORT}. A leg still pointed
 * at 7.1.0 would otherwise go green. This class is on the 7.2 and 8.0 legs only.
 */
class EngineVersionCanaryIT {

    @Test
    void versionContains72Or80() {
        String baseUrl = "http://localhost:" + System.getenv().getOrDefault("PI_ENGINE_7_PORT", "8083")
                + "/flowable-rest/service";
        RestClient engine = EngineSeed.requireReachable(baseUrl, "--profile flowable-72 or --profile flowable-80");
        Map<?, ?> body = engine.get().uri("/management/engine").retrieve().body(Map.class);
        assertThat(body).isNotNull();
        assertThat(String.valueOf(body.get("version"))).containsPattern("7\\.2|8\\.0");
    }
}
