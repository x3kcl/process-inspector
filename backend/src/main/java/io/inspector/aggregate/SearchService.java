package io.inspector.aggregate;

import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.FlowablePage;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The API aggregator: builds one search plan per selected engine, fans out in
 * parallel with per-engine timeouts, and merges normalized rows.
 *
 * Per-engine plan (ARCHITECTURE.md §2.3):
 *   1. POST /query/historic-process-instances  — primary query (running + completed)
 *   2. POST /query/process-instances {suspended:true} — suspended-ID set
 *   3. GET  /management/deadletter-jobs        — failed-ID set + error snippet
 * Status is derived in the BFF, then filtered to the requested OR-set.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;
    private final ExecutorService fanoutPool;

    public SearchService(EngineRegistry registry, FlowableEngineClient flowable, InspectorProperties props) {
        this.registry = registry;
        this.flowable = flowable;
        int parallelism = props.fanoutParallelism() != null ? props.fanoutParallelism() : 8;
        this.fanoutPool = Executors.newFixedThreadPool(parallelism);
    }

    public SearchResponse search(SearchRequest request) {
        List<EngineConfig> targets = resolveTargets(request);
        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();

        Map<EngineConfig, CompletableFuture<List<ProcessInstanceRow>>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(engine, CompletableFuture.supplyAsync(() -> searchOneEngine(engine, request), fanoutPool));
        }

        List<ProcessInstanceRow> rows = new ArrayList<>();
        for (Map.Entry<EngineConfig, CompletableFuture<List<ProcessInstanceRow>>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            // Generous outer guard; the RestClient read timeout is the real per-call limit.
            long budgetMs = engine.timeouts().read() * 3L + 2000;
            try {
                List<ProcessInstanceRow> engineRows = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(engineRows);
                perEngine.putIfAbsent(engine.id(), EngineResult.success(engineRows.size(), engineRows.size()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} search failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }

        rows.sort(Comparator.comparing(ProcessInstanceRow::startTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new SearchResponse(rows, new HashMap<>(perEngine));
    }

    /* ---------------- per-engine plan ---------------- */

    private List<ProcessInstanceRow> searchOneEngine(EngineConfig engine, SearchRequest req) {
        Set<InstanceStatus> wanted = EnumSet.copyOf(req.effectiveStatuses());
        int pageSize = Math.min(
                req.pageSize() != null && req.pageSize() > 0 ? req.pageSize() : engine.maxPageSizeOrDefault(),
                engine.maxPageSizeOrDefault());

        // 1) primary historic query — covers both running and completed instances
        FlowablePage historic = flowable.queryHistoricProcessInstances(engine, historicBody(req, wanted, pageSize));

        // 2) + 3) join sets — needed whenever any open (non-COMPLETED) status is requested,
        // both to filter and to label the status column correctly.
        boolean wantsOpen = wanted.contains(InstanceStatus.ACTIVE)
                || wanted.contains(InstanceStatus.SUSPENDED)
                || wanted.contains(InstanceStatus.FAILED);
        Set<String> suspendedIds = wantsOpen
                ? idsOf(flowable.queryRuntimeProcessInstances(engine,
                        Map.of("suspended", true, "size", engine.maxPageSizeOrDefault())))
                : Set.of();
        Map<String, String> failedById = wantsOpen ? deadLetterIndex(engine) : Map.of();

        List<ProcessInstanceRow> rows = new ArrayList<>();
        for (Map<String, Object> pi : historic.dataOrEmpty()) {
            String id = str(pi, "id");
            String endTime = str(pi, "endTime");

            InstanceStatus status;
            if (endTime != null)                       status = InstanceStatus.COMPLETED;
            else if (failedById.containsKey(id))       status = InstanceStatus.FAILED;
            else if (suspendedIds.contains(id))        status = InstanceStatus.SUSPENDED;
            else                                       status = InstanceStatus.ACTIVE;

            if (!wanted.contains(status)) continue;

            rows.add(new ProcessInstanceRow(
                    engine.id() + ":" + id,
                    engine.id(),
                    engine.name(),
                    engine.color(),
                    id,
                    str(pi, "businessKey"),
                    str(pi, "processDefinitionKey"),
                    str(pi, "processDefinitionName"),
                    status,
                    str(pi, "startTime"),
                    endTime,
                    failedById.get(id)));
        }
        return rows;
    }

    /** Flowable historic-process-instance query body — AND of all scalar + variable filters. */
    private Map<String, Object> historicBody(SearchRequest req, Set<InstanceStatus> wanted, int pageSize) {
        Map<String, Object> body = new HashMap<>();
        if (req.processDefinitionKey() != null && !req.processDefinitionKey().isBlank()) {
            body.put("processDefinitionKey", req.processDefinitionKey());
        }
        if (req.businessKey() != null && !req.businessKey().isBlank()) {
            body.put("processBusinessKey", req.businessKey());
        }
        if (req.startedAfter() != null && !req.startedAfter().isBlank()) {
            body.put("startedAfter", req.startedAfter());
        }
        if (req.startedBefore() != null && !req.startedBefore().isBlank()) {
            body.put("startedBefore", req.startedBefore());
        }
        if (req.variables() != null && !req.variables().isEmpty()) {
            body.put("variables", req.variables().stream().map(v -> {
                Map<String, Object> m = new HashMap<>();
                m.put("name", v.name());
                m.put("value", v.value());
                m.put("operation", v.operation() != null ? v.operation() : "equals");
                if (v.type() != null) m.put("type", v.type());
                return m;
            }).toList());
        }
        // Narrow finished when the status OR-set is one-sided (pure optimization).
        boolean wantsOpen = wanted.contains(InstanceStatus.ACTIVE) || wanted.contains(InstanceStatus.SUSPENDED)
                || wanted.contains(InstanceStatus.FAILED);
        boolean wantsCompleted = wanted.contains(InstanceStatus.COMPLETED);
        if (wantsCompleted && !wantsOpen)      body.put("finished", true);
        else if (wantsOpen && !wantsCompleted) body.put("finished", false);

        body.put("size", pageSize);
        body.put("sort", "startTime");
        body.put("order", "desc");
        return body;
    }

    private static Set<String> idsOf(FlowablePage page) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> entry : page.dataOrEmpty()) {
            String id = str(entry, "id");
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** processInstanceId → exception-message snippet, from the engine's dead-letter queue. */
    private Map<String, String> deadLetterIndex(EngineConfig engine) {
        FlowablePage page = flowable.listDeadLetterJobs(engine, engine.maxPageSizeOrDefault());
        Map<String, String> index = new HashMap<>();
        for (Map<String, Object> job : page.dataOrEmpty()) {
            String pid = str(job, "processInstanceId");
            if (pid != null) {
                index.putIfAbsent(pid, str(job, "exceptionMessage"));
            }
        }
        return index;
    }

    private List<EngineConfig> resolveTargets(SearchRequest req) {
        if (req.engineIds() == null || req.engineIds().isEmpty()) {
            return registry.all();
        }
        Set<String> ids = new HashSet<>(req.engineIds());
        return registry.all().stream().filter(e -> ids.contains(e.id())).toList();
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @PreDestroy
    void shutdown() {
        fanoutPool.shutdownNow();
    }
}
