package io.inspector.heat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import io.inspector.dto.DefinitionHeat.Coverage;
import io.inspector.dto.DefinitionHeat.NodeHeat;
import io.inspector.dto.DefinitionHeat.Outcome;
import io.inspector.dto.DefinitionHeat.Tier;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure aggregation of per-instance historic activity rows into {@link DefinitionHeat}. */
public final class HeatAggregator {

    private HeatAggregator() {}

    /** One sampled instance: the rows read, the engine's {@code total}, and whether the read failed. */
    public record Instance(List<ActivityInstanceRecord> rows, long totalRows, boolean readFailed) {}

    public record Sample(
            String engineId,
            String definitionId,
            Instant asOf,
            int requestedN,
            long finishedTotal,
            Instant oldestEnd,
            Instant newestEnd,
            List<Instance> instances,
            String bpmnXml) {

        /** Without the definition's BPMN XML: armed event-gateway catch events cannot be resolved. */
        public Sample(
                String engineId,
                String definitionId,
                Instant asOf,
                int requestedN,
                long finishedTotal,
                Instant oldestEnd,
                Instant newestEnd,
                List<Instance> instances) {
            this(engineId, definitionId, asOf, requestedN, finishedTotal, oldestEnd, newestEnd, instances, null);
        }
    }

    private static final String FLOW = "sequenceFlow";
    private static final int P95_FLOOR = 20;

    private static final class Acc {
        String name;
        String type;
        int executions;
        int instances;
        int lastInstance = -1;
        long total;
        final List<Long> timed = new ArrayList<>();
    }

    public static DefinitionHeat aggregate(Sample s) {
        int tooManyRows = 0;
        int readFailed = 0;
        List<List<ActivityInstanceRecord>> ok = new ArrayList<>();
        for (Instance i : s.instances()) {
            if (i.readFailed()) {
                readFailed++;
            } else if (i.totalRows() > i.rows().size()) {
                tooManyRows++;
            } else {
                ok.add(i.rows());
            }
        }
        int sampled = s.instances().size();
        if (sampled > 0 && ok.isEmpty()) {
            throw new IllegalStateException("every sampled instance was dropped");
        }
        boolean noActivity = ok.stream().anyMatch(List::isEmpty);
        boolean anyFlow = ok.stream().flatMap(List::stream).anyMatch(r -> FLOW.equals(r.activityType()));
        Coverage coverage = sampled == 0
                ? Coverage.NO_HISTORY
                : noActivity ? Coverage.NO_ACTIVITY_HISTORY : anyFlow ? Coverage.FULL : Coverage.NO_FLOW_HISTORY;
        boolean figures = coverage == Coverage.FULL || coverage == Coverage.NO_FLOW_HISTORY;

        List<NodeHeat> nodes = figures ? nodes(ok) : List.of();
        List<Outcome> outcomes = new ArrayList<>();
        int noEnd = 0;
        if (figures) {
            noEnd = outcomes(ok, outcomes);
        }
        int dropped = tooManyRows + readFailed;
        return new DefinitionHeat(
                s.engineId(),
                s.definitionId(),
                s.asOf(),
                false,
                coverage,
                null,
                s.requestedN(),
                sampled,
                s.finishedTotal(),
                dropped,
                new DefinitionHeat.DropReasons(tooManyRows, readFailed),
                s.oldestEnd(),
                s.newestEnd(),
                dropped > 0,
                nodes,
                outcomes,
                noEnd,
                "Newest %d of %d finished instances (sample, not the full history)%s."
                        .formatted(
                                sampled,
                                s.finishedTotal(),
                                dropped > 0
                                        ? "; %d dropped, figures over the remaining %d".formatted(dropped, ok.size())
                                        : ""));
    }

    private static List<NodeHeat> nodes(List<List<ActivityInstanceRecord>> ok) {
        Map<String, Acc> accs = new LinkedHashMap<>();
        for (int idx = 0; idx < ok.size(); idx++) {
            for (ActivityInstanceRecord r : ok.get(idx)) {
                if (FLOW.equals(r.activityType()) || r.activityId() == null) {
                    continue;
                }
                Acc a = accs.computeIfAbsent(r.activityId(), k -> new Acc());
                if (a.name == null) {
                    a.name = r.activityName();
                }
                if (a.type == null) {
                    a.type = r.activityType();
                }
                a.executions++;
                if (a.lastInstance != idx) {
                    a.lastInstance = idx;
                    a.instances++;
                }
                if (r.endTime() != null && r.durationInMillis() != null) {
                    a.timed.add(r.durationInMillis());
                    a.total += r.durationInMillis();
                }
            }
        }
        long grand = accs.values().stream().mapToLong(a -> a.total).sum();
        return accs.entrySet().stream()
                .map(e -> node(e.getKey(), e.getValue(), ok.size(), grand))
                .sorted(Comparator.comparingLong(NodeHeat::totalMs).reversed().thenComparing(NodeHeat::activityId))
                .toList();
    }

    private static NodeHeat node(String id, Acc a, int analysed, long grand) {
        List<Long> sorted = a.timed.stream().sorted().toList();
        boolean any = !sorted.isEmpty();
        Double avg = any ? (double) a.total / sorted.size() : null;
        double share = grand > 0 ? a.total * 100.0 / grand : 0.0;
        Tier tier = avg == null || avg < 1
                ? Tier.NONE
                : share >= 40 ? Tier.DOMINANT : share >= 10 ? Tier.WARM : share >= 1 ? Tier.COOL : Tier.NONE;
        return new NodeHeat(
                id,
                a.name,
                a.type,
                a.executions,
                a.instances,
                a.instances * 100.0 / analysed,
                0,
                avg,
                any ? nearestRank(sorted, 50) : null,
                sorted.size() >= P95_FLOOR ? nearestRank(sorted, 95) : null,
                any ? sorted.get(sorted.size() - 1) : null,
                a.total,
                share,
                tier);
    }

    /** Nearest rank: {@code sorted[ceil(p/100*n) - 1]}. */
    private static long nearestRank(List<Long> sorted, int p) {
        return sorted.get((int) Math.ceil(p / 100.0 * sorted.size()) - 1);
    }

    /** One outcome per instance: its latest-started end-event row (ties: the later row). */
    private static int outcomes(List<List<ActivityInstanceRecord>> ok, List<Outcome> out) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        int noEnd = 0;
        for (List<ActivityInstanceRecord> rows : ok) {
            ActivityInstanceRecord last = null;
            for (ActivityInstanceRecord r : rows) {
                if (r.activityType() != null
                        && r.activityType().toLowerCase(Locale.ROOT).contains("endevent")
                        && (last == null || !started(r).isBefore(started(last)))) {
                    last = r;
                }
            }
            if (last == null) {
                noEnd++;
            } else {
                counts.computeIfAbsent(last.activityId(), k -> new int[1])[0]++;
                names.putIfAbsent(last.activityId(), last.activityName());
            }
        }
        counts.forEach((id, c) -> out.add(new Outcome(id, names.get(id), c[0], c[0] * 100.0 / ok.size())));
        out.sort(Comparator.comparingInt(Outcome::count).reversed().thenComparing(Outcome::endEventId));
        return noEnd;
    }

    private static Instant started(ActivityInstanceRecord r) {
        try {
            return OffsetDateTime.parse(r.startTime()).toInstant();
        } catch (RuntimeException e) {
            return Instant.MIN;
        }
    }
}
