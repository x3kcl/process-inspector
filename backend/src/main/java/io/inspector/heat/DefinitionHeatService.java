package io.inspector.heat;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.GuardRefusedException;
import io.inspector.client.ActivityInstancePage;
import io.inspector.client.CmmnApiClient;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.client.ProcessInstancePage;
import io.inspector.client.ProcessInstanceQuery;
import io.inspector.client.ProcessInstanceRecord;
import io.inspector.config.HeatProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.DefinitionHeat;
import io.inspector.heat.HeatAggregator.Instance;
import io.inspector.heat.HeatAggregator.Sample;
import io.inspector.registry.EngineCapabilities;
import io.inspector.registry.EngineRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Definition heat gather (#784, DEFINITION-HEATMAP.md §3-§4): the newest N finished instances of
 * ONE definition version, one activity page per instance, the version's BPMN, aggregated by
 * {@link HeatAggregator}. Every engine call runs on {@link CallPriority#AGGREGATE}. Results are
 * cached 10 minutes per (engine, definition, N), single-flight; a refresh bypasses the cache at
 * most once per 60 s per key, a refused one answers the cached value with {@code
 * refreshThrottledUntil}. A failed gather is not cached.
 */
@Service
public class DefinitionHeatService {

    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration REFRESH_MIN_INTERVAL = Duration.ofSeconds(60);
    /** The AGGREGATE bulkhead's permits: more workers would only queue on it. */
    private static final int LANE_PERMITS = 2;

    private record Key(String engineId, String definitionId, int n) {}

    private final EngineRegistry registry;
    private final ProcessApiClient processes;
    private final CmmnApiClient cases;
    private final HeatProperties properties;
    private final Clock clock;
    private final AsyncCache<Key, DefinitionHeat> cache;
    private final Cache<Key, Instant> lastRefresh;

    public DefinitionHeatService(
            EngineRegistry registry,
            ProcessApiClient processes,
            CmmnApiClient cases,
            HeatProperties properties,
            Clock clock,
            Ticker ticker) {
        this.registry = registry;
        this.processes = processes;
        this.cases = cases;
        this.properties = properties;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(500)
                .ticker(ticker)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .buildAsync();
        this.lastRefresh = Caffeine.newBuilder()
                .expireAfterWrite(REFRESH_MIN_INTERVAL)
                .maximumSize(500)
                .ticker(ticker)
                .build();
    }

    public DefinitionHeat heat(String engineId, String definitionId, Integer n, boolean refresh) {
        EngineConfig engine = registry.resolveOrNotFound(engineId);
        Key key = new Key(engineId, definitionId, properties.clamp(n));
        Instant throttledUntil = refresh ? claimRefresh(key) : null;
        if (refresh && throttledUntil == null) {
            cache.synchronous().invalidate(key);
        }
        boolean[] gathered = {false};
        CompletableFuture<DefinitionHeat> future = cache.get(key, (k, executor) -> {
            gathered[0] = true;
            return CompletableFuture.supplyAsync(() -> gather(engine, definitionId, k.n()), executor);
        });
        try {
            return served(future.join(), !gathered[0], throttledUntil);
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /** Null when this caller may refresh now (and takes the slot), else when it next may. */
    private Instant claimRefresh(Key key) {
        Instant now = clock.instant();
        Instant[] refused = {null};
        lastRefresh.asMap().compute(key, (k, previous) -> {
            if (previous != null && now.isBefore(previous.plus(REFRESH_MIN_INTERVAL))) {
                refused[0] = previous.plus(REFRESH_MIN_INTERVAL);
                return previous;
            }
            return now;
        });
        return refused[0];
    }

    private DefinitionHeat gather(EngineConfig engine, String definitionId, int n) {
        requireBpmnDefinition(engine, definitionId);
        List<ProcessInstanceRecord> sampled = new ArrayList<>();
        long finishedTotal = sample(engine, definitionId, n, sampled);
        List<Instance> instances = readActivities(engine, sampled);
        String bpmnXml = sampled.isEmpty() ? null : bpmnXml(engine, definitionId);
        List<Instant> ends = sampled.stream()
                .map(r -> parse(r.endTime()))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        Sample sample = new Sample(
                engine.id(),
                definitionId,
                clock.instant(),
                n,
                finishedTotal,
                ends.isEmpty() ? null : ends.get(0),
                ends.isEmpty() ? null : ends.get(ends.size() - 1),
                instances,
                bpmnXml);
        try {
            return HeatAggregator.aggregate(sample);
        } catch (IllegalStateException e) {
            throw new GuardRefusedException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "heat-unavailable",
                    "Every sampled instance of " + definitionId + " on '" + engine.id()
                            + "' was dropped; no figure can be shown.");
        }
    }

    /** 404 like the other definition routes; a CMMN case definition is out of scope (#784). */
    private void requireBpmnDefinition(EngineConfig engine, String definitionId) {
        boolean caseDefinition;
        try {
            if (processes.getProcessDefinition(engine, CallPriority.AGGREGATE, definitionId) != null) {
                return;
            }
            EngineCapabilities capabilities = registry.healthOf(engine.id()).capabilities();
            caseDefinition = capabilities != null
                    && capabilities.scopeType()
                    && cases.getCmmnCaseDefinition(engine, CallPriority.AGGREGATE, definitionId) != null;
        } catch (RuntimeException e) {
            throw unavailable(engine, e);
        }
        if (caseDefinition) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "heat-bpmn-only",
                    "Heat covers BPMN process definitions only; " + definitionId + " is a CMMN case definition.");
        }
        throw new GuardRefusedException(
                HttpStatus.NOT_FOUND,
                "definition-not-found",
                "Definition " + definitionId + " does not exist on '" + engine.id() + "'.");
    }

    /**
     * The newest {@code n} finished instances, paged at the engine's page size. The first page's
     * {@code total} is the finished total (the "of M"): the same bounded query, no second count.
     */
    private long sample(EngineConfig engine, String definitionId, int n, List<ProcessInstanceRecord> out) {
        int page = engine.maxPageSizeOrDefault();
        long total = 0;
        try {
            while (out.size() < n) {
                int size = Math.min(page, n - out.size());
                ProcessInstancePage p = processes.queryHistoricProcessInstances(
                        engine,
                        CallPriority.AGGREGATE,
                        ProcessInstanceQuery.builder()
                                .processDefinitionId(definitionId)
                                .finished(true)
                                .sort("endTime", "desc")
                                .start(out.size())
                                .size(size)
                                .build());
                if (out.isEmpty()) {
                    total = p.total();
                }
                List<ProcessInstanceRecord> rows = p.dataOrEmpty();
                out.addAll(rows.subList(0, Math.min(rows.size(), size)));
                if (rows.size() < size) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            throw unavailable(engine, e);
        }
        return total;
    }

    /** One page per instance, two at a time (the lane's permits); a failed read is dropped. */
    private List<Instance> readActivities(EngineConfig engine, List<ProcessInstanceRecord> sampled) {
        int page = engine.maxPageSizeOrDefault();
        List<Future<Instance>> reads = new ArrayList<>();
        try (ExecutorService workers = Executors.newFixedThreadPool(
                LANE_PERMITS, Thread.ofVirtual().name("heat-", 0).factory())) {
            for (ProcessInstanceRecord instance : sampled) {
                reads.add(workers.submit(() -> {
                    try {
                        ActivityInstancePage rows = processes.listHistoricActivities(
                                engine, CallPriority.AGGREGATE, instance.id(), 0, page);
                        return new Instance(rows.dataOrEmpty(), rows.total(), false);
                    } catch (RuntimeException e) {
                        return new Instance(List.of(), 0, true);
                    }
                }));
            }
        }
        return reads.stream().map(Future::resultNow).toList();
    }

    /** The version's BPMN for the event-gateway rule; null (structure UNAVAILABLE) if unreadable. */
    private String bpmnXml(EngineConfig engine, String definitionId) {
        try {
            return processes.processDefinitionResourceData(engine, CallPriority.AGGREGATE, definitionId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static GuardRefusedException unavailable(EngineConfig engine, RuntimeException e) {
        String reason = e instanceof CallNotPermittedException
                ? "circuit open — engine shedding load"
                : e instanceof BulkheadFullException ? "aggregate lane busy" : "engine did not answer";
        return new GuardRefusedException(
                HttpStatus.SERVICE_UNAVAILABLE, "engine-unavailable", "Engine '" + engine.id() + "': " + reason + ".");
    }

    private static Instant parse(String time) {
        if (time == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(time).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static DefinitionHeat served(DefinitionHeat h, boolean cached, Instant throttledUntil) {
        return new DefinitionHeat(
                h.engineId(),
                h.definitionId(),
                h.asOf(),
                cached,
                h.coverage(),
                h.structure(),
                h.requestedN(),
                h.sampledInstances(),
                h.finishedTotal(),
                h.droppedInstances(),
                h.dropReasons(),
                h.oldestEnd(),
                h.newestEnd(),
                h.partial(),
                h.nodes(),
                h.outcomes(),
                h.noEndEventCount(),
                h.samplingNote(),
                throttledUntil);
    }
}
