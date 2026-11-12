package io.inspector.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.inspector.support.StubFlowableEngine;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * R-TEST-05 P3 (issue #467, TEST-STRATEGY.md §7, TRACEABILITY-MATRIX §C-8): the SSE + bulk
 * soak. The SSE hub ({@code SseHub}) and the bulk permit pool ({@code BulkJobRunner}'s
 * per-engine semaphores) are in-memory singletons; a leak there only shows under sustained
 * load, so this suite holds {@link #SUBSCRIBERS} browser-like {@code /api/bulk/events}
 * streams open for the whole soak while 600-item filter bulks run back to back against two
 * {@link StubFlowableEngine}s (300 synthetic dead-letter rows each — the same stub foundation
 * as the P1/P2 suites; scale that must never be seeded on a real engine, TEST-STRATEGY §10).
 *
 * <p><b>Duration is a parameter</b>: {@code -Dsoak.minutes=<n>} (decimal allowed, default 3).
 * The nightly job {@code soak-p3} runs the 20-minute hold; a local proof run uses a few minutes.
 *
 * <p><b>Thresholds</b> (TEST-STRATEGY §7 P3), all read from {@code /actuator/prometheus} — the
 * same surface the production alert rules evaluate:
 * <ul>
 *   <li>every job: {@code COMPLETED}, {@code totalItems == 600}, every item {@code ok} with a
 *       finish time and an audit id, and the stubs acknowledged exactly 600 more writes;</li>
 *   <li>zero dropped / duplicated events: every held subscriber saw every job id, and all
 *       subscribers saw the SAME number of {@code bulk-job} events per job (the hub fans each
 *       coalesced flush out to every live emitter, so any difference is a drop or a dup);</li>
 *   <li>{@code sse_emitters_active} stays at baseline + N for the whole hold, and returns to
 *       baseline once the clients disconnect;</li>
 *   <li>no {@code SseEmitterErrorsSpiking} condition during the hold — at most 5 increments of
 *       {@code sse_emitter_errors_total} in any 5-minute window — and no refused subscription;</li>
 *   <li>post-GC heap ({@code jvm_memory_used_bytes{area="heap"}}) at the end of the hold is
 *       within +10% of the post-warm-up baseline.</li>
 * </ul>
 *
 * <p>Docker use is runner → container only (the Testcontainers Postgres); the BFF, the stubs and
 * the SSE clients all live in this JVM, so the job needs no route from a container back to the
 * runner (the hp02 rootless-DinD slots have none).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it-p3soak")
// Re-enables the Prometheus registry @SpringBootTest switches off: the soak reads its numbers
// from the same scrape the production alert rules evaluate.
@AutoConfigureMetrics
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SseBulkSoakIT {

    private static final Logger log = LoggerFactory.getLogger(SseBulkSoakIT.class);

    private static final int ROWS_PER_ENGINE = 300;
    private static final int ITEMS_PER_JOB = 2 * ROWS_PER_ENGINE;
    private static final int SUBSCRIBERS = 50;
    private static final int WARMUP_JOBS = 2;
    private static final double HEAP_TOLERANCE = 0.10;
    private static final int ALERT_ERRORS_PER_5M = 5;
    private static final Duration HOLD =
            Duration.ofMillis((long) (Double.parseDouble(System.getProperty("soak.minutes", "3")) * 60_000));

    private static final StubFlowableEngine STUB_A = new StubFlowableEngine(ROWS_PER_ENGINE);
    private static final StubFlowableEngine STUB_B = new StubFlowableEngine(ROWS_PER_ENGINE);
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("P3_PORT_A", STUB_A::port);
        registry.add("P3_PORT_B", STUB_B::port);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Subscriber> subscribers = new ArrayList<>();
    // One cookie jar per user: Basic re-authenticates each call but keeps ONE session, as a
    // browser would. A cookie-less client would mint a new HttpSession per poll — heap growth
    // the soak would then blame on the BFF.
    private final Map<String, HttpClient> clients = new HashMap<>();

    @AfterAll
    void tearDown() {
        subscribers.forEach(Subscriber::close);
        STUB_A.close();
        STUB_B.close();
    }

    @Test
    void subscribersHeldThroughBackToBackBulksStayCompleteAndLeakFree() throws Exception {
        Metrics before = scrape();
        double emitterBaseline = before.emittersActive;

        for (int i = 0; i < SUBSCRIBERS; i++) {
            subscribers.add(new Subscriber("viewer", "soak-sse-" + i));
        }
        await("all " + SUBSCRIBERS + " streams registered")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> scrape().emittersActive == emitterBaseline + SUBSCRIBERS);

        List<String> jobIds = new ArrayList<>();
        List<Metrics> samples = new ArrayList<>();
        long heapBaseline = -1;
        long heapMax = 0;
        int writesBefore = STUB_A.instanceWriteCount() + STUB_B.instanceWriteCount();
        Instant holdStart = Instant.now();
        Instant holdEnd = holdStart.plus(HOLD);

        while (jobIds.size() < WARMUP_JOBS + 1 || Instant.now().isBefore(holdEnd)) {
            Instant jobStart = Instant.now();
            String jobId = runOneBulk();
            Duration jobTook = Duration.between(jobStart, Instant.now());
            jobIds.add(jobId);
            await("every subscriber saw job " + jobId)
                    .atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> subscribers.stream().allMatch(s -> s.count(jobId) > 0));

            System.gc(); // same JVM as the BFF: a post-GC sample is the leak signal, not GC timing
            Metrics m = scrape();
            samples.add(m);
            assertThat(m.emittersActive)
                    .as("no stream dropped or leaked mid-soak (after job %d)", jobIds.size())
                    .isEqualTo(emitterBaseline + SUBSCRIBERS);
            if (jobIds.size() == WARMUP_JOBS) {
                heapBaseline = m.heapUsed;
            }
            heapMax = Math.max(heapMax, m.heapUsed);
            log.info(
                    "P3 soak job {} ({}) settled in {} ms: heap {} MiB, emitters {}, errors {}",
                    jobIds.size(),
                    jobId,
                    jobTook.toMillis(),
                    m.heapUsed >> 20,
                    (long) m.emittersActive,
                    (long) m.emitterErrors);
        }
        Duration held = Duration.between(holdStart, Instant.now());
        Metrics end = samples.getLast();

        // ---- per-item reporting corroborated at the engine: exactly one write per item ----
        int writes = STUB_A.instanceWriteCount() + STUB_B.instanceWriteCount() - writesBefore;
        assertThat(writes).isEqualTo(jobIds.size() * ITEMS_PER_JOB);

        // ---- zero dropped / duplicated events ----
        await("event fan-out settled")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Subscriber reference = subscribers.getFirst();
                    for (String jobId : jobIds) {
                        int expected = reference.count(jobId);
                        assertThat(expected)
                                .as("job %s reached the subscribers", jobId)
                                .isPositive();
                        for (Subscriber s : subscribers) {
                            assertThat(s.count(jobId))
                                    .as("%s saw job %s the same number of times as %s", s.name, jobId, reference.name)
                                    .isEqualTo(expected);
                        }
                    }
                });
        int minPingsExpected = (int) (held.toSeconds() / 15) - 2;
        for (Subscriber s : subscribers) {
            assertThat(s.alive()).as("%s's stream is still open", s.name).isTrue();
            assertThat(s.pings.get())
                    .as("%s kept receiving heartbeats", s.name)
                    .isGreaterThanOrEqualTo(minPingsExpected);
        }

        // ---- no SseEmitterErrorsSpiking: increase(sse_emitter_errors_total[5m]) > 5 ----
        List<Metrics> window = new ArrayList<>(List.of(before));
        window.addAll(samples);
        for (int i = 0; i < window.size(); i++) {
            for (int j = i + 1; j < window.size(); j++) {
                if (Duration.between(window.get(i).at, window.get(j).at).compareTo(Duration.ofMinutes(5)) > 0) {
                    break;
                }
                assertThat(window.get(j).emitterErrors - window.get(i).emitterErrors)
                        .as("sse_emitter_errors_total increase within 5m (%s → %s)", window.get(i).at, window.get(j).at)
                        .isLessThanOrEqualTo(ALERT_ERRORS_PER_5M);
            }
        }
        assertThat(end.subscriptionsRefused - before.subscriptionsRefused)
                .as("no subscriber hit the hub cap")
                .isZero();

        // ---- heap stability ----
        assertThat(end.heapUsed)
                .as("post-GC heap at the end of the hold vs the post-warm-up baseline %d MiB", heapBaseline >> 20)
                .isLessThanOrEqualTo((long) (heapBaseline * (1 + HEAP_TOLERANCE)));

        // ---- emitter count back to baseline once the browsers go away ----
        subscribers.forEach(Subscriber::close);
        await("emitters back to baseline")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> scrape().emittersActive == emitterBaseline);
        Metrics after = scrape();

        log.info(
                "P3 soak result: held {}s, {} jobs x {} items, {} subscribers, events/job/subscriber {}..{}, "
                        + "heap baseline {} MiB, end {} MiB ({}%), max {} MiB, emitter errors during hold {}, "
                        + "on disconnect {}",
                held.toSeconds(),
                jobIds.size(),
                ITEMS_PER_JOB,
                SUBSCRIBERS,
                jobIds.stream().mapToInt(subscribers.getFirst()::count).min().orElse(0),
                jobIds.stream().mapToInt(subscribers.getFirst()::count).max().orElse(0),
                heapBaseline >> 20,
                end.heapUsed >> 20,
                String.format("%+.1f", 100.0 * (end.heapUsed - heapBaseline) / heapBaseline),
                heapMax >> 20,
                (long) (end.emitterErrors - before.emitterErrors),
                (long) (after.emitterErrors - end.emitterErrors));
    }

    /** One select-all-matching-filter suspend over both stubs' FAILED rows; returns once it settled. */
    private String runOneBulk() throws Exception {
        Map<String, Object> body = Map.of(
                "criteria", Map.of("engineIds", List.of("soak-a", "soak-b"), "statuses", List.of("FAILED")),
                "verb", "suspend",
                "reason", "P3 soak (#467): back-to-back bulk");
        JsonNode submitted = call("operator", "POST", "/api/bulk/filter", mapper.writeValueAsString(body));
        assertThat(submitted.path("totalItems").asInt()).isEqualTo(ITEMS_PER_JOB);
        String jobId = submitted.path("id").asText();

        JsonNode[] settled = new JsonNode[1];
        await("bulk job " + jobId + " settled")
                .atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    settled[0] = call("operator", "GET", "/api/bulk/" + jobId, null);
                    return !"PENDING".equals(settled[0].path("state").asText())
                            && !"RUNNING".equals(settled[0].path("state").asText());
                });
        JsonNode job = settled[0];
        assertThat(job.path("state").asText()).as("job %s", jobId).isEqualTo("COMPLETED");
        assertThat(job.path("totalItems").asInt()).isEqualTo(ITEMS_PER_JOB);
        assertThat(job.path("tallies").path("ok").asInt())
                .as("tallies %s", job.path("tallies"))
                .isEqualTo(ITEMS_PER_JOB);
        assertThat(job.path("items")).hasSize(ITEMS_PER_JOB);
        for (JsonNode item : job.path("items")) {
            assertThat(item.path("state").asText()).isEqualTo("ok");
            assertThat(item.path("finishedAt").isNull())
                    .as("item %s finished", item.path("ordinal"))
                    .isFalse();
            assertThat(item.path("auditId").isNull())
                    .as("item %s audited", item.path("ordinal"))
                    .isFalse();
        }
        return jobId;
    }

    private JsonNode call(String user, String method, String path, String json) throws Exception {
        HttpClient client = clients.computeIfAbsent(
                user,
                u -> HttpClient.newBuilder()
                        .cookieHandler(new CookieManager())
                        .connectTimeout(Duration.ofSeconds(5))
                        .build());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", basic(user));
        if (json != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("%s %s → %s", method, path, response.body())
                .isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private Metrics scrape() throws Exception {
        HttpClient client = clients.computeIfAbsent(
                "viewer",
                u -> HttpClient.newBuilder().cookieHandler(new CookieManager()).build());
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                        .header("Authorization", basic("viewer"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("/actuator/prometheus").isEqualTo(200);
        long heap = 0;
        double emitters = Double.NaN;
        double errors = 0;
        double refused = 0;
        for (String line : response.body().split("\n")) {
            if (line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("jvm_memory_used_bytes{") && line.contains("area=\"heap\"")) {
                heap += (long) value(line);
            } else if (line.startsWith("sse_emitters_active")) {
                emitters = value(line);
            } else if (line.startsWith("sse_emitter_errors_total")) {
                errors = value(line);
            } else if (line.startsWith("sse_subscriptions_refused_total")) {
                refused = value(line);
            }
        }
        assertThat(heap).as("jvm_memory_used_bytes{area=heap} is exported").isPositive();
        assertThat(emitters).as("sse_emitters_active is exported").isNotNaN();
        return new Metrics(Instant.now(), heap, emitters, errors, refused);
    }

    private static double value(String line) {
        return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":dev").getBytes(StandardCharsets.UTF_8));
    }

    private record Metrics(
            Instant at, long heapUsed, double emittersActive, double emitterErrors, double subscriptionsRefused) {}

    /**
     * A browser-like EventSource: one long-lived {@code text/event-stream} GET with its own
     * cookie jar. It keeps COUNTS only (per job id, plus heartbeats) — buffering the raw lines
     * would grow this JVM's heap and be blamed on the BFF. {@link #close()} cancels the body
     * subscription, which drops the connection from any thread (a blocking
     * {@code HttpURLConnection} reader holds a lock that {@code disconnect()} waits on).
     */
    private final class Subscriber implements Flow.Subscriber<String> {
        final String name;
        final Map<String, AtomicInteger> bulkEvents = new ConcurrentHashMap<>();
        final AtomicInteger pings = new AtomicInteger();
        final AtomicBoolean ended = new AtomicBoolean();
        private volatile Flow.Subscription subscription;
        private String event;

        Subscriber(String user, String name) {
            this.name = name;
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .cookieHandler(new CookieManager())
                    .build()
                    .sendAsync(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bulk/events"))
                                    .header("Authorization", basic(user))
                                    .header("Accept", "text/event-stream")
                                    .build(),
                            HttpResponse.BodyHandlers.fromLineSubscriber(this))
                    .whenComplete((response, error) -> ended.set(true));
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            if (line.startsWith("event:")) {
                event = line.substring(6);
            } else if (line.startsWith("data:")) {
                if ("bulk-job".equals(event)) {
                    bulkEvents
                            .computeIfAbsent(line.substring(5), k -> new AtomicInteger())
                            .incrementAndGet();
                } else if ("ping".equals(event)) {
                    pings.incrementAndGet();
                }
            } else if (line.isEmpty()) {
                event = null;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            ended.set(true);
        }

        @Override
        public void onComplete() {
            ended.set(true);
        }

        int count(String jobId) {
            AtomicInteger n = bulkEvents.get(jobId);
            return n == null ? 0 : n.get();
        }

        boolean alive() {
            return !ended.get();
        }

        void close() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
