package io.inspector.migration;

import java.util.List;

/** Per-item outcomes of a cohort migrate. Nothing here is an engine batch. */
public record CohortExecuteResponse(String engineId, long count, List<CohortItemOutcome> items) {

    public record CohortItemOutcome(String instanceId, String outcome, String detail) {}
}
