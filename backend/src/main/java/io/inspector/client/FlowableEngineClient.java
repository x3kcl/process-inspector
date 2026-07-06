package io.inspector.client;

import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One authenticated {@link RestClient} per registered engine, built lazily and cached.
 * All Flowable V6 REST calls go through here so auth, timeouts and error translation
 * live in exactly one place.
 */
@Component
public class FlowableEngineClient {

    private final Environment env;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public FlowableEngineClient(Environment env) {
        this.env = env;
    }

    /* ---------- Flowable V6 REST calls (the whitelist the BFF exposes) ---------- */

    /** POST /query/historic-process-instances — the primary search query. */
    public FlowablePage queryHistoricProcessInstances(EngineConfig engine, Map<String, Object> body) {
        return client(engine).post()
                .uri("/query/historic-process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FlowablePage.class);
    }

    /** POST /query/process-instances — runtime query (used for the suspended-ID set). */
    public FlowablePage queryRuntimeProcessInstances(EngineConfig engine, Map<String, Object> body) {
        return client(engine).post()
                .uri("/query/process-instances")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FlowablePage.class);
    }

    /** GET /management/deadletter-jobs — the failed-instance join set. */
    public FlowablePage listDeadLetterJobs(EngineConfig engine, int size) {
        return client(engine).get()
                .uri(uri -> uri.path("/management/deadletter-jobs")
                        .queryParam("size", size)
                        .queryParam("sort", "createTime")
                        .queryParam("order", "desc")
                        .build())
                .retrieve()
                .body(FlowablePage.class);
    }

    /** GET /management/engine — health + version probe. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> engineInfo(EngineConfig engine) {
        return client(engine).get()
                .uri("/management/engine")
                .retrieve()
                .body(Map.class);
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

    /* ---------- client construction ---------- */

    private RestClient client(EngineConfig engine) {
        return clients.computeIfAbsent(engine.id(), id -> build(engine));
    }

    private RestClient build(EngineConfig engine) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(engine.timeouts().connect()));
        rf.setReadTimeout(Duration.ofMillis(engine.timeouts().read()));

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
