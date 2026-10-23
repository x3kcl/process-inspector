package io.inspector.aggregate;

import io.inspector.api.MdcPropagatingExecutors;
import io.inspector.audit.ProtectedInstance;
import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.detail.InstanceDetailService;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.EngineResult;
import io.inspector.dto.SearchResponse.SignatureGeneration;
import io.inspector.registry.EngineRegistry;
import io.inspector.triage.ErrorSignatureNormalizer;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The API aggregator (ARCHITECTURE §2.3, the CORRECTED status join): one search plan per
 * selected engine, fanned out on virtual threads with per-engine budgets, rows merged and
 * sorted in the BFF. Status is derived as FLAGS ({@link InstanceStatusFlags}); the pure
 * join semantics live in {@link StatusJoin} (rung-1 tested), the engine I/O here.
 *
 * <p>Plan selection per requested status set:
 * <ul>
 *   <li><b>INVERTED</b> (FAILED/RETRYING only): drive FROM the job queues — page the DLQ to
 *       exhaustion (bounded by {@code dlq-scan-cap}, definition filter pushed down), plus
 *       the two withException lanes for the RETRYING tier; resolve call-activity children up
 *       the {@code superProcessInstanceId} chain (batched level-by-level, depth-capped,
 *       cycle-guarded); hydrate via the historic query with {@code processInstanceIds}.</li>
 *   <li><b>MIXED</b>: primary historic query per filters, then per-page enrichment of exactly
 *       the displayed rows: bulk runtime suspended state (with an ignored-filter fallback for
 *       legacy engines), bounded-N+1 DLQ membership, and capped withException lane scans.</li>
 * </ul>
 *
 * A capped scan never impersonates a complete one: the per-engine envelope carries
 * {@code dlqScan/failingScan = "truncated@N"} and every downstream count is a lower bound.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final EngineRegistry registry;
    private final ProcessApiClient flowable;
    private final InspectorProperties props;
    private final ProtectedInstanceRepository protectedInstances;
    private final ExecutorService fanout = MdcPropagatingExecutors.newVirtualThreadPerTaskExecutor();
    private final SearchEnginePlan plan;
    // Package-private for the DEEP_PAGE-isolation test (F3 / R-NFR-08).
    final Semaphore engineSlots;
    final Semaphore deepPageSlots;

    public SearchService(
            EngineRegistry registry,
            ProcessApiClient flowable,
            InspectorProperties props,
            ProtectedInstanceRepository protectedInstances) {
        this.registry = registry;
        this.flowable = flowable;
        this.props = props;
        this.protectedInstances = protectedInstances;
        int fanoutN = props.fanoutParallelism() != null ? props.fanoutParallelism() : 8;
        this.engineSlots = new Semaphore(fanoutN);
        // F3 (R-NFR-08): deep paging gets its OWN, smaller BFF fan-out budget. The engine-side
        // Resilience4j DEEP_PAGE bulkhead only throttles AFTER a BFF slot is taken, so sharing
        // engineSlots let a scroller (or a crafted-cursor flood) occupy the interactive slots and
        // starve live search — the exact opposite of the do-no-harm claim below. A separate,
        // smaller semaphore means deep scroll queues on THESE permits and never touches interactive.
        this.deepPageSlots = new Semaphore(Math.max(1, fanoutN / 2));
        this.plan = new SearchEnginePlan(flowable, props, fanout, this);
    }

    public SearchResponse search(SearchRequest request) {
        return aggregate(request, null, null);
    }

    /**
     * Scope-filtered interactive search (S2, R-SAFE-17). {@code readableEngineIds} is the set of
     * engines the caller may read, or {@code null} = unrestricted (enforcement off — the legacy
     * fleet-wide behaviour). Only the interactive read path is scoped; {@link #resolveAllMatching}
     * (bulk enumeration) stays unfiltered — bulk is gated at submit and per item.
     */
    public SearchResponse search(SearchRequest request, Set<String> readableEngineIds) {
        return aggregate(request, null, readableEngineIds);
    }

    /**
     * Filter-bulk resolution (v1.x #2, SPEC §7): the SAME per-engine plan as {@link #search},
     * but paged to exhaustion so the returned rows are the COMPLETE match list — never one
     * grid page impersonating "all matching". {@code rowCap} bounds the harvest: a slice
     * stops collecting at {@code rowCap + 1} rows (the caller detects overflow and refuses),
     * and a MIXED-plan candidate pool that is already known to exceed the cap degrades that
     * engine honestly instead of enumerating it.
     */
    public SearchResponse resolveAllMatching(SearchRequest request, int rowCap) {
        return aggregate(request, rowCap, null);
    }

    /** A deep-paging cursor older than this is rejected (bounds replay + incoherent-merge window). */
    private static final long CURSOR_TTL_MILLIS =
            java.time.Duration.ofMinutes(10).toMillis();
    /** Default GLOBAL rows per deep page when the caller sends none; clamped by {@link #MAX_DEEP_PAGE_SIZE}. */
    private static final int DEFAULT_DEEP_PAGE_SIZE = 50;

    private static final int MAX_DEEP_PAGE_SIZE = 200;

    /**
     * One deep page of the globally-sorted merged stream (R-SEM-22 / R-NFR-08,
     * {@code docs/KWAY-PAGING.md}). MIXED / {@code startTime desc} ONLY: the cursor resumes on a
     * per-engine startTime offset, so a {@code failureTime} sort (BFF-derived, no engine resume)
     * and a FAILED/RETRYING-only (INVERTED) search are both refused here — the caller surfaces the
     * depth-wall time-filter seam instead. The fan-out runs on a dedicated BFF slot budget
     * ({@link #deepPageSlots}) AND the engine-side {@code DEEP_PAGE} Resilience4j lane, so a scroller
     * (or a crafted-cursor flood) degrades itself and never starves interactive search's slots.
     * A crafted cursor is bound-checked ({@link PagingCursor#validateInbound}) BEFORE any engine is
     * touched — that inbound check, not the {@code filterHash}, is the DoS ceiling.
     *
     * @param cursorToken the opaque cursor from the previous page, or blank/null for the first page
     * @param nowMillis   the wall clock for the TTL check and the emitted cursor's {@code issuedAt}
     */
    public DeepPage deepPage(SearchRequest request, String cursorToken, long nowMillis) {
        return deepPage(request, cursorToken, nowMillis, null);
    }

    /** Scope-filtered "Load more" (S2, R-SAFE-17): {@code readableEngineIds} null = unrestricted. */
    public DeepPage deepPage(SearchRequest request, String cursorToken, long nowMillis, Set<String> readableEngineIds) {
        BffFilters bff = BffFilters.of(request); // validates the failure window → 400 on bad ISO
        if (request.definitionVersion() != null && !notBlank(request.processDefinitionKey())) {
            throw new IllegalArgumentException("definitionVersion requires processDefinitionKey");
        }
        String sortBy = notBlank(request.sortBy()) ? request.sortBy() : "startTime";
        if (!"startTime".equals(sortBy)) {
            throw new IllegalArgumentException(
                    "deep paging is available only for startTime-ordered searches — narrow by a failure-time window instead");
        }
        Set<InstanceStatus> wanted = EnumSet.copyOf(request.effectiveStatuses());
        if (StatusJoin.planFor(wanted) == StatusJoin.Plan.INVERTED) {
            throw new IllegalArgumentException(
                    "deep paging is not available for FAILED/RETRYING-only searches — narrow by a failure-time window instead");
        }
        // #279 defensive: a provably-empty (known-stale) signature never mints a cursor at the
        // entry search, so a "Load more" for one cannot normally arrive — degrade to empty if it does.
        SignatureGeneration signatureGeneration = signatureGenerationNotice(request);
        if (signatureGeneration != null && signatureGeneration.provablyEmpty()) {
            return new DeepPage(List.of(), Map.of(), null, false);
        }

        List<EngineConfig> targets = resolveTargets(request, readableEngineIds);
        Map<String, Integer> depthCaps = new HashMap<>();
        targets.forEach(e -> depthCaps.put(e.id(), e.deepPagingMaxDepthOrDefault()));

        String filterHash = PagingCursor.filterHash(request);
        final PagingCursor incoming;
        if (notBlank(cursorToken)) {
            incoming = PagingCursor.decode(cursorToken); // 400 on garbage
            incoming.validateInbound(filterHash, sortBy, depthCaps, CURSOR_TTL_MILLIS, nowMillis); // 400 pre-fan-out
        } else {
            incoming = null;
        }

        int globalPageSize = Math.min(
                request.pageSize() != null && request.pageSize() > 0 ? request.pageSize() : DEFAULT_DEEP_PAGE_SIZE,
                MAX_DEEP_PAGE_SIZE);

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            int offset = incoming != null ? incoming.offsets().getOrDefault(engine.id(), 0) : 0;
            PageWindow window = new PageWindow(offset, Math.min(globalPageSize, engine.maxPageSizeOrDefault()));
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                deepPageSlots.acquireUninterruptibly();
                                try {
                                    return plan.searchOneEngine(
                                            engine, request, wanted, bff, null, window, CallPriority.DEEP_PAGE);
                                } finally {
                                    deepPageSlots.release();
                                }
                            },
                            fanout));
        }

        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();
        markExcludedEngines(request, targets, perEngine);
        Map<String, List<ProcessInstanceRow>> emittable = new LinkedHashMap<>();
        Map<String, List<String>> rawKeys = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            long budgetMs = engine.timeoutsOrDefault().read() * 6L + 2000;
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                emittable.put(engine.id(), slice.rows());
                rawKeys.put(engine.id(), slice.rawWindowStartKeys());
                totals.put(engine.id(), slice.total());
                perEngine.put(
                        engine.id(),
                        EngineResult.success(slice.rows().size(), slice.total(), slice.dlqScan(), slice.failingScan()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} deep page failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }

        PagingCursor.PageResult merged = PagingCursor.mergePage(
                incoming, emittable, rawKeys, totals, globalPageSize, sortBy, filterHash, depthCaps, nowMillis);
        List<ProcessInstanceRow> rows = markProtected(new ArrayList<>(merged.rows()));
        String nextCursor = merged.nextCursor() != null ? merged.nextCursor().encode() : null;
        // #273: decorate the walled engines' OWN envelope so the client can tell "this lane is
        // permanently frozen below its total" apart from "this lane just has more to fetch".
        for (String eng : merged.cappedEngines()) {
            perEngine.computeIfPresent(eng, (id, r) -> r.withCapped(true));
        }
        return new DeepPage(rows, new HashMap<>(perEngine), nextCursor, merged.depthCapped());
    }

    private SearchResponse aggregate(SearchRequest request, Integer exhaustCap, Set<String> readableEngineIds) {
        BffFilters bff = BffFilters.of(request); // validates the failure window → 400 on bad ISO
        if (request.definitionVersion() != null && !notBlank(request.processDefinitionKey())) {
            // A bare version number is meaningless across definitions — the version-drill
            // (triage cards, SPEC §8) always carries its key.
            throw new IllegalArgumentException("definitionVersion requires processDefinitionKey");
        }
        String sortBy = notBlank(request.sortBy()) ? request.sortBy() : "startTime";
        if (!sortBy.equals("startTime") && !sortBy.equals("failureTime")) {
            throw new IllegalArgumentException("sortBy must be 'startTime' or 'failureTime', got '" + sortBy + "'");
        }
        // #279 signature-generation honesty (R-SEM-03): a signature drill link is bound to the
        // normalizer generation that minted its hash. A KNOWN older/newer generation provably
        // matches no freshly-computed hash, so short-circuit to zero-WITH-REASON without touching
        // an engine — the read-path analogue of BulkErrorClassService's write-path refusal, but
        // degrading (empty + honest reason) rather than refusing (409). A legacy/unstamped link
        // still runs (it MIGHT carry a current hash) but carries the advisory so a zero is not
        // misread as a confirmed "problem solved".
        SignatureGeneration signatureGeneration = signatureGenerationNotice(request);
        if (signatureGeneration != null && signatureGeneration.provablyEmpty()) {
            return new SearchResponse(
                    List.of(),
                    Map.of(),
                    new EnumMap<>(InstanceStatus.class),
                    null,
                    null,
                    null,
                    false,
                    null,
                    signatureGeneration);
        }
        List<EngineConfig> targets = resolveTargets(request, readableEngineIds);
        if (request.variables() != null
                && !request.variables().isEmpty()
                && !notBlank(request.processDefinitionKey())) {
            // SPEC §8 guardrail: variable-value search hits typically-unindexed engine tables
            // (ACT_RU/HI_VARINST) — without a definition to narrow it, every targeted engine
            // pays a table scan.
            log.warn(
                    "Variable search without a processDefinitionKey — unindexed variable-table scan on {} engine(s); narrow by definition",
                    targets.size());
        }
        Set<InstanceStatus> wanted = EnumSet.copyOf(request.effectiveStatuses());
        Map<String, EngineResult> perEngine = new ConcurrentHashMap<>();
        markExcludedEngines(request, targets, perEngine);

        Map<EngineConfig, CompletableFuture<EngineSlice>> futures = new LinkedHashMap<>();
        for (EngineConfig engine : targets) {
            futures.put(
                    engine,
                    CompletableFuture.supplyAsync(
                            () -> {
                                engineSlots.acquireUninterruptibly();
                                try {
                                    return plan.searchOneEngine(
                                            engine, request, wanted, bff, exhaustCap, null, CallPriority.INTERACTIVE);
                                } finally {
                                    engineSlots.release();
                                }
                            },
                            fanout));
        }

        List<ProcessInstanceRow> rows = new ArrayList<>();
        Map<InstanceStatus, Long> statusCounts = new EnumMap<>(InstanceStatus.class);
        // Per-engine page-1 material for the deep-paging ENTRY cursor (below): the emittable rows,
        // the RAW window keys (offset-advance basis), and the totals (overflow detection).
        Map<String, List<ProcessInstanceRow>> emittableByEngine = new LinkedHashMap<>();
        Map<String, List<String>> rawKeysByEngine = new LinkedHashMap<>();
        Map<String, Long> totalsByEngine = new LinkedHashMap<>();
        for (Map.Entry<EngineConfig, CompletableFuture<EngineSlice>> e : futures.entrySet()) {
            EngineConfig engine = e.getKey();
            // Generous outer guard; the real limits are the per-call read timeout, the
            // per-engine breaker and the bounded scans inside the plan. Exhaustive
            // resolution pages up to cap/pageSize times, so the backstop scales with it.
            long budgetMs = engine.timeoutsOrDefault().read() * 6L + 2000;
            if (exhaustCap != null) {
                budgetMs *= Math.max(1, exhaustCap / engine.maxPageSizeOrDefault());
            }
            try {
                EngineSlice slice = e.getValue().get(budgetMs, TimeUnit.MILLISECONDS);
                rows.addAll(slice.rows());
                slice.counts().forEach((status, n) -> statusCounts.merge(status, n, Long::sum));
                emittableByEngine.put(engine.id(), slice.rows());
                rawKeysByEngine.put(engine.id(), slice.rawWindowStartKeys());
                totalsByEngine.put(engine.id(), slice.total());
                perEngine.put(
                        engine.id(),
                        EngineResult.success(slice.rows().size(), slice.total(), slice.dlqScan(), slice.failingScan()));
            } catch (TimeoutException te) {
                e.getValue().cancel(true);
                perEngine.put(engine.id(), EngineResult.failure("timeout after " + budgetMs + "ms"));
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                log.warn("Engine {} search failed: {}", engine.id(), cause.toString());
                perEngine.put(engine.id(), EngineResult.failure(cause.getMessage()));
            }
        }

        // R-SEM-23: a deterministic TOTAL order — the sort key (parsed as an Instant so the
        // offset-form vs Z-form ISO strings different engines emit collate correctly) then
        // compositeId as the final tiebreak, so a given merged set orders identically across
        // requests. Extracted to StatusJoin (rung-1 tested); the old inline branch compared
        // startTime as a raw String with no tiebreak (nondeterministic among same-second ties).
        rows.sort(StatusJoin.resultOrder(sortBy));

        // Deep-paging ENTRY cursor (R-SEM-22, docs/KWAY-PAGING.md): the frontend cannot mint the
        // opaque cursor itself, so an ordinary MIXED / startTime-desc search whose result OVERFLOWS
        // (some engine has total > fetched) hands back the cursor that resumes AFTER this page —
        // the "Load more" entry point. mergePage(null, …, pageSize = all shown rows) emits exactly
        // the page-1 rows and computes that resume position. ~95% of searches never overflow, so
        // this stays null and the cursor machinery is untouched.
        String nextCursor = null;
        boolean depthCapped = false;
        if (exhaustCap == null
                && "startTime".equals(sortBy)
                && StatusJoin.planFor(wanted) == StatusJoin.Plan.MIXED
                && !rows.isEmpty()
                && perEngine.values().stream().anyMatch(r -> r.ok() && r.total() > r.fetched())) {
            Map<String, Integer> depthCaps = new HashMap<>();
            targets.forEach(t -> depthCaps.put(t.id(), t.deepPagingMaxDepthOrDefault()));
            PagingCursor.PageResult entry = PagingCursor.mergePage(
                    null,
                    emittableByEngine,
                    rawKeysByEngine,
                    totalsByEngine,
                    rows.size(),
                    sortBy,
                    PagingCursor.filterHash(request),
                    depthCaps,
                    System.currentTimeMillis());
            depthCapped = entry.depthCapped();
            if (entry.nextCursor() != null) nextCursor = entry.nextCursor().encode();
            // #273: same per-lane decoration as deepPage() — page 1 can already wall an engine
            // (a tiny configured depth cap, or a pathological cap of 0).
            for (String eng : entry.cappedEngines()) {
                perEngine.computeIfPresent(eng, (id, r) -> r.withCapped(true));
            }
        }
        return new SearchResponse(
                markProtected(rows),
                new HashMap<>(perEngine),
                statusCounts,
                null,
                null,
                nextCursor,
                depthCapped,
                null,
                signatureGeneration);
    }

    /**
     * The #279 signature-generation notice for a signature-filtered search, or {@code null} when
     * the search does not filter on a signature OR the link carries the current generation (no
     * notice needed). Legacy/unstamped links ({@code signatureAlgoVersion == null}) are treated
     * as UNKNOWN-generation, never assumed-current — the deliberate #279 decision: assuming
     * current is exactly today's silent-zero default. Pure over the request (rung-1 testable).
     */
    static SignatureGeneration signatureGenerationNotice(SearchRequest req) {
        if (!notBlank(req.signatureHash())) {
            return null; // not a signature drill — nothing to reason about
        }
        int current = ErrorSignatureNormalizer.ALGO_VERSION;
        Integer stamped = req.signatureAlgoVersion();
        if (stamped != null && stamped == current) {
            return null; // current generation — the hash matches fresh hashes, no notice
        }
        if (stamped == null) {
            return new SignatureGeneration(
                    false,
                    null,
                    current,
                    "This link predates signature-generation stamping, so its error signature can't be tied to a"
                            + " normalizer generation. If it shows nothing, the fingerprint generation it was built"
                            + " under may have been retired (the inspector now computes v" + current + ") rather than"
                            + " the failures being resolved — re-open the group from the triage landing or its incident.");
        }
        return new SignatureGeneration(
                false,
                stamped,
                current,
                "This link was built with error-signature generation v" + stamped + " but the inspector now computes v"
                        + current + ", so a retired-generation signature matches no current failures. This is a stale"
                        + " link, not a resolved problem — re-open the group from the triage landing or its incident to"
                        + " search the current generation.");
    }

    /**
     * R-SAFE-05 enrichment for the bulk bar: one batched lookup against the protected
     * registry per result page. A Postgres outage leaves the flag {@code null} (unknown)
     * — search must not fail over it; the execution-time guard still refuses fail-closed.
     */
    private List<ProcessInstanceRow> markProtected(List<ProcessInstanceRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<ProtectedInstance.Key> guarded;
        try {
            List<ProtectedInstance.Key> keys = rows.stream()
                    .map(row -> new ProtectedInstance.Key(row.engineId(), row.processInstanceId()))
                    .toList();
            guarded = protectedInstances.findAllById(keys).stream()
                    .map(p -> new ProtectedInstance.Key(p.getEngineId(), p.getInstanceId()))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (RuntimeException e) {
            log.warn("protected-instance lookup unavailable — rows carry protectedInstance=null: {}", e.toString());
            return rows;
        }
        return rows.stream()
                .map(row -> row.withProtected(
                        guarded.contains(new ProtectedInstance.Key(row.engineId(), row.processInstanceId()))))
                .toList();
    }

    /**
     * One engine's contribution: filtered rows, honesty markers, the facet counts, and — for a
     * deep-paging window only — the sort-keys of EVERY raw engine row in the window (including
     * ones the BFF filtered out), so the cursor advances its offset over the engine's RAW result
     * set, not the filtered subset. {@code null}/empty on the normal (non-deep-paged) path.
     */
    record EngineSlice(
            List<ProcessInstanceRow> rows,
            long total,
            String dlqScan,
            String failingScan,
            Map<InstanceStatus, Long> counts,
            List<String> rawWindowStartKeys) {}

    /** One bounded page fetched from one engine at a given offset — the deep-paging fetch unit. */
    record PageWindow(int start, int size) {}

    /**
     * One deep page's result, before it is mapped onto the API {@code SearchResponse} (S3):
     * the merged/emitted rows, the per-engine reachability envelope, the opaque {@code nextCursor}
     * (null at end of stream), and whether any engine reached its depth cap.
     */
    public record DeepPage(
            List<ProcessInstanceRow> rows,
            Map<String, EngineResult> perEngine,
            String nextCursor,
            boolean depthCapped) {}

    /**
     * The parsed BFF-side filters (M2b, SPEC §8): the failure window + error text apply to
     * job rows on the scan legs (Flowable cannot query instances by dead-letter evidence);
     * currentActivity is matched against unfinished activity id/name per candidate row.
     */
    record BffFilters(
            Instant failedAfter, Instant failedBefore, String errorText, String signatureHash, String currentActivity) {
        static BffFilters of(SearchRequest req) {
            return new BffFilters(
                    parseBound(req.failureTimeAfter(), "failureTimeAfter"),
                    parseBound(req.failureTimeBefore(), "failureTimeBefore"),
                    notBlank(req.errorText()) ? req.errorText() : null,
                    notBlank(req.signatureHash()) ? req.signatureHash() : null,
                    notBlank(req.currentActivity()) ? req.currentActivity() : null);
        }

        boolean anyFailureFilter() {
            return failedAfter != null || failedBefore != null || errorText != null || signatureHash != null;
        }

        private static Instant parseBound(String iso, String field) {
            if (iso == null || iso.isBlank()) return null;
            Instant bound = StatusJoin.parseInstant(iso);
            if (bound == null) {
                throw new IllegalArgumentException(field + " is not an ISO-8601 timestamp: '" + iso + "'");
            }
            return bound;
        }
    }

    /** Failure evidence folded per instance: newest dead-letter createTime + first snippet. */
    record Failure(String failureTime, String snippet) {
        static Failure merge(Failure a, Failure b) {
            String time = a.failureTime() == null
                    ? b.failureTime()
                    : b.failureTime() == null
                            ? a.failureTime()
                            : (a.failureTime().compareTo(b.failureTime()) >= 0 ? a.failureTime() : b.failureTime());
            return new Failure(time, a.snippet() != null ? a.snippet() : b.snippet());
        }
    }

    /* ================= row assembly & query bodies ================= */

    // Package-private (not private) for SearchServiceRowTest — direct rung-1 coverage of the
    // row-assembly mapping, same testability seam as InstanceDetailService.terminationReason.
    ProcessInstanceRow row(EngineConfig engine, Map<String, Object> pi, InstanceStatusFlags flags, Failure failure) {
        String id = str(pi, "id");
        String definitionId = str(pi, "processDefinitionId");
        return new ProcessInstanceRow(
                engine.id() + ":" + id,
                engine.id(),
                engine.name(),
                engine.accentColor(),
                id,
                str(pi, "businessKey"),
                // Root-vs-child (W2 #7, R-UXQ-12): historic rows serialize the parent id for
                // call-activity children; null = root. Both plan legs assemble from historic rows.
                str(pi, "superProcessInstanceId"),
                definitionKeyOf(definitionId),
                str(pi, "processDefinitionName"),
                definitionVersionOf(definitionId),
                str(pi, "tenantId"),
                "bpmn", // carried from day one for CMMN support (SPEC §3)
                flags.primaryStatus(),
                flags,
                str(pi, "startTime"),
                str(pi, "endTime"),
                failure != null ? failure.failureTime() : null,
                failure != null ? failure.snippet() : null,
                null, // protectedInstance filled by markProtected() on the final page
                InstanceDetailService.terminationReason(pi, flags.ended()));
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

    private List<EngineConfig> resolveTargets(SearchRequest req, Set<String> readableEngineIds) {
        List<EngineConfig> base;
        if (req.engineIds() == null || req.engineIds().isEmpty()) {
            base = registry.all();
        } else {
            Set<String> ids = new HashSet<>(req.engineIds());
            base = registry.all().stream().filter(e -> ids.contains(e.id())).toList();
        }
        // S2 (R-SAFE-17): when read-scope enforcement is on, the caller may only fan out over engines
        // its grants overlap at VIEWER. null = unrestricted (enforcement off) — never treated as empty.
        if (readableEngineIds == null) {
            return base;
        }
        return base.stream().filter(e -> readableEngineIds.contains(e.id())).toList();
    }

    /**
     * Mark each EXPLICITLY-requested engine that will not be queried as a labeled excluded leg on the
     * perEngine envelope (never silently dropped, R-SEM-24): de-registered, disabled, or — under S2
     * read scoping — outside the caller's access scope. Only explicit {@code engineIds} are labeled;
     * an implicit "all engines" request narrows silently to the readable set, because labeling every
     * unreadable engine would leak the existence and ids of engines the caller may not see. An
     * enabled, still-resolvable engine absent from {@code targets} can only have been scope-excluded
     * (the disabled/removed cases are not enabled+resolvable), so the classification is exact.
     */
    private void markExcludedEngines(
            SearchRequest request, List<EngineConfig> targets, Map<String, EngineResult> perEngine) {
        if (request.engineIds() == null || request.engineIds().isEmpty()) {
            return;
        }
        Set<String> resolved = new HashSet<>();
        for (EngineConfig t : targets) {
            resolved.add(t.id());
        }
        for (String requested : request.engineIds()) {
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

    static String likePattern(String substring) {
        return substring.contains("%") ? substring : "%" + substring + "%";
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @PreDestroy
    void shutdown() {
        fanout.shutdownNow();
    }
}
