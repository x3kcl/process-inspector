package io.inspector.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.inspector.migration.CohortFindingAggregator.ActivityAggregate;
import io.inspector.migration.CohortFindingAggregator.BlockedInstance;
import io.inspector.migration.CohortFindingAggregator.Instance;
import io.inspector.migration.MigrationFinding.Code;
import io.inspector.migration.MigrationFinding.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rung 1 (#495): fold per-instance §14 findings into blocker / warning / clean and activity counts. */
class CohortFindingAggregatorTest {

    @Test
    void countsBlockersWarningsAndCleanAndDropsBlockersFromTheExecutableSet() {
        Instance both = new Instance(
                "both",
                List.of(
                        finding(
                                Code.UNMAPPED_ACTIVE_ACTIVITY,
                                Severity.BLOCKER_ADVICE,
                                "reviewTask",
                                "nothing to send"),
                        finding(
                                Code.UNMAPPED_ACTIVE_ACTIVITY,
                                Severity.BLOCKER_ADVICE,
                                "reviewTask",
                                "nothing to send"),
                        finding(Code.TYPE_CHANGED_SAME_ID, Severity.WARNING, "reviewTask", "type changed")));
        Instance loss = new Instance(
                "loss",
                List.of(finding(Code.SCOPE_COLLAPSE_TOKEN_LOSS, Severity.BLOCKER_ADVICE, "scopeA", "two tokens die")));
        Instance also = new Instance(
                "also",
                List.of(finding(
                        Code.UNMAPPED_ACTIVE_ACTIVITY, Severity.BLOCKER_ADVICE, "reviewTask", "nothing to send")));
        Instance warn = new Instance(
                "warn",
                List.of(
                        finding(Code.ACTIVE_SCOPE_REMOVED, Severity.WARNING, "scopeB", "scope dissolves"),
                        finding(Code.NESTING_PATH_CHANGED, Severity.WARNING, "stepA", "moved scope")));
        Instance info = new Instance(
                "info", List.of(finding(Code.BOUNDARY_CLOCK_RESET, Severity.INFO, null, "clock may reset")));
        Instance plain = new Instance("plain", List.of());

        CohortFindingAggregator.Result result =
                CohortFindingAggregator.aggregate(List.of(both, loss, also, warn, info, plain));

        assertThat(result.blockerCount()).isEqualTo(3);
        assertThat(result.warningCount()).isEqualTo(1);
        assertThat(result.cleanCount()).isEqualTo(2);
        assertThat(result.executableIds()).containsExactly("warn", "info", "plain");
        assertThat(result.executableIds()).doesNotContain("both", "loss", "also");
        assertThat(result.blockers())
                .extracting(BlockedInstance::instanceId, BlockedInstance::reason)
                .containsExactly(
                        tuple("both", "nothing to send"),
                        tuple("loss", "two tokens die"),
                        tuple("also", "nothing to send"));
        assertThat(result.byActivity())
                .extracting(
                        ActivityAggregate::activityId,
                        aggregate -> aggregate.code().name(),
                        ActivityAggregate::instances)
                .containsExactlyInAnyOrder(
                        tuple("reviewTask", "UNMAPPED_ACTIVE_ACTIVITY", 2),
                        tuple("reviewTask", "TYPE_CHANGED_SAME_ID", 1),
                        tuple("scopeA", "SCOPE_COLLAPSE_TOKEN_LOSS", 1),
                        tuple("scopeB", "ACTIVE_SCOPE_REMOVED", 1),
                        tuple("stepA", "NESTING_PATH_CHANGED", 1),
                        tuple(null, "BOUNDARY_CLOCK_RESET", 1));
    }

    private static MigrationFinding finding(Code code, Severity severity, String activityId, String detail) {
        return new MigrationFinding(code, severity, activityId, detail);
    }
}
