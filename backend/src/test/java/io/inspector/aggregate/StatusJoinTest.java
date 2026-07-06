package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.aggregate.StatusJoin.Plan;
import io.inspector.dto.InstanceStatusFlags;
import io.inspector.dto.SearchRequest.InstanceStatus;
import java.time.Instant;
import java.util.HashMap;
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
}
