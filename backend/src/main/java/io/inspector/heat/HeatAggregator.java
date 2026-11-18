package io.inspector.heat;

import io.inspector.client.ActivityInstanceRecord;
import io.inspector.dto.DefinitionHeat;
import java.time.Instant;
import java.util.List;

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
            List<Instance> instances) {}

    public static DefinitionHeat aggregate(Sample s) {
        return new DefinitionHeat(
                s.engineId(),
                s.definitionId(),
                s.asOf(),
                false,
                DefinitionHeat.Coverage.FULL,
                0,
                0,
                0,
                0,
                new DefinitionHeat.DropReasons(0, 0),
                null,
                null,
                false,
                List.of(),
                List.of(),
                0,
                "");
    }
}
