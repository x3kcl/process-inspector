package io.inspector.migration;

import java.util.List;

/** Request bodies of the hierarchy plan builder (#497). */
public final class HierarchyPlanRequests {

    private HierarchyPlanRequests() {}

    /**
     * The plan's root(s): exactly one of {@code rootInstanceId} (one running instance) or
     * {@code cohort} (the S2 cohort filter — every running instance it matches is a root, resolved
     * server-side). The tree below is enumerated by the BFF.
     */
    public record HierarchyPlanCreate(String rootInstanceId, CohortPreviewRequest cohort) {}

    /**
     * The decision for one source definition of the plan. {@code skip} leaves its instances on
     * their version. Otherwise the group is configured: {@code toDefinitionId} (null = the latest
     * version of the same key) and the shared {@code mappings}, estimated per instance.
     */
    public record HierarchyPlanDecide(
            String definitionId, Boolean skip, String toDefinitionId, List<MigrationMapping> mappings) {}
}
