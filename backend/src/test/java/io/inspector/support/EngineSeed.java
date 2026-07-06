package io.inspector.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * REST-only seeding against a dockerized flowable-rest engine (engine-harness skill).
 * Never touches engine tables; deployment success is asserted via the definition list,
 * not the 2xx (validate-bpmn §3).
 */
public final class EngineSeed {

    public static final Path FAILING_PAYMENT_BPMN =
            Path.of("..", "docker", "processes", "demo-failing-payment.bpmn20.xml");

    private EngineSeed() {}

    public static RestClient engineClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBasicAuth("rest-admin", "test"))
                .build();
    }

    /** Loud fail with the compose command — a down engine must never look like a green skip. */
    public static RestClient requireReachable(String baseUrl, String composeProfileHint) {
        RestClient engine = engineClient(baseUrl);
        try {
            engine.get().uri("/management/engine").retrieve().toBodilessEntity();
        } catch (Exception e) {
            fail("engine at " + baseUrl + " is not reachable — start the harness first: "
                    + "docker compose -f docker/docker-compose.dev.yml " + composeProfileHint
                    + " up -d  (" + e.getMessage() + ")");
        }
        return engine;
    }

    public static long definitionCount(RestClient engine, String key) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&latest=true")
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }

    public static void deployIfMissing(RestClient engine, String key, Path bpmn) {
        if (!Files.exists(bpmn)) {
            fail("seed BPMN not found: " + bpmn.toAbsolutePath());
        }
        if (definitionCount(engine, key) == 0) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", new FileSystemResource(bpmn));
            engine.post()
                    .uri("/repository/deployments")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .toBodilessEntity();
        }
        if (definitionCount(engine, key) == 0) {
            fail("'" + key + "' deployed but the definition did not appear — parse failure?");
        }
    }

    /** Starts the organically-dead-lettering fixture (divisor=0). Returns the instance id. */
    public static String startFailingPayment(RestClient engine) {
        Map<String, Object> started = engine.post()
                .uri("/runtime/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "processDefinitionKey",
                        "demoFailingPayment",
                        "variables",
                        List.of(
                                Map.of("name", "amount", "type", "integer", "value", 100),
                                Map.of("name", "divisor", "type", "integer", "value", 0))))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    public static long deadLetterCountFor(RestClient engine, String processInstanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?processInstanceId=" + processInstanceId)
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }
}
