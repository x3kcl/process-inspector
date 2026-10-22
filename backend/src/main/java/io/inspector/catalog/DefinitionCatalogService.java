package io.inspector.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedDefinitionRepository;
import io.inspector.catalog.DefinitionCatalogResponse.CatalogDefinition;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineHealth;
import io.inspector.registry.EngineRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cross-engine definition catalog. Count-only running totals, 20s cache, partial-engine
 * envelope. ponytail: at most {@link #LIST_CAP} definitions per kind per engine; versions
 * past that stay out of the page and {@code countsBounded} says so. Upgrade path: cursor
 * the repository list the way search pages instances.
 */
@Service
public class DefinitionCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DefinitionCatalogService.class);

    static final int LIST_CAP = 50;

    private final EngineRegistry registry;
    private final ProcessApiClient processes;
    private final CmmnApiClient cases;
    private final ProtectedDefinitionRepository protectedDefinitions;
    private final ExecutorService fanout = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
    private final Semaphore engineSlots;
    private final Cache<String, EngineSlice> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(20))
            .maximumSize(256)
            .build();

    public DefinitionCatalogService(
            EngineRegistry registry,
            ProcessApiClient processes,
            CmmnApiClient cases,
            ProtectedDefinitionRepository protectedDefinitions,
            InspectorProperties props) {
        this.registry = registry;
        this.processes = processes;
        this.cases = cases;
        this.protectedDefinitions = protectedDefinitions;
        int fanoutN = props.fanoutParallelism() != null ? props.fanoutParallelism() : 8;
        this.engineSlots = new Semaphore(fanoutN);
    }

    public DefinitionCatalogResponse list(
            Set<String> engineIds, String key, String name, boolean latestOnly, Set<String> readableEngineIds) {
        String nameLike = nameLike(name);
        List<EngineConfig> targets = resolveTargets(engineIds, readableEngineIds);
        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();
        markExcluded(engineIds, targets, perEngine);

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                engineSlots.acquireUninterruptibly();
                                try {
                                    return cachedSlice(engine, key, nameLike, latestOnly);
                                } finally {
                                    engineSlots.release();
                                }
                            },
                            fanout));
        }

        List<CatalogDefinition> rows = new ArrayList<>();
        boolean bounded = false;
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            long budgetMs = engine.timeoutsOrDefault().read() * 4L + 2000;
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(slice.rows());
                bounded = bounded || slice.bounded();
                perEngine.put(engine.id(), EngineResult.success(slice.rows().size(), slice.reportedTotal()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                bounded = true;
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} definition catalog failed: {}", engine.id(), cause.toString());
                bounded = true;
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }
        decorateProtection(rows);
        rows.sort(Comparator.comparing(CatalogDefinition::key, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                        Comparator.comparingInt(CatalogDefinition::version).reversed())
                .thenComparing(CatalogDefinition::engineId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CatalogDefinition::kind));
        return new DefinitionCatalogResponse(rows, new HashMap<>(perEngine), bounded);
    }

    private EngineSlice cachedSlice(EngineConfig engine, String key, String nameLike, boolean latestOnly) {
        String cacheKey = engine.id() + "|" + nullToEmpty(key) + "|" + nullToEmpty(nameLike) + "|" + latestOnly;
        EngineSlice hit = cache.getIfPresent(cacheKey);
        if (hit != null) {
            return hit;
        }
        EngineSlice slice = load(engine, key, nameLike, latestOnly);
        cache.put(cacheKey, slice);
        return slice;
    }

    private EngineSlice load(EngineConfig engine, String key, String nameLike, boolean latestOnly) {
        Boolean latest = latestOnly ? Boolean.TRUE : null;
        FlowablePage page = processes.listProcessDefinitions(
                engine, CallPriority.INTERACTIVE, blankToNull(key), nameLike, latest, null, 0, LIST_CAP);
        List<CatalogDefinition> rows = new ArrayList<>();
        boolean bounded = page.total() > page.dataOrEmpty().size();
        for (Map<String, Object> row : page.dataOrEmpty()) {
            rows.add(toRow(engine, "BPMN", row, countProcess(engine, str(row, "id")), latestOnly));
            if (rows.get(rows.size() - 1).countLowerBound()) {
                bounded = true;
            }
        }
        long reported = page.total();
        if (cmmn(engine)) {
            try {
                FlowablePage casesPage = cases.listCaseDefinitions(
                        engine, CallPriority.INTERACTIVE, blankToNull(key), nameLike, latest, LIST_CAP);
                if (casesPage.total() > casesPage.dataOrEmpty().size()) {
                    bounded = true;
                }
                reported += casesPage.total();
                for (Map<String, Object> row : casesPage.dataOrEmpty()) {
                    rows.add(toRow(engine, "CMMN", row, countCase(engine, str(row, "id")), latestOnly));
                    if (rows.get(rows.size() - 1).countLowerBound()) {
                        bounded = true;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Engine {} case-definition catalog failed: {}", engine.id(), e.toString());
                bounded = true;
            }
        }
        return new EngineSlice(rows, reported, bounded);
    }

    private Count countProcess(EngineConfig engine, String definitionId) {
        if (definitionId == null) {
            return new Count(null, true);
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("processDefinitionId", definitionId);
            body.put("size", 1);
            FlowablePage page = processes.queryRuntimeProcessInstances(engine, CallPriority.INTERACTIVE, body);
            return new Count(page != null ? page.total() : 0L, false);
        } catch (RuntimeException e) {
            return new Count(null, true);
        }
    }

    private Count countCase(EngineConfig engine, String definitionId) {
        if (definitionId == null) {
            return new Count(null, true);
        }
        try {
            return new Count(cases.countRunningCases(engine, CallPriority.INTERACTIVE, definitionId), false);
        } catch (RuntimeException e) {
            return new Count(null, true);
        }
    }

    private CatalogDefinition toRow(
            EngineConfig engine, String kind, Map<String, Object> row, Count count, boolean latestOnly) {
        String tenant = str(row, "tenantId");
        if (tenant == null) {
            tenant = engine.tenantId();
        }
        return new CatalogDefinition(
                engine.id(),
                kind,
                str(row, "id"),
                str(row, "key"),
                str(row, "name"),
                number(row.get("version")),
                latestOnly,
                bool(row.get("suspended")),
                str(row, "deploymentId"),
                str(row, "category"),
                tenant,
                count.value(),
                count.lowerBound(),
                null);
    }

    private void decorateProtection(List<CatalogDefinition> rows) {
        Map<String, String> reasons;
        try {
            reasons = new HashMap<>();
            for (ProtectedDefinition row : protectedDefinitions.findAll()) {
                reasons.put(row.getEngineId() + "\0" + row.getDefinitionKey(), row.getReason());
            }
        } catch (RuntimeException e) {
            log.warn(
                    "protected-definition lookup unavailable — catalog leaves protectedDefinition null: {}",
                    e.toString());
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            CatalogDefinition row = rows.get(i);
            String reason = row.key() == null ? null : reasons.get(row.engineId() + "\0" + row.key());
            rows.set(
                    i,
                    new CatalogDefinition(
                            row.engineId(),
                            row.kind(),
                            row.definitionId(),
                            row.key(),
                            row.name(),
                            row.version(),
                            row.latest(),
                            row.suspended(),
                            row.deploymentId(),
                            row.category(),
                            row.tenantId(),
                            row.runningCount(),
                            row.countLowerBound(),
                            reason != null));
        }
    }

    private boolean cmmn(EngineConfig engine) {
        EngineHealth health = registry.healthOf(engine.id());
        EngineCapabilities capabilities = health != null ? health.capabilities() : null;
        return capabilities != null && capabilities.scopeType();
    }

    private List<EngineConfig> resolveTargets(Set<String> engineIds, Set<String> readableEngineIds) {
        List<EngineConfig> base = (engineIds == null || engineIds.isEmpty())
                ? registry.all()
                : registry.all().stream()
                        .filter(e -> engineIds.contains(e.id()))
                        .toList();
        if (readableEngineIds == null) {
            return base;
        }
        return base.stream().filter(e -> readableEngineIds.contains(e.id())).toList();
    }

    private void markExcluded(
            Set<String> requestedIds, List<EngineConfig> targets, Map<String, EngineResult> perEngine) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return;
        }
        Set<String> resolved = targets.stream().map(EngineConfig::id).collect(Collectors.toSet());
        for (String requested : requestedIds) {
            if (requested == null || requested.isBlank() || resolved.contains(requested)) {
                continue;
            }
            Optional<EngineConfig> row = registry.resolve(requested);
            String why;
            if (row.isPresent() && row.get().enabled()) {
                why = "the engine \"" + requested + "\" is outside your access scope — excluded from this catalog";
            } else if (row.isPresent()) {
                why = "the engine \"" + requested + "\" is currently disabled — excluded from this catalog";
            } else {
                why = "the engine \"" + requested + "\" is no longer registered — excluded from this catalog";
            }
            perEngine.put(requested, EngineResult.failure(why));
        }
    }

    private static String nameLike(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.indexOf('%') >= 0 ? trimmed : "%" + trimmed + "%";
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }

    private record Count(Long value, boolean lowerBound) {}

    private record EngineSlice(List<CatalogDefinition> rows, long reportedTotal, boolean bounded) {}
}
