package io.inspector.client;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The single resilience + connection chokepoint shared by every {@code *ApiClient} facade
 * (Engine-client split, #86 — F2/F9). One authenticated {@link RestClient} per registered engine
 * per read/write lane, built lazily and cached; auth, timeouts, evidence capture, X-Forwarded-User,
 * and the per-engine circuit breaker + bulkhead all live here exactly once, regardless of how many
 * facades a given call belongs to.
 *
 * <p>Do-no-harm (ARCHITECTURE §2.2): every call runs through a per-engine Resilience4j circuit
 * breaker (config "engine" by default) — a sick engine fast-fails alone instead of starving BFF
 * threads. {@code CallNotPermittedException} propagates to callers, which translate it into
 * partial-result envelopes.
 *
 * <p>{@link CallPriority} is a MANDATORY parameter on {@link #call} (no priority-less convenience
 * overload) — the issue this class closes named "uniform CallPriority first-param" as an explicit
 * goal precisely because the old god-class let CMMN/external-worker callers silently default to
 * {@code INTERACTIVE} with no escape hatch. Every facade method threads its caller's priority
 * through explicitly instead.
 */
@Component
public class GuardedCaller {

    /** Engine-side attribution courtesy on forward-user engines (M4-CLOSEOUT §2). */
    static final String FORWARDED_USER_HEADER = "X-Forwarded-User";

    /**
     * A Flowable entity id is a UUID or a {@code key:version:uuid} composite — always within this
     * set. The process-api lanes interpolate ids through the RestClient's {@code {var}} template
     * (TEMPLATE_AND_VALUES-encoded); the sibling-context ({@code /cmmn-api}, {@code
     * /external-job-api}) helpers do the same, but a value carrying {@code /}, {@code ?}, {@code #}
     * or a {@code ..} traversal would still be a whitelist bypass — re-targeting the request to an
     * arbitrary path on the engine host under the BFF's rest-admin credentials, violating the
     * "BFF whitelists engine paths" iron rule (F1). Validate at the client boundary so EVERY caller
     * (action dispatch, omnibox resolve, scope/case services) is covered uniformly: an illegal id
     * is an {@link IllegalArgumentException} (→ 400), never an upstream request.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final Environment env;
    private final CircuitBreakerRegistry breakers;
    private final BulkheadRegistry bulkheads;
    private final Map<String, RestClient> readClients = new ConcurrentHashMap<>();
    // Mutating calls get their own client so write-ms (R-NFR-07) budgets them separately.
    private final Map<String, RestClient> writeClients = new ConcurrentHashMap<>();

    public GuardedCaller(Environment env, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads) {
        this.env = env;
        this.breakers = breakers;
        this.bulkheads = bulkheads;
    }

    /**
     * The resilience lane a call runs on (do-no-harm, SPEC §2). Interactive user queries own the
     * per-engine {@code "engine"} bulkhead + breaker (8 permits). The v2/M4 snapshot sampler runs
     * on a SEPARATE, thin {@code "sampler"} lane keyed {@code "{id}:sampler"} so a periodic trend
     * poll can never consume the permits an interactive search is waiting on (PLAN v2/M4 — "give
     * it its own thin lane"). The instance name MUST differ from the interactive one: the registry
     * keys instances by name, so a shared name would silently reuse the same 8-permit bulkhead.
     */
    public enum CallPriority {
        INTERACTIVE("engine", null),
        BACKGROUND("sampler", "sampler"),
        // v2 k-way-merge deep paging (R-NFR-08): an offset-near-cap historic query is far heavier
        // than page-1, and a scroller (or an attacker firing cursors at offset=cap-1) must NEVER
        // consume the 8 interactive permits page-1 search is waiting on. Its OWN per-engine lane
        // keyed "{id}:deep-page" so deep scroll degrades itself, never interactive search.
        DEEP_PAGE("deep-page", "deep-page");

        private final String config;
        private final String suffix;

        CallPriority(String config, String suffix) {
            this.config = config;
            this.suffix = suffix;
        }

        String config() {
            return config;
        }

        String instanceName(String engineId) {
            return suffix == null ? engineId : engineId + ":" + suffix;
        }
    }

    /**
     * The resiliency chokepoint every facade call runs through: one bulkhead + one circuit breaker
     * per engine id, keyed by {@code priority}. The bulkhead is outermost so a fan-out leg (parallel
     * per-row enrichment on virtual threads) can never flood a struggling engine; the breaker inside
     * it only counts calls that actually ran. Open breaker → {@code CallNotPermittedException};
     * saturated bulkhead after its wait → {@code BulkheadFullException} — both become ordinary
     * perEngine error envelopes upstream.
     */
    public <T> T call(EngineConfig engine, CallPriority priority, Supplier<T> op) {
        String name = priority.instanceName(engine.id());
        return bulkheads
                .bulkhead(name, priority.config())
                .executeSupplier(
                        () -> breakers.circuitBreaker(name, priority.config()).executeSupplier(op));
    }

    /** Same guard, void-shaped — the mutation call sites (toBodilessEntity()). */
    public void run(EngineConfig engine, CallPriority priority, Runnable op) {
        call(engine, priority, () -> {
            op.run();
            return null;
        });
    }

    /**
     * Is this engine's breaker currently OPEN (R-SEM-11, issue #101)? A bounded-wait caller (the
     * bulk dispatcher) polls this to detect recovery WITHOUT itself dispatching a doomed call —
     * {@code HALF_OPEN}/{@code CLOSED} both read {@code false} (a trial call is worth attempting).
     */
    public boolean isOpen(String engineId, CallPriority priority) {
        String name = priority.instanceName(engineId);
        return breakers.circuitBreaker(name, priority.config()).getState() == CircuitBreaker.State.OPEN;
    }

    static String safeId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches() || id.contains("..")) {
            throw new IllegalArgumentException("illegal engine entity id");
        }
        return id;
    }

    /* ---------- registry hot-reload seam (v2 Registry CRUD S3) ---------- */

    /**
     * Drop everything cached for an engine id so the NEXT call rebuilds against the current
     * registry row (docs/REGISTRY-CRUD.md §4, seam #2). Editing an engine's base-URL / auth /
     * timeouts is stale until this runs. Called strictly post-commit by {@link
     * io.inspector.registry.RegistryReloadListener}.
     *
     * <p>Both cached {@link RestClient}s are dropped, AND the Resilience4j breaker + bulkhead named
     * instances are REMOVED (not reset) for every {@link CallPriority} lane — a reset would leave
     * the named instance lingering in the registry and leak it on add/remove churn (the ≤20-engine
     * cap bounds it, but removal is correct). A later call re-materializes them from config.
     */
    public void evict(String engineId) {
        readClients.remove(engineId);
        writeClients.remove(engineId);
        for (CallPriority priority : CallPriority.values()) {
            String name = priority.instanceName(engineId);
            breakers.remove(name);
            bulkheads.remove(name);
        }
    }

    /** Test hook: whether a read client is currently cached for this id. */
    boolean isClientCached(String engineId) {
        return readClients.containsKey(engineId);
    }

    /* ---------- client construction ---------- */

    RestClient readClient(EngineConfig engine) {
        return readClients.computeIfAbsent(
                engine.id(), id -> build(engine, engine.timeoutsOrDefault().read(), false));
    }

    /** Client for mutating verbs — same connection settings, write-ms read timeout. */
    RestClient writeClient(EngineConfig engine) {
        return writeClients.computeIfAbsent(
                engine.id(), id -> build(engine, engine.timeoutsOrDefault().write(), true));
    }

    private RestClient build(EngineConfig engine, int readTimeoutMs, boolean write) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(engine.timeoutsOrDefault().connect()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
        rf.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient.Builder builder =
                RestClient.builder().baseUrl(engine.baseUrl()).requestFactory(rf);

        // Evidence capture (R-L3-01, SPEC §3): outermost interceptor so it records the FINAL
        // request URL/method/body and the observed HTTP status + wall duration of every leg —
        // but ONLY while a derivation is being re-derived for the "Explain this status" view
        // (EngineCallRecorder.isActive()). Inert on every other call, so the hot search/vitals
        // paths pay nothing. getStatusCode() does not consume the body, so downstream mapping is
        // unaffected; a transport failure records status=null (an honest "no reply") and rethrows.
        builder.requestInterceptor((req, bytes, exec) -> {
            if (!EngineCallRecorder.isActive()) {
                return exec.execute(req, bytes);
            }
            long startNanos = System.nanoTime();
            String method = req.getMethod().name();
            String url = req.getURI().toString();
            String body = recordedBody(bytes);
            try {
                var response = exec.execute(req, bytes);
                EngineCallRecorder.record(
                        method, url, body, response.getStatusCode().value(), elapsedMs(startNanos));
                return response;
            } catch (java.io.IOException | RuntimeException e) {
                EngineCallRecorder.record(method, url, body, null, elapsedMs(startNanos));
                throw e;
            }
        });

        Auth auth = engine.auth();
        if (auth != null) {
            switch (auth.type()) {
                case basic ->
                    builder.requestInterceptor((req, bytes, exec) -> {
                        req.getHeaders().setBasicAuth(auth.username(), resolveSecret(auth.passwordRef()));
                        return exec.execute(req, bytes);
                    });
                case bearer ->
                    builder.requestInterceptor((req, bytes, exec) -> {
                        req.getHeaders().setBearerAuth(resolveSecret(auth.tokenRef()));
                        return exec.execute(req, bytes);
                    });
                case none -> {
                    /* unauthenticated engine */
                }
            }
        }

        // X-Forwarded-User send-side (M4-CLOSEOUT §2): only on the WRITE client, only for engines
        // that opted in. The value is the per-call actor set by the mutation dispatch (ForwardedActor,
        // which carries the SAME actor the audit row was written with) — never an inbound header
        // (D2c), never a SecurityContextHolder read (D2a: empty/stale on bulk workers). A blank actor
        // yields no header (already collapsed to null by ForwardedActor.sanitize). Because the flag
        // is baked into the built client, a forward-user flip must evict the cached client to take
        // effect — the S3 evict() path (RegistryReloadListener) already rebuilds it post-commit.
        if (write && engine.forwardUser()) {
            builder.requestInterceptor((req, bytes, exec) -> {
                String actor = ForwardedActor.current();
                if (actor != null) {
                    req.getHeaders().set(FORWARDED_USER_HEADER, actor);
                }
                return exec.execute(req, bytes);
            });
        }
        return builder.build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Evidence bodies (query filters) are tiny JSON; cap the captured copy defensively so a
     *  recorder that ever spans a large write cannot pin an unbounded String on the heap. */
    private static final int MAX_RECORDED_BODY_BYTES = 4096;

    private static String recordedBody(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        int keep = Math.min(bytes.length, MAX_RECORDED_BODY_BYTES);
        String body = new String(bytes, 0, keep, StandardCharsets.UTF_8);
        return bytes.length > MAX_RECORDED_BODY_BYTES ? body + "…(" + bytes.length + " bytes total)" : body;
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
