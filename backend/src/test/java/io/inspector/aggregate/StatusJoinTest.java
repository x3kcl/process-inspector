package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.aggregate.StatusJoin.Plan;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.ProcessInstanceRow;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rung 1 (unit-test-patterns): the pure join semantics — plan selection, flag predicates,
 * primary-chip precedence, CMMN hygiene, and the cycle-guarded hierarchy walk (R-TEST-07:
 * the cycle case is ONLY testable here — a real engine cannot produce a cyclic
 * superProcessInstanceId chain).
 */
class StatusJoinTest {

    /* ---------- plan selection ---------- */

    @Nested
    class PlanSelection {

        @Test
        void failedOnlyInverts() {
            assertThat(StatusJoin.planFor(Set.of(InstanceStatus.FAILED))).isEqualTo(Plan.INVERTED);
        }

        @Test
        void retryingOnlyInverts() {
            assertThat(StatusJoin.planFor(Set.of(InstanceStatus.RETRYING))).isEqualTo(Plan.INVERTED);
        }

        @Test
        void failedPlusRetryingInverts() {
            assertThat(StatusJoin.planFor(Set.of(InstanceStatus.FAILED, InstanceStatus.RETRYING)))
                    .isEqualTo(Plan.INVERTED);
        }

        @Test
        void anyNonFailureStatusForcesMixed() {
            assertThat(StatusJoin.planFor(Set.of(InstanceStatus.FAILED, InstanceStatus.ACTIVE)))
                    .isEqualTo(Plan.MIXED);
            assertThat(StatusJoin.planFor(Set.of(InstanceStatus.COMPLETED))).isEqualTo(Plan.MIXED);
            assertThat(StatusJoin.planFor(Set.of(InstanceStatus.values()))).isEqualTo(Plan.MIXED);
        }
    }

    /* ---------- flag predicates (OR within the wanted set) ---------- */

    @Nested
    class Predicates {

        final InstanceStatusFlags failedActive = new InstanceStatusFlags(false, false, true, false, false);
        final InstanceStatusFlags suspendedWithDlq = new InstanceStatusFlags(false, true, true, false, false);
        final InstanceStatusFlags rolledUpParent = new InstanceStatusFlags(false, false, false, false, true);
        final InstanceStatusFlags retrying = new InstanceStatusFlags(false, false, false, true, false);
        final InstanceStatusFlags completed = new InstanceStatusFlags(true, false, false, false, false);
        final InstanceStatusFlags plainActive = InstanceStatusFlags.NONE;

        @Test
        void failedMatchesDeadLetterAndSubprocessRollup() {
            assertThat(StatusJoin.matches(failedActive, Set.of(InstanceStatus.FAILED)))
                    .isTrue();
            assertThat(StatusJoin.matches(rolledUpParent, Set.of(InstanceStatus.FAILED)))
                    .isTrue();
            assertThat(StatusJoin.matches(retrying, Set.of(InstanceStatus.FAILED)))
                    .isFalse();
        }

        @Test
        void collisionsMatchEitherPredicate() {
            // A suspended instance with dead-letter jobs is found by BOTH filters (SPEC §3).
            assertThat(StatusJoin.matches(suspendedWithDlq, Set.of(InstanceStatus.SUSPENDED)))
                    .isTrue();
            assertThat(StatusJoin.matches(suspendedWithDlq, Set.of(InstanceStatus.FAILED)))
                    .isTrue();
            assertThat(StatusJoin.matches(suspendedWithDlq, Set.of(InstanceStatus.ACTIVE)))
                    .isFalse();
        }

        @Test
        void retryingIsItsOwnTier() {
            assertThat(StatusJoin.matches(retrying, Set.of(InstanceStatus.RETRYING)))
                    .isTrue();
            assertThat(StatusJoin.matches(failedActive, Set.of(InstanceStatus.RETRYING)))
                    .isFalse();
        }

        @Test
        void completedAndActiveAreLifecyclePredicates() {
            assertThat(StatusJoin.matches(completed, Set.of(InstanceStatus.COMPLETED)))
                    .isTrue();
            assertThat(StatusJoin.matches(plainActive, Set.of(InstanceStatus.ACTIVE)))
                    .isTrue();
            // An active-but-failed instance still satisfies the ACTIVE lifecycle predicate.
            assertThat(StatusJoin.matches(failedActive, Set.of(InstanceStatus.ACTIVE)))
                    .isTrue();
        }
    }

    /* ---------- primary chip precedence ---------- */

    @Test
    void primaryChipPrecedenceIsCompletedFailedRetryingSuspendedActive() {
        assertThat(new InstanceStatusFlags(true, false, true, true, true).primaryStatus())
                .isEqualTo(InstanceStatus.COMPLETED);
        assertThat(new InstanceStatusFlags(false, true, true, true, false).primaryStatus())
                .isEqualTo(InstanceStatus.FAILED);
        assertThat(new InstanceStatusFlags(false, false, false, false, true).primaryStatus())
                .isEqualTo(InstanceStatus.FAILED);
        assertThat(new InstanceStatusFlags(false, true, false, true, false).primaryStatus())
                .isEqualTo(InstanceStatus.RETRYING);
        assertThat(new InstanceStatusFlags(false, true, false, false, false).primaryStatus())
                .isEqualTo(InstanceStatus.SUSPENDED);
        assertThat(InstanceStatusFlags.NONE.primaryStatus()).isEqualTo(InstanceStatus.ACTIVE);
    }

    /* ---------- CMMN hygiene ---------- */

    @Test
    void cmmnScopedJobsAreExcludedFromEveryLeg() {
        assertThat(StatusJoin.isBpmnJob(Map.of("processInstanceId", "pi-1"))).isTrue();
        assertThat(StatusJoin.isBpmnJob(Map.of("processInstanceId", "pi-1", "scopeType", "bpmn")))
                .isTrue();
        // CMMN jobs share the tables: null processInstanceId, or explicit cmmn scope.
        assertThat(StatusJoin.isBpmnJob(Map.of())).isFalse();
        assertThat(StatusJoin.isBpmnJob(Map.of("processInstanceId", "pi-1", "scopeType", "cmmn")))
                .isFalse();
    }

    /* ---------- hierarchy walk (R-TEST-07) ---------- */

    @Nested
    class HierarchyWalk {

        @Test
        void childResolvesToRootThroughTheChain() {
            Map<String, String> parents = Map.of("grandchild", "child", "child", "root");
            assertThat(StatusJoin.resolveRoot("grandchild", parents::get, 10)).isEqualTo("root");
        }

        @Test
        void rootResolvesToItself() {
            assertThat(StatusJoin.resolveRoot("root", id -> null, 10)).isEqualTo("root");
        }

        @Test
        void cyclicParentMapTerminatesInsteadOfRecursingTheBff() {
            // Real engines cannot produce this; the guard exists so a data anomaly (or a
            // future engine bug) degrades to a truncated walk, never a spinning BFF.
            Map<String, String> cycle = Map.of("a", "b", "b", "c", "c", "a");
            AtomicInteger lookups = new AtomicInteger();
            String root = StatusJoin.resolveRoot(
                    "a",
                    id -> {
                        lookups.incrementAndGet();
                        return cycle.get(id);
                    },
                    10);
            assertThat(root).isIn("a", "b", "c");
            assertThat(lookups.get()).isLessThanOrEqualTo(3);
        }

        @Test
        void depthLimitStopsARunawayChain() {
            // Synthetic 100-deep chain: n0 <- n1 <- ... walker starts at n0, limit 10.
            Map<String, String> parents = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                parents.put("n" + i, "n" + (i + 1));
            }
            assertThat(StatusJoin.resolveRoot("n0", parents::get, 10)).isEqualTo("n10");
        }
    }

    /* ---------- M2b BFF-side failure filters (SPEC §8) ---------- */

    @Nested
    class FailureFilters {

        final Map<String, Object> job = Map.of(
                "processInstanceId", "pi-1",
                "createTime", "2026-07-06T09:14:45.798+00:00", // the engines' offset wire form
                "exceptionMessage", "Error while evaluating expression: ${amount % divisor}");

        final Instant nineAm = Instant.parse("2026-07-06T09:00:00Z");
        final Instant tenAm = Instant.parse("2026-07-06T10:00:00Z");

        @Test
        void windowBoundsAreInclusiveOverCreateTime() {
            assertThat(StatusJoin.jobMatchesFailureFilters(job, nineAm, tenAm, null))
                    .isTrue();
            assertThat(StatusJoin.jobMatchesFailureFilters(job, tenAm, null, null))
                    .isFalse();
            assertThat(StatusJoin.jobMatchesFailureFilters(job, null, nineAm, null))
                    .isFalse();
            Instant exact = Instant.parse("2026-07-06T09:14:45.798Z");
            assertThat(StatusJoin.jobMatchesFailureFilters(job, exact, exact, null))
                    .isTrue();
        }

        @Test
        void errorTextIsACaseInsensitiveSubstringOverTheSnippet() {
            assertThat(StatusJoin.jobMatchesFailureFilters(job, null, null, "AMOUNT % DIVISOR"))
                    .isTrue();
            assertThat(StatusJoin.jobMatchesFailureFilters(job, null, null, "connection refused"))
                    .isFalse();
        }

        @Test
        void aJobWithoutProvableCreateTimeCannotMatchAWindow() {
            Map<String, Object> noTime = Map.of("processInstanceId", "pi-2", "exceptionMessage", "boom");
            assertThat(StatusJoin.jobMatchesFailureFilters(noTime, nineAm, null, null))
                    .isFalse();
            // ...but text-only filtering still works without a timestamp
            assertThat(StatusJoin.jobMatchesFailureFilters(noTime, null, null, "boom"))
                    .isTrue();
        }

        @Test
        void parseInstantAcceptsBothOffsetAndZuluFormsAndRefusesGarbage() {
            assertThat(StatusJoin.parseInstant("2026-07-06T09:14:45.798+00:00"))
                    .isEqualTo(Instant.parse("2026-07-06T09:14:45.798Z"));
            assertThat(StatusJoin.parseInstant("2026-07-06T10:00:00Z")).isEqualTo(tenAm);
            assertThat(StatusJoin.parseInstant("not-a-date")).isNull();
            assertThat(StatusJoin.parseInstant(null)).isNull();
        }
    }

    /* ---------- M3: the signature drill-down filter (pure core) ---------- */

    @Nested
    class SignatureHashFilter {

        private final Map<String, Object> divisorJob1 =
                Map.of("id", "job-1", "processInstanceId", "pi-1", "exceptionMessage", "/ by zero");
        private final Map<String, Object> divisorJob2 =
                Map.of("id", "job-2", "processInstanceId", "pi-2", "exceptionMessage", "/ by zero");
        private final Map<String, Object> timeoutJob = Map.of(
                "id", "job-3", "processInstanceId", "pi-3", "exceptionMessage", "connection refused to 10.0.0.7");

        private String snippetHashOf(String message) {
            return io.inspector.triage.ErrorSignatureNormalizer.normalize(message)
                    .hash();
        }

        @Test
        void snippetHashMatchKeepsExactlyThatGroupsJobs() {
            var kept = StatusJoin.filterBySignatureHash(
                    java.util.List.of(divisorJob1, timeoutJob, divisorJob2),
                    snippetHashOf("/ by zero"),
                    job -> {
                        throw new AssertionError("no stacktrace fetch needed on a snippet match");
                    },
                    0);
            assertThat(kept).containsExactly(divisorJob1, divisorJob2);
        }

        @Test
        void refinedHashMatchesViaOneRepresentativeStacktracePerGroup() {
            // The drill hash comes from a stacktrace-REFINED triage card — the snippet-only
            // hash differs. The bridge fetches ONE representative stacktrace per group.
            String refinedHash =
                    snippetHashOf("java.lang.ArithmeticException: / by zero\n\tat MapBasedELResolver.invoke(...)");
            AtomicInteger fetches = new AtomicInteger();
            var kept = StatusJoin.filterBySignatureHash(
                    java.util.List.of(divisorJob1, divisorJob2, timeoutJob),
                    refinedHash,
                    job -> {
                        fetches.incrementAndGet();
                        return "job-3".equals(job.get("id"))
                                ? "java.net.ConnectException: connection refused to 10.0.0.7"
                                : "java.lang.ArithmeticException: / by zero\n\tat whatever(...)";
                    },
                    10);
            assertThat(kept).containsExactly(divisorJob1, divisorJob2);
            assertThat(fetches.get()).as("one fetch per GROUP, never per job").isEqualTo(2);
        }

        @Test
        void refinementStopsAtTheSampleBudgetAndAFailedFetchDegradesToTheSnippet() {
            String refinedHash = snippetHashOf("java.lang.ArithmeticException: / by zero");
            // Budget 0: no refinement allowed — nothing matches on snippet, nothing kept.
            var kept = StatusJoin.filterBySignatureHash(
                    java.util.List.of(divisorJob1, timeoutJob), refinedHash, job -> "unused", 0);
            assertThat(kept).isEmpty();
            // A null stacktrace (job gone between scan and fetch) degrades quietly.
            var keptNullTrace =
                    StatusJoin.filterBySignatureHash(java.util.List.of(divisorJob1), refinedHash, job -> null, 10);
            assertThat(keptNullTrace).isEmpty();
        }

        @Test
        void noMatchMeansEmptyNeverUnfiltered() {
            var kept = StatusJoin.filterBySignatureHash(
                    java.util.List.of(divisorJob1, timeoutJob), "0".repeat(64), job -> null, 10);
            assertThat(kept).isEmpty();
        }
    }

    /* ---------- R-SEM-23: deterministic total order (KWAY-PAGING §4) ---------- */

    @Nested
    class ResultOrder {

        /** A row carrying only the fields the comparator reads; the rest are inert. */
        private ProcessInstanceRow row(String compositeId, String startTime, String failureTime) {
            return new ProcessInstanceRow(
                    compositeId,
                    "eng",
                    null,
                    null,
                    "pid",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "bpmn",
                    null,
                    null,
                    startTime,
                    null,
                    failureTime,
                    null,
                    null);
        }

        @Test
        void identicalSortKeyBreaksTiesOnCompositeIdNotAppendOrder() {
            // Same instant → order is decided solely by the compositeId tiebreak (ascending),
            // regardless of the order the fan-out appended them.
            var list = new ArrayList<>(List.of(
                    row("e:2", "2026-07-09T10:00:00.000Z", null),
                    row("e:0", "2026-07-09T10:00:00.000Z", null),
                    row("e:1", "2026-07-09T10:00:00.000Z", null)));
            list.sort(StatusJoin.resultOrder("startTime"));
            assertThat(list).extracting(ProcessInstanceRow::compositeId).containsExactly("e:0", "e:1", "e:2");
        }

        @Test
        void offsetFormAndZFormOfTheSameInstantCompareEqualThenTiebreak() {
            // The bug R-SEM-23 fixes: a raw String desc compare orders "…Z" (0x5A) ABOVE
            // "…+00:00" (0x2B) though they are the SAME instant → e:9 would wrongly sort first.
            // Instant-parse makes them equal, so the compositeId tiebreak (asc) decides: e:1, e:9.
            var list = new ArrayList<>(List.of(
                    row("e:9", "2026-07-09T10:00:00.000Z", null), row("e:1", "2026-07-09T10:00:00.000+00:00", null)));
            list.sort(StatusJoin.resultOrder("startTime"));
            assertThat(list).extracting(ProcessInstanceRow::compositeId).containsExactly("e:1", "e:9");
        }

        @Test
        void nullAndUnparseableKeysSortLastButStillTiebreak() {
            // Null startTime and a garbage string both resolve to a null key → nullsLast, then
            // the two null-keyed rows still tiebreak on compositeId (a total order among nulls).
            var list = new ArrayList<>(List.of(
                    row("e:2", null, null),
                    row("e:5", "2026-07-09T10:00:00.000Z", null),
                    row("e:1", "not-a-timestamp", null)));
            list.sort(StatusJoin.resultOrder("startTime"));
            assertThat(list).extracting(ProcessInstanceRow::compositeId).containsExactly("e:5", "e:1", "e:2");
        }

        @Test
        void failureTimeModeSortsOnFailureTimeInstantDescending() {
            var list = new ArrayList<>(List.of(
                    row("e:2", "2026-07-09T23:59:00.000Z", "2026-07-09T08:00:00.000+00:00"),
                    row("e:1", "2026-07-09T00:00:00.000Z", "2026-07-09T12:00:00.000Z")));
            list.sort(StatusJoin.resultOrder("failureTime"));
            // Newest failureTime first (e:1), even though its startTime is oldest — the key is failureTime.
            assertThat(list).extracting(ProcessInstanceRow::compositeId).containsExactly("e:1", "e:2");
        }

        @Test
        void orderIsStableAcrossRepeatedSortsOfAShuffledInput() {
            var a = row("e:3", "2026-07-09T10:00:00.000Z", null);
            var b = row("e:1", "2026-07-09T10:00:00.000+00:00", null); // same instant as a
            var c = row("e:2", "2026-07-09T09:00:00.000Z", null); // older
            var first = new ArrayList<>(List.of(a, b, c));
            first.sort(StatusJoin.resultOrder("startTime"));
            var second = new ArrayList<>(List.of(c, a, b));
            second.sort(StatusJoin.resultOrder("startTime"));
            assertThat(first).extracting(ProcessInstanceRow::compositeId).containsExactly("e:1", "e:3", "e:2");
            assertThat(second)
                    .extracting(ProcessInstanceRow::compositeId)
                    .isEqualTo(
                            first.stream().map(ProcessInstanceRow::compositeId).toList());
        }
    }
}
