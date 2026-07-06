package io.inspector.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One authenticated {@link RestClient} per registered engine, built lazily and cached.
 * All Flowable V6/V7 REST calls go through here so auth, timeouts, resiliency and error
 * translation live in exactly one place.
 *
 * Do-no-harm (ARCHITECTURE §2.2): every call runs through a per-engine Resilience4j
 * circuit breaker (config "engine") — a sick engine fast-fails alone instead of
 * starving BFF threads. {@code CallNotPermittedException} propagates to callers, which
 * translate it into partial-result envelopes.
 */
@Component
public class FlowableEngineClient {

    private final Environment env;
    private final CircuitBreakerRegistry breakers;
    private final Map<String, RestClient> readClients = new ConcurrentHashMap<>();
    // Mutating calls get their own client so write-ms (R-NFR-07) budgets them separately.
    private final Map<String, RestClient> writeClients = new ConcurrentHashMap<>();

    public FlowableEngineClient(Environment env, CircuitBreakerRegistry breakers) {
        this.env = env;
        this.breakers = breakers;
    }

    /* ---------- Flowable REST calls (the whitelist the BFF exposes) ---------- */

    /** POST /query/historic-process-instances — the primary search query. */
    public FlowablePage queryHistoricProcessInstances(EngineConfig engine, Map<String, Object> body) {
        return guarded(engine, () -> client(engine).post()
                .uri("/query/historic-process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FlowablePage.class));
    }

    /** POST /query/process-instances — runtime query (used for the suspended-ID set). */
    public FlowablePage queryRuntimeProcessInstances(EngineConfig engine, Map<String, Object> body) {
        return guarded(engine, () -> client(engine).post()
                .uri("/query/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FlowablePage.class));
    }

    /** GET /management/deadletter-jobs — the failed-instance join set. */
    public FlowablePage listDeadLetterJobs(EngineConfig engine, int size) {
        // No sort param: createTime is not an accepted job sort property, and the
        // membership join doesn't care about order.
        return guarded(engine, () -> client(engine).get()
                .uri(uri -> uri.path("/management/deadletter-jobs")
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(FlowablePage.class));
    }

    /** GET /management/engine — health + version probe. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> engineInfo(EngineConfig engine) {
        return guarded(engine, () -> client(engine).get()
                .uri("/management/engine")
                .retrieve()
                .body(Map.class));
    }

    /* ---------- M1 health-strip calls: size=1 totals, never row fetches ---------- */

    /** The four job queues (flowable-rest skill §3) and their management paths. */
    public enum JobLaneKind {
        EXECUTABLE("/management/jobs"),
        TIMER("/management/timer-jobs"),
        SUSPENDED("/management/suspended-jobs"),
        DEADLETTER("/management/deadletter-jobs");

        final String path;
        JobLaneKind(String path) { this.path = path; }
    }

    /** Lane count via the size=1 total trick — no rows transferred. */
    public long countJobs(EngineConfig engine, JobLaneKind lane) {
        FlowablePage page = guarded(engine, () -> client(engine).get()
                .uri(uri -> uri.path(lane.path).queryParam("size", 1).build())
                .retrieve()
                .body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /** Oldest executable job row (dueDate asc), or null when the lane is empty. */
    public Map<String, Object> oldestExecutableJob(EngineConfig engine) {
        FlowablePage page = guarded(engine, () -> client(engine).get()
                .uri(uri -> uri.path(JobLaneKind.EXECUTABLE.path)
                        .queryParam("size", 1)
                        .queryParam("sort", "dueDate")
                        .queryParam("order", "asc")
                        .build())
                .retrieve()
                .body(FlowablePage.class));
        List<Map<String, Object>> rows = page != null ? page.dataOrEmpty() : List.of();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Count of timers already past due at {@code dueBefore} — the overdue-timer alarm. */
    public long countOverdueTimers(EngineConfig engine, Instant dueBefore) {
        // Whole seconds only: Flowable's date parsing 400s on fractional seconds.
        String dueBeforeIso = dueBefore.truncatedTo(ChronoUnit.SECONDS).toString();
        FlowablePage page = guarded(engine, () -> client(engine).get()
                .uri(uri -> uri.path(JobLaneKind.TIMER.path)
                        .queryParam("size", 1)
                        .queryParam("dueBefore", dueBeforeIso)
                        .build())
                .retrieve()
                .body(FlowablePage.class));
        return page != null ? page.total() : 0;
    }

    /**
     * Capability probe: does the engine record activity history? 200 = yes; a 4xx
     * (endpoint missing / history disabled) = no. Client errors are the expected
     * negative answer here and never trip the breaker (ignore-exceptions config).
     */
    public boolean probeActivityHistory(EngineConfig engine) {
        try {
            guarded(engine, () -> client(engine).get()
                    .uri(uri -> uri.path("/history/historic-activity-instances")
                            .queryParam("size", 1)
                            .build())
                    .retrieve()
                    .body(FlowablePage.class));
            return true;
        } catch (HttpClientErrorException e) {
            return false;
        }
    }

    /* ---------- Generic paged Flowable response envelope ---------- */

    /**
     * Every Flowable list/query endpoint answers {data:[…], total, start, size, …};
     * we keep entries untyped here and map them in the aggregation layer.
     */
    public record FlowablePage(List<Map<String, Object>> data, long total, int start, int size) {
        public static FlowablePage empty() { return new FlowablePage(List.of(), 0, 0, 0); }
        public List<Map<String, Object>> dataOrEmpty() { return data != null ? data : List.of(); }
    }

    /* ---------- resiliency chokepoint ---------- */

    /**
     * Every outbound call passes through here: one circuit breaker per engine id,
     * shared config "engine" (application.yml). Open breaker → CallNotPermittedException
     * without a network attempt.
     */
    private <T> T guarded(EngineConfig engine, Supplier<T> call) {
        return breakers.circuitBreaker(engine.id(), "engine").executeSupplier(call);
    }

    /* ---------- client construction ---------- */

    private RestClient client(EngineConfig engine) {
        return readClients.computeIfAbsent(engine.id(),
                id -> build(engine, engine.timeoutsOrDefault().read()));
    }

    /** Client for mutating verbs — same connection settings, write-ms read timeout. */
    protected RestClient writeClient(EngineConfig engine) {
        return writeClients.computeIfAbsent(engine.id(),
                id -> build(engine, engine.timeoutsOrDefault().write()));
    }

    private RestClient build(EngineConfig engine, int readTimeoutMs) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(engine.timeoutsOrDefault().connect()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(engine.baseUrl())
                .requestFactory(rf);

        Auth auth = engine.auth();
        if (auth != null) {
            switch (auth.type()) {
                case basic -> builder.requestInterceptor((req, bytes, exec) -> {
                    req.getHeaders().setBasicAuth(auth.username(), resolveSecret(auth.passwordRef()));
                    return exec.execute(req, bytes);
                });
                case bearer -> builder.requestInterceptor((req, bytes, exec) -> {
                    req.getHeaders().setBearerAuth(resolveSecret(auth.tokenRef()));
                    return exec.execute(req, bytes);
                });
                case none -> { /* unauthenticated engine */ }
            }
        }
        return builder.build();
    }

    /** Secrets are configured as env-var NAMES; the value never appears in config or API output. */
    private String resolveSecret(String ref) {
        String value = env.getProperty(ref);
        if (value == null) {
            throw new IllegalStateException("Secret env var not set: " + ref);
        }
        return value;
    }
}
