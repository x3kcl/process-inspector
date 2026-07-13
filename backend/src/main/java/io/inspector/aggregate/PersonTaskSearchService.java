package io.inspector.aggregate;

import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.PersonTaskSearchResponse;
import io.inspector.dto.PersonTaskSearchResponse.PersonTaskRow;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.registry.EngineRegistry;
import jakarta.annotation.PreDestroy;
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
 * Person-centric task search (issue #99): "Bob is on vacation, what is he sitting on?" — every
 * OPEN task assigned to, or claimable by, a person across every fanned-out engine. Mirrors
 * {@link SearchService}'s fan-out shape (virtual-thread-per-engine, bounded by
 * {@code engineSlots}, per-engine timeout budget, partial-engine-failure envelope,
 * {@code readableEngineIds} threaded through from the request thread) but is otherwise much
 * simpler: two bounded {@code GET /runtime/tasks} legs per engine (assignee, candidateUser), no
 * status join, no plan selection. Rows feed the EXISTING reassign/return-to-team verbs
 * unchanged.
 */
@Service
public class PersonTaskSearchService {

    private static final Logger log = LoggerFactory.getLogger(PersonTaskSearchService.class);

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final ExecutorService fanout = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
    private final Semaphore engineSlots;

    public PersonTaskSearchService(EngineRegistry registry, ProcessApiClient flowable, InspectorProperties props) {
        this.registry = registry;
        this.flowable = flowable;
        int fanoutN = props.fanoutParallelism() != null ? props.fanoutParallelism() : 8;
        this.engineSlots = new Semaphore(fanoutN);
    }

    /**
     * {@code readableEngineIds} is the S2 read-scope set (null = unrestricted), resolved on the
     * request thread by the controller — never inside the fan-out.
     */
    public PersonTaskSearchResponse search(String person, Set<String> engineIds, Set<String> readableEngineIds) {
        List<EngineConfig> targets = resolveTargets(engineIds, readableEngineIds);
        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();
        markExcludedEngines(engineIds, targets, perEngine);

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                engineSlots.acquireUninterruptibly();
                                try {
                                    return searchOneEngine(engine, person);
                                } finally {
                                    engineSlots.release();
                                }
                            },
                            fanout));
        }

        List<PersonTaskRow> rows = new ArrayList<>();
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            long budgetMs = engine.timeoutsOrDefault().read() * 4L + 2000;
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(slice.rows());
                // rawTotal sums the two legs' engine-reported totals — an honest UPPER bound (an
                // assigned+candidate task double-counts here even though it was deduped into one
                // row above), so "shown of ~total" never UNDERSTATES how much may be truncated.
                perEngine.put(engine.id(), EngineResult.success(slice.rows().size(), slice.rawTotal()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} person-task search failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }

        // Deterministic order: soonest due date first (nulls last), then created time — the
        // triage-worthy "what's most urgent" ordering an operator picking up someone's queue wants.
        rows.sort(
                Comparator.comparing((PersonTaskRow r) -> r.dueDate(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PersonTaskRow::createTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PersonTaskRow::taskId));

        return new PersonTaskSearchResponse(rows, new HashMap<>(perEngine));
    }

    /** One engine's slice: deduped rows plus the raw (pre-dedup) total for truncation honesty. */
    private record EngineSlice(List<PersonTaskRow> rows, long rawTotal) {}

    /** Two bounded legs, deduped by task id (a task assigned to the person never double-counts as a candidate hit). */
    private EngineSlice searchOneEngine(EngineConfig engine, String person) {
        int pageSize = engine.maxPageSizeOrDefault();
        Map<String, PersonTaskRow> byTaskId = new LinkedHashMap<>();

        FlowablePage assigned = flowable.listTasksByAssignee(engine, CallPriority.INTERACTIVE, person, pageSize);
        mergeLeg(byTaskId, assigned, engine, PersonTaskRow.MATCH_ASSIGNED);

        // candidate leg never overrides a row the assignee leg already placed — directly-assigned
        // outranks claimable when a task happens to carry both.
        FlowablePage candidate = flowable.listTasksByCandidateUser(engine, CallPriority.INTERACTIVE, person, pageSize);
        mergeLeg(byTaskId, candidate, engine, PersonTaskRow.MATCH_CANDIDATE);

        return new EngineSlice(new ArrayList<>(byTaskId.values()), assigned.total() + candidate.total());
    }

    private void mergeLeg(
            Map<String, PersonTaskRow> byTaskId, FlowablePage page, EngineConfig engine, String matchReason) {
        for (Map<String, Object> t : page.dataOrEmpty()) {
            String taskId = str(t, "id");
            if (taskId == null || byTaskId.containsKey(taskId)) {
                continue;
            }
            PersonTaskRow row = toRow(engine, t, matchReason);
            if (row != null) {
                byTaskId.put(taskId, row);
            }
        }
    }

    private PersonTaskRow toRow(EngineConfig engine, Map<String, Object> t, String matchReason) {
        String taskId = str(t, "id");
        if (taskId == null) {
            return null;
        }
        // processDefinitionId is "key:version:deploymentId" (SearchService/MigrationService convention).
        String definitionId = str(t, "processDefinitionId");
        String definitionKey = definitionId != null ? definitionId.split(":", 2)[0] : null;
        return new PersonTaskRow(
                engine.id(),
                str(t, "processInstanceId"),
                taskId,
                str(t, "name"),
                str(t, "taskDefinitionKey"),
                definitionKey,
                str(t, "assignee"),
                str(t, "createTime"),
                str(t, "dueDate"),
                matchReason);
    }

    private List<EngineConfig> resolveTargets(Set<String> engineIds, Set<String> readableEngineIds) {
        List<EngineConfig> base = (engineIds == null || engineIds.isEmpty())
                ? registry.all()
                : registry.all().stream()
                        .filter(e -> engineIds.contains(e.id()))
                        .toList();
        // S2 (R-SAFE-17): same narrowing as SearchService — null = unrestricted, never "empty".
        if (readableEngineIds == null) {
            return base;
        }
        return base.stream().filter(e -> readableEngineIds.contains(e.id())).toList();
    }

    /** Same labeled-exclusion honesty as {@code SearchService.markExcludedEngines} (R-SEM-24). */
    private void markExcludedEngines(
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
                why = "the engine \"" + requested + "\" is outside your access scope — excluded from this search";
            } else if (row.isPresent()) {
                why = "the engine \"" + requested + "\" is currently disabled — excluded from this search";
            } else {
                why = "the engine \"" + requested + "\" is no longer registered — excluded from this search";
            }
            perEngine.put(requested, EngineResult.failure(why));
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
