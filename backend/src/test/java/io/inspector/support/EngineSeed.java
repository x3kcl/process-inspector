package io.inspector.support;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
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
    public static final Path FAILING_RETRY_BPMN = Path.of("..", "docker", "processes", "demo-failing-retry.bpmn20.xml");
    public static final Path PARENT_BPMN = Path.of("..", "docker", "processes", "demo-parent.bpmn20.xml");
    public static final Path USER_TASK_BPMN = Path.of("..", "docker", "processes", "demo-user-task.bpmn20.xml");
    public static final Path ORDER_BPMN = Path.of("..", "docker", "processes", "demo-order.bpmn20.xml");

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

    /** Latest deployed version of a definition key (0 when absent). */
    public static int latestVersion(RestClient engine, String key) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&latest=true")
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return data == null || data.isEmpty() ? 0 : ((Number) data.get(0).get("version")).intValue();
    }

    /**
     * Force-deploys a NEW version of an already-deployed key by appending a unique XML
     * comment (Flowable only bumps the version when the content changed — validate-bpmn §3).
     * Returns the new latest version, asserted to have actually bumped.
     */
    public static int deployNewVersion(RestClient engine, String key, Path bpmn) {
        int before = latestVersion(engine, key);
        String xml;
        try {
            xml = Files.readString(bpmn)
                    .replace("</definitions>", "<!-- version-bump " + UUID.randomUUID() + " --></definitions>");
        } catch (IOException e) {
            fail("cannot read seed BPMN " + bpmn.toAbsolutePath() + ": " + e);
            return -1; // unreachable
        }
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return bpmn.getFileName().toString();
            }
        });
        engine.post()
                .uri("/repository/deployments")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toBodilessEntity();
        int after = latestVersion(engine, key);
        if (after <= before) {
            fail("'" + key + "' redeploy did not bump the version (" + before + " -> " + after + ")");
        }
        return after;
    }

    /** All deployed versions of a key, ascending. */
    public static List<Integer> deployedVersions(RestClient engine, String key) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&size=100")
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return data.stream()
                .map(d -> ((Number) d.get("version")).intValue())
                .sorted()
                .toList();
    }

    /** Starts the straight-through fixture — COMPLETED by the time the call returns. */
    public static String startCompletedOrder(RestClient engine) {
        return startInstance(engine, "demoOrder", null, List.of());
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

    /** Starts any seed process over REST with the organically-failing variable pair. */
    public static String startFailing(RestClient engine, String definitionKey, String businessKey) {
        return startInstance(
                engine,
                definitionKey,
                businessKey,
                List.of(
                        Map.of("name", "amount", "type", "integer", "value", 100),
                        Map.of("name", "divisor", "type", "integer", "value", 0)));
    }

    public static String startInstance(
            RestClient engine, String definitionKey, String businessKey, List<Map<String, Object>> variables) {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("processDefinitionKey", definitionKey);
        body.put("variables", variables);
        if (businessKey != null) body.put("businessKey", businessKey);
        Map<String, Object> started = engine.post()
                .uri("/runtime/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    /** SUSPENDED is a runtime REST action — BPMN cannot suspend itself. */
    public static void suspend(RestClient engine, String processInstanceId) {
        engine.put()
                .uri("/runtime/process-instances/" + processInstanceId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("action", "suspend"))
                .retrieve()
                .toBodilessEntity();
    }

    /** The call-activity child of a parent instance (created synchronously at parent start). */
    public static String childInstanceOf(RestClient engine, String parentInstanceId) {
        Map<String, Object> page = engine.post()
                .uri("/query/historic-process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("superProcessInstanceId", parentInstanceId, "size", 5))
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        if (data == null || data.isEmpty()) {
            fail("no call-activity child found for parent " + parentInstanceId);
        }
        return String.valueOf(data.get(0).get("id"));
    }

    /** RETRYING evidence: failing-with-retries-left jobs park in the TIMER lane (withException). */
    public static long failingTimerCountFor(RestClient engine, String processInstanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/timer-jobs?withException=true&processInstanceId=" + processInstanceId)
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }

    public static long deadLetterCountFor(RestClient engine, String processInstanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?processInstanceId=" + processInstanceId)
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }
}
