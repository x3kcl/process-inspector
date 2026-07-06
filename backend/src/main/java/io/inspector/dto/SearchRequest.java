package io.inspector.dto;

import java.util.List;

/**
 * Search semantics (spec §3.A/§8): AND between categories, OR within a category.
 * - engineIds / statuses are OR-sets;
 * - the scalar filters + every variable filter are ANDed into the engine query.
 *
 * <p>Filter kinds (ARCH §2.3): {@code businessKey(Like)}, {@code variables} and the start
 * window are FLOWABLE-NATIVE — pushed down into the engine query. {@code failureTimeAfter/
 * Before} and {@code errorText} are BFF-SIDE — Flowable cannot query instances by dead-letter
 * evidence, so they filter the job-scan legs before root resolution; setting them means only
 * failure-bearing rows can match. {@code currentActivity} is a separate native leg
 * (unfinished historic activities) intersected in the BFF.
 */
public record SearchRequest(
        List<String> engineIds, // empty/null → all enabled engines
        List<InstanceStatus> statuses, // empty/null → all statuses
        String processDefinitionKey,
        Integer definitionVersion, // exact deployed version — requires processDefinitionKey;
        // resolved per engine to the concrete processDefinitionId and pushed down natively
        // into the historic query AND the job-lane scans (the triage version-drill, SPEC §8)
        String businessKey, // Flowable exact match
        String businessKeyLike, // substring; wrapped %…% unless the caller sent wildcards.
        // NOT supported on 6.3-era engines (filter silently ignored) — detected per call
        // via an impossible-match canary; that engine degrades to an envelope error.
        String startedAfter, // ISO-8601, e.g. 2026-07-01T00:00:00Z
        String startedBefore,
        String failureTimeAfter, // ISO-8601 window over the newest dead-letter/failing-job
        String failureTimeBefore, // createTime — independent of instance start (SPEC §8)
        String errorText, // case-insensitive substring over exception snippets (BFF-side)
        String signatureHash, // normalized error-signature hash (R-SEM-03) — the triage
        // error-group drill-down. BFF-side over the failure-lane scan legs: snippet-hash
        // match, with the same one-representative-stacktrace refinement bridge triage uses
        // (a refined group hash still matches its snippet-only jobs). Failure-evidence
        // semantics like errorText: setting it means only failure-bearing rows can match.
        String currentActivity, // case-insensitive contains over unfinished activity id/name
        List<VariableFilter> variables,
        String sortBy, // startTime (default) | failureTime — merged rows, newest first
        Integer pageSize // per-engine cap, clamped by engine maxPageSize
        ) {

    /**
     * Request-side status predicates over the derived flags (SPEC §3/§8) — OR within the set.
     * FAILED ⇔ hasDeadLetterJobs ∨ failedInSubprocess; RETRYING ⇔ hasFailingJobs (failing
     * but retries remaining — display term per the §0 glossary); ACTIVE ⇔ ¬ended ∧ ¬suspended.
     */
    public enum InstanceStatus {
        ACTIVE,
        SUSPENDED,
        COMPLETED,
        FAILED,
        RETRYING
    }

    /** Maps 1:1 onto Flowable's query-variable JSON: {name, value, operation, type}. */
    public record VariableFilter(String name, Object value, String operation, String type) {}

    public List<InstanceStatus> effectiveStatuses() {
        return (statuses == null || statuses.isEmpty()) ? List.of(InstanceStatus.values()) : statuses;
    }

    /** True when any BFF-side failure-evidence filter is set (failure window / error text / signature). */
    public boolean hasFailureFilters() {
        return notBlank(failureTimeAfter)
                || notBlank(failureTimeBefore)
                || notBlank(errorText)
                || notBlank(signatureHash);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
