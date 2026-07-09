package io.inspector.aggregate;

import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.triage.ErrorSignatureNormalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The pure core of the status join (ARCHITECTURE §2.3) — plan selection, flag predicates,
 * CMMN-scope hygiene and the {@code superProcessInstanceId} chain walk. Everything here is
 * static and collection-in/collection-out so the join semantics are provable at rung 1
 * (unit-test-patterns); the engine I/O lives in {@link SearchService}.
 */
final class StatusJoin {

    private StatusJoin() {}

    /** The two v1 search plans (ARCH §2.3). */
    enum Plan {
        /** Failure-lane-only request: drive FROM the job queues, then hydrate by instance id. */
        INVERTED,
        /** Historic query per filters, then per-page flag enrichment of the displayed rows. */
        MIXED
    }

    private static final Set<InstanceStatus> FAILURE_LANES = Set.of(InstanceStatus.FAILED, InstanceStatus.RETRYING);

    /**
     * FAILED-only (and RETRYING-only) searches invert: a historic-first plan would fetch a
     * page, then discover which rows failed — silently declassifying every failed instance
     * beyond the page. Driving from the job queues finds exactly the failed set.
     */
    static Plan planFor(Set<InstanceStatus> wanted) {
        return FAILURE_LANES.containsAll(wanted) ? Plan.INVERTED : Plan.MIXED;
    }

    /** Request-side predicates over the flags — OR across the wanted set (SPEC §8). */
    static boolean matches(InstanceStatusFlags flags, Set<InstanceStatus> wanted) {
        for (InstanceStatus status : wanted) {
            boolean hit =
                    switch (status) {
                        case COMPLETED -> flags.ended();
                        case FAILED -> flags.hasDeadLetterJobs() || flags.failedInSubprocess();
                        case RETRYING -> flags.hasFailingJobs();
                        case SUSPENDED -> flags.suspended() && !flags.ended();
                        case ACTIVE -> !flags.ended() && !flags.suspended();
                    };
            if (hit) return true;
        }
        return false;
    }

    /**
     * CMMN hygiene (SPEC §3): flowable-rest shares its job tables with the CMMN engine.
     * A job without a {@code processInstanceId} — or explicitly scoped {@code cmmn} on
     * engines new enough to serialize {@code scopeType} (~6.8+) — is excluded from every
     * BPMN join leg.
     */
    static boolean isBpmnJob(Map<String, Object> job) {
        Object pid = job.get("processInstanceId");
        if (pid == null || pid.toString().isBlank()) return false;
        Object scopeType = job.get("scopeType");
        return scopeType == null || "bpmn".equalsIgnoreCase(scopeType.toString());
    }

    /**
     * BFF-side failure-evidence predicate (SPEC §8, M2b): Flowable cannot query instances by
     * dead-letter evidence, so the failure window ({@code createTime}, inclusive bounds) and
     * the error-text substring are applied to the JOB rows of the scan legs — before root
     * resolution, so a filtered-out child never rolls up. A job whose {@code createTime} is
     * missing or unparseable cannot be proven inside a requested window and is excluded.
     */
    static boolean jobMatchesFailureFilters(Map<String, Object> job, Instant after, Instant before, String errorText) {
        if (after != null || before != null) {
            Instant createTime = parseInstant(str(job, "createTime"));
            if (createTime == null) return false;
            if (after != null && createTime.isBefore(after)) return false;
            if (before != null && createTime.isAfter(before)) return false;
        }
        if (errorText != null && !errorText.isBlank()) {
            String snippet = str(job, "exceptionMessage");
            if (snippet == null || !snippet.toLowerCase(Locale.ROOT).contains(errorText.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The signature drill-down predicate (SPEC §8, R-SEM-03): keeps the jobs whose
     * normalized error signature matches {@code wantedHash}. Jobs group by their
     * snippet-level signature first (a job row carries only {@code exceptionMessage});
     * a group whose snippet hash misses gets ONE representative stacktrace via
     * {@code stacktraceOf} — the same refinement triage applies before hashing its
     * cards — bounded by {@code sampleBudget}, so a drill-down from a stacktrace-refined
     * triage card still matches its snippet-only jobs. Pure given the injected fetcher
     * (rung 1); the engine I/O lives in {@link SearchService}.
     */
    static List<Map<String, Object>> filterBySignatureHash(
            List<Map<String, Object>> jobs,
            String wantedHash,
            Function<Map<String, Object>, String> stacktraceOf,
            int sampleBudget) {
        Map<String, List<Map<String, Object>>> bySnippetHash = new LinkedHashMap<>();
        for (Map<String, Object> job : jobs) {
            String hash = ErrorSignatureNormalizer.normalize(str(job, "exceptionMessage"))
                    .hash();
            bySnippetHash.computeIfAbsent(hash, h -> new ArrayList<>()).add(job);
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> group : bySnippetHash.entrySet()) {
            boolean hit = group.getKey().equals(wantedHash);
            if (!hit && sampleBudget-- > 0) {
                String stacktrace = stacktraceOf.apply(group.getValue().get(0));
                if (stacktrace != null && !stacktrace.isBlank()) {
                    hit = ErrorSignatureNormalizer.normalize(stacktrace).hash().equals(wantedHash);
                }
            }
            if (hit) matched.addAll(group.getValue());
        }
        return matched;
    }

    /**
     * The post-merge <strong>total order</strong> for search results (R-SEM-23): the requested
     * sort key newest-first, then {@code compositeId} as the final tiebreak, so a given result set
     * orders <em>identically</em> across requests instead of leaving same-key rows in whatever
     * order the fan-out happened to append them.
     *
     * <p>The key ({@code startTime} or {@code failureTime}) is compared as a parsed {@link Instant},
     * never a raw String: different engines emit different ISO forms for the same instant — the S0
     * spike proved 6.8 serializes {@code +00:00} where 7.1 serializes {@code Z} — and a lexical
     * String compare mis-orders {@code Z} (0x5A) against {@code +} (0x2B) for the identical instant.
     * Null or unparseable keys sort last ({@code nullsLast}) but STILL tiebreak on {@code compositeId}
     * (always non-null), so the order is total even among a cluster of null-keyed rows.
     *
     * <p>Honesty (R-SEM-22): this bounds duplicate <em>emission</em> at the merge — it does NOT
     * repair an engine-side page-boundary skip under concurrent mutation. The order is deterministic
     * for a fixed input; it is never claimed stable across a straddling insert/delete.
     */
    static Comparator<ProcessInstanceRow> resultOrder(String sortBy) {
        Function<ProcessInstanceRow, String> key =
                "failureTime".equals(sortBy) ? ProcessInstanceRow::failureTime : ProcessInstanceRow::startTime;
        return Comparator.comparing(
                        (ProcessInstanceRow r) -> parseInstant(key.apply(r)),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ProcessInstanceRow::compositeId);
    }

    /**
     * Lenient ISO-8601 → {@link Instant}: engines serialize offset form
     * ({@code 2026-07-06T09:14:45.798+00:00}), requests usually the {@code Z} form — both
     * are ISO_OFFSET_DATE_TIME. Null when blank or unparseable (callers decide severity:
     * request bounds fail the request, job rows fail the match).
     */
    static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    /**
     * Walks a call-activity child up the {@code superProcessInstanceId} chain to its root.
     * Cycle-guarded and depth-limited (ARCH §2.3: a looping call-activity structure must not
     * recurse the BFF — R-TEST-07 pins this over a fixture parent-map, since a real engine
     * cannot produce a cyclic chain). Hitting the guard returns the last node reached.
     *
     * @param parentOf resolves an instance id to its {@code superProcessInstanceId}, or null
     *                 for a root (callers memoize the engine lookups behind this)
     */
    static String resolveRoot(String instanceId, Function<String, String> parentOf, int maxDepth) {
        String current = instanceId;
        Set<String> visited = new HashSet<>();
        visited.add(current);
        for (int depth = 0; depth < maxDepth; depth++) {
            String parent = parentOf.apply(current);
            if (parent == null || parent.isBlank() || !visited.add(parent)) {
                return current;
            }
            current = parent;
        }
        return current;
    }
}
