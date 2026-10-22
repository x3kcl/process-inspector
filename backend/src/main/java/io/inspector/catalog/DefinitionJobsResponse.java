package io.inspector.catalog;

import java.util.List;

/** Count-only job lanes for one definition ({@code size=1}). A failed lane is a lower bound of 0. */
public record DefinitionJobsResponse(String scope, List<JobLaneCount> lanes) {

    public record JobLaneCount(String lane, long total, boolean lowerBound) {}
}
