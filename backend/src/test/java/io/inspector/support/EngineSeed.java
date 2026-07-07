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
    public static final Path FLOW_SURGERY_BPMN = Path.of("..", "docker", "processes", "demo-flow-surgery.bpmn20.xml");
    public static final Path EXTERNAL_WORKER_BPMN =
            Path.of("..", "docker", "processes", "demo-external-worker.bpmn20.xml");
    /** Out-of-scope fixture: an async-failing CMMN case, deployed over the cmmn-api ONLY. */
    public static final Path FAILING_CASE_CMMN = Path.of("..", "docker", "processes", "demo-failing-case.cmmn.xml");

    private EngineSeed() {}

    /**
     * Acquire up to {@code maxTasks} external-worker jobs on {@code topic} so their lockOwner is
     * populated — the IT setup for the lock-owner mapping assertion (v1.x #7). Hits the External
     * Worker REST API at the {@code /external-job-api} sibling of the management {@code /service}
     * base. Returns how many were acquired; the lock lapses after the duration, so KEEP-up
     * residue is self-healing.
     */
    @SuppressWarnings("unchecked")
    public static int acquireExternalWorkerJobs(String serviceBaseUrl, String topic, String workerId, int maxTasks) {
        String base = serviceBaseUrl.endsWith("/service")
                ? serviceBaseUrl.substring(0, serviceBaseUrl.length() - "/service".length()) + "/external-job-api"
                : serviceBaseUrl + "/external-job-api";
        List<Map<String, Object>> acquired = engineClient(base)
                .post()
                .uri("/acquire/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "topic", topic,
                        "lockDuration", "PT10M",
                        "maxTasks", maxTasks,
                        "numberOfRetries", 3,
                        "workerId", workerId))
                .retrieve()
                .body(List.class);
        return acquired == null ? 0 : acquired.size();
    }

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

    /**
     * Test-residue hygiene on the KEEP-up stack: an IT that seeds instances deletes ITS OWN
     * at the end — accumulated dead letters eventually cross a profile's dlq-scan-cap and
     * turn unrelated capped-scan ITs flaky. Quiet: a target already gone is fine.
     */
    public static void deleteInstanceQuietly(RestClient engine, String processInstanceId) {
        try {
            engine.delete()
                    .uri("/runtime/process-instances/" + processInstanceId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // already ended/deleted — residue cleanup must never fail a test
        }
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

    /** First dead-letter job id of an instance, or null while none exists yet. */
    @SuppressWarnings("unchecked")
    public static String deadLetterJobIdFor(RestClient engine, String processInstanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?processInstanceId=" + processInstanceId)
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return data == null || data.isEmpty()
                ? null
                : String.valueOf(data.get(0).get("id"));
    }

    public static long deadLetterCountFor(RestClient engine, String processInstanceId) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?processInstanceId=" + processInstanceId)
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }

    /* ---------------- CMMN out-of-scope fixture (cmmn-wire-shape-spike) ---------------- */

    /** The {@code /cmmn-api} sibling of a process-api {@code /service} base URL. */
    public static RestClient cmmnClient(String serviceBaseUrl) {
        String base = serviceBaseUrl.endsWith("/service")
                ? serviceBaseUrl.substring(0, serviceBaseUrl.length() - "/service".length()) + "/cmmn-api"
                : serviceBaseUrl + "/cmmn-api";
        return engineClient(base);
    }

    public static long caseDefinitionCount(RestClient cmmn, String caseKey) {
        Map<String, Object> page = cmmn.get()
                .uri("/cmmn-repository/case-definitions?key=" + caseKey + "&latest=true")
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }

    /** Deploys the CMMN case model over the cmmn-api if its key is not already present. */
    public static void deployCmmnIfMissing(RestClient cmmn, String caseKey, Path cmmnFile) {
        if (!Files.exists(cmmnFile)) {
            fail("seed CMMN not found: " + cmmnFile.toAbsolutePath());
        }
        if (caseDefinitionCount(cmmn, caseKey) == 0) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("file", new FileSystemResource(cmmnFile));
            cmmn.post()
                    .uri("/cmmn-repository/deployments")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .toBodilessEntity();
        }
        if (caseDefinitionCount(cmmn, caseKey) == 0) {
            fail("'" + caseKey + "' deployed but the case definition did not appear — parse failure?");
        }
    }

    /** Starts the async-failing case; its dead-letter job surfaces out-of-scope. Returns the case-instance id. */
    public static String startFailingCase(RestClient cmmn, String caseKey) {
        Map<String, Object> started = cmmn.post()
                .uri("/cmmn-runtime/case-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("caseDefinitionKey", caseKey))
                .retrieve()
                .body(Map.class);
        return String.valueOf(started.get("id"));
    }

    /**
     * Out-of-scope dead-letters as the inspector sees them: rows in the PROCESS-API
     * projection with no {@code processInstanceId} (a CMMN job sharing the job tables).
     * One page (size=200) — enough for a delta await on the KEEP-up stack.
     */
    @SuppressWarnings("unchecked")
    public static long outOfScopeDeadletterCount(RestClient engine) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?size=200")
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return data == null
                ? 0
                : data.stream()
                        .filter(r -> {
                            Object pid = r.get("processInstanceId");
                            return pid == null || String.valueOf(pid).isBlank();
                        })
                        .count();
    }

    /**
     * True once the PROCESS-API DLQ projection (what the inspector scans) shows an
     * out-of-scope orphan — a {@code processInstanceId:null} row carrying {@code needle} in
     * its exception. Residue-independent: keys on this test's unique failing expression, so
     * a parallel session's CMMN residue neither satisfies nor starves the await.
     */
    @SuppressWarnings("unchecked")
    public static boolean outOfScopeDeadletterPresent(RestClient engine, String needle) {
        Map<String, Object> page = engine.get()
                .uri("/management/deadletter-jobs?withException=true&size=200")
                .retrieve()
                .body(Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
        return data != null
                && data.stream().anyMatch(r -> {
                    Object pid = r.get("processInstanceId");
                    boolean orphan = pid == null || String.valueOf(pid).isBlank();
                    return orphan && String.valueOf(r.get("exceptionMessage")).contains(needle);
                });
    }

    /** Terminates a case instance (removing its dead-letter job). Quiet: a target already gone is fine. */
    public static void deleteCaseQuietly(RestClient cmmn, String caseInstanceId) {
        try {
            cmmn.delete()
                    .uri("/cmmn-runtime/case-instances/" + caseInstanceId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            // already ended/deleted — residue cleanup must never fail a test
        }
    }

    /**
     * Cascade-deletes every deployment backing a case key — hermetic teardown so no
     * dead-lettered CMMN case survives to skew a parallel session's pristine DLQ counts.
     */
    @SuppressWarnings("unchecked")
    public static void deleteCmmnDeploymentsQuietly(RestClient cmmn, String caseKey) {
        try {
            Map<String, Object> page = cmmn.get()
                    .uri("/cmmn-repository/case-definitions?key=" + caseKey + "&size=100")
                    .retrieve()
                    .body(Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) page.get("data");
            if (data == null) return;
            data.stream()
                    .map(d -> String.valueOf(d.get("deploymentId")))
                    .distinct()
                    .forEach(dep -> {
                        try {
                            cmmn.delete()
                                    .uri("/cmmn-repository/deployments/" + dep + "?cascade=true")
                                    .retrieve()
                                    .toBodilessEntity();
                        } catch (RuntimeException e) {
                            // best-effort residue cleanup
                        }
                    });
        } catch (RuntimeException e) {
            // best-effort residue cleanup
        }
    }
}
