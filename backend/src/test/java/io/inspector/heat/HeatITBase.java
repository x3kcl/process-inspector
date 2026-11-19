package io.inspector.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.EngineSeed;
import io.inspector.support.NoDbTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;

/**
 * Rung 4 (engine-harness, #786): definition heat through {@code GET /api/definitions/.../heat}
 * against REAL engines, seeded over REST only. {@code heatProbe} (docker/processes/heat-probe):
 * 14 instances end at {@code endA}, 8 at {@code endB}, 1 takes a detour with two more history rows
 * than the others; every instance waits on a 3 s timer, then an event-based gateway arms a
 * message (sent over REST, taken) and a one-year timer (armed, never taken). Each engine is
 * registered with a page size that fits a normal instance exactly, so the detour instance is
 * dropped as too many rows; the sample bounds are lowered to 20 (default) and 25 (clamp).
 * {@code heatProbeInstanceLevel} sets {@code flowable:historyLevel} {@code instance}.
 *
 * <p>Docker-free context (no BFF Postgres: heat is a read without audit). Deletes every instance
 * of both keys before and after, so the finished totals are exactly what it seeded.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ENGINE_HEAT_PASSWORD=test",
            "inspector.heat.sample-size=" + HeatITBase.SAMPLE_SIZE,
            "inspector.heat.max-sample-size=" + HeatITBase.MAX_SAMPLE_SIZE
        })
@ActiveProfiles("it")
@Import(NoDbTestSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureTestRestTemplate
abstract class HeatITBase {

    static final int SAMPLE_SIZE = 20;
    static final int MAX_SAMPLE_SIZE = 25;

    private static final String KEY = "heatProbe";
    private static final String INSTANCE_LEVEL_KEY = "heatProbeInstanceLevel";
    private static final Path BPMN = Path.of("..", "docker", "processes", "heat-probe.bpmn20.xml");
    private static final Path INSTANCE_LEVEL_BPMN =
            Path.of("..", "docker", "processes", "heat-probe-instance-level.bpmn20.xml");
    private static final String MESSAGE = "heatProbeGo";
    private static final int TO_A = 14;
    private static final int TO_B = 8;
    private static final int SEEDED = TO_A + TO_B + 1;
    private static final int ANALYSED = TO_A + TO_B;
    private static final int INSTANCE_LEVEL_SEEDED = 3;

    /**
     * One engine under test. {@code routes}: it records {@code sequenceFlow} rows (6.4+);
     * {@code pageSize}: the history rows of one normal {@code heatProbe} instance on it.
     */
    record Engine(String id, String url, boolean routes, int pageSize) {}

    abstract List<Engine> engines();

    /** The registry this class talks to, replacing the profile's engine list. */
    static void register(DynamicPropertyRegistry registry, List<Engine> engines) {
        for (int i = 0; i < engines.size(); i++) {
            Engine e = engines.get(i);
            String p = "inspector.engines[" + i + "].";
            registry.add(p + "id", e::id);
            registry.add(p + "name", e::id);
            registry.add(p + "base-url", e::url);
            registry.add(p + "environment", () -> "dev");
            registry.add(p + "enabled", () -> "true");
            registry.add(p + "mode", () -> "read-only");
            registry.add(p + "auth.type", () -> "basic");
            registry.add(p + "auth.username", () -> "rest-admin");
            registry.add(p + "auth.password-ref", () -> "ENGINE_HEAT_PASSWORD");
            registry.add(p + "max-page-size", () -> String.valueOf(e.pageSize()));
        }
    }

    static String url(String portEnv, String port) {
        return "http://localhost:" + System.getenv().getOrDefault(portEnv, port) + "/flowable-rest/service";
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MeterRegistry metrics;

    private final Map<String, RestClient> clients = new LinkedHashMap<>();
    private final Map<String, String> definitions = new HashMap<>();
    private final Map<String, String> instanceLevelDefinitions = new HashMap<>();

    @BeforeAll
    void seed() {
        for (Engine e : engines()) {
            RestClient engine = EngineSeed.requireReachable(e.url(), "");
            EngineSeed.deployIfMissing(engine, KEY, BPMN);
            EngineSeed.deployIfMissing(engine, INSTANCE_LEVEL_KEY, INSTANCE_LEVEL_BPMN);
            purge(engine, KEY);
            purge(engine, INSTANCE_LEVEL_KEY);
            clients.put(e.id(), engine);
            definitions.put(e.id(), latestDefinitionId(engine, KEY));
            instanceLevelDefinitions.put(e.id(), latestDefinitionId(engine, INSTANCE_LEVEL_KEY));
            for (int i = 0; i < SEEDED; i++) {
                String route = i < TO_A ? "A" : i < TO_A + TO_B ? "B" : "DETOUR";
                EngineSeed.startInstance(
                        engine,
                        KEY,
                        "it-heat-" + route + "-" + i,
                        List.of(Map.of("name", "route", "type", "string", "value", route)));
            }
            for (int i = 0; i < INSTANCE_LEVEL_SEEDED; i++) {
                EngineSeed.startInstance(engine, INSTANCE_LEVEL_KEY, "it-heat-instance-level-" + i, List.of());
            }
        }
        // Send the message to each instance as soon as its event-based gateway has armed it, so
        // the message wait stays well under the 3 s timer (the slow step). Reads poll; each send
        // happens once, outside the wait.
        int sent = 0;
        while (sent < SEEDED * engines().size()) {
            List<String[]> waiting = new ArrayList<>();
            await().atMost(60, TimeUnit.SECONDS)
                    .pollInterval(200, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        waiting.clear();
                        clients.forEach((id, engine) -> waitingExecutions(engine)
                                .forEach(execution -> waiting.add(new String[] {id, execution})));
                        return !waiting.isEmpty();
                    });
            for (String[] w : waiting) {
                clients.get(w[0])
                        .put()
                        .uri("/runtime/executions/" + w[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("action", "messageEventReceived", "messageName", MESSAGE))
                        .retrieve()
                        .toBodilessEntity();
                sent++;
            }
        }
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> clients.forEach((id, engine) -> {
                    assertThat(finishedCount(engine, KEY)).as(id).isEqualTo(SEEDED);
                    assertThat(finishedCount(engine, INSTANCE_LEVEL_KEY)).as(id).isEqualTo(INSTANCE_LEVEL_SEEDED);
                }));
    }

    @AfterAll
    void cleanup() {
        clients.values().forEach(engine -> {
            purge(engine, KEY);
            purge(engine, INSTANCE_LEVEL_KEY);
        });
    }

    @Test
    void heatMatchesTheSeededRunsOnTheAggregateLaneAndASecondCallIsServedFromTheCache() throws Exception {
        for (Engine e : engines()) {
            String id = e.id();
            double before = aggregateCalls(id);

            JsonNode h = heat(id, definitions.get(id), "?n=1000");

            double gathered = aggregateCalls(id) - before;
            assertThat(h.path("cached").asBoolean()).as(id).isFalse();
            // Every engine read of the gather ran on the AGGREGATE lane: the definition, the
            // sample pages, one activity page per sampled instance, and the BPMN.
            assertThat(gathered).as(id).isGreaterThanOrEqualTo(SEEDED + 3);
            assertThat(h.path("coverage").asText()).as(id).isEqualTo(e.routes() ? "FULL" : "NO_FLOW_HISTORY");
            assertThat(h.path("structure").asText()).as(id).isEqualTo("RESOLVED");
            assertThat(h.path("requestedN").asInt()).as(id).isEqualTo(MAX_SAMPLE_SIZE);
            assertThat(h.path("sampledInstances").asInt()).as(id).isEqualTo(SEEDED);
            assertThat(h.path("finishedTotal").asLong()).as(id).isEqualTo(SEEDED);
            assertThat(h.path("droppedInstances").asInt()).as(id).isEqualTo(1);
            assertThat(h.path("dropReasons").path("tooManyRows").asInt()).as(id).isEqualTo(1);
            assertThat(h.path("dropReasons").path("readFailed").asInt()).as(id).isZero();
            assertThat(h.path("dropReasons").path("noActivityHistory").asInt())
                    .as(id)
                    .isZero();
            assertThat(h.path("partial").asBoolean()).as(id).isTrue();
            assertThat(h.path("samplingNote").asText())
                    .as(id)
                    .isEqualTo("All 23 finished instances; 1 dropped, "
                            + "figures over the remaining 22"
                            + (e.routes()
                                    ? ""
                                    : "; event-gateway branches inferred from timing, this engine records no routes")
                            + ".");

            Map<String, JsonNode> nodes = byId(h.path("nodes"), "activityId");
            assertThat(nodes)
                    .as(id)
                    .containsOnlyKeys("start", "coolDown", "waitGw", "catchGo", "catchYear", "routeGw", "endA", "endB");
            for (String visited : List.of("start", "coolDown", "waitGw", "catchGo", "routeGw")) {
                assertHits(id, nodes.get(visited), ANALYSED, 0, ANALYSED);
            }
            assertHits(id, nodes.get("endA"), TO_A, 0, ANALYSED);
            assertHits(id, nodes.get("endB"), TO_B, 0, ANALYSED);
            // Armed by the event-based gateway in every instance, never taken.
            assertHits(id, nodes.get("catchYear"), 0, ANALYSED, ANALYSED);
            assertThat(nodes.get("catchYear").path("avgMs").isNull()).as(id).isTrue();
            assertThat(nodes.get("catchYear").path("totalMs").asLong()).as(id).isZero();

            JsonNode slow = nodes.get("coolDown");
            assertThat(slow.path("avgMs").asDouble()).as(id).isGreaterThanOrEqualTo(3000.0);
            assertThat(slow.path("p50Ms").asLong()).as(id).isGreaterThanOrEqualTo(3000L);
            assertThat(slow.path("p95Ms").asLong())
                    .as(id)
                    .isGreaterThanOrEqualTo(slow.path("p50Ms").asLong());
            assertThat(slow.path("maxMs").asLong())
                    .as(id)
                    .isGreaterThanOrEqualTo(slow.path("p95Ms").asLong());
            for (JsonNode n : nodes.values()) {
                if (!n.path("p95Ms").isNull()) {
                    assertThat(n.path("p50Ms").asLong())
                            .as(id + " " + n)
                            .isLessThanOrEqualTo(n.path("p95Ms").asLong());
                }
                if (n != slow && !n.path("avgMs").isNull()) {
                    assertThat(n.path("avgMs").asDouble())
                            .as(id + " " + n)
                            .isLessThan(slow.path("avgMs").asDouble());
                }
            }
            assertThat(slow.path("tier").asText()).as(id).isEqualTo("DOMINANT");

            Map<String, JsonNode> outcomes = byId(h.path("outcomes"), "endEventId");
            assertThat(outcomes).as(id).containsOnlyKeys("endA", "endB");
            assertThat(outcomes.get("endA").path("name").asText()).as(id).isEqualTo("Approved");
            assertThat(outcomes.get("endA").path("count").asInt()).as(id).isEqualTo(TO_A);
            assertThat(outcomes.get("endA").path("pct").asDouble())
                    .as(id)
                    .isCloseTo(TO_A * 100.0 / ANALYSED, within(1e-9));
            assertThat(outcomes.get("endB").path("name").asText()).as(id).isEqualTo("Rejected");
            assertThat(outcomes.get("endB").path("count").asInt()).as(id).isEqualTo(TO_B);
            assertThat(outcomes.get("endB").path("pct").asDouble())
                    .as(id)
                    .isCloseTo(TO_B * 100.0 / ANALYSED, within(1e-9));
            assertThat(h.path("noEndEventCount").asInt()).as(id).isZero();

            // Within the TTL: the same answer from the cache, not one engine call.
            double beforeCached = aggregateCalls(id);
            JsonNode again = heat(id, definitions.get(id), "?n=1000");
            assertThat(again.path("cached").asBoolean()).as(id).isTrue();
            assertThat(again.path("asOf").asText())
                    .as(id)
                    .isEqualTo(h.path("asOf").asText());
            assertThat(aggregateCalls(id)).as(id).isEqualTo(beforeCached);
        }
    }

    @Test
    void theSampleIsTheNewestNOfTheFinishedTotal() throws Exception {
        for (Engine e : engines()) {
            String id = e.id();

            JsonNode byDefault = heat(id, definitions.get(id), "");
            JsonNode five = heat(id, definitions.get(id), "?n=5");

            assertThat(byDefault.path("requestedN").asInt()).as(id).isEqualTo(SAMPLE_SIZE);
            assertThat(byDefault.path("sampledInstances").asInt()).as(id).isEqualTo(SAMPLE_SIZE);
            assertThat(byDefault.path("finishedTotal").asLong()).as(id).isEqualTo(SEEDED);
            assertThat(byDefault.path("samplingNote").asText())
                    .as(id)
                    .startsWith("Newest 20 of 23 finished instances (sample, not the full history)");
            assertThat(five.path("requestedN").asInt()).as(id).isEqualTo(5);
            assertThat(five.path("sampledInstances").asInt()).as(id).isEqualTo(5);
            assertThat(five.path("finishedTotal").asLong()).as(id).isEqualTo(SEEDED);
            List<Instant> newest = newestEnds(clients.get(id), definitions.get(id), 5);
            assertThat(Instant.parse(five.path("newestEnd").asText())).as(id).isEqualTo(newest.get(0));
            assertThat(Instant.parse(five.path("oldestEnd").asText())).as(id).isEqualTo(newest.get(4));
        }
    }

    @Test
    void aSecondRefreshWithinSixtySecondsIsThrottledAndServedFromTheCache() throws Exception {
        for (Engine e : engines()) {
            String id = e.id();

            JsonNode refreshed = heat(id, definitions.get(id), "?n=7&refresh=true");
            double before = aggregateCalls(id);
            Instant asked = Instant.now();
            JsonNode throttled = heat(id, definitions.get(id), "?n=7&refresh=true");

            assertThat(refreshed.path("cached").asBoolean()).as(id).isFalse();
            assertThat(refreshed.path("refreshThrottledUntil").isNull()).as(id).isTrue();
            assertThat(throttled.path("cached").asBoolean()).as(id).isTrue();
            Instant until =
                    Instant.parse(throttled.path("refreshThrottledUntil").asText());
            assertThat(until).as(id).isAfter(asked).isBeforeOrEqualTo(asked.plusSeconds(60));
            assertThat(aggregateCalls(id)).as(id).isEqualTo(before);
        }
    }

    @Test
    void aDefinitionWithInstanceHistoryLevelSaysItHasNoActivityHistory() throws Exception {
        for (Engine e : engines()) {
            String id = e.id();

            JsonNode h = heat(id, instanceLevelDefinitions.get(id), "");

            assertThat(h.path("sampledInstances").asInt()).as(id).isEqualTo(INSTANCE_LEVEL_SEEDED);
            assertThat(h.path("finishedTotal").asLong()).as(id).isEqualTo(INSTANCE_LEVEL_SEEDED);
            assertThat(h.path("droppedInstances").asInt()).as(id).isZero();
            if (e.routes()) {
                assertThat(h.path("coverage").asText()).as(id).isEqualTo("NO_ACTIVITY_HISTORY");
                assertThat(h.path("nodes")).as(id).isEmpty();
                assertThat(h.path("outcomes")).as(id).isEmpty();
            } else {
                // FINDING (#786): Flowable 6.3.1 ignores a per-process `instance` level and keeps
                // writing activity rows, so the definition has node figures like any other.
                assertThat(h.path("coverage").asText()).as(id).isEqualTo("NO_FLOW_HISTORY");
                Map<String, JsonNode> nodes = byId(h.path("nodes"), "activityId");
                assertThat(nodes).as(id).containsOnlyKeys("start", "end");
                assertHits(id, nodes.get("end"), INSTANCE_LEVEL_SEEDED, 0, INSTANCE_LEVEL_SEEDED);
            }
        }
    }

    /* ---------------------------------------------------------------------------------------- */

    private static void assertHits(String engineId, JsonNode node, int instances, int armed, int analysed) {
        String as = engineId + " " + node.path("activityId").asText();
        assertThat(node.path("executions").asInt()).as(as).isEqualTo(instances);
        assertThat(node.path("instances").asInt()).as(as).isEqualTo(instances);
        assertThat(node.path("hitPct").asDouble()).as(as).isCloseTo(instances * 100.0 / analysed, within(1e-9));
        assertThat(node.path("armedNotTaken").asInt()).as(as).isEqualTo(armed);
    }

    private JsonNode heat(String engineId, String definitionId, String query) throws Exception {
        ResponseEntity<String> response = rest.withBasicAuth("viewer", "dev")
                .getForEntity("/api/definitions/" + engineId + "/" + definitionId + "/heat" + query, String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private double aggregateCalls(String engineId) {
        Timer timer = metrics.find("engine_fanout_duration_seconds")
                .tag("engineId", engineId)
                .tag("leg", "AGGREGATE")
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private static Map<String, JsonNode> byId(JsonNode array, String field) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        array.forEach(n -> out.put(n.path(field).asText(), n));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> waitingExecutions(RestClient engine) {
        Map<String, Object> page = engine.get()
                .uri("/runtime/executions?processDefinitionKey=" + KEY + "&messageEventSubscriptionName=" + MESSAGE
                        + "&size=100")
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream().map(x -> String.valueOf(x.get("id"))).toList();
    }

    @SuppressWarnings("unchecked")
    private static long finishedCount(RestClient engine, String key) {
        Map<String, Object> page = engine.get()
                .uri("/history/historic-process-instances?processDefinitionKey=" + key + "&finished=true&size=1")
                .retrieve()
                .body(Map.class);
        return ((Number) page.get("total")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Instant> newestEnds(RestClient engine, String definitionId, int n) {
        Map<String, Object> page = engine.get()
                .uri("/history/historic-process-instances?processDefinitionId=" + definitionId
                        + "&finished=true&sort=endTime&order=desc&size=" + n)
                .retrieve()
                .body(Map.class);
        return ((List<Map<String, Object>>) page.get("data"))
                .stream()
                        .map(r -> OffsetDateTime.parse(String.valueOf(r.get("endTime")))
                                .toInstant())
                        .toList();
    }

    @SuppressWarnings("unchecked")
    private static String latestDefinitionId(RestClient engine, String key) {
        Map<String, Object> page = engine.get()
                .uri("/repository/process-definitions?key=" + key + "&latest=true")
                .retrieve()
                .body(Map.class);
        return String.valueOf(
                ((List<Map<String, Object>>) page.get("data")).get(0).get("id"));
    }

    /** Every instance of {@code key}, running or finished, deleted from runtime and history. */
    @SuppressWarnings("unchecked")
    private static void purge(RestClient engine, String key) {
        Map<String, Object> page = engine.get()
                .uri("/history/historic-process-instances?processDefinitionKey=" + key + "&size=1000")
                .retrieve()
                .body(Map.class);
        for (Map<String, Object> row : (List<Map<String, Object>>) page.get("data")) {
            String id = String.valueOf(row.get("id"));
            if (row.get("endTime") == null) {
                EngineSeed.deleteInstanceQuietly(engine, id);
            }
            try {
                engine.delete()
                        .uri("/history/historic-process-instances/" + id)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RuntimeException e) {
                // already gone
            }
        }
    }
}
