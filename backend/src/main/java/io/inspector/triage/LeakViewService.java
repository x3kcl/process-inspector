package io.inspector.triage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.LeakViewsResponse;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount;
import io.inspector.dto.LeakViewsResponse.LeakDefinitionCount.EngineLeakCount;
import io.inspector.dto.LeakViewsResponse.LeakWindows;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Stage 0 "Leak views" aggregator (SPEC §4, R-BAU-02) — the slow leaks that never enter a
 * failure lane: long-RUNNING and long-SUSPENDED process instances, grouped per definition.
 *
 * <p>AGGREGATION-INDEPENDENT by the same doctrine as {@link TriageAggregationService}: it never
 * reuses the grid-search plan. Per engine, on virtual threads, it enumerates the deployed
 * definition KEYS ({@code latest=true} — a bounded metadata list, not instance rows) and then
 * issues count-only ({@code size=1}) runtime queries per (key, window) — the iron rule for every
 * Stage 0 count. Age is {@code now − startTime} via the {@code startedBefore} predicate for ALL
 * three windows, including SUSPENDED: Flowable records no suspension timestamp, so the honest
 * predicate is "currently suspended AND started > 7d ago" (R-SEM-05; the frontend label says so).
 *
 * <p>Counts merge per definition key across every reachable engine. An unreachable engine, or a
 * definition list larger than the enumeration cap, sets {@code lowerBound} and names the engine
 * in {@code unavailableEngines} — every count then wears the standard lower-bound badge in the
 * UI (R-SEM-12), never a confidently-wrong total. Served from a short-lived cache (same
 * thundering-herd protection as the dashboard).
 */
@Service
public class LeakViewService {

    private static final Logger log = LoggerFactory.getLogger(LeakViewService.class);
    private static final String KEY = "leak-views";

    private static final Duration ACTIVE_30D = Duration.ofDays(30);
    private static final Duration ACTIVE_90D = Duration.ofDays(90);
    private static final Duration SUSPENDED_7D = Duration.ofDays(7);

    /**
     * Ceiling on definition keys enumerated per engine. A deployment with more distinct keys
     * than this is exotic; if it happens the count is an honest lower bound (flagged), never a
     * silent omission — the same do-no-harm posture as the failure-lane scan cap.
     */
    private static final int DEFINITION_CAP = 500;

    private final EngineRegistrySupplier registry;
    private final ProcessApiClient flowable;
    private final Clock clock;
    private final Cache<String, LeakViewsResponse> cache;
    private final ExecutorService fanout = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();

    /** Narrow seam over {@code EngineRegistry.all()} so the aggregation core unit-tests cleanly. */
    @FunctionalInterface
    public interface EngineRegistrySupplier {
        List<EngineConfig> all();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LeakViewService(
            io.inspector.registry.EngineRegistry registry,
            ProcessApiClient flowable,
            InspectorProperties props,
            Clock clock) {
        this(
                registry::all,
                flowable,
                clock,
                Duration.ofSeconds(props.triageOrDefault().cacheTtlSOrDefault()));
    }

    /** Test seam: an engine-list supplier and an explicit TTL — no Spring context required. */
    LeakViewService(EngineRegistrySupplier registry, ProcessApiClient flowable, Clock clock, Duration ttl) {
        this.registry = registry;
        this.flowable = flowable;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).build();
    }

    /** The cached {@code /api/triage/leak-views} read — single-flight behind the TTL. */
    public LeakViewsResponse leakViews() {
        return cache.get(KEY, k -> aggregate());
    }

    /**
     * Diagnostics (issue #96, {@code GET /api/diag}): age of the CURRENTLY cached snapshot —
     * {@code asMap()} peeks the cache without triggering the loader. Empty before the first
     * {@link #leakViews} call this process has served.
     */
    public Optional<Duration> cacheAge() {
        LeakViewsResponse cached = cache.asMap().get(KEY);
        return cached == null
                ? Optional.empty()
                : Optional.of(Duration.between(Instant.parse(cached.asOf()), clock.instant()));
    }

    /* ---------------- the fan-out (unit-tested directly) ---------------- */

    LeakViewsResponse aggregate() {
        Instant now = clock.instant();
        String active30 = now.minus(ACTIVE_30D).toString();
        String active90 = now.minus(ACTIVE_90D).toString();
        String suspended7 = now.minus(SUSPENDED_7D).toString();
        LeakWindows windows = new LeakWindows(active30, active90, suspended7);

        List<EngineConfig> engines = registry.all();
        Map<String, Future<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : engines) {
            futures.put(engine.id(), fanout.submit(() -> sliceOf(engine, active30, active90, suspended7)));
        }

        // key → [active30, active90, suspended7], summed across engines; byKeyByEngine keeps the
        // per-engine breakdown alive (issue #126) so a caller-scoped projection can honestly
        // recompute a slice instead of nulling it (unlike ErrorGroup's DL/retrying split, every
        // window count here IS separable per engine).
        Map<String, long[]> byKey = new TreeMap<>();
        Map<String, Map<String, long[]>> byKeyByEngine = new TreeMap<>();
        ConcurrentSkipListSet<String> unavailable = new ConcurrentSkipListSet<>();
        boolean lowerBound = false;
        for (EngineConfig engine : engines) {
            EngineSlice slice = resolve(engine.id(), futures.get(engine.id()));
            if (!slice.ok()) {
                unavailable.add(engine.id());
                lowerBound = true;
                continue;
            }
            lowerBound |= slice.truncated();
            slice.counts().forEach((key, counts) -> {
                long[] agg = byKey.computeIfAbsent(key, k -> new long[3]);
                for (int i = 0; i < 3; i++) {
                    agg[i] += counts[i];
                }
                byKeyByEngine.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(engine.id(), counts);
            });
        }

        List<LeakDefinitionCount> definitions = byKey.entrySet().stream()
                .map(e -> {
                    Map<String, EngineLeakCount> perEngine = new LinkedHashMap<>();
                    byKeyByEngine
                            .getOrDefault(e.getKey(), Map.of())
                            .forEach((engineId, counts) ->
                                    perEngine.put(engineId, new EngineLeakCount(counts[0], counts[1], counts[2])));
                    return new LeakDefinitionCount(
                            e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], perEngine, false);
                })
                // A definition surfaces only when it actually leaks in at least one window.
                .filter(d -> d.activeOver30d() > 0 || d.activeOver90d() > 0 || d.suspendedStartedOver7d() > 0)
                .sorted(Comparator.comparingLong(LeakDefinitionCount::activeOver30d)
                        .thenComparingLong(LeakDefinitionCount::suspendedStartedOver7d)
                        .reversed())
                .toList();

        return new LeakViewsResponse(now.toString(), windows, definitions, lowerBound, List.copyOf(unavailable));
    }

    private EngineSlice resolve(String engineId, Future<EngineSlice> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            log.warn("Leak-view slice for {} died: {}", engineId, ex.toString());
            return EngineSlice.failed();
        }
    }

    private record EngineSlice(boolean ok, boolean truncated, Map<String, long[]> counts) {
        static EngineSlice failed() {
            return new EngineSlice(false, false, Map.of());
        }
    }

    private EngineSlice sliceOf(EngineConfig engine, String active30, String active90, String suspended7) {
        try {
            // Cheap pre-check: the whole-engine window totals. When an engine has no leaks at
            // all (the common case) we never enumerate its definitions — three count queries,
            // not 3×K.
            long active30Total = count(engine, null, false, active30);
            long active90Total = count(engine, null, false, active90);
            long suspended7Total = count(engine, null, true, suspended7);
            if (active30Total == 0 && active90Total == 0 && suspended7Total == 0) {
                return new EngineSlice(true, false, Map.of());
            }

            FlowablePage defs = flowable.listLatestProcessDefinitions(engine, CallPriority.INTERACTIVE, DEFINITION_CAP);
            List<Map<String, Object>> rows = defs != null ? defs.dataOrEmpty() : List.of();
            boolean truncated = defs != null && defs.total() > rows.size();

            // Per-(key,window) count-only queries fan out on the shared virtual-thread pool; the
            // per-engine bulkhead is the do-no-harm ceiling, so this can never flood the engine.
            Map<String, long[]> counts = new LinkedHashMap<>();
            Map<String, Future<long[]>> keyFutures = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object keyObj = row.get("key");
                if (keyObj == null || keyObj.toString().isBlank()) {
                    continue;
                }
                String key = keyObj.toString();
                if (counts.containsKey(key) || keyFutures.containsKey(key)) {
                    continue; // latest=true is one row per key, but guard against dupes defensively.
                }
                keyFutures.put(key, fanout.submit(() -> new long[] {
                    active30Total == 0 ? 0 : count(engine, key, false, active30),
                    active90Total == 0 ? 0 : count(engine, key, false, active90),
                    suspended7Total == 0 ? 0 : count(engine, key, true, suspended7),
                }));
            }
            for (Map.Entry<String, Future<long[]>> e : keyFutures.entrySet()) {
                counts.put(e.getKey(), e.getValue().get());
            }
            return new EngineSlice(true, truncated, counts);
        } catch (Exception ex) {
            log.debug("Leak-view slice degraded for {}: {}", engine.id(), ex.toString());
            return EngineSlice.failed();
        }
    }

    /**
     * A single count-only runtime query: total open instances matching (optional key, suspended
     * flag, {@code startedBefore}). {@code size=1} — a TOTAL, never rows (iron rule). Runtime
     * only: completed instances leave the runtime tables, so a "still running / still suspended"
     * leak is exactly a runtime match.
     */
    private long count(EngineConfig engine, String definitionKey, boolean suspended, String startedBefore) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (definitionKey != null) {
            body.put("processDefinitionKey", definitionKey);
        }
        body.put("suspended", suspended);
        body.put("startedBefore", startedBefore);
        body.put("size", 1);
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            body.put("tenantId", engine.tenantId());
        }
        FlowablePage page = flowable.queryRuntimeProcessInstances(engine, CallPriority.INTERACTIVE, body);
        return page != null ? page.total() : 0;
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }
}
