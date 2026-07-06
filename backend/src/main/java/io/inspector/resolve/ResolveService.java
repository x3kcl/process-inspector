package io.inspector.resolve;

import io.inspector.client.FlowableEngineClient;
import io.inspector.client.FlowableEngineClient.JobLaneKind;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ResolveResponse;
import io.inspector.dto.ResolveResponse.EngineProbe;
import io.inspector.dto.ResolveResponse.MatchKind;
import io.inspector.dto.ResolveResponse.ResolveMatch;
import io.inspector.registry.EngineRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The omnibox resolver (SPEC §4, R-SEM-04): one pasted string, resolved across all
 * reachable engines in the normative order — process-instance ID → execution ID → task ID
 * → job ID → business key. A composite {@code engine:id} (prefix = a registered engine)
 * short-circuits the fan-out to that engine. Per engine the chain stops at the FIRST kind
 * that matches (Flowable ids are engine-unique across tables); different engines may match
 * different kinds — the response is always a disambiguation list, the UI decides
 * navigation. An unreachable engine degrades to a {@code perEngine} error entry, never a
 * failed resolve (partial results are the contract, flowable-rest skill §6).
 */
@Service
public class ResolveService {

    private static final Logger log = LoggerFactory.getLogger(ResolveService.class);

    /** Job lanes probed for a raw job id, dead-letter first (the 3am paste is usually one). */
    private static final List<JobLaneKind> JOB_LANES =
            List.of(JobLaneKind.DEADLETTER, JobLaneKind.TIMER, JobLaneKind.EXECUTABLE, JobLaneKind.SUSPENDED);

    /** Per-engine cap on business-key matches — each one derives flags (several calls). */
    private static final int BUSINESS_KEY_PREVIEW_CAP = 25;

    private final EngineRegistry registry;
    private final FlowableEngineClient flowable;
    private final InstanceDetailService detail;
    private final ExecutorService fanout = Executors.newVirtualThreadPerTaskExecutor();

    public ResolveService(EngineRegistry registry, FlowableEngineClient flowable, InstanceDetailService detail) {
        this.registry = registry;
        this.flowable = flowable;
        this.detail = detail;
    }

    public ResolveResponse resolve(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("q must not be blank");
        }

        // Composite engine:id — only a REGISTERED engine id makes the prefix a composite
        // (mirrors the client-side classifier): "order:4711" stays an opaque business key.
        List<EngineConfig> targets = registry.all();
        String needle = q;
        int at = q.indexOf(':');
        if (at > 0) {
            String prefix = q.substring(0, at);
            String rest = q.substring(at + 1).trim();
            EngineConfig named = targets.stream()
                    .filter(e -> e.id().equals(prefix))
                    .findFirst()
                    .orElse(null);
            if (named != null && !rest.isEmpty()) {
                targets = List.of(named);
                needle = rest;
            }
        }

        String id = needle;
        Map<EngineConfig, CompletableFuture<List<ResolveMatch>>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(engine, CompletableFuture.supplyAsync(() -> resolveOnEngine(engine, id), fanout));
        }

        List<ResolveMatch> matches = new ArrayList<>();
        Map<String, EngineProbe> perEngine = new LinkedHashMap<>();
        for (Map.Entry<EngineConfig, CompletableFuture<List<ResolveMatch>>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            // Same outer-guard doctrine as the search fan-out: the real limits are the
            // per-call read timeout and the per-engine breaker inside.
            long budgetMs = engine.timeoutsOrDefault().read() * 6L + 2000;
            try {
                matches.addAll(e.getValue().get(budgetMs, TimeUnit.MILLISECONDS));
                perEngine.put(engine.id(), EngineProbe.reached());
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineProbe.failed("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Resolve on {} failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineProbe.failed(cause.getMessage()));
            }
        }
        return new ResolveResponse(q, matches, perEngine);
    }

    /** The R-SEM-04 chain on ONE engine — first kind that matches wins. */
    private List<ResolveMatch> resolveOnEngine(EngineConfig engine, String id) {
        // 1. Process instance — the historic GET covers running AND completed instances.
        Map<String, Object> historic = flowable.getHistoricProcessInstance(engine, id);
        if (historic != null) {
            return List.of(match(engine, MatchKind.PROCESS_INSTANCE, id, historic));
        }

        // 2. Execution — a child execution's id maps to its owning instance.
        Map<String, Object> execution = flowable.getExecution(engine, id);
        String pid = execution != null ? str(execution, "processInstanceId") : null;
        if (pid != null) {
            return matchInstance(engine, MatchKind.EXECUTION, id, pid);
        }

        // 3. Task — runtime first, historic fallback so a completed task still resolves.
        Map<String, Object> task = flowable.getTask(engine, id);
        if (task == null) {
            task = flowable.getHistoricTaskInstance(engine, id);
        }
        pid = task != null ? str(task, "processInstanceId") : null;
        if (pid != null) {
            return matchInstance(engine, MatchKind.TASK, id, pid);
        }

        // 4. Job — all four lanes; CMMN-scoped jobs (no processInstanceId) are not ours.
        for (JobLaneKind lane : JOB_LANES) {
            Map<String, Object> job = flowable.getJob(engine, lane, id);
            pid = job != null ? str(job, "processInstanceId") : null;
            if (pid != null && !pid.isBlank()) {
                return matchInstance(engine, MatchKind.JOB, id, pid);
            }
        }

        // 5. Business key — exact match, hierarchy-aware (children carry the key too);
        //    can legitimately be MANY instances. Always a disambiguation entry, the UI
        //    routes BUSINESS_KEY kinds to a pre-filtered search (R-SEM-04) — so the list
        //    here is a bounded preview (each match derives full status flags), never an
        //    attempt at completeness.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processBusinessKey", id);
        body.put("size", Math.min(BUSINESS_KEY_PREVIEW_CAP, engine.maxPageSizeOrDefault()));
        body.put("sort", "startTime");
        body.put("order", "desc");
        if (engine.tenantId() != null && !engine.tenantId().isBlank()) {
            body.put("tenantId", engine.tenantId());
        }
        List<ResolveMatch> byKey = new ArrayList<>();
        for (Map<String, Object> row :
                flowable.queryHistoricProcessInstances(engine, body).dataOrEmpty()) {
            byKey.add(match(engine, MatchKind.BUSINESS_KEY, id, row));
        }
        return byKey;
    }

    private List<ResolveMatch> matchInstance(EngineConfig engine, MatchKind kind, String matchedId, String pid) {
        Map<String, Object> historic = flowable.getHistoricProcessInstance(engine, pid);
        if (historic == null) {
            return List.of(); // vanished between the two reads — an acceptable snapshot race
        }
        return List.of(match(engine, kind, matchedId, historic));
    }

    private ResolveMatch match(EngineConfig engine, MatchKind kind, String matchedId, Map<String, Object> historic) {
        String pid = str(historic, "id");
        String definitionId = str(historic, "processDefinitionId");
        InstanceStatusFlags flags = detail.flagsFor(engine, historic);
        return new ResolveMatch(
                kind,
                engine.id(),
                pid,
                engine.id() + ":" + pid,
                matchedId,
                str(historic, "businessKey"),
                definitionKeyOf(definitionId),
                definitionVersionOf(definitionId),
                str(historic, "startTime"),
                str(historic, "endTime"),
                flags,
                flags.primaryStatus());
    }

    private static String definitionKeyOf(String definitionId) {
        if (definitionId == null) return null;
        int i = definitionId.indexOf(':');
        return i > 0 ? definitionId.substring(0, i) : definitionId;
    }

    private static Integer definitionVersionOf(String definitionId) {
        if (definitionId == null) return null;
        String[] parts = definitionId.split(":", 3);
        try {
            return parts.length >= 2 ? Integer.valueOf(parts[1]) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }
}
