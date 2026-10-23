package io.inspector.migration;

import io.inspector.migration.MigrationFinding.Code;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Folds one preview's §14 findings per instance into cohort counts (#495). No engine calls.
 * An instance is counted once: any {@code BLOCKER_ADVICE} is a blocker (and drops out of the
 * executable set), else any {@code WARNING} is a warning, else it is clean. {@code INFO} does
 * not warn. Activity totals count distinct instances, so a repeated finding on one instance
 * does not inflate the count.
 */
public final class CohortFindingAggregator {

    private CohortFindingAggregator() {}

    public static Result aggregate(List<Instance> instances) {
        int blockers = 0;
        int warnings = 0;
        int clean = 0;
        List<String> executable = new ArrayList<>();
        List<BlockedInstance> blocked = new ArrayList<>();
        List<String> sample = new ArrayList<>();
        List<InstanceRow> rows = new ArrayList<>();
        Map<Key, LinkedHashSet<String>> byActivity = new LinkedHashMap<>();
        Map<Code, LinkedHashSet<String>> byCode = new LinkedHashMap<>();
        for (Instance instance : instances == null ? List.<Instance>of() : instances) {
            if (instance == null || instance.instanceId() == null) {
                continue;
            }
            String id = instance.instanceId();
            sample.add(id);
            boolean blocker = instance.unreadReason() != null
                    || instance.findings().stream().anyMatch(MigrationFinding::isBlockerAdvice);
            boolean warning = !blocker && instance.findings().stream().anyMatch(MigrationFinding::isWarning);
            if (blocker) {
                blockers++;
                blocked.add(new BlockedInstance(id, reason(instance)));
                rows.add(new InstanceRow(id, "blocker", instance.findings()));
            } else if (warning) {
                warnings++;
                executable.add(id);
                rows.add(new InstanceRow(id, "warning", instance.findings()));
            } else {
                clean++;
                executable.add(id);
                rows.add(new InstanceRow(id, "clean", instance.findings()));
            }
            for (MigrationFinding finding : instance.findings()) {
                byActivity
                        .computeIfAbsent(new Key(finding.activityId(), finding.code()), key -> new LinkedHashSet<>())
                        .add(id);
                byCode.computeIfAbsent(finding.code(), key -> new LinkedHashSet<>())
                        .add(id);
            }
        }
        List<ActivityAggregate> aggregates = new ArrayList<>();
        byActivity.forEach(
                (key, ids) -> aggregates.add(new ActivityAggregate(key.activityId(), key.code(), ids.size())));
        aggregates.sort(
                Comparator.comparing(ActivityAggregate::activityId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(aggregate -> aggregate.code().name()));
        Map<String, Integer> codeCounts = new LinkedHashMap<>();
        byCode.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry ->
                        codeCounts.put(entry.getKey().name(), entry.getValue().size()));
        return new Result(
                blockers,
                warnings,
                clean,
                List.copyOf(aggregates),
                List.copyOf(executable),
                List.copyOf(blocked),
                List.copyOf(sample),
                Map.copyOf(codeCounts),
                List.copyOf(rows));
    }

    /** Blocker finding details, first-seen order, duplicates collapsed. An unread preview wins. */
    private static String reason(Instance instance) {
        if (instance.unreadReason() != null) {
            return instance.unreadReason();
        }
        LinkedHashSet<String> details = new LinkedHashSet<>();
        for (MigrationFinding finding : instance.findings()) {
            if (finding.isBlockerAdvice() && finding.detail() != null) {
                details.add(finding.detail());
            }
        }
        return String.join(" ", details);
    }

    /**
     * @param unreadReason set when preview refused this instance, so it stays out of the
     *     executable set without inventing a taxonomy code
     */
    public record Instance(String instanceId, List<MigrationFinding> findings, String unreadReason) {
        public Instance(String instanceId, List<MigrationFinding> findings) {
            this(instanceId, findings, null);
        }

        public Instance {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    public record Result(
            int blockerCount,
            int warningCount,
            int cleanCount,
            List<ActivityAggregate> byActivity,
            List<String> executableIds,
            List<BlockedInstance> blockers,
            List<String> sampleInstanceIds,
            Map<String, Integer> codeCounts,
            List<InstanceRow> instances) {}

    public record ActivityAggregate(String activityId, Code code, int instances) {}

    public record BlockedInstance(String instanceId, String reason) {}

    /** One analyzed instance, in cohort order. {@code bucket} is blocker, warning, or clean. */
    public record InstanceRow(String instanceId, String bucket, List<MigrationFinding> findings) {}

    private record Key(String activityId, Code code) {}
}
