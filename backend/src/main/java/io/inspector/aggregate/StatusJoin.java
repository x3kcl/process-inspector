package io.inspector.aggregate;

import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.util.HashSet;
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
