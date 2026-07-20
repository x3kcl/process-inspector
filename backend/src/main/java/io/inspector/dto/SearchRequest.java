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
        // match against freshly-computed hashes. Failure-evidence semantics like errorText:
        // setting it means only failure-bearing rows can match. Paired with signatureAlgoVersion
        // (#279): the hash is an opaque key whose MEANING is bound to the normalizer generation
        // that minted it, so a drill link must carry the generation it was built under.
        String currentActivity, // case-insensitive contains over unfinished activity id/name
        List<VariableFilter> variables,
        String sortBy, // startTime (default) | failureTime — merged rows, newest first
        Integer pageSize, // per-engine cap, clamped by engine maxPageSize
        // v2 deep paging (docs/KWAY-PAGING.md, R-SEM-22): an opaque cursor from a previous page's
        // nextCursor. Present ⇒ this is a "Load more" for the SAME filter (which is re-sent in the
        // body and is authoritative — the cursor only carries the resume offsets, bound to the
        // filter by a hash). pageSize is then the GLOBAL rows to emit; the per-engine window is
        // internal. Absent ⇒ an ordinary single-shot search. MIXED/startTime-desc only.
        String cursor,
        // #279 the normalizer generation the paired signatureHash was minted under
        // (ErrorSignatureNormalizer.ALGO_VERSION at link-build time). null = a legacy/unstamped
        // link (pre-#279 bookmark) — assumed UNKNOWN generation, never assumed-current: the read
        // path degrades an empty result into an honest "retired generation" reason instead of a
        // silent zero. Present-but-not-current ⇒ the hash provably matches no fresh (current-
        // generation) hash, so the search short-circuits to zero-with-reason (SearchResponse
        // .signatureGeneration), mirroring how BulkErrorClassService refuses the same mismatch
        // on the WRITE path — but degrading rather than refusing (SPEC §8, R-SEM-03).
        Integer signatureAlgoVersion) {

    /**
     * Pre-#279 17-arg shape (no {@code signatureAlgoVersion}) → an unstamped/legacy signature
     * link, assumed unknown-generation. Keeps existing call sites/tests off constructor churn.
     */
    public SearchRequest(
            List<String> engineIds,
            List<InstanceStatus> statuses,
            String processDefinitionKey,
            Integer definitionVersion,
            String businessKey,
            String businessKeyLike,
            String startedAfter,
            String startedBefore,
            String failureTimeAfter,
            String failureTimeBefore,
            String errorText,
            String signatureHash,
            String currentActivity,
            List<VariableFilter> variables,
            String sortBy,
            Integer pageSize,
            String cursor) {
        this(
                engineIds,
                statuses,
                processDefinitionKey,
                definitionVersion,
                businessKey,
                businessKeyLike,
                startedAfter,
                startedBefore,
                failureTimeAfter,
                failureTimeBefore,
                errorText,
                signatureHash,
                currentActivity,
                variables,
                sortBy,
                pageSize,
                cursor,
                null);
    }

    /**
     * Pre-deep-paging 16-arg shape (no {@code cursor}) → an ordinary single-shot search. Keeps
     * existing call sites/tests off constructor churn (unit-test-patterns: no constructor churn).
     */
    public SearchRequest(
            List<String> engineIds,
            List<InstanceStatus> statuses,
            String processDefinitionKey,
            Integer definitionVersion,
            String businessKey,
            String businessKeyLike,
            String startedAfter,
            String startedBefore,
            String failureTimeAfter,
            String failureTimeBefore,
            String errorText,
            String signatureHash,
            String currentActivity,
            List<VariableFilter> variables,
            String sortBy,
            Integer pageSize) {
        this(
                engineIds,
                statuses,
                processDefinitionKey,
                definitionVersion,
                businessKey,
                businessKeyLike,
                startedAfter,
                startedBefore,
                failureTimeAfter,
                failureTimeBefore,
                errorText,
                signatureHash,
                currentActivity,
                variables,
                sortBy,
                pageSize,
                null);
    }

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
